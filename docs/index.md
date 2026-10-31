---
layout: default
title: 首页
nav_exclude: false
nav_order: 1
permalink: /
---

# 听风 WindTone

Android 原生本地音乐播放器。**数据全部留在本机**，只在你主动（或开启自动检查时）才访问 GitHub 上的公共分发仓库查询新版本。

支持 MP3 / FLAC / WAV / M4A / OGG / Opus 等常见格式；同时支持「独立歌词文件」与「音频内嵌歌词」两种来源，兼容 KRC / YRC（酷狗 / 网易云）格式，并支持原文 + 翻译的双语歌词。

[![源码](https://img.shields.io/badge/源码-GitHub-181717?logo=github)](https://github.com/LuckyLearning/WindTone)
[![下载](https://img.shields.io/badge/下载-公共仓库-5B4BE0)](https://github.com/LuckyLearning/Release/tree/main/WindTone)
[![License](https://img.shields.io/github/license/LuckyLearning/WindTone)](https://github.com/LuckyLearning/WindTone/blob/main/LICENSE)
[![Docs](https://img.shields.io/badge/文档站-Pages-4f63e0)](https://luckylearning.github.io/WindTone/)

---

## 从这里开始

<div class="cards">
<a class="card" href="功能说明.html"><b>功能说明</b><span>音乐库、歌词、播放、桌面组件与设置页的完整说明</span></a>
<a class="card" href="ui/"><b>界面效果图</b><span>约 94 屏 · 按功能分章（手机 / 车机）；见 ui/UI清单.md</span></a>
<a class="card" href="权限与更新.html"><b>权限与更新</b><span>申请哪些权限、什么时候申请；应用内更新怎么工作</span></a>
<a class="card" href="版本历史.html"><b>版本历史</b><span>从 1.0.0 到现在，每个版本改了什么</span></a>
<a class="card" href="https://github.com/LuckyLearning/WindTone"><b>源码仓库</b><span>已公开 · Apache-2.0 · Issues / PR 欢迎</span></a>
<a class="card" href="https://github.com/LuckyLearning/WindTone/blob/main/LICENSE"><b>LICENSE</b><span>Apache License 2.0</span></a>
</div>

---

## 下载安装

最新安装包在公共分发仓库 **[LuckyLearning/Release](https://github.com/LuckyLearning/Release/tree/main/WindTone)**（**源码已公开**于 [LuckyLearning/WindTone](https://github.com/LuckyLearning/WindTone)；安装包仍单独放在分发仓，按「软件 / 版本」分目录，只保留最近 5 个版本，供应用内更新匿名读取）。每个版本按 CPU 架构分成 4 个包（Release 签名包，R8 混淆 + 资源压缩，约 3 MB）：

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
| 网络 | **只有**一个用途 —— 访问公共分发仓库检查 / 下载更新；不引入 OkHttp / Gson |
| 许可证 | [Apache-2.0](https://github.com/LuckyLearning/WindTone/blob/main/LICENSE) |

工程结构、构建命令与签名说明见仓库根的 [README](https://github.com/LuckyLearning/WindTone#readme)。

---

## 文档索引

### 面向使用者

- [功能说明](功能说明.html) —— 音乐库 / 格式支持 / 歌词 / 播放 / 桌面组件 / 设置页
- [界面效果图](ui/) —— 设计基准（Design Source of Truth）
- [权限与更新](权限与更新.html) —— 权限清单、`.lrc` 获取条件、应用内更新机制、已知限制
- [版本历史](版本历史.html)
- [源码 / 贡献](https://github.com/LuckyLearning/WindTone) · [LICENSE Apache-2.0](https://github.com/LuckyLearning/WindTone/blob/main/LICENSE)

### 面向开发者 / 维护者

- [AI 开发指南](AI开发指南.html) —— 项目约定、架构、踩坑记录（维护工作量最大的一份）
- [数据库设计 MusicDb](数据库设计-MusicDb.html)
- [发版规则（版本号 + 发版流程）](版本号发版规则.html)
- [可复现构建](可复现构建.html)
- [转 public 检查清单](转public检查清单.html) —— 密钥扫描、Secrets、公开后待办（维护者）
- [真机验证清单](真机验证清单.html) —— 维护者发版前勾选；含车机 / 手机专项，非面向最终用户
- [设计 · 悬浮歌词预设化](设计-悬浮歌词预设化.html) · [车载模式](设计-车载模式.html) · [悬浮窗按应用隐藏](设计-悬浮窗按应用隐藏.html)
- [数据层重构规划](数据层重构规划.html) · [联网播放架构评审](架构评审-联网播放可行性.html)
- [文档盘点](DOC_INVENTORY.html) · [删除/归档记录](DELETIONS.html)

### 功能规划

- 排期以对话 Canvas「WindTone 可用规划（现行）」为准。
- 历史分期规划已移入 [archive/](archive/)（见 [DELETIONS](DELETIONS.html)），不再作排期源。

<!-- 最后部署核验: 2026-10-31 Pages Source=GitHub Actions -->
