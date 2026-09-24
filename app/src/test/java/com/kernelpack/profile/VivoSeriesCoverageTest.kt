package com.kernelpack.profile

import org.junit.Assert.assertTrue
import org.junit.Test

/** 蓝厂方案**实际覆盖**的内核系列分布（看清现状用）。 */
class VivoSeriesCoverageTest {

    private fun seriesOf(scheme: BaselineScheme): List<String> =
        BaselineRegistry.allEntries
            .filter { it.scheme == scheme }
            // 用注册表自己的压缩函数，而不是 substringBeforeLast ——
            // 后者对两段式（`6.6`）会压成 `6`，正好把"两套词汇"的裂缝盖住。
            .map { BaselineRegistry.gkiSeriesOf(it.kernelSeries) }
            .distinct()

    @Test
    fun `蓝厂覆盖到 6_1 与 6_12 而不只是 6_6`() {
        val v = seriesOf(BaselineScheme.VIVO)
        println("[蓝厂] 覆盖的大系列: $v")
        assertTrue("蓝厂应覆盖 6.1：$v", v.contains("6.1"))
        assertTrue("蓝厂应覆盖 6.6：$v", v.contains("6.6"))
        assertTrue("蓝厂应覆盖 6.12：$v", v.contains("6.12"))
    }

    @Test
    fun `通用方案同样三族齐全`() {
        val u = seriesOf(BaselineScheme.UNIVERSAL)
        println("[通用] 覆盖的大系列: $u")
        assertTrue(u.contains("6.1"))
        assertTrue(u.contains("6.6"))
        assertTrue(u.contains("6.12"))
    }
}
