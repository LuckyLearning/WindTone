---
layout: default
title: 项目文档
---

# 听风 WindTone

Android 原生本地音乐播放器。**数据全部留在本机**，只在你主动（或开启自动检查时）才访问 GitHub Releases 查询新版本。

支持 MP3 / FLAC / WAV / M4A / OGG / Opus 等常见格式；同时支持「独立歌词文件」与「音频内嵌歌词」两种来源，包含 KRC / YRC 逐字歌词与双语歌词。

[![Android CI](https://github.com/LuckyLearning/WindTone/actions/workflows/android-ci.yml/badge.svg)](https://github.com/LuckyLearning/WindTone/actions/workflows/android-ci.yml)
[![Release APK](https://github.com/LuckyLearning/WindTone/actions/workflows/android-release.yml/badge.svg)](https://github.com/LuckyLearning/WindTone/actions/workflows/android-release.yml)
[![Latest Release](https://img.shields.io/github/v/release/LuckyLearning/WindTone?display_name=tag&sort=semver)](https://github.com/LuckyLearning/WindTone/releases/latest)
[![License](https://img.shields.io/github/license/LuckyLearning/WindTone)](https://github.com/LuckyLearning/WindTone/blob/main/LICENSE)

---

## 从这里开始

<div class="cards">
<a class="card" href="功能说明.html"><b>功能说明</b><span>音乐库、歌词、播放、桌面组件与设置页的完整说明</span></a>
<a class="card" href="ui/"><b>界面效果图</b><span>51 屏设计基准，手机与车机两套布局</span></a>
<a class="card" href="权限与更新.html"><b>权限与更新</b><span>申请哪些权限、什么时候申请；应用内更新怎么工作</span></a>
<a class="card" href="版本历史.html"><b>版本历史</b><span>从 1.0.0 到现在，每个版本改了什么</span></a>
</div>

---

## 下载安装

最新安装包在 **[Releases](https://github.com/LuckyLearning/WindTone/releases/latest)** 页面。每个版本按 CPU 架构分成 4 个包（Release 签名包，R8 混淆 + 资源压缩，约 3 MB）：

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

---

## 技术一览

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 + Jetpack Compose（Material 3，BOM 2024.10.01） |
| 播放内核 | Media3 1.4.1（`MediaSessionService` / ExoPlayer） |
| 本地存储 | Room 2.6.1（KSP），schema v5，9 张表 |
| 构建 | AGP 8.7.2 / Gradle 8.9（Version Catalog 统一管控） |
| 最低版本 | Android 7.0（API 24），targetSdk 35 |
| 架构 | 分域 ViewModel + 手写服务定位器（`AppContainer`），无 Hilt / Koin |
| 网络 | **只有**一个用途 —— 访问 `api.github.com` 检查更新；不引入 OkHttp / Gson |

工程结构、构建命令与签名说明见仓库根的 [README](https://github.com/LuckyLearning/WindTone#readme)。

---

## 文档索引

### 面向使用者

- [功能说明](功能说明.html) —— 音乐库 / 格式支持 / 歌词 / 播放 / 桌面组件 / 设置页
- [界面效果图](ui/) —— 设计基准（Design Source of Truth）
- [权限与更新](权限与更新.html) —— 权限清单、`.lrc` 获取条件、应用内更新机制、已知限制
- [版本历史](版本历史.html)

### 面向开发者

- [AI 开发指南](AI开发指南.html) —— 项目约定、架构、踩坑记录（维护工作量最大的一份）
- [数据库设计 MusicDb](数据库设计-MusicDb.html)
- [数据层重构规划](数据层重构规划.html)
- [发版规范：Release 说明与多平台产物](发版规范-Release说明与多平台产物.html)
- [悬浮歌词完善方案](悬浮歌词完善方案.html)
- [死代码审计报告 2026-10-15](代码审计-2026-10-15.html)

### 功能规划

- [功能扩展规划（总纲）](功能规划.html)
- [第二期](功能规划-第二期.html) · [第三期](功能规划-第三期.html) · [第四期 · 云盘音乐（已否决）](功能规划-第四期-云盘音乐.html) · [第五期](功能规划-第五期.html)
