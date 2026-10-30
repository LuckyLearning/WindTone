# 功能：软 EQ（E1 主路径骨架）

> 状态：**main 未发版 / E1**  
> 日期：2026-10-31（Asia/Shanghai）  
> Spike 结论：`docs/spike-软EQ.md`（有条件 GO）  
> 代码：`playback/softEq/`（`SoftEqPath` / `SoftEqAudioProcessor` / …）

## 做什么

把 Spike 里的 Media3 `AudioProcessor` 软 EQ **接到主播放路径**，做成可开关骨架：

| 项 | E1 |
| --- | --- |
| 挂进 `PlaybackService` → `DefaultAudioSink.setAudioProcessors` | ✅ |
| 运行时 `SoftEqPath.setEnabled` 开/关 | ✅（默认关，直通） |
| 硬 EQ `DEVICE_REJECTED` 挂钩 `SoftEqPath.onHardEqUnavailable` | ✅（只记原因） |
| 自动启用（`SoftEqSpike.ENABLED`） | ❌ 仍为 false |
| 门控只在 REJECTED 时切软 EQ（E2） | ❌ 不做 |
| 曲线桥接用户预设/场景（E3） | ❌ 不做 |
| 设置文案（E4） | ❌ 不做 |
| Prefs + 诊断全套（E5） | ❌ 不做 |
| 真机 A/B（E6） | ❌ 不做 |

## 怎么用（开发）

```kotlin
SoftEqPath.setEnabled(true)          // 开（仍需非零增益曲线才有听感）
SoftEqPath.processor.setGainsMb(...) // 或 setCurve；单位 mB，对齐 AudioFx
SoftEqPath.setEnabled(false)         // 关 → processor.isActive=false，PCM 直通
```

硬 EQ 建失败时 `AudioFx.reportFailure` 会调用 `SoftEqPath.onHardEqUnavailable(DEVICE_REJECTED)`。  
当前 `SoftEqSpike.ENABLED=false`，**不会**因此自动打开软 EQ。

与 `#9` 左右声道平衡同属「不依赖 `AudioEffect`」的 PCM 链；软 EQ 在平衡之前。

## 验证

```bat
set JAVA_HOME=E:\workspace\jdk\jdk-17
gradlew.bat :app:compileDebugKotlin
gradlew.bat :app:testDebugUnitTest --tests com.luckylearning.windtone.playback.softEq.*
```

## 发版

代码合入 main；**不打 tag**（GitHub Actions 额度满）。恢复额度后再发版。
