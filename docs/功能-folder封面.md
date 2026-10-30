---
layout: default
title: 功能 · 同目录 folder 封面
---

# 同目录 folder.jpg 封面回退（A6）

> 落地日：2026-10-31（Asia/Shanghai）
> 版本：随 **v1.2.23** 发版（若 H6 的 v1.2.22 尚未发出，发版号以当时 main 下一档为准）

## 干了什么

曲目 / 专辑封面在**内嵌图缺失**时，回退读取音频**同目录**下的常见封面文件：

1. `folder.jpg`（及 `Folder.JPG` 等大小写变体）
2. `cover.jpg`
3. `AlbumArt.jpg`

有内嵌封面时**完全不碰**文件夹文件，现有管线（内存位图 → 磁盘 `cacheDir/covers` → 解码）不变。

## 怎么干的

1. 新增纯逻辑 `FolderCover`：`pickName` / `resolveFile` / `readBytes`。
2. `CoverRepository.loadRaw`：`embeddedPicture` 为空时调用 `FolderCover.readBytes(song)`。
3. 目录来源与侧车歌词一致：优先 `Song.path` 的父目录，其次可作为目录列出的 `Song.folderPath`；`REMOTE` 与无真实路径的条目跳过。
4. 单测 `FolderCoverTest` 钉优先级、大小写、路径 / folderPath、空文件、远程跳过。

## 缓存与失效

| 场景 | 行为 |
|---|---|
| 内嵌存在 | 只用内嵌；文件夹文件被忽略 |
| 内嵌缺失、目录有 folder.jpg | 字节写入与内嵌相同的 `stableKey` 原始缓存，并照常写磁盘解码缓存 |
| 两者皆无 | 负缓存空字节（既有行为），同进程不再重试 |
| 事后才放入 folder.jpg | 需「设置 → 曲库与存储 → 清空封面缓存」或杀进程后才会出现 |
| 替换同名 folder.jpg | 同上；不做 mtime 监视（与内嵌被外部改写同口径） |

## 怎么验

1. 准备无内嵌封面的 flac/mp3，同目录放 `folder.jpg`，扫入曲库 → 列表 / 播放页应显示该图。
2. 同目录同时有 `cover.jpg` 与 `folder.jpg` → 应显示 `folder.jpg`。
3. 有内嵌封面且同目录另有 `folder.jpg` → 仍显示内嵌。
4. `./gradlew :app:testDebugUnitTest --tests com.luckylearning.windtone.data.FolderCoverTest`
5. `./gradlew :app:testDebugUnitTest --tests com.luckylearning.windtone.data.CoverCacheTest`

## 不做什么

- 不扫 `folder.png` / `front.jpg` 等扩展名单（需要时再加候选）。
- 不做 SAF 无路径条目的 DocumentFile 列目录（与歌词侧车同限制）。
- 不按专辑聚合「只解一次 folder.jpg 给整专」——仍按曲缓存，磁盘 LRU 会自然去重内容以外的键。