package com.kernelpack.boot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * boot 镜像格式识别的测试。
 *
 * 用例全部是**手工构造的头**（字段偏移来自 AOSP boot header 定义），
 * 不依赖任何真实镜像 —— 这样即使手上没固件也能验证识别逻辑。
 */
class BootFormatDetectorTest {

    /** 造一个 AOSP boot header：magic + 指定字段。 */
    private fun aospBoot(
        headerVersion: Int,
        pageSize: Int = 4096,
        secondSize: Int = 0,
    ): ByteArray {
        val b = ByteArray(4096)
        "ANDROID!".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        putU32(b, 0x10, secondSize)     // v0..v2 的 second_size
        putU32(b, 0x24, pageSize)       // v0..v2 的 page_size
        putU32(b, 0x28, headerVersion)
        return b
    }

    private fun vendorBoot(headerVersion: Int, dtbSize: Int = 0): ByteArray {
        val b = ByteArray(4096)
        "VNDRBOOT".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        putU32(b, 0x08, headerVersion)
        putU32(b, 0x18, dtbSize)
        return b
    }

    private fun putU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    // ---------------------------------------------------------------- 可验证的判据

    @Test
    fun `标准 AOSP boot 各版本都能认出来`() {
        for (v in 0..4) {
            val info = BootFormatDetector.detect(aospBoot(v))
            assertEquals("v$v 应识别为 AOSP boot", BootFormatDetector.Format.AOSP_BOOT, info.format)
            assertTrue("应可解析", info.format.parseable)
            assertEquals(v, info.headerVersion)
        }
    }

    @Test
    fun `v3 以上页大小固定 4096`() {
        val info = BootFormatDetector.detect(aospBoot(headerVersion = 4, pageSize = 2048))
        assertEquals(4096, info.pageSize)
        assertTrue("要点明 v3+ 固定", info.evidence.any { it.contains("固定 4096") })
    }

    @Test
    fun `v0 到 v2 读出真实页大小`() {
        val info = BootFormatDetector.detect(aospBoot(headerVersion = 2, pageSize = 2048))
        assertEquals(2048, info.pageSize)
    }

    @Test
    fun `v0 带 second stage 会被记进依据`() {
        val info = BootFormatDetector.detect(aospBoot(headerVersion = 2, secondSize = 12345))
        assertTrue("应提到 second stage", info.evidence.any { it.contains("second_size") })
    }

    @Test
    fun `vendor_boot 必须点名内核不在里面并指路`() {
        val info = BootFormatDetector.detect(vendorBoot(headerVersion = 4, dtbSize = 999))
        assertEquals(BootFormatDetector.Format.VENDOR_BOOT, info.format)
        assertFalse("vendor_boot 不该被当成可解析内核的容器", info.format.parseable)
        assertTrue("标题要点明内核不在里面", info.summary.contains("内核不在"))
        assertNotNull("必须给建议", info.advice)
        assertTrue("建议要指向 boot.img / init_boot.img",
            info.advice!!.contains("boot.img") && info.advice.contains("init_boot"))
        assertTrue("要把 dtb 的事说清楚", info.evidence.any { it.contains("dtb") })
    }

    @Test
    fun `裸 arm64 Image 能认出来`() {
        val image = ByteArray(4096)
        byteArrayOf(0x4D, 0x5A, 0x40, 0xFA.toByte()).copyInto(image, 0)
        val info = BootFormatDetector.detect(image)
        assertEquals(BootFormatDetector.Format.RAW_KERNEL, info.format)
        assertTrue(info.format.parseable)
        assertTrue(info.summary.contains("裸 arm64"))
    }

    @Test
    fun `压缩内核镜像能认出来`() {
        val gz = ByteArray(4096)
        byteArrayOf(0x1F, 0x8B.toByte(), 0x08).copyInto(gz, 0)
        val info = BootFormatDetector.detect(gz)
        assertEquals(BootFormatDetector.Format.RAW_KERNEL, info.format)
        assertTrue("应认出是压缩的", info.summary.contains("压缩"))
    }

    @Test
    fun `太小的文件直接说清楚`() {
        val info = BootFormatDetector.detect(ByteArray(4))
        assertEquals(BootFormatDetector.Format.UNKNOWN, info.format)
        assertTrue(info.summary.contains("太小") || info.summary.contains("字节"))
        assertNotNull(info.advice)
    }

    // ---------------------------------------------------------------- 启发式判据

    @Test
    fun `MTK 特征命中时输出必须带疑似`() {
        val b = ByteArray(1 shl 16)
        "ANDROID!".toByteArray().copyInto(b, 0)   // 先把 magic 冲掉，走启发式分支
        b.fill(0, 0, 8)
        "mediatek".toByteArray().copyInto(b, 64)
        val info = BootFormatDetector.detect(b)
        assertEquals(BootFormatDetector.Format.MTK_SUSPECT, info.format)
        assertTrue("启发式结论必须带「疑似」，实际：${info.summary}", info.summary.contains("疑似"))
        assertTrue("要给出依据", info.evidence.isNotEmpty())
        assertNotNull("MTK 要给排查建议", info.advice)
    }

    @Test
    fun `高通特征命中时输出必须带疑似`() {
        val b = ByteArray(1 shl 16)
        "QC_IMAGE_VERSION".toByteArray().copyInto(b, 64)
        val info = BootFormatDetector.detect(b)
        assertEquals(BootFormatDetector.Format.QCOM_SPLIT, info.format)
        assertTrue("启发式结论必须带「疑似」", info.summary.contains("疑似"))
    }

    @Test
    fun `完全认不出时说清看了哪些特征`() {
        val b = ByteArray(1 shl 16)
        b.fill(0x5A)
        val info = BootFormatDetector.detect(b)
        assertEquals(BootFormatDetector.Format.UNKNOWN, info.format)
        assertFalse(info.format.parseable)
        assertTrue("要列出看过的特征", info.evidence.size >= 2)
        assertTrue("要把开头字节打出来", info.evidence.any { it.contains("5A") })
        assertNotNull(info.advice)
    }

    @Test
    fun `超范围的 header 版本要如实说不认识`() {
        val info = BootFormatDetector.detect(aospBoot(headerVersion = 9))
        assertEquals(BootFormatDetector.Format.UNKNOWN, info.format)
        assertTrue(info.summary.contains("9"))
        assertNotNull(info.advice)
    }

    // ---------------------------------------------------------------- 与解析器联动

    @Test
    fun `解析器遇到 vendor_boot 抛出的信息里含指路`() {
        val ex = runCatching { BootImageParser.parse(vendorBoot(4, 100)) }.exceptionOrNull()
        assertNotNull("应抛异常", ex)
        val msg = ex!!.message ?: ""
        assertTrue("消息要含内核不在这里：$msg", msg.contains("内核不在"))
        assertTrue("消息要指向 boot.img：$msg", msg.contains("boot.img"))
    }

    @Test
    fun `解析器暴露的 detectFormat 与检测器一致`() {
        val bytes = aospBoot(4)
        assertEquals(
            BootFormatDetector.detect(bytes).format,
            BootImageParser.detectFormat(bytes).format,
        )
    }
}
