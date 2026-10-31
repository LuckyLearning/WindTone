package com.luckylearning.windtone.data.online

import com.luckylearning.windtone.data.UserFacingCopy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.net.ssl.SSLException
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * 在线播放列表的**纯规则**：无网络、无 Context、无协程，可完整单测（2026-10-21 从
 * [OnlinePlaylists] 拆出）。
 *
 * 拆出来的理由与 `LibrarySearch` / `SelectionMath` 同一套：这一层的错误**不抛异常**，
 * 只表现为「地址补错协议头导不进来」「中文电台标题一片问号」「4MB 上限没生效把车机拖住」——
 * 全是用户看得见但说不清原因的故障，必须逐条钉住。
 *
 * 这里只放「给定输入就有确定输出」的判定；带副作用的抓取在 `NetworkPlaylistFetcher`，
 * 编排与持久化在 [OnlinePlaylists]。
 */
internal object OnlinePlaylistRules {

    /**
     * 规范化用户粘贴的地址。
     *
     * 用户从浏览器复制常丢掉协议头（`example.com/list.m3u`），补 https 而不是直接拒绝 ——
     * 拒绝的话用户只会看到「地址格式不对」，不知道怎么修。
     *
     * ⚠️ 域名合法性**显式判定**，不能依赖 `new URL(...)` 的抛错：它的校验行为随
     * JDK 版本漂移（v1.1.37 实锤：`https://a b.com/x` 在 JDK 21 抛 MalformedURLException，
     * JDK 17 静默接受，CI 单测当场红），Android 的 libcore 又是第三套。URL() 只用来
     * 兜底解析出 host，畸形输入在下面就已被拦下。
     */
    fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) error(AppStrings.s(R.string.opr_url_empty).ifEmpty { "地址不能为空" })
        val withScheme = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            else -> "https://$t"
        }
        val hostPart = withScheme.substringAfter("://", "")
            .substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPart.isBlank()) error(AppStrings.s(R.string.opr_url_no_host).ifEmpty { "地址里缺少域名" })
        if (hostPart.count { it == '[' } != hostPart.count { it == ']' }) error(AppStrings.s(R.string.opr_url_bad).ifEmpty { "地址格式不对" })
        val bare = hostPart.substringBeforeLast(':').trim('[', ']') // 剥端口 / IPv6 括号
        if (bare.isBlank()) error(AppStrings.s(R.string.opr_url_no_host).ifEmpty { "地址里缺少域名" })
        if (hostPart.any { it.isWhitespace() }) error(AppStrings.s(R.string.opr_url_bad).ifEmpty { "地址格式不对" })
        val parsed = runCatching { URL(withScheme) }.getOrNull() ?: error(AppStrings.s(R.string.opr_url_bad).ifEmpty { "地址格式不对" })
        if (parsed.host.isNullOrBlank()) error(AppStrings.s(R.string.opr_url_no_host).ifEmpty { "地址里缺少域名" })
        return withScheme
    }

    /**
     * 没填名称时的默认名。
     *
     * `guessTitle` 取的是路径末段，但大量清单的文件名就叫 `list.m3u` / `index.m3u` ——
     * 直接用它会让列表里出现好几个「list」，没法分辨。这类通用名退化成域名。
     */
    fun defaultName(url: String): String {
        val base = M3uParser.guessTitle(url)
        val generic = base.equals("list", true) || base.equals("index", true) ||
            base.equals("playlist", true) || base.equals("default", true)
        if (!generic) return base
        return M3uParser.originOf(url)?.substringAfter("://")?.ifBlank { null } ?: base
    }

    /**
     * 地址 → MIME。按查询串 / 片段之前、最后一个 `.` 之后的扩展名判。
     *
     * `?` 与 `#` 都要剥：`?` 后面常带鉴权 token，`#` 后面可能是页内锚点，
     * 两者都可能出现在扩展名之后（`a.flac?token=1` / `a.mp3#t=10`）。
     * 与 [M3uParser.guessTitle] 的剥法保持一致，否则同一个地址会在两处得到不同结论。
     *
     * 未知扩展名返回 null 而不是猜一个：播不动的格式由播放器报错，比这里瞎猜更诚实。
     */
    fun mimeOf(url: String): String? =
        when (url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "m3u8" -> "application/x-mpegURL"
        else -> null
    }

    /**
     * 抓回来的是不是网页。
     *
     * 用户最常犯的错就是把播放页地址贴进来；不拦这一下，界面上只会显示
     * 「内容里没有任何可播放地址」，用户完全不知道错在哪。
     */
    fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().take(200).lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") ||
            (head.contains("<body") && !head.contains("#extm3u"))
    }

    /**
     * 读响应体，超过上限即停。
     *
     * 上限 4MB 是防呆：有人会把整个 IPTV 频道表（几万行）当音乐列表填进来，
     * 不设限的话解析和落盘都会把车机拖住。
     */
    fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_BYTES) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 编码判定：先按 UTF-8 严格解码，失败再按 GB18030。
     *
     * 中文电台列表里有大量 GBK 文件，按 UTF-8 硬解会得到一片问号，
     * 而标题乱码比「没导入」更难排查（用户只会说「词都不认识」）。
     */
    fun decode(bytes: ByteArray): String {
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        val gb = runCatching { String(bytes, Charset.forName("GB18030")) }.getOrNull()
        return gb ?: String(bytes, Charsets.UTF_8)
    }

    /**
     * 网络异常 → 用户能懂的话。
     *
     * 直接把异常 message 抛给界面会出现「connection closed」「Connection refused」这种
     * 用户读不懂的英文；这里换成能指导动作的说法（换网 / 检查地址 / 对方没响应）。
     */
    fun friendly(e: IOException): String = when (e) {
        is UnknownHostException ->
            UserFacingCopy.withNext(
                AppStrings.s(R.string.opr_dns).ifEmpty { "解析不到这个域名（网络不可用，或地址写错了）" },
                AppStrings.s(R.string.opr_dns_next).ifEmpty { "检查网络后重试，或核对歌单地址是否写对" },
            )
        is SocketTimeoutException ->
            UserFacingCopy.withNext(
                AppStrings.s(R.string.opr_timeout).ifEmpty { "连接超时：对方没有响应，或当前网络太慢" },
                AppStrings.s(R.string.opr_timeout_next).ifEmpty { "换网络后重试，或稍后再刷新该歌单" },
            )
        is SSLException ->
            UserFacingCopy.withNext(
                AppStrings.s(R.string.opr_ssl).ifEmpty { "HTTPS 握手失败：对方的证书不受信任" },
                AppStrings.s(R.string.opr_ssl_next).ifEmpty { "核对系统时间是否准确，或换用可信证书的 HTTPS 地址" },
            )
        else ->
            UserFacingCopy.withNext(
                AppStrings.s(
                    R.string.opr_connect,
                    e.message ?: e.javaClass.simpleName,
                ).ifEmpty { "连不上这个地址（${e.message ?: e.javaClass.simpleName}）" },
                AppStrings.s(R.string.opr_connect_next).ifEmpty { "检查网络后重试；确认地址可在浏览器打开" },
            )
    }

    /** 响应体上限：防「把整个 IPTV 频道表当音乐列表填进来」 */
    const val MAX_BYTES = 4 * 1024 * 1024
}
