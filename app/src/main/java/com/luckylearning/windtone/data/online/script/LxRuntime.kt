package com.luckylearning.windtone.data.online.script

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 脚本 init 声明的一族能力 */
data class LxDeclaredSource(
    val id: String,
    val actions: Set<String>,
    val qualities: List<String>,
)

data class LxInitResult(
    val ok: Boolean,
    val sources: List<LxDeclaredSource> = emptyList(),
    val error: String? = null,
)

/**
 * 单个洛雪 v2 脚本的 QuickJS 宿主。
 *
 * ## 这一层必须与洛雪**逐字对齐**，不允许自创协议
 *
 * 自定义源的 JS 侧完全由 `assets/lx/preload.js` 决定（该文件直接从洛雪
 * `android/app/src/main/assets/script/user-api-preload.js` 原样拷贝）。宿主侧要做的只有三件事：
 *
 *  1. 注入 preload 期望的 7 个原生全局函数
 *     （`__lx_native_call__` + 6 个 `__lx_native_call__utils_*` / `_set_timeout`）；
 *  2. `evaluate(preload)` → `lx_setup(...)` → `evaluate(脚本)`，与
 *     `cn.toside.music.mobile.userApi.QuickJS#createJSEnv` 顺序一致；
 *  3. 双向调用都走 `__lx_native__` / `__lx_native_call__` 这一对，消息体是 **JSON 文本**。
 *
 * 历史教训（v1.2.61 的第一版在线功能）：自己写了一版精简 preload，且把
 * 「宿主 → 脚本」的请求体少包了一层 `data`（洛雪是 `{requestKey, data:{source,action,info}}`），
 * 结果脚本里 `info` 永远是 undefined —— 表现就是「搜索能出结果、一首都播不了」。
 * 现在改用原版 preload + 原版报文，语义上不再有自己的发明。
 *
 * ## 线程约定
 *
 * `QuickJSContext` 不是线程安全的，所有 evaluate / call 都在专用 HandlerThread 上执行；
 * 调用方只投递 + 等 Deferred。HTTP 请求由 OkHttp 异步发出，响应再 post 回 JS 线程回灌。
 */
