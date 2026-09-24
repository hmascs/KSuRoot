package com.kernelpack.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.kernelpack.profile.BaselineRegistry
import org.junit.Test

/**
 * 构建前「先看内核版本，再选方案」的测试。
 *
 * 这是用户明确要求的顺序：**不能**先开跑再在半路发现内核不对。
 */
class KernelSchemeSelectorTest {

    private val k612 = "6.12.23-android16-5-g82efd98459a2-ab14457512-4k"
    private val k66 = "6.6.89-android15-8-g1f71897ac249-abogki467805059-4k"
    private val k61 = "6.1.145-android14-11-maybe-dirty"
    private val k510 = "5.10.246-gki-g786fdfdbfaf0-dirty"
    private val k515 = "5.15.178-android13-8-gfb31f5bdd612"

    @Test
    fun `三个主线内核都选主线方案`() {
        for (release in listOf(k612, k66)) {
            val d = KernelSchemeSelector.select(release)
            assertTrue("$release 应是 Selected，实际 $d", d is KernelSchemeSelector.Decision.Selected)
            val s = d as KernelSchemeSelector.Decision.Selected
            assertFalse("$release 不该走测试方案", s.useTestScheme)
            assertEquals(KernelTier.MAINLINE, s.tier)
        }
    }

    @Test
    fun `主线内核不受五系开关影响`() {
        for (allow in listOf(false, true)) {
            val d = KernelSchemeSelector.select(k66, allowTestKernel = allow)
            assertTrue("allowTestKernel=$allow 时 6.6 仍应可选，实际 $d", d.ok)
        }
    }

    @Test
    fun `五系内核默认被拦下并给出开启路径`() {
        val d = KernelSchemeSelector.select(k510)
        assertTrue("默认应拦下 5.10，实际 $d", d is KernelSchemeSelector.Decision.Blocked)
        val b = d as KernelSchemeSelector.Decision.Blocked
        assertTrue("要指向设置页", b.remedy.contains("设置"))
        assertTrue("要说明未经本工程验证", b.detail.any { it.contains("未经本工程验证") })
    }

    @Test
    fun `五点十五也默认被拦下`() {
        assertTrue(KernelSchemeSelector.select(k515) is KernelSchemeSelector.Decision.Blocked)
    }

    @Test
    fun `开启开关后五系内核改用测试方案并带风险提示`() {
        val d = KernelSchemeSelector.select(k510, allowTestKernel = true)
        assertTrue("开了开关应可选，实际 $d", d is KernelSchemeSelector.Decision.Selected)
        val s = d as KernelSchemeSelector.Decision.Selected
        assertTrue("必须走测试方案", s.useTestScheme)
        assertEquals(KernelTier.TEST, s.tier)
        assertTrue("必须带风险提示", s.notes.any { it.contains("测试") })
    }

    @Test
    fun `认不出内核版本时拒绝而不是猜一个方案`() {
        for (bad in listOf("", "这不是版本号", "gki-dirty", "-android14-11")) {
            val d = KernelSchemeSelector.select(bad)
            assertTrue("「$bad」应被拒绝，实际 $d", d is KernelSchemeSelector.Decision.Blocked)
        }
    }

    @Test
    fun `四系与七系内核明确拒绝`() {
        for (bad in listOf("4.19.200-android11", "4.14.180", "7.0.1-android17")) {
            val d = KernelSchemeSelector.select(bad)
            assertTrue("$bad 应被拒绝，实际 $d", d is KernelSchemeSelector.Decision.Blocked)
            assertTrue((d as KernelSchemeSelector.Decision.Blocked).title.contains("不支持"))
        }
    }

    @Test
    fun `方案摘要能看出用的是哪条线`() {
        val main = KernelSchemeSelector.describe(KernelSchemeSelector.select(k612))
        assertTrue("主线摘要应含主线方案：$main", main.contains("主线方案"))
        assertTrue(main.contains("6.12"))

        val test = KernelSchemeSelector.describe(
            KernelSchemeSelector.select(k510, allowTestKernel = true),
        )
        assertTrue("测试摘要应含测试方案：$test", test.contains("测试方案"))
        assertTrue(test.contains("5.10"))
    }

    @Test
    fun `拒绝时摘要必须带上补救方式`() {
        val text = KernelSchemeSelector.describe(KernelSchemeSelector.select(k510))
        assertTrue("摘要要能直接给用户看，含补救方式：$text", text.contains("设置"))
    }

    @Test
    fun `主线与测试系列清单与注册表口径一致`() {
        // [2026-09-25] 主线加入 6.1 —— 它现在有专属基线（libbaseline_6_1.so，6_1 族）。
        assertEquals(listOf("6.1", "6.6", "6.12"), KernelSchemeSelector.MAINLINE_SERIES)
        // 注意 JUnit 三参重载是 (message, expected, actual) —— 顺序写反会报类型不匹配
        assertEquals(
            "选择器与注册表的主线清单必须同源，不能各说一套",
            BaselineRegistry.MAINLINE_SERIES,
            KernelSchemeSelector.MAINLINE_SERIES,
        )
    }
    // ================= 「强制指定」必须在**构建前**就拦下 =================

