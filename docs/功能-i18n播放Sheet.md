---
title: 功能 · i18n 播放 Sheet（第 4 批）
---

# i18n 播放 Sheet（第 4 批）

把 `SheetsPlayback.kt` 里面向用户的硬编码中文抽进既有 i18n 体系：

- `res/values/strings.xml` = 英文兜底
- `res/values-zh/strings.xml` = 中文

与第 1 / 2 / 3 批同一套约定，不另造框架。详见 `docs/AI开发指南.md` § i18n 第 4 批。

## 覆盖范围

- 播放队列 Sheet：标题（含曲数 plurals）、打乱/清空/存为歌单、上移/下移/移除
- 睡眠定时 Sheet：倒计时文案、预设分钟、自定义分钟/再播 N 首、播完曲/专辑/队列、取消定时
- 播放设置 Sheet：当前歌曲 / 音效 / 睡眠 / A-B 循环 / 歌词分组与行内控件
- 悬浮窗样式速选：标题、说明、已修改提示、更多设置

## 刻意未改

- SoftEq / EqSheet 软路径 / 诊断包 SoftEq 段（E5 并行）
- `UserFacingCopy` 大结构
- `versionName` / tag / release

## 验证

- `./gradlew :app:testDebugUnitTest --offline --tests com.luckylearning.windtone.playback.SleepTimerTest`
- 人工扫：SheetsPlayback 无残留用户可见中文硬编码
