---
title: 功能 · i18n 设置歌词与维护 Toast（第 20 批）
---

# i18n 设置歌词与维护 Toast（第 20 批）

把**设置深层**里仍硬编码的用户可见文案收进既有 i18n 体系：

- `res/values/strings.xml` = 英文兜底
- `res/values-zh/strings.xml` = 中文

与第 1–19 批同一套约定，不另造框架。详见 `docs/AI开发指南.md` § i18n 第 20 批。

## 覆盖范围

### `sl_a11y_*`（SettingsLyricsPage · 前台识别开关）

- `sl_a11y_title`：开关标题
- `sl_a11y_sub_on` / `sl_a11y_sub_off`：开/关副文案

Compose 用 `stringResource`。

### `svm_*`（SettingsViewModel · 维护 / 崩溃日志 Toast）

- `svm_cleaned_n` / `svm_cleaned_none`：清理失效歌曲
- `svm_cover_cleared`：清空封面缓存
- `svm_user_data_cleared`：清空用户数据
- `svm_library_cleared`：清空曲库与导入目录
- `svm_crash_cleared` / `svm_crash_exported` / `svm_export_failed`：崩溃日志清空 / 导出

非 Composable，走 `AppStrings.s`（与诊断页 Toast 同模式）。

## 刻意未改

- SoftEq / SoftEqSpike / 播放内核
- `SettingsSystemPages` 里「软 EQ 状态」诊断行与 Toast（属 SoftEq E5）
- `PlaybackErrorMessages`（播放失败文案，下一批候选）
- `SettingsSearch` 检索关键词表（索引用双语 token，不是界面文案）
- `ForegroundProbe` / `LyricOverlayBridge` 排障报告正文
- versionName / tag / release

## 验证

- `./gradlew :app:compileDebugKotlin --offline`
- 相关：无专属 SettingsViewModel UI 测；至少编译通过
- 人工扫：`SettingsLyricsPage` 前台识别行、`SettingsViewModel` 用户可见 Toast 无残留中文硬编码
