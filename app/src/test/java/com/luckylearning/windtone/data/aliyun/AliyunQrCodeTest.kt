package com.luckylearning.windtone.data.aliyun

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码像素生成的自检 —— 核心断言是**能被解码回来**。
 *
 * 背景：1.1.61 之前用 `MARGIN=1` + `600×600` 非整数缩放，阿里云盘 App 扫不出来。
 * 这类问题肉眼看不出来（屏幕上就是个二维码），所以这里不只断言结构，
 * 而是把生成的像素直接喂给 ZXing 解码器走一遍完整识别流程：
 * 解码结果 != 原文，就说明「扫不出来」这类回归一定会被抓到。
 */
class AliyunQrCodeTest {

    /** 服务端真实返回的二维码内容（长度与实际一致） */
    private val realContent =
        "https://passport.aliyundrive.com/qrcodeCheck.htm" +
            "?lgToken=182e7980c8283b1eb456210f96a246bfd_0000000&_from=havana"

    /** 把生成的像素交给 ZXing 解码（等价于扫码器看到这张图） */
    private fun decode(spec: AliyunQrCode.Spec): String {
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        val source = RGBLuminanceSource(spec.pixelSize, spec.pixelSize, AliyunQrCode.argb(spec))
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun realContentRoundTripsThroughDecoder() {
        // 按 260dp @3x ≈ 780px 生成，和真机一致
        val spec = AliyunQrCode.spec(realContent, targetPx = 780)
        assertEquals(realContent, decode(spec))
    }

    @Test
    fun roundTripsAtEveryPlausibleScreenDensity() {
        // 1x(dpi160)~4x(dpi640)：任何密度下都必须扫得出来
        for (target in listOf(240, 260, 320, 390, 480, 660, 780, 1040)) {
            val spec = AliyunQrCode.spec(realContent, targetPx = target)
            assertEquals("targetPx=$target 解码失败", realContent, decode(spec))
        }
    }

    @Test
    fun quietZoneIsFourModulesOnEverySide() {
        val spec = AliyunQrCode.spec(realContent, targetPx = 780)
        val n = spec.moduleCount
        // 规范要求静区 ≥4 个模块；阿里云盘 App 对白边尤其敏感
        for (i in 0 until AliyunQrCode.QUIET_ZONE_MODULES) {
            for (j in 0 until n) {
                assertTrue("左白边 $i,$j 应为白", !spec.bits[i][j])
                assertTrue("右白边 $i,$j 应为白", !spec.bits[n - 1 - i][j])
                assertTrue("上白边 $i,$j 应为白", !spec.bits[j][i])
                assertTrue("下白边 $i,$j 应为白", !spec.bits[j][n - 1 - i])
            }
        }
    }

    @Test
    fun symbolSideIsAValidQrVersionPlusQuietZone() {
        val spec = AliyunQrCode.spec(realContent, targetPx = 780)
        val modules = spec.moduleCount - AliyunQrCode.QUIET_ZONE_MODULES * 2
        // QR 版本 1..40 边长 = 21 + 4k，必须是奇数
        assertEquals(1, modules % 2)
        assertTrue("符号边长异常：$modules", modules in 21..177)
        assertEquals(0, (modules - 21) % 4)
    }

    @Test
    fun topLeftFinderPatternIsSevenModulesWithHeartOfThree() {
        val spec = AliyunQrCode.spec(realContent, targetPx = 780)
        val q = AliyunQrCode.QUIET_ZONE_MODULES
        // 定位图形的左上角外框：7×7 一圈全黑
        for (i in 0 until 7) {
            assertTrue(spec.bits[q + i][q])
            assertTrue(spec.bits[q][q + i])
        }
        // 中间 3×3 实心
        for (dx in 2..4) for (dy in 2..4) assertTrue(spec.bits[q + dx][q + dy])
        // 内外框之间的白环
        assertEquals(false, spec.bits[q + 1][q + 1])
        assertEquals(false, spec.bits[q + 5][q + 5])
    }

    @Test
    fun upscalingIsIntegralSoEdgesLandOnWholePixels() {
        for (target in listOf(240, 300, 480, 780, 1000)) {
            val spec = AliyunQrCode.spec(realContent, targetPx = target)
            assertEquals(
                "像素边长必须是模块数的整数倍（否则抗锯齿会糊边）",
                spec.moduleCount * spec.scale,
                spec.pixelSize,
            )
            assertTrue("scale 至少 1", spec.scale >= 1)
        }
    }

    @Test
    fun bitmapNeverExceedsAvailableWidth() {
        // 渲染层约定：位图生成后视图按位图的精确像素尺寸显示（1:1 零缩放），
        // 所以位图绝不能比可用宽度还宽（否则又会被缩小重采样）
        for (target in listOf(240, 320, 480, 780, 1080)) {
            val spec = AliyunQrCode.spec(realContent, targetPx = target)
            assertTrue(
                "target=$target 时位图 ${spec.pixelSize}px 超出了可用宽度",
                spec.pixelSize <= target || spec.pixelSize <= spec.moduleCount * AliyunQrCode.MIN_SCALE,
            )
            assertTrue("scale 至少 ${AliyunQrCode.MIN_SCALE}", spec.scale >= AliyunQrCode.MIN_SCALE)
        }
    }

    @Test
    fun tinyRequestIsRaisedToReadableMinimum() {
        // 传 40px 进来也不会生成一张糊成一团的图（234/45 落到 5 倍 = 225px）
        val spec = AliyunQrCode.spec(realContent, targetPx = 40)
        assertTrue("scale 应被抬到至少 4 倍，实际 ${spec.scale}", spec.scale >= 4)
        assertEquals(realContent, decode(spec))
    }

    @Test
    fun everyPixelRunInsideAModuleIsSameColor() {
        val spec = AliyunQrCode.spec(realContent, targetPx = 780)
        val px = AliyunQrCode.argb(spec)
        val n = spec.pixelSize
        val s = spec.scale
        var mismatched = 0
        // 逐模块检查：模块内每个像素必须同色（这是「不糊边」的定义）
        for (mx in 0 until spec.moduleCount) {
            for (my in 0 until spec.moduleCount) {
                val expected = if (spec.bits[mx][my]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                for (dy in 0 until s) {
                    val row = (my * s + dy) * n + mx * s
                    for (dx in 0 until s) if (px[row + dx] != expected) mismatched++
                }
            }
        }
        assertEquals("模块内有杂色像素（会出现灰边）", 0, mismatched)
    }

    @Test
    fun quietZonePixelsArePureWhite() {
        val spec = AliyunQrCode.spec(realContent, targetPx = 780)
        val px = AliyunQrCode.argb(spec)
        val n = spec.pixelSize
        val edge = AliyunQrCode.QUIET_ZONE_MODULES * spec.scale
        var nonWhite = 0
        // 四条边带：逐行扫一条，逐列扫一条，横竖都取样
        for (i in 0 until edge) {
            for (j in 0 until n step 7) {
                if (px[i * n + j] != 0xFFFFFFFF.toInt()) nonWhite++
                if (px[(n - 1 - i) * n + j] != 0xFFFFFFFF.toInt()) nonWhite++
                if (px[j * n + i] != 0xFFFFFFFF.toInt()) nonWhite++
                if (px[j * n + (n - 1 - i)] != 0xFFFFFFFF.toInt()) nonWhite++
            }
        }
        assertEquals("静区里出现了非白像素", 0, nonWhite)
        // 四角必须纯白（这是静区最靠内的位置）
        assertEquals(0xFFFFFFFF.toInt(), px[0])
        assertEquals(0xFFFFFFFF.toInt(), px[n - 1])
        assertEquals(0xFFFFFFFF.toInt(), px[(n - 1) * n])
        assertEquals(0xFFFFFFFF.toInt(), px[n * n - 1])
    }

    @Test
    fun differentContentsProduceDifferentMatrices() {
        val a = AliyunQrCode.modules("https://passport.aliyundrive.com/qrcodeCheck.htm?lgToken=a")
        val b = AliyunQrCode.modules("https://passport.aliyundrive.com/qrcodeCheck.htm?lgToken=b")
        val same = a.size == b.size && a.indices.all { x -> a[x].contentEquals(b[x]) }
        assertTrue("不同内容不应生成同一张图", !same)
    }
}
