---
layout: default
title: 功能 · Album Artist 合辑浏览
---

# Album Artist 合辑浏览（G2）

曲库侧增加 **合辑艺人**（Album Artist）浏览入口，交互与「歌手 / 专辑 / 流派 / 年代」一致：列表聚合 → 点进组内曲目 → 可播该组全部。

## 口径

- 聚合字段优先曲目元数据里的 **albumArtist**；为空则**回退 artist**。
- Various Artists / 合辑 / 群星等标签**保留**（不当作未知占位丢掉），合辑曲目归到同一组，而不会按各演唱者拆开。
- 无 albumArtist 且无 artist 归「未知合辑艺人」；空库时列表诚实为空。
- 组内排序：专辑 → 曲序 → 标题（与歌手组内类似）。

## 取舍：不升 Room schema

`SongEntity` **没有** albumArtist 列；`MusicDb` 使用 `fallbackToDestructiveMigration()`，升 schema 会清库。

本版做法（对齐 G1）：

| 层 | 做法 |
|---|---|
| 内存 `Song` | 增加可选 `albumArtist: String?`（不入表） |
| 扫描 | MediaStore `ALBUM_ARTIST`（API 30+）；MMR `METADATA_KEY_ALBUMARTIST` |
| 持久 | Prefs JSON 侧缓存 `album_artist_json`（`AlbumArtistCache`，同 `music_player_prefs`） |
| 冷启动 | `applyLibrary`：`putFromSongs` + `overlay`（在 genre/year overlay 之外再盖一层） |

**不**重建数据库。若日后要进 Room，再单独评估 Migration / 接受清库。

## 实现要点

| 符号 | 作用 |
|---|---|
| `AlbumArtist.kt` | `normalizeAlbumArtist` / `effectiveAlbumArtist` / `buildAlbumArtistGroups` |
| `AlbumArtistCache` | Prefs JSON 读写与 overlay |
| `LibraryView.ALBUM_ARTIST` | 抽屉与车机导航随 `entries` 自动出现 |
| `LibraryCore.refreshGroups` / `openGroup` | 分组与进组播放列表 |

单测：`AlbumArtistTest`；抽屉 / 车机标签随枚举扩容已更新。

## 版本

代码已合入并随 **≥ v1.2.37** 出包。
