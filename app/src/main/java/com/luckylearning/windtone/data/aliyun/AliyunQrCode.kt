package com.luckylearning.windtone.data.aliyun

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.max

/**
 * 二维码的**像素生成**（与渲染框架无关，可单测）。
 *
 * 为什么单独一层：最初直接在 Canvas 里按「BitMatrix 像素」逐格画，出了问题 ——
 * 阿里云盘 App 扫不出来。两个原因叠在一起：
 *
 *  1. `MARGIN = 1`（白边只有 1 个模块）。二维码规范要求静区 **≥4 个模块**，
 *     白边不够时多数扫码器直接放弃定位。
 *  2. `encode(content, QR_CODE, 600, 600)` 把 37 个模块强行摊到 600 像素
 *     （每模块 16.2 像素，非整数），再被 Canvas 缩到 1.1 像素重画一遍 ——
 *     抗锯齿把每个模块的边缘糊成半灰，0.5 像素级的缝隙直接消失。
 *
 * 现在的做法：
 *  · `encode(..., 0, 0, MARGIN=4)` → ZXing 输出**一个模块一格**的最小矩阵，
 *    不再做任何非整数缩放，静区 4 个模块；
 *  · 再按**整数倍**放大到目标像素，每个模块恰好 `scale × scale` 个同色像素，
 *    边缘永远落在整像素上，不产生半透明过渡。
 *
 * 输出 `IntArray`（ARGB）而不是 `Bitmap`，这样纯 JVM 也能断言像素。
 */
object AliyunQrCode {

    /** 静区（白边）模块数：规范要求 ≥4，少于 4 阿里云盘 App 会认不出 */
    const val QUIET_ZONE_MODULES = 4

    /** 放大后的最小边长（像素）：太小在手机上本身就难扫 */
    const val MIN_TARGET_PX = 240

    /** 一个二维码该怎么画：模块数、每模块放大倍数、总像素、颜色矩阵 */
    class Spec(
        /** 含静区的边长（模块数） */
        val moduleCount: Int,
        /** 每个模块放大成多少像素（≥1 的整数） */
        val scale: Int,
        /** 位图边长（像素）= moduleCount × scale */
        val pixelSize: Int,
        /** `bits[x][y]` = true 表示该模块为黑色 */
        val bits: Array<BooleanArray>,
    )

    /**
     * 生成「一个模块一格」的矩阵（含静区）。
     *
     * ZXing 的 `encode` 在 width/height 传 0 时走 `max(width, symbolSize)` 分支，
     * 即输出符号本身大小 + 两侧静区，倍数恒为 1 —— 不会有非整数缩放。
     */
    fun modules(content: String, quietZone: Int = QUIET_ZONE_MODULES): Array<BooleanArray> {
        val size = max(quietZone, 0)
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to size,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val bits = Array(matrix.width) { BooleanArray(matrix.height) }
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bits[x][y] = matrix[x, y]
            }
        }
        return bits
    }

    /** 按目标像素算出画法：整数倍放大 + 居中由调用方处理（这里已对齐到整像素） */
    fun spec(
        content: String,
        targetPx: Int,
        quietZone: Int = QUIET_ZONE_MODULES,
    ): Spec {
        val bits = modules(content, quietZone)
        val count = bits.size
        val px = max(targetPx, MIN_TARGET_PX)
        // 整数倍：宁可略小于目标像素，也不用 1.3 倍这种会糊边的小数倍
        val scale = max(1, px / count)
        return Spec(
            moduleCount = count,
            scale = scale,
            pixelSize = count * scale,
            bits = bits,
        )
    }

    /** 展开成 ARGB 像素数组（白底黑块），可直接喂给 `Bitmap.setPixels` */
    fun argb(spec: Spec, black: Int = 0xFF000000.toInt(), white: Int = 0xFFFFFFFF.toInt()): IntArray {
        val n = spec.pixelSize
        val out = IntArray(n * n) { white }
        for (mx in 0 until spec.moduleCount) {
            for (my in 0 until spec.moduleCount) {
                if (!spec.bits[mx][my]) continue
                val x0 = mx * spec.scale
                val y0 = my * spec.scale
                for (dy in 0 until spec.scale) {
                    val rowStart = (y0 + dy) * n + x0
                    for (dx in 0 until spec.scale) {
                        out[rowStart + dx] = black
                    }
                }
            }
        }
        return out
    }
}
