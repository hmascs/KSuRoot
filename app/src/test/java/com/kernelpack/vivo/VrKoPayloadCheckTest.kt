package com.kernelpack.vivo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 载荷检查闸门的第一半：**只读判据**（字节 → 带没带 vr.ko 抹标记）。
 *
 * 这组用例里最关键的一条是**负例** —— 喂一份**确定没有绕过**的真实载荷
 * （`libksu_vivo_iqoo12_a15.so`），断言判为"没有"。
 * 没有这条，一个恒返回 `PRESENT` 的实现也能让所有正例通过。
 *
 * 用的是**仓库里真实随包的 `.so`**，不是手搓的字节：判据的区分度只有在真实语料上
 * 才算数（实测 36 份有 / 89 份没有，两条判据完全一致、零假阳性）。
 */
class VrKoPayloadCheckTest {

    @Test
    fun `负例 —— 没有绕过的真实蓝厂载荷必须判为 ABSENT`() {
        val r = VrKoPayloadCheck.check(payload("libksu_vivo_iqoo12_a15.so"))
        assertEquals(VrKoPayloadCheck.Status.ABSENT, r.status)
        assertFalse(r.hasBypass)
        // 证据要如实：ELF 解析成功、可执行节扫到了字节，才谈得上"确认没有"
        assertTrue("应解析成功", r.evidence.elfParsed)
        assertTrue("应扫到可执行节", r.evidence.executableBytes > 0)
        assertFalse("不该有日志串", r.evidence.detagLogMarker)
        assertFalse("机器码特征不该成立", r.evidence.codeSignature)
    }

    @Test
    fun `负例2 —— 另一份没有绕过的蓝厂载荷`() {
        val r = VrKoPayloadCheck.check(payload("libksu_vivo_iqooz95gvivot35grootghos_any_3cc6.so"))
        assertEquals(VrKoPayloadCheck.Status.ABSENT, r.status)
        assertFalse(r.hasBypass)
    }

    @Test
    fun `通用方案载荷不该带绕过 —— 它本来就不该带`() {
        assertEquals(
            VrKoPayloadCheck.Status.ABSENT,
            VrKoPayloadCheck.check(payload("libionstack.so")).status,
        )
    }

    @Test
    fun `正例 —— 本轮重编的两份族基线必须判为 PRESENT`() {
        for (name in listOf("libbaseline_6_1.so", "libbaseline_6_12.so")) {
            val r = VrKoPayloadCheck.check(payload(name))
            assertEquals("$name 应带 vr.ko 抹标记", VrKoPayloadCheck.Status.PRESENT, r.status)
            // 两条判据都要成立，才说明"日志串 + 机器码"这套组合真的对上了
            assertTrue("$name 应有日志串", r.evidence.detagLogMarker)
            assertTrue("$name 应有机器码特征", r.evidence.codeSignature)
            assertTrue("$name 两条判据应同时成立", r.evidence.bothSignals)
        }
    }

    @Test
    fun `正例 —— 蓝厂 6_6 all-in-one 载荷（上游自带）`() {
        val r = VrKoPayloadCheck.check(payload("libbs.so"))
        assertEquals(VrKoPayloadCheck.Status.PRESENT, r.status)
        assertTrue(r.evidence.bothSignals)
    }

    @Test
    fun `不是 ELF 的字节判为 UNPARSEABLE —— 不是 ABSENT`() {
        // "解析不了"与"确认没有"必须分开：闸门给的原因不同。
        val r = VrKoPayloadCheck.check(ByteArray(64) { 0x41 })
        assertEquals(VrKoPayloadCheck.Status.UNPARSEABLE, r.status)
        assertFalse(r.evidence.elfParsed)
    }

    @Test
    fun `空字节判为 UNPARSEABLE 且不崩`() {
        assertEquals(VrKoPayloadCheck.Status.UNPARSEABLE, VrKoPayloadCheck.check(ByteArray(0)).status)
    }

    @Test
    fun `只有日志串、没有机器码时也判 PRESENT（任一判据成立即可）`() {
        // 故意构造"ELF 头都缺"的输入：只要那条只可能由 patch_task_vr_tag 打出的串在，
        // 就说明这份二进制是从带绕过的源码编出来的。
        val bytes = ("\u0000\u0000" + VrKoPayloadCheck.DETAG_LOG_MARKER + " ok=%d").toByteArray()
        val r = VrKoPayloadCheck.check(bytes)
        assertEquals(VrKoPayloadCheck.Status.PRESENT, r.status)
        assertTrue(r.evidence.detagLogMarker)
        assertFalse("ELF 没解析成功", r.evidence.elfParsed)
    }

    @Test
    fun `只有 add 0x2c 而没有清 0x400 时不足以判定 —— 单靠它会有假阳性`() {
        // 实测：6 份**没有**绕过的载荷里也有 `add #0x2c`。所以机器码特征
        // 必须"两个都要"，不能只看 tag B 的偏移。
        val e = VrKoPayloadCheck.Evidence(
            detagLogMarker = false,
            tagAAddSites = 1,
            tagBAddSites = 1,
            syscallTpFlagClearSites = 0,
            elfParsed = true,
            executableBytes = 4096,
        )
        assertFalse(e.codeSignature)
    }

    // ── 真实文件定位 ────────────────────────────────────────────────
    //
    // 单测的工作目录是 Gradle 模块目录（app/）。找不到就**直接失败**，
    // 不静默跳过 —— 静默跳过会让这组用例在文件被挪走之后变成"全绿的空壳"。
    private fun payload(name: String): ByteArray {
        val candidates = listOf(
            File("src/main/jniLibs/arm64-v8a/$name"),
            File("app/src/main/jniLibs/arm64-v8a/$name"),
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（cwd=${File(".").absolutePath}）")
        return f.readBytes()
    }
}
