package com.luckylearning.windtone.playback.softEq

import com.luckylearning.windtone.playback.EqPresets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E3: SoftEqCurve mapping — builtin / CSV / remap onto soft centers. */
class SoftEqCurveTest {

    private val softCenters = SoftEqCurve.CENTERS_HZ
    private val fiveBands = listOf(60, 230, 910, 3600, 14000)

    @Test
    fun centersMatchEqPresetAnchors() {
        assertArrayEquals(intArrayOf(60, 230, 910, 3600, 14000), softCenters)
        assertArrayEquals(SoftEqEngine.DEFAULT_CENTERS_HZ, softCenters)
    }

    @Test
    fun fromBuiltinMatchesEqPresetsCurveFor() {
        EqPresets.ALL.forEach { preset ->
            val resolved = SoftEqCurve.fromBuiltin(preset)
            assertEquals(SoftEqCurve.Source.BUILTIN, resolved.source)
            assertEquals(preset.id, resolved.presetId)
            assertArrayEquals(
                "preset ${preset.id}",
                EqPresets.curveFor(preset, fiveBands),
                resolved.gainsMb,
            )
        }
    }

    @Test
    fun resolvePrefersBuiltinIdOverStaleCsv() {
        // CSV claims flat; builtin rock must win.
        val r = SoftEqCurve.resolve(
            presetId = EqPresets.ROCK.id,
            bandsCsv = "0,0,0,0,0",
        )
        assertEquals(SoftEqCurve.Source.BUILTIN, r.source)
        assertArrayEquals(EqPresets.curveFor(EqPresets.ROCK, fiveBands), r.gainsMb)
    }

    @Test
    fun sizeMatchedCsvAppliesAsBands() {
        val r = SoftEqCurve.fromBandsCsv("100,-50,0,200,-100")
        assertEquals(SoftEqCurve.Source.BANDS_CSV, r.source)
        assertArrayEquals(intArrayOf(100, -50, 0, 200, -100), r.gainsMb)
    }

    @Test
    fun emptyCsvIsFlat() {
        val r = SoftEqCurve.fromBandsCsv("")
        assertEquals(SoftEqCurve.Source.FLAT, r.source)
        assertTrue(r.gainsMb.all { it == 0 })
    }

    @Test
    fun mismatchedCsvWithoutHardLayoutFallsFlat() {
        val r = SoftEqCurve.fromBandsCsv("100,200,300") // 3 bands, soft expects 5
        assertEquals(SoftEqCurve.Source.FLAT, r.source)
        assertEquals(5, r.gainsMb.size)
        assertTrue(r.gainsMb.all { it == 0 })
    }

    @Test
    fun remapTenBandCustomOntoSoftCenters() {
        val tenCenters = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        // Bass-ish custom: boost lows, cut highs
        val tenGains = intArrayOf(600, 500, 300, 100, 0, -50, -100, -200, -300, -400)
        val remapped = SoftEqCurve.remapGains(tenCenters, tenGains, softCenters)
        assertEquals(5, remapped.size)
        // Soft 60Hz near 62Hz (+500); soft 14000Hz near 16000 (-400)
        assertTrue("60Hz gain ${remapped[0]}", remapped[0] in 450..600)
        assertTrue("14kHz gain ${remapped[4]}", remapped[4] in -400..-250)
        assertTrue(SoftEqCurve.isAudible(remapped))
    }

    @Test
    fun fromBandsCsvRemapsWhenHardLayoutKnown() {
        val tenCenters = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        val csv = "600,500,300,100,0,-50,-100,-200,-300,-400"
        val r = SoftEqCurve.fromBandsCsv(csv, hardCentersHz = tenCenters)
        assertEquals(SoftEqCurve.Source.REMAPPED, r.source)
        assertEquals(5, r.gainsMb.size)
        assertTrue(SoftEqCurve.isAudible(r.gainsMb))
    }

    @Test
    fun fromGainsMbClamps() {
        val r = SoftEqCurve.fromGainsMb(intArrayOf(2000, 0, 0, 0, -2000))
        assertEquals(SoftEqCurve.Source.EXPLICIT, r.source)
        assertEquals(1500, r.gainsMb[0])
        assertEquals(-1500, r.gainsMb[4])
    }

    @Test
    fun isAudibleThreshold() {
        assertFalse(SoftEqCurve.isAudible(intArrayOf(0, 5, -5, 0, 0)))
        assertTrue(SoftEqCurve.isAudible(intArrayOf(0, 10, 0, 0, 0)))
    }
}
