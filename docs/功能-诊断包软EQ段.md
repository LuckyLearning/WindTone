---
layout: default
title: 功能 - 诊断包软EQ段
---

# 诊断包 `[软EQ]` 段（E5）

导出「诊断包（完整）」或诊断页「软 EQ 状态」时给出软路径只读摘要，方便回传后判断相位 / 确认偏好 / 曲线是否跟上。

## 内容

- **相位**：`IDLE` / `CANDIDATE` / `SOFT_ACTIVE` / `HARD_ACTIVE`
- **Prefs.softEqUserConfirm** 与内存确认（不一致标明）
- **处理器启用 / 候选**、`SoftEqSpike.ENABLED`
- **硬不可用原因**、**曲线摘要**（Source / preset / 五段 mB / 可听 / 中心 Hz）

Prefs 键：`soft_eq_user_confirm`。不含凭据与 URL。

## 实现

- `diag/SoftEqProbe.kt` → `DiagnosticsReport.Facts.softEqBlock`
- 诊断页 `SettingsDiagnosisPage`「软 EQ 状态」行

## 状态

代码已上 main；**发版等 GitHub Actions 额度**（禁止打 tag）。E6 真机 A/B 见 `docs/功能-软EQ.md`（未验车）。