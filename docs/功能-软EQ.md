# 功能：软 EQ（E3 曲线桥接）

> 状态：**main 未发版 / E3**（占位版本可跟 v1.2.37，**未改 versionName**）  
> 日期：2026-10-31（Asia/Shanghai）  
> Spike 结论：`docs/spike-软EQ.md`（有条件 GO）  
> 代码：`playback/softEq/`（`SoftEqCurve` / `SoftEqGate` / `SoftEqPath` / `SoftEqAudioProcessor` / …）

## 做什么

把 Spike 里的 Media3 `AudioProcessor` 软 EQ **接到主播放路径**，在硬 EQ 报 `DEVICE_REJECTED` 时门控，并在 **SOFT_ACTIVE** 时按与硬 EQ **同一套曲线**滤波：

| 项 | E1 | E2 | E3 |
| --- | --- | --- | --- |
| 挂进 `PlaybackService` → `DefaultAudioSink.setAudioProcessors` | ✅ | ✅ | ✅ |
| 运行时 `SoftEqPath.setEnabled` 开/关 | ✅（默认关，直通） | ✅ | ✅ |
| 硬 EQ `DEVICE_REJECTED` 挂钩 | ✅（只记原因） | ✅ + 门控 | ✅ |
| 自动候选（REJECTED → candidate） | — | ✅ | ✅ |
| 用户确认开关骨架（Prefs `softEqUserConfirm`） | — | ✅（默认关，不自动开听感） | ✅ |
| 硬 EQ 恢复 → 强制关软路径（不双开） | — | ✅ `onHardEqAvailable` | ✅ |
| `SoftEqSpike.ENABLED` 强开覆盖 | false | false（仅调试强开） | false |
| 曲线桥接用户预设/自定义增益（E3） | — | — | ✅ `SoftEqCurve` + `SoftEqPath.syncCurve` |
| 设置文案（E4） | — | — | — |
| Prefs + 诊断全套（E5） | — | ✅（仅 confirm 键） | ✅（confirm + 曲线状态） |
| 真机 A/B（E6） | — | — | — |

## 门控策略（SoftEqGate）

1. **硬优先**：硬 EQ 可用（`bindEffects` 成功）→ `HARD_ACTIVE`，软路径强制关。  
2. **REJECTED → 候选**：`DEVICE_REJECTED` → `CANDIDATE`；界面可提示「可走软 EQ」（文案 E4）。  
3. **用户确认才开软**：`softEqUserConfirm=true` 且处于候选 → `SOFT_ACTIVE`，`processor` 启用。默认 **false**（只候选、不改听感）。  
4. **不双开**：硬恢复时关软；`userConfirmSoft` 保留，下次再 REJECTED 可直接重回 `SOFT_ACTIVE`。  
5. `SESSION_PENDING` / `NONE`：不成候选，软保持关。

相位：`IDLE` → `CANDIDATE` / `SOFT_ACTIVE` ⇄ `HARD_ACTIVE`。

## 曲线桥接（SoftEqCurve / SoftEqPath）

软路径中心频率固定为 `SoftEqEngine.DEFAULT_CENTERS_HZ`（60 / 230 / 910 / 3600 / 14000），与 `EqPresets` 锚点一致。

| 输入 | 行为 |
| --- | --- |
| 内置预设 id（`EqPresets.byId`） | `EqPresets.curveFor` 落到软中心（精确） |
| 自定义 / 用户 `eqBands` CSV，段数 = 5 | 直接夹取写入 |
| CSV 段数 ≠ 5，且记得硬 EQ 频段布局 | log 频率轴插值 remap 到软中心 |
| CSV 段数不对且无硬布局 | **平直**（避免错形） |

`AudioFx` 在 `bindEffects` / 改预设·频段·方案·恢复默认 / `pushValuesToEffects` 时调用 `SoftEqPath.syncCurve`（或 `onHardBandLayout`）；`onHardEqUnavailable` 也会从 Prefs 刷新曲线。曲线常驻 processor；仅 `SOFT_ACTIVE` 时真正滤波。

## 怎么用（开发）

```kotlin
SoftEqPath.setUserConfirmSoft(true)   // Prefs 骨架；候选时才会真正开软
SoftEqPath.onHardEqUnavailable(DEVICE_REJECTED)  // AudioFx.reportFailure 已接
SoftEqPath.onHardEqAvailable()        // AudioFx.bindEffects 成功已接
SoftEqPath.syncCurve(presetId, bandsCsv)  // E3：硬曲线 → 软 processor
SoftEqPath.applyBuiltinPreset("rock")     // 或直接套内置
SoftEqPath.setEnabled(true)           // 仍可用的直控（测试/开发）；产品路径走 confirm
```

`AudioFx.init` 会 `SoftEqPath.bindPrefs`，读出 `Prefs.softEqUserConfirm` 并同步曲线。

## 验证

```bat
set JAVA_HOME=E:\workspace\jdk\jdk-17
gradlew.bat :app:compileDebugKotlin
gradlew.bat :app:testDebugUnitTest --tests com.luckylearning.windtone.playback.softEq.* --offline
```

## 发版

代码合入 main，**不打 tag**（GitHub Actions 额度满）。恢复额度后再发版。占位版本可记 v1.2.37，**未改 `versionName`**。
