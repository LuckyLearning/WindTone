package com.luckylearning.windtone.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 少量自定义图标（Material 核心图标库里没有的）。
 * 直接复用 Material Symbols 的路径数据，避免引入体积庞大的 icons-extended。
 */
object AppIcons {

    private fun build(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()

    // ⚠️ 三个播放控制图标一律用**绝对坐标**、多子路径用「Z M」分开写（大写 M）。
    // 最初沿用的 Material 写法里子路径开头是小写相对 m（如 `zM3.5 6…`），
    // 比亚迪车机的渲染链（HWUI/GPU 驱动路径合成）把这种「closepath 后接相对 moveto」
    // 的多子路径画成了扭曲残缺的形状（上一曲在真机上乱码，2026-10-18 用户上报），
    // 同语法的 SkipNext 却正常 —— 手机端无差别。改写为全绝对坐标后形状逐点等价。
    val Pause: ImageVector by lazy { build("Pause", "M6 19L10 19L10 5L6 5Z M14 5L18 5L18 19L14 19Z") }

    val SkipNext: ImageVector by lazy { build("SkipNext", "M6 18L14.5 12L6 6Z M16 6L18 6L18 18L16 18Z") }

    val SkipPrevious: ImageVector by lazy { build("SkipPrevious", "M6 6L8 6L8 18L6 18Z M9.5 12L18 18L18 6Z") }

    val Shuffle: ImageVector by lazy {
        build(
            "Shuffle",
            "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z",
        )
    }

    /** 顺序播放（播完即止，不循环）—— 单向箭头语义上表示“一路往下播” */
    val Sequential: ImageVector by lazy {
        build("Sequential", "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z")
    }

    val Repeat: ImageVector by lazy {
        build(
            "Repeat",
            "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z",
        )
    }

    val RepeatOne: ImageVector by lazy {
        build(
            "RepeatOne",
            "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z",
        )
    }

    val MusicNote: ImageVector by lazy {
        build("MusicNote", "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z")
    }

    val Folder: ImageVector by lazy {
        build(
            "Folder",
            "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z",
        )
    }

    val QueueMusic: ImageVector by lazy {
        build(
            "QueueMusic",
            "M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z",
        )
    }

    val Lyrics: ImageVector by lazy {
        build("Lyrics", "M15 15H3v2h12v-2zm0-8H3v2h12V7zM3 13h18v-2H3v2zm0 8h18v-2H3v2zM3 3v2h18V3H3z")
    }

    val Sort: ImageVector by lazy { build("Sort", "M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z") }

    val Speed: ImageVector by lazy {
        build("Speed", "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z")
    }

    val PlaylistAdd: ImageVector by lazy {
        build("PlaylistAdd", "M14 10H2v2h12v-2zm0-4H2v2h12V6zm4 8v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM2 16h8v-2H2v2z")
    }

    val Tune: ImageVector by lazy {
        build("Tune", "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z")
    }

    /**
     * 麦克风（搜索框语音听写入口）。
     *
     * ⚠️ 第二个子路径刻意写成 `Z M17 11`（大写 M 绝对移动）——Material 原写法是
     * `zM17 11`（closepath 后接小写相对 moveto），比亚迪车机会把它画成残缺形状
     * （与 2026-10-18 那批播放控制图标同一个坑，见文件顶部说明）。
     */
    val Mic: ImageVector by lazy {
        build(
            "Mic",
            "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z " +
                "M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z",
        )
    }

    val Album: ImageVector by lazy {
        build(
            "Album",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z",
        )
    }

    val Artist: ImageVector by lazy {
        build(
            "Artist",
            "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
        )
    }

    /** 合辑艺人 / Album Artist 浏览（G2）——多人叠影 */
    val AlbumArtist: ImageVector by lazy {
        build(
            "AlbumArtist",
            "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z",
        )
    }

    /** 流派浏览（G1） */
    val Genre: ImageVector by lazy {
        build(
            "Genre",
            "M12 2l2.4 7.2H22l-6 4.4 2.3 7.2L12 16.8 5.7 20.8 8 13.6 2 9.2h7.6z",
        )
    }

    /** 年代浏览（G1） */
    val Year: ImageVector by lazy {
        build(
            "Year",
            "M19 4h-1V2h-2v2H8V2H6v2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V10h14v10zm0-12H5V6h14v2z",
        )
    }

    val LibraryMusic: ImageVector by lazy {
        build(
            "LibraryMusic",
            "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 5h-3v5.5c0 1.38-1.12 2.5-2.5 2.5S10 13.88 10 12.5s1.12-2.5 2.5-2.5c.57 0 1.08.19 1.5.51V5h4v2zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z",
        )
    }

    val VolumeUp: ImageVector by lazy {
        build(
            "VolumeUp",
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
        )
    }

    val SpeedAlt: ImageVector by lazy {
        build(
            "SpeedAlt",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
        )
    }

    val Bedtime: ImageVector by lazy {
        build(
            "Bedtime",
            "M12.34 2.02C6.59 1.82 2 6.42 2 12c0 5.52 4.48 10 10 10 3.71 0 6.93-2.02 8.66-5.02-7.51-.25-12.09-8.43-8.32-14.96z",
        )
    }

    val Edit: ImageVector by lazy {
        build(
            "Edit",
            "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
        )
    }

    /** 已收藏（实心）。播放页常驻歌名行的红心。 */
    val Favorite: ImageVector by lazy {
        build(
            "Favorite",
            "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
        )
    }

    /** 未收藏（空心）。 */
    val FavoriteBorder: ImageVector by lazy {
        build(
            "FavoriteBorder",
            "M16.5 3c-1.74 0-3.41.81-4.5 2.09C10.91 3.81 9.24 3 7.5 3 4.42 3 2 5.42 2 8.5c0 3.78 3.4 6.86 8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 16.5 3zm-4.4 15.55l-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05z",
        )
    }

    /** 信息（音频信息等详情入口）。 */
    val Info: ImageVector by lazy {
        build(
            "Info",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
        )
    }

    /** 分享（系统分享面板入口，N1）。 */
    val Share: ImageVector by lazy {
        build(
            "Share",
            "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92c0-1.61-1.31-2.92-2.92-2.92zM18 4c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zM6 13c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm12 7.02c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z",
        )
    }

    val Bookmark: ImageVector by lazy {
        build(
            "Bookmark",
            "M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2z",
        )
    }

    /**
     * 右箭头：只用在「点开会进入另一个界面」的行上（设置页的子页入口、
     * 打开弹层/弹窗的行）。就地执行动作或就地展开的行不加，避免满屏箭头。
     * 规范见 docs/ui/index.html 与 docs/AI开发指南.md。
     */
    val ChevronRight: ImageVector by lazy {
        build("ChevronRight", "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z")
    }

    /** 拖动排序手柄（两条横杠） */
    val DragHandle: ImageVector by lazy {
        build("DragHandle", "M20 9H4v2h16V9zM4 15h16v-2H4v2z")
    }

    /** 「移出曲库」用的眼睛关闭图标（移出 = 曲库里不再显示它） */
    val VisibilityOff: ImageVector by lazy {
        build(
            "VisibilityOff",
            "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 " +
                "3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 " +
                "11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 " +
                "7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 " +
                "9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 " +
                "1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 " +
                "3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z",
        )
    }

    /** 「恢复/放回曲库」用的眼睛图标 */
    val Visibility: ImageVector by lazy {
        build("Visibility", "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z")
    }

    /** 「重复歌曲清理」用的图标（两枚叠放的方块） */
    val ContentCopy: ImageVector by lazy {
        build("ContentCopy", "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z")
    }

    /** 「收听报告」用的柱状图图标 */
    val BarChart: ImageVector by lazy {
        build("BarChart", "M5 9.2h3V19H5V9.2zM10.6 5h2.8v14h-2.8V5zm5.6 8H19v6h-2.8v-6z")
    }

    // ================================================================ v1.1.3：抽屉与设置分类卡
    // 路径逐字取自 docs/ui/index.html 的 <symbol>，改图标前先改效果图（UI 唯一视觉真源）。
    //
    // ⚠️ **弧线命令的 flag 必须与相邻数字用空格隔开**，不能连写。
    // 效果图（浏览器）里写成 `a7.5 7.5 0 00-14.3-1.6` 是合法的 —— SVG 规范规定
    // large-arc / sweep 两个 flag 各只占**一个字符**，所以 `00` = 两个 flag。
    // 但 Compose 的 `PathParser` 是按数字贪婪读取的，`00` 会被当成**一个数字**，
    // 于是 arc 的 7 个参数少读两个，**后续整条路径被丢弃且不报任何错**。
    // 实证（2026-10-15）：`Cloud` 连写时只解析出 3 个 PathNode（补空格后 6 个），
    // 画出来几乎是空白 —— 用户看到的就是「检查更新前面缺个图标」。
    // 抄效果图路径时一律改写成：`a rx ry rot laf sf x y`。

    /** 顶栏汉堡按钮：打开侧边抽屉（效果图 `#i-menu`） */
    val Menu: ImageVector by lazy {
        build("Menu", "M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z")
    }

    /** 设置主页「外观」分类卡（效果图 `#i-palette`） */
    val Palette: ImageVector by lazy {
        build(
            "Palette",
            "M12 2a10 10 0 0 0 0 20c1.1 0 2-.9 2-2 0-.5-.2-1-.5-1.3-.3-.4-.5-.8-.5-1.3 " +
                "0-1.1.9-2 2-2h2.4A4.6 4.6 0 0 0 22 11.1C21.9 6.1 17.4 2 12 2zM6.5 12" +
                "a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3-4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z" +
                "m5 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3 4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z",
        )
    }

    /**
     * 设置主页「更新」分类卡（效果图 `#i-cloud`）。
     *
     * 2026-10-17 之前抽屉里也有一项「检查更新」用同一个图标，那一项已删（入口收敛，
     * 检查更新只剩设置 → 更新里）—— 图标留着，设置主页那张分类卡还在用。
     */
    val Cloud: ImageVector by lazy {
        build("Cloud", "M19.4 10a7.5 7.5 0 0 0 -14.3 -1.6A5 5 0 0 0 6 18h13a4 4 0 0 0 .4 -8z")
    }

    /**
     * 设置主页「歌词」分类卡（效果图 `#i-lyrics`：气泡 + 两行文字）。
     *
     * ⚠️ 刻意与 [Lyrics]（四行文字块）并存：两者在效果图里是不同的符号 ——
     * [Lyrics] 用在导航与播放页的「歌词/封面」切换键上，这个只用在设置分类卡。
     * 想合并成一个之前，先确认效果图把两处改成同一个符号了。
     */
    val LyricsBubble: ImageVector by lazy {
        build(
            "LyricsBubble",
            "M4 4h16a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2H8l-6 5V6a2 2 0 0 1 2 -2z" +
                "m3 4v2h10V8H7zm0 4v2h7v-2H7z",
        )
    }

    /**
     * 设置主页「悬浮歌词」分类卡（v1.1.52 独立成页时新增）：
     * 一块屏幕 + 底部字幕条，与 [LyricsBubble]（气泡歌词）区分开。
     */
    val Subtitles: ImageVector by lazy {
        build(
            "Subtitles",
            "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2z" +
                "M4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z",
        )
    }

    // ================================================================ v1.1.80：实验室

    /**
     * 「实验室功能」分类卡图标（Material science，锥形瓶）。
     *
     * 语义：这里是**还没定型**的功能，跑一段时间确认稳定后再移到正式位置。
     */
    val Science: ImageVector by lazy {
        build(
            "Science",
            "M19.8 18h-.2l-2.4-8.5c-.4-1.4-1.7-2.3-3.2-2.3V6h.5c.6 0 1-.4 1-1s-.4-1-1-1H9.5" +
                "c-.6 0-1 .4-1 1s.4 1 1 1h.5v1.2c-1.5 0-2.8.9-3.2 2.3L4.4 18h-.2c-.6 0-1 .4-1 1" +
                "s.4 1 1 1h15.6c.6 0 1-.4 1-1s-.4-1-1-1zM8.6 9.5c.1-.5.6-.9 1.2-.9h4.4" +
                "c.6 0 1.1.4 1.2.9l1.7 6H6.9l1.7-6z",
        )
    }

    // ================================================================ v1.1.4：恢复默认

    /**
     * 设置行尾的「恢复默认」小按钮（效果图 `.rst` / `#i-reset`，Material restart_alt）。
     *
     * 用自定义矢量而不是 `Icons.Filled.Refresh`：本项目只引入了 `material-icons-core`，
     * 那个包里的 `Refresh` 是**实心圆弧**，与效果图上「箭头 + 环绕弧线」的描边图形不是一个东西。
     * 路径逐字取自 `docs/ui/index.html` 的 `<symbol id="i-reset">`。
     */
    /** 无障碍（人形 + 张臂）：播放页「前台识别状态」按钮用（v1.1.92） */
    val Accessibility: ImageVector by lazy {
        build(
            "Accessibility",
            "M12 2c1.1 0 2 .9 2 2s-.9 2-2 2-2-.9-2-2 .9-2 2-2z" +
                "M21 9h-6v13h-2v-6h-2v6H9V9H3V7h18v2z",
        )
    }

    val Reset: ImageVector by lazy {
        build(
            "Reset",
            "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4" +
                "c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z",
        )
    }
}
