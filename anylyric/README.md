# AnyLyric

WindTone 的轻量分支：旁听车机上任意播放器**已经发布**的媒体会话，把播放器自己暴露的歌词画到悬浮层。

**它不是播放器** —— 不播放声音、不申请音频焦点、不控制播放、不读文件。网络**只**用于应用内更新。

- 包名：`com.anylyric`（与 `:app` 不同，可同机共存）
- 平台：Android 12+（`minSdk = 31`）
- 运行时依赖：仅 `kotlin-stdlib`

---

## 一期范围（当前实现）

只做「直接获取」，用来验证车机播放器到底有没有把歌词放进可被旁观的系统通道。

| 通道 | 来源 | 判定 |
|---|---|---|
| L0 | `MediaMetadata` 自定义键 | 键名白名单（lyric / lrc / 歌词）+ `[mm:ss.xx]` 内容特征双判，解析出 ≥2 行才采纳 |
| L1 | `TITLE` 逐行推送 | 曲目指纹（包名 / duration / mediaId / artist / 专辑 / 音轨）不变而 `TITLE` 在 8 秒内变化 ≥2 次 → 判定「伪歌名」，切逐行模式 |
| L2 | 媒体通知文本 | 含 LRC 特征则解析；或多行正文且已判定伪歌名时作为逐行流 |

**不实现**：L3 本地 `.lrc`、L4 在线／无障碍。二者只在 `LyricsSource` 接口上预留挂载点，**不写实现**。
`INTERNET` 权限只给「检查更新」用，歌词链路始终不联网、不读本地文件。

因此多数普通播放器会显示「暂无歌词」——这是**一期预期结果，不是缺陷**。一期验收标准是通道探测报告，不是歌词覆盖率。

### 首屏延迟的两条硬约束（v0.1.1）

1. **缓冲必须早于判定**：每次 TITLE 变化就进 L1 缓冲，不等判定成立。否则判定前看到的
   第一二句会被永久丢弃，首屏要多等一个歌词周期。判定成立时缓冲整体回填。
2. **判定只要两条证据**：`minChanges = 2`（8 秒内两个不同标题）。要求三条会让首屏
   白等 6~15 秒。两条就够的前提是曲目指纹未变，且「歌名 → 歌名 - 播放中」这类
   后缀变体已单独排除（`TitleTimeline.isTrivialVariant`）。

空窗期的文案也分两种：标题还在动 → 「正在获取歌词…」；标题停住 20 秒仍无行 → 「暂无歌词」。

---

## 权限状态：两个事实，不能混用

`listenerGranted`（系统设置里是否勾选，读 `enabled_notification_listeners`）与
`listenerConnected`（系统此刻是否已绑定服务）是两件事。只判后者会在「应用关闭后
重新打开」时误报未授予——系统还没 bind，但权限一直勾着，用户进设置看到的也是勾选态。

| 状态 | 界面 | 动作 |
|---|---|---|
| 已授权已连接 | 绿色「已连接」 | 正常显示歌词 |
| 已授权未绑定 | 琥珀「已授权，正在等待系统连接…」 | 自动 `requestRebind`（5 秒节流）+ 约 10 秒轮询；**仍照常出歌词**（见下） |
| 已授权未绑定超 9 秒 | 红色「已授权，但系统没有自动恢复连接」 | 文案改指手工：点「系统设置」把开关关掉再打开一次 |
| 未授权 | 红色「未授予」 | 「去开启」+「系统设置」两个入口 |

**「未绑定」不等于「不能用」**：`getActiveSessions` 的权限边界是授权列表，不要求
`NotificationListenerService` 实例已连接；`MediaController` 的回调也走 MediaSession
自己的 binder。因此未绑定期间 L0/L1 与进度照常工作，缺的只有 L2（通知文本）与推送
事件——由 8 秒一次的低频查询代偿，服务一连上就停。这才是「关掉应用再打开」后仍能出
歌词的原因。

