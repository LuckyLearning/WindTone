---
layout: default
title: AI 开发指南
nav_exclude: false
nav_order: 11
parent: 开发文档
---

# 听风 WindTone · AI 开发指南

> **读者**：在本仓库新开上下文接手的 AI 助手（也适用于新人开发者）
> **目的**：读完这一份就能直接改代码，不用再翻遍全仓
> **对应版本**：v1.1.1（versionCode 11）
> **最后更新**：2026-10-14（分域架构重构 + 去兼容层）

---

## 0. 先读这一段（30 秒速览）

### 这是什么

**听风 WindTone** 是一个纯本地的 Android 音乐播放器（Kotlin + Jetpack Compose + Media3）。
不依赖任何在线音乐服务，播的是本机音频文件；唯一的联网功能是「应用内更新」——读公共分发仓库的 latest.json 查新版本。

- 仓库：`https://github.com/LuckyLearning/WindTone`（**public**）
- 发布方式：打 `v*` tag → GitHub Actions 自动构建**已签名** APK 并创建 Release

### 一句话架构

**四个分域 ViewModel 各自持有自己那块状态，共享的底层服务由 `AppContainer` 提供。**

```
Compose UI（MainScreen / PlayerScreen / Sheets / SettingsScreen）
   ↓ 各自 collectAsStateWithLifecycle，不再有一个"万能 vm"
LibraryViewModel   PlayerViewModel    UpdateViewModel    SettingsViewModel
（曲库/扫描/浏览）  （播放，接口委托） （更新，接口委托）  （维护/诊断/日志）
   ↓                   ↓                  ↓                  ↓
   └───────────────────┴──────────────────┴──────────────────┘
                            ↓
                    AppContainer（手写服务定位器，by lazy）
   ┌──────────────┬──────────────┬──────────────┬──────────────┐
 MusicRepository  PlaybackEngine  UpdateRepository  DataMaintenance
 （扫描/排序/搜索）（Media3+队列+统计）（检查/下载/安装） （快照/清理）
   ↓                    ↓
 MediaStoreScanner   PlaybackService (MediaLibraryService)
 FolderScanner/FileScanner

 两个跨域共享件：SongIndex（uri↔dbId + 每曲状态）· MessageBus（一次性提示）
```

**要点**：
- `PlayerViewModel` / `UpdateViewModel` **不写实现**，用 Kotlin 接口委托（`PlayerActions by engine`）把动作整体转出，
  所以它们只有几十行；真正的逻辑在 `playback/PlaybackEngine.kt`、`data/UpdateRepository.kt` 这两个**普通类**里。
- 播放引擎是**应用级单例**（不存在 `onCleared()`），退到后台由 `setAppForeground(false)` + 计时器自停承担节流。
- 跨域只经两个窄接口：**状态**走 `SongIndex`，**瞬时提示**走 `MessageBus`；没有任何一个域反向依赖另一个域的 UiState。

### 立刻能用的事实表

| 项目 | 值 |
|---|---|
| 包名 / namespace / applicationId | `com.luckylearning.windtone`（debug 版加 `.debug` 后缀，可与 release 共存） |
| 版本号定义位置 | **`app/build.gradle.kts` 顶部** `appVersionName` / `appVersionCode`（单一来源） |
| 依赖/插件版本定义位置 | **`gradle/libs.versions.toml`**（Version Catalog）；脚本里只写 `libs.*` 访问器 |
| 当前版本 | `1.1.1` / code `11` |
| minSdk / targetSdk / compileSdk | 24 / 35 / 35 |
| Java / Kotlin | Java 17（source/target/jvmTarget）、Kotlin 2.0.21 |
| Gradle / AGP | 8.9（Wrapper）/ 8.7.2 |
| 签名库 | `.toolchain/keystore/windtone.jks`（alias `windtone`）—— **本地与 CI 同一套** |
| APK 产物名 | `WindTone-v1.1.3.apk`（debug：`WindTone-v1.1.3-debug.apk`）—— 版本号随发版变化 |
| 应用显示名 | 英文 `WindTone` / 中文 `听风`（`values/` 与 `values-zh/`） |
| 数据库 | `music_player.db`，Room **v5**，9 张表（schema 快照在 `app/schemas/`） |
| 单元测试 | **349 个**：`lyrics/` / `UpdateChecker` / `PlayMode` 为纯 JVM；`db/` 与 `playback/` 走 Robolectric（真实 SQLite）；`ui/` 是 Compose 界面冒烟（见 §11.13） |

### ⚠️ 兼容口径（2026-10-14 起，务必先读）

产品已拍板 **不做跨版本数据兼容：软件重新手动安装即可**。因此代码里**没有任何迁移路径**：

- `MusicDb` 只挂 `fallbackToDestructiveMigration()`；改 schema **不写 Migration**，结构对不上就整库重建
  （曲库由重扫恢复，歌单/收藏/统计会丢——这是已接受的口径，不是 bug）。
- Prefs 里**没有**遗留键搬迁逻辑，也没有 `data_v4_migrated` 之类的幂等标志。
- 因此**也没有迁移回归测试**（`MigrationV3ToV4Test` 已删除）。

> 换句话说：**当前形态就是唯一形态**。改 schema 时把 `version` +1、重导 schema 快照、同步文档即可，
> 不必再考虑"老用户升级怎么办"。若日后想恢复兼容，Room 会优先走 `addMigrations(...)` 而非兜底，补上即可。

### 五条铁律

1. **不要改包名、DB 名、Prefs 名、通知渠道 ID**——它们决定老用户能否覆盖升级、数据能否保留。改名 = 所有歌单/收藏/播放统计清零。
2. **版本号只改 `app/build.gradle.kts` 顶部那两行**，别去改 `defaultConfig` 里的 `versionCode`/`versionName`。CI 的一致性校验按顶部变量做正则匹配，改错地方会导致 tag 校验失败。
3. **`docs/` 已在 `.gitignore` 里**——本目录是本地开发资料，**不会推到 GitHub**。往这里写文档是安全的。
4. **过期没用的东西一律删掉，不要"先留着"**（用户 2026-10-26 明确要求，见 0.1 节）。
5. **GitHub 一律用本地 `.github-token`，禁止弹窗要账号**（用户 2026-10-28 明确要求，见 0.2 节）。拉取、推送、发版、查 Actions/Release、应用内更新链路的 API 核验都适用。
---

### 0.1 删除优于保留（铁律 4 的展开）

这个项目里"留着以防万一"的东西最后都变成了误导读者的噪声：旧尺寸常量、
已被新实现取代的函数、删了功能却留下的状态与文案。**默认动作是删，不是注释掉、不是标记 deprecated。**

#### 命中任一条就删

| 情况 | 例子（真实发生过的） |
| --- | --- |
| 符号已无引用（类 / 函数 / 常量 / 字符串资源） | `CAR_SIDE_TILE`、`CAR_MAIN_BUTTON` 在改用 `CarMetrics` 后没人读 |
| 被新实现取代的旧路径，**连同它的测试** | `OverlayCollapseHelper.handlePositions`（两钮）→ `handleColumn`（任意颗） |
| 功能整体移除后留下的状态 / Prefs 字段 / 动作 / 文案 / 文档段落 | 移除「双语歌词」切换后清掉 `lyricBilingual`、`setLyricBilingual`、`BilingualLyrics` 与 10 条字符串 |
| 只在注释里"作为参考"保留的代码 | 旧尺码常量——历史写进注释即可，代码不占位 |
| 过期文档 | 进 `docs/archive/`，并在 `docs/DELETIONS.md` 记一行 |

#### 必须保留的（别删错）

1. **对外契约**：包名、DB 名、Prefs 名、通知渠道 ID、已发布的 `action` 字符串（铁律 1）。
2. **老用户 SharedPreferences 里的死键**：不再读取即可，**但不要为它写兼容分支**；也不主动清理。
   现存死键：`lyric_word_by_word`、`lyric_sung_color`、`lyric_bilingual_mode` —— 见到别当 bug 排查。
3. **发布过的文案 key 若仍被商店/文档引用**（本项目没有商店，可忽略）。

#### 执行方式（四步，缺一不可）

1. **删前全仓 grep**：`app/src`（含 test）+ `docs/`，确认零引用；注意 `R.string.xxx` 与 `getIdentifier` 两种引用方式；
2. **连测试一起删**：留着测一个已删函数，等于留了一处必然失败的负债；
3. **删后必须** `./gradlew compileDebugKotlin compileDebugUnitTestKotlin testDebugUnitTest` 全绿；
4. **记账**：代码删除在版本历史写一句"删了什么"；文档删除/归档必须在 `docs/DELETIONS.md` 追加一行。

---


### 0.2 GitHub 鉴权须知（AI / 自动化必读，2026-10-28）

> **做错一次就会反复弹窗烦用户。** 本机已有 PAT，不要再走交互登录。

#### 必须

| 动作 | 正确做法 |
| --- | --- |
| `git fetch` / `git pull` / `git push` / 推 tag | **`bash scripts/push.sh`**（推送）；拉取同等用同一套 `GIT_ASKPASS` + `.github-token`。详见根目录 **`.github-token.example`** |
| 查 Release / Actions / 删 tag / verify | `Authorization: Bearer $TOKEN`，TOKEN 只从 `.github-token` 用下面的 `grep` 抽出 |
| 发版 | `bash scripts/release.sh …`（内部已走 `push.sh`） |

#### 禁止

- 弹 Vault / 账号确认卡 / 浏览器 `gh auth login` / `gh auth refresh` / 让用户一次次点授权
- 把 token 写进 remote URL、`git config`、commit、聊天、日志摘要
- 整文件 `cat .github-token` 当 token（文件可能含注释）——**必须** `grep -oE '(ghp_|github_pat_)[A-Za-z0-9_]+' .github-token | head -1`
- 本机没有 `gh` CLI 时硬装并交互登录；用 `git` + REST 即可

#### 怎么配（给人看的）

1. 复制 `.github-token.example` → `.github-token`
2. 只留**一行** PAT（`ghp_` 或 `github_pat_` 开头），无引号、无注释混在同行
3. 权限见 example（Contents / Workflows / Actions 读写）

`.github-token` 已在 `.gitignore`，**绝不要改名后提交**。

### 0.3a Actions 触发矩阵（2026-10-31）

| 工作流 | 何时自动跑 | 手动 |
| --- | --- | --- |
| Android CI | **不自动**（push/PR 已关） | Actions → Android CI → Run workflow |
| Release APK | 推送 `v*` tag | 可 workflow_dispatch（一般用 tag） |
| Pages | 改 `docs/**`（或本 workflow）推 main | Actions → Deploy docs → Run workflow |

详情与省分钟背景见 [`spike-CI提速.md`](spike-CI提速.md) §9。

### 0.3 Actions 额度不足时的发版策略

> **现象**：GitHub Actions 分钟 / 消费额度用尽时，打 `v*` tag 会立刻触发 workflow 并**秒失败**，白白烧额度、也出不了 APK。

#### 额度不足时（只推代码）

1. **只推代码到 `main`**：`bash scripts/push.sh origin main`（铁律 5 / §0.2：一律 `.github-token`，**禁止** GCM / Vault / `gh auth` 弹窗）。
2. **禁止** `bash scripts/release.sh …`、禁止 `git tag` / 推 `v*`、禁止改 `appVersionName` / `appVersionCode` 冲发版。
3. 功能继续合入 main；验证推 main 即可（不强制每项立刻发版）。若有「已合入、未出包」记进 [`待发版积压.md`](待发版积压.md)；真机勾选见 [`真机验证清单.md`](真机验证清单.md) **§5**。

#### 额度恢复后

> **发版不跑单测（2026-10-31）**：`scripts/release.sh` 默认跳过 `testDebugUnitTest`；单测只在功能合入时跑。`--verify` 只等云端 Release 资产（APK / mapping / latest.json），不本地测。云端 `android-release` 保留 lint Error + 签名出包，**不再**跑全量单测；`android-ci` 仍仅手动。避免「本地发版测 + Actions 再测」重复烧时间/额度。

按 [`待发版积压.md`](待发版积压.md) 的顺序操作：先核已有 tag 的 Release 资产（无包则 **Re-run**，勿改号），再对新合入走标准 `release.sh`（§8.3）。构建对照 [`可复现构建.md`](可复现构建.md)；CI 省时见 [`spike-CI提速.md`](spike-CI提速.md)。

## 1. 产品概览

### 1.1 定位

面向「有本地音乐文件、想要一个干净播放器」的用户。核心诉求：启动快、扫描可控、歌词体验好、不偷跑流量。

### 1.2 功能全景

| 模块 | 能力 |
|---|---|
| **音乐库** | 三种来源（系统媒体库 / SAF 目录 / 文件路径深度扫描）；五视图（歌曲·专辑·歌手·文件夹·歌单）；拼音与首字母搜索（`pinyin4j`）；6 种排序；字母悬浮索引 |
| **扫描** | **默认不扫全盘**（`ScanScope.UNSET`），首次进入停在「选择音乐位置」页；可选全盘 / 指定目录；扫描结果落 **`songs` 曲库**（增量 upsert + 软删），二次启动直接读曲库 |
| **歌词** | 同名 `.lrc`/`.krc`/`.yrc`/`.txt` 旁挂、音频内嵌标签、双语（原文+翻译）、逐曲偏移（±20s）、字号缩放、桌面悬浮歌词（1·3 行 / 字号 / 对齐 / 底板 / 颜色 / 双语副行） |
| **播放** | Media3 ExoPlayer + MediaSessionService；后台播放、耳机/蓝牙控制；播放队列整队列持久化（`queue_songs` 表）；变速（0.5x–2.0x）；跳过静音；睡眠定时（倒计时/播完当前曲/专辑/队列）；响度增强 |
| **音效** | 音效方案一键包（8 条，`SoundSchemes`）、均衡器（16 个内置预设 + 5/6/10 段自定义）、低音增强、虚拟环绕、响度增强、**混响**（`AudioFx`，基于系统 `AudioEffect`，见 §6.10）；播放过渡：切歌淡入淡出 + 曲间音量平衡 + A/B 对比原声（`TrackVolume`，v1.1.52） |
| **歌单/收藏** | 自建歌单（增删改、排序）、收藏、最近播放、最常播放、播放统计（次数/累计时长） |
| **数据维护** | 诊断页「数据与存储」：曲库/目录/歌单/缓存占用快照、清理失效歌曲、清空封面缓存、重置用户数据（保留曲库） |
| **个性化** | 封面主色动态主题、Material 3 色板、浮动迷你条、封面上下滑调音量、迷你条拖拽跳转 |
| **系统集成** | 桌面小组件（上一首/播放/下一首）、快捷设置磁贴、崩溃日志本地留存与导出 |
| **更新** | 读公共分发仓库 `latest.json` → 下载 APK（进度条，多线路）→ SHA-256 校验 → FileProvider 交系统安装器 |

### 1.3 界面导航结构

```
MainActivity
└── MusicPlayerTheme（封面主色动态取色）
    └── MainScreen
        ├── 首次进入：ScanSetupView（选择音乐位置）
        ├── 扫描中：ScanningState
        └── 主界面
            ├── LibraryHeader（搜索框 + 视图切换 + 排序 + 字母索引）
            ├── 列表区（按当前 LibraryView 渲染）
            │   ├── SongsList / GroupListView / GroupDetail
            │   └── PlaylistListView / PlaylistDetail
            └── 底部迷你播放条（可拖拽跳转、左右滑切歌）
                └── PlayerScreen（点击展开：封面 + 歌词 + 控制）
                    └── LyricsPane（歌词面板）
        ├── Sheets（抽屉/弹窗：队列、播放设置、编辑歌曲信息……）
        └── SettingsScreen（设置页）
```

---

## 2. 技术栈（版本锁定）

依赖与插件的版本**全部集中在 `gradle/libs.versions.toml`（Gradle Version Catalog）**，
`build.gradle.kts` 里只写 `libs.xxx.yyy` 访问器，**任何字面版本号都不许再出现**。

唯一例外是产品自身的版本号 `appVersionName` / `appVersionCode`，它们仍在 `app/build.gradle.kts` 顶部 ——
因为 `scripts/release.sh` 与 `.github/release-notes` 渲染器都按固定文本格式 `grep` 这两行，搬进 TOML 会连带改掉整条发版链路。
**第三方依赖版本在 catalog，产品版本在模块脚本。**

### 2.1 构建插件（`gradle/libs.versions.toml` 的 `[plugins]`）

| 插件 | 版本 | 访问器 |
|---|---|---|
| `com.android.application` | 8.7.2 | `libs.plugins.android.application` |
| `org.jetbrains.kotlin.android` | 2.0.21 | `libs.plugins.kotlin.android` |
| `org.jetbrains.kotlin.plugin.compose` | 2.0.21 | `libs.plugins.kotlin.compose` |
| `com.google.devtools.ksp` | 2.0.21-1.0.27 | `libs.plugins.ksp` |

> Kotlin 2.0 起 Compose 编译器随 Kotlin 版本走，`kotlin-compose` 与 `kotlin-android` 共用同一个 `kotlin` 版本引用；
> `ksp` 的版本号格式是「Kotlin 版本-ksp 版本」，**前半段必须与 `kotlin` 严格一致**，否则 KSP 会拒绝启动。
> 升级时三者要一起动：`agp` / `kotlin` / `ksp`，并核对 Gradle Wrapper 版本与之配套。

### 2.2 依赖（`gradle/libs.versions.toml` 的 `[libraries]`）

| 类别 | 依赖（版本引用） |
|---|---|
| 基础 | `core-ktx`(androidxCore)、`appcompat`、`activity-compose`、`documentfile` |
| Lifecycle | `lifecycle-runtime-ktx / runtime-compose / viewmodel-compose / lifecycle-service`(androidxLifecycle) |
| Compose | BOM `androidx-compose-bom`(composeBom) + `ui`、`ui-graphics`、`ui-tooling-preview`、`material3`、`material-icons-core`、`ui-tooling`(debug) |
| 播放 | `media3-exoplayer / media3-session / media3-common`(media3) |
| 数据库 | `room-runtime / room-ktx / room-compiler`(room)；`room-compiler` 走 `ksp(...)` 配置；`ksp { room.schemaLocation = app/schemas }`，**schema 快照入库**（排障与核对索引时对照它） |
| 其他 | `pinyin4j`（拼音搜索）、`guava`、`kotlinx-coroutines-android` |
| 测试 | `junit` + `robolectric` + `androidx-test-core`（让 Room 在 JVM 单测里跑真实 SQLite） |

**两条 catalog 特有的规矩**：

1. **Compose 系列的库故意不写 `version`** —— 版本交给 BOM 统一对齐。给它们单独写版本反而会覆盖 BOM 结果、破坏内在一致性。
   引用 BOM 时必须包一层：`val composeBom = platform(libs.androidx.compose.bom)`。
2. **别名用 kebab-case，Gradle 转成点号访问器**：`androidx-compose-ui-graphics` → `libs.androidx.compose.ui.graphics`。
   同名前缀是允许的（`libs.androidx.compose.ui` 与 `libs.androidx.compose.ui.graphics` 可以共存）。

### 2.3 刻意不引入的东西

- **没有** OkHttp / Retrofit / Gson —— 应用内更新只用 `HttpURLConnection` + Android 内置 `org.json`，避免为一个小功能背一整套网络栈。
- **没有** Hilt / Koin —— 依赖图是一棵浅树、也没有"测试时替换作用域"的需求，`AppContainer`（手写服务定位器 + `by lazy`）已经够用。
- **没有** Navigation Compose —— 界面切换靠 `LibraryView` 状态 + `AnimatedContent`/条件渲染。
- **没有** 用 catalog 的 `[bundles]` —— 显式列出每个依赖比 bundle 更易读（这个项目的构建脚本刻意做到"读一遍就知道全部依赖"）。

---

## 3. 仓库与目录结构

### 3.1 顶层

```
WindTone/                        ← 仓库根 == Gradle 根（已扁平化，没有嵌套子工程目录）
├─ app/                          ← 唯一的 Gradle 模块（`app/schemas/` 是 Room schema 快照，**入库**）
├─ .github/
│   └─ workflows/
│       ├─ android-release.yml   打 v* tag → 单测/lint 门禁 + 签名包 + Release（唯一自动 CI）
│       ├─ pages.yml             docs/** → GitHub Pages（亦可手动）
│       └─ android-ci.yml        仅 workflow_dispatch → 单测 + debug 包（日常 push 不跑）
├─ docs/                         ⚠️ 本地开发资料，已 gitignore，**不会上传**
│   ├─ AI开发指南.md（本文）      接手必读
│   ├─ 数据库设计-MusicDb.md     表结构 / 红线
│   ├─ 数据层重构规划.md         v3→v4 的来龙去脉
│   ├─ 功能规划.md / -第二期 / -第三期（第四期·云盘音乐规划文档已按用户要求删除，2026-10-20）
│   ├─ 版本号发版规则.md          版本号规则 + 发版流程（唯一真源，含一键发版与验证链）
│   └─ ui/                       ⭐ UI 唯一视觉真源（7 章 / 57 屏，改 UI 先改它）
│        index.html              总览：章节卡 + 屏索引（脚本扫各章生成）
│        01-main … 07-spec.html  分章屏稿（改哪屏只读哪章）
│        ui.css / ui.js / ui-sprite.js  样式 / 行为 / 图标池（分章文件不写内联）
├─ scripts/
│   ├─ push.sh                   用 .github-token 推送（GIT_ASKPASS，token 不落盘；退出码已根治 trap 污染）
│   ├─ release.sh                跑单测 → 改版本号 → 提交 → 打 tag → 推送 + 推后查证（--verify 接着跑验证链）
│   ├─ verify-release.sh         ★ 四步验证链：tag → workflow → 4 资产 → latest.json → 验签（--skip-ci 跳过轮询）
│   ├─ verify_apk.py             ★ 下 universal 包验签名证书 + 包内版本串（无 apksigner，自解析 Signing Block）
│   ├─ publish-release-repo.sh   CI 用：把产物发到公共仓库 LuckyLearning/Release（快照 force push）
│   ├─ render-release-notes.sh   渲染发版说明（固定模板 + 只换版本号）
│   ├─ gen-release-manifest.py   生成公共仓库 latest.json / README 版本行
│   ├─ sdk-setup.bat             Windows 本地 SDK 引导
│   └─ gen_icons.py              图标生成
├─ gradle/
│   ├─ libs.versions.toml        ⭐ 依赖与插件版本的**唯一来源**（Version Catalog）
│   └─ wrapper/                  Wrapper 8.9（CI 必需，务必入库）
├─ gradlew / gradlew.bat         可执行位已在 git 索引中修正（100755）
├─ build.gradle.kts              根：只声明插件（别名指向 catalog），版本不落地
├─ settings.gradle.kts           rootProject.name = "WindTone"（默认加载 gradle/libs.versions.toml）
├─ gradle.properties             JVM 内存 3072m、parallel、caching
├─ .gitignore / .gitattributes
├─ README.md                     对外文档（功能/权限/发布流程）
├─ LICENSE                       Apache 2.0
├─ keystore.properties           ⚠️ 本地签名口令，已 gitignore
├─ local.properties              ⚠️ sdk.dir，已 gitignore
├─ .github-token / .example      ⚠️ PAT，已 gitignore（.example 是模板，入库）
├─ .toolchain/                   ⚠️ 本地工具链（Android SDK + keystore），已 gitignore，数百 MB
├─ logs/                         ⚠️ 构建日志，已 gitignore
└─ release/                      ⚠️ 本地产出的历史 APK，已 gitignore
```

### 3.2 源码树（`app/src/main/java/com/luckylearning/windtone/`）

