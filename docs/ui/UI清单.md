# WindTone UI 图清单

> 自动/半自动维护：以 `docs/ui/*.html` 中 `<figure class="fig" id="scr-…">` 为准。
> 更新日：2026-10-25（Asia/Shanghai）。对照 Compose 以仓库当前代码为准。

## 使用方式

1. 打开 [`index.html`](index.html) 总览，或直接打开各章 HTML（支持 `file://`）。
2. 模拟器验证：[`verify.html`](verify.html) + `bash scripts/emulator.sh start wt_phone|wt_auto --window`。
3. 状态说明：`已有` = 本次之前已有原型；`新建` = 本次补齐；`待对齐` = 代码有、产品入口或视觉仍需核对；`N/A` = 该形态不适用。

## 汇总

- 效果图屏数（figure）：**94**
- 其中手机形态：**78**；车机形态：**16**
- 另列车机 N/A 对照：**4** 条

## 完整清单

| id | 标题 | 形态 | 章节文件 | Compose / 来源 | 状态 | 备注 |
|---|---|---|---|---|---|---|
| scr-songs | ① 歌曲列表（主态） | 手机 | 01-main.html | MainScreen · SongsList / Common.SongRow | 已有 |  |
| scr-moremenu | ①B 顶栏「更多」下拉菜单（展开态） | 手机 | 01-main.html | MainScreen · LibraryHeader DropdownMenu | 已有 |  |
| scr-drawer | ①C 主界面 · 侧边抽屉（展开态） | 手机 | 01-main.html | MainScreen · ModalNavigationDrawer（顶栏汉堡按钮开合） | 已有 |  |
| scr-search | ② 歌曲列表 · 搜索态 | 手机 | 01-main.html | MainScreen · LibraryHeader.searchMode | 已有 |  |
| scr-album | ③ 专辑视图 | 手机 | 01-main.html | MainScreen · GroupListView（ALBUM） | 已有 |  |
| scr-artist | ④ 歌手视图 | 手机 | 01-main.html | MainScreen · GroupListView（ARTIST） | 已有 |  |
| scr-folder | ⑤ 文件夹视图（⋮ 菜单展开） | 手机 | 01-main.html | MainScreen · GroupListView（FOLDER，canRemove=true） | 已有 |  |
| scr-folder-grouping | ⑤B 文件夹视图 · 分组规则（按所选目录） | 手机 | 01-main.html | FolderScanner.kt → folderPath = 所选目录完整文档 ID；组标题 = 最后一个文件夹名字 | 已有 |  |
| scr-playlist | ⑥ 歌单列表 | 手机 | 01-main.html | MainScreen · PlaylistListView | 已有 |  |
| scr-groupdetail | ⑦ 分组详情（专辑） | 手机 | 01-main.html | MainScreen · GroupDetail + DetailHeader | 已有 |  |
| scr-playlistdetail | ⑧ 歌单详情 | 手机 | 01-main.html | MainScreen · PlaylistDetail + PlaylistActionsMenu | 已有 |  |
| scr-setup | ⑨ 首次进入 · 选择音乐位置 | 手机 | 01-main.html | MainScreen · ScanSetupView（扫描范围 UNSET 且库为空） | 已有 |  |
| scr-scanning | ⑩ 扫描中 · 骨架屏 | 手机 | 01-main.html | MainScreen · ScanningState + Common.LibrarySkeleton | 已有 |  |
| scr-emptyscan | ⑪ 空状态 · 未扫到音频 / 无匹配结果 | 手机 | 01-main.html | Common.EmptyHint（黑胶插画 128dp） | 已有 |  |
| scr-player-lyrics | ⑫ 播放页 · 歌词态（默认） | 手机 | 02-player.html | PlayerScreen.kt · LyricsPane（showLyrics = true） | 已有 |  |
| scr-player-car | ⑫D 播放页 · 沉浸态（歌词态 · 5 秒无操作） | 手机 | 02-player.html | PlayerScreen.kt · immersive —— 歌词态 5 秒无操作触发（手机 / 车机通用）；车机横屏见第六节 ㊲（v3 布局：左导航 + 内容区 + 底部播放条） | 已有 |  |
| scr-player-cover | ⑬ 播放页 · 封面态（含播放模式提示） | 手机 | 02-player.html | PlayerScreen.kt · PlayerScreenParts.kt(CoverPane) + modeHint | 已有 |  |
| scr-player-vol | ⑭ 播放页 · 音量浮层 | 手机 | 02-player.html | PlayerScreen.kt · 上下滑手势反馈（约 1.2s 后消失） | 已有 |  |
| scr-player-nolyric | ⑮ 播放页 · 暂无歌词 | 手机 | 02-player.html | LyricsPane.kt · NoLyricsHint | 已有 |  |
| scr-player-plain | ⑯ 播放页 · 纯文本歌词 / 加载中 | 手机 | 02-player.html | LyricsPane.kt · PlainLyrics · LyricsLoading | 已有 |  |
| scr-queue | ⑰ 播放队列 | 手机 | 03-sheets.html | SheetsPlayback.kt · QueueSheet（播放页顶栏「队列」图标打开） | 已有 |  |
| scr-options | ⑱ 播放设置 | 手机 | 03-sheets.html | SheetsPlayback.kt · PlayerOptionsSheet（播放页 Tune 图标打开） | 已有 |  |
| scr-eq | ⑲ 均衡器与音效 · 上半屏 | 手机 | 03-sheets.html | ui/EqSheet.kt · EqSheet（需先开始播放，否则提示不可用） | 已有 |  |
| scr-eq-fx | ⑲B 均衡器与音效 · 下半屏 | 手机 | 03-sheets.html | ui/EqSheet.kt · 同一页向下滚：增强音效（低音 / 环绕 / 响度 / 混响）+ 播放过渡（淡入淡出 / 音量平衡 / 按住对比原声） | 已有 |  |
| scr-sleep | ⑳ 睡眠定时 | 手机 | 03-sheets.html | SheetsPlayback.kt · SleepSheet | 已有 |  |
| scr-songactions | ㉑ 歌曲操作（长按 / 点 ⋮） | 手机 | 03-sheets.html | SheetsSong.kt · SongActionsSheet | 已有 |  |
| scr-songinfo | ㉒ 音频信息 | 手机 | 03-sheets.html | SheetsSong.kt · SongInfoSheet · AudioInspector | 已有 |  |
| scr-addplaylist | ㉓ 加入歌单 | 手机 | 03-sheets.html | SheetsPlaylist.kt · AddToPlaylistSheet | 已有 |  |
| scr-scanscope | ㉔ 扫描范围 | 手机 | 03-sheets.html | SheetsScanScope.kt · ScanScopeSheet（顶栏「更多」→ 扫描范围） | 已有 |  |
| scr-dlg-newplaylist | ㉕ 对话框 · 新建 / 重命名歌单 | 手机 | 03-sheets.html | MainScreen · NewPlaylistDialog | 已有 |  |
| scr-dlg-editsong | ㉖ 对话框 · 编辑歌曲信息 | 手机 | 03-sheets.html | MainScreen · EditSongDialog | 已有 |  |
| scr-dlg-delete | ㉗ 对话框 · 删除歌曲文件（危险操作） | 手机 | 03-sheets.html | MainScreen · 删除确认（仅文件夹视图可触发） | 已有 |  |
| scr-dlg-removefolder | ㉘ 对话框 · 移除文件夹扫描结果 | 手机 | 03-sheets.html | MainScreen · 文件夹视图 ⋮ → 移除扫描结果 | 已有 |  |
| scr-settings-1 | ㉙ 设置页 · 主页面（二级分类） | 手机 | 04-settings.html | SettingsScreen.kt · 分类入口卡：外观 / 播放 / 歌词 / 音乐库 / 关于与诊断 / 实验室（v1.1.80） | 已有 |  |
| scr-settings-appearance | ㉙B 设置页 · 外观子页 | 手机 | 04-settings.html | SettingsScreen.kt · 主题模式（跟随系统 / 浅色 / 深色）· 主题跟随封面取色 · 车载模式 | 已有 |  |
| scr-settings-playback | ㉙C 设置页 · 播放子页 | 手机 | 04-settings.html | SettingsScreen.kt · 默认播放速度 / 继续上次播放 / 跳过静音 / 均衡器与音效 | 已有 |  |
| scr-settings-lyrics | ㉙D 设置页 · 歌词子页 | 手机 | 04-settings.html | SettingsScreen.kt · 歌词字号 / 桌面悬浮歌词及其外观选项 | 已有 |  |
| scr-settings-colorpicker | ㉙E 颜色选择弹层 | 手机 | 04-settings.html | SettingsScreen.kt · ColorPickerSheet（v1.1.8 起只剩「悬浮歌词文字颜色」一处调用） | 已有 |  |
| scr-dlg-overlay-hidden-apps | ㉙F 不显示悬浮窗的应用 · 名单弹窗 | 手机 | 04-settings.html | SettingsLyricsOverlayAppsDialog.kt · OverlayHiddenAppsDialog（v1.1.29 新增） | 已有 |  |
| scr-settings-2 | ㉚ 设置页 · 音乐库子页 | 手机 | 04-settings.html | SettingsScreen.kt · 本地曲库（短音频过滤） | 已有 |  |
| scr-libstats | ㉚H 曲库格式与容量 | 手机 | 04-settings.html | LibraryStatsScreen.kt · 各格式首数 / 占用 / 占比 · 无损拆分 | 已有 |  |
| scr-settings-update | ㉚C 设置页 · 更新子页 | 手机 | 04-settings.html | SettingsScreen.kt · 检查更新 / 自动检查 / 下载线路 | 已有 |  |
| scr-settings-diagnostics | ㉚D 设置页 · 关于与诊断子页 | 手机 | 04-settings.html | SettingsScreen.kt · 播放统计 / 数据与存储 / 关于（「调试」分区默认隐藏，见 ㉚F） | 已有 |  |
| scr-diagnostics-foreground-log | ㉚F 关于与诊断 · 调试 | 手机 | 04-settings.html | SettingsSystemPages.kt · SettingsDiagnosticsPage 的「调试」分区（v1.1.29 起，v1.1.31 重排） | 已有 |  |
| scr-dlg-carmode | ㉚E 设置页 · 车载模式说明弹窗 | 手机 | 04-settings.html | SettingsScreen.kt · showCarModeInfo（AlertDialog） | 已有 |  |
| scr-dlg-update | ㉛ 更新对话框（三态） | 手机 | 04-settings.html | SettingsScreen.kt · UpdateDialog | 已有 |  |
| scr-dlg-downloadroute | ㉚G 设置页 · 下载线路弹窗 | 手机 | 04-settings.html | SettingsScreen.kt · showRouteDialog（AlertDialog，单选） | 已有 |  |
| scr-dlg-minduration | ㉚F 设置页 · 过滤短音频弹窗 | 手机 | 04-settings.html | SettingsScreen.kt · showMinDurationDialog（AlertDialog） | 已有 |  |
| scr-settings-online | ㉚G 设置页 · 在线播放 | 手机 | 04-settings.html | SettingsOnlinePage.kt · 缓存上限弹窗 / 在线收听报告 | 已有 | 旧入口图；现行入口见 scr-online-hub / 实验室 |
| scr-widget | ㉜ 桌面小组件 + 快捷设置磁贴 | 手机（系统能力） | 05-extras.html | MusicWidgetProvider · PlaybackTileService | 已有 |  |
| scr-notif | ㉝ 媒体通知 / 锁屏 | 手机 | 05-extras.html | PlaybackService（Media3 默认 MediaStyle） | 已有 |  |
| scr-snack | ㉞ 顶部提示（Snackbar） | 手机 | 05-extras.html | MainScreen · SnackbarHost（TopCenter + statusBarsPadding + top 6dp） | 已有 |  |
| scr-car-library | ㊱ 车机 · 全部歌曲 · 最近播放（v3 布局 · 现行） | 车机 | 06-car.html | CarLibraryScreen.kt —— 左导航 188px + 内容区 + 底部常驻播放条 92px | 已有 |  |
| scr-car-albums | ㊱B 车机 · 专辑（网格 · v3 布局） | 车机 | 06-car.html | CarLibraryScreen.kt —— 同一导航壳，内容区换成 6 列封面网格；「歌手」同构 | 已有 |  |
| scr-car-player-full | ㊲ 车机 · 播放页 · 歌词态（v3 布局） | 车机 | 06-car.html | PlayerScreen.kt —— 导航壳保留，内容区换成歌词 + 进度 + 大控制键；5 秒无触摸收成沉浸态 | 已有 |  |
| scr-car-player-cover | ㊲A 车机 · 播放页 · 封面态 | 车机 | 06-car.html | PlayerScreen.kt · PlayerScreenParts.kt(CoverPane) —— 歌词↔封面切换；含播放模式提示（顺序 / 单曲 / 随机） | 已有 |  |
| scr-car-player-nolyr | ㊲B 车机 · 播放页 · 暂无歌词 | 车机 | 06-car.html | PlayerScreen.kt —— 本地无 .lrc / 内嵌歌词时的占位态，绝不联网抓取 | 已有 |  |
| scr-car-player-vol | ㊲C 车机 · 播放页 · 音量浮层 | 车机 | 06-car.html | PlayerScreen.kt · VolumeHud —— 方向盘音量键 / 旋钮触发，2 秒无操作自动消失 | 已有 |  |
| scr-car-player-immersive | ㊲D 车机 · 播放页 · 沉浸态（歌词态 · 5 秒无触摸） | 车机 | 06-car.html | PlayerScreen.kt · immersive —— 歌词态 5 秒无触摸触发（手机 / 车机通用）；车机收起左导航与控制键，歌词铺满全宽 | 已有 |  |
| scr-car-settings-flat | ㊳ 车机 · 设置（扁平单页 · v3 布局） | 车机 | 06-car.html | CarSettingsScreen.kt —— 导航「设置」选中；层级 1，打开即可改；底部常驻播放条一步回播放页 | 已有 |  |
| scr-car-overlay | ㊴ 车机 · 桌面悬浮歌词（四种状态） | 车机 | 06-car.html | LyricOverlayService.kt —— 对齐可配（默认居中）· 锁定态穿透 · 调整态可拖 · App 前台自动隐藏 | 已有 |  |
| scr-car-overlay-style | 车机 · 设置 → 悬浮窗样式 | 车机 | 08-floating-lyrics.html | CarSettingsScreen.kt（**FR-07 已接**：三枚大卡 + 字号步进 + 四角 chips；无驾驶中简化） | 已有 | FR-08 驾驶中简化仍为 **已否决 / 不做** |
| scr-car-artists | ㊱C 车机 · 歌手（圆头像网格 · v3） | 车机 | 06-car.html | CarLibraryScreen.kt — 同导航壳；歌手用 round 封面 | 新建 |  |
| scr-car-folders | ㊱D 车机 · 文件夹 | 车机 | 06-car.html | CarLibraryScreen.kt — 文件夹列表（大触控行） | 新建 |  |
| scr-car-playlists | ㊱E 车机 · 歌单 | 车机 | 06-car.html | CarLibraryScreen.kt — 歌单列表 | 新建 |  |
| scr-car-search | ㊱F 车机 · 搜索 | 车机 | 06-car.html | CarLibraryScreen.kt · 顶栏搜索（大输入区） | 新建 |  |
| scr-car-aliyun | ㊱G 车机 · 阿里云盘（左导航常驻入口） | 车机 | 06-car.html | CarLibraryScreen.kt · onOpenAliyun + AliyunPanPage（v1.1.84） | 新建 |  |
| scr-car-na-phone-only | ㊱H 车机 · 手机专属能力对照（N/A 说明卡） | 车机 | 06-car.html | 非独立 Compose 页 · 对照说明 | 新建 | 对照卡，非独立业务页 |
| scr-lyr-menu | ㉔ 播放设置弹层 · 歌词菜单（整合后） | 手机 | 08-floating-lyrics.html | SheetsPlayback.kt「歌词」分组升级（D2 拍板：不做新顶栏菜单，落点=播放设置弹层） | 已有 |  |
| scr-lyr-style-quick | ㉕ 歌词菜单 · 悬浮窗样式速选（展开态） | 手机 | 08-floating-lyrics.html | 播放设置弹层内展开：手机 4 套预设 + 车机 3 套（仅车机模式） | 已有 |  |
| scr-overlay-style | ㉖ 设置 → 歌词 → 悬浮窗样式（总览） | 手机 | 08-floating-lyrics.html | SettingsOverlayStylePage.kt · 预设网格 + 通用参数分组容器 | 已有 |  |
| scr-overlay-preset-detail | ㉗ 预设切换即时预览（选中「毛玻璃双行」） | 手机 | 08-floating-lyrics.html | 预设差异参数卡 + 桌面实时预览小窗 | 已有 |  |
| scr-preset-minimal | ㉘ 预设 1 · 极简单行 | 手机 | 08-floating-lyrics.html | 一行当前歌词 · 细胶囊 · 最低干扰 | 已有 |  |
| scr-preset-glass | ㉙ 预设 2 · 毛玻璃双行 | 手机 | 08-floating-lyrics.html | glass 底 + 当前行 / 下一行 | 已有 |  |
| scr-preset-gradient | ㉚ 预设 3 · 液态渐变 · 唱片 | 手机 | 08-floating-lyrics.html | 封面取色渐变 + 旋转唱片缩略 | 已有 |  |
| scr-preset-classic | ㉛ 预设 4 · 经典方块 | 手机 | 08-floating-lyrics.html | 方角专辑卡 · 纯展示（D1：无播放控制键） | 已有 |  |
| scr-preset-car | ㉜ 车机专属三预设 · 总览（D3：按车机场景重新设计） | 手机 | 08-floating-lyrics.html | C1 导航窄条 / C2 驻停歌词 / C3 纯净浮层 —— 全部只展示、触摸穿透 | 已有 |  |
| scr-settings-home | ㉞ 设置主页（优化后） | 手机 | 08-floating-lyrics.html | SettingsRootPage.kt · 六张分类卡统一 icon halo + 20dp 圆角 + 12dp 间距 | 已有 |  |
| scr-lyrics-page | ㉟ 歌词子页（优化后） | 手机 | 08-floating-lyrics.html | SettingsLyricsPage.kt · 三段分组容器：显示 / 悬浮窗（新）/ 高级 | 已有 |  |
| scr-aliyun-login | ㉛ 阿里云盘 · 未登录 | 手机 | 09-online.html | AliyunPanPage.kt · NotLoggedInBody（抽屉「在线音乐」一级入口） | 新建 |  |
| scr-dlg-aliyun-qr | ㉛B 对话框 · 扫码登录阿里云盘 | 手机 | 09-online.html | AliyunQrLoginDialog.kt · 应用内二维码 + 轮询 | 新建 |  |
| scr-aliyun-browse | ㉛C 阿里云盘 · 已登录浏览 | 手机 | 09-online.html | AliyunPanPage.kt · LoggedInBody（目录 / 播放 / 扫描为歌单） | 新建 |  |
| ~~scr-baidu-login~~ | ~~百度网盘~~ | — | — | **已删除**：产品只保留阿里云盘 | 删除 | 2026-10-25 |
| ~~scr-baidu-browse~~ | ~~百度网盘浏览~~ | — | — | **已删除** | 删除 | 2026-10-25 |
| scr-online-hub | ㉝ 设置页 · 在线播放（实验室入口） | 手机 | 09-online.html | SettingsOnlinePage.kt · ONLINE_HUB（实验室 → 在线播放） | 新建 |  |
| scr-online-playlists | ㉝B 在线歌单列表 | 手机 | 09-online.html | OnlinePlaylistsPage.kt · SettingsPage.ONLINE | 新建 |  |
| scr-dlg-online-add | ㉝C 对话框 · 添加在线歌单 | 手机 | 09-online.html | OnlinePlaylistsPage.kt · OnlinePlaylistAddDialog | 新建 |  |
| scr-listening-report | ㉞ 收听报告（本地 / 在线共用版式） | 手机 | 09-online.html | ListeningReportScreen.kt · 本地 listen_log 或 OnlineListeningReport | 新建 |  |
| scr-dup-empty | ㉟ 重复歌曲清理 · 未扫描 / 空态 | 手机 | 10-tools.html | DuplicateCleanupScreen.kt | 新建 |  |
| scr-dup-result | ㉟B 重复歌曲清理 · 扫描结果 | 手机 | 10-tools.html | DuplicateCleanupScreen.kt · GroupCard / CopyRow | 新建 |  |
| scr-settings-lab | ㊹ 设置页 · 实验室 | 手机 | 10-tools.html | SettingsLabPage.kt · SettingsPage.LAB（v1.1.80） | 新建 |  |
| scr-menu-order | ㊹B 设置页 · 调整菜单顺序 | 手机 | 10-tools.html | SettingsOnlinePage.kt · SettingsMenuOrderPage | 新建 |  |
| scr-boot-diag | ㊺ 启动诊断页 | 手机 | 10-tools.html | BootDiagView.kt · 启动异常时的原生诊断页 | 新建 |  |
| scr-split-probe | ㊺B 分栏探测（开发探针） | 手机 | 10-tools.html | SplitProbeView.kt · 原生 View / Compose / App 半屏对照 | 新建 | 开发探针；产品入口待对齐 |