两个按钮始终并存：修复动作（去开启 / 重新连接）与「系统设置」。「系统设置」优先直跳
本应用的通知使用权详情页（API 30+），失败才退回列表页——手工关开是自动重绑失效时
唯一的自救路径，不该让用户在一长串应用里翻找。

判因分两条：`PERMISSION_LISTENER`（没勾）与 `LISTENER_UNBOUND`（勾着但没绑上，且没拿到
歌词）。已拿到歌词则判 `OK`，只在 evidence 里标 `degraded`。


---

## 外观与位置（v0.2.1）

外观收敛为**数据**：`OverlayStyle` 一个不可变数据类，默认值只在 `OverlayDefaults`
定义一次，每个可调项都能「重置」写回那里。`LyricRenderer` 只负责把参数变成像素。

| 预设 | 底色 | 描边 | 行数 | 适用 |
|---|---|---|---|---|
| 极简描边 `STRIPE` | 全透明 | 有（2px 轮廓） | 2 | 不想遮挡车机界面 |
| 玻璃胶囊 `GLASS`（默认） | 55% | 无 | 2 | 通用 |
| 实底字条 `SOLID` | 86% | 无 | 2 | 地图 / 视频类复杂背景 |
| 导航态 `NAVI` | 86% | 无 | 1（居中） | 行车中只看一句 |

可调项：字号 14–40sp、行数 1–3、对齐左/中/右、背景不透明度 0–100%、圆角 0–28dp、
文字描边、加粗、切换淡入 0/320ms、副行内容（下一句 / 上一句 / 歌名·歌手 / 关闭）、
播放状态条、无歌词时隐藏。

**配色三项各自可调**（v0.2.1）：当前行颜色、其它行颜色、背景色。只给一组车机上看得清的
色板（`OverlayPalette`），不做自由取色器——强光下车机屏对比度本来就差，调出灰字灰底
是负体验。其它行的实际不透明度还会再乘「副行不透明度」；背景色再乘「背景不透明度」。
**换预设不会冲掉自定义颜色**，只改观感参数。

**位置存比例不存像素**（`xRatio/yRatio ∈ [0,1]`，横竖屏各一套）：

- 换分辨率、改字号、横竖屏切换都不会让窗口跑出屏幕；
- 拖动时先钳制像素再换算回比例，所以**拖不出屏幕**——旧实现能把窗口拖到屏外并落盘，
  用户看到的是「悬浮窗消失了」，无法自查；
- 九宫格锚点写死 0 / 0.5 / 1 的组合，松手距边 24dp 内自动吸附。

**宽高也可调**（v0.2.1）：`widthRatio / heightRatio` 同样存比例，`0` 表示自适应。
两种调法 —— 编辑态拖四角（对角固定、实时预览、松手换算成比例落盘），或在设置页用
「宽度 / 高度」步进（宽 24–98%，高 4–60%）。固定宽度的额外好处是每句歌词不再改变
窗口尺寸，`requestLayout` 不再牵动窗口重排。

**编辑态**：虚线框 + 四角把手 + 底部提示条，右下角圆形「✓」退出，60 秒无操作自动退出。
长按弹出四个操作：锁定位置 / 隐藏 10 分钟 / 打开设置 / 复制当前行。菜单是自绘在同一
个 View 里的，不起 Dialog —— 车机上弹窗会抢焦点，而且部分 ROM 上直接用 application
context 弹 Dialog 会 `BadTokenException`。

**左侧收起把手**（v0.2.1）：独立小窗口（30×72dp），竖向居中贴歌词窗左缘，点一下收起 /
展开。拆成独立窗口是必须的——歌词窗默认 `FLAG_NOT_TOUCHABLE`，为了这颗按钮把整窗改成
可触摸会让歌词区域变成一个挡手的黑洞。把手可随时关掉（设置 → 位置与尺寸）；关掉时
收起功能同时失效，避免留下一个没法恢复的收起态。

