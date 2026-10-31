---
title: 功能 · i18n 诊断 Probe 扫尾（第 25 批）
---

# i18n 诊断 Probe 扫尾（第 25 批）

把诊断包内剩余用户可见 Probe/脚本正文收进既有 i18n 体系：

- `res/values/strings.xml` = 英文兜底
- `res/values-zh/strings.xml` = 中文

与第 1–24 批同一套约定。详见 `docs/AI开发指南.md` § i18n 第 25 批。

## 覆盖范围

| 前缀 | 模块 |
| --- | --- |
| `mp_*` | MemoryProbe 内存画像段外壳与判读句 |
| `scn_*` | SceneProbe 场景段（档位 gloss / 冷启动 / stash 摘要） |
| `pq_*` | PlaybackQualityProbe 播放质量段 |
| `pf_*` | PerfMarks 性能段 |
| `fs_*` | ForensicsScript 注释与短提示（adb 命令保持英文） |

## 刻意不改

- SoftEq 门控 / 滤波 / 播放内核
- **ProcStats** 桶标签与 failReason（`libc malloc…` / `匿名：dalvik` 等作归类键与匹配前缀，机器可读，保持稳定）
- BootTrace stage 常量字面量、scene id（drive/commute/sleep）

## 验证

- `./gradlew :app:compileDebugKotlin --offline`
- `SceneProbeTest` / `PerfMarksTest` / `PlaybackQualityProbeTest` / `ForensicsScriptTest` / `DiagnosticsReportTest`（zh-rCN + `AppStrings.init`）

## 硬编码面

本批后诊断包用户可见正文已基本抽完；残余主要是 ProcStats 归类键（有意保留）与零散引擎/云盘 Toast。可转：**扫尾扫仓**或车上 §5·E6 / 产品项。