## 车机 N/A（手机专属）

| id | 标题 | 形态 | 章节文件 | Compose / 来源 | 状态 | 备注 |
|---|---|---|---|---|---|---|
| — | 桌面小组件 / 快捷磁贴 | 车机 N/A | 05-extras.html | MusicWidgetProvider / PlaybackTileService | N/A | 见 scr-car-na-phone-only |
| — | ~~百度网盘独立子页~~ | — | — | **已删除**（只保留阿里云盘） | 删除 | 2026-10-25 |
| — | 重复歌曲清理 | 车机 N/A | 10-tools.html | DuplicateCleanupScreen.kt | N/A | 建议手机处理 |
| — | 调整菜单顺序 | 车机 N/A | 10-tools.html | SettingsMenuOrderPage | N/A | 车机设置扁平单页 |

## 章节与功能划分

| 文件 | 功能 |
|---|---|
| 01-main.html | 手机主界面 / 曲库浏览 / 抽屉 / 扫描 |
| 02-player.html | 手机播放页 |
| 03-sheets.html | 手机抽屉与弹窗 |
| 04-settings.html | 手机设置主路径 |
| 05-extras.html | 小组件 / 通知 / Snackbar |
| 06-car.html | 车机曲库 / 播放 / 设置 / 云盘入口 |
| 07-spec.html | 颜色与尺寸规范（无业务屏） |
| 08-floating-lyrics.html | 悬浮歌词与预设 |
| 09-online.html | 手机在线歌单 / 阿里云盘 / 收听报告 |
| 10-tools.html | 重复清理 / 实验室 / 诊断探针 |
| verify.html | 模拟器验证命令与核对顺序 |

## 待对齐

- `SplitProbeView`：开发探针，无产品入口文案终稿。
- `BootDiagView`：仅异常启动路径，视觉为原生 View，HTML 为示意。
- 设置主页六卡文案 / 图标：若与最新字符串资源有出入，以 `SettingsPages.kt` 为准再回写 `scr-settings-1`。
- 车机阿里云盘未登录态：复用手机 `scr-aliyun-login` 信息架构，车机框可再出一版细稿。
- `flow.html` 用户流程图未纳入本清单屏表（非 figure 屏）。
- **FR-08 驾驶中简化**：用户 2026-10-25 **否决 / 不做**；`08-floating-lyrics.html` 已改为 PASS 标注，不再当交付功能。
- **FR-07 车机三枚大卡**：已在 `CarSettingsScreen` 落地（三枚大卡 + 字号步进 + 四角 chips；套用写全局）。