**切换动效**（v0.2.1）：不再改窗口 `alpha`（那会让整块 surface 重新合成，车机上表现为
掉帧），改为 View 内双缓冲交叉淡入——旧行在前 35% 退干净、新行从 12% 浮上来，两条
曲线刻意错开以免叠成重影。一个复用的 `ValueAnimator` + `DecelerateInterpolator`，
提示语 / 菜单 / 编辑态都不淡入。

**空状态**与正文分开：提示语用小一号、约 70% 透明度，第二行补「歌名 · 歌手」，
不会再出现「取到了没取到看起来一样」。

---

## 应用内更新（v0.2.0）

更新读**公共分发仓库的静态文件**，不碰 GitHub API（无需鉴权，raw 还是 CDN，不占 API 配额）：

```
https://raw.githubusercontent.com/LuckyLearning/Release/main/AnyLyric/latest.json
…/AnyLyric/v0.2.0/AnyLyric-v0.2.0.apk
```

- **按 `versionCode` 判断新旧**，清单缺 `versionCode` 才退回版本名比较 —— 只比版本名会有
  「装过更高版本名、看不到较低版本」的死角；
- 线路 `fastly.jsdelivr → gcore.jsdelivr → gh-proxy → 官方 raw` 依次尝试；某条返回坏数据
  （镜像错误页、半截 JSON）继续试下一条，不把「代理坏了」当成本软件的结论；
- 下载后三层校验：长度下限 + `PK` 头、清单声明的大小、SHA-256。镜像线路越多越需要摘要这层；
- 安装包经自带的 `ApkProvider`（`content://<applicationId>.apkfiles/…`）交给系统安装器，
  **不引 AndroidX**（FileProvider 在 androidx.core 里，为一个功能引整库不划算）；
  同时带 `FLAG_GRANT_READ_URI_PERMISSION` 与 `clipData`，否则部分 ROM 的安装器读不到文件；
- 「安装未知应用」没开时先把用户送到授权页，而不是等他点完再失败；
- 失败一律在**设置页里**显示成人话并写进探测报告 `[更新]` 段 —— 车机上不看 adb 也能自查。

检查与下载都在工作线程，结果回主线程；进度只在百分比变化时刷界面。

---

## 构建

```bash
# 必须用标准 OpenJDK 21。GraalVM 的 jlink 会让 JdkImageTransform 失败。
export JAVA_HOME="C:/Users/stu_l/.jdks/openjdk-21.0.2"

./gradlew :anylyric:assembleDebug
./gradlew :anylyric:assembleRelease
./gradlew :anylyric:testDebugUnitTest
./gradlew :anylyric:lintDebug
```

产物：`anylyric/build/outputs/apk/release/AnyLyric-v<版本>.apk`（release 约 180 KB）。

---

## 架构

```
MediaWatchService  (NotificationListenerService，由系统绑定，不自拉活)
   └─ SessionHub          会话发现 / 选主 / 回调注册 / 通知文本采集
        ├─ SessionClock        进度外推 + 行定位（二分）
        ├─ TitleTimeline       伪歌名判定（变化频率 + 曲目指纹）
        ├─ LyricsSourceChain   L0 → L1 → L2，保留全部尝试记录
        └─ OverlayHost         TYPE_APPLICATION_OVERLAY，默认 FLAG_NOT_TOUCHABLE
             ├─ OverlayGeometry  比例↔像素、四边钳制、吸附（纯函数，可单测）
             ├─ OverlayStyle     外观参数 + 预设展开 + 范围钳制（唯一真源）
             ├─ CollapseHandle   左侧收起把手，独立小窗口（只有它吃触摸）
             └─ LyricRenderer    单 View + Canvas；三种形态 CONTENT / PROMPT / MENU
```

