# 功能：软 EQ（E2 门控 DEVICE_REJECTED）

> 状态：**main 未发版 / E2**  
> 日期：2026-10-31（Asia/Shanghai）  
> Spike 结论：`docs/spike-软EQ.md`（有条件 GO）  
> 代码：`playback/softEq/`（`SoftEqGate` / `SoftEqPath` / `SoftEqAudioProcessor` / …）

## 做什么

把 Spike 里的 Media3 `AudioProcessor` 软 EQ **接到主播放路径**，并在硬 EQ 报 `DEVICE_REJECTED` 时做门控：

| 项 | E1 | E2 |
| --- | --- | --- |
| 挂进 `PlaybackService` → `DefaultAudioSink.setAudioProcessors` | ✅ | ✅ |
| 运行时 `SoftEqPath.setEnabled` 开/关 | ✅（默认关，直通） | ✅ |
| 硬 EQ `DEVICE_REJECTED` 挂钩 | ✅（只记原因） | ✅ + 门控 |
| 自动候选（REJECTED → candidate） | ❌ | ✅ |
| 用户确认开关骨架（Prefs `softEqUserConfirm`） | ❌ | ✅（默认关，不自动开听感） |
| 硬 EQ 恢复 → 强制关软路径（不双开） | ❌ | ✅ `onHardEqAvailable` |
| `SoftEqSpike.ENABLED` 强开覆盖 | false | false（仅调试强开） |
| 曲线桥接用户预设/场景（E3） | ❌ | ❌ |
| 设置文案（E4） | ❌ | ❌ |
| Prefs + 诊断全套（E5） | ❌ | ❌（仅 confirm 键） |
| 真机 A/B（E6） | ❌ | ❌ |

## 门控策略（SoftEqGate）

1. **硬优先**：硬 EQ 可用（`bindEffects` 成功）→ `HARD_ACTIVE`，软路径强制关。  
2. **REJECTED → 候选**：`DEVICE_REJECTED` → `CANDIDATE`；界面可提示「可走软 EQ」（文案 E4）。  
3. **用户确认才开软**：`softEqUserConfirm=true` 且处于候选 → `SOFT_ACTIVE`，`processor` 启用。默认 **false**（只候选、不改听感）。  
4. **不双开**：硬恢复时关软；`userConfirmSoft` 保留，下次再 REJECTED 可直接重回 `SOFT_ACTIVE`。  
5. `SESSION_PENDING` / `NONE`：不成候选，软保持关。

相位：`IDLE` → `CANDIDATE` / `SOFT_ACTIVE` ↔ `HARD_ACTIVE`。

## 怎么用（开发）

```kotlin
SoftEqPath.setUserConfirmSoft(true)   // Prefs 骨架；候选时才会真正开软
SoftEqPath.onHardEqUnavailable(DEVICE_REJECTED)  // AudioFx.reportFailure 已接
SoftEqPath.onHardEqAvailable()        // AudioFx.bindEffects 成功已接
SoftEqPath.setEnabled(true)           // 仍可用的直控（测试/开发）；产品路径走 confirm
```

`AudioFx.init` 会 `SoftEqPath.bindPrefs`，读出 `Prefs.softEqUserConfirm`。

## 验证

```bat
set JAVA_HOME=E:\workspace\jdk\jdk-17
gradlew.bat :app:compileDebugKotlin
gradlew.bat :app:testDebugUnitTest --tests com.luckylearning.windtone.playback.softEq.*
```

## 发版

代码合入 main，**不打 tag**（GitHub Actions 额度满）。恢复额度后再发版。
