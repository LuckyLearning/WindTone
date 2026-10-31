# 诊断包线程 / fd 对照收口

## 背景

长稳对照（1.2.40/42 车机包）要看线程数、fd、VmRSS。旧缺口：

- fd 告警写死 **1024**，厂商软限不同时误报；
- 诊断行只有打开数，**看不到软限 / FDSize**，跨机不好比；
- 崩溃快照 `readCpu()` 会扫全部 `task/*/stat`，冷路径偏重。

## 本轮

| 点 | 做法 |
| --- | --- |
| 软限 | `parseFdSoftLimit(/proc/self/limits)` → `Proc.fdLimit`；展示 `fds N/L` |
| FDSize | 从 `status` 解析表容量；文案标明「不是打开数」 |
| 稳定键 | `proc_key\tthreads=…\tfds=…\tfd_limit=…\tfd_size=…\tvm_rss_kb=…\tvm_hwm_kb=…` 便于 grep 对照 |
| 告警 | `fdNearLimit`（≥90% 软限）、`threadsElevated`（>200）与 CrashLog 同口径 |
| 崩溃轻读 | `readCpu(includeTopThreads=false)` 跳过线程榜扫盘 |

## 性能口径

- 仍只在导出诊断包 / 崩溃快照时读 `/proc`，**无常驻采样线程**。
- 多读一次 `limits`（小文本）；崩溃路径少 N 次 task stat。
- 不碰 SoftEq / 播放热路径。

## 单测

`ProcStatsTest`：FDSize、limits、fdNearLimit、stableKeyLine。
