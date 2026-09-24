package com.kernelpack.boot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * LZ4 解码器的交叉验证测试。
 *
 * 为什么这么写
 * ------------
 * LZ4 解码的 bug 有个恶劣性质：**它不报错，只是解出错误的数据**。内核偏移算错
 * 的后果是写坏内核内存（panic / 变砖），所以这里不是"随便造点数据试试"，而是：
 *
 *   1. **真实镜像向量**：从真实 6.1.145 内核的 boot.img 里截取 lz4_legacy 块前缀，
 *      期望结果是**已验证的 Python 解码器**算出来的（只内嵌 sha256，省体积）。
 *      Kotlin 侧解出同样的哈希 = 移植没有语义偏差。
 *   2. **合成边界向量**：重叠匹配、超长匹配（扩展字节）、多 sequence 串联 ——
 *      这三类是最容易写错的地方。
 *   3. **错误分类**：不支持的格式必须返回明确原因，而不是 null + 沉默。
 */
class Lz4Test {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun `真实 boot_img 的 lz4_legacy 块前缀能解出与 Python 一致的结果`() {
        val compressed = b64(Lz4Vectors.REAL_COMPRESSED_B64)
        val out = Lz4.decompressBlock(compressed, 0, compressed.size)
        assertNotNull("真实块前缀应当能解出结果", out)
        assertEquals("解压长度与 Python 侧不一致", Lz4Vectors.REAL_EXPECTED_LENGTH, out!!.size)
        assertEquals("解压内容 sha256 与 Python 侧不一致", Lz4Vectors.REAL_EXPECTED_SHA256, sha256(out))
    }

    @Test
    fun `完整 legacy 帧（魔数 + 块长度）也能解出同样结果`() {
        val compressed = b64(Lz4Vectors.REAL_COMPRESSED_B64)
        val frame = Lz4.MAGIC_LEGACY + u32le(compressed.size) + compressed
        assertTrue("魔数识别", Lz4.isLegacy(frame, 0))
        val out = Lz4.decompressLegacy(frame, 0, frame.size)
        assertNotNull(out)
        assertEquals(Lz4Vectors.REAL_EXPECTED_LENGTH, out!!.size)
        assertEquals(Lz4Vectors.REAL_EXPECTED_SHA256, sha256(out))
    }

    @Test
    fun `重叠匹配必须逐字节复制（offset 小于匹配长度）`() {
        val out = Lz4.decompressBlock(b64(Lz4Vectors.SYNTH_A_COMPRESSED_B64), 0,
            b64(Lz4Vectors.SYNTH_A_COMPRESSED_B64).size)
        assertArrayEquals(b64(Lz4Vectors.SYNTH_A_EXPECTED_B64), out)
        // 这条语义错了就会解成 "ABCD" + 一堆重复的首字节，而不是 ABCD 的循环
        assertEquals("ABCDABCDABCDABCD", String(out!!, Charsets.US_ASCII))
    }

    @Test
    fun `超长匹配走 15 加扩展字节路径`() {
        val compressed = b64(Lz4Vectors.SYNTH_B_COMPRESSED_B64)
        val out = Lz4.decompressBlock(compressed, 0, compressed.size)
        assertArrayEquals(b64(Lz4Vectors.SYNTH_B_EXPECTED_B64), out)
        assertEquals(270, out!!.size)
    }

    @Test
    fun `多个 sequence 串联（字面量 匹配 字面量）`() {
        val compressed = b64(Lz4Vectors.SYNTH_C_COMPRESSED_B64)
        val out = Lz4.decompressBlock(compressed, 0, compressed.size)
        assertArrayEquals(b64(Lz4Vectors.SYNTH_C_EXPECTED_B64), out)
        assertEquals("HELLO-WORLD-HELLO-WORLD-TAILTAIL", String(out!!, Charsets.US_ASCII))
    }

    @Test
    fun `非法数据返回 null 而不是抛异常`() {
        // 偏移为 0 的匹配是非法的
        val bad = byteArrayOf(0x00, 0x00, 0x00)
        assertNull(Lz4.decompressBlock(bad, 0, bad.size))
        // 非 legacy 数据不该被当成 legacy
        assertNull(Lz4.decompressLegacy(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 8))
    }

