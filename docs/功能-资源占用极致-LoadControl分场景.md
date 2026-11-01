---
layout: default
title: 资源占用极致 · LoadControl 分场景
---

# 资源占用极致 · LoadControl 分场景（B1）

**前提**：车机主路径 = 歌词悬浮窗常开。本批只砍播放器预读垫量，**不**改悬浮窗跟词、SoftEq 滤波核、交叉淡入。

## 改动

| 场景 | scheme | min / max / playback / afterRebuffer |
| --- | --- | --- |
| **远程**（默认） | `http` / `https`；空、未知、自定义 | **30s / 120s / 4s / 8s**（与改前一致） |
| **本地** | `file` / `content` / `android.resource` / `rawresource` / `asset` / `filedescriptor`… | **8s / 24s / 1.5s / 3s** |

实现：`LoadControlScenePolicy`（可测纯规则）+ `SceneLoadControl`（双 `DefaultLoadControl` 共享 `DefaultAllocator`）。切歌监听 + LoadControl 决策路径双通道更新场景；歧义一律远程，避免卡顿。

## 性能

- 本地曲：堆上预读垫量约降到原先 1/4～1/5（时长维度）。
- 网盘/弱网：行为与改前相同。
- 无新增采样线程；不碰 SoftEq / 悬浮窗 ticker。

## 验证

- 单测：`LoadControlScenePolicyTest`（本地 / 远程 / 常量约束）。
- 本机：`assembleDebug` + 上述单测。
- 真机建议：本地无损连播看 RSS；弱网网盘 A/B 确认仍不中途卡。

## 下一步

**A6/B2/B3**：关路径拆链（未启用音效处理器从 AudioSink 链卸下；勿动 SoftEq 滤波核本身）。