```
AppContainer.kt           ⭐ 手写服务定位器（`by lazy`）：Prefs / MusicDb / MusicRepository / SongIndex /
                          MessageBus / PlaybackEngine / UpdateRepository / DataMaintenance
MusicApp.kt               Application：装配 CrashLog、建通知渠道、暴露 `container`
                          （**不再做 Prefs→Room 搬迁** —— 本版无搬迁逻辑）
MainActivity.kt           唯一 Activity：setContent → 建 4 个 ViewModel → 主题取色

state/                    ── 界面状态类型（纯数据类，不依赖 Android，四个域共用）──
  UiState.kt              LibraryUiState / PlayerUiState / PlaybackProgress / UpdateUiState +
                          LibraryView / GroupInfo / PlaylistInfo / VirtualPlaylist / PlayStats / PlayMode

data/                     ── 数据与 IO 层 ──
  MessageBus.kt           ⭐ 跨域一次性提示总线（SharedFlow + DROP_OLDEST），取代借道 UiState.message
  SongIndex.kt            ⭐ uri↔dbId 映射 + 每曲状态整表；曲库域与播放域之间唯一的共享状态
  ThemeColorExtractor.kt  从封面位图提取主色（Palette 风格，无外部依赖）
  model/Song.kt           @Immutable 领域模型（Song / SongSource）
  MusicRepository.kt      扫描编排 + 排序 + 搜索（loadLibrary / sort / search）
  MediaStoreScanner.kt    来源 A：系统媒体库（MediaStore.Audio）
  LocalScanners.kt        来源 B/C：FolderScanner（SAF 目录）、FileScanner（文件路径深度扫描）
  MetadataReader.kt       读取音频文件元数据（标题/歌手/专辑/时长）
  LyricsLoader.kt         歌词装配：内嵌 → 旁挂文件 的优先级
  LyricsMatcher.kt        同名歌词文件匹配策略
  PinyinIndex.kt          拼音索引 + 首字母（带缓存）
  CoverRepository.kt      封面三级缓存：内存位图（maxMemory/8）→ 磁盘（cacheDir/covers，50MB LRU）→ 解码（尺寸=显示尺寸，并发单飞）
  AudioInspector.kt       解析音频文件技术信息（采样率/位深/码率）供设置页展示
  SongDeleter.kt          删除歌曲文件（媒体库确认框 / SAF / 直接路径）+ 联动清理
  DataMaintenance.kt      数据自检与维护（统计快照 / 清理失效歌曲 / 重置用户数据 / 封面缓存）
  SafPaths.kt             SAF 文档 ID ↔ 显示名/完整路径 的换算（文件夹视图分组键）
  CrashLog.kt             崩溃日志落盘、导出、清空
  Prefs.kt                SharedPreferences 封装（所有用户偏好的唯一入口）
  UpdateChecker.kt        应用内更新：查询 Release、语义化比对、下载、校验、安装
  UpdateRepository.kt     ⭐ 更新域持有者：自有 scope + UpdateUiState + 12 个动作（文件尾声明 UpdateActions 接口）
  db/Entities.kt          v4 八张表的实体定义
  db/Daos.kt              7 个 DAO + 投影类
  db/MusicDb.kt           Room 装配（entities / version / schema 导出）；**本版不注册任何迁移**
  db/SongMapping.kt       SongEntity ↔ Song 映射（含标签覆盖合并）
  online/                 ── 在线播放列表（m3u / m3u8 / pls）──
    M3uParser.kt          纯解析：M3U/PLS/HLS 判定、相对路径解析、`Artist - Title` 拆分（17 例单测）
    OnlinePlaylistRules.kt 纯规则：地址规范化 / 默认名 / MIME / 网页识别 / 4MB 上限 / GB18030 回落 / 异常转人话
    NetworkPlaylistFetcher.kt 唯一碰网络的文件（HttpURLConnection + 超时 + UA）
    OnlinePlaylists.kt    编排与持久化（SharedPreferences + JSON）：add / refresh / rename / remove / toSongs
                          （`fetcher` 是注入点，单测不连网；见 `docs/数据层重构规划.md` §14）

lyrics/                   ── 纯 JVM 逻辑，可单测 ──
  Lyrics.kt               Lyrics / LyricLine / LyricSource 模型
  LrcParser.kt            LRC 解析（时间轴、翻译行合并、增强标签剥离）
  KrcParser.kt            KRC / YRC 解析（解密解压后**只保留行时间轴**，行内标签剥离）
  TextCodec.kt            文本编码探测（UTF-8/GBK/BIG5 等），歌词乱码就找它
  EmbeddedTags.kt         音频容器内嵌歌词标签解析（ID3 USLT / FLAC VORBIS / MP4 ©lyr）
  BinaryReader.kt         二进制读取工具

playback/                 ── 播放层 ──
  PlaybackEngine.kt       ⭐ 播放域持有者（约 1000 行，**应用级单例**）：player / progress / themeSeed
                          三个流 + 队列持久化与恢复 + 播放统计 + 歌词装载 + 全部播放动作
                          （文件尾声明 `interface PlayerActions`，37 个方法）
  PlaybackService.kt      MediaLibraryService（前台服务：通知栏与锁屏控制 + 车机浏览树回调）
  LibraryBrowser.kt       车机 / 外部浏览器的歌单层级浏览树（只读 Room，无缓存，见 §6.9）
  PlaybackConnection.kt   客户端侧：建立/复用 MediaController
  PlayerHandle.kt         全局持有 ExoPlayer 实例（供小组件/磁贴直接操作）
  AudioFx.kt              均衡器/低音/虚拟环绕/响度增强/混响（AudioEffect 封装，单例，见 §6.10）
  EqPresets.kt            16 个内置均衡器预设曲线（听感 8 / 流派 8）+ log 轴插值 + 曲线预览归一化
  ReverbPresets.kt        7 档内置混响（关闭 / 小房间…板式）→ 平台 PresetReverb 档位的纯数据映射
  SoundSchemes.kt         8 条音效方案一键包（曲线+低音+环绕+响度+混响；纯数据，含车载方案，见 §6.10）
  SoundStash.kt           进车音效快照的纯文本编解码（车载档案：手机听感不被车载方案覆盖）
  TrackVolume.kt          播放过渡单例：切歌淡入淡出斜坡 + 曲间响度平衡（服务端音量执行者）
  FadeRamp.kt             淡入淡出纯函数曲线（线性、夹取、接缝连续，有形状单测）
  LoudnessScan.kt         响度数学纯函数（RMS/dBFS、增益夹取、衰减因子；衰减制不抬升）
  LoudnessCache.kt        曲目响度文件缓存（filesDir 文本文件，不进 Room——纯缓存不配 schema 升版）
  TrackLoudnessAnalyzer.kt MediaCodec 解码薄壳：前 120s → RMS 增益；失败静默返回 null
  Pitch.kt                变调：半音 ↔ 倍率换算
  SleepTimer.kt           睡眠定时（单例，StateFlow 暴露剩余时间）

overlay/                  ── 悬浮窗歌词（活在与界面无关的生命周期里）──
  LyricOverlayService.kt  单行字幕悬浮窗（前台服务，可拖拽、锁定穿透、后台才显示）
  LyricOverlayBus.kt      进程内单向数据槽：ViewModel 单向写、服务单向读，彼此不持引用
  LyricOverlayBridge.kt   界面侧门面（开关 / 授权引导 / 位置调整）
  OverlayVisibility.kt    显示判定的**唯一纯函数**（单测覆盖；隐藏条件都汇进这里）
  OverlayTextComposer.kt  悬浮窗文字排版（行数 / 双语副行 / 明暗层次）
  OverlayPositioner.kt    窗口位置（拖拽 / 锁定穿透 / 位置记忆）
  ForegroundAppState.kt   前台包名共享状态（无障碍服务写、悬浮窗服务读；纯函数可单测）
  ForegroundAppService.kt 无障碍服务：TYPE_WINDOW_STATE_CHANGED → 上报前台包名 + 写历史
  ForegroundAppDetector.kt 使用情况权限轮询（fallback）+ 通道状态 / 系统设置跳转
  ForegroundAppLog.kt     前台切换历史（files/foreground_app_log.tsv，滚动 300 条，开关门控）

tile/PlaybackTileService.kt   快捷设置磁贴
widget/MusicWidgetProvider.kt 桌面小组件（显式 Intent，无跨应用冲突风险）

ui/                       ── 展示层 ──
  LibraryViewModel.kt     ⭐ 曲库域门面（73 行）：7 个 `XxxActions by session.xxx` 委托 + 2 个状态导出
  library/                ⭐ 曲库域实现（2026-10-15 由 1695 行拆出，见 §6.8）
    LibrarySession.kt      共享状态宿主（唯一的 `StateFlow<LibraryUiState>` / messages / scope）+ 装配
    LibraryActions.kt / LibraryCore.kt(778)        扫描 / 视图 / 排序 / 搜索 / 收藏 / 删文件 / 统计 / 搜索历史
    FolderActions.kt / FolderController.kt(365)    导入目录 / SAF / 歌词刷新 / 权限 / 移除扫描结果
    M3uActions.kt / M3uStore.kt(158)               M3U 导入导出 / 歌曲信息编辑（song_overrides）
    PlaylistActions.kt / PlaylistStore.kt(234)     歌单
    SelectionActions.kt / SelectionController.kt(167) 批量多选
    ReportActions.kt / ReportController.kt(107)    收听报告
    DuplicateActions.kt / DuplicateController.kt(68)  重复清理
  PlayerViewModel.kt      ⭐ 播放域：`PlayerActions by playback` 接口委托，只转出动作与三个流
  UpdateViewModel.kt      ⭐ 更新域：`UpdateActions by updates` 接口委托（20 行）
  SettingsViewModel.kt    ⭐ 维护域：诊断快照 / 清理失效歌曲 / 重置用户数据 / 崩溃日志
  MainScreen.kt           主界面（列表 / 分组 / 歌单 / 扫描引导）—— 把分域 VM **按需**传给子组件
  MainScreenParts.kt      主界面子组件（PlayerPane / SelectionBar / SaveQueueAsPlaylistDialog，2026-10-21 拆出）
  PlayerScreen.kt         全屏播放页（**手机与车机共用**；改它之前务必先读 docs/ui/02-player.html 与 06-car.html 核对两版）
                          ⚠️ 车机分支（`state.carMode`）自 v1.1.53 起是「左功能列 + 左歌词 + 右唱片」：
                          收藏 / 播放队列 / 播放设置三键竖排在**最左一列**（`CarSideActions`），
                          顶栏只剩「收起箭头 + 居中歌名/歌手」，底部控制行只剩「播放模式 + ⏮⏯⏭」。
                          改版式时这三处必须一起动，漏一处就会出现「同一个入口两处」或主键不居中。
  PlayerScreenParts.kt    播放页自绘零件（PlaybackSlider / TimeLabel / CoverPane，2026-10-21 拆出）
  LyricsPane.kt           歌词面板（滚动居中、当前行高亮、偏移调节）
  SheetsPlayback.kt        播放域弹层（队列、睡眠定时、播放设置）
  SheetsSong.kt            单曲域弹层（长按操作、音频信息、编辑歌曲信息）
  SheetsPlaylist.kt        歌单域弹层（加入歌单、新建歌单、歌单菜单）
  SheetsScanScope.kt       扫描范围（弹层 + 顶栏芯片）
  Common.kt                共享 UI 原语（封面 / 歌曲行 / 迷你播放条 / 弹层通用行组件……）
  EqSheet.kt              「均衡器与音效」弹层（方案网 / 预设网 / 曲线预览 / 频段 / 增强音效 / 播放过渡 / A/B 对比，见 §6.10）
  SettingsScreen.kt       设置页外壳（枚举 / 下钻动效 / 页面分发 · 一主六子）
  SettingsPages.kt        子页组合：主页卡 / 外观 / 播放 / 曲库与存储（**页面只组合分区**）
  SettingsLibrarySections.kt  曲库页分区：统计 / 本地曲库 / 数据与存储 / 危险操作
  SettingsAboutSections.kt    关于页分区：版本卡 / 检查更新 / 隐私说明 / 排障入口
  SettingsSystemPages.kt  关于与更新页 + 诊断与排障页 + 更新页
  Common.kt               通用组件（封面、歌曲行、空态、骨架屏……）
  AppIcons.kt             图标封装
  theme/Theme.kt          Material 3 主题、色板生成
```

### 3.3 资源

```
res/
├─ values/            **英文兜底文案** strings.xml（app_name = WindTone）、themes.xml（Theme.MusicPlayer）
├─ values-zh/         中文 strings.xml（app_name = 听风）
├─ values-night/      深色主题
├─ drawable/ layout/  图标、小组件布局
├─ mipmap-*/          启动图标（含 anydpi-v26 自适应图标）
└─ xml/               file_paths.xml（FileProvider 路径）、music_widget_info.xml
```

> **⚠️ 两个桶的语义别搞反（2026-10-24 起的硬约定）**：`values/` 是**英文兜底**，不是"中文默认"。
> Android 的解析规则是「当前语言 → `values`」，所以**任何一条文案都必须在 `values/` 里存在英文值**，
> 只在 `values-zh/` 里定义会让英文设备直接 `NotFoundException` 崩溃。加语言 = 新增 `values-xx/`，代码一行不改。
> 细则见 §10 第 17 条。

---

## 4. 架构与数据流

### 4.1 分层原则

```
UI（Compose）     只读 StateFlow、只调 ViewModel 方法，不直接碰 Repository/DB
ViewModel         按域分家（曲库 / 播放 / 更新 / 维护），各自只持有自己那块状态
AppContainer      服务定位器：单例持有 Repository / 引擎 / 共享组件，供各 ViewModel 取用
data / playback   无 UI 依赖，可独立推理（lyrics/ 甚至可纯 JVM 单测）
state             界面状态类型，ViewModel 与 UI 共用；**不依赖 ui / playback**（防反向依赖）
```

**为什么 `state/` 要单独一个包**：`PlayerUiState` 这类类型既要被 `playback` 域产出、又要被 `ui` 消费。
如果留在 `ui/` 里，`playback` 就得 `import ui.*` —— 播放层反向依赖展示层是设计污染。
挪进 `state/` 后两边都指向一个无依赖的纯数据包。

`MainActivity` 用 `viewModel()` 建四个 VM，`init` 里的职责各归各的：

- `LibraryViewModel` → `reloadAll()`（读目录 → 读搜索历史 → **先出曲库缓存列表**再后台增量扫描）
- `PlaybackEngine`（由容器 lazy 创建）→ 恢复队列与播放态、按需 attach 音效
- `UpdateRepository` → `maybeAutoCheck()`（内部限频）
- `SettingsViewModel` → 按需刷新诊断快照（**不**在 `init` 里跑，省一次 IO）

### 4.2 状态流一览（改 UI 前先看这里）

```kotlin
// lib.library
val library:   StateFlow<LibraryUiState>    // 曲库与浏览（歌曲、视图、分组、歌单、搜索……）
// playerVm.player / .progress / .themeSeed
val player:    StateFlow<PlayerUiState>     // 播放器（当前曲、歌词、队列、EQ 开关……）
val progress:  StateFlow<PlaybackProgress>  // ⚠️ 进度**单独**一个流，250ms 刷新
val themeSeed: StateFlow<Int?>              // 封面主色（MusicPlayerTheme 拿它生成色板）
// updateVm.update
val update:    StateFlow<UpdateUiState>     // 更新检查 / 下载 / 安装
// settings.maintenanceReport（普通 State，按需刷新）
```

> **性能约定（重要）**：播放进度**故意**从 `PlayerUiState` 拆出来单独放在 `progress` 流里，并把订阅下沉到叶子组件（进度条、时间文本）。这样 250ms 一次的重组不会波及整个主界面。**新加"随进度变化"的 UI 时，请订阅 `progress` 而不是 `player`**，否则会把主界面拖回每 250ms 全量重组。
>
> **跨域提示**走 `MessageBus`（`lib.messages` 是它的 `SharedFlow`），`MainScreen` 统一收集后弹 Snackbar。
> 任何一层都可以 `bus.post("...")`，不需要知道界面长什么样；也不要再把提示塞回某个 UiState。

### 4.3 播放链路

```
UI 调 playerVm.playQueue(songs, index)      // PlayerViewModel 用接口委托整体转给 PlaybackEngine
  → ensureController()（PlaybackConnection.connect() 懒连接）
  → controller.setMediaItems(...) / prepare() / play()
  → PlaybackService（MediaLibraryService）持有真正的 ExoPlayer
  → 回调 → engine 更新 player / progress 流
  → AudioFx 在拿到 audioSessionId 后 attach，挂上音效
```

- `PlayerViewModel` **没有任何播放逻辑**，只有 `PlayerActions by engine` 一行委托 + 三个流的转发。
  加播放动作时改 `PlaybackEngine` 与 `PlayerActions` 接口，`PlayerViewModel` 不用动。
- `PlayerHandle` 是给**进程内其他入口**（小组件、磁贴）直接操作播放器的把手，不走 ViewModel。
- 队列**持久化**：`queue_songs` 表（`position` + `songId`）+ `player_state` 单行（当前曲 / 进度 / 随机 / 循环模式），
  引擎创建时 `restoreQueueIfNeeded()` 整队列恢复。队列以稳定 URI 换算 `songId`，**不用 `dbId` 做媒体项 id**。
  - ⚠️ **恢复动作是「尽力而为 + 可重试」的**：`restoreQueueIfNeeded()` 会被调用多次（缓存装载后 / 扫描完成后），
    只有在**真的把队列摆上去**之后才置 `queueRestored`。原因是一条实测撞到的时序：曲库还没推给引擎
    （`librarySongs` 为空）时恢复会被跳过，若此刻就置位，这次启动就**永久**失去恢复机会 ——
    症状是底部迷你条与当前曲都不出现，不报错也不崩溃（v1.1.16 修）。
  - 「恢复完要不要顺手 `play()`」的判定在 `playback/AutoResume.kt` 的 `shouldAutoResume(enabled, queueSize, isPlaying)`，
    见 §11.1 第 6 条。日志标签沿用 `PlaybackEngine`：`断点续播：恢复队列 N 首，第 i 首，位置 XXms`。
- **引擎是应用级单例，没有 `onCleared()`**：退到后台靠 `setAppForeground(false)` 停掉进度计时器，
  `onIsPlayingChanged` / 再次前台化时重新拉起（原来依赖 ViewModel 销毁收尾的逻辑已全部改为自持）。

### 4.4 扫描链路

```
lib.scan(auto) 或 lib.startFullScan() / lib.setScanScope(scope)
  → MusicRepository.loadLibrary()
      ├─ ScanScope.FULL     → MediaStoreScanner.scan(deepAccess) [+ FileScanner 深度]
      ├─ ScanScope.FOLDERS  → FolderScanner.scan(treeUri) 逐个已导入目录
      └─ 合并去重（buildKey 用稳定标识）
  → onProgress 回调节流上报 → library.statusText
  → persistSongs() 在**一个事务**里 upsert 到 songs 表（保住已有行 id）
      + markMissing（本轮没见到的置 missingSince，软删）+ 重算 folders.songCount
  → applyLibrary() 计算 visible（排序+搜索）→ 更新 library 流
```

**启动顺序**：`loadFromCacheThenScan()` —— **先读 `songs` 表秒出列表**（v4 起它是曲库本体而非缓存，行 id 稳定），
再后台增量刷新，避免每次启动都等全盘扫描。

> ⚠️ `persistSongs` 的关键是 **`existingId` 保持不变**（`Song.dbId` 透传）。
> 若被当成新行插入，指向它的收藏 / 歌单 / 队列会因为主键变化而全部失联。

### 4.5 歌词链路

```
engine.loadLyrics(song)
  → LyricsLoader.load()            ← **唯一加载漏斗**，全应用的歌词都从这一处出去
      1) 同名旁挂文件：LyricsMatcher 匹配 → 按扩展名分派
           .krc → KrcParser.parseKrc（XOR 解密 + zlib 解压）
           .yrc → KrcParser.parseText（明文）
           其余 → TextCodec 探测编码 → 有行时间轴走 LrcParser.parse，否则 plainText
      2) 音频内嵌同步歌词 SYLT
      3) 音频内嵌歌词 USLT / Vorbis LYRICS / ©lyr（再按有无行时间轴分派）
      4) 都没有 → Lyrics.EMPTY
  → 应用 song_state.lyricOffsetMs 逐曲偏移（改偏移写回该列）
```

**全应用只有「逐行」一种精度**（v1.1.8 起）：KRC / YRC / 增强 LRC 的行内字级标签一律**剥离干净**，时间轴只取行首。这条决定了 `LyricLine` 不再需要字级字段，也让渲染端不需要再关心播放位置。

⚠️ 历史上这个出口还挂过 `WordTimingSynth.withDerivedWordTiming()`（给只有行时间轴的普通 LRC 派生字级时间轴）。那套机制已整体移除 —— **要恢复必须同时恢复「数据产出」与「渲染消费」两端**，只加一头就是「有数据但没人消费」的死代码，而且不会报任何错。

**歌词乱码**一律先看 `TextCodec.kt`；**KRC / YRC 读不出内容**看 `KrcParser`（先确认是不是被当成普通文本读了）；**旁挂找不到**看 `LyricsMatcher` 的候选名规则。

---

## 5. 数据层

### 5.1 Room 数据库

文件 `music_player.db`（**勿改名**），`MusicDb`，`version = 5`，`exportSchema = true`
（schema 快照落在 `app/schemas/com.luckylearning.windtone.data.db.MusicDb/`，**入库**）。
详细字段表与红线见 `docs/数据库设计-MusicDb.md`，这里只给地图：

| 表 | 主键 | 用途 |
|---|---|---|
| `songs` | `id`（自增）+ `uri` 唯一索引 | **曲库本体**（不是缓存）。`missingSince > 0` = 软删；`excluded = 1` = 用户主动移出 |
| `folders` | `id` + `docId` 唯一索引 | 导入目录的**唯一事实源**（含顺序 `sort` 与 `songCount`、`excluded`） |
| `song_state` | `songId`（外键 CASCADE） | 每曲一行宽表：收藏 + 统计 + 歌词偏移 + 标签覆盖 |
| `playlists` | `id` | 歌单元信息 |
| `playlist_songs` | `(playlistId, songId)`，双外键 CASCADE | 歌单-歌曲关联 + `position` |
| `queue_songs` | `id`，外键 CASCADE | 播放队列（整体重写语义） |
| `player_state` | `id = 1`（永远一行） | 当前曲 / 进度 / 随机 / 循环模式 |
| `search_history` | `query` | 搜索历史（上限 10，写入用 INSERT OR IGNORE + UPDATE） |
| `listen_log` | `hourEpoch`（= `epochMillis / HOUR_MS`） | **收听时长小时桶**（v5 新增），`addMs` 累加 + 写时 `prune`，`RETENTION_DAYS = 400` |

**分家原则（v4 的分界线）**：**凡是"跟着某首歌走"的数据一律进 Room 并挂 `songId` 外键**，
靠 CASCADE 保证"删歌就清干净"；全局开关/标量留在 Prefs（见 §5.2）。

**关键身份约定**：
- `Song.stableKey = uri.toString()` 是**自然键，算法永不改**（UI、歌单、封面缓存回退键都靠它）；
- `Song.dbId`（= `songs.id`）**只有持久化边界能用**，UI 层不要碰；
- **MediaItem 的 mediaId 仍用 URI 字符串**（播放器进程没有数据库）。

**两种"看不见"必须区分（v5 新增 `excluded`）**：
- `missingSince > 0` = **文件暂时不在**（拔卡 / 换挂载点）。软删，文件回来自动恢复，用户没表达过任何意图。
- `excluded = 1` = **用户主动移出曲库**（长按移出 / 排除目录）。**重扫不得把它收回来**——
  `excludedUris()` 在 upsert 循环里直接跳过，标记不因重扫而重置；`SongDao.visible()` / `countsByFolder()`
  一律带 `excluded = 0` 过滤。行本身保留，所以收藏 / 统计 / 歌单关联都还在，设置页可一键恢复。

**迁移策略**：**本版不写迁移**（产品口径：不做跨版本兼容，重装即可）。`MusicDb` 只挂
`.fallbackToDestructiveMigration()`——结构对不上就整库重建，曲库由重扫恢复，歌单 / 收藏 / 统计会丢。
所以仓库里**没有** `Migrations.kt`、**没有** `PrefsRoomMigration.kt`、**也没有**迁移回归测试。

改 schema 的完整步骤见 §11.2。若日后想保住用户数据，加 `addMigrations(...)` 即可——
Room 会优先走迁移而非兜底，其他代码不用动。

### 5.2 Prefs 键表（`data/Prefs.kt`）

SharedPreferences 文件 `music_player_prefs`（**勿改名**）。v4 之后只剩**全局标量/枚举/开关**：

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `scan_scope` / `scan_scope_chosen` | String/Bool | `UNSET` | 扫描范围与其是否已选过 |
| `deep_scan` | Bool | false | 深度扫描（读旁挂歌词） |
| `min_song_duration_sec` | Int | 60 | 过滤短音频阈值 |
| `sort` | String | — | **旧键**：全局排序。v1.1.4 起**只作老数据兼容回落**（本视图新键没写过时才读它），不要再往里写 |
| `sort_<视图名>` | String | 按视图 | 各视图自己的排序（`sort_SONGS` / `sort_ALBUM` / `sort_ARTIST` / `sort_FOLDER` / `sort_PLAYLIST`）。默认值由 `LibraryView.defaultSort` 给，不走 Prefs |
| `library_view` | String | `SONGS` | 上次停留视图，重启恢复 |
| `lyric_scale` | Float | 1.0 | 歌词字号缩放 |
| `lyrics_pane_visible` | Bool | true | 播放页显示歌词还是封面（v5 新增，**持久化**，不再每次进页面重置） |
| `car_mode` | Bool | false | 车载模式（开机自启 + 直进播放页） |
| `lyric_overlay` / `_x` / `_y` | Bool/Int | false | 桌面悬浮歌词开关与其窗口位置（`_x` / `_y` 为 `Int.MIN_VALUE` 表示没拖过） |
| `lyric_overlay_lines` | Int | 1 | 悬浮歌词行数（1 / 3） |
| `lyric_overlay_text_scale` | Int | 1 | 悬浮歌词字号档位（0 小 / 1 中 / 2 大） |
| `lyric_overlay_align` | Int | 0 | 悬浮歌词水平对齐（0 居中 / 1 居左）。**只管窗内文字排版**，不参与窗口摆放（窗口位置见 `lyric_overlay_x` / `_y`） |
| `lyric_overlay_backdrop` | Int | 1 | 悬浮歌词底板（0 无 / 1 半透明 / 2 实底） |
| `lyric_overlay_pause_mode` | Int | 0 | 暂停时悬浮歌词（0 淡出 / 1 隐藏） |
| `lyric_overlay_width_percent` | Int | 80 | 悬浮窗宽度（屏宽百分比 1..100，v1.1.4 新增）。服务侧另有 48dp 像素下限。**默认不是 100%**：窗口宽度决定横向可移动量（`屏宽 − 窗宽`），100% 时归零、悬浮窗只能上下拖 |
| `lyric_overlay_text_color` | Int | `0xFFFFFFFF` | 悬浮窗歌词文字颜色（v1.1.4 新增）。v1.1.8 起是**整窗唯一的颜色** —— 主角与上下句靠明暗 / 字重区分，不再有「已唱色」 |
| `lyric_overlay_hidden_apps` | StringSet | `emptySet()` | **不显示悬浮窗的应用**（v1.1.29 新增）：名单内包名前台时悬浮窗收起（`OverlayVisibility.foregroundHidden`）。检测通道见 §11.15 |
| `settings_debug_unlocked` | Bool | false | **调试分区是否已解锁**（v1.1.31 新增，默认 **false = 隐藏**）：关于与诊断页的「调试」分区（前台应用记录 / 悬浮歌词自检 / 崩溃日志 / 开机自启）只在为 true 时渲染。解锁 = 在「关于」卡上连点 5 次（`DEBUG_UNLOCK_TAPS`，计数不落盘）；关闭 = 分区顶部「显示调试功能」开关写回 false。纯界面态，不叫醒任何服务 |
| `foreground_log_enabled` | Bool | false | **前台应用记录**（v1.1.29 新增，默认关）：开着才把前台切换写进 `files/foreground_app_log.tsv`（滚动 300 条）。与名单共用检测通道，二者任一需要时才拉起检测 |
| `skip_silence` | Bool | false | 跳过静音 |
| `dynamic_theme` | Bool | **true** | 封面主色动态主题 |
| `playback_speed` | Float | 1.0 | 播放速度 |
| `eq_enabled` / `eq_preset_id` / `eq_bands` / `eq_bass` / `eq_virtual` / `eq_loudness` / `eq_reverb_id` | — | — | 音效状态（`eq_preset_id` = 内置预设 id，空串 = 自定义；`eq_reverb_id` = 混响 id，空串 = 从未选过 = 关闭。见 §6.10） |
| `soft_eq_user_confirm` | Bool | **false** | 软 EQ 用户确认（E5）：硬 EQ `DEVICE_REJECTED` 时仅候选；为 true 才 `SOFT_ACTIVE`。硬恢复强制关软、偏好保留。见 `docs/功能-软EQ.md` |
| `auto_check_update` | Bool | **true** | 启动自动检查更新（唯一联网开关） |
| `last_update_check_at` | Long | 0 | 自动检查限频时间戳 |
| `update_download_route` | String | `AUTO` | 下载线路（自动/直连/镜像） |

