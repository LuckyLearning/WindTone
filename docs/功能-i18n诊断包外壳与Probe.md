---
title: 功能 · i18n 诊断包外壳 + SoftEq/默认音乐 Probe（第 24 批）
---

# i18n 诊断包外壳 + SoftEq/默认音乐 Probe（第 24 批）

把 **DiagnosticsReport** 用户可见外壳，以及 **SoftEqProbe** / **DefaultMusicProbe** 正文，收进既有 i18n 体系：

- `res/values/strings.xml` = 英文兜底
- `res/values-zh/strings.xml` = 中文

与第 1–23 批同一套约定。详见 `docs/AI开发指南.md` § i18n 第 24 批。

## 覆盖范围

### `dr_*`（DiagnosticsReport 外壳）

- 标题 / 结束线 / 采集时间
- 分段头：环境 / 权限 / 播放 / 悬浮窗 / 曲库 / 事件日志 / 一键取证
- 行标签：应用、mapping、versionCode、签名、系统、ABI、屏幕、内存/存储、权限行、曲目/状态/模式/歌词、悬浮窗开关与样式、曲库计数与扫描
- 是/否、开/关、（无）/（空）占位
- 音源 scheme 本地/在线/未知
- 播放质量附加行：无缝（历史死键）/ 跳过静音 / 片头片尾跳过

嵌套块（memory / scene / softEq / defaultMusic / performance / playbackQuality 正文 / foreground / forensics 脚本）仍由各自 Probe 产出；本批只 i18n 外壳与下列两个 Probe。

### `sep_*`（SoftEqProbe）

- 段头、相位/Prefs/内存确认/处理器/Spike/硬原因/曲线/中心Hz/说明
- 相位四态说明、硬三态说明、曲线格式、开/关
- **Prefs 键名 / 枚举名 / SoftEqSpike.ENABLED 字面量保持机器可读**；只译用户可见 gloss

### `dmp_*`（DefaultMusicProbe）

- 段头、四条 intent 依据行、命中/未命中/失败、脚注三行

## 刻意不改

- SoftEq 门控与滤波行为（SoftEqGate / SoftEqPath / SoftEqSpike）
- 播放内核 / Media3 行为
- MemoryProbe / SceneProbe / PlaybackQualityProbe / PerfMarks / ForensicsScript 正文（下一批候选）
- 诊断字段机器可读键名语义

## 验证

- `./gradlew :app:compileDebugKotlin --offline`
- `SoftEqProbeTest`、`DiagnosticsReportTest`（Robolectric `zh-rCN` + `AppStrings.init`）

## 下一批候选

- ✅ 第 25 批已覆盖 Memory/Scene/PlaybackQuality/PerfMarks/Forensics（见 `功能-i18n诊断Probe扫尾.md`）
- 扫尾扫仓 / 车上 §5·E6 / 产品项