class LxRuntime(
    private val context: Context,
    private val script: LxScript,
) {
    private val key: String = UUID.randomUUID().toString()
    private val thread = HandlerThread("windtone-lx-${script.id.take(16)}").apply { start() }
    private val handler = Handler(thread.looper)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var js: QuickJSContext? = null

    @Volatile
    private var initResult: LxInitResult? = null

    /** `lx.send('inited')` 到达即释放；脚本即使 init 是异步的也不会白等满超时 */
    private val initLatch = CountDownLatch(1)

    /**
     * 脚本案（musicUrl / lyric / pic）的等待者，按 requestKey 关联。
     *
     * 用 ConcurrentHashMap 而不是普通 MutableMap：写入发生在 JS 线程（调用方投递前
     * 其实在主线程），完成发生在 JS 线程的 `response` 回调里，两边线程不同。
     */
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** 脚本发起的 HTTP 请求，用于 cancelRequest */
    private val httpCalls = ConcurrentHashMap<String, Call>()

    @Volatile
    private var destroyed = false

    /**
     * 加载脚本并等到 `lx.send('inited')`。
     *
     * 分两段等，是为了避免「脚本其实早就 init 好了，却还要干等满超时」：
     * evaluate 返回后先看有没有拿到 inited（绝大多数源是同步 send），
     * 没拿到才按异步 init 再多等 6 秒。
     */
    fun load(): LxInitResult {
        val ref = AtomicReference<LxInitResult>()
        val evaluated = CountDownLatch(1)
        post {
            try {
                QuickJSLoader.init()
                val ctx = QuickJSContext.create()
                js = ctx
                ctx.setConsole(ConsoleBridge(script.name))
                createEnvObj(ctx)
                val preload = context.assets.open("lx/preload.js")
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                ctx.evaluate(preload)
                ctx.getGlobalObject().getJSFunction("lx_setup").call(
                    key,
                    script.id,
                    script.name,
                    script.description,
                    script.version,
                    script.author,
                    script.homepage,
                    script.code,
                )
                try {
                    ctx.evaluate(script.code)
                } catch (t: Throwable) {
                    // 与洛雪一致：脚本抛错时通知 preload 标记已 init，再按「有没有
                    // 收到 inited」判定成败 —— 有些源会在末尾抛一个无关异常但已 init 成功
                    Log.w(TAG, "脚本执行异常 ${script.name}: ${t.message}")
                    runCatching { callJs("__run_error__", null) }
                    if (initResult == null) {
                        ref.set(LxInitResult(false, error = t.message ?: t.javaClass.simpleName))
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "load ${script.name}: ${t.message}")
                ref.set(LxInitResult(false, error = t.message ?: t.javaClass.simpleName))
            } finally {
                evaluated.countDown()
            }
        }
        evaluated.await(20, TimeUnit.SECONDS)
        ref.get()?.let { return it }
        initResult?.let { return it }
        // 脚本的 init 是异步的（先发请求再 send）：再给 6 秒
        if (initLatch.await(6, TimeUnit.SECONDS)) {
            initResult?.let { return it }
        }
        return LxInitResult(false, error = "脚本未调用 lx.send('inited')")
    }

    /**
     * 让脚本处理一次宿主动作。
     *
     * 报文形状严格照洛雪 `core/init/userApi/index.ts`：
     * ```
     * { requestKey, data: { source, action, info } }
     * ```
     * `info` 由调用方负责（musicUrl 是 `{ type, musicInfo }`，见 [LxActionInfo]）。
     *
     * @return 脚本返回的 url / 歌词文本；失败返回 null
     */
    suspend fun invoke(source: String, action: String, info: JSONObject): String? {
        val deferred = CompletableDeferred<String?>()
        val requestKey = "u" + UUID.randomUUID().toString().replace("-", "")
        waiters[requestKey] = deferred
        post {
            callJs("request", LxRequestEnvelope.build(requestKey, source, action, info).toString())
        }
        return try {
            withTimeout(20_000L) { deferred.await() }
        } catch (t: Throwable) {
            null
        } finally {
            waiters.remove(requestKey)
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        httpCalls.values.forEach { runCatching { it.cancel() } }
        httpCalls.clear()
        handler.post {
            runCatching { js?.destroy() }
            js = null
        }
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- 环境

    /**
     * 注入 preload 期望的原生函数。**名字和返回值形状都不能改**：
     * preload 里 `nativeFuncs[name.replace('__lx_native_call__','')]` 直接按名取，
     * 少一个就会在脚本调用 `lx.utils.crypto.md5()` 时抛 TypeError。
     */
    private fun createEnvObj(ctx: QuickJSContext) {
        val global = ctx.getGlobalObject()
        global.setProperty("__lx_native_call__") { args: Array<Any?> ->
            if (args.size >= 3 && key == args[0]) {
                onNativeCall(args[1] as? String, args[2] as? String)
            }
            null
        }
        global.setProperty("__lx_native_call__utils_str2b64") { args: Array<Any?> ->
            runCatching {
                Base64.encodeToString(
                    (args.getOrNull(0) as? String ?: "").toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
            }.getOrDefault("")
        }
        global.setProperty("__lx_native_call__utils_b642buf") { args: Array<Any?> ->
            runCatching {
                val bytes = Base64.decode(args.getOrNull(0) as? String ?: "", Base64.DEFAULT)
                val sb = StringBuilder("[")
                for (i in bytes.indices) {
                    if (i > 0) sb.append(',')
                    sb.append(bytes[i].toInt())
                }
                sb.append(']').toString()
            }.getOrDefault("")
        }
        global.setProperty("__lx_native_call__utils_str2md5") { args: Array<Any?> ->
            runCatching {
                // preload 传进来的是 encodeURIComponent 后的串，必须先解码
                val text = URLDecoder.decode(args.getOrNull(0) as? String ?: "", "UTF-8")
                shaHex("MD5", text.toByteArray(Charsets.UTF_8))
            }.getOrDefault("")
        }
        global.setProperty("__lx_native_call__utils_aes_encrypt") { args: Array<Any?> ->
            runCatching {
                aesEncrypt(
                    data = base64Decode(args.getOrNull(0) as? String),
                    key = base64Decode(args.getOrNull(1) as? String),
                    iv = base64Decode(args.getOrNull(2) as? String),
                    mode = args.getOrNull(3) as? String ?: "AES",
                )
            }.getOrDefault("")
        }
        global.setProperty("__lx_native_call__utils_rsa_encrypt") { args: Array<Any?> ->
            runCatching {
                rsaEncrypt(
                    data = base64Decode(args.getOrNull(0) as? String),
                    publicKeyBase64 = args.getOrNull(1) as? String ?: "",
                    padding = args.getOrNull(3) as? String ?: "RSA/ECB/NoPadding",
                )
            }.getOrDefault("")
        }
        global.setProperty("__lx_native_call__set_timeout") { args: Array<Any?> ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@setProperty null
            val delay = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
            // preload 的 callbacks 用 Number 作键，所以回灌时也必须是数字字面量
            handler.postDelayed({ callJs("__set_timeout__", id.toString()) }, delay)
            null
        }
    }

    /** JS → 宿主。运行在 JS 线程。 */
    private fun onNativeCall(action: String?, raw: String?) {
        if (action == null) return
        val data = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        when (action) {
            "init" -> {
                val ok = data?.optBoolean("status") == true
                val sources = mutableListOf<LxDeclaredSource>()
                if (ok) {
                    val obj = data?.optJSONObject("info")?.optJSONObject("sources")
                    if (obj != null) {
                        for (sid in obj.keys()) {
                            val s = obj.optJSONObject(sid) ?: continue
                            if (s.optString("type") != "music") continue
                            val actions = s.optJSONArray("actions")?.let { arr ->
                                (0 until arr.length()).map { arr.optString(it) }.toSet()
                            } ?: emptySet()
                            val qualities = s.optJSONArray("qualitys")?.let { arr ->
                                (0 until arr.length()).map { arr.optString(it) }
                            } ?: emptyList()
                            sources += LxDeclaredSource(sid, actions, qualities)
                        }
                    }
                }
                initResult = LxInitResult(
                    ok = ok,
                    sources = sources,
                    error = data?.optString("errorMessage")?.takeIf { it.isNotBlank() },
                )
                initLatch.countDown()
            }

            "request" -> {
                val requestKey = data?.optString("requestKey").orEmpty()
                val url = data?.optString("url").orEmpty()
                if (requestKey.isBlank() || url.isBlank()) return
                startHttpRequest(requestKey, url, data?.optJSONObject("options"))
            }

            "cancelRequest" -> {
                // preload 把 requestKey 直接 stringify，所以这里是 JSON 标量而不是对象
                val rk = raw?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                rk?.let { httpCalls.remove(it)?.cancel() }
            }

            "response" -> {
                val rk = data?.optString("requestKey").orEmpty()
                val waiter = waiters.remove(rk) ?: return
                if (data?.optBoolean("status") == true) {
                    waiter.complete(extractResult(data.optJSONObject("result")))
                } else {
                    waiter.complete(null)
                }
            }

            "showUpdateAlert" -> {
                Log.i(TAG, "[${script.name}] 源有更新：${data?.optString("log")?.take(120)}")
            }

            else -> Log.d(TAG, "未处理的脚本动作：$action")
        }
    }

    /**
     * preload 回传的 `result` 形状：
     * - `musicUrl` → `{ data: { type, url } }`
     * - `pic`      → `{ data: "<url>" }`
     * - `lyric`    → `{ data: { lyric, tlyric, rlyric, lxlyric } }`
     */
    private fun extractResult(result: JSONObject?): String? {
        if (result == null) return null
        return when (result.optString("action")) {
            "musicUrl" -> result.optJSONObject("data")?.optString("url")
            "pic" -> result.opt("data") as? String
            "lyric" -> result.optJSONObject("data")?.optString("lyric")
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    /** 宿主 → 脚本。只可在 JS 线程调用；[payload] 必须是合法 JSON 文本。 */
    private fun callJs(action: String, payload: String?) {
        val fn = js?.globalObject?.getJSFunction("__lx_native__") ?: return
        runCatching {
            if (payload == null) fn.call(key, action) else fn.call(key, action, payload)
        }.onFailure { Log.w(TAG, "调用脚本失败 $action: ${it.message}") }
    }

    private fun post(block: () -> Unit) {
        if (destroyed) return
        handler.post { if (!destroyed) runCatching(block) }
    }

    // ---------------------------------------------------------------- HTTP

    /**
     * 脚本发起的请求，语义照洛雪 `core/init/userApi/request.js`：
     *
     * - 默认头 `User-Agent: <桌面 Chrome>` + `Accept: application/json`（脚本显式给的同名头覆盖默认）；
     * - POST 且未指定 Content-Type：有 `form` → urlencoded，有 `formData` → multipart，否则 JSON；
     * - Content-Type 为 JSON 且有 body → 序列化成 JSON 文本；
     * - **响应 body 先尝试 `JSON.parse`，能解析就给对象** —— 这是脚本里
     *   `body.code === 0` 能成立的前提，给纯文本会让所有源静默失败；
     * - `binary=true` 时给字节数组（preload 的 `utils.buffer.bufToString` 接受数组）。
     */
    private fun startHttpRequest(requestKey: String, url: String, options: JSONObject?) {
        if (!url.startsWith("http", ignoreCase = true)) {
            respondError(requestKey, "unsupported url scheme")
            return
        }
        val headers = options?.optJSONObject("headers")?.let { JSONObject(it.toString()) }
            ?: JSONObject()
        if (!headers.has("Accept")) headers.put("Accept", "application/json")

        var method = options?.optString("method").orEmpty().ifBlank { "get" }.uppercase()
        var contentType = if (headers.has("Content-Type")) headers.optString("Content-Type") else null
        val form = options?.optJSONObject("form")
        val formData = options?.optJSONObject("formData")
        var bodyText: String? = null

        if (method == "POST" && contentType == null) {
            when {
                form != null -> {
                    contentType = "application/x-www-form-urlencoded"
                    bodyText = encodeForm(form)
                }
                formData != null -> {
                    contentType = "multipart/form-data"
                }
                else -> contentType = "application/json"
            }
        }
        if (contentType != null && !headers.has("Content-Type")) headers.put("Content-Type", contentType)
        val rawBody = options?.opt("body")
        if (contentType?.startsWith("application/json") == true &&
            rawBody != null && rawBody != JSONObject.NULL
        ) {
            bodyText = if (rawBody is String) JSONObject.quote(rawBody) else rawBody.toString()
        }

        val builder = Request.Builder().url(url)
        for (name in headers.keys()) {
            runCatching { builder.addHeader(name, headers.optString(name)) }
        }
        val requestBody: RequestBody? = when {
            formData != null && contentType?.startsWith("multipart") == true -> {
                val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                for (name in formData.keys()) {
                    mp.addFormDataPart(name, formData.optString(name))
                }
                mp.build()
            }
            bodyText != null -> bodyText.toRequestBody(contentType?.toMediaTypeOrNull())
            method in setOf("POST", "PUT", "PATCH", "DELETE") ->
                ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
            else -> null
        }
        builder.method(method, requestBody)

        val call = client.newCall(builder.build())
        httpCalls[requestKey] = call
        val timeoutMs = options?.optLong("timeout")?.takeIf { it in 1..60_000 } ?: 13_000L
        runCatching { call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS) }
        val binary = options?.optBoolean("binary") == true

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                httpCalls.remove(requestKey)
                post { respondError(requestKey, e.message ?: "network error") }
            }

            override fun onResponse(call: Call, response: Response) {
                httpCalls.remove(requestKey)
                val payload = runCatching {
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    val bodyJson: Any = if (binary) {
                        JSONArray().apply { bytes.forEach { put(it.toInt()) } }
                    } else {
                        val text = bytes.toString(Charsets.UTF_8)
                        // 与洛雪一致：能 JSON.parse 就给对象，不能就给原始字符串
                        runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: text
                    }
                    val headersJson = JSONObject()
                    response.headers.forEach { (k, v) ->
                        runCatching { if (!headersJson.has(k)) headersJson.put(k, v) }
                    }
                    JSONObject()
                        .put("statusCode", response.code)
                        .put("statusMessage", response.message)
                        .put("headers", headersJson)
                        .put("body", bodyJson)
                        .put("url", response.request.url.toString())
                        .put("ok", response.isSuccessful)
                }.getOrElse { e ->
                    post { respondError(requestKey, e.message ?: "read response failed") }
                    return
                }
                post {
                    callJs(
                        "response",
                        JSONObject().put("requestKey", requestKey).put("response", payload).toString(),
                    )
                }
            }
        })
    }

    private fun respondError(requestKey: String, message: String) {
        callJs(
            "response",
            JSONObject().put("requestKey", requestKey).put("error", message).toString(),
        )
    }

    /** 表单编码用 `encodeURIComponent` 语义（空格是 %20，不是 URLEncoder 的 +） */
    private fun encodeForm(form: JSONObject): String {
        val sb = StringBuilder()
        for (name in form.keys()) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
                .append('=')
                .append(URLEncoder.encode(form.optString(name), "UTF-8").replace("+", "%20"))
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 加密

    private fun base64Decode(text: String?): ByteArray =
        if (text.isNullOrEmpty()) ByteArray(0) else Base64.decode(text, Base64.DEFAULT)

    private fun shaHex(algo: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algo).digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 照洛雪 `crypto/AES.encrypt(String data, String key, String iv, String mode)`：
     * 入参都是 base64，`iv` 为空串走 ECB（`AES` = AES/ECB/NoPadding），否则 CBC + PKCS7。
     */
    private fun aesEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray, mode: String): String {
        val cipher = Cipher.getInstance(mode)
        val secret = SecretKeySpec(key, "AES")
        if (iv.isEmpty()) {
            cipher.init(Cipher.ENCRYPT_MODE, secret)
        } else {
            val finalIv = ByteArray(16)
            System.arraycopy(iv, 0, finalIv, 0, minOf(iv.size, 16))
            cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(finalIv))
        }
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    /** 照洛雪 `crypto/RSA.encryptRSAToString`：公钥是去掉 PEM 头的 base64，入参也是 base64 */
    private fun rsaEncrypt(data: ByteArray, publicKeyBase64: String, padding: String): String {
        val spec = java.security.spec.X509EncodedKeySpec(
            Base64.decode(publicKeyBase64.trim(), Base64.DEFAULT),
        )
        val publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance(padding)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    /** 脚本里的 console.* 落到 logcat，前缀带脚本名，方便排查「某个源为什么安静失败」 */
    private class ConsoleBridge(private val scriptName: String) : QuickJSContext.Console {
        override fun log(info: String?) {
            Log.d(TAG, "[$scriptName] $info")
        }

        override fun info(info: String?) {
            Log.i(TAG, "[$scriptName] $info")
        }

        override fun warn(info: String?) {
            Log.w(TAG, "[$scriptName] $info")
        }

        override fun error(info: String?) {
            Log.e(TAG, "[$scriptName] $info")
        }
    }

    companion object {
        private const val TAG = "LxRuntime"
    }
}
