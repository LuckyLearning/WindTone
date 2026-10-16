---
layout: default
title: 数据库设计 MusicDb
---

# WindTone 数据库开发文档（MusicDb · v4）

> 更新：2026-10-14 · 对应代码：`data/db/`（`MusicDb` version **4**）· `data/Prefs.kt`
> 回答的问题：**现在哪些数据存在哪、为什么这么分、怎么迁、改的时候别碰什么。**

---

## 一、总览：两条存储通道

| 通道 | 引擎 | 定位 | 生命周期 |
|---|---|---|---|
| **Room**（`music_player.db`） | SQLite，`data/db/` | **结构性数据**：成表、成对、按 key 查、做关联、随使用增长 | 跟随 App，只随 schema 版本变化 |
| **SharedPreferences**（`Prefs.kt`） | XML KV | **标量偏好**：单个开关/数值/枚举，同步读、启动即用 | 跟随 App |

**判断标准（本文档的纲）**：数据需要**按 key 查、做关联、随使用无限增长**的，进 Room；
只是一个**全局开关或标量**的，留 Prefs。不存在"为了统一而统一"。

> v4 的分界比 v3 更硬：**凡是"跟着某首歌走"的数据（收藏、统计、歌词偏移、标签编辑、歌单条目、队列条目）
> 一律进 Room 并挂 `songId` 外键**——这样"删一首歌要清哪些地方"由数据库的 CASCADE 负责，
> 代码里再也找不到"漏清一处"的机会。

---

## 二、Room v4 结构（8 表 / 7 DAO）

文件 `music_player.db`（**勿改名**），`exportSchema = true`，schema 快照在
`app/schemas/com.luckylearning.windtone.data.db.MusicDb/`（**入库**，改表后必须跟着提交）。

### 2.1 曲库 `songs` —— 唯一真相（不再是"缓存"）

| 字段 | 说明 |
|---|---|
| `id` | 自增主键。**所有用户数据都指向它** |
| `uri`（唯一索引） | 即 `Song.stableKey`（`uri.toString()`）。**自然键，红线：算法永不改** |
| `source` | `MEDIA_STORE` / `FOLDER` / `FILE`；反序列化遇未知来源丢行 |
| `title/artist/album/albumId` | 媒体元数据（**原始值**，不含用户覆盖） |
| `durationMs/sizeBytes/mimeType/trackNo/displayName` | 音频元数据 |
| `path` | 绝对路径（仅深度扫描 / Android 10 及以下有值） |
| `folderId` | 外键 → `folders.id`，**ON DELETE SET NULL** |
| `folderPath` | 文件夹视图分组键（SAF = 解码文档 ID，深度扫描 = 绝对路径） |
| `mediaStoreId` | 来源内的数字 id（MediaStore 的 `_ID`），仅展示与排障 |
| `lrcUri/dateAdded` | 侧车歌词；加入时间 |
| `lastSeenAt` | 本轮扫描见到它的时间戳 |
| `missingSince` | `0` = 可用；`> 0` = 文件暂不可见（**软删**） |

索引：`uri`(unique) / `folderId` / `folderPath` / `albumId` / `missingSince`。

**软删语义（v4 的关键）**：文件不见了**不删行**，只把 `missingSince` 置成时间戳。
收藏、歌单、统计、歌词偏移全部保留；文件回来自动恢复。真删只在两处发生：
用户主动"清理失效歌曲"，或删除单曲。

### 2.2 导入目录 `folders` —— 目录列表的唯一事实源

| 字段 | 说明 |
|---|---|
| `id` | 自增主键 |
| `treeUri` | SAF 树 URI（FILE / MEDIA_STORE 来源为绝对路径，无树 URI 存空串） |
| `docId`（唯一索引） | 目录唯一标识：SAF 解码文档 ID（`primary:Music/华语`）/ 绝对路径 |
| `name` / `fullPath` | 叶目录名（列表标题）；冒号后的完整路径（同名消歧） |
| `sort` | 用户添加顺序 |
| `addedAt` / `songCount` | 登记时间；该目录下可视歌曲数（冗余列，避免分组时再聚合） |

> v4 之前这里与 `prefs.folderUris` **双写**（顺序在 Prefs、展示在库），是状态漂移的温床。
> 现在 `prefs.folderUris` 已删除，本表是唯一来源，顺序由 `sort` 承担。

### 2.3 每曲本地状态 `song_state` —— 一张宽表顶原来四份

| 字段 | 来源 |
|---|---|
| `songId`（主键，外键 → `songs.id`，**CASCADE**） | — |
| `favorite` | 原 `play_stats.favorite` |
| `playCount` / `lastPlayedAt` / `playedMs` | 原 `play_stats` 三列 |
| `lyricOffsetMs` | 原 Prefs 里按 `songKey` 散存的 `<hash>` 键 |
| `overrideTitle` / `overrideArtist` / `overrideAlbum` | 原 `song_overrides` 三列（`null` = 不覆盖，空串 = 用户主动清空） |
| `updatedAt` | 最后修改时间 |

