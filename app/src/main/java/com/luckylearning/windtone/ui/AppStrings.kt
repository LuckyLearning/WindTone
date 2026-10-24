package com.luckylearning.windtone.ui

import android.content.Context
import androidx.annotation.StringRes
import com.luckylearning.windtone.MusicApp
import java.util.Locale

/**
 * 非组合上下文（数据层 / 服务 / 通知 / 枚举）取字符串的唯一入口。
 *
 * 为什么需要它：Compose 的 `stringResource` 只能在 `@Composable` 里调，
 * 而播放错误、扫描进度、通知标题这些文案产生在普通函数里。
 * 让这些函数改成返回资源 id 会牵动大量签名（且带参数的文案还得额外造载体类型），
 * 收益不抵风险，所以统一走这里的应用级 Context。
 *
 * 生命周期：Context 用的是 `applicationContext`，不会泄漏 Activity。
 * 在 [MusicApp.onCreate] 里初始化；测试环境（Robolectric）由 Application 自动带上。
 *
 * 兜底：万一在被初始化之前就被调用（例如静态初始化器），返回空串而不是崩溃 ——
 * 文案缺失是可以容忍的降级，进程被杀不是。
 */
object AppStrings {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 取串；[args] 会按 String.format 规则替换 %1$s 这类位置参数 */
    fun s(@StringRes id: Int, vararg args: Any): String {
        val ctx = appContext ?: return ""
        return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    }

    /**
     * 大小写不敏感的场景（个别语言存在双参与格变位）用这个包一层，
     * 目前仅用于需要与旧文案逐字对齐的地方，避免大小写规则差异带来的回归。
     */
    fun sLower(@StringRes id: Int, vararg args: Any): String =
        s(id, *args).lowercase(Locale.getDefault())
}
