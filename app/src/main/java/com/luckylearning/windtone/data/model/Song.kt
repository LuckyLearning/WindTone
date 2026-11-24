package com.luckylearning.windtone.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.luckylearning.windtone.data.online.OnlineKeys

/**
 * 歌曲来源：系统媒体库 / SAF 导入的文件夹 / 直接文件路径（深度扫描）/ 在线列表（M3U·PLS）/
 * 在线音源（洛雪协议）。
 *
 * [REMOTE] 与 [ONLINE] 都是「没有本地文件」的来源，但两者**必须分开**：
 *  - [REMOTE] 的 uri 就是最终可播地址（M3U 里写死的 http 直链），拿到即可播；
 *  - [ONLINE] 的 uri 只是**身份**（`lx:kw:228908`），必须经音源解析出直链才能播，
 *    而且直链有短时效、可能要换源。
 *
 * 混用会让播放链在每一处都要猜「这个地址能不能直接给播放器」，而这正是 v1.1.71
 * 「unknown protocol」事故的成因。界面与数据层对两者都要显式判断，不能假设「有 uri 就有文件」。
 */
enum class SongSource { MEDIA_STORE, FOLDER, FILE, REMOTE, ONLINE }

/**
 * 标注 @Immutable：本类所有字段都是 val，且 `Uri` 本身不可变，
 * 更新一律走 `copy`（整体替换）。不加这个注解 Compose 会把它判定为不稳定类型，
 * 导致列表项在父级重组时无法跳过——这是滚动掉帧的主要来源之一。
 */
@Immutable
data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long = 0L,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String? = null,
    val displayName: String = "",
    /** 有绝对路径时才有值（深度扫描 / Android 10 及以下） */
    val path: String? = null,
    val folderPath: String? = null,
    val source: SongSource = SongSource.MEDIA_STORE,
    val trackNo: Int = 0,
    /** 已定位到的同名 .lrc / .txt 歌词文件（若有） */
    val lrcUri: Uri? = null,
    /**
     * 流派标签（MediaStore GENRE / MMR；无则 null）。
     * **不入 Room**——见 [com.luckylearning.windtone.data.GenreYearCache]。
     */
    val genre: String? = null,
    /**
     * 发行年（四位；MediaStore YEAR / MMR DATE·YEAR；无则 null）。
     * **不入 Room**——见 [com.luckylearning.windtone.data.GenreYearCache]。
     */
    val year: Int? = null,
    /**
     * 合辑艺人 / Album Artist（MediaStore ALBUM_ARTIST / MMR；无则 null）。
     * **不入 Room**——见 [com.luckylearning.windtone.data.AlbumArtistCache]。
     * 浏览时优先本字段，空则回退 [artist]（见 effectiveAlbumArtist）。
     */
    val albumArtist: String? = null,
    /**
     * 碟号（多碟专辑；MediaStore TRACK 高 16 位 / MMR DISC_NUMBER；无则 null）。
     * **不入 Room**——见 [com.luckylearning.windtone.data.DiscNumberCache]。
     * 亦可从 [trackNo] 的 MediaStore 编码即时解码（见 effectiveDiscNumber）。
     */
    val discNumber: Int? = null,
    val dateAdded: Long = 0L,
    /**
     * 曲库主键（Room `songs.id`）。
     *
     * UI 层不关心它——[stableKey] 才是界面查找 / 列表 key / 播放媒体项 id 的依托；
     * 它只在持久化边界使用（收藏、歌词偏移、队列、封面磁盘缓存文件名的换算）。
     * 扫描过程中直接构造的 Song 还是 0，落库后由 ViewModel 回填。
     */
    val dbId: Long = 0L,
) {
    val artistOrUnknown: String
        get() = artist?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知歌手"

    val albumOrUnknown: String
        get() = album?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知专辑"

    val baseName: String
        get() = displayName.substringBeforeLast('.', displayName)

    /**
     * 全局身份键，**所有用户数据与播放都靠它**。
     *
     *  - 阿里云盘：用 fragment 里的盘编号 + 文件编号（直链每次签名都不同，不能当身份）；
     *  - 在线音源：uri 本身就是身份键 `lx:<源>:<id>`（直链同理不能当身份）；
     *  - 其余来源：完整地址 `uri.toString()`，**一字不改**（老用户收藏全挂在这套算法上）。
     *
     * 新增来源时务必「分区」而不是改算法：本地分支一旦变动，已有收藏 / 歌单 / 队列 /
     * 统计 / 歌词偏移会整体脱钩，且不可逆。
     */
    /**
     * 跨补链不变的身份。
     *
     * 云盘：`aliyun-pending://#ay=…` 与直链 `https://…#ay=…` 都要得到 `aliyun:ay=…`；
     * 在线：`lx-pending://#lx:kw:1` 与直链 `https://…#lx:kw:1` 都要得到 `lx:kw:1`。
     *
     * 漏掉在线这一支的后果很具体：补链后 stableKey 变成整条 http URL，
     * `replaceMediaItem` 按 mediaId 就找不到原来那一项，表现为「补到链也不出声」。
     */
    val stableKey: String get() = TrackFragment.cacheKey(uri.fragment)
        ?: OnlineKeys.parse(uri.fragment)?.let { OnlineKeys.of(it.sourceId, it.songId) }
        ?: uri.toString()

    val hasSidecarLyric: Boolean get() = lrcUri != null

    /**
     * 在线音源曲目（`uri` = `lx:<源>:<id>`，需解析才能播）。
     *
     * 这个判断会出现在播放链、封面、歌词、删除、分享等十几处，所以收成一个属性：
     * 写成 `source == SongSource.ONLINE` 散落各处，早晚会有人漏掉一处。
     */
    val isOnline: Boolean get() = source == SongSource.ONLINE

    /**
     * 曲目是否由**本机文件**承载（可读标签、可取内嵌封面、可删文件）。
     *
     * 本地扫描三种来源为真；M3U 远程项与在线音源项为假。
     */
    val isLocalFile: Boolean
        get() = source == SongSource.MEDIA_STORE || source == SongSource.FOLDER || source == SongSource.FILE
}