> ⚠️ **三个「死键」**：`lyric_word_by_word` / `lyric_sung_color` / `lyric_overlay_sung_color` 在 v1.1.8
> 随逐字染色一起删掉了读写代码，但**刻意不清理**老用户 SharedPreferences 里的残留值 ——
> 清它没有收益，只多一次写盘和一类可能崩的地方。**看到这三个键别当 bug 排查。**

**不再存在的键（勿再引入）**：目录列表（`folders` / `folders_ordered`）、队列（`queue_keys` / `queue_index`）、
播放位置（`last_uri` / `last_pos` / `shuffle`）、逐曲歌词偏移（`lyric_offset_song_*`）、
通知栏歌词（`notify_lyrics`，功能已于 2026-10-15 整体移除）、
权限询问标记（`perm_asked`，2026-10-15 清理——属性从来没被任何代码读过，权限状态由界面自己的
`remember` 状态持有）、
搜索历史（`search_history`）——这些"成表、会增长"的数据 v4 起全部进 Room。
Prefs 里**也没有**任何遗留键搬迁逻辑或 `data_v4_migrated` 之类的幂等标志。

**`lyric_overlay_x` 已恢复使用（2026-10-16 用户拍板，推翻 10-15 的废弃决定）**：悬浮窗
横向与纵向**都由拖动决定**（x / y 都是距屏幕左 / 顶沿的像素），`lyric_overlay_align` 语义收窄为
**只管歌词文字在窗口内部怎么排**（居中 / 靠左），与窗口位置无关（详见 §11.9）。
注意默认横向必须用「(屏宽 − 窗宽) / 2」算，**不能用** gravity 的 `CENTER_HORIZONTAL`
——后者的 `x=0` 是「相对屏幕中心的偏移」，与拖动坐标系不能共存，拖一下就会跳半屏。

新增偏好项的写法：加一个 `var` 属性 + 在 `private companion object` 里加 `KEY_` 常量。**不要**在多处硬编码键名字符串。

**读取口径**：**全是同步读**（`prefs.xxx`），需要在 Compose 里响应就手动包一层
`mutableStateOf` / `remember { prefs.xxx }`。**不要再引入 `OnSharedPreferenceChangeListener` 封装**——
2026-10-15 已把 `Prefs.onChanged()`（返回取消订阅 lambda 的那个封装）**当死代码删除**，全工程 0 调用者：
界面各自同步读属性就够，SharedPreferences 读本身极轻；真要"另一处改了这里跟着变"，让写入方回调即可。
**不迁 DataStore**——留下的全是同步读、启动即用的标量，迁过去要改几十处调用点，收益只有一个 Flow。

---

## 6. 关键子系统

### 6.1 音乐扫描与权限

- **默认不扫全盘**是产品硬决策：安装后不主动申请任何权限，用户明确选择后才扫。
- 权限：`READ_MEDIA_AUDIO`（13+）、`READ_EXTERNAL_STORAGE`（≤32，`maxSdkVersion=32`）、`MANAGE_EXTERNAL_STORAGE`（可选，深度扫描读旁挂 `.lrc` 用）。
- `music_player.db` 的 **`songs` 曲库**让二次启动无需重扫；切换扫描范围 / 移除目录时才按需清理。
  文件暂时不可见的歌曲只标 `missingSince`（软删），收藏与歌单不受影响，文件回来自动恢复。
- 支持的扩展名与 MIME 白名单在 `MediaStoreScanner` / `LocalScanners` 里，加格式改这两处。

### 6.2 应用内更新（`data/UpdateRepository.kt` + `data/UpdateChecker.kt`）

域持有者是 **`UpdateRepository`**（自有 CoroutineScope、暴露 `UpdateUiState.update`、12 个动作，
文件尾声明 `interface UpdateActions`）；**`UpdateChecker`** 是它内部的无状态 HTTP 逻辑层
（查 Release / 比对版本 / 下载 / 校验 / 安装）。界面侧 `UpdateViewModel` 用接口委托整体转出，**不写实现**。

```
checkLatest()  ── GET raw.githubusercontent.com/{owner}/{repo}/{branch}/{app}/latest.json
                 · 静态文件、CDN 直出，**不消耗 api.github.com 的 60 次/小时配额**
                 · 一次请求拿全：version / versionCode / tag / notes / assets[abi,file,size,sha256]
                 → parseManifest()：坏 JSON / 没版本号 / 没资产都折成 Failed（永不抛异常）
                 → orderAssets(assets, deviceAbis)：本机 ABI → universal → 其它 ABI
                 → isNewer(candidate, BuildConfig.VERSION_NAME)  语义化版本比较（1.10 > 1.9，不是字符串比较）
download()
   → downloadCandidates()：直连 raw → jsDelivr → ghproxy（手动选线路时**只走那一条**）
   → 写 .part；8 秒内不足 512KB 判「慢线路」并换下一条
   → verifyApk()：ZIP 头 → 字节数 → **SHA-256**（清单给了摘要就必须一致，否则拒绝安装）
   → 进度回调 → UpdateUiState.progress
install()
   → FileProvider.getUriForFile(context, "${applicationId}.fileprovider", apk)
   → Intent(ACTION_VIEW, apkUri) + FLAG_GRANT_READ_URI_PERMISSION → 系统安装器
```

> ⚠ **为什么不直接读源码仓库的 Releases**（v1.1.30 改，公开后仍适用）：匿名读
> `api.github.com` / Atom 易撞 **出口 IP** 60 次/小时配额（手机常在运营商 NAT 后），且大文件资产不适合走 API。
> 安装包由发布流水线另发到**公共分发仓库** `LuckyLearning/Release`（`<软件>/<版本>/`，只保留最近 5 个版本），
> 应用只读其 `latest.json` + raw/CDN，无需鉴权。

> ⚠ **`plainNotes()` 必须同时吃下 Markdown 与 HTML**：清单里的 `notes` 是模板渲染出的 **Markdown**，
> 但历史上的 Release 正文曾是 **HTML**（Atom 的 `<content>`），且旧版本客户端仍会读老数据。
> 只清洗一种，另一种就会把标记原样甩进弹窗（v1.0 ~ v1.1.11 线上就是这样，用户看到一整屏 `<p><strong>`）。
> 清洗口径：HTML 与 Markdown 的表格都折成 `• a · b` 行、块级元素与列表项各占一行、段间不留空行、
> **先剥标签再解实体**（反过来会让正文里被转义的 `&lt;b&gt;` 变成尖括号被当标签剥掉）。

- 仓库坐标来自 `BuildConfig.RELEASE_OWNER` / `RELEASE_REPO` / `RELEASE_BRANCH` / `RELEASE_APP_DIR` / `RELEASE_PAGE_URL`（在 `build.gradle.kts` 的 `buildConfigField`，**换仓库改这里**）。
- 资产名由清单里的 `assets[].file` 给出，**应用侧不再按模板推导文件名**（模板只影响 CI 产出）。
- `REQUEST_INSTALL_PACKAGES` 权限 + `canInstall()` 判断，未授权时引导用户去设置页（`unknownSourcesIntent()`）。
- FileProvider authority 是 **`${applicationId}.fileprovider`** 占位符 → debug 与 release 自动隔离，无需硬编码。
- `file_paths.xml` 只暴露 `cache-path updates/`，不要为了省事暴露整盘。

### 6.3 播放与音频

- `PlaybackService : MediaLibraryService`（**2026-10-16 起**，此前是 `MediaSessionService`——它是前者的父类，
  换过来是为了给车机媒体面板提供歌单浏览树，见 §6.9），Manifest 里 `foregroundServiceType="mediaPlayback"`。
  通知渠道 ID `local_music_player_playback`（**遗留名，勿改**）。
- `AudioFx` 单例：`init(context)` → `attach(audioSessionId)` 后才生效；换曲导致 sessionId 变化需重新 attach。
- `SleepTimer` 单例：`start(ms)` / `startEndOfSong(pos, dur)` / `cancel()`，`remainingMs` 是 StateFlow。
- `PlayerHandle`：给小组件/磁贴用的进程内播放器把手。

### 6.4 主题与封面取色

`data/ThemeColorExtractor`（**在 `data/` 包，不在 `ui/`** —— 它是无 UI 依赖的纯算法）从封面位图抽样聚类出主色，
经 `PlaybackEngine.themeSeed` 流出，`MainActivity` 订阅后传给 `MusicPlayerTheme(seedColor)` 生成 M3 色板。
`CoverRepository` 负责位图解码 + 内存 LruCache + 磁盘缓存，`MusicApp.onCreate` 会清一次内存缓存防脏数据。
**两级内存缓存都按堆比例取上限**（位图 `maxMemory/8` 夹 8~32MB、原始字节 `maxMemory/16` 夹 2~6MB，v1.2.12 起；
旧实现原始字节是固定 16MB）；`MusicApp.onTrimMemory` 按压力等级调用 `CoverRepository.trim(...)`，
**磁盘缓存一律不清** —— 它是「丢掉内存还能快速回来」的退路。
**动态主题默认开启**，设置页可关。

### 6.5 崩溃日志

`CrashLog.install(this)` 在 `MusicApp` 里挂 `Thread.setDefaultUncaughtExceptionHandler`，日志落本地文件；设置页可查看条数、导出、清空。R8 保留了 `SourceFile,LineNumberTable`，所以 release 崩溃栈可读。

**启动即崩时设置页是走不到的**（v1.1.13 的车机黑屏就是这么发现的：装上打开就是黑屏，连系统对话框都没有，而用户没有 adb、也进不去设置页）。所以 `MainActivity.onCreate` 在 `setContent` 之前先判一次，命中就渲染 `ui/BootDiagView`（**纯 Android View，不用 Compose**）：设备与系统、屏幕与 insets、上一轮启动的阶段表、最近崩溃栈，外加「进入应用」「复制全部」。看过一次由 `CrashLog.markSeen` 记账；`diag-*` 属于非致命诊断，**不会**触发这一页。口径由 `CrashLogTest` 钉住。

**为什么是纯 View 而不是 Compose 页**：出问题的很可能正是 Compose 那条链（GL 驱动 / 动态取色 / insets）。拿一个可能坏掉的东西去报告另一个东西坏了，等于没报告 —— 诊断页必须站在最不容易坏的那一侧。

**`data/BootTrace`：区分「崩了」和「没画」**。车机黑屏有两种完全不同的成因：`Application.onCreate` 就抛异常（进程秒退，Activity 根本没建起来），或者进程活着但一帧都没送出去。冷启动每一步都往 `files/boot/trace.txt` 追加一行「相对耗时 + 阶段」，出首帧时（`window.decorView` 的 `onPreDraw`）补一行 `frame:first`；下次启动把上一轮文件改名 `prev.txt` 保留，`lastIncompleteBoot()` 据此判断「上一轮有没有画出首帧」。**这个判据只看数据，不看现象**，由 `BootTraceTest` 钉住（含两个易错分支：停在诊断页没点进入不算故障；点过「进入应用」却没出首帧必须保留信号）。

配套的两处防御：

- `MusicApp.onCreate` 的每一步初始化都套 `runCatching`（`step("cover") { ... }`）。这几步里没有一步是「失败就宁可不启动」的，一律降级继续并记账 —— 否则任何一行抛异常都会让诊断页永远出不来。
- 构建开关 `-PdiagBuild=true`（`BuildConfig.BOOT_DIAG`）让**每次**冷启动都先停在诊断页。车机上「打开全黑」时用户点不到任何运行时开关，只有把判断前移到构建期才保证那台机器上一定能看到这一页。
- 构建开关 `-PswRender=true` 把 `android:hardwareAccelerated` 置 false（`manifestPlaceholders`），用来做 GPU/驱动问题的 A/B 对照。

### 6.6 小组件与磁贴

- 小组件 `MusicWidgetProvider`：`onReceive` 用**显式 Intent**（`Intent(context, MusicWidgetProvider::class.java).setAction(...)`）触发，action 常量 `com.luckylearning.windtone.widget.{TOGGLE,NEXT,PREVIOUS}` 同时出现在代码与 Manifest 里，**改包名时要两处一起改**。
- 小组件四个可点区域（封面 / 歌名 / 歌手 / 外框）走 `private fun entry(context, requestCode, action): PendingIntent`，
  **必须四个不同 `requestCode`**——PendingIntent 的身份是 `(requestCode, Intent)`，requestCode 相同时
  `FLAG_UPDATE_CURRENT` 会让后建的把先建的**覆盖掉**（表现为"点哪都是同一个动作"）。三个文字区分别深链到
  播放页 / 曲库 / 歌单（`AppShortcut.OPEN_*`），外框保留 `widget_info` 的 `openApp` 兜底。
- 磁贴 `PlaybackTileService`：`BIND_QUICK_SETTINGS_TILE` 权限。**一个 `TileService` 只有一个点击动作**，
  所以「下一首」是**独立服务** `NextTrackTileService`（`Tile` 不支持附加动作按钮）。
- **收藏磁贴刻意不做**：① 一个 TileService 只有一个动作，收藏会挤掉播放/暂停；② 更关键——收藏状态在
  Room（`song_state`）而不是 MediaSession 里，磁贴要写收藏就得在 `LibraryViewModel` 之外**再开一条写入路径**，
  必然与内存里的 `favoriteUris` 不同步。播放页与小组件都已有一次点击即可收藏的入口，不值得为磁贴开这个口子。
- `openApp(...)` 在 API 34+ 走 `startActivityAndCollapse(PendingIntent)`，Intent 重载**已弃用**，必须版本分支。

### 6.7 启动器快捷方式与深链通道（`ui/AppShortcut.kt`）

`AppShortcut` 是**静态快捷方式 + 小组件 + 磁贴共用**的"待处理动作"通道，目的是把"我是从哪进来的"
从启动参数里解耦出来，交给界面在合适时机消费：

```xml
res/xml/shortcuts.xml（3 个静态快捷方式：全部播放 / 随机播放 / 我的收藏）
   action = com.luckylearning.windtone.SHORTCUT ，extra.shortcut = play_all | shuffle | favorites
   ⚠️ <intent> 里**只写 action**，不写 targetPackage / targetClass —— 落点靠
      AndroidManifest 里 MainActivity 上那条同 action + CATEGORY_DEFAULT 的 <intent-filter>
MainActivity.onCreate / onNewIntent → AppShortcut.put(AppShortcut.from(intent))
   （onNewIntent 必须写：Activity 已在栈顶时不会再走 onCreate，且 setIntent(intent) 要跟着补）
MainScreen.kt → 订阅 AppShortcut.pending，用 AppShortcut.canRun(四个参数) 判"现在能不能执行"，
                 就绪才 consumePending() 并执行
```

⚠ **为什么不能直接执行**：冷启动时曲库是异步装载的，此时 `playQueue(空列表)` 会**静默失败**
（`PlaybackEngine.startQueue` 里就是一句 `if (queue.isEmpty()) return@launch`），
用户看到的是"点了快捷方式没反应"。真的没歌时由界面弹一句提示，不能静默。

⚠ **就绪判据只能用「结论」，不能用「还没发生的事」**（`ui/AppShortcutGateTest.kt` 守着）。
v1.1.3 ~ v1.1.12 的判据是 `songs 非空 || 扫描范围未选 || 不在扫描` —— 看着挺全，
但那三条在**冷启动第一帧**居然同时"看着像"成立（`songs` 当然是空的，
`scanning` 也还没开始扫 = false，`scanScope` 是上次选过的值）→ 判成就绪 → 动作被消费掉
→ 一个都没执行。用户报的现象是「三个入口都只是把应用打开」。
现在只认 `LibraryUiState.libraryLoaded`（首批装载已给出结论：有歌**或**确定没有）与
`playlistsLoaded`（虚拟歌单曲目已算过），两个标志位分别由 `LibraryCore` 与
`PlaylistStore.refreshPlaylistsNow()` 在**真的算完之后**置位；`scanning` 只用来区分
「扫描中要等」与「确实没有要提示」——注意这两者的期望恰好相反，混成一个布尔必错。

⚠ **「打开我的收藏」要拼 `virtualPlaylistInfo(...)`，不能去 `playlists` 里按 id 找**：
`LibraryUiState.playlists` 只有**用户自建歌单**（直接来自 playlist 表），
最近播放 / 最常播放 / 我的收藏这三个虚拟歌单是"曲库内存状态的视图"，从来没进过它。
v1.1.12 之前快捷方式就是去那里找，永远返回 null，于是只切了个视图就结束 ——
用户看到的是**歌单列表页**（同一现象的另一半）。现在列表页与快捷方式共用
`state/UiState.kt` 里的 `virtualPlaylistInfo(id, library)` 这一个构造函数。

⚠ **通道用 `MutableStateFlow` 而不是普通变量**：`onNewIntent` 发生在 Compose 之外，
普通变量变更不会触发重组，界面永远看不到新动作。

⚠ **组件声明只有"都不写"这一条路走得通**（`app/src/test/.../AppShortcutContractTest.kt` 守着）：
   · 只写 `targetClass` 不写 `targetPackage` → **所有 ROM 上点击都提示「未安装应用」**。
     这是 v1.1.3 ~ v1.1.11 的真机故障：应用能装能启动，只有长按图标点一下才知道坏了，
     而提示还反直觉（像是包没装上）。
   · 两个都写 → 包名被写死，而 debug 的 applicationId 带 `.debug` 后缀（见 `app/build.gradle.kts`），
     debug 包上会去拉正式版 —— 拉起另一个应用比报错更糟。
   · 结果：`<intent>` 只留 `android:action` + `<extra>`，落点由 `MainActivity` 上
     `action=AppShortcut.ACTION` + `android.intent.category.DEFAULT` 的 `<intent-filter>` 提供
     （隐式意图少了 `CATEGORY_DEFAULT` 同样解析不到，等于没写）。
     action 字符串与包名无关，故意写成固定的非后缀值，debug / release 同一份资源即可。
   · 小组件与磁贴走的是**显式 Intent**（`Intent(context, MainActivity::class.java)`），
     只是复用同一个 action / extra 通道，不依赖那条 filter。

### 6.8 曲库域的拆分结构（`ui/library/`，2026-10-15）

`LibraryViewModel` 曾经 1695 行，同时装七摊状态。现在是**一个 73 行的委托壳 + `ui/library/` 下 15 个文件**：

```
LibraryViewModel(app)  ──► LibrarySession(app)  持有唯一的 StateFlow<LibraryUiState> / messages / scope
   LibraryActions  by session.core        → LibraryCore.kt          （扫描/视图/排序/搜索/收藏/删文件/统计/搜索历史）
   FolderActions   by session.folders     → FolderController.kt     （导入目录/SAF/歌词刷新/权限/移除扫描结果）
   M3uActions      by session.m3u         → M3uStore.kt             （M3U 导入导出/歌曲信息编辑）
   PlaylistActions by session.playlists   → PlaylistStore.kt
   SelectionActions by session.selection  → SelectionController.kt
   ReportActions   by session.reports     → ReportController.kt
   DuplicateActions by session.dedupe     → DuplicateController.kt
```

**为什么用接口委托而不是"转发方法"**：与 `PlayerViewModel → PlayerActions` 同一套惯用法。
壳里没有几十个一行转发，界面调用点（约百余处）**一个都没改**。

⚠ **三条不走委托的方法**（`loadReport` / `bulkRemoveFromLibrary` / `bulkDeleteFiles`）：
Kotlin 规定「重写不能声明默认值」，而接口上的默认值表达式**取不到实例状态**
（需要 `session.selection.selectedSongs()` / `session.reports.currentDays()`）。
所以这三条只能留在 `LibraryViewModel` 里声明默认值再转发，**三个 Actions 接口刻意不含它们**。
新增方法时先问一句：默认值要不要依赖实例状态？要，就得放这里。

⚠ **接口必须是 public**：`LibraryViewModel` 是 public，实现 internal 接口会报
"public class exposes internal supertype"；各实现类本身保持 `internal`。

⚠ **作用域一致**：所有行为类统一挂在 `LibraryViewModel` 的 `viewModelScope` 上
（`init` 里 `session.scope = viewModelScope`），与拆分前的 `viewModelScope.launch` 语义完全相同，
不要在每个 Controller 里自建 `CoroutineScope`。

---

### 6.9 车机浏览树（`playback/LibraryBrowser.kt`，2026-10-16）

**要解决的问题**：`MediaSessionService` 只能把「当前播放项」交给系统，车机面板因此永远只有一个
播放卡片，看不到任何歌单层级。补法是换 `MediaLibraryService`（`MediaSessionService` 的子类，
播放侧能力一个不少）并实现 `MediaLibrarySession.Callback`。

```
MediaLibrarySession.Callback            LibraryBrowser（只读 Room，无缓存）
   onGetLibraryRoot   ──► 根节点「听风」
   onGetChildren      ──► 根 → 我的收藏 / 最近播放 / 最常播放 ＋ 自建歌单
                          歌单 → 该歌单曲目（按 position / lastPlayedAt / playCount 排序）
   onGetItem          ──► 单个节点（认不出的 id 回 ERROR_BAD_VALUE，不抛异常）
   onAddMediaItems / onSetMediaItems ──► 把节点变成能播的项（见下）
```

⚠ **三条不变量**（改这块之前先读）：

1. **歌曲节点的 `mediaId` 就是音频 URI**（= `Song.stableKey`），与播放页 / 队列 / 封面缓存同一套 id。
   分类节点统一用 `wt:` 前缀（`BrowseIds`），因为音频 URI 一定是 `content://` / `file://` / 绝对路径，
   不可能以 `wt:` 开头——「这是分类节点还是歌」只看前缀，不加额外字段。
2. **跨 Binder 会丢 URI**：`MediaItem` 过 Binder 只保留 `mediaId` 与 metadata，`LocalConfiguration` 没了。
   所以 `onAddMediaItems` / `onSetMediaItems` 必须自己把 URI 写回去，否则车机上「点了不出声」。
   展开歌单会把 1 项变 N 项，此时原来的 `startIndex` / 起播位置失效，`onSetMediaItems` 里显式重置。
3. **回调必须立刻返回 Future**：binder 线程不能等 Room。统一走 `SettableFuture` + `browseScope`（IO），
   失败写 `CrashLog`（TAG `WindToneBrowse`）并回错误码——不留静默失败
   （口径见《第五期规划》§八「诊断可观测性」）。

**浏览树只做一层下钻**（歌单 → 歌曲），刻意不做专辑 / 歌手 / 文件夹与车机侧搜索：
车机面板的屏幕与操作方式只适合「选一个歌单，从头放」，把五个视图原样搬上去没人翻得完。
要加视图，先在 `BrowseIds` 里加节点，再在 `LibraryBrowser.childrenOf` 里补一条分支，
最后补 `LibraryBrowserTest` 的用例。

**相关 Manifest 项**：`PlaybackService` 挂三个并列 intent-filter
（`MediaLibraryService` / `MediaSessionService` / `MediaBrowserService`），
`<application>` 下有 `com.google.android.gms.car.application` → `res/xml/automotive_app_desc.xml`
（`<uses name="media"/>`，Android Auto 的能力声明；车机自带面板不走这条）。

---

### 6.10 音效链（`playback/AudioFx.kt`，2026-10-17 补全）

四个效果器都挂在**同一个 `audioSessionId`** 上，由 `PlaybackService` 在
`Player.Listener.onAudioSessionIdChanged` 里调 `AudioFx.attach(sessionId)` 重新装一遍
（切歌换会话就换一批效果器实例，所以**任何状态都必须先落盘、再在 attach 时重放**）。

```
AudioFx（进程内单例，UI 与服务同进程直连）
  state: StateFlow<EqState>     ← UI 只读它；所有写入口都是 AudioFx 上的方法
  ├─ Equalizer      频段 ±1500 mB，段数由设备决定（5 / 6 / 10 段都见过）
  ├─ BassBoost      0..1000
  ├─ Virtualizer    0..1000（⚠️ 平台类已标记 @Deprecated，暂留；换实现要连 UI 一起改）
  ├─ LoudnessEnhancer 0..1500 mB
  └─ PresetReverb   7 档（关闭 / 小房间 / 中房间 / 大房间 / 中厅 / 大厅 / 板式）
```

⚠ **四条不变量**（改这一块之前先读）：

