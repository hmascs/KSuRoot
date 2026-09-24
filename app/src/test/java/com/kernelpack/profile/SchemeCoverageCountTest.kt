package com.kernelpack.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案覆盖规模的**验收断言**。
 *
 * 这组用例把"通用 51 档 / 蓝厂 50 档"这个目标钉在代码里 ——
 * 否则以后谁动了 `entries` 或派生规则，覆盖规模会静默变化而没人发现。
 */
class SchemeCoverageCountTest {

    private val upstream = GhostLockKernelOffsets.KERNELS
    private val universal = BaselineRegistry.entriesWithUpstream
        .filter { it.scheme == BaselineScheme.UNIVERSAL }
    private val derived = BaselineRegistry.allEntries
        .filter { it.scheme == BaselineScheme.VIVO && it.profile.id.endsWith("-vivo") }

    @Test
    fun `上游偏移表是 50 档且每档都有真实符号数据`() {
        assertEquals("上游内核档数", 50, upstream.size)
        for (k in upstream) {
            assertTrue("${k.release} 没有符号数据", k.symbols.isNotEmpty())
            assertEquals("${k.release} 符号数应为 9", 9, k.symbols.size)
        }
    }

    @Test
    fun `通用方案共 51 档 —— 50 档上游 + 1 档手写实测`() {
        val handWritten = universal.count { !it.profile.id.startsWith("up-") }
        val fromUpstream = universal.count { it.profile.id.startsWith("up-") }
        assertEquals("手写实测档应为 1", 1, handWritten)
        assertEquals("上游档应为 50", 50, fromUpstream)
        assertEquals("通用方案合计应为 51", 51, universal.size)
    }

    @Test
    fun `蓝厂方案从通用方案派生 50 档上游档`() {
        // 蓝厂派生的是通用方案的**每一档**；其中 50 档来自上游、
        // 另有 1 档是通用方案原有的手写档派生（与蓝厂自己的 PD2520 同系列，会被实测档顶掉）。
        val derivedFromUpstream = derived.count { it.profile.id.startsWith("up-") }
        assertEquals("从上游派生的蓝厂档应为 50", 50, derivedFromUpstream)
        assertEquals("派生总数应等于通用方案档数", universal.size, derived.size)
    }

    @Test
    fun `蓝厂方案实际可路由的档位数`() {
        // 蓝厂 = 自己手写的 PD2520（6.6，实测）+ 派生档
        val vivo = BaselineRegistry.allEntries.filter { it.scheme == BaselineScheme.VIVO }
        assertTrue("蓝厂至少应有 50 档", vivo.size >= 50)
        val betaCount = vivo.count { it.beta }
        assertTrue("派生档应全部标 beta，实际 $betaCount", betaCount >= 50)
    }

    @Test
    fun `三族结构体偏移互不相同 —— 这是不能按版本外推的证据`() {
        val f61 = GhostLockKernelOffsets.structFields(GhostLockKernelOffsets.StructFamily.F6_1)
        val f66 = GhostLockKernelOffsets.structFields(GhostLockKernelOffsets.StructFamily.F6_6)
        val f612 = GhostLockKernelOffsets.structFields(GhostLockKernelOffsets.StructFamily.F6_12)
        assertTrue("6_1 与 6_6 的 pi_lock 不该相同", f61["task_pi_lock"] != f66["task_pi_lock"])
        assertTrue("6_6 与 6_12 的 pi_lock 不该相同", f66["task_pi_lock"] != f612["task_pi_lock"])
        assertTrue("6_1 与 6_12 的 tasks 不该相同", f61["task_tasks"] != f612["task_tasks"])
    }

    @Test
    fun `上游档的偏移标注是 UPSTREAM 而不是实测`() {
        // 铁律：借用别家的清单必须标 UPSTREAM，不得冒充实测
        val up = universal.first { it.profile.id.startsWith("up-") }
        val tiers = up.offsets.notes.map { it.tier }.toSet()
        assertTrue(
            "上游档的偏移标注里出现了非 UPSTREAM 的层级：$tiers",
            tiers.all { it == com.kernelpack.offset.SourceTier.UPSTREAM_TARGET_H },
        )
    }

    @Test
    fun `上游档全部标 beta`() {
        for (e in universal.filter { it.profile.id.startsWith("up-") }) {
            assertTrue("${e.profile.id} 没标 beta", e.beta)
        }
    }
}
