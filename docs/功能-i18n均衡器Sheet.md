---
title: 功能 · i18n 均衡器 Sheet（第 9 批）
---

# i18n 均衡器 Sheet（第 9 批）

把 **EqSheet** 面向用户的分区标题 / 提示 / 开关旁文案收进既有 i18n 体系：

- `res/values/strings.xml` = 英文兜底
- `res/values-zh/strings.xml` = 中文

与第 1–8 批同一套约定，不另造框架。详见 `docs/AI开发指南.md` § i18n 第 9 批。

## 覆盖范围

- 标题复用 `po_eq_effects`
- 全局效果链提示 `eq_global_effects_warning`、不可用诊断 `eq_unavailable_detail`
- 分区：`eq_section_scheme` / `eq_section_transition` / `eq_section_preset` / `eq_section_bands` / `eq_section_enhance`
- 方案：hint / 车载后缀 / 未选标题与说明
- A/B 对比按钮：`eq_ab_compare` / `eq_ab_bypassed`
- 播放过渡：淡入淡出标题与 hint、时长、音量平衡标题
- 预设卡「自定义」、频段 hint
- 增强：低音 / 环绕 / 响度 / 关闭 / 百分比与 mB 格式、混响与不可用提示

## 刻意未改

- SoftEq 生产逻辑与 `SoftEqPathSection`（文案已走 SoftEqCopy / soft_eq_*）
- `SoundSchemes` / `EqPresets` / `ReverbPresets` 目录名与描述（内容库，下批）
- `versionName` / tag / release
- 并行曲库浏览单测文件

## 验证

- `./gradlew :app:testDebugUnitTest --offline --tests com.luckylearning.windtone.playback.AudioFx*`
  （含 `eqUnavailableRes` 相关）
- 人工扫：`EqSheet.kt` 用户可见 chrome 无残留中文硬编码（注释与内容库名除外）
