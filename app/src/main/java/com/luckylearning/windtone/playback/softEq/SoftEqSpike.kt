package com.luckylearning.windtone.playback.softEq

/**
 * Soft-EQ 自动回退总闸。Docs: `docs/功能-软EQ.md` / `docs/spike-软EQ.md`.
 *
 * E1 已把 [SoftEqPath.processor] 挂进 [com.luckylearning.windtone.playback.PlaybackService]
 * 音频链，运行时开关走 [SoftEqPath.setEnabled]（默认关）。
 *
 * [ENABLED] 控制「硬 EQ `DEVICE_REJECTED` 时是否自动 `setEnabled(true)`」；
 * E1/E2 前保持 **false**（挂钩会记原因，不改听感）。E2 再打开门控。
 */
object SoftEqSpike {
    const val ENABLED: Boolean = false
}
