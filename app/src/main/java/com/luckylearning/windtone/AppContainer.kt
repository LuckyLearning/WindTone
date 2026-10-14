package com.luckylearning.windtone

import android.app.Application
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.LyricsLoader
import com.luckylearning.windtone.data.MessageBus
import com.luckylearning.windtone.data.MusicRepository
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SongIndex
import com.luckylearning.windtone.data.UpdateRepository
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.playback.PlaybackEngine

/**
 * 应用级依赖容器（手写服务定位器）。
 *
 * 为什么不用 Hilt / Koin：本项目的依赖图是**一棵浅树**——一个 Room 库、一个偏好文件、
 * 一个播放引擎、两个仓库，全部应用级单例，没有作用域、没有注入点替换的需求。
 * 引入注解处理器换来的只是几十行代码和一次全量重建，不划算。
 *
 * 谁拿它：所有 ViewModel 通过 `(application as MusicApp).container` 取依赖，
 * 于是「谁 new 了谁」从 ViewModel 里彻底消失，测试时替换一个字段即可。
 *
 * 所有字段 `by lazy`：真正的构造推迟到第一次使用，
 * 保证 `Application.onCreate` 不会因为建库/连播放服务而变慢。
 */
class AppContainer(private val app: Application) {

    val prefs: Prefs by lazy { Prefs(app) }

    val db: MusicDb by lazy { MusicDb.get(app) }

    /** 曲库扫描与排序（无状态，按调用传参） */
    val libraryRepository: MusicRepository by lazy { MusicRepository(app, prefs) }

    val lyricsLoader: LyricsLoader by lazy { LyricsLoader(app) }

    /** `uri ↔ 主键` 与每曲状态的内存索引，曲库域与播放域共用 */
    val songIndex: SongIndex by lazy { SongIndex(db) }

    /** 全局一次性提示总线（替代原先借道 `LibraryUiState.message` 的做法） */
    val messages: MessageBus by lazy { MessageBus() }

    /** 数据自检 / 清理 / 重置 */
    val maintenance: DataMaintenance by lazy { DataMaintenance(app, db) }

    /** 播放域状态持有者：应用级单例，生命周期长于任何界面 */
    val playback: PlaybackEngine by lazy {
        PlaybackEngine(app, prefs, db, lyricsLoader, songIndex, messages)
    }

    /** 应用内更新域 */
    val updates: UpdateRepository by lazy { UpdateRepository(app, prefs, messages) }
}
