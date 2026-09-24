package com.kernelpack.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游 50 档**能不能被构建流程查到**。
 *
 * 这组用例的存在理由是一次真实事故：50 档按**小版本**登记（`6.6.118`），
 * 而构建流程传进来的是**大系列**（`6.6`）—— 两者对不上，
 * 于是登记得再全也**一次都不会被选中**，是死数据。
 *
 * 单测当时全绿，因为没有任何一条用例去问"这条路由走不走得通"。
 * 所以这组用例补的就是那一问：**登记了，还要能被查到。**
 */
class UpstreamRoutingTest {

    private val sample = GhostLockKernelOffsets.KERNELS.first()

    @Test
    fun `按完整内核串能路由到上游档`() {
        val id = BaselineRegistry.profileIdFor(
            BaselineScheme.UNIVERSAL,
            sample.release.substringBefore('-'),
            sample.release,
        )
        assertNotNull("按完整内核串查不到 —— 上游档又变成死数据了", id)
        assertTrue("应命中上游档，实际 $id", id!!.startsWith("up-"))
    }

    @Test
    fun `按小版本也能路由到上游档`() {
        val id = BaselineRegistry.profileIdFor(
            BaselineScheme.UNIVERSAL,
            "6.6",
            sample.release.substringBefore('-'),
        )
        assertNotNull(id)
        assertTrue(id!!.startsWith("up-"))
    }

    @Test
    fun `没有内核串时退回大系列 —— 手写实测档不受影响`() {
        // 回归：三级路由不能把原有行为改掉
        val id = BaselineRegistry.profileIdFor(BaselineScheme.UNIVERSAL, "6.6", null)
        assertNotNull("手写档应仍能按大系列查到", id)
        assertTrue("应命中手写档而非上游档：$id", !id!!.startsWith("up-"))
    }

    @Test
    fun `未登记的内核串不会命中任何上游档 —— 不做前缀推断`() {
        val id = BaselineRegistry.profileIdFor(
            BaselineScheme.UNIVERSAL,
            "6.6",
            "6.6.118-android15-8-gdeadbeefdead-ab000000000-4k",
        )
        // 完整串不存在 → 退到小版本 6.6.118（存在）→ 命中上游档
        // 但换成完全没登记的版本，就必须落到大系列的手写档，而不是"最接近的上游档"
        val id2 = BaselineRegistry.profileIdFor(
            BaselineScheme.UNIVERSAL,
            "6.6",
            "6.6.999-android15-8-gdeadbeefdead-ab000000000-4k",
        )
        assertNotNull(id2)
        assertTrue("未登记的小版本不该命中上游档：$id2", !id2!!.startsWith("up-"))
    }

    @Test
    fun `每一档上游内核都能按自己的完整串查到`() {
        val miss = GhostLockKernelOffsets.KERNELS.filter { k ->
            BaselineRegistry.profileIdFor(
                BaselineScheme.UNIVERSAL, k.release.substringBefore('-'), k.release,
            )?.startsWith("up-") != true
        }
        assertTrue(
            "以下上游档按完整串查不到：${miss.map { it.release }}",
            miss.isEmpty(),
        )
    }

    @Test
    fun `蓝厂方案同样能路由到派生的上游档`() {
        val k = GhostLockKernelOffsets.KERNELS.first()
        val id = BaselineRegistry.profileIdFor(
            BaselineScheme.VIVO, k.release.substringBefore('-'), k.release,
        )
        assertNotNull("蓝厂查不到派生档", id)
        assertTrue("蓝厂应命中 -vivo 派生档：$id", id!!.endsWith("-vivo"))
        assertTrue("派生档必须是 beta", BaselineRegistry.isBetaFor(
            BaselineScheme.VIVO, k.release.substringBefore('-'), k.release,
        ))
    }

    @Test
    fun `上游档一律 beta 而手写档不是`() {
        assertTrue(BaselineRegistry.isBetaFor(
            BaselineScheme.UNIVERSAL, sample.release.substringBefore('-'), sample.release,
        ))
        assertTrue(!BaselineRegistry.isBetaFor(BaselineScheme.UNIVERSAL, "6.6", null))
    }
}
