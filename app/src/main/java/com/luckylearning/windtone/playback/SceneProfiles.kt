package com.luckylearning.windtone.playback

/**
 * 场景化模式的内置档位（驾车 / 通勤 / 助眠）。
 *
 * 与 [SoundSchemes] 的差别：方案只打包「听感」（EQ+增强）；场景再叠一层
 * **睡眠定时 / 变速 / 变调**，一键切到一种使用情境。用户自己的 EQ 预设也能
 * 被引用（[EqSource.USER_PRESET]），但本文件只钉住三档默认，不做自定义编辑器。
 *
 * 约束：
 *  - 纯 Kotlin 数据，普通 JVM 单测可跑；
 *  - `id` 持久化到 Prefs，**发布后不可改**；
 *  - [EqSource.SCHEME] / [EqSource.BUILTIN_PRESET] 的 `eqRefId` 必须真实存在，
 *    由单测对质；用户预设在应用时再校验（用户可能已删）。
 */
object SceneProfiles {

    enum class EqSource {
        /** [SoundSchemes] 的 id */
        SCHEME,
        /** [UserEqPresets] 的 id（`u_` 前缀） */
        USER_PRESET,
        /** [EqPresets] 的 id（只套曲线，增强保持场景里另写的话由 apply 层决定） */
        BUILTIN_PRESET,
    }

    /**
     * @param id 持久化稳定标识（勿改）
     * @param eqSource 均衡器从哪里取
     * @param eqRefId 对应源的 id
     * @param sleepMinutes null=不碰睡眠定时；0=取消；>0=启动 N 分钟
     * @param speed null=不碰变速；否则夹到 [com.luckylearning.windtone.data.SettingsDefaults.PLAYBACK_SPEED_RANGE]
     * @param pitchSemitones null=不碰变调；否则半音
     * @param playlistId 预留：可选歌单；MVP 三档都为 null
     */
    data class Profile(
        val id: String,
        val eqSource: EqSource,
        val eqRefId: String,
        val sleepMinutes: Int? = null,
        val speed: Float? = null,
        val pitchSemitones: Int? = null,
        val playlistId: Long? = null,
    )

    /** 驾车：车载驾驶方案 + 原速原调，关掉睡眠定时 */
    val DRIVE = Profile(
        id = "drive",
        eqSource = EqSource.SCHEME,
        eqRefId = SoundSchemes.CAR_DRIVE.id,
        sleepMinutes = 0,
        speed = 1.0f,
        pitchSemitones = 0,
    )

    /** 通勤：清澈人声方案 + 原速原调，关掉睡眠定时 */
    val COMMUTE = Profile(
        id = "commute",
        eqSource = EqSource.SCHEME,
        eqRefId = SoundSchemes.VOCAL.id,
        sleepMinutes = 0,
        speed = 1.0f,
        pitchSemitones = 0,
    )

    /** 助眠：沉浸环绕 + 45 分钟睡眠定时，原速原调 */
    val SLEEP = Profile(
        id = "sleep",
        eqSource = EqSource.SCHEME,
        eqRefId = SoundSchemes.SPACE.id,
        sleepMinutes = 45,
        speed = 1.0f,
        pitchSemitones = 0,
    )

    val ALL: List<Profile> = listOf(DRIVE, COMMUTE, SLEEP)

    fun byId(id: String): Profile? = ALL.firstOrNull { it.id == id }

    /** UI 芯片顺序（与 [com.luckylearning.windtone.ui.SceneChipsRow] 一致）。 */
    fun chipIds(): List<String> = ALL.map { it.id }

    /** 手改 EQ / 另选方案 = 离开场景（清空 active id）。纯函数便于单测。 */
    fun activeIdAfterManualEdit(activeId: String): String = ""

    /**
     * 点同一档再次 = 退出；点「关闭」= 退出；点另一档 = 切过去。
     * @return 下一个应套用的场景 id；空串表示执行 exit（还原快照）
     */
    fun nextSelection(currentId: String, tappedId: String): String {
        if (tappedId.isEmpty()) return ""
        if (tappedId == currentId) return ""
        return tappedId
    }

    /**
     * 冷启动：已落盘的 [sceneId] 如何处理。
     *
     * - [None]：无场景
     * - [Reapply]：按档重套听感（不重拍 stash；与进档后杀进程再开的语义一致）
     * - [ClearInvalid]：未知 id，应清标记；有 stash 时走 exit 还原
     */
    sealed class ColdStart {
        data object None : ColdStart()
        data class Reapply(val id: String) : ColdStart()
        data object ClearInvalid : ColdStart()
    }

    /**
     * 进档时是否需要拍 stash。
     * 冷启动重套传 captureBaseline=false，即使 stash 空也不拍（避免把场景听感写成退出基线）。
     */
    fun shouldCaptureBaseline(captureBaseline: Boolean, existingStashRaw: String): Boolean =
        captureBaseline && existingStashRaw.isEmpty()

    fun planColdStart(persistedSceneId: String): ColdStart {
        if (persistedSceneId.isEmpty()) return ColdStart.None
        if (byId(persistedSceneId) == null) return ColdStart.ClearInvalid
        return ColdStart.Reapply(persistedSceneId)
    }
}
