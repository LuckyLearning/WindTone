# 功能：软 EQ（E5 Prefs + 诊断收口）

> 状态：**main 未发版 / E5**（占位版本可跟 v1.2.38，**未改 versionName**）  
> 日期：2026-10-31（Asia/Shanghai）  
> Spike 结论：`docs/spike-软EQ.md`（有条件 GO）  
> 代码：`playback/softEq/` + `diag/SoftEqProbe.kt`  
> `SoftEqSpike.ENABLED`：**false**（禁止默认强开）

## 做什么

把 Spike 里的 Media3 `AudioProcessor` 软 EQ **接到主播放路径**，在硬 EQ 报 `DEVICE_REJECTED` 时门控，并在 **SOFT_ACTIVE** 时按与硬 EQ **同一套曲线**滤波；E5 补齐 **Prefs 键文档**、**诊断包 / 诊断页可读相位与曲线摘要**。

| 项 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- |
| 挂进 `PlaybackService` → `DefaultAudioSink.setAudioProcessors` | ✅ | ✅ | ✅ | ✅ | ✅ |
| 运行时 `SoftEqPath.setEnabled` 开/关 | ✅（默认关，直通） | ✅ | ✅ | ✅ | ✅ |
| 硬 EQ `DEVICE_REJECTED` 挂钩 | ✅（只记原因） | ✅ + 门控 | ✅ | ✅ | ✅ |
| 自动候选（REJECTED → candidate） | — | ✅ | ✅ | ✅ | ✅ |
| 用户确认开关（Prefs `soft_eq_user_confirm`） | — | ✅ 骨架 | ✅ | ✅ EqSheet | ✅ + 诊断可读 |
| 硬 EQ 恢复 → 强制关软路径（不双开） | — | ✅ | ✅ | ✅ | ✅ |
| `SoftEqSpike.ENABLED` 强开覆盖 | false | false | false | false | false |
| 曲线桥接（E3） | — | — | ✅ SoftEqCurve | ✅ | ✅ |
| 设置文案 / 确认入口（E4） | — | — | — | ✅ SoftEqCopy | ✅ |
| Prefs 键表 + SoftEqProbe 诊断（E5） | — | — | — | — | ✅ |
| 真机 A/B（E6） | — | — | — | — | 清单见下（**未验车**） |

## Prefs 键

| 键 | 类型 | 默认 | 属性 | 说明 |
| --- | --- | --- | --- | --- |
| `soft_eq_user_confirm` | Bool | **false** | `Prefs.softEqUserConfirm` | 用户确认走软路径。仅 `DEVICE_REJECTED` 候选时为 true 才 `SOFT_ACTIVE`；硬恢复关软、**偏好保留** |

写入路径：EqSheet 确认开关 → `SoftEqPath.setUserConfirmSoft` → Prefs + `SoftEqGate`。  
`AudioFx.init` → `SoftEqPath.bindPrefs` 读回。键表亦见 `docs/AI开发指南.md` §5.2。

## 门控策略（SoftEqGate）

1. **硬优先**：硬 EQ 可用 → `HARD_ACTIVE`，软路径强制关。  
2. **REJECTED → 候选**：`DEVICE_REJECTED` → `CANDIDATE`。  
3. **用户确认才开软**：`softEqUserConfirm=true` 且候选 → `SOFT_ACTIVE`。默认 **false**。  
4. **不双开**：硬恢复关软；`userConfirmSoft` 保留。  
5. `SESSION_PENDING` / `NONE`：不成候选。

相位：`IDLE` → `CANDIDATE` / `SOFT_ACTIVE` ↔ `HARD_ACTIVE`。

## 曲线桥接（SoftEqCurve / SoftEqPath）

软中心：`SoftEqEngine.DEFAULT_CENTERS_HZ`（60 / 230 / 910 / 3600 / 14000）。

| 输入 | 行为 |
| --- | --- |
| 内置预设 id | `EqPresets.curveFor` 落到软中心 |
| CSV 段数 = 5 | 夹取写入 |
| CSV 段数 ≠ 5 + 记得硬布局 | log 频率 remap |
| 否则 | **平直** |

曲线常驻 processor；仅 `SOFT_ACTIVE` 时真正滤波。

## EqSheet 软路径文案（E4）

硬拒绝且 `CANDIDATE` / `SOFT_ACTIVE` 时显示「软件均衡（软 EQ）」块（`SoftEqCopy` + 确认开关）。  
`HARD_ACTIVE` / `IDLE` **不展示**。不可用主文案仍走 `eq_unavailable_device`——**勿谎称硬 EQ 仍可用**。

