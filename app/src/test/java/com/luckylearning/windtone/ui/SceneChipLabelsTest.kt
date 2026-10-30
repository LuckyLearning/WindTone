package com.luckylearning.windtone.ui

import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.SceneProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * i18n 第 8 批：场景芯片 string 映射与 [SceneProfiles.chipIds] 对齐。
 */
class SceneChipLabelsTest {

    @Test
    fun `三档顺序与 chipIds 一致`() {
        assertEquals(SceneProfiles.chipIds(), SceneChipLabels.chips().map { it.id })
    }

    @Test
    fun `已知档有标签与说明资源`() {
        assertEquals(R.string.scene_drive, SceneChipLabels.labelResOf("drive"))
        assertEquals(R.string.scene_drive_desc, SceneChipLabels.descResOf("drive"))
        assertEquals(R.string.scene_commute, SceneChipLabels.labelResOf("commute"))
        assertEquals(R.string.scene_commute_desc, SceneChipLabels.descResOf("commute"))
        assertEquals(R.string.scene_sleep, SceneChipLabels.labelResOf("sleep"))
        assertEquals(R.string.scene_sleep_desc, SceneChipLabels.descResOf("sleep"))
    }

    @Test
    fun `未知档与空串无映射`() {
        assertNull(SceneChipLabels.labelResOf(""))
        assertNull(SceneChipLabels.descResOf(""))
        assertNull(SceneChipLabels.labelResOf("nope"))
        assertNull(SceneChipLabels.descResOf("Drive"))
    }

    @Test
    fun `每档 label 与 desc 资源不同`() {
        for (chip in SceneChipLabels.chips()) {
            assertTrue(chip.labelRes != chip.descRes)
        }
    }
}