# 歌单 M3U 导入（D2）

与「导出为 M3U」对称：用户从文件选择器打开 `.m3u` / `.m3u8`，按本机曲库匹配后新建歌单。

## 入口

- 歌单页 → 导入 M3U（`MainScreen.m3uImportLauncher` → `LibraryViewModel.importM3u` → `M3uStore.importM3U`）

## 匹配顺序

1. 完整 `Song.path`
2. 路径 / URI 末段文件名
3. `#EXTINF` 标题（去掉「歌手 - 」前缀后与 `Song.title` 忽略大小写对齐）

相对路径**不**擅自拼盘符；以导出清单里的绝对 path / content URI 为主。

## 性能（铁律）

| 约束 | 实现 |
| --- | --- |
| 不挡播放热路径 | `withContext(Dispatchers.IO)` 读盘与匹配 |
| 按需触发 | 仅用户点导入；无常驻线程 / 大缓存 |
| 防 OOM | `MAX_BYTES` 1 MiB；`MAX_LINES` 2 万；`MAX_MATCHED` 2 千 |
| 索引 | `HashMap` O(1)；禁止对整库逐行 `endsWith` |

超限：过大拒读（`m3u_too_large`）；行/命中截断仍建歌单并 Toast（`m3u_import_truncated`）。

## 文案

`m3u_*`（含 `m3u_too_large` / `m3u_import_truncated`）；用户可见走 `AppStrings` + `ifEmpty`。

## 单测

`M3uLocalImportTest`：路径/文件名/EXTINF、去重、命中上限、读盘上限。
