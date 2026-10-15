# 听风 WindTone

Android 原生本地音乐播放器。**数据全部留在本机**，只在你主动（或开启自动检查时）才访问 GitHub Releases 查询新版本。

支持 MP3 / FLAC / WAV / M4A / OGG / Opus 等常见格式，同时支持「独立歌词文件」与「音频内嵌歌词」两种歌词来源，包含 KRC / YRC 逐字歌词与双语歌词。

[![Android CI](https://github.com/LuckyLearning/WindTone/actions/workflows/android-ci.yml/badge.svg)](https://github.com/LuckyLearning/WindTone/actions/workflows/android-ci.yml)
[![Release APK](https://github.com/LuckyLearning/WindTone/actions/workflows/android-release.yml/badge.svg)](https://github.com/LuckyLearning/WindTone/actions/workflows/android-release.yml)
[![Latest Release](https://img.shields.io/github/v/release/LuckyLearning/WindTone?display_name=tag&sort=semver)](https://github.com/LuckyLearning/WindTone/releases/latest)
[![License](https://img.shields.io/github/license/LuckyLearning/WindTone)](LICENSE)

---

## 下载安装

最新安装包在 **[Releases](https://github.com/LuckyLearning/WindTone/releases/latest)** 页面。
每个版本按 CPU 架构分成 4 个包（Release 签名包，R8 混淆 + 资源压缩，约 3 MB）：

| 文件 | 说明 |
|---|---|
| `WindTone-vX.Y.Z-arm64-v8a.apk` | **绝大多数手机装这个**（近十年的 64 位 ARM 设备） |
| `WindTone-vX.Y.Z-armeabi-v7a.apk` | 更老的 32 位 ARM 设备 |
| `WindTone-vX.Y.Z-x86_64.apk` | 模拟器 / x86 设备 |
| `WindTone-vX.Y.Z-universal.apk` | 全架构合一，体积略大；**拿不准架构时用它** |

安装步骤：

1. 下载 APK 到手机；
2. 允许「安装未知来源应用」；
3. 点击安装。

> **关于「覆盖升级会不会保留数据」——请先读这段**
>
> 同一套数据表结构（当前 `schema v5`）的版本之间覆盖升级，**数据完整保留**。
> 但一旦版本升级改变了表结构，应用会**重建数据库**：曲库重新扫描即可恢复，
> **歌单、收藏、播放统计、歌词偏移会清空**。
>
> 本应用**不做跨版本数据迁移**，这是既定取舍（不为兼容老库长期背迁移代码与它的回归风险）。
> 所以升级前请留意 Release 说明里是否提到「数据表结构变化」；升级后需要重新选一次扫描范围。
> 中间版本（`1.0.6 → 1.1.0`）就是这样一次整库重建。

### 应用内更新

应用内置了版本检测，不用再手动下载：

- **设置 → 更新 → 检查更新**，或库页右上角 `⋮` →「检查更新」；
- 发现新版本时会显示版本号、安装包大小和更新说明，点「下载并安装」即可；
- 下载完成后调起系统安装器，确认后完成升级；
- 「启动时自动检查更新」默认开启，每 24 小时最多检查一次，**检查失败不打扰**；关闭后应用完全不联网。

> 更新源是公开的 GitHub Releases，无需登录。第一次从应用内安装时，系统会要求你允许「听风」安装应用。

---

## 一、功能

### 音乐库
- **默认不扫描全盘**：安装后停在「选择音乐位置」页，由用户明确指定扫描范围（可在库页左上角胶囊标签或 `⋮` 菜单随时切换）：
  1. **扫描全盘** —— 读取系统媒体库（MediaStore）里的全部音频；若已授予「所有文件访问权限」，会额外做深度文件扫描，连带匹配同目录 `.lrc`
  2. **指定目录** —— 通过 SAF 只扫描用户挑的若干文件夹，可随时追加 / 移除；不需要任何存储权限
- 三个来源自动合并去重（媒体库 / SAF 目录 / 深度扫描），同一首歌优先保留带歌词、时长完整的那条
- 搜索（歌名 / 歌手 / 专辑 / 文件名），支持**拼音全拼与首字母**（如 `zf` 命中「周杰伦」）
- **搜索历史**（最近 10 条，搜索框空态显示，可一键清空）
- 6 种排序：标题、歌手、专辑、时长、添加时间、文件名
- **底部导航栏切换五种视图**：歌曲 / 专辑 / 歌手 / 文件夹 / 歌单，单手可达；**重启后停留在上次的视图**
- **A–Z 字母索引导航条**，按标题首字拼音定位
- **「回到当前播放」浮动按钮**：当前曲目滚出屏幕后一键跳回
- **每行右侧 `⋮` 更多入口**（长按同样可用），不必先发现长按手势
- **批量多选**：长按任意一行进入多选，之后点行即勾选；支持全选 / 反选，可批量**加入歌单、收藏、加入播放队列、移出曲库、删除文件**
  - 删除走**系统的一次确认框**（一次请求涵盖所选全部文件），不会逐首弹窗；只清掉真正删成功的那批，没删成的留在原地 —— 不会出现「删了一半、界面还不知道」的状态
- **文件夹视图支持删除歌曲文件**：`⋮` →「删除歌曲文件」，媒体库文件走系统确认框，SAF 目录与深度扫描路径直接删，同名 `.lrc` 一并删除
- **排除目录**：文件夹视图里可把某个导入目录标为「已移出」——它扫出来的歌不再进曲库，但**重扫不会把这个标记收回**（这是"用户主动移出"，与"文件此刻不在"的软删严格区分），设置页可一键恢复
- **歌单系统**：新建 / 重命名 / 删除 / 加入 / 移除 / 播放全部；**拖动排序**、**一键去重**（按「歌名 + 歌手 + 时长」判定，只移出歌单、不删文件）、**把当前播放队列存成歌单**；固定入口「最近播放」「最常播放」
- **收藏**（行尾 `⋮` 或长按歌曲 → 底部操作抽屉；批量多选里也能一次收藏一批）
- **曲库持久化**：扫描结果落 Room 曲库，冷启动先出列表秒开，再后台按已选范围增量刷新；文件暂时不可见只标记软删，收藏与歌单不受影响
- **骨架屏占位**：扫描期间显示 8 行微光占位条，而不是一个转圈
- **播放统计**：播放次数、累计收听时长、听过的歌曲数（设置页可清空，收藏保留）
- **收听报告**：近 **7 / 30 天**的每日收听曲线、**24 小时时段分布**与 TOP 榜单；另附「已移出曲库」数量与一键恢复
- **音频信息**：`⋮` →「音频信息」，读容器 / 编码 / 码率 / 采样率 / 声道 / 位深 / 时长
- 列表显示内嵌封面、标题、歌手、时长，并标注歌词来源
- 歌曲信息本地编辑（**只覆盖显示，不修改原文件**）

### 格式支持
| 容器 | 说明 |
|---|---|
| MP3 / MP2 | 全支持 |
| FLAC | 全支持（无损） |
| WAV | 全支持 |
| M4A / AAC | 全支持 |
| OGG / OGA | Vorbis 全支持 |
| OPUS | 全支持 |
| AMR | 全支持 |
| WMA / APE / DSF / TAK / TTA | 内核不支持，扫描时跳过并计数提示 |

> 解码由 Android MediaCodec + Media3(ExoPlayer) 完成，无需额外的第三方解码库。

### 歌词
取词优先级，逐级回退：

1. **同目录 `.lrc` 文件**
   匹配规则（大小写不敏感）：
   - `歌名.lrc`
   - `歌名 - 歌手.lrc`
   - `歌名.zh.lrc` / `歌名.sc.lrc` / `歌名_xxx.lrc`
   - `歌名.txt`（纯文本歌词，无时间轴也能显示）
2. **同目录 `.txt` 歌词**
3. **音频内嵌同步歌词** — ID3v2 `SYLT` 帧（毫秒制时间戳）
4. **音频内嵌歌词**
   - MP3：ID3v2.2/2.3/2.4 的 `USLT` 帧、`TXXX:LYRICS`
   - FLAC：`VORBIS_COMMENT` 中的 `LYRICS` / `UNSYNCEDLYRICS` / `UNSYNCED LYRICS` 等
   - OGG / Opus：`\x03vorbis` / `OpusTags` Vorbis Comment
   - M4A / MP4：`moov.udta.meta.ilst.©lyr`

取出后按下表确定解析器：

| 文件 | 解析器 | 能力 |
|---|---|---|
| `.lrc` | `lyrics/LrcParser.kt` | 时间轴、多标签、元信息、offset、**增强逐字标签** |
| `.krc`（酷狗） | `lyrics/WordLyricsParser.kt` | 16 字节密钥 XOR + zlib 解密 → **逐字时间轴** |
| `.yrc`（网易云） | `lyrics/WordLyricsParser.kt` | **逐字时间轴** |
| `.txt` | `LrcParser.plainText` | 无时间轴纯文本 |

歌词解析能力：

- 时间轴 `[mm:ss]`、`[mm:ss.xx]`、`[mm:ss.xxx]`、`[mm:ss:xx]`，一行多时间标签
- 元信息 `[ti:]` `[ar:]` `[al:]` `[by:]` `[offset:]`（offset 会自动换算）
- **逐字歌词**：KRC / YRC / 增强 LRC `<mm:ss.xx>`，播放时当前行**逐字染色**（karaoke）
- **双语歌词**：同时间戳（±50ms）相邻两行自动合并为「主行 + 翻译副行」
- **编码嗅探**：BOM → UTF-8 严格校验 → GB18030 严格校验 → 兜底宽容解码。
  因此 UTF-8 / GBK / GB18030 / UTF-16 的歌词文件都能正确显示，不会乱码。

歌词界面：

- 有时间轴 → 自动居中滚动、当前行高亮放大、**点击任意一行跳转到该时间点**
- 逐字歌词 → 行内按播放进度染色
- 手动滑动时自动暂停跟随 4 秒，避免「抢滑」
- 调整**歌词偏移**（±0.5s 步进、可重置）与**字号缩放**；偏移**按歌曲分别记忆**
- **长按歌词里任意一行 →「就唱到这句」**：以该行为基准一次性算好整首的偏移（不用拿 ±0.5s 反复试探），弹窗里也能一键重置为 0
- 歌词面板与封面态的切换**会记住**，下次进播放页还是你上次看的那一面
- 可把当前歌词**导出为 `.lrc`**
- 无时间轴 → 居中纯文本滚动显示；无歌词 → 明确提示获取方式

**桌面悬浮歌词**（Android 8.0+，需「显示悬浮窗」授权）：
- 退到桌面 / 导航页后，在屏幕最上层显示当前歌词行；App 在前台时自动隐藏（避免与播放页歌词重复），灭屏也隐藏
- **1 行 / 3 行**两种排版，3 行带上下句上下文、当前行高亮
- 当前行有翻译时自动附一行**双语副行**；开启「逐字歌词」时按字染色
- **字号**（小 / 中 / 大）、**水平对齐**（居中 / 居左）、**底板**（无 / 半透明 / 深色实底）、**暂停表现**（淡出 / 隐藏）都可调，改完立刻给一次预览
- 垂直位置可拖动微调，**调整态 60 秒无操作自动锁定**；锁定态**完全点击穿透**，不挡桌面与导航操作
- 前奏回落显示歌名、间奏停在上一句并压暗 —— 字幕不会突然消失
- 「歌曲被系统杀掉后」会在下一次切歌时自动重建服务，长途导航不必重开 App
- 设置页提供**一键自检**：授权 / 开关 / 服务 / 窗口 / 位置 / 内容 / 外观一次测完并直接给结论

> 逐字效果需要 `.krc` / `.yrc` 文件放在音频同目录（与 `.lrc` 相同的命名匹配规则），
> 或音频内嵌增强 LRC 标签；普通 `.lrc` 为逐行滚动 + 双语。

### 播放
- Media3 `MediaSessionService` 后台播放，**息屏 / 退后台 / 任务划掉后继续播放**
- **通知栏 + 锁屏播放控制**，封面自动同步到通知
- 耳机线控、蓝牙按键（上一首 / 下一首 / 播放暂停）
- 音频焦点处理（来电、其他 App 播放时自动让出）、拔出耳机自动暂停
- 播放模式：列表循环 / 单曲循环 / 随机
- **播放队列抽屉**：查看队列、点击跳转、上移 / 下移排序、移除单条
- **断点续播**：杀掉进程重开后，**整个队列（含顺序与当前曲目）与播放进度**自动恢复，但不自动播放
- **播放失败自动跳下一首**（带连续错误计数，避免损坏文件卡死队列）
- **变速播放** 0.75x ~ 2.0x
- **均衡器与音效**：系统均衡器（预设 + 自定义频段）、低音增强、环绕声、响度增强
- **睡眠定时**：倒计时 / 播完当前曲两种模式；到点前 **10 秒淡出渐弱**，不会在听感正好的时候一刀切断
- **跳过静音**（整轨、现场录音的开头空白）
- 封面区手势：**双击暂停 / 左右滑切歌 / 上下滑调音量**（带音量百分比浮层）
- 进度条**自绘**：显示缓冲进度段，拖动时轨道与圆点放大，时间气泡跟随
- **迷你播放条**可拖拽 / 点击跳转进度、封面左右滑切歌、双击暂停
- 播放页背景跟随封面主色做**垂直渐变**，封面播放时轻微呼吸缩放
- 播放设置改为**分组抽屉**（音效 / 睡眠 / 速度 / 歌词）

### 桌面组件与系统入口
- **桌面小部件**：封闭面 / 歌名 / 歌手三块区域**分别直达**播放页 / 曲库 / 歌单，配「上一首 / 播放暂停 / 下一首」三键
- **下拉快捷磁贴**：两个 —— 「播放·暂停」（显示当前歌名与歌手）与「下一首」；没有队列时点播放磁贴会直接进应用开始播放，而不是静默失败
- **启动器图标长按快捷方式**：播放全部 / 随机播放 / 我的收藏；冷启动时曲库还在异步装载，所以这三个动作是「先记下意图 → 曲库就绪后再执行」，不会点了没反应
- **车载（Android Auto）媒体卡**：已声明媒体浏览器兼容层，车机能识别本应用并提供播放控制（歌单层级浏览暂未提供）
- **平板 / 折叠屏**：可用宽度 ≥ 840dp 时自动切成**左右双栏**（左侧列表 / 右侧播放页），手机竖屏布局完全不变

### 设置页
库页右上角 `⋮` →「设置」。设置页是**一个主页面 + 三个子页**：主页面只放会反复改的开关 / 滑块，一次性配置、只读信息与排障工具各自收进子页——改一个开关不用先滚过六组无关内容。每个页面的滚动位置各记各的，从子页返回不会把你顶回顶部。

| 层级 | 分区 | 内容 |
|---|---|---|
| 主页 | 外观 | 主题跟随封面取色、车载模式 |
| 主页 | 播放 | 默认播放速度、跳过静音、均衡器与音效 |
| 主页 | 歌词 | 歌词字号、逐字歌词；**桌面悬浮歌词**开关与行数 / 字号 / 对齐 / 底板 / 暂停表现（改完立刻给 6 秒预览） |
| 主页 | — | 三个子页入口：音乐库 / 更新 / 关于与诊断 |
| 子页 · 音乐库 | 本地曲库 | 短音频过滤阈值（默认 60 秒） |
| 子页 · 更新 | 更新 | 检查更新、启动时自动检查更新、下载线路、打开发布页面 |
| 子页 · 关于与诊断 | 播放统计 | 统计概览 + **收听报告**（7 / 30 天曲线与时段分布） |
| 子页 · 关于与诊断 | 数据与存储 | 已移出曲库数量、清理失效歌曲、**恢复已移出的歌**、**重复歌曲清理**、清空封面缓存；两个不可撤销按钮（**清空曲库与导入目录**（保留收藏与统计） / 清空全部用户数据） |
| 子页 · 关于与诊断 | 悬浮歌词 | 一键自检（授权 / 开关 / 服务 / 窗口 / 位置 / 内容 / 前后台 / 外观），失败给结论而不只是一个红叉 |
| 子页 · 关于与诊断 | 崩溃日志 | 查看 / 导出 / 清空 |
| 子页 · 关于与诊断 | 关于 | 版本、包名、更新来源、隐私说明 |

---

## 二、权限说明

| 权限 | 用途 | 何时申请 |
|---|---|---|
| `READ_MEDIA_AUDIO`（33+）/ `READ_EXTERNAL_STORAGE`（≤32） | 读取系统媒体库 | **仅在你选择「扫描全盘」时** |
| `MANAGE_EXTERNAL_STORAGE`（可选） | 全盘模式下的深度扫描，读取同目录 `.lrc` | 在 `⋮` 菜单中手动开启 |
| `POST_NOTIFICATIONS`（33+） | 播放控制通知 | 首次播放时 |
| `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)` / `WAKE_LOCK` | 后台播放 | 安装即声明 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | **仅**用于访问 `api.github.com` 检查更新、从 GitHub 下载新版本 APK | 安装即声明，实际只在检查/下载时使用 |
| `REQUEST_INSTALL_PACKAGES` | 下载完成后调起系统安装器 | 首次应用内安装时由系统弹窗确认 |

> **关于网络权限**：这是本应用唯一的联网能力，只为「应用内更新」服务，
> 只请求 `api.github.com` 与 GitHub 的 Release 下载地址，不采集、不上传任何信息。
> 在「设置 → 更新」中关闭「启动时自动检查更新」后，应用不会发起任何网络请求。
> 歌单、收藏、播放统计、歌词偏移、崩溃日志全部只保存在本机。

### 关于 `.lrc` 歌词的获取条件（重要）
系统媒体库（MediaStore）本身不索引 `.lrc` 文件，Android 10 之后 App 也无法直接访问
音频同目录的普通文件。因此要使用**独立歌词文件**，在「扫描范围」里二选一：

- **方式 A（推荐，不需要任何权限）**：扫描范围选「指定目录」→「选择音乐目录…」，
  用系统文件选择器授权音乐目录。授权后可读取该目录树下的音频与 `.lrc`。
- **方式 B（扫描全盘 + 深度扫描）**：扫描范围选「扫描全盘」，再在 `⋮` 菜单里授予
  「所有文件访问权限」（深度扫描），之后重新扫描。

内嵌歌词不受此限制，任何时候都能读取。

---

## 三、应用内更新是怎么实现的

```
App 启动 / 用户点「检查更新」
        │
        ├─ GET https://api.github.com/repos/LuckyLearning/WindTone/releases/latest
        │     · 只读公开接口，无需鉴权；自动跳过草稿与预发布
        │     · 解析 tag_name → 版本号，body → 更新说明，assets → APK 直链
        │
        ├─ 与 BuildConfig.VERSION_NAME 做语义化比较（3.10.0 > 3.9.2）
        │
        ├─ 有新版本 → 弹窗展示版本号 / 大小 / 更新说明
        │
        ├─ 下载 APK 到 cacheDir/updates/（先写 .part，校验 zip 头后再改名）
        │
        └─ FileProvider 交给系统安装器 → 用户确认 → 覆盖安装
```

关键实现都在 `data/UpdateChecker.kt`，几个刻意的设计：

- **不引入 OkHttp / Gson**，用 `HttpURLConnection` + Android 内置 `org.json`，避免为了一个更新功能撑大包体；
- **失败静默**：自动检查失败（离线 / 限流 / 仓库没发版）只打日志，不弹任何提示；
- **手动检查的失败会给明确原因**，例如「网络不可用」「连接 GitHub 超时」「仓库还没有发布正式版本」；
- 下载前清空 `updates/` 目录，避免多个历史安装包堆积；
- 下载完成后先校验文件头是不是 `PK\x03\x04`（zip/APK），半截文件不会交给安装器。

### 发布流程（打 tag 即出包）

```bash
# 1. 改版本号：app/build.gradle.kts 里的 appVersionCode +1、appVersionName 改新版本
# 2. 提交
git add -A && git commit -m "release: v1.0.1"
# 3. 打 tag 并推送 —— 推 tag 会自动触发 Release 工作流
git tag v1.0.1
git push origin main --tags
```

工作流 `.github/workflows/android-release.yml` 会：

1. 校验 tag 与 `build.gradle.kts` 的 `versionName` 一致（不一致直接失败，避免应用内更新判断错乱）；
2. 跑单元测试；
3. 用仓库密钥签名构建 Release APK；
4. 重命名为 `WindTone-v1.0.1.apk`，上传 artifact；
5. 创建 GitHub Release 并附上 APK、大小与 SHA-256。

> 也可以只推 `main`：`.github/workflows/android-ci.yml` 会跑单测 + 编译 debug 包做校验，不会发布。

---

## 四、源码结构

```
WindTone/
├─ .github/workflows/
│  ├─ android-ci.yml            主干/PR：单测 + debug 构建校验
│  └─ android-release.yml       打 tag：单测 + 签名 Release + 创建 Release
├─ app/src/main/java/com/luckylearning/windtone/
│  ├─ AppContainer.kt           依赖装配（手写服务定位器）
│  ├─ data/                     数据层（扫描 / 数据库 / 封面 / 歌词匹配 / 更新 / 维护 / 删除）
│  ├─ lyrics/                   歌词引擎（纯 JVM，可直接单测）
│  ├─ playback/                 播放引擎、播放服务与音效
│  ├─ state/                    界面状态模型（四个域共用）
│  ├─ ui/                       Compose 界面与四个分域 ViewModel
│  ├─ overlay/ widget/ tile/    悬浮歌词 / 桌面小部件 / 快捷磁贴
│  └─ MusicApp.kt MainActivity.kt
├─ app/src/test/                59 个单元测试（歌词解析 / 更新检查 / 播放模式 / Room DAO）
├─ scripts/                     图标生成（Python）、本地 SDK 引导（bat）、发版与推送助手
├─ gradle/                      Wrapper + **`libs.versions.toml`（依赖与插件版本唯一来源）**
├─ gradlew gradlew.bat          Gradle Wrapper 启动脚本
├─ build.gradle.kts settings.gradle.kts gradle.properties
└─ .toolchain/                  本地专属：Android SDK + 签名库（已 gitignore）
```

| 模块 | 关键文件 | 说明 |
|---|---|---|
| 歌词解析（LRC） | `lyrics/LrcParser.kt` | 时间轴 / 元信息 / 多标签 / 增强逐字标签 / offset / 双语合并 |
| 逐字歌词 | `lyrics/WordLyricsParser.kt` | KRC（XOR+zlib）/ YRC 逐字时间轴 |
| 编码嗅探 | `lyrics/TextCodec.kt` | BOM + UTF-8/GB18030 严格校验 |
| 内嵌歌词 | `lyrics/EmbeddedTags.kt` | ID3v2(SYLT/USLT/TXXX)、FLAC Vorbis Comment、Ogg/Opus、MP4 ©lyr |
| 随机访问读取 | `lyrics/BinaryReader.kt` | 基于 FileChannel，可直接读 `content://` 描述符 |
| 音乐库聚合 | `data/MusicRepository.kt` | 按扫描范围分流、三来源合并去重 |
| 数据库 | `data/db/MusicDb.kt` | Room：曲库 / 目录 / 每曲状态 / 歌单 / 队列 / 播放态 / 搜索历史（8 表） |
| 音频技术信息 | `data/AudioInspector.kt` | `MediaExtractor` 读轨道级参数 |
| 文件删除 | `data/SongDeleter.kt` | 媒体库 / SAF / 文件路径三套删除通道 |
| **应用内更新** | `data/UpdateChecker.kt` | GitHub Releases 查询、APK 下载校验、FileProvider 安装 |
| 崩溃日志 | `data/CrashLog.kt` | 本地落地，保留最近 5 份，设置页可导出 |
| 播放服务 | `playback/PlaybackService.kt` | Media3 MediaSessionService |
| 播放桥接 | `playback/PlaybackConnection.kt` | MediaController 连接与 MediaItem 构造 |
| 播放句柄 | `playback/PlayerHandle.kt` | ExoPlayer 专有开关（跳过静音）的进程内通道 |
| **播放引擎** | `playback/PlaybackEngine.kt` | 应用级单例：队列持久化与恢复、播放统计、歌词装载、全部播放动作 |
| 音效 | `playback/AudioFx.kt` | 均衡器 / 低音 / 环绕 / 响度增强 |
| 睡眠定时 | `playback/SleepTimer.kt` | 倒计时 / 播完当前曲 |
| 依赖装配 | `AppContainer.kt` | 手写服务定位器：各层单例集中在此 |
| 共享状态 | `data/SongIndex.kt` · `data/MessageBus.kt` | uri↔数据库主键映射 + 跨域一次性提示 |
| 状态管理 | `ui/LibraryViewModel.kt` · `PlayerViewModel.kt` · `UpdateViewModel.kt` · `SettingsViewModel.kt` | 按曲库 / 播放 / 更新 / 维护四个域分家 |
| 进度与渲染分离 | `PlaybackEngine` · `PlaybackProgress` | 播放进度走独立 `StateFlow`，再以 `State<T>` 下沉到叶子组件读取；主界面不再按秒重组 |
| 通用组件 | `ui/Common.kt` | 卡片化列表项 / 迷你播放条 / 骨架屏 / Canvas 唱片插画空态 |
| 主题 | `ui/theme/Theme.kt` | 封面取色派生 M3 全量色板、统一 5 档圆角 `AppShapes` |
| 设置页 | `ui/SettingsScreen.kt` | 统一设置 / 统计 / 崩溃日志 / **更新** / 关于 |
| 歌词界面 | `ui/LyricsPane.kt` | 居中跟随滚动、逐字染色、双语副行、点击跳转 |

---

## 五、本地构建

### 环境要求
- JDK 17 或 21
- Android SDK：platform 35 + build-tools 35.0.0
- Gradle 8.9（用仓库自带的 wrapper 即可）
- AGP 8.7.2 / Kotlin 2.0.21 / Compose BOM 2024.10.01 / Media3 1.4.1
  —— 依赖与插件的版本统一由 `gradle/libs.versions.toml`（Gradle Version Catalog）管控，升级只需改该文件中的一个版本号

### Android Studio
直接 `Open` 本仓库根目录即可（仓库根就是 Gradle 工程根）。
`local.properties` 不入库，Android Studio 会自动生成，或用下面的脚本指向自带的 SDK。

### 命令行
```bash
# 单元测试（歌词解析，纯 JVM，不需要设备）
./gradlew :app:testDebugUnitTest

# 出包
./gradlew :app:assembleDebug :app:assembleRelease

# 只生成 lint / 测试报告
./gradlew :app:testDebugUnitTest --info
```

产出：`app/build/outputs/apk/release/WindTone-v<版本>.apk`（Debug 为 `.../debug/WindTone-v<版本>-debug.apk`）

Windows 下首次可先跑 `scripts\sdk-setup.bat` 把 SDK 装到 `.toolchain\android-sdk`。

### 签名

本项目**本地构建与 CI 使用同一套密钥库** `.toolchain/keystore/windtone.jks`，
所以两边产出的 APK 签名一致、可以互相覆盖安装。

密钥库本身不进仓库（`.toolchain/` 与 `*.jks` 均已 gitignore），取值优先级：

1. `-PkeystorePath=...` 等命令行参数（临时覆盖）
2. 环境变量 `WINDTONE_KEYSTORE` / `WINDTONE_STORE_PASSWORD` / `WINDTONE_KEY_ALIAS` / `WINDTONE_KEY_PASSWORD`（CI 从 Secrets 注入）
3. 本地 `keystore.properties`（已 gitignore，开发机专属）
4. 默认路径 `.toolchain/keystore/windtone.jks`

四者都取不到时产出**未签名包**（`assembleRelease` 仍然成功）。

当前证书：`CN=WindTone, OU=Mobile, O=LuckyLearning, C=CN`，
SHA-256 `75EAECCD7CAB0DB4E30170F52091B3BF80AC0F1D6F89235433EBE2B5876E7398`。

> ⚠️ 覆盖安装要求前后版本签名一致。**一旦密钥库或口令丢失，后续版本就无法覆盖升级**，
> 用户只能卸载重装。请把 `windtone.jks` 与口令单独备份到安全位置（密码管理器 / 离线介质），
> 不要只留在开发机上。

### CI 需要的 Secrets

在仓库 **Settings → Secrets and variables → Actions** 里配置：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | 密钥库文件的 base64：`base64 -w0 .toolchain/keystore/windtone.jks` |
| `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_ALIAS` | 密钥别名（`windtone`） |
| `KEY_PASSWORD` | 密钥口令 |

未配置时工作流仍会成功，但产物不带签名，不能直接覆盖安装。

---

## 六、已知限制

- WMA / APE / DSF 等格式需要额外解码器，当前版本会跳过（列表统计里会提示数量）。
  补齐需引入 `media3-decoder-ffmpeg`，代价是包体积 +10~20 MB。
- 系统媒体库来源的歌曲，默认无法匹配同目录 `.lrc`（Android 存储沙箱限制），
  需要「导入文件夹」或「深度扫描」。
- 「编辑歌曲信息」只做**应用内显示覆盖**（`song_overrides` 表），不写回音频文件。
- 应用内更新依赖 GitHub 可达性；网络受限时可在设置页点「打开发布页面」手动下载。
- 桌面悬浮歌词只支持在 Android 8.0+ 上运行，且需要系统「显示悬浮窗」授权；部分厂商 ROM
  （HyperOS / MIUI / DiLink）还需额外把应用加入白名单或放行「后台弹出界面」，细节见设置页的「悬浮歌词自检」。
- 音频信息里的「位深」取决于容器是否声明该字段，WAV / FLAC 一般有，MP3 一般为空。

---

## 七、版本历史

| 版本 | 主要内容 |
|---|---|
| **1.0.1** | **播放控制与音乐库整理**。<br>• 播放模式合并：随机播放与循环模式原先各占一个按钮，现合并为播放页上的**单个按钮四态轮换**（顺序播放 → 列表循环 → 单曲循环 → 随机播放），切换时浮出模式名提示；控制区由 5 个按钮减为 4 个。<br>• 文件夹视图：不再显示无路径歌曲聚成的「未知目录」；SAF 来源的歌曲补齐真实目录（按目录树相对路径 + URI 反推），新增**「移除扫描结果」**可一次性清掉某个文件夹下的全部歌曲（不删除手机里的音频文件）。<br>• 新增 `PlayMode` 轮换与推导的单元测试，单测总数 28 → 32。 |
| **1.0.0** | **首个公开版本**。汇聚开发期多轮迭代的成果：三来源扫描（系统媒体库 / 指定目录 / SAF）、LRC 与内嵌歌词（含 KRC/YRC 逐字时间轴、双语合并、拼音搜索）、Media3 后台播放与通知栏控制、Room 歌单与播放统计、均衡器与响度增强、睡眠定时、桌面小部件与快捷磁贴、崩溃日志；R8 混淆后约 2.9 MB；界面为 M3 动态配色 + 封面主色渐变。<br>本版同时落地工程化能力：GitHub Actions（打 tag 自动出**已签名**包并创建 Release，主干/PR 跑 28 个单元测试）、应用内版本检测与一键升级（GitHub Releases 查询 → APK 下载校验 → FileProvider 安装）。 |

---

## License

[Apache License 2.0](LICENSE)
