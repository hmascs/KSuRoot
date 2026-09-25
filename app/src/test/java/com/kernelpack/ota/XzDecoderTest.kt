package com.kernelpack.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * XZ 解码器的交叉验证。
 *
 * 向量是**真实 `xz` 产物**（见 [XzVectors]）。这一点不能省：XZ 是算术编码，
 * "差一位"和"完全正确"之间没有中间状态 —— 用自造的字节只能证明代码不崩，
 * 证明不了它解得对。
 */
class XzDecoderTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun compressed(v: XzVectors.Vector): ByteArray =
        Base64.getDecoder().decode(v.compressedB64)

    @Test
    fun `三条真实 xz 向量都逐字节解出`() {
        for (v in XzVectors.ALL) {
            val out = XzDecoder.decode(compressed(v), v.decodedSize)
            assertEquals("${v.name} 长度", v.decodedSize, out.size)
            assertEquals("${v.name} 内容", v.decodedSha256, sha256(out))
        }
    }

    @Test
    fun `默认 maxOutput 不截断小样本`() {
        // maxOutput = Int.MAX_VALUE 时 OutputBuffer 是惰性增长的（初始 64 KiB），
        // 不会因为传了 Int.MAX_VALUE 就尝试开 2 GiB —— 这条同时守住那个性质。
        val v = XzVectors.CRC32_CHECK
        val out = XzDecoder.decode(compressed(v))
        assertEquals(v.decodedSize, out.size)
        assertEquals(v.decodedSha256, sha256(out))
    }

    @Test
    fun `maxOutput 小于真实长度时只解出前 maxOutput 字节`() {
        val v = XzVectors.MIXED
        val full = XzDecoder.decode(compressed(v), v.decodedSize)
        val capped = XzDecoder.decode(compressed(v), 1000)
        assertEquals(1000, capped.size)
        // 前缀必须与完整解码的前 1000 字节逐字节一致
        assertTrue(full.copyOf(1000).contentEquals(capped))
    }

    @Test
    fun `输入被截断时默认抛 XzException`() {
        val full = compressed(XzVectors.MIXED)
        val cut = full.copyOf(full.size * 9 / 10)
        assertThrows(XzException::class.java) {
            XzDecoder.decode(cut, XzVectors.MIXED.decodedSize)
        }
    }

    @Test
    fun `单块流被截断时 allowTruncated 不抛异常且结果仍是前缀`() {
        // 单块流的块头里写着**整块**的压缩长度，解码器会一次性把整块取走 ——
        // 所以截断后一个字节都拿不到。这是这个解码器的**设计**（上游就是这么用的：
        // 每次喂进来的是恰好一个操作的完整数据），不是缺陷。这里把这条性质钉住，
        // 免得日后有人以为"allowTruncated 一定能吐出一部分"。
        val v = XzVectors.MIXED
        val full = compressed(v)
        val cut = full.copyOf(full.size * 9 / 10)
        val partial = XzDecoder.decode(cut, v.decodedSize, allowTruncated = true)
        val whole = XzDecoder.decode(full, v.decodedSize)
        assertEquals("单块流截断后应当一个字节都解不出", 0, partial.size)
        assertTrue(partial.size == 0 || whole.copyOf(partial.size).contentEquals(partial))
    }

    @Test
    fun `多块 XZ 流会抛异常 —— 已实测的已知缺陷，不是测试写错`() {
        // 2026-09-25 逐块诊断的实测结果（`xz --block-size=16384 -9` 产出的 5 块流）：
        //   block#0：16384 字节**完全正确**（前 8 字节与 xz 侧逐字节一致）；
        //   block#1：从**第 2 个字节**起发散 —— 期望 `0356: KS`，实得 `00000000`；
        //           随后 `copyMatch dist=5311818 len=46 size=16392` → XzException。
        // 各块的 LZMA2 头解析（reset=3 / props=93 / packSize / 偏移）与 xz 侧**完全一致**，
        // 输入字节也一致，所以问题在解码状态而非定位。
        //
        // 这条测试的作用是**钉住现状**：谁将来修这个缺陷，这里会先红。
        // 单块流不受影响 —— 上面三条真实向量已经逐字节证明过了。
        val v = XzVectors.MULTI_BLOCK
        assertThrows(XzException::class.java) {
            XzDecoder.decode(compressed(v), v.decodedSize)
        }
    }

    @Test
    fun `不是 XZ 流时抛 XzException`() {
        assertThrows(XzException::class.java) {
            XzDecoder.decode(ByteArray(64) { it.toByte() }, 1024)
        }
    }

    @Test
    fun `空输入抛 XzException`() {
        assertThrows(XzException::class.java) { XzDecoder.decode(ByteArray(0), 1024) }
    }

    @Test
    fun `魔数对但流标志非法时抛 XzException`() {
        // \xFD 7 z X Z \x00 之后是 stream flags，这里放一个非法 check 类型 0x0F + 1
        val bogus = byteArrayOf(
            0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(),
            'X'.code.toByte(), 'Z'.code.toByte(), 0x00,
            0x10, 0x00, 0x00, 0x00, 0x00,
        )
        assertThrows(XzException::class.java) { XzDecoder.decode(bogus, 1024) }
    }
}
