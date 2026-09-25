package com.kernelpack.vivo

import com.kernelpack.profile.BaselineScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 载荷检查闸门的第二半：**方案闸门**（什么时候必须拦）。
 *
 * 三条必须钉死的：
 * 1. 蓝厂方案 + 没有绕过的载荷 → **拦住**（并且原因说得清）；
 * 2. 通用方案 → **不触发这条检查**（连 ELF 都不解析）；
 * 3. 蓝厂方案 + 带绕过的载荷 → 放行。
 *
 * 第 2 条用**一堆垃圾字节**来证：如果通用方案也去解析 ELF，垃圾字节会走到
 * `UNPARSEABLE` 那条路，从而被判 `Blocked` —— 那就说明检查没有短路掉。
 */
class VrKoPayloadGateTest {

    @Test
    fun `通用方案完全不触发这条检查 —— 喂垃圾字节也必须 NotApplicable`() {
        val garbage = ByteArray(4096) { 0x41 }
        assertEquals(
            VrKoGateDecision.NotApplicable,
            VrKoPayloadGate.decide(BaselineScheme.UNIVERSAL, "libionstack.so", garbage),
        )
    }

    @Test
    fun `未知方案也不触发 —— 这条检查只属于蓝厂方案`() {
        val garbage = ByteArray(4096) { 0x41 }
        assertEquals(
            VrKoGateDecision.NotApplicable,
            VrKoPayloadGate.decide(BaselineScheme.UNKNOWN, "whatever.so", garbage),
        )
    }

    @Test
    fun `蓝厂方案 + 没有绕过的真实载荷 → 拦住`() {
        val decision = VrKoPayloadGate.decide(
            BaselineScheme.VIVO,
            "libksu_vivo_iqoo12_a15.so",
            payload("libksu_vivo_iqoo12_a15.so"),
        )
        assertTrue("必须拦住，实际 $decision", decision is VrKoGateDecision.Blocked)
        val blocked = decision as VrKoGateDecision.Blocked
        assertEquals(VrKoPayloadGate.TITLE, blocked.title)
        // 原因必须具体到判据，不能只说"不合格"
        assertTrue(
            "正文要给出判据，实际：${blocked.detail}",
            blocked.detail.any { it.contains("vr detag") },
        )
        assertTrue(
            "正文要说明后果，实际：${blocked.detail}",
            blocked.detail.any { it.contains("sys_exit") },
        )
        assertTrue("要给可执行的替代方案", blocked.remedy.contains("libbs.so"))
    }

    @Test
    fun `蓝厂方案 + 读不出来的字节 → 也拦住，但原因不同`() {
        val decision = VrKoPayloadGate.decide(
            BaselineScheme.VIVO,
            "broken.so",
            ByteArray(64) { 0x00 },
        )
        assertTrue(decision is VrKoGateDecision.Blocked)
        val blocked = decision as VrKoGateDecision.Blocked
        assertTrue(
            "要区分「解析不了」与「确认没有」，实际：${blocked.detail}",
            blocked.detail.any { it.contains("无法确认") },
        )
    }

    @Test
    fun `蓝厂方案 + 6_6 自带绕过载荷 → 放行`() {
        assertEquals(
            VrKoGateDecision.Pass,
            VrKoPayloadGate.decide(BaselineScheme.VIVO, "libbs.so", payload("libbs.so")),
        )
    }

    @Test
    fun `蓝厂方案 + 本轮重编的两份族基线 → 放行`() {
        for (name in listOf("libbaseline_6_1.so", "libbaseline_6_12.so")) {
            assertEquals(
                "$name 应该过得了蓝厂闸门",
                VrKoGateDecision.Pass,
                VrKoPayloadGate.decide(BaselineScheme.VIVO, name, payload(name)),
            )
        }
    }

    @Test
    fun `蓝厂方案 + 通用载荷 → 拦住（通用载荷不该被路由到蓝厂方案）`() {
        val decision = VrKoPayloadGate.decide(
            BaselineScheme.VIVO,
            "libionstack.so",
            payload("libionstack.so"),
        )
        assertTrue(decision is VrKoGateDecision.Blocked)
    }

    @Test
    fun `Pass 与 NotApplicable 是两个不同的结论`() {
        // 若把"不适用"也实现成 Pass，界面上就分不清"检查过了、它带"与"根本没检查"。
        assertFalse(VrKoGateDecision.NotApplicable == VrKoGateDecision.Pass)
    }

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
