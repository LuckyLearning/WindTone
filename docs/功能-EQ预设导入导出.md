---
layout: default
title: 功能 · EQ 预设导入导出
---

# EQ 预设导入导出（#10）

用户自定义均衡器预设（「我的」）可 **导出为 JSON 文件**、**从文件导入**，用于分享与备份。

## 入口

均衡器与音效弹层 → **预设 → 我的** → 「导出」/「导入」。

- **导出**：系统 SAF `CreateDocument`（`application/json`），默认名 `windtone-eq-presets.json`。列表为空时 Toast 提示先保存。
- **导入**：SAF `OpenDocument`；校验 `format` 后弹出确认，可选 **合并** 或 **覆盖全部**。

## 文件格式

```json
{
  "format": "windtone.user_eq_presets",
  "version": 1,
  "exportedAt": 1730000000000,
  "presets": [
    {
      "id": "u_…",
      "name": "夜路",
      "bands": "300,-100,0,50,400",
      "bass": 700,
      "virtualizer": 150,
      "loudness": 300,
      "reverbId": "hall_medium"
    }
  ]
}
```

- `format` 必须为 `windtone.user_eq_presets`，否则拒收并提示「不是听风的 EQ 预设文件」。
- 单条校验与内存预设相同（`u_` id、名字清洗、频段 CSV）；坏条跳过。
- **内部 Prefs** 仍用单元分隔符编码（`UserEqPresets.encode`/`decode`）；JSON **仅用于文件**，与 Prefs 无关。

## 导入策略（诚实提示）

| 模式 | 行为 |
|---|---|
| **合并** | 同 `id` 覆盖该条；新 `id` 追加；已满 24 条时新 id 跳过并计入 skipped |
| **覆盖全部** | 丢弃当前「我的」列表，只保留文件内容（截到 24）；**不可撤销**，对话框写明 |

导入完成后 Toast 汇报新增 / 更新 / 跳过数量。若覆盖后当前选中的用户预设已不在列表中，选中态回到「自定义」，**当前声音曲线不强制清零**。

## 实现要点

| 符号 | 作用 |
|---|---|
| `UserEqPresets.toExportJson` / `fromExportJson` | 文件 JSON（纯 JVM 手写解析，不引 org.json） |
| `UserEqPresets.importPresets` + `ImportMode` | 合并 / 覆盖 |
| `AudioFx.exportUserPresetsJson` / `importUserPresetsFromJson` | 读写 Prefs 列表 |
| `EqSheet`「我的」区 | SAF + 确认对话框 |

单测：`UserEqPresetsTest`（JSON 往返、转义、合并/覆盖、满员跳过）。

## 版本

已随 **≥ v1.2.37** 出包（原文档占位 v1.2.36，**无此 tag**）。