    @Test
    fun `KernelDecompressor 能识别真实格式并给出格式名`() {
        val compressed = b64(Lz4Vectors.REAL_COMPRESSED_B64)
        assertEquals(KernelDecompressor.Format.NONE, KernelDecompressor.detect(compressed, 0))
        val frame = Lz4.MAGIC_LEGACY + u32le(compressed.size) + compressed
        assertEquals(KernelDecompressor.Format.LZ4_LEGACY, KernelDecompressor.detect(frame, 0))
        val r = KernelDecompressor.decompress(frame, 0, frame.size)
        assertTrue("应当是 Ok，实际 $r", r is DecompressOutcome.Ok)
        assertEquals("lz4_legacy", (r as DecompressOutcome.Ok).format)
    }

    @Test
    fun `不支持的格式给出明确原因而不是沉默`() {
        val zstd = KernelDecompressor.MAGIC_ZSTD + ByteArray(16)
        val r = KernelDecompressor.decompress(zstd, 0, zstd.size)
        assertTrue("zstd 应当报 Unsupported，实际 $r", r is DecompressOutcome.Unsupported)
        val err = ParseError.UnsupportedCompression(
            (r as DecompressOutcome.Unsupported).format, r.magicHex)
        assertTrue("错误文案要包含格式名", err.readable.contains("zstd"))
        assertTrue("错误文案要给出魔数证据", err.readable.contains("28 b5 2f fd"))
    }

    /**
     * 端到端验证：用**真实 boot.img** 跑完整解析链路。
     *
     * 需要设置环境变量 `KSU_TEST_BOOT_IMG=/path/to/boot.img`；未设置时自动跳过，
     * 因此不会影响 CI。建议用「你手上那台设备当前固件的 boot.img」跑一次 ——
     * 这是唯一能证明"解析出的版本号确实是这台设备的"的方法。
     */
    @Test
    fun `端到端 真实 boot_img 能解压并读到内核版本`() {
        val path = System.getenv("KSU_TEST_BOOT_IMG")
        assumeTrue("未设置 KSU_TEST_BOOT_IMG，跳过端到端验证", !path.isNullOrBlank())
        val file = File(path!!)
        assumeTrue("镜像不存在：$path", file.isFile)

        val parsed = BootImageParser.parse(file.readBytes())
        assertNull("不该有诊断信息，实际：${parsed.diagnosis}", parsed.diagnosis)
        // 注意：内核段**可能本来就是未压缩的 arm64 Image**（实测 5.10.246 就是这种），
        // 此时 decompressed=false 属于正确行为。真正要保证的是"拿到的是可用的内核镜像"。
        assertTrue(
            "内核段既没被解压、也不是 arm64 Image —— 说明解析确实失败了" +
                "（压缩格式未识别？段偏移切错？）",
            parsed.info.decompressed || parsed.info.arm64Image,
        )

        val banner = findVersionBanner(parsed.image)
        assertNotNull("应当在解压后的镜像里找到带版本号的 Linux version", banner)
        println("[端到端] ${file.name} -> $banner（镜像 ${parsed.image.size} 字节，" +
            "容器 ${parsed.info.container}，header v${parsed.info.headerVersion}，" +
            "arm64Image=${parsed.info.arm64Image}，decompressed=${parsed.info.decompressed}）")
    }

    /** 在字节数组里找"带版本号的" Linux version（避开 printk 格式串 `%s (%s)`）。 */
    private fun findVersionBanner(image: ByteArray): String? {
        val needle = "Linux version ".toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i <= image.size - needle.size) {
            if (image[i] == needle[0]) {
                var ok = true
                for (k in needle.indices) if (image[i + k] != needle[k]) { ok = false; break }
                if (ok) {
                    val d = i + needle.size
                    if (d < image.size && image[d] in '0'.code.toByte()..'9'.code.toByte()) {
                        var j = d
                        while (j < image.size && j - i < 200 &&
                            image[j] != 0.toByte() && image[j] != '\n'.code.toByte()
                        ) j++
                        return String(image, i, j - i, Charsets.US_ASCII)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun u32le(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
    )
}
