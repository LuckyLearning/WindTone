# Spike：软 EQ（Media3 AudioProcessor）

> 日期：2026-10-31（Asia/Shanghai）  
> 仓库路径：`app/src/main/java/com/luckylearning/windtone/playback/softEq/`  
> 文档路径：`docs/spike-软EQ.md`  
> 状态：**E3 曲线桥接已接（SoftEqCurve）；SoftEqPath 默认关；SoftEqSpike.ENABLED=false；main 未发版 / E3**

## 1. 问题

车机 `AudioFx` 会话/全局建 `Equalizer` 失败 → `DEVICE_REJECTED`。  
问：Media3 **1.4.1** 的 `AudioProcessor` 能否做出可听多段 EQ？

## 2. 结论：**GO（有条件）**

| 项 | 判定 |
| --- | --- |
| 总判 | **GO** — PCM 峰形多段路径成立，可排期产品化 |
| 合进主路径 | **否** — `SoftEqSpike.ENABLED=false`，未改 `PlaybackService` |
| 发版 | **否** — 无真机听感验收 |
| 硬阻断 | 未发现 |

边界：只做频段 EQ；须 PCM（本 App 本地已是）；与 Sonic 变速共存（用户 processor 在链前）；产品化需硬 EQ 优先、拒绝时才软 EQ。

## 3. 证据

- API：`DefaultAudioSink.Builder.setAudioProcessors` + 自定义 `DefaultRenderersFactory.buildAudioSink`
- 架构：不依赖 `audioSessionId`；与 `TrackVolume` 不冲突
- POC：`SoftEqBiquad` / `SoftEqEngine` / `SoftEqAudioProcessor` / `SoftEqSpike`
- 单测：`SoftEqBiquadTest`（1 kHz +6 dB 中心 RMS ≫ 100 Hz；ENABLED=false）

```bat
gradlew.bat :app:testDebugUnitTest --tests com.luckylearning.windtone.playback.softEq.SoftEqBiquadTest
```

## 4. 怎么验证（真机）

默认听不到。临时按 `SoftEqSpike` KDoc 接线、`setEnabled(true)`、抬高音曲线，在 DEVICE_REJECTED 车机 A/B；验完撤线并保持 ENABLED=false。

## 5. 建议下一步

硬可用走 AudioEffect；仅 REJECTED 启 Soft EQ；设置文案区分；曲线复用用户预设/场景。不做默认合入、不做软混响/crossfade 搭车。

## 6. 改判 NO-GO 条件

强制 offload 从不 queueInput；RenderersFactory 导致播控大回归；车机 SoC 欠载且 5 段仍不行。
