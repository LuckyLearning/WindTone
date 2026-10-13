package com.luckylearning.windtone.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * 从封面提取主题主色（无 Palette 依赖的轻量实现）：
 * 把缩略图像素按 RGB 高位分桶计数，挑「出现次数 × 饱和度权重」最高的桶，
 * 再做亮度矫正，保证在明暗两种主题下都可作主色。
 */
object ThemeColorExtractor {

    fun extract(imageBytes: ByteArray): Int? {
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / 2 >= 48 && h / 2 >= 48) {
                sample *= 2; w /= 2; h /= 2
            }
            BitmapFactory.decodeByteArray(
                imageBytes, 0, imageBytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }.getOrNull() ?: return null

        val buckets = HashMap<Int, Long>()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (px in pixels) {
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            // 灰白黑像素权重低
            val sat = if (max == 0) 0 else (max - min) * 255 / max
            if (sat < 24 && max > 220) continue
            val key = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            buckets[key] = (buckets[key] ?: 0L) + 1L + (sat / 32)
        }
        bitmap.recycle()
        if (buckets.isEmpty()) return null

        val best = buckets.maxByOrNull { it.value } ?: return null
        val key = best.key
        val r = ((key shr 8) and 0xF) * 17
        val g = ((key shr 4) and 0xF) * 17
        val b = (key and 0xF) * 17
        return adjustForTheme(r, g, b)
    }

    /** 中等亮度、足够饱和：浅色主题压暗，深色主题提亮 */
    private fun adjustForTheme(r: Int, g: Int, b: Int): Int {
        val lum = (0.299 * r + 0.587 * g + 0.114 * b)
        val target = 140.0
        val factor = when {
            lum < 60 -> 2.2
            lum < target -> target / lum
            lum > 210 -> 0.62
            else -> 1.0
        }
        fun clamp(v: Double) = v.coerceIn(18.0, 250.0).toInt()
        // 提升一点饱和度
        val rr = clamp(r * factor)
        val gg = clamp(g * factor)
        val bb = clamp(b * factor)
        val avg = (rr + gg + bb) / 3
        fun sat(v: Int) = (avg + (v - avg) * 1.25).coerceIn(0.0, 255.0).toInt()
        return Color.rgb(sat(rr), sat(gg), sat(bb))
    }
}
