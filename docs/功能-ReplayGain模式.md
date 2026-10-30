---
layout: default
title: ReplayGain 曲目 / 专辑模式
---

# ReplayGain 曲目 / 专辑模式（N6）

> 版本：发版时写入版本历史（预计 v1.2.27，紧随 H5/i18n 的 1.2.26）

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
