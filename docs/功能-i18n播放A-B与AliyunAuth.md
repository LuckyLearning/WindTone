---
title: 功能 · i18n 播放 A-B / 逐句 Toast + AliyunAuth 错误句（第 22 批）
---

# i18n 播放 A-B / 逐句 Toast + AliyunAuth 错误句（第 22 批）

把 **PlaybackEngine** A-B / 逐句循环 / 紧邻悬浮歌词 Toast，以及 **AliyunAuth / AliyunErrors** 用户可见错误句，收进既有 i18n 体系：

- `res/values/strings.xml` = 英文兜底
- `res/values-zh/strings.xml` = 中文

与第 1–21 批同一套约定。详见 `docs/AI开发指南.md` § i18n 第 22 批。

## 覆盖范围

### `pe_*`（PlaybackEngine）

- `pe_overlay_*`：悬浮歌词启动失败、自动锁定位置
- `pe_ab_*`：设 A / 设 B 校验与成功、清除 A-B
- `pe_lyric_*`：逐句循环进出、无时间轴

### `aa_*` / `aa_err_*`（AliyunAuth）

- 粘贴空、缺刷新令牌、各路径失败标签、HTTP 空响应/非 JSON
- `AliyunErrors.hint`：错误码 → 原因 + 下一步（`UserFacingCopy.withNext`）
- API 无 code 时保留服务端 `message` 原文

## 刻意不改

- SoftEq / 播放内核 / 云协议行为
- `ParallelMsg` 等内部匹配键

## 验证

- `./gradlew :app:compileDebugKotlin --offline`
- `:app:testDebugUnitTest --tests ...AliyunAuthTest`（及可选 AbLoopTest）

## 下一批候选

- 设置搜索词
- 排障正文
- 其它仍硬编码的引擎/云盘 Toast