索引：`favorite`（收藏列表/计数用）。

**为什么合成一张**：这三类数据都以"一首歌"为单位、都随歌曲消失、读列表时要一起取。
拆开只会带来多次查询与多处清理。附带好处：**"清空播放统计"只需 UPDATE 统计三列**，
天然不会误伤收藏与标签编辑。

### 2.4 歌单 `playlists` / `playlist_songs`

- `playlists`：`id`（自增）、`name`、`createdAt`。
- `playlist_songs`：主键 `(playlistId, songId)`，另有 `position` / `addedAt`；
  两个外键**都是 CASCADE**（歌单没了、歌没了，条目都跟着没）；索引 `songId`。
- v3 的关联键是 `songUri`（超长字符串），v4 起换成整数 `songId`。

### 2.5 播放队列 `queue_songs` 与播放态 `player_state`

- `queue_songs`：`id`（自增）、`position`、`songId`（外键 CASCADE）；索引 `songId` / `position`。
  整体"以当前队列为准"重写（几百行的事务开销 < 5ms），所以不需要为 position 冲突做交换逻辑。
- `player_state`：**永远只有一行**（`id = 1`），`currentSongId`（可空，**不挂外键**）/
  `positionMs` / `shuffle` / `repeatMode` / `updatedAt`。断点续播的全部依据。

> v4 之前队列是 `prefs.queueKeys`（`\n` 拼串，上限 500）+ `queueIndex`，
> 每次保存都要全量重写一个巨型字符串；现在是一次事务内的 DELETE + INSERT。

### 2.6 搜索历史 `search_history`

`query`（主键）、`usedAt`、`hits`；索引 `usedAt`。条数由 `LibraryViewModel.SEARCH_HISTORY_LIMIT` 控制（10）。
写入用「INSERT OR IGNORE + UPDATE」两步而不是内联 upsert——老系统 SQLite 不认识 `ON CONFLICT DO UPDATE`。

### 2.7 版本历史

| 版本 | 变更 |
|---|---|
| v1 | 初版四表（`songs_cache` / `play_stats` / `song_overrides` / `playlists` 等） |
| v2 | `play_stats` 加 `playedMs` |
| v3 | 新增 `scanned_folders`（目录登记，当时与 `prefs.folderUris` 双写） |
| **v4** | **曲库化：8 张表、整数主键、软删、每曲状态合并、队列入表**（当前版本） |

**不保留任何迁移路径**（2026-10-14 产品口径：**不做跨版本兼容，软件重新手动安装**）：
代码里**没有** `Migrations.kt`、**没有** `PrefsRoomMigration.kt`，`MusicDb` 只挂 `fallbackToDestructiveMigration()`。
改结构时把 `version +1` 即可——结构对不上就整库重建（曲库靠重扫恢复，歌单 / 收藏 / 统计会丢，这是已接受的口径）。

> 若日后要恢复兼容：补 `addMigrations(...)`，Room 会优先走迁移而非兜底，其余代码不用动。
> 历史 v3→v4 迁移踩过两个"真机必崩"的坑（`playlist_songs` 同名旧表被 `IF NOT EXISTS` 跳过、占位歌 INSERT 漏 NOT NULL 列），
> 记录在 `docs/数据层重构规划.md` §十，将来重写迁移时可当参考。

---

## 三、没有启动期搬迁（原 `PrefsRoomMigration` 已删除）

逐曲歌词偏移、队列、搜索历史、目录顺序在 v3 时存在 Prefs 里，v4 曾用一段启动期任务把它们搬进 Room。
**这条路径现在整体删除**（连同 `data_v4_migrated` 幂等标志、遗留键常量与 `clearLegacyKeys()`）——
本版不做跨版本兼容，用户重新安装就是全新库，没有任何需要搬的东西。

启动流程随之简化：`LibraryViewModel.init` 直接读曲库 / 目录 / 搜索历史，**不再 await 任何搬迁门闸**
（原先的 `PrefsRoomMigration.awaitDone()` 与 `data_v4_migrated` 判断都已移除）。

---

## 四、SharedPreferences 清单（v4 瘦身之后）

文件 `music_player_prefs`（**勿改名**）。留下的全部是**全局标量/枚举/开关**：