1. **均衡器曲线不用平台预设**。`Equalizer.usePreset` 的名字是英文、**各 ROM 不一致**（第 0 个有的叫
   Normal、有的直接是 Rock），且无法本地化。改为自研曲线：`EqPresets` 里每个预设是
   一组「锚点频率 → 增益」，在 **log 频率轴**上对设备实际频段中心频率插值
   （线性插值会让低频曲线形状随设备分段方式漂移），越界取端点、不外推。
   锚点频率 60 / 230 / 910 / 3600 / 14000 Hz **正好是常见五段机的中心频率**，
   所以五段机上曲线精确落在锚点上（测试钉着这条）。
2. **`EqState` 的 `equals` 是引用相等**（`this === other` + `identityHashCode`）：它带 `IntArray`，
   结构相等既慢又容易在"内容没变"时漏掉重组。每个新实例都算"变了"，这是有意的 —— 别改成 data class 自动生成。
3. **`available` 与 `reverbAvailable` 分开报**。几个效果器是各自独立的 `AudioEffect`，
   **均衡器可用而混响不可用的机器是存在的**（ROM 常裁剪混响）。混成一个布尔会让整页在那种机器上整个消失。
   每个效果器各自 `runCatching`，拿不到就只少一档功能。
4. **恢复默认 = "什么都没选过"**，不是"选了一个默认档"：`AudioFx.reset()` 写回
   `eqEnabled=false` + `eqPresetId=""` + 频段全平 + 三项增强归零 + `eqReverbId=""`，
   界面显示「自定义 + 全平」而**不是**停在「原声」预设上。写回值与 `Prefs` 各 getter 的默认值一一对应，
   新增一项时**两处都要动**（`SettingsDefaultsTest` 只覆盖了 `SettingsDefaults` 里登记过的键，
   音效这一组没登记，所以这里靠人工比对 + `AudioFx.reset()` 的注释）。

**页面侧**（`ui/EqSheet.kt`，效果图 ⑲ / ⑲B）按**决策顺序**分五段（2026-10-22 起；此前为三段）：
方案 → 预设 → 频段（先画曲线再拖滑块）→ 增强音效 → 播放过渡。
两处口径沿用既有约定：**手动拖频段滑块 / 任一增强滑块 = 离开预设与方案进入「自定义」**（`presetId` 与方案 id 清空）；
标题行的 ↺ **只在偏离出厂状态时出现**（判据含混响）。曲线预览的高度用
`EqPresets.previewHeights` 按**设备量程**归一化 —— 形状可信、绝对高度不代表真实增益，
真实数值看滑块右侧的 dB 标签。

**音效方案（v1.1.52，`playback/SoundSchemes.kt`，参考主流音乐 App 的「主题音效」形态）**：
8 条一键包 = 曲线 + 低音 + 环绕 + 响度 + 混响，`AudioFx.setScheme(id)` 一次写齐并**打开总开关**；
微调任一参数 = 离开方案（id 清空）。引用的曲线 / 混响 id、强度区间由 `SoundSchemesTest` 对质。
id 持久化后**不可改名**（改了用户已选方案丢失）。

**播放过渡（v1.1.52，`playback/TrackVolume.kt`）**：与 `AudioFx` 同一套「进程内单例」思路，
但管的是**播放器音量**（`player.volume`），不依赖效果器可用性（车机上效果器建不出来也照常可用）：
  - **淡入淡出**：`FadeRamp` 纯函数曲线（线性、接缝连续），自动切歌时曲尾淡出 / 新曲淡入；
    手动暂停 / 拖动 / 关开关立即恢复基线。注意 Media3 的 `onPositionDiscontinuity` 在
    **自动切歌时也会回调**（reason = AUTO_TRANSITION），那条路径必须放行给淡入。
  - **音量平衡**：衰减制——按曲目 RMS（`TrackLoudnessAnalyzer` 解码前 120s）把过响的歌压到
    -20 dBFS（`LoudnessScan` 纯函数），增益缓存进 `LoudnessCache`（filesDir 文本文件，
    **不进 Room**：纯缓存不配 schema 升版，否则 `fallbackToDestructiveMigration` 会拿真数据陪葬）。
    没分析过的曲目先原音量播，分析完下一遍生效；分析失败静默跳过。

**车载音效档案（v1.1.52）**：`PlaybackEngine.setCarMode` 进车时把手机侧音效值整体快照
（`SoundStash` 编码进 `Prefs.soundStash`）再套用车载方案，出车原样还原 ——
车载模式里调的声音只留在车载模式。没选过车载方案（`carSchemeId` 空）则两边都不动。

**A/B 对比原声（v1.1.52）**：`AudioFx.setBypassed(true)` 临时旁路全部效果器（只动运行态开关，
不碰落盘值），松手按用户原设置恢复。

---

## 7. 构建与开发环境

### 7.1 本机环境（维护者示例 · 勿提交绝对路径）

| 项 | 建议 |
|---|---|
| JDK | **JDK 21**（Temurin / OpenJDK；**不要**用 GraalVM 跑 AGP——`JdkImageTransform` 会挂）。构建前 `export JAVA_HOME=...` |
| Android SDK | `local.properties` 的 `sdk.dir`，或仓库内 `.toolchain/android-sdk`（`scripts/sdk-setup.bat` 可引导） |
| Gradle | Wrapper 8.9，**离线可用**（依赖已在用户级 `~/.gradle` 缓存） |

> Gradle 跑在 JDK 21 上没问题；编译目标是 Java 17（`compileOptions` / `jvmTarget`），别混。
> `local.properties` 与 `.toolchain/` **都不入库**，换机器要重建。脚本若残留某台机器的 JDK 绝对路径，仅作「存在则选用」回退；贡献者请设自己的 `JAVA_HOME` / `WINDTONE_JDK`。

### 7.2 常用命令

```bash
export JAVA_HOME="${WINDTONE_JDK:-$JAVA_HOME}"  # 需指向非 GraalVM 的 JDK 21

# 配置自检（改完 build.gradle.kts / libs.versions.toml 先跑这个，比全量构建快得多）
./gradlew help --offline --console=plain

# 看某条配置实际解析出的版本（改完 catalog 后核对依赖有没有按预期落位）
./gradlew :app:dependencies --configuration debugRuntimeClasspath --offline

# 单元测试（193 个；lyrics / UpdateChecker / PlayMode 纯 JVM 秒级，db/ 与 playback/ 及 ui/ 走 Robolectric，首跑要下 android-all）
./gradlew testDebugUnitTest --offline --console=plain

# Debug 包（快）
./gradlew assembleDebug --offline

# Release 包（含 R8 混淆 + 资源压缩，2~3 分钟；首次/增量差异大）
./gradlew assembleRelease --offline

# 产物位置
#   app/build/outputs/apk/release/WindTone-v1.1.1.apk
#   app/build/outputs/apk/debug/WindTone-v1.1.1-debug.apk
```

**离线构建**：加 `--offline` 可避免网络抖动导致的解析失败（依赖已缓存）。新加依赖时必须先联网构建一次。

### 7.3 验证产物的方法

```bash
# 1) 包名 / 版本 / 输出文件名
cat app/build/outputs/apk/release/output-metadata.json

# 2) 签名证书（确认是 windtone.jks 签的、v2 方案）
export JAVA_HOME="${WINDTONE_JDK:-$JAVA_HOME}"  # 需指向非 GraalVM 的 JDK 21
.toolchain/android-sdk/build-tools/*/apksigner.bat verify --print-certs \
    app/build/outputs/apk/release/WindTone-v1.1.1.apk
# 期望：CN=WindTone, OU=Mobile, O=LuckyLearning, C=CN
#       SHA-256 75eaeccd7cab0db4e30170f52091b3bf80ac0f1d6f89235433ebe2b5876e7398
```

### 7.4 环境坑清单（踩过的）

| 现象 | 原因 | 解法 |
|---|---|---|
| `Unresolved reference: util`（`java.util.Properties()`） | Gradle Kotlin DSL 里 `java` 被 `JavaPluginExtension` 访问器遮蔽 | 文件顶部显式 `import java.util.Properties` |
| Windows 下文件被占用，删不掉 `app/` 目录 | Gradle 守护进程锁文件 | 先 `./gradlew --stop` |
| CI 上 `./gradlew` 报 **退出码 126** | Windows 提交丢了可执行位（索引里是 100644） | `git update-index --chmod=+x gradlew scripts/*.sh` |
| `sdkmanager` 退出码 1 | `setup-android@v3` 默认装 `tools` 包，该包已下架 | workflow 里显式 `packages: 'platform-tools'` |
| 调用 GitHub API 偶发 `HTTP=000` | 本机代理层抖动 | 重试，或用 `WebFetch` 看 Actions 网页 |
| `/tmp` 与 `mktemp` 被 sandbox 拦 | 沙箱限制 | 中间文件写仓库内 `logs/`（已 gitignore） |
| `.github-token` 读取被判 `Bad credentials` | 文件首行是注释，整文件读会带上注释 | `grep -oE '(ghp_\|github_pat_)[A-Za-z0-9_]+' .github-token \| head -1` |

---

## 8. 签名与发布

### 8.1 密钥（本地与 CI 同一套）

| 项 | 值 |
|---|---|
| 密钥库 | `.toolchain/keystore/windtone.jks`（**不进仓库**，已 gitignore） |
| 别名 | `windtone` |
| 证书 DN | `CN=WindTone, OU=Mobile, O=LuckyLearning, C=CN` |
| 有效期 | 10950 天（约 30 年） |
| 证书 SHA-256 | `75EAECCD7CAB0DB4E30170F52091B3BF80AC0F1D6F89235433EBE2B5876E7398` |
| 口令存放 | 本地 `keystore.properties`（gitignore）+ GitHub Secrets |

> ⚠️ **务必自行备份 `windtone.jks` 与口令**。密钥一旦丢失，后续版本无法覆盖升级老用户，只能让用户卸载重装（数据全丢）。

> 历史说明：早期用过 `musicplayer.jks`（DN `CN=Local Music Player`），已废弃。`.toolchain/keystore/musicplayer.jks` 仍在本地但**不再被引用**。

### 8.2 签名取值优先级（`app/build.gradle.kts`）

```
1. -P 命令行参数            （-PkeystorePath=... -PkeystorePassword=... -PkeyAlias=... -PkeyPassword=...）
2. 环境变量 WINDTONE_*      （CI：WINDTONE_KEYSTORE / WINDTONE_STORE_PASSWORD / WINDTONE_KEY_ALIAS / WINDTONE_KEY_PASSWORD）
3. 本地 keystore.properties （storeFile / storePassword / keyAlias / keyPassword）
4. 默认 .toolchain/keystore/windtone.jks
```

口令全缺时 **Release 不签名但构建仍成功**（产出未签名包，装不上）。看到"构建成功但装不上"先查这一条。

### 8.3 发版流程（标准动作）

> ⚠️ **先查 Actions 额度**。耗尽时**不要**跑本节命令（会打 tag、烧空额度且无 APK）——只 `push.sh` 推代码，恢复后按 [`待发版积压.md`](待发版积压.md) 补发。细则见 **§0.3**。

```bash
# 方式 A：用脚本一把梭（推荐）
bash scripts/release.sh patch --verify   # ★ 发版 + 自动跑完四步验证链（阻塞 10~20 min，建议后台跑）
bash scripts/release.sh patch            # 只发不等 CI
bash scripts/release.sh minor            # 1.1.1 → 1.2.0
bash scripts/release.sh major            # 1.1.1 → 2.0.0
bash scripts/release.sh 1.5.2            # 指定版本号
bash scripts/release.sh patch --dry      # 先看它要干什么


# 方式 B：手工
# 1) 改 app/build.gradle.kts 顶部 appVersionName / appVersionCode（code 必须 +1）
# 2) git add -A && git commit -m "release: v1.0.1"
# 3) bash scripts/push.sh origin main
# 4) git tag v1.0.1 && bash scripts/push.sh origin v1.0.1   ← 推 tag 触发 Release 工作流
```

`scripts/release.sh` 已内置：**跑单测 → 打印发版说明预览 → 改版本号 → 提交 → 打 tag → 推送 + 推后查证（REST 实查远端，查不到自动补推一次）→（`--verify`）四步验证链**。
推送一律走 `scripts/push.sh`（token 经 `GIT_ASKPASS` 临时传递，**不写进 git 配置、不进 remote URL**）。

> ⭐ **`--verify` 是默认推荐用法**。它会在推送后自动调用 `scripts/verify-release.sh` 跑完
> §8.6 的四步验证链（tag → workflow 轮询 → 4 个资产 → 公共仓库 `latest.json` → 下载验签 +
> 包内版本串），**一条命令出 PASS/FAIL 结论**，省掉 4~5 次分开的 REST 调用与轮询，也避免漏掉第 ④ 步。
> 代价是阻塞等 CI（10~20 分钟），放后台跑即可：
> ```bash
> bash scripts/release.sh patch --verify > build/release.log 2>&1 &
> ```
> 事后想单独补跑：`bash scripts/verify-release.sh vX.Y.Z`（CI 已知跑完加 `--skip-ci`）。
> 三个脚本的完整说明见 `docs/版本号发版规则.md` 第三节（发版流程）。

> **单测在改版本号之前跑**（2026-10-14 调整）。反过来的话，单测一失败就把仓库留在
> 「版本号已改但没提交」的脏状态，下次重跑会让 `versionCode` 再 +1（已实际踩出过 code 6）。
> 现在测试失败 = 零残留，直接修完再跑即可。
>
> ⚠️ **单测必须用可用的 JDK**。本机 shell 的 `JAVA_HOME` 默认指向 `graalvm-jdk-21.0.7`，
> 它的 `jlink` 处理 `platforms/android-35/core-for-system-modules.jar` 会报 `JdkImageTransform` 失败。
> 脚本已自动识别「路径带 graal」并换成 `openjdk-21.0.2`（原来只判断 `JAVA_HOME` 是否为空，是无效的）。

> **tag 名必须等于 `versionName`**。workflow 里有硬校验，不一致会直接 `exit 1`，因为应用内更新靠版本号比对，tag 与 versionName 脱节会导致误判。

> **发版说明是固定模板**（v1.0.7 起）：正文来自 `.github/release-notes/template.md`，
> 由 `scripts/render-release-notes.sh` 只替换 `{version}` / `{version_code}` / `{date}` 三个占位符
> —— **发版时不需要写任何文案，也不要让 AI 去生成内容**。
> 想给某个版本单独写说明时，放 `.github/release-notes/v{版本}.md` 覆盖模板即可（平时不用建）。
> 本地预览：`bash scripts/render-release-notes.sh`（跑 `release.sh` 时也会自动打印一份）。
> 规矩见 `.github/release-notes/README.md`。

> **同一版本号要重发**：GitHub 上 tag 不能重复，必须先在远端删掉旧 Release 与 tag
> （`DELETE /repos/{owner}/{repo}/releases/{id}`、`DELETE /git/refs/tags/v{X.Y.Z}`）、本地 `git tag -d`，
> 再跑 `release.sh`。`appVersionCode` 会照常 +1（同名不同 code 才能覆盖安装）。
> ⚠️ 已发过的同名版本，安装包里的 versionName 相同，应用内更新**检测不到差异**，只能手动下载覆盖安装。

> **本机沙箱会挡 GitHub**：`github.com` / `api.github.com` 在默认沙箱内一律 TLS 握手失败
> （百度、dl.google.com 正常）。凡访问 GitHub 的 `curl` / `git push` 都要在沙箱外执行（工具层豁免），
> 否则 `release.sh` 会在推送步骤中断——此时**不要重跑 release.sh**（versionCode 会被再 +1），
> 直接补推即可：`bash scripts/push.sh origin main` + `bash scripts/push.sh origin vX.Y.Z`。

> ⚠️ **tag 推送「静默失败」已根治（2026-10-16，v1.0.6 ~ v1.1.4 六次全中后定位）**。根因链：
> 本机 Git Bash 的 `mktemp` 返回**混合斜杠的盘符路径**（`C:\...\Temp/tmp.xxx`）→ 沙箱 safe-delete
> 守卫拒绝删带盘符前缀的路径 → `push.sh` 的 `trap 'rm -f "$ASKPASS"' EXIT` **删除失败** →
> **bash 的 EXIT trap 里最后一条命令失败会覆盖整个脚本的退出码** → `push.sh` push 成功却返回 1 →
> `release.sh` 的 `set -e` 在「推完 main」后当场中止，**tag 那一行永远执行不到**。
> 表象：main 推成功、末尾只有 `[safe-delete][SAFE_DELETE_INVALID_PATH] embedded drive prefix is not
> allowed` 噪声、退出码非 0，看起来像成功，实际远端没有 tag。
> **修复（`push.sh`）**：askpass 路径用 `cygpath -u` 归一成 `/c/...` 再删（rm 能正常删，临时 token
> 文件不再残留）+ trap 里 `rm` 加 `|| true`（任何情况下不污染退出码）。
> **加固（`release.sh`）**：不再信任推送方的返回值——推完 main / tag 后用 GitHub REST **实查远端**
> （main 比对远端 SHA == 本地 HEAD；tag 查引用 200），查不到自动补推一次，仍失败就非零退出并给出
> 手动补救命令；`✓ 已推送 vX.Y.Z（远端已确认）` **只在远端查证通过后打印**。
> 万一仍失败，手动补推（同样在沙箱外）：
> ```bash
> bash scripts/push.sh origin vX.Y.Z
> # 事后核实（期望 200）：
> TOKEN="$(grep -oE '(ghp_|github_pat_)[A-Za-z0-9_]+' .github-token | head -1)"
> curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
>   https://api.github.com/repos/LuckyLearning/WindTone/git/refs/tags/vX.Y.Z
> ```
> 完整验证链见 §8.6。

### 8.4 CI 需要的 4 个 Secrets

仓库 **Settings → Secrets and variables → Actions**：

| Secret | 值 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 .toolchain/keystore/windtone.jks` |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | `windtone` |
| `KEY_PASSWORD` | key 口令 |

已配置完毕。**换密钥时这两个地方必须同步改**：本地 `keystore.properties` + 仓库 Secrets，否则 CI 出的包与本地包签名不一致，无法互相覆盖升级。

写 Secrets 的方式（环境无 `gh` CLI 时，用 API + libsodium 加密）：
```
GET /repos/{owner}/{repo}/actions/secrets/public-key   → 取 key + key_id
   用 nacl.public.SealedBox 加密值（需 PyNaCl）
PUT /repos/{owner}/{repo}/actions/secrets/{name}       → 提交 encrypted_value + key_id
```
写入用细粒度 PAT 需要 **Secrets: Read and write** 权限。

### 8.5 正确覆盖安装的前提

覆盖安装（不卸载直接升级）要求满足两条，缺一条就装不上：

1. **applicationId 相同**（`com.luckylearning.windtone`；debug 包带 `.debug` 后缀，与 release 是两个应用，可共存）。
2. **签名证书相同** —— 必须是同一套 `windtone.jks` 签名，且 `versionCode` 比已装的**大**。

> ⚠️ **本版起不再有数据兼容**（2026-10-14 拍板，见 §0 兼容口径）：`MusicDb` 从 v2 直接到 v4、
> 没有任何迁移路径，老用户覆盖安装会走 `fallbackToDestructiveMigration()` **整库重建** ——
> 歌单 / 收藏 / 播放统计 / 逐曲歌词偏移 / 队列 / 搜索历史 / 曲库缓存全部丢失，曲库需重新扫描。
> 「能覆盖安装」与「数据能保留」是两件事，别混。

### 8.6 发版后验证（每次都要走一遍）

`release.sh` 跑完**不等于**发版成功 —— tag 可能没推上去、CI 可能失败、包可能没签名。

**首选：一条命令跑完四步**（自动含下面 ①②③④ 全部检查，只需在沙箱外执行）：

```bash
bash scripts/verify-release.sh vX.Y.Z            # 等 CI 跑完再核（自动轮询，45s 一次）
bash scripts/verify-release.sh vX.Y.Z --skip-ci  # CI 已确认跑完，只核后半段
python scripts/verify_apk.py vX.Y.Z              # 只下 universal 包验签
```

输出以 `✓ 验证链全部通过：vX.Y.Z` / `✗ 验证链有 N 项未通过` 结尾，退出码同步（0/1）。
手动核对时用下面的原始命令（脚本已封装，仅在需要查细节时用）：

```bash
# 准备：所有 curl 都要在沙箱外执行
TOKEN="$(grep -oE '(ghp_|github_pat_)[A-Za-z0-9_]+' .github-token | head -1)"
REPO=repos/LuckyLearning/WindTone

# ① 远端 tag 真的在（404 就补推，见 §8.3）
curl -s -o /dev/null -w "tag: %{http_code}\n" -H "Authorization: Bearer $TOKEN" $REPO/git/refs/tags/vX.Y.Z

# ② workflow 跑完且 success（tag 推上去后约 3~5 分钟）
curl -s -H "Authorization: Bearer $TOKEN" "$REPO/actions/runs?per_page=3" \
  | python -c "import sys,json;[print(r['name'],r['head_branch'],r['status'],r['conclusion']) for r in json.load(sys.stdin)['workflow_runs']]"

# ③ Release 建好、4 个 ABI 资产齐全、正文是模板渲染稿
curl -s -H "Authorization: Bearer $TOKEN" $REPO/releases \
  | python -c "import sys,json;r=json.load(sys.stdin)[0];print(r['tag_name'],r['name']);[print(' ',a['name'],a['size']) for a in r['assets']]"
# 期望：arm64-v8a / armeabi-v7a / x86_64 / universal 四个，正文首行为「**听风 WindTone X.Y.Z**」

