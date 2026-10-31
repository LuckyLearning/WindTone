# 元数据 Prefs 侧缓存裁剪（G1/G2/G3 perf）

## 背景

`genre` / `year` / `albumArtist` / `discNumber` 不进 Room，落在 `music_player_prefs` 的 JSON
（`GenreYearCache` / `AlbumArtistCache` / `DiscNumberCache`）。旧实现：

- **从不删键** —— 歌曲移出曲库后条目仍在，长期膨胀；
- **每次 `load()` 全量 `JSONObject` 解析** —— `applyLibrary` 一次 put×3 + overlay×3，冷路径重复六次。

## 本轮

| 点 | 做法 |
| --- | --- |
| prune | `syncFromLibrary` = `putFromSongs` + `retainOnly(当前曲库 stableKey)` |
| 硬上限 | `MetadataPrefsPolicy.MAX_ENTRIES` = 20 000；超限按键名字典序截断 |
| memo | 进程内 `@Volatile` 解析结果；`save` 时更新，避免重复解析 |
| 调用点 | `LibraryCore.applyLibrary` IO 块改调 `syncFromLibrary` |

## 性能口径

- 不增常驻线程；不碰播放热路径 / SoftEq。
- SharedPreferences 字符串随删歌回落；解析次数从「每 apply 六次」降为「冷读一次 + 变更写回」。
- 上限只挡异常 JSON，正常车机曲库远低于此。

## 单测

- `MetadataPrefsPolicyTest`：retain / 清空 / 硬上限截断
- `GenreYearCacheTest`：写入后 prune 陈旧键、overlay 仍可读

## 未改

- Room schema、扫描源、封面 / SoftEq / 播放内核
