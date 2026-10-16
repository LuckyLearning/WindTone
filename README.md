# 听风 WindTone

Android 原生本地音乐播放器 —— Kotlin + Jetpack Compose，**数据全部留在本机**。

支持 MP3 / FLAC / WAV / M4A / OGG / Opus 等常见格式；歌词支持独立 `.lrc` 文件与音频内嵌标签，兼容 KRC / YRC（酷狗 / 网易云）格式，并支持原文 + 翻译的双语歌词。

[![Android CI](https://github.com/LuckyLearning/WindTone/actions/workflows/android-ci.yml/badge.svg)](https://github.com/LuckyLearning/WindTone/actions/workflows/android-ci.yml)
[![Release APK](https://github.com/LuckyLearning/WindTone/actions/workflows/android-release.yml/badge.svg)](https://github.com/LuckyLearning/WindTone/actions/workflows/android-release.yml)
[![Latest Release](https://img.shields.io/github/v/release/LuckyLearning/WindTone?display_name=tag&sort=semver)](https://github.com/LuckyLearning/WindTone/releases/latest)
[![License](https://img.shields.io/github/license/LuckyLearning/WindTone)](LICENSE)

---

## 下载安装

最新安装包在 **[Releases](https://github.com/LuckyLearning/WindTone/releases/latest)** 页面，按 CPU 架构分成 4 个包（Release 签名包，R8 混淆 + 资源压缩，约 3 MB）：

| 文件 | 说明 |
|---|---|
| `WindTone-vX.Y.Z-arm64-v8a.apk` | **绝大多数手机装这个**（近十年的 64 位 ARM 设备） |
| `WindTone-vX.Y.Z-armeabi-v7a.apk` | 更老的 32 位 ARM 设备 |
| `WindTone-vX.Y.Z-x86_64.apk` | 模拟器 / x86 设备 |
| `WindTone-vX.Y.Z-universal.apk` | 全架构合一，体积略大；**拿不准架构时用它** |

> **升级前请看**：同一套数据表结构（当前 `schema v5`）之间覆盖升级数据完整保留；
> 一旦版本改变了表结构，应用会**重建数据库**——曲库重新扫描即可恢复，但**歌单、收藏、
> 播放统计、歌词偏移会清空**。本应用**不做跨版本数据迁移**，这是既定取舍。

---

## 文档

完整的功能说明、界面效果图与开发文档在文档站：**<https://luckylearning.github.io/WindTone/>**

| 文档 | 内容 |
|---|---|
| [功能说明](https://luckylearning.github.io/WindTone/功能说明.html) | 音乐库 / 格式支持 / 歌词 / 播放 / 桌面组件 / 设置页 |
| [界面效果图](https://luckylearning.github.io/WindTone/ui/) | 52 屏设计基准（手机 + 车机），改动 UI 前必读 |
| [权限与更新](https://luckylearning.github.io/WindTone/权限与更新.html) | 权限清单、`.lrc` 获取条件、应用内更新机制、已知限制 |
| [版本历史](https://luckylearning.github.io/WindTone/版本历史.html) | 每个版本改了什么 |
| [AI 开发指南](https://luckylearning.github.io/WindTone/AI开发指南.html) | 项目约定、架构分层与踩坑记录 |
| [数据库设计](https://luckylearning.github.io/WindTone/数据库设计-MusicDb.html) | MusicDb schema 与各表职责 |
| [发版规范](https://luckylearning.github.io/WindTone/发版规范-Release说明与多平台产物.html) | Release 说明与多平台产物 |
| [功能规划](https://luckylearning.github.io/WindTone/功能规划.html) | 各期规划与已否决的方案 |

---

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 + Jetpack Compose（Material 3，BOM 2024.10.01） |
| 播放内核 | Media3 1.4.1（`MediaSessionService` / ExoPlayer） |
| 本地存储 | Room 2.6.1（KSP），schema v5，9 张表 |
| 构建 | AGP 8.7.2 / Gradle 8.9；依赖与插件版本统一由 Version Catalog 管控 |
| 最低版本 | Android 7.0（API 24），compileSdk / targetSdk 35 |
| 架构 | 分域 ViewModel（播放 / 曲库 / 设置 / 更新）+ 手写服务定位器 `AppContainer`，无 Hilt / Koin |
| 网络 | **只有**一个用途 —— 访问 `api.github.com` 检查更新；不引入 OkHttp / Gson |
| 单元测试 | 206 项（JVM + Robolectric），lint Error 级 0 |

---

## 源码结构

```
WindTone/
├─ .github/workflows/
│  ├─ android-ci.yml            主干/PR：单测 + debug 构建校验
│  ├─ android-release.yml       打 tag：单测 + 签名 Release + 创建 Release
│  └─ pages.yml                 文档站：docs/ → GitHub Pages
├─ app/src/main/java/com/luckylearning/windtone/
│  ├─ AppContainer.kt           依赖装配（手写服务定位器）
│  ├─ data/                     数据层（扫描 / 数据库 / 封面 / 歌词匹配 / 更新 / 维护 / 删除）
│  ├─ lyrics/                   歌词引擎（纯 JVM，可直接单测）
│  ├─ playback/                 播放引擎、播放服务与音效
│  ├─ state/                    界面状态模型（四个域共用）
│  ├─ ui/                       Compose 界面与四个分域 ViewModel
│  ├─ overlay/ widget/ tile/    悬浮歌词 / 桌面小部件 / 快捷磁贴
│  └─ MusicApp.kt MainActivity.kt
├─ app/src/test/                206 个单元测试
├─ docs/                        文档站源目录（GitHub Pages 发布目录）
├─ scripts/                     图标生成、本地 SDK 引导、发版与推送助手
├─ gradle/                      Wrapper + `libs.versions.toml`（依赖与插件版本唯一来源）
└─ .toolchain/                  本地专属：Android SDK + 签名库（已 gitignore）
```

| 模块 | 关键文件 | 说明 |
|---|---|---|
| 歌词解析 | `lyrics/LrcParser.kt` · `KrcParser.kt` · `EmbeddedTags.kt` · `TextCodec.kt` | LRC / KRC / YRC、内嵌标签、编码嗅探 |
| 歌词装配 | `lyrics/LyricsLoader.kt` | **唯一加载漏斗**：旁挂 / 内嵌多来源的取词优先级 |
| 音乐库聚合 | `data/MusicRepository.kt` | 按扫描范围分流、三来源合并去重 |
| 数据库 | `data/db/MusicDb.kt` | Room：9 表（曲库 / 目录 / 每曲状态 / 歌单 / 歌单曲目 / 队列 / 播放态 / 搜索历史 / 收听日志） |
| 应用内更新 | `data/UpdateChecker.kt` | GitHub Releases 查询、APK 下载校验、FileProvider 安装 |
| 播放引擎 | `playback/PlaybackEngine.kt` | 应用级单例：队列持久化与恢复、播放统计、歌词装载、全部播放动作（尾随 `PlayerActions` 接口） |
| 音效 / 变调 / 定时 | `playback/AudioFx.kt` · `EqPresets.kt` · `ReverbPresets.kt` · `Pitch.kt` · `SleepTimer.kt` | 均衡器（16 个内置预设 + 自定义频段）与增强音效（低音 / 环绕 / 响度 / 混响）、半音↔倍率换算、三档睡眠定时；弹层在 `ui/EqSheet.kt` |
| 设置页 | `ui/SettingsScreen.kt` + `SettingsPages / SettingsLyricsPage / SettingsSystemPages / SettingsRows / SettingsColors / SettingsDialogs` | 顶层编排（一主六子）+ 各子页正文 / 行组件 / 歌词配色 / 对话框 |

---

## 本地构建

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
# 单元测试（纯 JVM + Robolectric，不需要设备）
./gradlew :app:testDebugUnitTest

# 出包
./gradlew :app:assembleDebug :app:assembleRelease

# lint 报告（构建成功 ≠ lint 干净，看 app/build/reports/lint-results-debug.xml）
./gradlew :app:lintDebug
```

产出：`app/build/outputs/apk/release/WindTone-v<版本>.apk`（Debug 为 `.../debug/WindTone-v<版本>-debug.apk`）

Windows 下首次可先跑 `scripts\sdk-setup.bat` 把 SDK 装到 `.toolchain\android-sdk`。

### 文档站

`docs/` 目录同时是文档源与 GitHub Pages 发布目录，由 `.github/workflows/pages.yml` 在 `main` 分支推送时自动部署。
本地预览装好 Jekyll 后 `cd docs && jekyll serve` 即可，无需构建步骤。

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

### 发版

发版说明正文由 `.github/release-notes/template.md` 提供，`scripts/render-release-notes.sh` 由 CI 与 `scripts/release.sh` 共用。
细节见[发版规范](https://luckylearning.github.io/WindTone/发版规范-Release说明与多平台产物.html)。

---

## License

[Apache License 2.0](LICENSE)
