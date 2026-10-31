---
title: 功能 · i18n 扫描范围 Sheet（第 17 批）
---

# i18n 扫描范围 Sheet（第 17 批）

把 **SheetsScanScope**（`ScanScopeSheet` / `ScanScopeChip`）面向用户的硬编码中文收进既有 i18n 体系。

- `res/values/strings.xml` = 英文兜底
- `res/values-zh/strings.xml` = 中文

与第 1～16 批同一套约定，不另造框架。详见 `docs/AI开发指南.md` § i18n 第 17 批。

## 覆盖范围

### 复用既有 key

- 标题：`drawer_scan_scope`
- 全盘选项标题：`lib_scan_full`
- 目录行曲目数：`plurals/lib_song_count`

### 本批前缀 `ssc_*`

- 说明：`ssc_hint`
- 分区：`ssc_section_full` / `ssc_section_folders` / `ssc_section_imported`
- 全盘副标题：`ssc_full_subtitle`（Sheet 原文；与 `lib_scan_full_desc` 语义近但不合并，避免改引导页文案）
- 指定目录主副文案：`ssc_use_imported` / `ssc_pick_folder` / `ssc_active_folders` / `ssc_all_excluded` / `ssc_pick_via_picker`
- 追加目录：`ssc_import_more` / `ssc_import_more_sub`
- 已导入行：`ssc_excluded` / `ssc_folder_meta` / `ssc_cd_remove_folder`
- 排除说明：`ssc_exclude_hint`
- 芯片：`ssc_chip_unset` / `ssc_chip_full` / `ssc_chip_folders` / `ssc_chip_folders_n`

## 刻意未改

- SoftEq / SoftEqSpike / 播放内核
- OnlineListeningReport / Aliyun 整包
- versionName / tag / release
- 扫描逻辑、`ScanScope` 枚举、Prefs

## 验证

- `./gradlew :app:compileDebugKotlin --offline`
- `./gradlew :app:testDebugUnitTest --tests com.luckylearning.windtone.ui.CarViewLabelTest --tests com.luckylearning.windtone.ui.CarNavRailTest --offline`
- 人工扫：`SheetsScanScope.kt` 用户可见文案无残留中文硬编码（KDoc/注释除外）
