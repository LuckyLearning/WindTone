package com.luckylearning.windtone.playback.softEq

import com.luckylearning.windtone.playback.EqPresets
import kotlin.math.abs
import kotlin.math.log10

/**
 * Soft EQ curve bridge (E3): map hard-EQ builtin presets / custom band gains
 * onto the SoftEq Media3 processor centers.
 *
 * Soft path uses [SoftEqEngine.DEFAULT_CENTERS_HZ] (same five anchors as
 * [EqPresets]), so builtin presets land exactly. Custom / user `eqBands` CSV
 * remaps via log-frequency interpolation when a hard-EQ band layout was seen;
 * size-matched CSV applies as-is; otherwise falls back to flat.
 *
 * Pure JVM — no Android / Media3. Docs: docs/功能-软EQ.md.
 */
object SoftEqCurve {

    /** Soft processor centers (Hz); identical to [SoftEqEngine.DEFAULT_CENTERS_HZ]. */
    val CENTERS_HZ: IntArray = SoftEqEngine.DEFAULT_CENTERS_HZ.copyOf()

    enum class Source {
        /** [EqPresets.curveFor] on soft centers. */
        BUILTIN,
        /** Prefs / user CSV already sized for soft centers. */
        BANDS_CSV,
        /** CSV remapped from a remembered hard-EQ band layout. */
        REMAPPED,
        /** Empty / unusable input → all-zero. */
        FLAT,
        /** Caller-supplied gains on soft (or explicit) centers. */
        EXPLICIT,
    }

    data class Resolved(
        val centersHz: IntArray,
        val gainsMb: IntArray,
        val source: Source,
        /** Builtin / user preset id when known; empty for custom / flat. */
        val presetId: String = "",
    ) {
        init {
            require(centersHz.size == gainsMb.size) {
                "centers ${centersHz.size} != gains ${gainsMb.size}"
            }
        }

        fun copyGains(): IntArray = gainsMb.copyOf()
        fun copyCenters(): IntArray = centersHz.copyOf()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Resolved) return false
            return source == other.source &&
                presetId == other.presetId &&
                centersHz.contentEquals(other.centersHz) &&
                gainsMb.contentEquals(other.gainsMb)
        }

        override fun hashCode(): Int {
            var r = source.hashCode()
            r = 31 * r + presetId.hashCode()
            r = 31 * r + centersHz.contentHashCode()
            r = 31 * r + gainsMb.contentHashCode()
            return r
        }
    }

    fun flat(centersHz: IntArray = CENTERS_HZ): Resolved = Resolved(
        centersHz = centersHz.copyOf(),
        gainsMb = IntArray(centersHz.size),
        source = Source.FLAT,
    )

    fun fromBuiltin(
        preset: EqPresets.Preset,
        centersHz: IntArray = CENTERS_HZ,
    ): Resolved {
        val gains = EqPresets.curveFor(preset, centersHz.toList())
        return Resolved(
            centersHz = centersHz.copyOf(),
            gainsMb = gains,
            source = Source.BUILTIN,
            presetId = preset.id,
        )
    }

    fun fromBuiltinId(
        presetId: String,
        centersHz: IntArray = CENTERS_HZ,
    ): Resolved? {
        val preset = EqPresets.byId(presetId) ?: return null
        return fromBuiltin(preset, centersHz)
    }

    /**
     * Explicit gains on [centersHz] (clamped ±1500). Size must match.
     */
    fun fromGainsMb(
        gainsMb: IntArray,
        centersHz: IntArray = CENTERS_HZ,
        presetId: String = "",
    ): Resolved {
        require(gainsMb.size == centersHz.size)
        return Resolved(
            centersHz = centersHz.copyOf(),
            gainsMb = gainsMb.map { it.coerceIn(-1500, 1500) }.toIntArray(),
            source = Source.EXPLICIT,
            presetId = presetId,
        )
    }

    /**
     * Decode Prefs-style CSV. When [hardCentersHz] matches CSV length and
     * differs from soft centers, remap onto [centersHz].
     */
    fun fromBandsCsv(
        csv: String,
        centersHz: IntArray = CENTERS_HZ,
        hardCentersHz: IntArray? = null,
        minMb: Int = -1500,
        maxMb: Int = 1500,
    ): Resolved {
        val parts = csv.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.isEmpty()) return flat(centersHz)
        if (parts.size == centersHz.size) {
            return Resolved(
                centersHz = centersHz.copyOf(),
                gainsMb = parts.map { it.coerceIn(minMb, maxMb) }.toIntArray(),
                source = Source.BANDS_CSV,
            )
        }
        val hard = hardCentersHz
        if (hard != null && hard.size == parts.size && hard.isNotEmpty()) {
            val remapped = remapGains(
                sourceCentersHz = hard,
                sourceGainsMb = parts.map { it.coerceIn(minMb, maxMb) }.toIntArray(),
                targetCentersHz = centersHz,
            )
            return Resolved(
                centersHz = centersHz.copyOf(),
                gainsMb = remapped,
                source = Source.REMAPPED,
            )
        }
        // Unknown layout — safer silence than a wrong shape.
        return flat(centersHz)
    }

    /**
     * Prefer builtin id (recompute on soft centers); else bands CSV.
     */
    fun resolve(
        presetId: String,
        bandsCsv: String,
        hardCentersHz: IntArray? = null,
        centersHz: IntArray = CENTERS_HZ,
    ): Resolved {
        fromBuiltinId(presetId, centersHz)?.let { return it }
        return fromBandsCsv(bandsCsv, centersHz, hardCentersHz)
    }

    /**
     * Log-frequency linear remap of band gains onto [targetCentersHz]
     * (same idea as [EqPresets.gainAt]).
     */
    fun remapGains(
        sourceCentersHz: IntArray,
        sourceGainsMb: IntArray,
        targetCentersHz: IntArray,
    ): IntArray {
        require(sourceCentersHz.size == sourceGainsMb.size)
        if (sourceCentersHz.isEmpty()) return IntArray(targetCentersHz.size)
        val anchors = sourceCentersHz.indices
            .map { sourceCentersHz[it] to sourceGainsMb[it] }
            .sortedBy { it.first }
        return IntArray(targetCentersHz.size) { i ->
            gainAtAnchors(anchors, targetCentersHz[i]).coerceIn(-1500, 1500)
        }
    }

    /** True when any |gain| ≥ 10 mB (matches SoftEqEngine active threshold). */
    fun isAudible(gainsMb: IntArray): Boolean =
        gainsMb.any { abs(it) >= 10 }

    private fun gainAtAnchors(anchors: List<Pair<Int, Int>>, freqHz: Int): Int {
        if (freqHz <= anchors.first().first) return anchors.first().second
        if (freqHz >= anchors.last().first) return anchors.last().second
        for (i in 0 until anchors.size - 1) {
            val (f0, g0) = anchors[i]
            val (f1, g1) = anchors[i + 1]
            if (freqHz in f0..f1) {
                if (f0 == f1) return g0
                val t = (log10(freqHz.toDouble()) - log10(f0.toDouble())) /
                    (log10(f1.toDouble()) - log10(f0.toDouble()))
                return (g0 + (g1 - g0) * t).toInt()
            }
        }
        return anchors.last().second
    }
}
