---
layout: default
title: ReplayGain 曲目 / 专辑模式
---

# ReplayGain 曲目 / 专辑模式（N6）

> 版本：标签优先音量平衡自 **v1.2.14**；曲目/专辑模式（N6）自 **v1.2.29**。规划 **C3 已收口**（读标签 + 衰减制应用 + 无标签回落 RMS）。

## 做什么

音量平衡（M3）开着时，可在均衡器「播放过渡」里切换：

| 模式 | 用途 | 标签优先级 |
|---|---|---|
| **曲目增益**（默认） | 歌单混搭，各曲独立拉平 | TRACK → iTunes → ALBUM → R128 |
| **专辑增益** | 整专连听，保留专内相对响度 | ALBUM → TRACK → iTunes → R128 |

选择写入 Prefs（`replay_gain_mode`），杀进程仍在。默认 `TRACK`，与 v1.2.14 起行为一致。

## 无标签时回退

1. 偏好侧标签缺失 → 用另一侧（或按曲等价标签：iTunes / R128）。
2. 文件完全无增益标签 → 回落既有 RMS 估算（`LoudnessScan` / `TrackLoudnessAnalyzer`）。
3. 正增益仍只走衰减制（`player.volume` 抬不了就不动），不改均衡器「响度增强」。

## 实现要点

- `ReplayGainMode` + `ReplayGainTags.Gains.preferredDb(mode)`
- `TrackVolume` 双缓存：`replaygain_track_cache.txt` / `replaygain_album_cache.txt`；一次读标签两侧写入，切模式不重读文件。
- UI：`EqSheet` 播放过渡，音量平衡开启后显示两枚 FilterChip。

## 不是什么

不是均衡器「响度增强」（`eqLoudness` / `LoudnessEnhancer`）。本功能只乘播放器音量。

## 格式覆盖

| 容器 | 标签 |
| --- | --- |
| ID3（MP3 等） | `TXXX`：`REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_ALBUM_GAIN` |
| FLAC / Ogg | Vorbis：同上；Opus `R128_TRACK_GAIN`（换算到 RG 参考） |
| MP4 / M4A | `iTunNORM`（iTunes Sound Check） |
| APEv2 | `REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_ALBUM_GAIN`（常见于 ape/mpc 尾标） |

读文件头 256 KiB + 尾 64 KiB（`ReplayGainReader`），不整轨解码。

## 资源 / 性能铁律（最高优先级）

不论功能怎么加，**资源占用与性能优先于功能完整度**：

| 约束 | 现状 |
| --- | --- |
| **默认可关** | 「音量平衡」总开关（`Prefs.loudnessBalance` / 均衡器播放过渡）；关则 `loudnessFactor=1`，**不读标签、不排队 RMS** |
| **热路径零阻塞** | 切歌 / `applyLoudnessFor` 只读内存侧 `LoudnessCache`；`ReplayGainReader` 只在既有 `analyzeScope`（IO、`limitedParallelism(1)`）后台跑 |
| **无标签 ≈ 现网** | 未读完标签前若已有 RMS 缓存则用之，否则音量 1.0；读完无标签写 `TAG_ABSENT` 再按需排队 RMS——与关标签前行为一致 |
| **不新增常驻线程** | 复用 `TrackVolume.analyzeScope`，不另起线程池 |
| **缓存克制** | 仅两个小文本缓存（`replaygain_track_cache.txt` / `replaygain_album_cache.txt`）+ 既有 `loudness_cache.txt`；读文件头 256KiB + 尾 64KiB，**不整轨解码** |

本轮 C3 收口：**不改播放内核 / SoftEq**；只对齐文档并补 APEv2 解析单测。
