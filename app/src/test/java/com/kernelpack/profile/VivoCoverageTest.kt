package com.kernelpack.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝厂方案的档数**与可达性**。
 *
 * 光有档数不算支持 —— 每一档都必须能被三级路由按自己的完整内核串查到，
 * 否则就是"登记了但选不中"的死数据（这个坑本轮已经踩过一次）。
 */
class VivoCoverageTest {

    private val vivoAll = BaselineRegistry.allEntries.filter { it.scheme == BaselineScheme.VIVO }

    @Test
    fun `蓝厂总档数`() {
        val handWritten = vivoAll.count { !it.profile.id.startsWith("up-") }
        val derived = vivoAll.count { it.profile.id.startsWith("up-") }
        println("[蓝厂] 手写 $handWritten 档 + 派生 $derived 档 = 共 ${vivoAll.size} 档")
        assertEquals("派生档应等于上游档数", 50, derived)
        assertTrue("总档数应 >= 51", vivoAll.size >= 51)
    }

    @Test
    fun `每一档蓝厂派生档都能按完整内核串查到`() {
        val miss = GhostLockKernelOffsets.KERNELS.filter { k ->
            BaselineRegistry.profileIdFor(
                BaselineScheme.VIVO, k.release.substringBefore('-'), k.release,
            )?.endsWith("-vivo") != true
        }
        assertTrue(
            "以下上游档在蓝厂侧查不到：${miss.map { it.release }}",
            miss.isEmpty(),
        )
    }

    @Test
    fun `蓝厂手写档仍能按大系列查到且优先于派生档`() {
        val id = BaselineRegistry.profileIdFor(BaselineScheme.VIVO, "6.6", null)
        assertNotNull(id)
        assertTrue("6.6 生效的应是实测的 PD2520，不是派生档：$id", !id!!.endsWith("-vivo"))
    }

    @Test
    fun `派生档偏移与对应通用档逐字节一致 —— 是继承不是重算`() {
        for (u in BaselineRegistry.entriesWithUpstream.filter { it.scheme == BaselineScheme.UNIVERSAL }) {
            val d = vivoAll.firstOrNull { it.profile.id == u.profile.id + "-vivo" } ?: continue
            assertEquals("偏移被改动：${u.profile.id}", u.offsets.toString(), d.offsets.toString())
        }
    }

    @Test
    fun `蓝厂派生档全部标 beta`() {
        val notBeta = vivoAll.filter { it.profile.id.startsWith("up-") && !it.beta }
        assertTrue("未标 beta 的派生档：${notBeta.map { it.profile.id }}", notBeta.isEmpty())
    }

    @Test
    fun `蓝厂每一档都是 UPSTREAM 或实测，没有第三种来源`() {
        // 蓝厂派生档的偏移继承自上游档，来源层级应仍是 UPSTREAM
        val tiers = vivoAll.filter { it.profile.id.startsWith("up-") }
            .flatMap { it.offsets.notes.map { n -> n.tier } }
            .toSet()
        assertTrue(
            "蓝厂派生档的偏移层级应全为 UPSTREAM：$tiers",
            tiers.all { it == com.kernelpack.offset.SourceTier.UPSTREAM_TARGET_H },
        )
    }
}