# ④ 签名确实是 windtone.jks（把 asset 下载下来验一次）
./.toolchain/android-sdk/build-tools/35.0.0/apksigner.bat verify --print-certs wt.apk
# 期望 SHA-256 = 75eaeccd7cab0db4e30170f52091b3bf80ac0f1d6f89235433ebe2b5876e7398
```

> **第 ④ 步别省**。「构建成功但装不上」最常见的根因就是没签名（CI 缺 `KEYSTORE_BASE64` 时只是
> `::warning::`，step 依然 success）。签名不通过 = 用户装不上，必须回查 Secrets。
> `scripts/verify_apk.py` 已把这一步包进去（本机无 apksigner，脚本直接解析 APK Signing Block
> 扫 X.509 DER 比对 SHA-256）。
>
> ⚠️ **还要核包内版本串**：zipalign 对齐会让**连续几版字节数完全相同**
> （1.1.52 / 1.1.53 / 1.1.54 三个 universal 都是 3414642 字节），只看大小会误判成「发了旧包」。
> `verify_apk.py` 会解压 `AndroidManifest.xml` 找 UTF-16LE 的版本串再对一次。
>
> workflow 的 step 列表可以整体确认一遍（15 个全 success 才算过）：
> ```bash
> curl -s -H "Authorization: Bearer $TOKEN" "$REPO/actions/runs/<run_id>/jobs" \
>   | python -c "import sys,json;[print(j['name'])] or [print('  ',s['number'],s['name'],s['conclusion']) for j in json.load(sys.stdin)['jobs'] for s in j['steps']]"
> ```

---

## 9. CI 说明

### 9.1 `android-release.yml`（Release APK）

- **触发**：推送 `v*` tag；或手动 `workflow_dispatch`（手动只构建不发布）。
- **权限**：`contents: write`（用自动注入的 `GITHUB_TOKEN` 创建 Release，**不用 PAT**）。
- **Runner**：`ubuntu-24.04`（**刻意固定**——`ubuntu-latest` 计划于 2026-10-19 迁移到 Ubuntu 26，固定镜像避免构建环境被静默替换）。
- **流程**：检出 → JDK 17(temurin) → setup-android（`packages: 'platform-tools'`）→ 装 `platforms;android-35` + `build-tools:35.0.0` → setup-gradle（带缓存）→ 读版本号 → **校验 tag == versionName** → 从 Secret 还原 keystore → 单测 → `assembleRelease -PabiSplits=true` → 收集全部 APK + 算 SHA-256（写入 Step Summary）→ 上传 artifact → 组装 Release 说明（`bash scripts/render-release-notes.sh`，固定模板只换版本号）→ `softprops/action-gh-release` 创建 Release（`body_path`）。

### 9.1.1 ABI 分包与发版说明（v1.0.7 起）

**产物**：CI 传 `-PabiSplits=true`，一次产出 4 个包（本地 dev 不传该参数，`assembleDebug` 仍只出一个包）：

```
WindTone-v{版本}-arm64-v8a.apk      # 绝大多数手机 / 车机
WindTone-v{版本}-armeabi-v7a.apk    # 老设备
WindTone-v{版本}-x86_64.apk         # 模拟器 / ChromeOS
WindTone-v{版本}-universal.apk      # 拿不准架构时的兜底
```

**命名是契约**：应用内更新在 Atom 通道下拿不到资产列表，只能按名字推导下载地址。
所以改命名必须同时改 `app/build.gradle.kts` 的 `androidComponents` 命名逻辑、
`UpdateChecker.apkNameCandidates()` 和 `APK_ASSET_TEMPLATE*` 三个 BuildConfig 字段，
否则用户点「下载并安装」会 404（单测 `UpdateCheckerTest` 已锁住候选顺序）。

`apkNameCandidates()` 的顺序固定为：本机 ABI → 其它 ABI → universal → **历史单包命名**
（最后一条保证 1.0.6 及以前发布的版本也能升级上来，不要删）。

**Release 说明**：**固定模板** `.github/release-notes/template.md` + 渲染脚本
`scripts/render-release-notes.sh`（CI 调它、本地 `release.sh` 也调它，同一份逻辑，不会漂移）。
发版时只替换 `{version}` / `{version_code}` / `{date}`，**发版过程不需要写文案**；
单个版本要特写时用 `.github/release-notes/v{版本}.md` 覆盖（平时不建这个文件）。
模板里的安装包名必须与上面的命名契约一致，否则用户照着下会 404。

### 9.2 `android-ci.yml`（Android CI）

- **触发**：push / PR 到 `main`、`master`；可手动。
- **权限**：`contents: read`。
- **流程**：JDK 17 → SDK → 单测 → `assembleDebug` → 上传 debug 包（保留 7 天）+ 测试报告。

### 9.3 两个已修复的历史坑（别再踩）

1. `setup-android@v3` 的 `packages` 默认值是 `'tools platform-tools'`，而 **`tools` 包已从 SDK 仓库下架** → `sdkmanager` 退出码 1。**必须**显式 `with: packages: 'platform-tools'`。
2. `gradlew` 在 Windows 提交后**丢失可执行位**（索引 100644）→ Linux runner 上 `./gradlew` 退出码 126。已用 `git update-index --chmod=+x` 修正。**在 Windows 上新增任何 `.sh` 脚本后，都要记得补可执行位**：
   ```bash
   git update-index --chmod=+x scripts/新脚本.sh
   ```

---

## 10. 代码约定（必须遵守）

1. **状态只在 ViewModel 里**。Compose 组件不持有业务状态，只 `collectAsStateWithLifecycle()` + 回调。
2. **`progress` 流专用于高频进度**。任何 250ms 级刷新的 UI 都必须订阅它，别塞进 `player`。
3. **数据类加 `@Immutable`**（如 `Song`）。所有字段 `val`，更新走 `copy()`。漏了注解会让 Compose 判定类型不稳定，列表滚动掉帧。
4. **IO 一律 `Dispatchers.IO`**。扫描、文件读写、歌词解码、位图解码都要 `withContext(Dispatchers.IO)`。
5. **依赖版本一律走 Version Catalog**：坐标与版本只写在 `gradle/libs.versions.toml`，脚本里只出现 `libs.xxx.yyy` 访问器。**任何 `implementation("group:name:version")` 字符串坐标都算违规**（唯一例外是 `appVersionName` / `appVersionCode`，见 §2）。加依赖照 §11.8 走。
6. **不加不必要的依赖**。网络用 `HttpURLConnection` + `org.json`；取色自己实现；不引 Hilt/Retrofit/OkHttp。
7. **改包名要同步 5 处**：源码目录、`namespace`、`applicationId`、`proguard-rules.pro` 的 `-keep`、`MusicWidgetProvider` 的 action 常量（+ Manifest 的 `intent-filter`）。FileProvider authority 用 `${applicationId}` 占位符，**不用动**。
8. **新增 `.sh` 脚本后补可执行位**（见 §9.3）。
9. **本地资料写 `docs/`**（已 gitignore，不会外泄）。
10. **敏感值绝不硬编码**：token、口令一律走「本地 gitignore 文件 + CI Secrets」。
11. **版本号只在用户主动要求发版时才改**：日常功能开发不动 `appVersionName` / `appVersionCode`；只有当用户明确说「发个版本」/「release」时才用 `scripts/release.sh` 改版本号、打 tag、触发 CI。
12. **改 UI 先改效果图（硬约定）**：`docs/ui/` 是全部 UI 的设计真源 —— **2026-10-19 起按章拆成 7 个文件（共 57 屏）**：`index.html` 是总览（章节卡 + 全屏索引），`01-main.html` ~ `07-spec.html` 是分章屏稿，样式 / 行为 / 图标池分别在 `ui.css` / `ui.js` / `ui-sprite.js`。**改 UI 时只读目标屏所在的那一章**，不要整目录通读。任何 UI 改动的顺序固定为：先改对应分章并预览确认 → 再按该屏标注的 Compose 位置改 `ui/*.kt` → 出包核对。改配色先改 `ui.css` 顶部的 CSS 设计令牌，再同步 `Theme.kt`。**分章文件里不写内联样式与脚本**；新屏/新组件在对应章补位，编号顺延（同一屏的另一种状态用字母后缀，如 ⑲/⑲B，不占新号）；屏数一律由脚本现算，不手写数字。维护细则见 `docs/ui/README.md`。
13. **设置页是「一主六子」结构（v1.1.3 二次重构）**：主页面**只放六张二级分类入口卡**（`CategoryRow`，一屏放下）；外观 / 播放 / 歌词 / 音乐库 / 更新 / 关于与诊断各成一个子页。
    - v1.0.7 起曾是「主页面平铺外观 / 播放 / 歌词三组 + 三条子页入口」，**v1.1.3 把三组也下沉成子页** —— 起因是桌面悬浮歌词的五个外观选项加入后「歌词」一组独占两屏，主页又开始长回去。
    - 子页是 `SettingsScreen` 内部的 `SettingsPage` 枚举状态（**7 项**，`pagePositions` 数组长度必须同步），**不新建 Activity、不引导航库**；子页里返回键先回主页面。
    - ⚠️ 加子页 = 枚举加一项 + 「六张卡」里加一张 + `mutableStateListOf(0, 0, …)` 加一个 0，三处缺一就崩或错位。
    - **「关于与诊断」内部还有一层条件渲染（v1.1.31）**：`SectionTitle("调试")` 起的整段（前台应用记录 / 悬浮歌词自检 / 崩溃日志 / 开机自启）
      被 `if (player.debugUnlocked)` 包着，默认**不存在于界面**；解锁动线是在「关于」信息卡（`StatCard(onClick = …)`）上连点 5 次，
      关闭动线是分区顶部「显示调试功能」开关。排障工具对普通用户是纯噪音，藏起来不是安全措施，是不打扰。
      ⇒ 以后把新的排障项加进这段 `if` 里，**不要再往日常分区加调试开关**。
14. **chevron 的使用边界（v1.1.3 再放宽一档）**：右箭头（`AppIcons.ChevronRight`）只给 **`CategoryRow`（设置主页分类卡）** 和 **`ActionRow`**；`ActionRow` 里还只给**点开会进入另一个界面**的行（子页入口、打开弹层 / 弹窗的行，如「均衡器与音效」「下载线路」「过滤短音频」），**就地执行动作**（「检查更新」）或**就地展开**（「查看日志」）的行不加。弹层的 `OptionRow` 仍然不带箭头。行右侧可放「当前值摘要」（如「过滤 < 60 秒」「已是最新」）把状态前置。
    - `CategoryRow` = **一整类设置的门面**（左侧 40dp 语义图标 + 标题 + 摘要 + 箭头，只出现在设置主页）；
      `ActionRow` = **某一类设置里的一条**（无图标，出现在子页里）。两层信息密度不同，不会混淆。
15. **手机版导航是「顶栏汉堡 + 侧边抽屉」（v1.1.3）**：底部五视图导航已**整体移除**（列表因此多出一条导航栏的纵向空间），五个浏览视图 + 「扫描范围 / 设置」都收进左侧抽屉（`ui/LibraryDrawer.kt`）。
    - 规格：宽 280dp、右缘圆角 28dp、滑入 220ms、遮罩点收起、返回键收起、抽屉盖在迷你播放条之上；选中项 = secondaryContainer 实底胶囊 + 加粗。
    - 抽屉挂在 `MainScreen` 顶层 Box、**在播放页 / 设置页这两个全屏覆盖层之前** —— 那两个页面各有自己的返回按钮，效果图里也没有汉堡入口。
    - **v1.1.14 起车机也走这一套单栏 + 抽屉**：车载两栏（`CarHomeScreen`）已下线，车机上的「设置」同样从汉堡进抽屉。
16. **入口收敛（2026-10-17）**：同一个目的地不再挂多处入口。
    - **「设置」只有左侧抽屉一个入口**：顶栏「⋮」菜单里那一项已删（车机版左导航里的「设置」保留 —— 那是车机唯一的入口，且它本身就是左面板）。
    - **「检查更新」只在设置 → 更新**：原先抽屉与顶栏「⋮」菜单各挂一份，都已删。顶栏「⋮」菜单从此**只放就地动作**（扫描范围 / 重扫 / 导入 / 重匹配 .lrc / 深度扫描 / 收听报告），不放跳转项。
    - 这三处 `LibraryHeader` / `LibraryDrawer` 的 `onOpenSettings`、`onCheckUpdate` 参数已随之删除；`LibraryDrawerTest` 里有两条对照测试锁住这个口径（`showsBrandAndBothGroups` 只列 7 项、`checkUpdateIsNotInDrawer` 断言抽屉里不存在「检查更新」）。

17. **文案一律走资源，不再写死在 Compose 里（2026-10-24 起，新代码硬性要求）**。原「9.3 第二步不做」已被用户推翻。
    - **两个桶**：`res/values/strings.xml` = **英文兜底**（每条都必须在，否则英文设备 `NotFoundException`）；
      `res/values-zh/strings.xml` = 中文。**新增语言只加 `values-xx/`**，Kotlin 一行不改。
    - **计数用 `<plurals>`**（英语要区分单复数，中文只有 `other`），插值用 `%1$d` / `%1$s`，不要用字符串拼接。
    - **枚举标签不写在枚举里**：`state/UiState.kt` 的 `LibraryView` / `SortOrder`、`data/Prefs.kt` 的 `PlayMode` 等，
      一律通过 `ui/UiText.kt` 的 `labelRes()`（**纯映射，JVM 单测可断言**）+ `displayName()`（`@Composable`，内部 `stringResource`）取文案。
      数据层从此**不持有界面文案**——这是能加语言的关键解耦。
    - ⚠️ `stringResource` 是 `@Composable`，**不能**在 `buildString {}` / `ifBlank {}` / `LaunchedEffect` / `remember` 回调里调用，
      必须先求值成局部 `val` 再传进去（踩过的坑，见 §12）。
    - **改造粒度按文件**：一个 Compose 文件要么整块改造，要么先不动，不做"同一文件里一半资源一半字面量"。
    - **测试适配**：资源化后 Robolectric 默认 en-US，断言中文的 UI 测试要给 `@Config` 加语言限定（如 `qualifiers = "zh-w360dp-h640dp-xhdpi"`）；
      断言标签文案的纯 JVM 测试改为断言 `labelRes()` 的**资源 id**，与语言无关。
    - **进度**：第 1 批（`LibraryHeader` / `LibraryDrawer` / 播放模式标签）✅，其余按模块分批，未改造模块保持原状不算违规。

---

## 11. 常见任务手册

### 11.1 加一个设置项

**先决定它属于哪一层**（见 §10 第 13 条）：主页只有六张分类卡，所以要么挂进已有的某个子页（外观 / 播放 / 歌词 / **曲库与存储** / 实验室 / **关于与更新**），要么新开一个子页并同步「枚举 + 卡片 + `pagePositions`」三处。

1. `data/Prefs.kt`：加 `var xxx: T` 属性 + `private companion object` 里加 `KEY_XXX` 常量。
2. 对应的分域 `ui/*ViewModel.kt`：加读取方法（如 `fun xxxEnabled(): Boolean = prefs.xxx`）与写入方法（`fun setXxx(v: T) { prefs.xxx = v; … }`）。
   **按域选择 VM**：播放相关 → `PlayerViewModel`（多数情况下直接加到 `PlaybackEngine`，用接口委托转出）；
   曲库相关 → `LibraryViewModel`；维护 / 诊断 → `SettingsViewModel`；更新相关 → `UpdateViewModel`。
3. **加在对应的「分区」里，不要加进页面函数**（2026-10-30 用户口径：*功能代码独立，页面组合是另外的代码*）：
   - 分区 = 自带标题 / 行 / 弹窗状态的一个 composable，放在该页的 `Settings*Sections.kt` 里（已有 `SettingsLibrarySections.kt`、`SettingsAboutSections.kt`）；
   - 页面函数（`SettingsLibraryPage` / `SettingsAboutPage`）**只保留一串按顺序的调用** + 顶部一张「分区顺序表」注释；
   - 于是 **加功能 = 加一个 Section + 页面里加一行**，**调顺序 = 页面里挪一行**，改一区只读一个函数；
   - 页内行仍用 `SwitchRow` / `SliderRow` / `ActionRow`，通过参数传入回调。
   - `SwitchRow` 的描述太长（超过约 30 字）时，副标题压成一行要点，详细说明挂到 `onInfo`（标题右侧的 `?` 图标 → 弹窗）。
   - 新增入口行用 `ActionRow(title, subtitle, onClick, trailing = 当前值摘要, chevron = true)`（chevron 仅给"进入另一个界面"的行，见 §10 第 14 条）。
   - **成组滑块默认收起**（2026-10-30 口径）：一整组「用得少 + 一碰就改值」的控件（当前只有「速度与变调」）用
     `CollapsibleSectionTitle(title, summary, expanded, onToggle, compact = 弹层里传 true)` 代替 `SectionTitle`，**收起状态不持久化**
     ——每次进页面都收起，正是为了压掉误触面；`summary` 必须带上所有关键当前值（`1.00x · 原调`），否则收起后看不出被动过。
     ⚠️ 收起态**不搬走** ↺：它仍在展开后各行的行尾（见 §11.14-①）。
   - **新文案一律落资源**：标题 / 副标题 / 弹窗说明同时写进 `res/values/strings.xml`（英文）与 `res/values-zh/strings.xml`（中文），
     行里用 `stringResource(R.string.xxx)`。**不要**为省事直接写中文字面量（见 §10 第 17 条）。
4. 若 `SettingsScreen` 参数变了，同步改 `MainScreen.kt` 里的调用点。
5. **出厂默认值写进 `SettingsDefaults`（`data/Prefs.kt`）**，并给该行挂 `onReset`（见 §11.14-①）。
   ⚠️ 只在 getter 里写默认值是不够的：那样「恢复默认」写回的值与「首次安装」拿到的值会漂移，
   且**不报错、不崩溃**。`SettingsDefaultsTest` 会把两者逐项比一遍，漏了立刻红。
6. **「只在冷启动生效」的开关，setter 里不要执行动作**：典型是 v1.1.16 的「打开应用继续上次播放」——
   它描述的是**下次打开应用**的行为，写进 Prefs 就结束了；真正的判定点在
   `PlaybackEngine.restoreQueueIfNeeded` 里，且被抽成纯函数 `playback/AutoResume.kt` 的
   `shouldAutoResume(enabled, queueSize, isPlaying)` 以便 JVM 单测覆盖。
   若在 setter 里顺手 `play()`，用户拨一下开关就会听到歌跳位置，而他只是想改下一次的设置。

### 11.2 给数据库加字段 / 加表

1. `data/db/Entities.kt`：改对应 `@Entity`（新字段给 Kotlin 默认值）。
2. `data/db/Daos.kt`：补读写方法。
3. `data/db/MusicDb.kt`：`entities` / DAO 抽象方法（新表时）、**`version` +1**。
4. **不写 Migration**（本版无兼容包袱）：结构变了就让 `fallbackToDestructiveMigration` 重建即可。
5. 编译一次让 KSP 导出 `app/schemas/.../n+1.json`（**schema 文件入库**）—— 它是当前结构的权威快照，
   排障 / 写 DDL / 核对索引时对照它。
6. 跑 `./gradlew testDebugUnitTest`（`LibraryDbTest` 会验证新结构下 DAO 与 CASCADE 仍正常）。
7. 同步 `docs/数据库设计-MusicDb.md` 的 §二 与版本历史。

### 11.3 加一个浏览视图

1. `state/UiState.kt` 的 `enum class LibraryView` 加枚举值（带中文 `label`）。
2. `ui/LibraryViewModel.kt` 的 `refreshGroups()` 或新增分组逻辑，把结果放进 `LibraryUiState`。
3. `MainScreen.kt` 里按 `library.view` 分支渲染新列表；入口加在视图切换控件上。
4. 需要持久化的话，`Prefs.libraryView` 走的是 `LibraryView.name`，`from()` 已做非法值兜底。

### 11.4 改版本号并发版

> ⚠️ **前提**：只在用户主动要求发版时才执行。日常开发只提交功能代码，不动版本号。

```bash
bash scripts/release.sh patch --verify   # ★ 推荐：发版 + 自动跑完四步验证链，一条命令出结论
bash scripts/release.sh minor            # 或 patch / major / 具体版本号（不带 --verify 则只发不等 CI）
```

它会跑单测 → 把 `appVersionCode` +1、`appVersionName` 改成新值 → 提交、打 tag、推送
（推完 REST 实查远端）；带 `--verify` 时接着跑四步验证链（§8.6）。

**执行前还需确认**：Secrets 里的 keystore 是当前在用的那一套。

> ⚠️ 脚本会阻塞等 CI 约 10~20 分钟，建议后台跑并落日志：`... --verify > build/release.log 2>&1 &`。
> 失败时先看清**卡在哪一步**再决定补救（`docs/版本号发版规则.md` 第三节 3.4 有对照表）——
> **版本号改过之后不要无脑重跑**，会把 `versionCode` 再 +1。

### 11.5 换签名密钥

1. 生成新库：`keytool -genkeypair -keystore .toolchain/keystore/新名.jks -alias 新别名 -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=..., O=..., C=CN"`
2. 改本地 `keystore.properties`（4 个键）。
3. 改 `build.gradle.kts` 里的 `defaultKeystore` 路径与 doc 注释。
4. 用 API 重新写入 4 个 Secrets（`KEYSTORE_BASE64` 取新库 base64）。
5. **提醒用户**：换密钥后老版本无法覆盖升级，必须卸载重装（数据丢失）。
6. 若历史 Release 的 APK 是旧密钥签的，需要删掉重发（否则应用内更新下载到的包签名不匹配，装不上）。

### 11.6 加一个可单测的纯逻辑

放到 `app/src/test/java/com/luckylearning/windtone/...`，用 JUnit 4。分两类：

- **纯 JVM 逻辑**（`lyrics/`、`UpdateChecker`、`PlayMode`）：直接写 JUnit，无 Android 依赖。
  `testOptions.unitTests.isReturnDefaultValues = true` 已开，Android 桩方法返回默认值而不抛异常。
- **碰数据库的逻辑**（`db/`）：加 `@RunWith(RobolectricTestRunner::class)`，
  就能在 JVM 单测里用**真实 SQLite** 跑 Room —— 建库、DAO、级联删除（CASCADE）、软删语义都能测，不需要模拟器。
  写法参考 `db/LibraryDbTest.kt`（8 项，覆盖曲库 upsert / 软删 / CASCADE / 队列与播放态 / 搜索历史）。
- **碰 Compose 界面的逻辑**（`ui/`）：同样用 Robolectric，配 `createComposeRule()` 就能在 JVM 里
  渲染界面、点一下看行为，不需要设备。**但必须开 `@GraphicsMode(NATIVE)`，否则点击会静默打偏**
  —— 完整说明与三个必踩的坑见 **§11.13**，参考 `ui/LibraryDrawerTest.kt` / `ui/SettingsScreenTest.kt`。
- ⚠ **测试可以用 `java.time`，被测代码不行**：单测跑在 JDK 17 上，随便用；但 `minSdk = 24` 的
  生产代码用 `java.time` 会在 Android 7 上崩（`NoClassDefFoundError`，lint 报 `NewApi`，见 §12）。
  `ListeningReportTest` 里那套 `TimeZone` / `ZoneId` 双变量就是为这个分界服务的。
- 日期/时间相关的聚合**必须有单测**：错一天、错一小时不会有任何崩溃或日志，只会让人看到
  "早上 8 点就变成新的一天""昨天的时长跑到今天"，除了单测没有别的办法发现。
- **统计 / 聚合类逻辑必须是纯函数，且只有一个数据源**（范例：`data/LibraryStats.kt`，
  「曲库格式与容量」）。三条硬规矩：①输入就是内存里已经有的列表（`library.songs`），
  不查库、不碰文件系统、不落表 —— 同一件事查两遍就会出两份数字，之后所有时间都花在解释差异上；
  ②脏输入的兜底写在函数里并逐条有单测（无扩展名、MIME 说谎、`sizeBytes = 0`、同一格式多种写法）；
  ③界面上「百分比文字」与「条形长度」必须来自同一个比例值，各自算一遍迟早对不上，
  而 72% 的条配 71% 的字，足以让用户整页都不信。

### 11.7 排查"装不上"的包

按顺序查：① `apksigner verify --print-certs` 看签名是否为新密钥；② 与已安装版本的签名 SHA-256 对比（不一致 → 无法覆盖，需卸载重装）；③ 看是否未签名（§8.2 的口令缺失路径）；④ `output-metadata.json` 核对 `applicationId` 与 `versionCode`。

### 11.8 加一个第三方依赖

1. **`gradle/libs.versions.toml` 的 `[versions]`** 加版本号，变量名用 camelCase（如 `coil = "2.7.0"`）。
   同类库（Room / Media3 / Lifecycle 那种多 artifact 同版本）**共用一个版本变量**，不要一行一个。
2. 同文件的 `[libraries]` 加坐标，别名用 kebab-case（如 `coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }`）。
   - 属于 Compose BOM 管控范围的库**不要写 `version`**（见 §2.2）。
3. **`app/build.gradle.kts` 的 `dependencies { }`** 里引用访问器：`implementation(libs.coil.compose)`。
   - KSP / kapt 处理器用 `ksp(libs.…)`，**不要**用 `implementation`。
   - 只在测试用的加 `testImplementation(libs.…)`；只在 debug 构建的加 `debugImplementation(libs.…)`。
4. 校验：
   ```bash
   ./gradlew :app:dependencies --configuration debugRuntimeClasspath   # 看实际解析出的版本
   ./gradlew testDebugUnitTest assembleDebug                          # 配置能过 + 能打包
   ```
   配置阶段就能暴露 `Unresolved reference 'libs.…'`（别名拼错）与 catalog 语法错误，比全量构建快得多。

**升级一个库**：只改 `[versions]` 里那一行，所有引用点自动跟随。升级 `agp` / `kotlin` / `ksp` 三者要一起动，并核对 Gradle Wrapper 与 JDK（见 §7.4）。

**注意**：若某个依赖只在 CI 或某个 flavor 里出现，仍然要写进 catalog —— **不要**为了"只在一处用"就退回写字符串坐标，那样版本会重新散开。

### 11.9 排查「悬浮歌词没反应」

链路跨 7 个环节，任何一环断掉，用户侧的表现都是同一个"没反应"。**先让用户跑自检，不要猜**：

设置 → 关于与诊断 → **调试 → 悬浮歌词自检**（「调试」默认隐藏：在「关于」卡上连点 5 次才出现，v1.1.31）。它一次测完并直接给出结论：

| 环节 | 检查项 | 断了意味着 |
|---|---|---|
| ① | `Settings.canDrawOverlays` 系统授权 | 用户没授权 / 授权被 ROM 撤掉 |
| ② | `Prefs.lyricOverlay` 开关 | 开关是关的（或被失败回退逻辑关掉） |
| ③ | `LyricOverlayService.serviceAlive` | 服务没起来或被系统杀了（前台服务被拦） |
| ④ | `LyricOverlayService.windowAttached` | 服务活着但 `addView` 被拒 = ROM 悬浮窗白名单 |
| ⑤ | `LyricOverlayBus.health` 最近上报 | 失败原因原文（含异常类名与 message） |
| ⑥ | `LyricOverlayBus.foreground` | App 在前台 → **按设计隐藏**，退后台或点「显示预览 6 秒」 |
| ⑦ | `LyricOverlayBus.content` | 有文字可显示；空 = 无歌词且取不到歌名 |
| ⑧ | `LyricOverlayService.windowGeometry` | **挂上了 ≠ 看得见**：窗口被推到屏幕外 / 尺寸量成 0 时，前 7 项照样全绿 |

代码侧入口：`LyricOverlayBridge.snapshot(context)` → `OverlaySnapshot.report()`。
失败原因同时落在 `filesDir/crash/diag-*.txt`（`CrashLog.record`，见设置页崩溃日志）。
自检对话框里的四个动作（显示预览 6 秒 / 去系统设置授权 / 重新拉起服务 / **重置位置并预览**）都是就地执行、不关弹窗，
方便"把一件事做完 → 立刻回到同一屏看数字变没变"。

**「自检全绿但桌面上什么都没有」的三个真实原因（真机逐一实证后修复，别再往回踩）**：

0. **【根因，`8a67cef`】文字 View 被永久设成 `INVISIBLE`**。`attachWindow()` 给 `TextView` 设了
   `visibility = INVISIBLE`，而显隐逻辑只切换外层 `FrameLayout` 的可见性 —— 父 View 显示后，
   画在外层的深色底板照常出现、画在子 View 上的文字**永远不可见**，全工程没有任何地方把它设回 `VISIBLE`。
   **这个缺陷从功能诞生起就在：文字在任何设备上都没显示过。** 教训：显隐只在一层做（外层），
   子 View 别碰 visibility；自检要**分层报告**（外层窗口与文字 View 各自的可见性/尺寸）。
1. **位置被算到屏幕外**（对应 ⑧）。原实现在 `ACTION_DOWN` 里用「`屏幕宽 / 2`」把 `CENTER_HORIZONTAL` 坐标归一化成绝对坐标
   —— 它无视窗口自身宽度，等于把窗口整体右移半屏；而 `ACTION_UP` 又**无条件落盘**，
   于是"进调整模式随手点一下、什么都没拖"就能把窗口永久推到屏幕外。
   现口径：归一化取 `View.getLocationOnScreen` 的**真实左上角**；**只有真正拖动过才落盘**（`pendingPositionSave`）；
   夹取一律用「屏幕尺寸 − 窗口自身尺寸」；挂载后靠 `pendingMeasureClamp` 在**首次测量完成时补夹一次**
   （挂载瞬间 `width/height` 都是 0）。⚠️ 布局回调里**不能无条件调 `applyPosition()`**——它读的是 Prefs 里的旧坐标，
   会把拖动中的窗口一次次弹回去，表现为"拖不动"。
2. **对比度不足**（对应 ⑧ 全绿却还是看不见）。原实现锁定态是"纯白字 + 深色投影、无背景板"，
   而投影半径写死 `6f` 是**像素**，3x 屏上只有 2dp，亮色壁纸 / 桌面白色搜索框上白字直接糊掉。
   现口径：**锁定态也常驻一层半透明深色圆角底板**（`Color.argb(140,0,0,0)`，圆角 14dp），投影半径改用 dp 换算。
   底板不是装饰，是可读性保险——别再为了"干净"把它去掉。

**默认位置别贴着状态栏**：`TOP_OFFSET_DP = 64`（原 16）。荣耀/Honor 桌面顶部有一条**媒体胶囊**
（播放时才出现，而悬浮歌词恰恰只在播放时显示），16dp 会让文字整个叠在胶囊上看不清。

**已识别的 ROM 坑**：HyperOS / MIUI 对 `ACTION_MANAGE_OVERLAY_PERMISSION` 带 `package:` 数据支持不一致，
可能打开空列表（用户视角=点了没反应）——已做三级降级跳转，全断则写手动路径。
另：部分系统「显示悬浮窗」与「后台弹出界面」是两项，需分别放行。

**窗口跑到屏幕外时怎么救**：桌面上根本抓不到它，所以设置页自检里给了「重置位置并预览」
（`LyricOverlayBridge.resetPosition` → 清 `Prefs.lyricOverlayX/Y` 回 `Int.MIN_VALUE` +
`startForegroundService` 带 `ACTION_RESET_POSITION`，服务收到后就地重排）。

**内容决策在纯函数层，不在服务里**：`lyrics/OverlayContentResolver.resolve(lyrics, positionMs, offsetMs, title)`
→ `Resolved(current, translation, prev, next, idle)`；排版组装在 `overlay/OverlayTextComposer.compose(...)`
→ `List<StyledLine(text, role)>`。服务只把 `StyledLine` 转成 `SpannableStringBuilder` 画出来——
**「显示什么」可单测，「怎么画」可调样式**。要加新的内容规则就改 resolver，**别在服务里加 `if`**。
⚠️ `Resolved` / `StyledLine` 里**不许出现播放位置**：它们要被拿去比较去重（内容不变就不重组），
带上每 200ms 变一次的位置会把去重彻底废掉。

### 悬浮歌词的内容规则（三个曾让字幕「整体消失」的情形，别再踩）

| 情形 | 曾经的表现 | 现口径 |
|---|---|---|
| 前奏（还没到第一句，`indexAt` 返回 -1） | `null.orEmpty()` → 空串 → `visible = false` → **整窗隐藏** | 回落显示**歌名**，`next` 放第一句作预告，`idle = true` |
| 间奏（LRC 里 `[01:20.00]` 单独一行 = 空文本行） | 命中空行 → 整窗消失，下一句才回来（闪一下） | **回溯到最近的非空行**并压暗（`idle = true`）；`next` 从「时间轴当前位置之后」找第一个非空行（不是 anchor 之后） |
| 整首都是空行 / 连歌名都没有 | 空 | `visible = false`，窗口隐藏（这才是真的没东西可显示） |

⚠️ `LrcParser` **会**为带时间戳的空文本行生成 `LyricLine(text = "")`（合法 LRC 写法）。
**不要在解析层把它过滤掉**——间奏的长度本身是信息，`indexAt` 要靠它推进。

### 外观设置（五项，都不绑定车载模式）

设置 → 歌词，紧跟开关下方五项；共用 `OverlayChoice` 枚举驱动的选项弹层（标题 / 说明 / 选项都在枚举里，
避免每加一项就复制一份 `AlertDialog`）。**开关关着也允许预设**，所以不做可见性 gating；
改完统一走 `PlaybackEngine.previewOverlayAfterConfigChange()` = 通知重绘 + 立刻下发一次内容 + 6 秒前台预览。

| 设置项 | Prefs 键 | 取值 | 默认 |
|---|---|---|---|
| 悬浮歌词行数 | `lyricOverlayLines` | 1 / 3 | 1 |
| 悬浮歌词字号 | `lyricOverlayTextScale` | 0 小 / 1 中 / 2 大 | 1 |
| 悬浮歌词对齐 | `lyricOverlayAlign` | 0 居中 / 1 居左 | 0 |
| 悬浮歌词底板 | `lyricOverlayBackdrop` | 0 无 / 1 半透明 / 2 实底 | 1 |
| 暂停时的表现 | `lyricOverlayPauseMode` | 0 淡出 / 1 隐藏 | 0 |

存**档位**而不是裸值：日后调基准字号 / 基准透明度能带上老用户。

**「对齐」只管窗内文字排版**（2026-10-16 用户拍板，推翻此前「横向归对齐」）：它只改文字在框内的
`tv.gravity`（居中 → `CENTER`，居左 → `START|CENTER_VERTICAL`），**不再参与窗口摆放**——
`applyAppearance()` 里改 gravity 后**绝不能**顺手 `applyPosition()`，否则用户拖好的位置会被悄悄拉回去。

**窗口位置横纵两轴都由拖动决定**：
- `dragListener` 双轴增量累加（`lastRawX` / `lastRawY`），ACTION_UP 时 x、y 一起落盘；
  编辑态提示文案是「拖动到合适位置，松手即保存」。
- `lyric_overlay_x`（键 2026-10-15 曾废弃、次日恢复）+ `lyric_overlay_y`，`Int.MIN_VALUE` = 没拖过：
  横向走 `(屏宽 − 窗宽) / 2`，纵向走「状态栏下方 + 64dp」。
- ⚠️ 挂载时 `attachWindow` 里就按 Prefs 落定 `tv.gravity`：`observeState()` 在 `onCreate` 起来、
  而窗口是 `onStartCommand` 才挂的，首帧很可能读不到 `container` 而直接 return。

**摆放数学收在纯函数 `overlay/OverlayPositioner.kt`**（`place(Params) -> Placed`），
服务里的 `applyPosition()` 只负责取屏幕尺寸 / 读 Prefs / `updateViewLayout`：
- 「窗口跑到屏幕外」出过两次真机事故，而两次的表现都是「自检全绿但桌面上什么都没有」——
  极难排查。收成纯函数后用 `OverlayPositionerTest`（13 项）把边界钉死：
  夹取上限必须是「屏幕 − 窗口」、窗口未测量（=0）时不夹取、横向 / 纵向超界夹回、
  未拖过时横向落在 `(屏宽 − 窗宽)/2`。

**为什么服务每帧读 Prefs、而不是由界面推**：服务独立于界面存活，界面可能在改完设置后就被切走了。
读的是 SharedPreferences 内存缓存，开销可忽略；`LyricOverlayBus.appearance` 版本号只是「叫它醒一次」的触发点。

悬浮窗的「内容 / 排版 / 位置」三层分工（v1.1.8 定稿）：
- **内容**：`OverlayContentResolver.resolve()` → `Resolved(current / translation / prev / next / idle)`。
  它**只回答「这一刻该显示哪一行」**，不含任何播放位置数值。
- **排版**：`OverlayTextComposer.compose()` → `List<StyledLine(text, role)>`。主角与上下文只靠
  `Role`（`CURRENT` / `CONTEXT` / `TRANSLATION`）区分，渲染侧把主角画得最亮最粗、其余压暗。
- **位置**：`OverlayPositioner`（纯函数）决定窗口摆在屏幕哪里。

⚠️ **`positionMs` 绝不能进 `Resolved`**：它每 200ms 变一次，而 `LyricOverlayBus` 的去重比的是
`Resolved` 的**值相等** —— 参数一旦带上位置，「内容不变就不重组」立刻彻底失效。
`OverlayContentResolverTest.resolvedIsPositionIndependentWithinSameLine` 钉着这条。

⚠️ **曾经有一整套「逐字染色」，2026-10-16 已按「彻底删除」的产品口径整体移除。**
记在这里**不是**为了复述它的实现，而是为了「万一要恢复时别只恢复一半」——它由四段组成，
缺任何一段都会**静默失效**（不报错、不崩溃，只是什么都没发生）：
  ① **数据**：`.krc` / `.yrc` / 增强 LRC 三种真字级来源，外加给普通 LRC 派生字级时间轴的
     `WordTimingSynth.withDerivedWordTiming()`（v1.1.5 加的，因为本地歌词绝大多数只有行时间轴）；
  ② **数据模型**：`LyricLine.words` 与 `lineDurationMs`（后者是播放页染色归一化的分母，
     漏了它整行要么全灰要么瞬间全亮 —— v1.1.4 真机实测踩中）；
  ③ **渲染**：播放页 `LyricsPane.KaraokeText`（按「行时长归一化」染色）与悬浮窗
     `OverlayTextComposer.sungChars`（按「已开始的词数」计数）—— **两个消费者口径不同，
     改一处必须同时想另一处**，这是它维护成本高的根源；
  ④ **设置项**：`lyric_word_by_word` 开关 + 两处「已唱颜色」（`lyric_sung_color` /
     `lyric_overlay_sung_color`）+ 播放页的「逐字」来源徽标。
剩余部分（KRC / YRC / 增强 LRC 的**解析**）全部保留：行内字级标签被剥离，按行时间轴正常显示。

**字号与宽度**：基准 20sp × 档位倍率（0.82 / 1 / 1.25）。窗口宽度 v1.1.4 起按屏幕百分比固定
（**默认 80%**，见 §11.14 ④），文字不再需要 `maxWidth` 夹横向溢出。窗口的横向可移动量
= `屏宽 − 窗宽`，所以这个设置同时决定了「能左右拖多远」。

### 「调整位置」60 秒超时（唯一的反向事件通道）

调整态窗口是**吃触摸**的：用户拖完忘了点「完成调整」就退到桌面，屏幕上会留一条挡操作的横条。
服务只能判定「该收了」，状态却归播放域所有，所以发一条一次性事件：

```
LyricOverlayService.armEditingTimeout()（60s 无触摸）
  → LyricOverlayBus.requestExitEditing()      // MutableSharedFlow，extraBufferCapacity = 1
  → PlaybackEngine 在 init 里 collect → setOverlayEditing(false) + MessageBus 提示一句
```

这是**唯一的服务 → 播放域通道**，其余全部是播放域 → 服务单向。要加反向通道请沿用这个模式
（别让服务持有 ViewModel 引用）。

### 服务被系统杀掉后的自愈

`START_STICKY` 在 Android 12+ 的后台限制下并不可靠，而 `sync()` 原先只在「App 启动 / 开关切换 / 自检 / 预览」四处调用
—— 长途导航途中服务一旦被杀，用户侧就是「开关明明开着，字幕再也没出现过」，且没有任何自救路径。
现口径：`PlaybackEngine.reviveOverlayIfNeeded()` 在进度 ticker 里顺带检查（`!serviceAlive` 才重建），
**30 秒节流** —— ROM 若持续拒绝挂窗，无节流会退化成「每首歌重启一次服务」的死循环。

### 灭屏省电

`ACTION_SCREEN_OFF / ON` **只能在代码里注册**（清单注册收不到），跟着服务生命周期走。
灭屏时 `applyVisibility()` 直接置 INVISIBLE：窗口在灭屏时依旧会被系统合成，看不见还继续画是纯浪费。

### 播放页里的快捷入口

播放设置弹层（⑱ 屏）的「歌词」分组第一项就是「桌面悬浮歌词」，用 `OptionRow`（左侧图标 + 右侧选中 ✓）。
**列表里加行不属于「改 UI 布局」的范畴，但仍要遵守 `docs/ui/` 的行组件规范**
（`OptionRow` 不带箭头、`ActionRow` 才带 chevron）。授权页跳转函数 `Context.startOverlayPermissionPage()`
放在 `ui/Common.kt`，设置页与播放页共用一份。

### 11.10 页面 / 子页之间保留滚动位置

本应用有若干「同一块区域按条件切换内容」的页面：设置页的四层（主 / 音乐库 / 更新 / 关于与诊断）、
库页的视图与详情（歌曲 / 专辑 / 歌手 / 文件夹 / 歌单 / 分组详情 / 歌单详情）、车机页同样一套。
**这些页面必须各自保留滚动位置** —— 否则用户滚到列表中部、点进一个分组、返回，列表被顶回最上面，
只能重新滚一遍。用户 2026-10-15 明确提过这个诉求。

三种错法与正确做法：

| 写法 | 结果 |
|---|---|
| `when` 各分支里的 LazyColumn 不传 `state`，且外层没有托管 | ❌ 分支一切换，`rememberLazyListState()` 随组合消失 → 位置丢 |
| 所有页面共用**一个** `ScrollState`，切页时 `scrollTo(0)` | ❌ 「进子页从顶部开始」达成了，但主页面的位置被一起抹掉 → **返回时被顶回最上面**（就是本次修的 bug）。另外共用状态下进短子页会把 `maxValue` 压小，**`ScrollState.maxValue` 的 setter 会把 `value` 一起夹下来**，位置同样会丢 |
| **按页面各存一份**（下面是两种落地方式） | ✅ 进子页从它自己的位置开始、返回主页面回到原处 |

**方式 A —— 单滚动容器换 state（设置页）**：几个页面共用同一个 `verticalScroll` 时，**存数字而不是存
`ScrollState` 对象**：

```kotlin
val pagePositions = rememberSaveable(saver = PagePositionsSaver) { mutableStateListOf(0, 0, 0, 0) }
val pageIndex = page.ordinal
val pageScroll = remember(pageIndex) { ScrollState(pagePositions[pageIndex]) }
DisposableEffect(pageIndex, pageScroll) {
    onDispose { pagePositions[pageIndex] = pageScroll.value }   // 离开时快照
}
```

⚠ **别再用「按页面各建一个 `rememberScrollState()`，用 `when` 选一个挂上去」那版**：它依赖的假设是
「没挂上去的 state 不会被测量，`maxValue` / `value` 都不会被人动，天然保留原值」。
2026-10-15 用户**第二次**报同一个问题（进子页返回主页又被顶回最上面）后确认这条**不成立**。
教训：只要正确性建立在「某个对象在整段导航期间一直活着、而且一直没人动它」上，
就会在某个看不见的时刻被推翻，**还不报任何错**。存 `Int` 快照与 `ScrollState` 的生死完全无关，旋转屏幕也不丢。

**方式 B —— `SaveableStateHolder` 托管（库页 / 车机页）**：分支多（每种视图 + 详情页），逐个把 state 提到
父级会撑爆参数表。此时用：

```kotlin
Box(Modifier.weight(1f)) {
    // ⚠️ 必须声明在 when 之外：进详情时下面的分支会离开组合，
    //    holder 若随之被 forget，存下来的位置会一起丢掉。
    val listStateHolder = rememberSaveableStateHolder()
    when {
        library.selectedGroup != null -> GroupDetail(...)   // 详情页放在外面：每次进都从顶部开始
        else -> listStateHolder.SaveableStateProvider(library.view.name) {   // 以视图名做 key
            when (library.view) { LibraryView.SONGS -> SongsList(...); ... }
        }
    }
}
```

`SaveableStateHolder` 会在 key 离开组合时**存下**该子树里所有 `rememberSaveable` 的值（`rememberLazyListState()`
本身就是 saveable 的，所以子组件**一行都不用改**），key 再出现时恢复。每个视图用不同 key，因此
「专辑」「歌手」「文件夹」三套列表各记各的偏移，互不串位。

**两条口径**：
1. **详情页（分组 / 歌单）刻意不托管** —— 每次进入都从顶部开始，才符合「打开一个新的分组」的预期；
   否则会把上一个分组的偏移带到下一个分组上。
2. 新增任何「按条件切换内容」的页面时，按上表检查一遍；这是**容易漏、且用户一定会感知到**的一类缺陷。

---

### 11.11 死代码审计（怎么查、别误杀）

**为什么必须定期做**：本项目经历过三次大结构变动——分域 ViewModel（旧的 2623 行 `MusicViewModel` 被拆成四个）、
Prefs 瘦身（删 10 组键）、功能整体移除（通知栏歌词、WebDAV 云盘）。每次都会留下一批「声明还在、引用没了」的符号。
它们**不影响编译**，所以会一直躺着，直到有人以为"这功能还在"。

**扫描口径**：`引用数 = 全项目全词出现次数 − 声明次数`，`<= 0` 即候选。
private 成员按**文件内**统计（作用域就是文件），internal / public 按**整包**统计。

| 手段 | 抓什么 | 坑 |
|---|---|---|
| 全词正则统计 | 零引用的 `fun` / `val` / `class` / `object` | **必须用 `\b` 全词匹配**，否则 `Song` 会命中 `SongIndex`、`seekBy` 会命中 `seekRelative` |
| import 与正文比对 | 未使用的 import | `getValue` / `setValue` / `invoke` 等**委托与操作符约定**在源码里不出现字面名，必须白名单排除 |
| 编译警告 | Kotlin 自带的 unused 提示 | 增量编译会吞掉绝大部分警告，别指望它 |

**四类必然误报，看到别删**：
1. **框架回调** —— `override fun onUpdate / onBind / onGetSession / onPlaybackStateChanged / onTaskRemoved`：系统或 Media3 调；
2. **父类调用** —— `override fun sizeOf(...)`（`LruCache` 算容量时回调）；
3. **清单注册** —— `PlaybackTileService`、`MusicWidgetProvider` 在 `AndroidManifest.xml` 里注册，代码里搜不到引用；
4. **属性委托** —— 见上表第二行。

**本项目的判定标准**：
- **删**：重构残留 + 从未被引用的私有 / 工具成员。
  例：`MusicRepository.search()`（已被 `LibraryViewModel.searchSongs()` 取代，因为后者要叠拼音）、
  `BinaryReader.buf4 / buf8`（读 4/8 字节实际走 `readBytes(n)`）、`LyricsMatcher.isWordLyricFile / isLrcFile / baseNameOf`、
  `SongIndex(db)` 的 `db` 构造参数（标了 `@Suppress("unused")`，是自觉的妥协，不是需求）、
  `LibraryUiState.hasAudioPermission / scannedOnce`（**写了从没读**——界面用自己的 `remember` 状态判断权限，VM 里那份是重复的）、
  `Sheets.PlaylistHeader`（UI 重构后再无调用者）、`PlayerActions.seekBy / seekRelative / setLoudness`
  （歌词面板的快退/快进按钮最终没做；响度 UI 直接调 `AudioFx`，绕过了引擎转发）。
- **报告但保留**：**对外能力面**的成员——DAO 方法、`Prefs` 属性、引擎动作。
  它们是"接口表面"，删掉等于缩小 API，属产品或架构决策，不擅自处理。

**2026-10-15 一轮结果**：删除 **12 处死符号 + 48 条未使用 import**（21 个文件 / −158 行），
`testDebugUnitTest` **105 项仍全绿**、`assembleDebug` 通过。
⚠️ 其中 30 条未使用 import 集中在 `LibraryViewModel.kt` —— 那是**分域 ViewModel 拆分时的残留**
（`Update*` / `DataMaintenance` / `Playback*` 的 import 没跟着搬走）。**这类残留只会随重构积累，行尾也顺带被
`.gitattributes` 规范成 LF（仓库本就要求 LF，diff 干净）。**

**保留未动、需产品决策的三项**（见 §12）：
`UpdateChecker.setVerbose` / `DataMaintenance.resetLibrary()` / `Prefs.onChanged()`。

### 11.12 给列表加「多选批量操作」

曲库列表（以及任何 `SongRow` 列表）的多选态由 `LibraryViewModel` 持有，**不要**在 Composable 里
自己 `remember` 一份选中集合——批量动作要跨越"列表页 / 分组页 / 播放页"三处入口，状态一旦分层就会出现
"进了详情回来选中丢了""底部操作栏和列表勾选不一致"。

落地要点：

- 视图模型侧：`enterSelection(id)` / `toggleSelected(id)` / `selectAllVisible()` / `invertSelection()` /
  `exitSelection()` / `selectedSongs()`。**选中集合存 id 而不是 `Song` 对象**——曲库重扫后对象会换新的，
  存对象会出现"选中的曲目已经不是库里那一行了"。
- ⚠ **入口只有一处，改列表行时别把它碰掉**：`selecting` 只在 `enterSelection()`（长按歌曲行）
  与 `selectAllVisible()` / `invertSelection()` 里被置 `true`，而后两者又**只在多选态下可见**。
  也就是说：**长按那一处一旦没人调，整个批量操作就没有任何入口，功能静默隐身**
  （2026-10-15 真的这样交付过一次，靠死代码扫描才发现 `enterSelection` 零引用）。
  同理，删任何"看起来没人用"的 selection 相关方法前，先想清楚它是不是唯一入口。
- 行侧：`SongRow(selected = ...)` 传入 `Boolean?` 而不是 `Boolean`：`null` = 不做多选视觉，
  非空 = 显示 `Checkbox` 并按状态打勾。
- ⚠ **长按与拖拽排序同一个手势位**：`combinedClickable` 即使在 `onLongClick = null` 时**仍然消费长按**，
  会让外层拖拽排序收不到事件。所以「不参与多选」的列表必须显式传 `onLongClick = null` 并由
  `SongRow` 原样透传给 `combinedClickable`，别用 `{}` 占位。
- 删除走的是一次**系统多 URI 确认框**（`SongDeleter.prepareSystemConfirmMany` → `MediaStore.createDeleteRequest`），
  不要退化成逐首弹框；确认返回后只处理真正删成功的那批，失败的原样留在曲库并**无论成败都退出多选态**
  （否则用户会对着一个已经没有意义的选中集发呆）。

---

### 11.13 给 Compose 界面加「点一下看看」的冒烟测试

本机**默认没有连接真机**（`adb devices` 为空），所以「界面渲染得出来 / 点得动」
这件事以前只能靠人眼看。现在可以进单测：`androidx.compose.ui:ui-test-junit4` + Robolectric，
`createComposeRule()` 就能在 JVM 里跑 Compose，不需要设备。参考 `ui/LibraryDrawerTest.kt`。
（需要真机尺度的端到端时，用自建 AVD —— 配方见 11.13.1。）

依赖两个（版本都由 Compose BOM 决定，catalog 里**不写 version**）：

- `testImplementation(libs.androidx.compose.ui.test.junit4)` —— 提供 `createComposeRule()`；
- `debugImplementation(libs.androidx.compose.ui.test.manifest)` —— 提供 `ComponentActivity` 空壳
  （只进 debug 变体，不进正式包）。

⚠️ **三个必踩的坑**（照抄类上的注解即可，别自己精简）：

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")   // ① 必须有窗口尺寸
@GraphicsMode(GraphicsMode.Mode.NATIVE)                   // ② 必须开 NATIVE 图形后端
class XxxTest { @get:Rule val compose = createComposeRule() ... }
```

1. **不给 `qualifiers`**：节点在语义树里存在，但 **size 为 0** → `assertIsDisplayed()` 报
   "The component is not displayed!"，点击也无效。
2. **不开 `@GraphicsMode(NATIVE)`**：默认 LEGACY 图形后端下，**祖先里只要有 `Modifier.shadow`
   产生的图层，整棵子树就收不到触摸** —— 事件穿透到下层节点，`performClick()` 静默打到别人身上，
   既不报错也不生效。2026-10-15 定位过程：抽屉面板点不动，手工调用节点的
   `SemanticsActions.OnClick` 却是**完全正确**的（说明接线没问题）→ 逐层加料做隔离（普通 Box ✔ /
   套 `AnimatedVisibility` ✔ / 换成无阴影 `Surface` ✔ / **加回 `shadowElevation = 10.dp` ✘**）
   → 切 NATIVE 后全部 ✔。**这是图形后端的命中测试算错，不是产品代码问题**：
   `Surface(shadowElevation = …)` 是 Material3 对话框 / 底部弹层 / ElevatedCard 的通用写法。
   先怀疑环境，别急着改产品代码去"绕开阴影"。
3. **一个测试只能调一次 `setContent`**（第二次抛 `Cannot call setContent twice per test!`）。
   要换状态用 `mutableStateOf` 驱动重组，别重新渲染一遍。

**值得测什么**：编译期看不出来的东西 —— 入口组件能不能渲染、点击有没有接到正确的回调。
典型价值场景就是 v1.1.3 的抽屉：底部导航被删掉之后它成了视图切换的**唯一入口**，
它要是不工作，五个浏览视图全都进不去，而这类故障编译、打包、lint 全都不会报。
测试里别断言像素或文案细节（那些看效果图），断言**行为**。

---

### 11.13.1 宽屏 / 车机：按 ≥840dp 验一遍（附复现配方与车机 Dock 坑）

2026-10-17 的车机事故：比亚迪宋 L DM-i 装上打开**全黑**，手机上一切正常。

根因是双栏里的一条分隔线：`VerticalDivider(modifier = Modifier.fillMaxSize().width(1.dp))`。
`fillMaxSize()` = `fillMaxWidth() + fillMaxHeight()`，它在 `Row` 里把**整行宽度**吃满；
而 `Row` 只把「剩余宽度」分给 `weight(...)` 的子节点 → 左侧列表、右侧播放页**双双击 0 宽**，
整屏只剩主题背景色（深色模式下就是纯黑）。看着像渲染坏了，其实是布局把宽度吃光了。

为什么一直没发现：**双栏只在 `widthDp ≥ 840` 时才走**（`MainScreen` 的 `EXPANDED_WIDTH_DP`），
手机竖屏约 411dp，永远测不到；车机 1920×1080 / 240dpi 正好 1280dp，一进去就命中。

**模拟器复现配方**（`wt_phone` 这类普通 AVD 就够，不必用车机镜像）：

```bash
adb shell wm size 1920x1080
adb shell wm density 240            # 240dpi → 1280dp 宽，正好跨过 840
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0        # 0 才是横向；1 会把屏幕转成竖的
adb shell cmd uimode night yes      # 车机常年深色，黑屏才「黑得彻底」
# 之后每次改完重装、重启应用即可；收工还原：
adb shell wm size reset && adb shell wm density reset && adb shell cmd uimode night no
```

判据：**单栏**（顶栏 + 列表 + 迷你播放条）。若只有一片背景色，先怀疑有兄弟节点把宽度吃满。

**已上锁**：`LayoutContractTest` 扫全部 `ui/**`，禁止 `fillMaxSize()` 后接 `.width(...)` /
`.height(...)`。做过反向验证：改回旧写法，用例立刻变红。

#### v1.1.14：双栏整体下线，以及车机底部「点不到」

黑屏修好之后，同一位车机用户（比亚迪宋 L DM-i 2026 款）第二天又报了两条：

1. **左下角「设置」点不到**——被车机固定的按钮栏（Home / 应用 / 空调那条）压住了；
2. **不要双栏模式**。

第 1 条是平台差异：**车机 Dock 不是系统导航栏**，`WindowInsets.navigationBars` 实测为 0，
`navigationBarsPadding()` 拿到 0dp，于是一路铺到屏幕最底，正好被浮层盖住。
修法是给根布局留死高度：

```kotlin
val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
val bottomSafe = if (widthDp >= LARGE_SCREEN_WIDTH_DP) {
    maxOf(navigationBottom, CAR_DOCK_RESERVED_HEIGHT)   // 56.dp
} else navigationBottom
Box(Modifier.fillMaxSize().padding(bottom = bottomSafe))
```

`Surface` 背景仍铺满全屏，所以 Dock 后面不会露出一条异色；手机三键导航盖住迷你播放条的老问题也一起修了。

第 2 条是产品决定：车机两栏（`CarHomeScreen`）与平板双栏一起下线，统一单栏 + 抽屉导航。
`CarHomeScreen.kt` 文件保留但**不再被调用**（注释里写明了），后续要恢复必须先看
`docs/版本历史.md` 的 v1.1.13 / v1.1.14 两条 —— `LayoutContractTest.noTwoPaneLayout`
会禁止 `MainScreen` 里再出现 `VerticalDivider(` 或 `CarHomeScreen(`。

**教训**：横屏大屏的布局问题，手机上永远测不到；而「insets 为 0」这种平台差异，
只能靠真机照片里的位置关系反推（内容压到按钮下面 = insets 没给），别指望 `dumpsys` 会告诉你。

---

### 11.14 v1.1.4 的三处新约定（恢复默认 / 排序下沉 / 歌词颜色）

#### ① 每个可调项都要能「恢复默认」（↺）

- **形态**：行尾一枚 18dp 的 `AppIcons.Reset`（`Common.kt` 的 `ResetButton`，命中区 28dp）。
  `SwitchRow` / `SliderRow` / `ActionRow` 都有 `onReset: (() -> Unit)?` 参数，传 `null` 就不画。
- **只在偏离默认时出现**。判据包装成 `resetAction(needsReset) { … }`（`SettingsScreen.kt` 私有），
  把「判据」与「写回什么」写在同一个表达式里 —— 两处相隔二十行、改一处忘一处，
  正是"重置按钮把值写歪了"的典型来路。
- **写回的值只能是 `SettingsDefaults` 的常量**，不许就地写字面量。`SettingsDefaultsTest`
  会拿 `Prefs` 各 getter 与它逐项对质：这两处漂移的症状是「新装用户」与「恢复过默认的用户」
  行为不一致，**不报错、不崩溃、日志里什么都没有**。
- **↺ 嵌在整行 clickable 里是安全的**：Compose 的点击手势由最内层消费，点 ↺ 不会顺带打开
  这一行该进的界面。`SettingsScreenTest` 有一条专门守着（点字号的 ↺，断言色板弹层没被打开）。
- **值藏在弹层里的设置项，↺ 跟着弹层走**：均衡器那一行只是入口，它的 ↺ 在 `EqSheet` 标题行
  （`AudioFx.reset()`：关掉均衡器 + 频段全平 + 低音/环绕/响度归零 + 混响回到关闭，见 §6.10）。频段**不要**用 `usePreset(0)`
  —— 平台预设的名字与曲线各 ROM 不同，第 0 个未必是平直，有的直接是「Rock」。
- **不复位的两处**（有意）：均衡器那一行本身（见上）、悬浮歌词的位置（属「调整位置」路径，
  自检里有「重置位置并预览」）。

#### ② 排序按视图各存一份（`Prefs.sortOrder`）

- 读：`prefs.sortOrder(viewName, view.defaultSort)`；写：`setSortOrder`；复位：`resetSortOrder`。
  **三级回落**：`sort_<视图名>` → 旧全局键 `sort` → 调用方给的默认值。
- **各视图只列适用的排序项**（`LibraryView.sortOptions`），文案随视图变（`SortOrder.labelFor(view)`）：
  同一个 `TITLE` 在文件夹视图里叫「按文件夹名」。车机的排序键只在本视图的 `sortOptions` 内轮换。
- ⚠️ **`SortOrder.PLAYLIST_ORDER` 必须在 `MusicRepository.sort()` 开头提前 `return songs`**：
  它过不了 `sortedWith`，一旦落进去，用户自己拖出来的 M3U 顺序会被重排掉。
- 切视图（`LibraryCore.setView`）要**重排歌曲列表**并把新排序带进去 —— 每个视图带着自己那份进来。
- `SortOrderPrefsTest` 钉住三级回落、视图隔离与「每个视图的菜单里必须有它自己的默认项」。

#### ③ 歌词颜色（v1.1.8 起只剩「悬浮窗文字颜色」一处）

- v1.1.4 曾让**三处**歌词颜色共用一个 `ColorPickerSheet`（效果图 ㉙E）：播放页逐字已唱色 /
  悬浮窗正文色 / 悬浮窗已唱色。逐字染色移除后（v1.1.8）只剩 `lyric_overlay_text_color`，
  弹层也从「一个 `ColorSlot` 枚举决定写回哪个字段」收敛成「调用点直接传标题 / 说明 / 当前值」。
- ⚠️ **别再给悬浮窗加「已唱色」**：没有逐字进度可染，而整窗两种颜色会让「谁是主角」失去信号 ——
  主角与上下句现在**只靠明暗 / 字重 / 字号**区分。
- **预设色板不能做成枚举**：它承载的是用户拖滑块拖出来的任意 ARGB，枚举表达不了。
  同理，色板也不再需要色名（效果图 ㉙E 里的色点只有颜色没有文字），`presets` 就是一组色值。
- ⚠️ **默认值必须真的在色板里**：`SettingsDefaults.OVERLAY_TEXT_COLOR`（纯白）若不在
  `LyricsColors.presets` 中，用户点开弹层找不到默认色，「恢复默认」在界面上就成了不可达的一步。
  `LyricsColorsTest` 守着这条。
- **为什么不用色轮**：车机手指粗、精度差，色轮边缘的色相极难选准；三个滑块 + 一块实时预览条更好操作。
- 自定义滑块**松手才写回**（`onValueChangeFinished`）：逐帧写回会连着触发悬浮窗预览，
  把浮动窗口刷成流水；预览条本身逐帧在变，反馈并不缺。

#### ④ 悬浮窗宽度（屏宽百分比）

- `Prefs.lyricOverlayWidthPercent`（1..100，**默认 80**），服务侧换算成像素后设 `LayoutParams.width`，
  并**重摆一次位置** —— 夹取上限是「屏宽 − 窗口宽」，窗口变宽后能移动的余量会变小。
- ⚠️ **默认取 80% 而非 100%，这是「横向拖不动」的唯一解**：把上面那句话推到极端 —— 宽度 100% 时
  窗口宽 = 屏宽，夹取上限 = 0，`OverlayPositioner` 返回的 `x` 恒为 0。**拖动的横向接线哪怕完全正确，
  悬浮窗也一像素都动不了**，用户看到的就是「只能上下移动」（2026-10-16 用户把它当 bug 报回来，
  排查了半天接线才发现是几何问题）。所以宽度既是「换行上限」也是「横向活动余量」，
  默认必须留出余量；`OverlayPositionerTest` 里专门钉了一条「窗口宽度等于屏幕宽度时横向锁死在 0」。
  宽度行副标题在 100% 时会直接写明「已占满屏宽，无法左右移动」，免得用户再当成 bug。
- **留 48dp 像素下限**（标准最小触控边长）：1% 在 1080p 屏上只有 10px，窗口会小到既看不见也拖不着。
  ⚠️ 下限**不能往上抬**：80dp 在 1080p / 3x 的机器上已是屏宽 22%，滑块 1%~22% 会全变成同一个宽度，
  低端整段失灵。48dp 把死区压到 13% 以内。
- 滑块**逐帧写回**（与颜色滑块相反）：宽度与屏宽是直接的比例关系，松手才变会让人以为「拖了半天没反应」。
- 原先是 `WRAP_CONTENT`（长句顶边、短句窄条、宽度来回跳），改成按屏宽百分比后这个毛病才消失。

### 11.15 按应用隐藏悬浮窗 + 前台应用记录（v1.1.29，免 adb 检测前台）

需求来源：车机上打开 360 影像时悬浮歌词挡画面；且用户**没有 adb**，需要应用自己感知「谁在前台」。
设计全文见 `docs/设计-悬浮窗按应用隐藏.md`。四件套都在 `overlay/`：

```
ForegroundAppService.kt   无障碍服务：TYPE_WINDOW_STATE_CHANGED → ForegroundAppState.report(pkg)
                          （+ 开关开着时 ForegroundAppLog.append）。manifest 里 BIND_ACCESSIBILITY_SERVICE，
                          配置 res/xml/foreground_app_service.xml：只听窗口切换事件、不取窗口内容
ForegroundAppState.kt     进程内共享状态（StateFlow<String?>）+ 两个纯函数：
                          overlayHiddenForForeground(pkg, hiddenApps) / foregroundDetectionNeeded(hiddenApps, logEnabled)
ForegroundAppDetector.kt  使用情况权限通道（UsageStatsManager 轮询 1.5s，仅作 fallback）+ 通道状态 / 设置跳转
ForegroundAppLog.kt       历史落盘：files/foreground_app_log.tsv，滚动 300 条，SimpleDateFormat（java.time 禁用）
```

**判定与门控（都是纯函数，别在服务里加 if）**：
- 显示判定收在 `OverlayVisibility.show(...)` 的新参数 `foregroundHidden`——**名单命中是硬隐藏**，
  优先级仅次于灭屏 / 无内容，压过编辑态与预览。
- **fail-open 口径**：两个检测通道都不可用时按「不隐藏」放行——悬浮窗绝不会因为「检测不到」而消失。
  「显示什么/藏不藏」永远可单测，「怎么检测」才碰系统。
- **零成本门控**：`foregroundDetectionNeeded(hiddenApps, logEnabled)` 为 false（名单空且记录关）时
  服务不启动任何检测循环；`LyricOverlayService.syncForegroundTracking(...)` 负责拉起 / 取消。

**为什么主通道是无障碍而不是 UsageStats**：无障碍是**事件驱动**、听风在后台也收得到；
UsageStats 要「使用情况访问」授权且只能轮询。两者任一可用即可工作，状态显示在
设置 → 关于与诊断 → **调试** → 「前台应用记录」（该分区默认隐藏，关于卡连点 5 次解锁，v1.1.31）。

**历史记录三条红线**：
1. **默认关**（`SettingsDefaults.FOREGROUND_LOG_ENABLED = false`），开关控制写入，`SettingsDefaultsTest` 对质；
2. 只存本机文件（不是 DB——**故意不加 schema**，300 条滚动截断，坏了只丢日志不伤曲库）；
3. 副标题 / 诊断页文案要把「记录关了但名单仍生效」「（应用名解析不到）」这类**中间态说清楚**（真机验证过 launcher 包名取不到 label 的降级显示）。

**⚠️ 一次真实回归（2026-10-19）**：名单勾了不生效。根因是 `ForegroundAppState.currentPkg` 只被 `.value` **读一次**、
没进 `combine` —— 检测端写包名不产生任何既有事件，切应用后没人重判（暂停 / 歌词不长时永不重判）。
修法 = 作为**第六路**并入收集循环；`LayoutContractTest.overlayServiceSubscribesToForegroundPackage` 在源码层钉死。
**通用教训：写端与读端之间必须有一条订阅关系**，否则纯函数测试全绿也没用。

**UI 两处**：① 设置 → 歌词 底部「不显示悬浮窗的应用」（`SettingsLyricsOverlayAppsDialog.kt`：
launcher 应用列表 + 搜索 + 图标 + 手动贴包名，`Drawable→Bitmap` 转换要在 `remember` 外先取 `LocalContext`）；
② **关于与诊断 → 调试**里的「前台应用记录」（v1.1.31 从歌词页搬来，且整个「调试」分区**默认隐藏**、
关于卡连点 5 次解锁；与悬浮歌词自检 / 崩溃日志 / 开机自启
同属 `SectionTitle("调试")` 下，次级标题用 `DebugLabel`）：开关 + 通道状态 + 历史列表，**每行自带「加入名单」**，
已在名单的显示「已在名单」并禁用。查包名 → 加名单的动线因此全在一屏内完成。
测试：`OverlayVisibilityTest`（4 条新增）/ `ForegroundAppStateTest`（上报去重 + 门控 + 日志纯函数）/ `LayoutContractTest`（manifest 声明）/ `SettingsScreenTest`。

### 11.16 新增一条文案 / 加一种语言（i18n 流程，2026-10-24 起）

**新增一条文案（最常见）**：

1. `res/values/strings.xml` 加**英文**值 —— 这一条是兜底，**不能省**（英文设备缺它会 `NotFoundException`）。
2. `res/values-zh/strings.xml` 加**中文**值，**key 必须完全一致**。
3. UI 里用 `stringResource(R.string.xxx)`；带参数就写 `%1$s` / `%1$d`（不要在 Kotlin 里拼字符串，语序因语言而异）。
   计数类用 `<plurals>` + `pluralStringResource`（英语要分单复数，中文只写 `other`）。
4. 枚举标签走 `ui/UiText.kt` 的 `labelRes()` 映射，**不要**在枚举里写 `String` 常量（枚举拿不到 `Context`）。

**加一种语言（如日语）**：

1. 复制 `res/values-zh/strings.xml` → `res/values-ja/strings.xml`，逐条翻译。**Kotlin 一行不用改**。
2. 若该语言需要区分复数形态（英/法等），`<plurals>` 的 `quantity` 要按 CLDR 补齐（`one` / `other` …），
   中文、日文只有 `other`。
3. `app/build.gradle.kts` 的 `resourceConfigurations` 里补语言码（当前为 `zh` / `en`）。
4. ⚠️ **别只翻译已改造模块**：未改造模块仍是硬编码中文，会表现为"一半日语一半中文"。要么先把该模块抽干净再上语言，
   要么在加语言前把目标模块全部改造完。

**验证**：

```bash
./gradlew :app:testDebugUnitTest --offline          # 资源 id 断言与 UI 冒烟
./gradlew :app:lintDebug --offline                  # 查 MissingTranslation / ExtraTranslation 类问题
```

> 断言中文的 Compose 测试要给 `@Config` 加语言限定（如 `qualifiers = "zh-w360dp-h640dp-xhdpi"`），
> 因为 Robolectric 默认 en-US，资源化后界面上是英文，原中文断言会失配。

---

### 11.17 给 Compose 挂手势：装饰器里不能再套 Modifier（2026-10-28，v1.2.10 的教训）

**症状**：手势类功能上线后**完全没反应**，而编译、全量单测、lint、打包**全部通过**。

**根因**：`Modifier` 扩展函数里误写了两层 `pointerInput`。外层是 `Modifier` 的扩展函数，`this` 始终是那个 Modifier，
于是里层 `pointerInput(Unit) { … }` 被解析成 `this.pointerInput(…)` —— 只是**又构造了一个 Modifier 当表达式丢弃**，
没有任何节点承载它，手势检测器一次都没被执行过：

```kotlin
// ✗ 里层构造即丢弃：手势永远不会装上
internal fun Modifier.bad(): Modifier = this.pointerInput(Unit) {
    pointerInput(Unit) { detectHorizontalDragGestures(onHorizontalDrag = { _, _ -> }) }
}

// ✓ 只留一层，手势检测直接写在 block 里
internal fun Modifier.good(): Modifier = pointerInput(Unit) {
    detectHorizontalDragGestures(onHorizontalDrag = { _, _ -> })
}
```

编译器不报错的原因：`pointerInput` 的 block 签名是 `suspend PointerInputScope.() -> Unit`，返回 `Unit`，
"构造了一个没用上的 Modifier" 属于合法表达式，不会触发未使用返回值告警。

**判据（写任何手势装饰器前先自查）**：`Modifier.xxx(): Modifier = pointerInput(...) { … }` 的 block 里
**只放手势检测调用**（`detectTapGestures` / `detectDragGestures` / `awaitEachGesture` 等）；
出现 `pointerInput` / `graphicsLayer` / `clickable` 这类**链式 Modifier 调用**就是把装饰器写错了 —— 它们构造即丢弃。

**为什么纯函数单测抓不到**：`PlayerSwipe.resolve()` / `resisted()` 的 8 例全绿，因为它们测的是**判据**；
"手势有没有装上"是完全独立的另一层。要抓只能**真滑一次**：见 `PlayerSwipeGestureTest`
（Robolectric + `performTouchInput { swipeLeft() }`，父级挂手势、子级是 `verticalScroll` 的歌词列，
断言回调收到了方向）。**新增任何手势，必须配一条这种"滑一次"的测试**，否则这类故障只能等用户在真机上发现。

> 顺带确认的一条事实：父级 `detectHorizontalDragGestures` + 子级 `verticalScroll` 可以共存 ——
> 纵向滚动容器只消费竖直方向，横向手势会正常上浮给父级（`PlayerSwipeGestureTest` 钉住了这一点）。

**反向推论（2026-10-28 补）**：子级要保留自己的**纵向**手势时，只能用 `detectVerticalDragGestures`。
`detectDragGestures` 是在 `onDrag` 里自己 `consume()` 的，横向会被一起吃掉 —— 手机封面态就是这样
长期收不到父级的横滑，于是它自己另立了一套固定 90px 阈值、且没有跟手位移（同一动作在歌词态和封面态
手感不一样）。**判据要唯一：子级只处理自己那一轴，另一轴原样上浮给父级。**
接线用 `CoverCarSwipeTest` 钉住（封面 / 车机唱片区横滑要能上浮到父级，纵滑只能调音量）。

---

### 11.18 封面内存：解码尺寸必须等于显示尺寸（2026-10-29，v1.2.12）

**症状**：长列表滚过一两百首后开始反复重解码、掉帧；低内存设备（车机）切后台容易被杀；
播放页封面切换时偶发明显卡顿。三项都不是「功能坏了」，所以永远不会出现在功能测试里。

**三个根因，都在封面这一条链上**：

1. **解码尺寸乘了 2**。`Common.kt` 原来是 `with(density) { decodeDp.roundToPx() } * 2`。
   `roundToPx()` 返回的**已经是物理像素**，再乘 2 就是按 4 倍面积解码：
   1080p 手机上播放页封面（292dp ≈ 803px）解成 1606px（5.2MB），列表缩略图（52dp ≈ 143px）解成 286px。
   后果不只是费内存 —— 32MB 位图预算原本只能放约 196 张缩略图，滚过 200 首就开始淘汰重解。
2. **`inSampleSize` 单独用会漏降**。它只能取 2 的幂，且旧实现要求「宽和高都 ≥ 2×目标」才降一档。
   目标值一旦大于原图（乘 2 之后很容易），就完全不降采样：3000px 的内嵌封面**按全尺寸解出 18MB**。
3. **原始字节缓存固定 16MB**，内容与位图缓存重复，在车机 192MB 堆上占 8%；
   而它只是通知栏封面与主题取色的中转，两者都能从磁盘缓存重解。

**改法（三条缺一不可）**：

- 解码尺寸 = 显示像素，**只有 ≥ 512px 的大图**留 1.25 倍余量（`CoverRepository.targetPxFor`）；
- 两段式缩放：`inSampleSize` 按**长边**粗降 → `fitSize` 算出精确目标 → `createScaledBitmap` 收尾，
  旧图 `recycle()`（用 `!==` 兜住「尺寸已相同、返回自身」）；
- 两级内存缓存都按堆比例取上限（位图 `maxMemory/8`、原始 `maxMemory/16`），
  并由 `MusicApp.onTrimMemory` 分档释放（`CoverRepository.trim`）。

**判据（改任何图片加载先看这三条）**：
① 目标尺寸的单位必须是**物理像素**，不是 dp、更不是「dp × 倍率」；
② 只用 `inSampleSize` 的缩放**必然**存在「目标大于原图 → 全尺寸」的漏洞，必须配精确收尾；
③ 常驻缓存的上限必须与 `Runtime.maxMemory()` 挂钩，**不能写死 MB 数** —— 手机与车机的堆差 2~3 倍。

**为什么这组测试写成纯函数**：`CoverCacheTest` 测 `targetPxFor` / `sampleSizeFor` / `fitSize`
三个纯函数，不需要 Context、不需要真实图片。其中一条专门断言
`sampleSizeFor(3000, 3000, 1606) == 1` —— 把「旧倍率下会全尺寸解码」这个事实钉在测试里，
任何人把倍率调回去都会红。

**上线后怎么量化**：诊断包新增 `[内存]` 段（`diag/MemoryProbe.kt`），给 Java 堆 used/max、native 堆、
系统可用与低内存标志、封面两级缓存的占用与命中率、磁盘缓存大小。车机不让 adb，这一段是唯一能拿到的数：
**命中率低 → 预算给小了或键设计有问题；位图占用贴着上限 → 列表尺寸档位太多或 targetPx 又算大了。**

---

## 12. 已知限制与技术债

| 项 | 现状 | 影响 / 建议 |
|---|---|---|
| `MainScreen.kt` 1026 行 | ✅ 拆过两轮：2026-10-15 按 §8.6 把列表区抽成 `LibraryHeader.kt`（424 行）+ `LibraryLists.kt`（742 行）；2026-10-21 把 `PlayerPane` / `SelectionBar` / `SaveQueueAsPlaylistDialog` 等自包含子组件抽成 `MainScreenParts.kt`（228 行）。`MainScreen` 只留编排（单栏骨架 / 抽屉 / 播放页挂载 / 深链消费）——**主体不再拆**，理由见 `docs/数据层重构规划.md` §13.1 | 同轮 `PlayerScreen.kt`（1046 → 791）拆出 `PlayerScreenParts.kt`（319 行）、`Sheets.kt`（1015 行）按域拆成四个 `Sheets*.kt`。下一个偏大的文件是 `overlay/LyricOverlayService.kt`（1266 行，悬浮窗绘制 + 夹取 + 按应用隐藏混在一块，见 §11.15） |
| 测试覆盖 | **579 个**（`db/`、`playback/` 有 Robolectric 用例，`ui/` 是 Compose 界面冒烟） | 已覆盖 CASCADE / 软删 / 排除 / 统计 / 队列 / 搜索历史 / 歌词解析 / 悬浮歌词内容解析与排版（32 项）/ 悬浮窗摆放与夹取（14 项）/ 悬浮窗按应用隐藏与前台记录（v1.1.29，见 §11.15）/ 更新检查（24 项，含说明正文的通道无关性，见 §6.2）/ 收听报告日历聚合（15 项）/ 重复歌曲指纹（13 项）/ 侧边抽屉行为（6 项）/ 设置页一主六子导航（3 项）/ 车机浏览树（12 项，见 §6.9）/ 均衡器预设曲线与预览归一化（14 项）+ 混响档位表（7 项，见 §6.10）/ 启动器快捷方式契约（4 项）+ 桌面入口就绪判据（6 项）+ 虚拟歌单条目（4 项，均见 §6.7）/ 设置双写迁移（8 + 7 项）/ 搜索纯函数（17 项）/ 多选态纯函数（22 项）/ 在线播放列表（17 + 17 + 22 项，见 `docs/数据层重构规划.md` §十四）；仍缺 `MusicRepository` 的排序 / 搜索，以及 ViewModel 层的行为测试 |
| **`java.time` 不能用**（`minSdk = 24`） | `isCoreLibraryDesugaringEnabled = false` 是**有意**的 | `java.time` / `java.util.stream` / `Optional` 都要 **API 26**，低版本上直接 `NoClassDefFoundError`（不是降级，是崩）。要日历运算走 `java.util.Calendar` + `TimeZone`（见 `data/ListeningReport.kt` 的实现与注释），日期加减用 `Calendar.add(DAY_OF_MONTH, ±n)` 而不是加减 86_400_000 毫秒。真要引入 `java.time`，先开脱糖并加 `desugar_jdk_libs`（会全局影响构建，别为一个小功能开） |
| **lint 的 `abortOnError = false`** | 2026-10-15 收尾时已把 **Error 清到 0**（warnings 仍在，不阻塞构建） | 也就是说"构建成功"≠"lint 干净"。改完代码别只看 `BUILD SUCCESSFUL`，要扫一眼 `app/build/reports/lint-results-debug.xml` 里的 **Error** 级条目。当天共三次收敛：**32 → 5**（21 条 `java.time` 的 `NewApi` 见上一行，5 条 `Tile.setSubtitle` 的 `NewApi` 见 `tile/TileCompat.kt`，`StartActivityAndCollapseDeprecated` 改显式 `@Suppress` 并写明理由），再 **5 → 0**（5 条 `UnsafeOptInUsageError`：`kotlinOptions.freeCompilerArgs` 里的 `-opt-in=...UnstableApi` 编译器认、lint 不认，改为在 `PlaybackService` / `PlayerHandle` 上挂 `@androidx.annotation.OptIn`）。⚠️ 注意 `w:` 开头的 `-opt-in ... is not an opt-in requirement marker` 是**历史告警**，Kotlin 编译期就带着，不是新引入的 |
| 无迁移 = 无版本兼容 | 已定口径：重装即可 | 需要保住老用户数据时，得补 `addMigrations(...)` 并配迁移回归测试（见 §5.1 末）。**v1.0.6（DB v2）→ v1.1.0（DB v4）就是按这条走的：覆盖安装会整库重建** |
| v1.1.0 的 Release 正文沿用通用模板 | 用户 2026-10-14 明确决定「模板原样不动直接发」 | 正文里「覆盖升级后歌单、收藏、播放统计…全部保留」与上面这条**相互矛盾**（实际会清库）。Release 正文可在 GitHub 上直接编辑，要改随时改；模板文件是 `.github/release-notes/template.md` |
| Media3 通知正文只读 `artist` | ✅ **结论存档，功能已移除** | 反编译实证：`DefaultMediaNotificationProvider.getNotificationContentText()` 的字节码只有一句 `getfield artist`，**写进 `subtitle` 的歌词行在通知里永远看不见**（v1.1.0 及以前那个「通知栏 / 锁屏显示歌词」开关从头到尾都是失效的）。v1.1.1 曾用自定义 provider 改过取值顺序，**2026-10-15 该功能整体移除**（通知栏那点宽度放歌词本来就难看，歌词的落点改为桌面悬浮歌词）。结论保留是为了**别再用 `subtitle` 碰运气**；真要往通知里塞内容，只有两条路：改 provider 取值顺序，或覆盖 `artist`（会污染队列抽屉与小组件） |
| Robolectric 首次跑要下 android-all | 约 1~2 分钟（之后走缓存） | CI 上会慢一点，属正常；本地 `testDebugUnitTest` 时间变长主要来自这里 |
| 遗留命名 | 包名已换，但 `music_player.db`、`music_player_prefs`、通知渠道 `local_music_player_playback`、样式 `Theme.MusicPlayer` 仍是旧名 | **不要改**——改了会丢用户数据。换名需要写数据迁移 |
| `fallbackToDestructiveMigration` | 已开启，且是本版**唯一**的版本兜底 | 改 schema 忘了 `version +1` → 老库结构对不上 → 整库重建（曲库靠重扫恢复，歌单/收藏/统计丢）。改 schema 必做：`version +1` + 重导 schema 快照 |
| 未签名包也能构建成功 | 口令缺失时静默降级 | "构建成功但装不上"的常见原因，先查 §8.2 |
| `docs/` 入仓库 | ✅ 2026-10-16 起入库（同时是 GitHub Pages 发布目录） | 远程访客能看到规划文档；对外首屏仍是 `README.md` |
| 仓库 public | 代码全公开 | 写代码注释时注意别把内网地址、真实口令等写进去 |
| `MANAGE_EXTERNAL_STORAGE` | 声明为可选 | 上架 Google Play 会被拒（该权限受限）。自用/侧载无碍 |
| **`UpdateChecker.setVerbose` 从未被调用** | ✅ **2026-10-15 已接上** | `MusicApp.onCreate` 里 `UpdateChecker.setVerbose(BuildConfig.DEBUG)`，5 处 `if (verbose) Log.w(...)` 现在 debug 包才输出。**别再摘下它**（摘了就回到"有开关但永远不打印"的状态） |
| **`DataMaintenance.resetLibrary()` 无 UI 入口** | ✅ **2026-10-15 已补入口** | 设置页「数据与存储」加了「清空曲库与导入目录（保留收藏与统计）」。⚠ **必须连带调 `LibraryViewModel.onLibraryCleared()`**——它把扫描范围退回 `UNSET` 并清 folders；只调 `reloadAll()` 的话，范围为「全盘」时会立刻重扫把曲库填回，表现为"按钮点了没反应" |
| **`Prefs.onChanged()` 无调用者** | ✅ **2026-10-15 已删** | 全工程 0 调用者。界面一律同步读属性（见 §5.2 末尾"读取口径"）。**不要再引入监听器封装** |
| **`androidx.appcompat` / `lifecycle.service` 零 import** | ✅ **2026-10-15 已删** | 主题继承框架 `android:Theme.Material.Light.NoActionBar` 而非 AppCompat；`MediaSessionService` 自带 lifecycle。catalog 里删了版本项 / 库项 / 两处 `implementation(...)`，并在 catalog 注释里留了原因。**删传递依赖要真机跑一遍**，不能只看编译过 |
| **9.3 英文资源** | 第一步（框架层文案：磁贴 / 快捷方式 / 小组件）已分 `values`(英) 与 `values-zh`(中)；**第二步（Compose 文案抽资源）2026-10-24 由用户拍板重启**，按模块分批推进 | 已改造模块整块走 `stringResource` + 资源 id，**不要**在已改造文件里再写中文字面量。未改造模块仍是硬编码中文（预期状态，不是 bug）。抽取规范见 §10 第 17 条。当前进度：第 1 批（曲库首页 / 抽屉 / 播放模式）✅ |
| **9.1 Android Auto 只做了清单兼容层** | `PlaybackService` 加了并列的 `android.media.browse.MediaBrowserService` intent-filter | 车机媒体卡能认出本应用，但**歌单/文件夹不可浏览**（要换 `MediaLibraryService` 才能提供层级）。且**未在真机/模拟器验证过** |
| **9.2 平板 / 车机双栏** | ✅ **v1.1.14 已整体下线** | 起因是车机真机反馈（设置被 Dock 压住 + 不要双栏）。现在所有尺寸统一单栏；`widthDp >= 840` 只用来判断底部要不要给车机 Dock 留 56dp。`CarHomeScreen.kt` 保留未接入，恢复前先读版本历史 v1.1.13 / v1.1.14 |

---

## 13. 接手检查清单

新开上下文接手时，按这个顺序过一遍即可上手：

- [ ] 读 §0 事实表、**兼容口径**与三条铁律
- [ ] `cat app/build.gradle.kts`（版本、包名、签名、BuildConfig 全在这）
- [ ] `cat gradle/libs.versions.toml`（依赖与插件的全部版本，改依赖只看这一份）
- [ ] `cat app/src/main/AndroidManifest.xml`（组件、权限、FileProvider）
- [ ] 看 `AppContainer.kt`（服务定位器：谁提供什么，一眼看完）
- [ ] 看 `state/UiState.kt` 的四个 `*UiState`（状态模型）→ 再到 `ui/` 四个 ViewModel 看各自的公开方法
- [ ] 看 `data/db/`（`Entities.kt` 表结构 / `Daos.kt` 查询 / `MusicDb.kt` 装配）
- [ ] 看 `data/Prefs.kt`（所有用户偏好）
- [ ] 跑一遍 `./gradlew testDebugUnitTest` 确认环境可用（首跑 Robolectric 会下载，慢属正常）
- [ ] `git log --oneline` + `git status` 确认当前分支状态
- [ ] 若涉及发布：读 §8 / §9，并确认 Secrets 与本地 keystore 一致

**动手前的自检**：改的是不是包名 / DB 名 / Prefs 名？（如果是，先确认用户接受数据丢失）
改 schema 有没有把 `version` +1？新加的高频 UI 有没有订阅 `progress` 流？新增的 `.sh` 脚本补可执行位了吗？
跨域提示有没有走 `MessageBus`（而不是塞回某个 UiState）？

---

*本文档位于 `docs/`（2026-10-16 起随仓库入库，并发布到 GitHub Pages）。*

### i18n 第 2 批（2026-10-31）

范围：`SheetsPlaylist.kt`、`LibraryLists.kt`（含「N 首」与空态）、`OnlinePlaylistsPage.kt`。

约定不变：`values/strings.xml` = 英文兜底，`values-zh/strings.xml` = 中文；「N 首」复用 `lib_song_count` plurals；本批新增前缀 `pl_` / `lib_lists_` / `online_`，以及通用键 `create` / `save` / `delete` / `rename` / `play_all` / `refresh` / `collapse` / `cd_back` 等。

本批键名：`create`, `save`, `delete`, `rename`, `play_all`, `refresh`, `collapse`, `cd_back`, `cd_more_actions`, `cd_drag_reorder`, `pl_add_song_title`, `pl_add_selected_title`, `pl_new_name_hint`, `pl_name_hint`, `pl_new_ellipsis`, `pl_new_title`, `pl_rename_ellipsis`, `pl_remove_duplicates`, `pl_export_m3u_ellipsis`, `pl_delete_playlist`, `pl_rename_title`, `pl_confirm`, `pl_my_playlists`, `pl_import_m3u`, `pl_empty_title`, `pl_empty_body`, `pl_detail_reorder_hint`, `pl_empty_virtual_title`, `pl_empty_virtual_body`, `pl_empty_user_title`, `pl_empty_user_body`, `pl_cd_playlist_actions`, `lib_lists_empty_folder_title`, `lib_lists_empty_full_title`, `lib_lists_empty_search_title`, `lib_lists_empty_folder_body`, `lib_lists_empty_full_need_perm_body`, `lib_lists_empty_full_body`, `lib_lists_empty_search_body`, `lib_lists_action_change_folder`, `lib_lists_action_grant_scan`, `lib_lists_action_rescan_full`, `lib_lists_action_scan_scope`, `lib_lists_loading_more`, `lib_lists_jump_current`, `lib_lists_group_empty_title`, `lib_lists_group_empty_body`, `lib_lists_remove_folder`, `online_section_title`, `online_section_body`, `online_add_title`, `online_add_subtitle`, `online_empty`, `online_hls_live`, `online_updated_at`, `online_no_entries`, `online_play`, `online_refreshing`, `online_refresh_failed`, `online_view_entries`, `online_preview_capped`, `online_tap_to_start`, `online_reading`, `online_add_failed`, `online_rename_title`, `online_name_label`, `online_delete_title`, `online_delete_body`, `online_error_title`, `online_url_label`, `online_name_optional`, `online_add_hint`, `online_add`, `online_entry_count`


落盘文件：
- `SheetsPlaylist.kt` / `LibraryLists.kt` / `OnlinePlaylistsPage.kt`
- `values/strings.xml` + `values-zh/strings.xml`（本批新增键；`refresh`/`collapse`/`cancel` 复用已有）

注意：`onClick` / `onFailure` 等非 @Composable 回调里用 `context.getString(...)`，UI 组合内用 `stringResource`；「N 首」走 `pluralStringResource(R.plurals.lib_song_count, …)`。源码字幕「填一个 … 直链」与 `online_add_subtitle` 中文对齐。无逻辑改动。

### i18n 第 3 批（2026-10-31）

范围：`PlayerScreen.kt`、`PlayerScreenParts.kt`（播放页用户可见文案 + contentDescription）。

约定不变：`values/strings.xml` = 英文兜底，`values-zh/strings.xml` = 中文；「队列 N 首」用 `player_queue_size` plurals；`cancel` 复用已有键。本批前缀 `player_` / `cd_`（播放页 contentDescription）。

本批键名：`cd_collapse_player`, `player_not_playing`, `cd_show_cover`, `cd_show_lyrics`, `cd_player_options`, `cd_favorite`, `cd_unfavorite`, `cd_previous`, `cd_play`, `cd_pause`, `cd_next`, `cd_queue`, `cd_a11y_on`, `cd_a11y_off`, `player_a11y_open_failed`, `player_a11y_no_page`, `player_lyric_offset_chip`, `player_sleep_remaining`, `player_queue_size` (plurals), `player_lyrics_filename_default`, `player_line_menu_title`, `player_line_menu_title_looping`, `player_line_menu_body`, `player_line_menu_body_looping`, `player_exit_line_loop`, `player_loop_this_line`, `player_calibrate_offset`, `player_calibrate_title`, `player_calibrate_body_align`, `player_calibrate_will_set`, `player_calibrate_will_set_with_current`, `player_calibrate_hint`, `player_calibrate_confirm`, `player_reset_offset_zero`, `player_cover_gesture_hint`

涉及文件：

- `PlayerScreen.kt` / `PlayerScreenParts.kt`
- `values/strings.xml` + `values-zh/strings.xml`（本批新增键；`cancel` 复用已有）

注意：`onClick` Toast / 导出默认名等非 @Composable 回调里用 `context.getString(...)`，UI 组合内用 `stringResource` / `pluralStringResource`。无业务逻辑改动。

### i18n 第 4 批（2026-10-31）

范围：`SheetsPlayback.kt`（播放队列 / 睡眠定时 / 播放设置 / 悬浮窗样式速选）。

约定不变：`values/strings.xml` = 英文兜底，`values-zh/strings.xml` = 中文；「播放队列 · N 首」「再播 N 首后暂停」用 plurals；`cancel` / `ok` / `move_up` / `move_down` / `cd_player_options` 复用已有键。本批前缀 `queue_` / `sleep_` / `po_` / `ov_` / `cd_remove`。

本批键名：`queue_sheet_title` (plurals), `queue_shuffle_order`, `queue_confirm_clear`, `queue_clear`, `queue_save_as_playlist`, `cd_remove`, `sleep_timer_title`, `sleep_remaining_pause`, `sleep_minutes_pause`, `sleep_custom_minutes`, `sleep_minutes_hint`, `sleep_minutes_error`, `sleep_end_of_song`, `sleep_end_of_album`, `sleep_end_of_queue`, `sleep_after_n_songs_label`, `sleep_after_n_songs` (plurals), `sleep_songs_hint`, `sleep_songs_error`, `sleep_cancel_timer`, `po_section_current_song`, `po_audio_info`, `po_audio_info_sub`, `po_share_current`, `po_share_current_sub`, `po_bookmarks`, `po_bookmarks_sub`, `po_section_effects`, `po_eq_effects`, `po_eq_effects_sub`, `po_stop_playback_timer`, `po_sleep_remaining`, `po_sleep_off`, `po_section_ab_loop`, `po_ab_line_looping`, `po_ab_range`, `po_ab_a_set`, `po_ab_hint`, `po_ab_set_a`, `po_ab_reset_a`, `po_ab_set_b`, `po_ab_reset_b`, `po_ab_exit_line`, `po_ab_clear`, `po_section_lyrics`, `po_desktop_lyrics`, `po_desktop_lyrics_on`, `po_desktop_lyrics_need_perm`, `po_desktop_lyrics_off`, `po_default_preset`, `po_overlay_style`, `po_overlay_style_sub`, `po_export_lrc`, `po_export_lrc_sub`, `po_speed`, `po_pitch`, `po_original_key`, `po_lyric_size`, `po_lyric_offset`, `po_lyric_offset_sub`, `po_reset`, `ov_style_title`, `ov_style_hint`, `ov_style_modified`, `ov_more_settings`

涉及文件：

- `SheetsPlayback.kt`
- `values/strings.xml` + `values-zh/strings.xml`（本批新增键；`cancel`/`ok`/`move_up`/`move_down`/`cd_player_options` 复用已有）

注意：默认预设名先在 `@Composable` 作用域取 `po_default_preset`，再传入 `ifBlank`，避免在非组合 lambda 里调 `stringResource`。无业务逻辑改动。未改 SoftEq / EqSheet / versionName。

### i18n 第 5 批（2026-10-31）

范围：`BookmarkSheet.kt`（播放书签面板）。

约定不变：`values/strings.xml` = 英文兜底，`values-zh/strings.xml` = 中文。标题复用 `po_bookmarks`；删除复用 `delete`。本批前缀 `bm_`。

本批键名：`bm_no_song`, `bm_current_position`, `bm_add_here`, `bm_empty`, `bm_list_count`, `bm_jump`, `bm_close`（另复用 `po_bookmarks` / `delete`）

涉及文件：

- `BookmarkSheet.kt`
- `values/strings.xml` + `values-zh/strings.xml`（本批新增 `bm_*` 键）

注意：无业务逻辑改动。未改 SoftEq / EqSheet / ListenCalendar / versionName。

### i18n 第 6 批（2026-10-31）

范围：`ListenCalendarScreen.kt`（听歌日历热力页；报告入口标题同键）。

约定不变：`values/strings.xml` = 英文兜底，`values-zh/strings.xml` = 中文。返回复用 `cd_back`。本批前缀 `cal_`；周标签用 `string-array` `cal_week_labels`。

本批键名：`cal_title`, `cal_cd_prev_month`, `cal_cd_next_month`, `cal_loading`, `cal_hint`, `cal_month_total`, `cal_active_days`, `cal_active_days_value`, `cal_max_day`, `cal_em_dash`, `cal_pick_day`, `cal_day_listened`, `cal_day_empty`, `cal_week_labels`（另复用 `cd_back`）

涉及文件：

- `ListenCalendarScreen.kt`
- `ListeningReportScreen.kt`（入口 `label` 改用 `cal_title`）
- `values/strings.xml` + `values-zh/strings.xml`（本批新增 `cal_*` 键与周标签数组）

注意：无业务逻辑改动。未改 SoftEq / EqSheet / 数据层 `formatListenDuration` 等 / versionName。







### i18n 第 7 批（2026-10-31）

范围：`SettingsOverlayPage.kt` 侧钮显隐 + 上下顺序；**2026-10-31** 起含第四颗无障碍状态（见 `功能-侧钮无障碍状态.md`）（功能-侧钮顺序设置相关 UI）。

约定不变：`values/strings.xml` = 英文兜底，`values-zh/strings.xml` = 中文。上移/下移复用 `move_up` / `move_down`。本批前缀 `oh_`。

本批键名：`oh_show_handles`, `oh_show_handles_on`, `oh_show_handles_off`, `oh_show_collapse`, `oh_show_collapse_sub`, `oh_show_play_pause`, `oh_show_play_pause_sub`, `oh_show_player`, `oh_show_player_sub`, `oh_order_title`, `oh_order_hint`, `oh_name_collapse`, `oh_name_play_pause`, `oh_name_player`, `oh_order_item`, `oh_restore_order`（另复用 `move_up` / `move_down`）

涉及文件：

- `SettingsOverlayPage.kt`
- `values/strings.xml` + `values-zh/strings.xml`（本批新增 `oh_*`）

注意：无业务逻辑改动。未改 SoftEq / EqSheet / SceneChips / OverlayCollapseHelper 引擎 / versionName。

### i18n 第 8 批（2026-10-31）

范围：`SceneChips.kt` 场景芯片 + 播放设置「场景」分区入口文案；读屏说明挂 `scene_*_desc`。

约定不变：`values/strings.xml` = 英文兜底，`values-zh/strings.xml` = 中文。映射集中在 `SceneChipLabels`（与 `SceneProfiles.chipIds` 对齐）。

本批键名：`scene_section`, `scene_off`, `scene_drive`, `scene_commute`, `scene_sleep`, `scene_hint`, `scene_drive_desc`, `scene_commute_desc`, `scene_sleep_desc`, `scene_off_desc`（本批新增）, `scene_chip_a11y`（本批新增）

涉及文件：

- `SceneChips.kt`（含 `SceneChipLabels`）
- `SheetsPlayback.kt` / `PlayerScreen.kt`（已用 `scene_section` / `SceneChipsRow`，本批未改调用点）
- `values/strings.xml` + `values-zh/strings.xml`
- 单测 `SceneChipLabelsTest`

注意：无业务逻辑改动。未改 SoftEq / EqSheet / SceneMode 引擎 / versionName。诊断包 `[场景]` 段仍中文。



### i18n 第 9 批（2026-10-31）

**EqSheet chrome（非 SoftEq）**：分区标题、全局效果提示、播放过渡、增强音效与 A/B 对比等用户可见文案进 `values` / `values-zh`；标题复用 `po_eq_effects`。

- SoftEq 区块保持 SoftEqCopy / `soft_eq_*`，本批不改生产逻辑
- `SoundSchemes` / `EqPresets` / `ReverbPresets` 名称描述仍硬编码，留给后续内容库批次
- 文档：`docs/功能-i18n均衡器Sheet.md`

### i18n 第 10 批（2026-10-31）

**BootDiagView 启动诊断页**：探针 / 状态说明 / 分区标题 / 按钮与 Toast / 屏幕·窗口快照字段进 alues / alues-zh（oot_diag_*）。纯 View，用 getString。

- 曲库格式筛选已在既有 lib_format_filter_*，本批不改
- 设置诊断导出 chrome、DiagnosticsReport 正文留给后续
- 文档：docs/功能-i18n启动诊断页.md



### i18n 第 11 批（2026-10-31）

**SettingsDiagnosisPage 诊断导出 chrome**：无障碍缺失提示、后台保活、完整诊断包、一键取证、车机排障报告、非官方提示、最近应用等进 values / values-zh（`diag_*`）。Compose 用 `stringResource`，Toast 用 `AppStrings.s`。

- SoftEq 诊断行、DiagnosticsReport/ForensicsScript/ForegroundProbe 正文本批不改
- 文档：`docs/功能-i18n诊断导出.md`


### i18n 第 12 批（2026-10-31）

**DuplicateCleanupScreen 重复清理页**：规则说明、扫描/空态/结果、确认弹窗、保留/移出徽章与元信息行进 values / values-zh（`dup_*`；标题复用 `duplicate_cleanup`，返回 `cd_back`，取消 `cancel`）。Compose 用 `stringResource`。

- SheetsSong（已第 13 批）/ MainScreen / Aliyun / SoftEq 本批不改
- 文档：`docs/功能-i18n重复清理页.md`

### i18n 第 13 批（2026-10-31）

**SheetsSong 单曲 Sheet**：长按操作 / 音频信息 / 编辑歌曲信息面向用户文案进 values / values-zh（`ss_*`）。收藏复用 `cd_favorite` / `cd_unfavorite`；音频信息标题复用 `po_audio_info`；保存 / 取消复用 `save` / `cancel`。Compose 用 `stringResource`。

- 本批键名：`ss_share`, `ss_play_next`, `ss_add_to_playlist`, `ss_view_album`, `ss_view_artist`, `ss_edit_info`, `ss_delete_file`, `ss_info_loading`, `ss_info_unavailable`, `ss_info_container`, `ss_info_codec`, `ss_info_sample_rate`, `ss_info_channels`, `ss_info_bits`, `ss_info_bitrate`, `ss_info_duration`, `ss_info_size`, `ss_info_mime`, `ss_info_path`, `ss_edit_title`, `ss_edit_hint`, `ss_field_title`, `ss_field_artist`, `ss_field_album`
- MainScreen（已第 14 批）/ Aliyun / SoftEq 本批不改
- 文档：`docs/功能-i18n单曲Sheet.md`

### i18n 第 14 批（2026-10-31）

**MainScreen / MainScreenParts 高流量壳**：多选栏、存队列为歌单、快捷方式空库提示、外部分享搜索未命中、删文件 / 移文件夹确认进 values / values-zh（`ms_*`）。加入歌单复用 `ss_add_to_playlist`；收藏复用 `cd_favorite`；保存 / 取消 / 删除复用 `save` / `cancel` / `delete`。Compose 用 `stringResource`；非 Composable 默认歌单名用 `Context.getString`。

- 本批键名：`ms_exit_selection`, `ms_selected_count`, `ms_select_songs`, `ms_select_all`, `ms_invert_selection`, `ms_add_to_queue`, `ms_remove_from_library`, `ms_delete_files`, `ms_save_queue_title`, `ms_save_queue_body`, `ms_playlist_name_hint`, `ms_default_playlist_name`, `ms_shortcut_empty_library`, `ms_search_not_found`, `ms_delete_song_title`, `ms_delete_song_body`, `ms_bulk_delete_title`, `ms_bulk_delete_body`, `ms_remove_folder_title`, `ms_remove_folder_body`, `ms_remove`
- CarLibrary（已第 15 批）/ Aliyun / SoftEq / SheetsScanScope 本批不改
- 文档：`docs/功能-i18n主界面壳.md`

### i18n 第 15 批（2026-10-31）

**CarLibraryScreen 车机曲库壳**：顶栏 / 左导航视图名 / 搜索占位 / 播放条无障碍文案进 values / values-zh。SONGS 专键 `car_view_all_songs`；其余视图复用 `view_*`（`carViewLabelRes`）；搜索 hint `car_search_hint`；本地音乐 / 首数 / 搜索关闭 / 播放控制复用 `lib_title_local_music` / `lib_song_count` / `cd_*`；品牌复用 `app_name`；扫描范围 / 设置 / 云盘此前已 `drawer_*`。

- 本批键名：`car_view_all_songs`, `car_search_hint`（其余复用）
- ListeningReport / LibraryStats / SheetsScanScope / Aliyun / SoftEq 本批不改
- 文档：`docs/功能-i18n车机曲库.md`

### i18n 第 16 批（2026-10-31）

**ListeningReportScreen / LibraryStatsScreen**：听歌报告与曲库格式容量壳文案进 values / values-zh（`lr_*` / `ls_*`）。标题复用 `lib_listening_report`；返回 `cd_back`；日历入口 `cal_title`；天数值 / 破折号复用 `cal_active_days_value` / `cal_em_dash`；首数复用 `lib_song_count`。Compose 用 `stringResource` / `pluralStringResource`。

- 本批键名：`lr_span_days`, `lr_loading`, `lr_section_daily`, `lr_section_hours`, `lr_section_top_played`, `lr_section_top_listened`, `lr_empty_plays`, `lr_empty_listen`, `lr_play_count`, `lr_stat_total`, `lr_stat_avg_day`, `lr_stat_active_days`, `lr_stat_peak_hour`, `lr_peak_hour_range`, `lr_today_label`, `lr_play_chart`, `ls_title`, `ls_empty`, `ls_stat_total_songs`, `ls_stat_total_size`, `ls_stat_total_duration`, `ls_stat_avg_size`, `ls_stat_format_count`, `ls_format_kinds`, `ls_section_by_size`, `ls_section_lossless_lossy`, `ls_lossless`, `ls_lossy`, `ls_unknown_format`, `ls_count_dot_size`, `ls_share_pct`, `ls_avg_bitrate`, `ls_footnote_base`, `ls_footnote_unknown_size`, `ls_footnote_m4a`
- OnlineListeningReport / SheetsScanScope / Aliyun / SoftEq 本批不改
- 文档：`docs/功能-i18n听歌报告与曲库统计.md`

### i18n 第 17 批（2026-10-31）

- 范围：`SheetsScanScope.kt`（`ScanScopeSheet` / `ScanScopeChip`）面向用户壳文案
- 前缀：`ssc_*`；复用 `drawer_scan_scope`、`lib_scan_full`、`plurals/lib_song_count`
- 未碰 SoftEq / 播放内核 / 扫描逻辑
- 文档：`docs/功能-i18n扫描范围Sheet.md`
