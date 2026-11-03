package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.ui.theme.glassContainerColor

/**
 * 弹窗内部滚动区的限高档（UI v2 · 阶段六）。
 *
 * 重构前 `240 / 320 / 340 / 360 / 380.dp` 五个数值散在 6 个文件 9 处，
 * 同一类内容在不同页面限高还不一样。这里收成唯一出处；**数值保持不变**，
 * 先把出处收敛，观感零回归。新增弹窗一律引用这里，不要再写魔法 dp。
 */
object DialogMaxHeight {
    /** 短列表（3~5 项）。 */
    val compact = 240.dp

    /** 中等列表 / 单选清单。 */
    val small = 320.dp

    /** 说明正文 / 帮助向导。 */
    val regular = 340.dp

    /** 长列表 / 书签。 */
    val large = 360.dp

    /** 诊断 / 日志类最长内容。 */
    val xlarge = 380.dp
}

/**
 * 全 App 唯一的底部弹层入口（UI v2 · 阶段六）。
 *
 * ## 为什么要有这一层
 *
 * 重构前 12 个 `ModalBottomSheet` 各自为政：
 *  · 只有 5 个给内容加了 `sheetMaxHeight()`（屏高 70%），其余在平板横屏上会被
 *    顶到底部控制键底下，最后一项点不到（2026-10-22 用户实测）；
 *  · 玻璃底色 `glassContainerColor()` 有的传、有的不传，深浅模式下观感不一致；
 *  · 限高比例散着 0.60 / 0.70 两种，超长时有的内部滚、有的不滚。
 *
 * 现在把这三件事收进一个函数：签名与 `ModalBottomSheet` 的常用参数兼容，
 * 所以调用点只需换个名字就自动获得统一底色与限高，内部结构不必重写。
 *
 * [scrollable] 默认 false：队列 / 歌单这类弹层内部自带 `LazyColumn` 或
 * 自己的 `verticalScroll`，外层再滚一层会和嵌套滚动打架。纯静态内容才置 true。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = glassContainerColor(),
    /** 内容最大高度占屏高比例；列表型弹层可传 0.60f 给底部多留一点。 */
    maxFraction: Float = 0.70f,
    /** 纯静态内容且没有自己的滚动时置 true。 */
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight(maxFraction))
                .then(
                    if (scrollable) Modifier.verticalScroll(rememberScrollState())
                    else Modifier
                ),
            content = content,
        )
    }
}