## 诊断（E5）

### 诊断包 `[软EQ]` 段

导出「诊断包（完整）」时追加只读摘要（`diag/SoftEqProbe.kt` → `DiagnosticsReport.Facts.softEqBlock`）：

- **相位**：`IDLE` / `CANDIDATE` / `SOFT_ACTIVE` / `HARD_ACTIVE`（附中文）
- **Prefs.softEqUserConfirm** 与内存确认（不一致会标明）
- **处理器启用 / 候选**、`SoftEqSpike.ENABLED`
- **硬不可用原因**
- **曲线摘要**：`Source` + preset id + 五段增益 mB + 可听否 + 中心 Hz

### 诊断页

「关于与诊断」→ **软 EQ 状态** 行：实时相位 · 确认 · 曲线源；点开复制短报并弹窗。完整包副标题亦列出「软EQ」。

不含凭据；不打印 URL/token。

## 怎么用（开发）

```kotlin
SoftEqPath.setUserConfirmSoft(true)   // Prefs；候选时才真正开软
SoftEqPath.onHardEqUnavailable(DEVICE_REJECTED)
SoftEqPath.onHardEqAvailable()
SoftEqPath.syncCurve(presetId, bandsCsv)
SoftEqProbe.buildReport(context)      // E5 诊断短报
```

## 验证

```bat
set JAVA_HOME=E:\workspace\jdk\jdk-17
gradlew.bat :app:compileDebugKotlin
gradlew.bat :app:testDebugUnitTest --tests com.luckylearning.windtone.playback.softEq.* --offline
gradlew.bat :app:testDebugUnitTest --tests com.luckylearning.windtone.diag.SoftEqProbeTest --offline
gradlew.bat :app:testDebugUnitTest --tests com.luckylearning.windtone.diag.DiagnosticsReportTest --offline
gradlew.bat :app:testDebugUnitTest --tests com.luckylearning.windtone.playback.StereoBalanceTest --offline
gradlew.bat :app:testDebugUnitTest --tests com.luckylearning.windtone.ui.SoftEqCopyTest --offline
`

边界单测（门控确认前后 / 硬恢复关软 / 平衡 ±1·0 / 空预设曲线）已覆盖于 SoftEqGateTest / SoftEqPathTest / SoftEqCurveTest / StereoBalanceTest。``

## E6 真机 A/B 清单（**未验车，勿勾选已完成**）

目标：在 `DEVICE_REJECTED` 车机上确认软路径可听、且不与硬 EQ 双开。

| # | 步骤 | 期望 | 结果 |
| --- | --- | --- | --- |
| 1 | 装当前 main 包；确认 `SoftEqSpike.ENABLED=false`、未改 versionName | 安装成功 | ☐ |
| 2 | 打开均衡器；若硬 EQ 可用 → 相位应 HARD_ACTIVE，诊断页无「软路径启用」 | 软块不抢硬面板 | ☐ |
| 3 | 在硬拒绝车机（或模拟 DEVICE_REJECTED）看 EqSheet「软件均衡」块 | 文案为候选、确认默认关 | ☐ |
| 4 | 开确认开关 → 放熟悉曲目；诊断包相位 = SOFT_ACTIVE、处理器启用=开 | 听感相对平直有差 | ☐ |
| 5 | 改预设 / 自定义频段 → 软路径听感跟随；诊断曲线摘要更新 | 与硬预设同向 | ☐ |
| 6 | 关确认 → 回 CANDIDATE、听感回平直；Prefs 仍为关 | 不残留滤波 | ☐ |
| 7 | 硬 EQ 恢复可用 → HARD_ACTIVE、软强制关；Prefs 确认若曾开则保留 | 不双开 | ☐ |
| 8 | 杀进程重开：确认偏好仍在；再 REJECTED 可直接 SOFT_ACTIVE | Prefs 持久 | ☐ |
| 9 | 复制诊断包 / 软 EQ 短报回传 | 含相位·confirm·曲线，无 token | ☐ |

**通过标准**：4–7 听感与诊断一致，且无硬+软双开。失败则保持 ENABLED=false，记现象到本表「结果」列，再开修复，不假装已验。

## 发版

代码合入 main，**不打 tag**（GitHub Actions 额度满）。恢复额度后再发版。占位可记 v1.2.38，**未改 `versionName`**。