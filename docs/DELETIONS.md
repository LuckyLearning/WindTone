---
layout: default
title: 文档删除与归档记录
---

# 文档删除与归档记录

> 约定：过期文档**禁止静默废弃**。移入 `docs/archive/` 或硬删时，**必须**在本文件追加一行。
> 时区：Asia/Shanghai (UTC+8)

## 记录

| 日期（Asia/Shanghai (UTC+8)） | 动作 | 原路径 | 现路径 | 原因 | 替代 / 备注 |
|---|---|---|---|---|---|
| 2026-10-25 | 归档（移动） | `docs/功能规划.md` | `docs/archive/功能规划.md` | 一期总纲（v1.0.0），阶段完成，不宜再当排期源 | 现行：对话 Canvas「WindTone 可用规划（现行）」+ `版本历史.md` |
| 2026-10-25 | 归档（移动） | `docs/功能规划-第二期.md` | `docs/archive/功能规划-第二期.md` | 二期规划已完成 | 同上 |
| 2026-10-25 | 归档（移动） | `docs/功能规划-第三期.md` | `docs/archive/功能规划-第三期.md` | 三期规划已完成 / 过时 | 同上 |
| 2026-10-25 | 归档（移动） | `docs/功能规划-第五期.md` | `docs/archive/功能规划-第五期.md` | 五期（v1.1.1 基线）多数已落地；含已作废项 | `设计-悬浮歌词预设化.md` + 版本历史 |
| 2026-10-25 | 归档（移动） | `docs/悬浮歌词完善方案.md` | `docs/archive/悬浮歌词完善方案.md` | 已被预设化设计替代；方案自述全部落地 | `设计-悬浮歌词预设化.md`（文首已声明替代） |
| 2026-10-25 | 归档（移动） | `docs/代码审计-2026-10-15.md` | `docs/archive/代码审计-2026-10-15.md` | 单日死代码审计快照，非现行规范 | 无；备查 |
| 2026-10-25 | 状态修订（未移） | `docs/设计-悬浮歌词预设化.md` | （原地） | **FR-08 驾驶中简化：用户否决 / 不做**；N-6 / 验收 / 风险表同步 | 见该文档变更记录 v2.2 |
| 2026-10-25 | 状态修订（未移） | `docs/ui/08-floating-lyrics.html` | （原地） | 去掉「驾驶中简化」作为可交付控件的呈现，标「已否决」 | FR-08 pass |
| 2026-10-25 | 状态修订（未移） | `docs/设计-悬浮窗按应用隐藏.md` | （原地） | 文首「尚未落地」过时；改为已落地（1.1.29 起，DiLink 链 1.1.82–91） | 真机验证清单 P0-78+ |
| 2026-10-25 | 注释修订（未移） | `app/.../OverlayPresets.kt` | （原地） | 去掉「v1.1.79 完整接入 FR-07/08」误导注释 | FR-07 仍待做；FR-08 不做 |

## 硬删记录

| 日期（Asia/Shanghai） | 动作 | 对象 | 原因 | 备注 |
|---|---|---|---|---|
| 2026-10-26 | 硬删 | `app/.../lyrics/BilingualLyrics.kt` + `BilingualLyricsTest.kt` | v1.2.2 移除「双语歌词」三档切换，过滤实现整体作废 | 固定为"原文+译文都显示"，见版本历史 1.2.2 |
| 2026-10-26 | 硬删 | `OverlayCollapseHelper.handlePositions`（两钮版）+ 对应测试 | 已被 `handleColumn`（任意颗）取代，三钮后无人调用 | 测试合并进 `handleColumn` 的用例 |
| 2026-10-26 | 硬删 | 车机旧尺码常量：`CAR_NAV_WIDTH`、`CAR_SIDE_TILE`、`CAR_MAIN_BUTTON`、`CAR_SIDE_BUTTON`、`CAR_SECONDARY_BUTTON`、`CAR_ROW_MIN_HEIGHT`、`CAR_BAR_HEIGHT` | v1.1.98 起尺寸统一由 `CarMetrics` 决定，这些只剩注释价值 | 历史保留在 `CarMetrics` 的档位表里 |
| 2026-10-26 | 硬删 | 10 条双语字符串（`bilingual_lyrics*`、`show_both_languages`、`chinese_only`、`english_only`、`which_lines_translated_lyrics_show`、`applies_to_lyrics_that_carry` 等） | 功能已移除，`values` / `values-zh` 两份都无引用 | 顺带把 `text_size_x_bilingual_lyrics` 的英文文案去掉"Bilingual lyrics"字样 |
| 2026-10-27 | 硬删 | `app/.../ui/CarSettingsScreen.kt`（930 行）+ `app/src/test/.../ui/CarSettingsTest.kt` | v1.2.8 用户拍板：车载设置与手机用同一套 `SettingsScreen`，唯一区别是顶栏每页常驻「播放页」；两套信息结构 = 两条维护线 | 效果图 ㊳ 已重画（`docs/ui/06-car.html`）；车载预设池行为不变 |
| 2026-10-27 | 硬删 | `SettingsScreen` 的 5 个车载专用参数（`onTogglePlay` / `onPrevious` / `onNext` / `onRescan` / `onOpenScanScope`）及其 `MainScreen` 实参 | 只有大卡页消费；大卡删除后无人调用（`onOpenPlayer` 保留，顶栏「播放页」用） | 回调本体（`playerVm.togglePlay` 等）在其他入口仍正常使用 |
| 2026-10-27 | 硬删 | 「完整设置」机制（`fullSettings` 状态 + 返回键分支 + 顶栏标题切换）与 `all_settings` 字符串（values / values-zh 两份） | 大卡删除后「完整设置」入口不复存在 | `SettingsScreenTest` 相应断言改为直接进子页 |
| 2026-10-27 | 合并删除 | `docs/发版规范-Release说明与多平台产物.md`（发版流程 / 多平台产物 / 公共分发仓库 / 一键发版） | 与 `docs/版本号发版规则.md` 合并为**唯一**发版规则文件，避免两份发版文档并存 | 内容并入 `版本号发版规则.md`（§二 发版产物 / §三 发版流程 / §四 Release 说明）；index / README / _layouts / DOC_INVENTORY / 权限与更新 / AI开发指南 / verify-release.sh 引用已改指向新文件 |
