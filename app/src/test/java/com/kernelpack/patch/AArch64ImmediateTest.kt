package com.kernelpack.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AArch64] 新增的两个**立即数**解码器。
 *
 * 期望值不是推出来的，是 `llvm-objdump` 在真实载荷上**打出来的**：
 * ```
 *   23db8: 91001ae1   add  x1, x23, #0x6
 *   23dd8: 9100b2e1   add  x1, x23, #0x2c
 *   23df8: 9275f802   and  x2, x0,  #0xfffffffffffffbff
 *   2407c: 9274fae2   and  x2, x23, #0xfffffffffffff7ff
 *   23dbc: d10103a2   sub  x2, x29, #0x40
 * ```
 * 机器码是唯一不会说谎的东西 —— 所以这组用例直接用那 5 个 32 位字，
 * 而不是"自己编码一条再自己解回来"（那样两边一起错也测不出来）。
 */
class AArch64ImmediateTest {

    @Test
    fun `add 立即数 —— tag A 的 0x6`() {
        val a = AArch64.decodeAddImmediate(0x91001ae1.toInt())
        assertNotNull(a)
        assertEquals(6L, a!!.imm.toLong())
        assertTrue("64 位形式", a.sf)
        assertTrue("不是减法", !a.isSub)
        assertEquals(23L, a.rn.toLong())
        assertEquals(1L, a.rd.toLong())
    }

    @Test
    fun `add 立即数 —— tag B 的 0x2c`() {
        val a = AArch64.decodeAddImmediate(0x9100b2e1.toInt())
        assertNotNull(a)
        assertEquals(0x2cL, a!!.imm.toLong())
    }

    @Test
    fun `sub 立即数不能被当成 add`() {
        val a = AArch64.decodeAddImmediate(0xd10103a2.toInt())
        assertNotNull(a)
        assertTrue(a!!.isSub)
        assertEquals(0x40L, a.imm.toLong())
    }

    @Test
    fun `不是 add 立即数的指令返回 null`() {
        // bl（分支）与 sturb（存储）：都不该被解成 add
        assertNull(AArch64.decodeAddImmediate(0x94001060.toInt()))
        assertNull(AArch64.decodeAddImmediate(0x381903bf.toInt()))
        // movz：属于 mov 宽立即数那一组，不是 100010
        assertNull(AArch64.decodeAddImmediate(AArch64.encodeMovz(true, 0, 0x400, 0)))
    }

    @Test
    fun `逻辑立即数 —— 清 VR_SYSCALL_TP_FLAG 0x400 的那条 AND`() {
        val l = AArch64.decodeLogicalImmediate(0x9275f802.toInt())
        assertNotNull(l)
        assertEquals(0L, l!!.opc.toLong())
        assertTrue("64 位形式", l.sf)
        assertEquals(0x400L.inv(), l.bitmask)
    }

    @Test
    fun `逻辑立即数 —— 清 TIF_SECCOMP 0x800 的那条 AND 必须区分得开`() {
        // 同一个函数里的 seccomp 绕过用的是 0x800。掩码若区分不开，
        // "扫到一条清位指令"就分不清是 vr 还是 seccomp —— 判据会失去区分度。
        val l = AArch64.decodeLogicalImmediate(0x9274fae2.toInt())
        assertNotNull(l)
        assertEquals(0x800L.inv(), l!!.bitmask)
    }

    @Test
    fun `逻辑立即数 —— 编码非法的掩码返回 null`() {
        // N=0 且 imms=0b111111 是未分配编码
        assertNull(AArch64.decodeLogicalImmediate(0x1200FC00))
    }

    @Test
    fun `decodeBitMasks 的手算陷阱 —— 0xfffffffffffffbff 是合法掩码`() {
        // 它不是"任意 64 位数"，而是 len=6 时 3 位元素循环重复出来的。
        // 手算极易得出"这不是合法掩码"（本工程就差点栽在这上面），所以单列一条。
        // 字段直接来自上面那两条实测指令的机器码：
        //   0x9275f802 → N=1 immr=53 imms=62
        //   0x9274fae2 → N=1 immr=52 imms=62
        assertEquals(0x400L.inv(), AArch64.decodeBitMasks(n = 1, immr = 53, imms = 62, width = 64))
        assertEquals(0x800L.inv(), AArch64.decodeBitMasks(n = 1, immr = 52, imms = 62, width = 64))
    }
}
