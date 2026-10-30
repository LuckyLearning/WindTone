# -*- coding: utf-8 -*-
from pathlib import Path
ROOT = Path(r"E:\workspace\Android\WindTone")

doc = """---
layout: default
title: 设计 - 队列清空与打乱顺序
---

# 队列抽屉：清空队列 + 打乱顺序（A3 / A4）

**状态**：已落地（本机 `main`，随下一版发版）  
**日期**：2026-10-31（Asia/Shanghai）

## 干了什么

在播放队列抽屉（`QueueSheet`）补两个动作，一次做完：

| 编号 | 按钮 | 行为 |
| --- | --- | --- |
| **A3** | 清空队列 | 二次确认后清空整份播放队列并落库；空队列只提示 |
| **A4** | 打乱顺序 | **改物理队列序**（Fisher–Yates），**不**打开/关闭「随机播放」模式；当前曲跟到新下标并尽量保留进度 |

与「播放模式 → 随机」的区别：随机模式由 Media3 `shuffleModeEnabled` 决定下一首；A4 是把列表本身重排，模式开关不动。

## 入口与交互

- 位置：播放页 → 播放队列抽屉 → 标题下方操作行（与「存为歌单」同行）
- **打乱顺序**：队列 ≥ 2 首才可点；点完 toast「已打乱队列顺序」
- **清空队列**：先点「清空队列」→ 同行变为「取消 / 确认清空」（红色）；确认后 toast「已清空播放队列」
- 车机误触：清空必须点两下，避免一把清空

## 怎么干的（实现笔记）

| 文件 | 职责 |
| --- | --- |
| `playback/QueuePolicies.kt` | `shuffleOrderIndices` 纯函数：置换 + 找回当前下标，可单测 |
| `playback/QueueManager.kt` | `clearQueue` / `shuffleQueueOrder(random)`；清队列走 `setMediaItems(empty)`；打乱后 `prepare`，若原在播则 `play` |
| `playback/PlayerActions.kt` + `PlaybackEngine.kt` | 动作面暴露，UI `by` 委托 |
| `ui/SheetsPlayback.kt` | `QueueSheet` 增加 `onClear` / `onShuffleOrder` + 二次确认态 |
| `ui/MainScreen.kt` | 接到 `playerVm.clearQueue()` / `shuffleQueueOrder()` |
| `QueuePoliciesTest` / `QueueManagerTest` | 空/单曲/置换/清空落库/打乱不碰 shuffle 开关 |

### 关键约束

- **不**改 `shuffleModeEnabled` / `repeatMode`
- 打乱时用当前 `currentPosition` 作为 `setMediaItems` 的 startPosition，减少听感跳变
- 清空会 `setCurrentQueue(empty)` + `notifyQueueSize(0)` + `persistQueue`，与单曲移除同一落库路径
- 文案暂硬编码中文（与抽屉原有「存为歌单」一致）；错开并行 i18n 批次，后续再抽 `strings.xml`

## 验证

```text
./gradlew testDebugUnitTest --tests com.luckylearning.windtone.playback.QueuePoliciesTest
./gradlew testDebugUnitTest --tests com.luckylearning.windtone.playback.QueueManagerTest
```

手工：开队列 → 打乱后当前曲仍在播、模式图标仍是「顺序/循环」而非「随机」→ 清空需确认 → 杀进程再开队列仍为空（或为打乱后的序）。

## 明确不做

- 不做成「一键开随机播放模式」
- 不做拖拽排序（仍用上下箭头）
- 不在本项做 i18n 抽字
"""
(ROOT / "docs/设计-队列清空与打乱.md").write_text(doc, encoding="utf-8", newline="\n")
print("wrote design doc")

# Update 功能说明.md queue bullet
feat = (ROOT / "docs/功能说明.md").read_text(encoding="utf-8")
old = "- **播放队列抽屉**：查看队列、点击跳转、上移 / 下移排序、移除单曲"
# try variants
import re
m = re.search(r"- \*\*播放队列抽屉\*\*：[^\n]+", feat)
if not m:
    raise SystemExit("功能说明 queue line missing")
print("old feat:", m.group(0))
new = "- **播放队列抽屉**：查看队列、点击跳转、上移 / 下移排序、移除单曲、**打乱顺序**（改物理序，非随机模式）、**清空队列**（二次确认）、存为歌单"
feat2 = feat[:m.start()] + new + feat[m.end():]
(ROOT / "docs/功能说明.md").write_text(feat2, encoding="utf-8", newline="\n")
print("updated 功能说明")