    @Test
    fun `强制六系但实测五系时必须前置拦下`() {
        // [勘误] 第一版 select() 没有 override 参数：这里会判 Selected，
        // 于是 boot.img 白解一遍、日志刷一屏，才被 KernelPack 内部的 BuildGate 拒绝。
        val d = KernelSchemeSelector.select(
            kernelRelease = k510,
            allowTestKernel = true,
            override = SeriesOverride.FORCE_6,
        )
        assertTrue("应前置拒绝，实际 $d", d is KernelSchemeSelector.Decision.Blocked)
        val b = d as KernelSchemeSelector.Decision.Blocked
        assertTrue("标题要说清是强制与实测冲突：${b.title}", b.title.contains("强制指定"))
        assertTrue("要指路去哪改：${b.remedy}", b.remedy.contains("设置"))
    }

    @Test
    fun `强制五系但实测六系同样前置拦下`() {
        val d = KernelSchemeSelector.select(k66, allowTestKernel = true, override = SeriesOverride.FORCE_5)
        assertTrue("应前置拒绝，实际 $d", d is KernelSchemeSelector.Decision.Blocked)
    }

    @Test
    fun `强制与实测一致时照常通过`() {
        val d = KernelSchemeSelector.select(k66, allowTestKernel = false, override = SeriesOverride.FORCE_6)
        assertTrue("一致就该放行，实际 $d", d is KernelSchemeSelector.Decision.Selected)
        assertFalse(
            "强制 6.x 配 6.6 不该被当成测试方案",
            (d as KernelSchemeSelector.Decision.Selected).useTestScheme,
        )
    }

    @Test
    fun `默认不强制时行为不变`() {
        val d = KernelSchemeSelector.select(k66)
        assertTrue("默认应可选，实际 $d", d is KernelSchemeSelector.Decision.Selected)
    }

    // ================= 设置页那一行用的短标签 =================

    @Test
    fun `每个系列都有非空的短标签`() {
        // 设置页那行右侧用 shortLabel 显示；为空的话那一行会看起来"没选中任何东西"
        for (o in SeriesOverride.entries) {
            assertTrue("${o.name} 的 shortLabel 不能为空", o.shortLabel.isNotBlank())
        }
    }

    @Test
    fun `测试线的短标签带测试字样`() {
        assertTrue(
            "5.x 的短标签要能一眼看出是测试：${SeriesOverride.FORCE_5.shortLabel}",
            SeriesOverride.FORCE_5.shortLabel.contains("测试"),
        )
    }
    // ================= 阻断归类（UI 靠它决定弹哪个框）=================

    @Test
    fun `阻断归类把五系开关单独识别出来`() {
        // 这一类是唯一有"一键去开启"路径的，归错了用户就会被引到一个没用的设置项
        val kind = com.ting.root.classifyBlock("识别到 5.x 内核，但「5.x 内核支持（beta）」未开启")
        assertEquals(com.ting.root.BuildBlockKind.TEST_KERNEL_DISABLED, kind)
    }

    @Test
    fun `阻断归类认得出 ABI 冲突与未知内核`() {
        assertEquals(
            com.ting.root.BuildBlockKind.ABI_CONFLICT,
            com.ting.root.classifyBlock("基线 ABI 冲突，已拒绝构建"),
        )
        assertEquals(
            com.ting.root.BuildBlockKind.ABI_CONFLICT,
            com.ting.root.classifyBlock("强制指定与实测内核不一致，已停止构建"),
        )
        assertEquals(
            com.ting.root.BuildBlockKind.UNKNOWN_KERNEL,
            com.ting.root.classifyBlock("认不出这个内核版本，已停止构建"),
        )
    }

    @Test
    fun `认不出来的标题落到 OTHER 而不是瞎猜`() {
        assertEquals(
            com.ting.root.BuildBlockKind.OTHER,
            com.ting.root.classifyBlock("完全没见过的错误"),
        )
        assertEquals(com.ting.root.BuildBlockKind.OTHER, com.ting.root.classifyBlock(""))
    }
    // ================= 6.1 已从主线移除（2026-09-12 口径）=================

    @Test
    fun `六点一现在有专属基线，属于主线`() {
        // [2026-09-25 更正] 旧断言写的是"6.1 必须被拒绝"，理由是
        // "6.1 是 flat 形态、与 6.6/6.12 的 nested 不是一套"。
        // **理由本身成立，但结论已过时** —— 我们已为 6.1 编出专属基线
        // （结构体偏移取 6_1 族），所以不再存在"拿 6.6 偏移硬打 6.1"的危险。
        val d = KernelSchemeSelector.select("6.1.145-android14-11-maybe-dirty")
        assertTrue("6.1 应被主线放行", d is KernelSchemeSelector.Decision.Selected)
        assertEquals("6.1", (d as KernelSchemeSelector.Decision.Selected).series)
        // 真正没有基线的 6.x 仍须拒绝 —— 这条不能松
        val bad = KernelSchemeSelector.select("6.5.1-android14-11-gabcdef123456")
        assertTrue("6.5 没有基线，必须拒绝", bad is KernelSchemeSelector.Decision.Blocked)
    }


}