| 分组 | 项 |
|---|---|
| 扫描 | `scanScope`* / `scanScopeChosen` / `deepScan` / `minSongDurationSec` |
| 展示 | `sort` / `libraryView` / `dynamicTheme` |
| 播放 | `playbackSpeed` / `skipSilence` / `eq*` 六项 / `carMode` |
| 歌词 | `lyricScale` / `lyricWordByWord` / `notifyLyrics` / `lyricOverlay` / `lyricOverlayX` / `lyricOverlayY` |
| 权限/更新 | `autoCheckUpdate` / `lastUpdateCheckAt` / `updateDownloadRoute`（`permissionAsked` 已于 2026-10-15 清理，从未被读取） |

\* 键名见 `Prefs.kt` 的 `KEY_*` 常量，**不要在多处硬编码字符串**。

**不存在的键（勿再引入）**：目录列表、队列、播放位置、逐曲歌词偏移、搜索历史这些"成表、会增长"的数据
v4 起全部进 Room。Prefs 里也**没有**任何遗留键搬迁逻辑或 `data_v4_migrated` 之类的幂等标志。

**响应式**：`prefs.onChanged { key -> ... }` 返回一个取消订阅的 lambda（`SharedPreferences.OnSharedPreferenceChangeListener`
的轻封装）。**不迁 DataStore**：现有 20 来个开关都是同步读、启动即用，迁过去要改几十处调用点，
收益只有一个 Flow，性价比不成立。

---

## 五、红线（改数据层前必读）

1. **`Song.stableKey = uri.toString()` 算法永不改**。曲库 `songs.uri`、封面磁盘缓存回退键、
   UI 里数百处查找/列表 key 全部依赖它。`Song.dbId` 只是给持久化边界用的旁路，**UI 不要碰**。
2. **MediaItem 的 mediaId 仍用 URI 字符串**（不用 dbId）：播放器进程没有数据库，URI 跨进程更稳。
3. **改结构 = 改 `@Entity` + `MusicDb.version` +1 + 重导 `app/schemas` 快照**（**不写 Migration**，见 §2.7）。
4. **改完结构要在真机重装验证一次**：本版没有迁移测试兜底，`LibraryDbTest` 只保证 DAO / CASCADE / 软删语义正确。
5. **DAO 调用一律在 `Dispatchers.IO`**；跨表写入用 `db.withTransaction { }`。
6. 手写 DDL 必须与 Room 期望**逐字一致**（列顺序无所谓，类型/约束/默认值有所谓）；
   权威结构看 `app/schemas/com.luckylearning.windtone.data.db.MusicDb/4.json` 的 `createSql`。

---

## 六、开发操作速查

| 任务 | 做法 |
|---|---|
| 加表 | `Entities.kt` 加 `@Entity` → `Daos.kt` 加 DAO → `MusicDb` 的 `entities` / 抽象方法 / **`version +1`** → 注入需要的 ViewModel |
| 加列 | 同上；**不写 `ALTER TABLE`**（本版无迁移），改完 `version +1` 让兜底重建即可 |
| 改索引 | 同上，索引写在 `@Entity(indices = [...])` 上 |
| 看当前真表结构 | `app/schemas/com.luckylearning.windtone.data.db.MusicDb/4.json` 的 `createSql` |
| 真机查库 | `adb shell "run-as com.luckylearning.windtone sqlite3 databases/music_player.db 'SELECT ...'"` |
| 跑 DAO 回归 | `./gradlew testDebugUnitTest --tests "*LibraryDbTest"`（Robolectric，真实 SQLite） |

**本文件与代码的同步责任**：改 schema 的提交必须同时更新 §二。

---

## 七、面向用户的数据维护能力

`data/DataMaintenance.kt` 把数据层能力收口成一个类，设置页「关于与诊断」子页的**数据与存储**分组直接消费它
（界面侧经 `SettingsViewModel` 暴露，不直接碰 `DataMaintenance`）：

| 能力 | 说明 |
|---|---|
| `report()` | 一条快照：曲库歌曲数 / 暂不可见数 / 导入目录数 / 歌单数 / 收藏数 / 播放次数 / 累计收听时长 / 搜索历史条数 / 封面缓存占用 / 数据库占用（含 `-wal` / `-shm`） |
| `cleanupMissingSongs()` | 真删 `missingSince > 0` 的行，CASCADE 会一并清掉它们的收藏、统计、歌单与队列条目（**不可撤销，界面二次确认**） |
| `resetUserData()` | 清空 `song_state` / `playlist*` / `queue_songs` / `player_state` / `search_history`，**保留曲库与目录** |
| `resetLibrary()` | 连曲库一起清（重新扫描即可恢复） |
| `clearCoverCache()` | 只删可再生的封面图 |

封面磁盘缓存在 `cacheDir/covers/<dbId>-<px>.jpg`（`dbId == 0` 时回退到 `u<uri.hashCode()>`），
上限 50MB，超了按 `lastModified` 淘汰到 80%——由 `CoverRepository` 管理，不归数据库。
