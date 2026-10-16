package com.luckylearning.windtone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.state.LibraryView

/**
 * 侧边抽屉（v1.1.3 重构：替代原底部五视图导航）。
 *
 * 视觉规格逐条对应 `docs/ui/index.html` 的 ①C 屏与 `.drawer / .d-*` 样式：
 *  - 宽 **280dp**；右缘圆角 **28dp**（`--r-xl`，与底部弹层同一档），左缘贴边不圆角
 *    → `RoundedCornerShape(topEnd / bottomEnd)`；
 *  - 滑入 **220ms**（效果图 `transition:transform .22s ease`），收起时滑出屏幕左侧；
 *  - 遮罩 `--dim`（深色 .62 / 浅色 .34）**盖住下层内容并吃掉触摸**，点它收起；
 *  - 品牌块 = 44dp 圆形 halo（primaryContainer）+「听风」16sp/800 +「WindTone · 本地音乐」11sp；
 *  - 两组：**音乐库**（原底部导航的五个浏览视图）/ **更多**（扫描范围 · 设置）；
 *  - 选中项：secondaryContainer 实底胶囊 + 加粗 + onSecondaryContainer 文字。
 *
 * ⚠️ 抽屉盖在迷你播放条之上、但不盖播放页 / 设置页这类全屏覆盖层 ——
 * 那些页面在效果图里各有自己的返回按钮，没有汉堡入口（见 MainScreen 里的挂载位置）。
 *
 * 交互取舍：效果图 ①C 的说明里「切换视图不关闭抽屉」与括号里的「点视图即切换并收起」
 * 互相矛盾；这里按括号内（也是抽屉导航的通行做法、且「更多」各项必须收起才看得到目的地）
 * 实现为**切换 + 收起**。
 *
 * 入口收敛（2026-10-17）：本抽屉是**设置在本应用里的唯一入口**（顶栏「⋮」菜单不再挂）；
 * 「检查更新」也只剩设置 → 更新里那一处（原先抽屉、顶栏菜单各挂一份）。
 */
@Composable
internal fun LibraryDrawer(
    open: Boolean,
    currentView: LibraryView,
    onSelectView: (LibraryView) -> Unit,
    onOpenScanScope: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- 遮罩
        // 深色 .62 / 浅色 .34（效果图 --dim 两套主题各一个值）。
        // 用当前配色方案的亮度反推明暗，而不是 isSystemInDarkTheme —— 主题可由
        // 封面取色派生，那时候「系统是不是深色」跟实际画出来的是不是深色未必一致。
        val darkScrim = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val scrimColor = Color.Black.copy(alpha = if (darkScrim) 0.62f else 0.34f)
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(220)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrimColor)
                    // indication = null：遮罩是"挡板"不是按钮，不要点出涟漪
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    )
            )
        }

        // ---------------------------------------------------------------- 抽屉本体
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(animationSpec = tween(220)) { -it },
            exit = slideOutHorizontally(animationSpec = tween(220)) { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            DrawerPanel(
                currentView = currentView,
                onSelectView = onSelectView,
                onOpenScanScope = onOpenScanScope,
                onOpenSettings = onOpenSettings,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun DrawerPanel(
    currentView: LibraryView,
    onSelectView: (LibraryView) -> Unit,
    onOpenScanScope: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        // --c-low（surface container low）比页面底色亮一档，抽屉才浮得起来
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        // box-shadow:10px 0 36px rgba(0,0,0,.42)
        shadowElevation = 10.dp,
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 16.dp),
        ) {
            DrawerBrand()
            DrawerGroupLabel("音乐库")
            LibraryView.entries.forEach { view ->
                DrawerItem(
                    label = view.label,
                    icon = view.icon,
                    selected = view == currentView,
                    onClick = { onSelectView(view); onClose() },
                )
            }

            // .d-gap{flex:1} —— 把「更多」组压到底部
            Spacer(Modifier.weight(1f))

            DrawerGroupLabel("更多")
            DrawerItem("扫描范围", AppIcons.Folder, false) { onOpenScanScope(); onClose() }
            DrawerItem("设置", AppIcons.Tune, false) { onOpenSettings(); onClose() }
        }
    }
}

/** 品牌块：44dp 圆形 halo + 中英双行标题（效果图 `.d-brand / .d-halo / .d-t / .d-s`） */
@Composable
private fun DrawerBrand() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "听风",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "WindTone · 本地音乐",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** 组标题：11sp / 700 / primary（效果图 `.d-label`） */
@Composable
private fun DrawerGroupLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** 抽屉项：胶囊底、22dp 图标、14sp 文字（效果图 `.d-item`，选中态 `.d-item.on`） */
@Composable
private fun DrawerItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onBackground
            },
        )
    }
}