单进程、无常驻前台服务、无数据库、无网络。位置存比例，样式存标量。

**编辑态**才临时打开触摸（拖动 / 长按菜单），退出后立刻回到完全穿透。

---

## 排障：不依赖 adb

设置页 → 通道探测 → 打开调试日志 → 在别的应用里播放 → 回到探测页看结论卡。

导出有三条**设备内**路径：

1. 复制到剪贴板
2. 保存到公共下载目录（车机文件管理器可浏览）
3. 系统分享面板

**不需要连接电脑、不需要开发者选项、不需要 USB 调试。**

自动判因按「权限 → 会话 → 通道 → 时钟 → 窗口」顺序给出第一条结论。其中四条会明确标注为**播放器侧事实**，不当成本应用故障：

- 会话数为 0：播放器未发布 MediaSession
- 三通道全未命中：播放器未暴露任何歌词通道
- 标题仅在暂停时更新：发布端 AVRCP 门控丢更新
- 进度多次小幅回跳：发布端进度不自洽（**仅当歌词带时间轴时才报**；L1 逐行流按最后一行定位，与 progress 无关，不应误伤）

另有两条属于**授权侧**，需分开：未勾选（`PERMISSION_LISTENER`，去授权）与勾着但服务未绑定（`LISTENER_UNBOUND`，点重新连接）。

**资源占用**（v0.2.1）：报告里多一段 `[RESOURCE]`，日志里每分钟一条 `[资源]`。数字全部来自本进程可读的接口，不需要任何权限：

| 字段 | 来源 | 用途 |
|---|---|---|
| `pss` | `Debug.getMemoryInfo` | 整进程实际占用；PSS 不涨而 Java 堆涨 = 还没被系统回收压力逼着还 |
| `java` / `native` | `Runtime` / `Debug.getNativeHeapAllocatedSize` | 区分「Kotlin 侧持有」与「原生/图形侧持有」 |
| `cpu` | `/proc/self/stat` 的 utime+stime 差值 | 区间均值，回答「是不是在空转烧 CPU」 |
| `threads` / `fd` | `/proc/self/status`、`/proc/self/fd` | 线程或 FD 只增不减 = 泄漏 |
| `gc` | `Debug.getRuntimeStat("art.gc.gc-count")` | 频繁 GC 会让歌词切句发顿 |

拿不到的值显示 `?`，不显示 `-1` —— 后者会被当成真实数据。

换歌、重播、拖动进度都不计入「进度不稳」：换歌时清零并忽略落到 0 附近的位置，回退超过 5 秒视为用户拖动。

---

## 验证状态

| 项 | 结果 |
|---|---|
| `:anylyric:testDebugUnitTest` | **106 项**通过（LRC 解析 10、伪歌名 10、责任链 8、时钟 6、判因 14、权限列表解析 7、几何 10、折行 9、样式 15、更新 8、资源采样 9） |
| `:anylyric:lintDebug` | Error 0 |
| `assembleRelease` | 约 **243 KB**（含矢量图标 + 5 组密度 PNG），运行时依赖仅 `kotlin-stdlib` |
| 真机 | 手机（HONOR / API 37）已验证 L1 可命中；车机 **未验证** |

未验证项：车机播放器的通道命中率、车厂 ROM 对悬浮窗与通知使用权的策略、多屏行为、
编辑态长按菜单在各家 ROM 上的触摸投递。

## 自测 L1 的发布端

用 WindTone 当发布端时必须先打开 **设置 → 实验室 → 车载蓝牙歌词**（`bt_car_lyric`）。
不开这一项，WindTone 的 `MediaMetadata.TITLE` 只有歌名，探测会如实报 `stable_title` ——
这不是 AnyLyric 的问题。开了之后 TITLE 会被当前歌词行接管，AnyLyric 同机直连
MediaSession 即可读到逐行流（不经 AVRCP 蓝牙栈，绕开其门控）。

