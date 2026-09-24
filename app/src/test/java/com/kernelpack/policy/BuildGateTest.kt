package com.kernelpack.policy

import com.kernelpack.profile.BaselineScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 硬闸门的测试 —— 用例全部来自**真机实测**，不是编出来的。
 *
 * 最关键的一条是 [五点十内核配六点六基线必须拒绝]：
 * 那正是用户拿 5.10.246 的 boot.img 配 GKI 6.6 基线档位、界面只给提示不给拦截、
 * 最后打出一个「需要改写 25 项 · 校验通过 12 项」的载荷的真实事故。
 * 现在这条路径必须被拒绝。
 */
class BuildGateTest {

    /**
     * vivo 5.10.246（VIVO300I.img），无 BTF。
     * 布局按开源 target.h 外推：yakidango-official/GhostLock-H80GT 的 5.10.236
     * 明确写着 task@0x30 lock@0x38 prio@0x40 deadline@0x48 → 80 字节（flat-10w）。
     */
    private val vivo510 = "5.10.246-gki-g786fdfdbfaf0-dirty"

    /** 实测：6.1.145（用户提供的 boot.img），waiter 88 字节（flat-11w）。 */
    private val k61 = "6.1.145-android14-11-maybe-dirty"

    /** 实测：本机 6.6.89-android15-8 GKI，waiter 112 字节（nested-6.6）。 */
    private val k66 = "6.6.89-android15-8-g1f71897ac249-abogki467805059-4k"

    @Test
    fun `布局族别按实测锚点判定`() {
        assertEquals(LayoutFamily.FLAT_10W, BuildGate.familyForRelease(vivo510))
        assertEquals(80, BuildGate.familyForRelease(vivo510).sizeBytes)
        // 5.15.178 实测 88 字节（mt6985 VERIFICATION.md：ABI XML + 真机反汇编双证）
        assertEquals(LayoutFamily.FLAT_11W, BuildGate.familyForRelease("5.15.178-android13-8-gfb31f5bdd612"))
        assertEquals(LayoutFamily.FLAT_11W, BuildGate.familyForRelease(k61))
        assertEquals(88, BuildGate.familyForRelease(k61).sizeBytes)
        assertEquals(LayoutFamily.NESTED_6_6, BuildGate.familyForRelease(k66))
        assertEquals(112, BuildGate.familyForRelease(k66).sizeBytes)
        assertEquals(LayoutFamily.UNKNOWN, BuildGate.familyForRelease("不是版本号"))
    }

    @Test
    fun `五点十内核配六点六基线必须拒绝`() {
        // 开「5.x 支持」开关：本用例要验证的是**ABI 布局冲突**那条路径，
        // 不这样的话会先被 5.x 开关拦下，验不到我们想验的东西。
        val d = BuildGate.evaluate(
            kernelRelease = vivo510,
            baselineAbiSeries = "6.6",
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            allowTestKernel = true,
        )
        assertTrue("这条路径必须被拒绝，实际 $d", d is GateDecision.Blocked)
        val b = d as GateDecision.Blocked
        val text = (listOf(b.title) + b.detail + b.remedy).joinToString("\n")
        assertTrue("要说明两边布局不同：$text", text.contains("flat-10w") && text.contains("nested-6.6"))
        assertTrue("要给出 80 / 112 字节的对比：$text", text.contains("80") && text.contains("112"))
    }

    @Test
    fun `打开忽略冲突后放行但必须带风险提示`() {
        val d = BuildGate.evaluate(vivo510, "6.6", SeriesOverride.AUTO, allowMismatch = true, allowTestKernel = true)
        assertTrue("应放行，实际 $d", d is GateDecision.ProceedWithWarning)
        val notes = (d as GateDecision.ProceedWithWarning).notes
        assertTrue("必须留下风险提示", notes.any { it.contains("忽略冲突") || it.contains("风险") })
        assertTrue("必须复述冲突原因", notes.any { it.contains("6.6") })
    }

    @Test
    fun `基线 ABI 与实测一致时放行`() {
        val d = BuildGate.evaluate(k66, "6.6", SeriesOverride.AUTO, allowMismatch = false)
        assertTrue("一致就该放行，实际 $d", d is GateDecision.Proceed)
        assertTrue(d.ok)
    }

    @Test
    fun `强制指定与实测冲突时拒绝`() {
        val d = BuildGate.evaluate(k61, baselineAbiSeries = null,
            override = SeriesOverride.FORCE_5, allowMismatch = false)
        assertTrue("强制 5 系但实测 6.1 应拒绝，实际 $d", d is GateDecision.Blocked)
        assertTrue((d as GateDecision.Blocked).detail.any { it.contains("强制指定 5.x") })
    }

    @Test
    fun `不支持的内核主版本直接拒绝`() {
        val d = BuildGate.evaluate("4.19.200-android11", null, SeriesOverride.AUTO, allowMismatch = false)
        assertTrue(d is GateDecision.Blocked)
        // 即使开了忽略冲突，4.x 也不该放行 —— 布局与 5/6 系都不同，没有"回退默认值"这回事
        val d2 = BuildGate.evaluate("4.19.200-android11", null, SeriesOverride.AUTO, allowMismatch = true)
        assertTrue("4.x 不应因忽略冲突而放行，实际 $d2", d2 is GateDecision.Blocked)
    }

    @Test
    fun `没有基线时不因 ABI 拒绝`() {
        // [2026-09-12 变更] 5.x 现在默认**被「5.x 支持」开关拦下**，所以这里显式开开关，
        // 才能验证"缺基线本身不构成拒绝理由"这条原始语义。
        val d = BuildGate.evaluate(vivo510, baselineAbiSeries = null,
            override = SeriesOverride.AUTO, allowMismatch = false, allowTestKernel = true)
        assertFalse("没有基线就无从比对 ABI，不该拒绝，实际 $d", d is GateDecision.Blocked)
        assertTrue("应放行（允许带提示），实际 $d", d.ok)
        assertTrue("放行时必须带上 5.x 的测试线提示，实际 $d",
            d is GateDecision.ProceedWithWarning &&
                d.notes.any { it.contains("测试内核") })
    }

    // ================= 「5.x 内核支持（beta）」开关 =================

    @Test
    fun `开关关闭时识别到五系内核直接拒绝构建`() {
        // 这是本轮的核心要求：默认不采用五系方案，而是**明确拦住**
        val d = BuildGate.evaluate(
            kernelRelease = vivo510,
            baselineAbiSeries = null,
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            allowTestKernel = false,
        )
        assertTrue("关着开关时 5.x 必须被拒绝，实际 $d", d is GateDecision.Blocked)
        val b = d as GateDecision.Blocked
        assertTrue("标题要点明是 5.x 支持未开启", b.title.contains("5.x"))
        assertTrue("要告诉用户去哪儿开，实际：${b.remedy}", b.remedy.contains("设置"))
        assertTrue("要说明为什么默认不放行", b.detail.any { it.contains("未经本工程验证") })
    }

    @Test
    fun `开关关闭时即使忽略冲突也不放行五系`() {
        // allowMismatch 是 ABI 冲突的逃生门，**不该**顺手把"未验证的 5.x"也放过去。
        // 两者是不同性质的拦截，不能共用一个开关。
        val d = BuildGate.evaluate(
            kernelRelease = vivo510,
            baselineAbiSeries = null,
            override = SeriesOverride.AUTO,
            allowMismatch = true,
            allowTestKernel = false,
        )
        assertTrue("忽略冲突不能绕过 5.x 开关，实际 $d", d is GateDecision.Blocked)
    }

    @Test
    fun `开关打开后五系内核可以构建但仍带测试提示`() {
        val d = BuildGate.evaluate(
            kernelRelease = vivo510,
            baselineAbiSeries = null,
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            allowTestKernel = true,
        )
        assertTrue("开了开关就该放行，实际 $d", d.ok)
        assertTrue("仍要提示测试线", d is GateDecision.ProceedWithWarning &&
            d.notes.any { it.contains("测试内核") })
        assertTrue("提示里要提到用户已手动开启",
            (d as GateDecision.ProceedWithWarning).notes.any { it.contains("已手动开启") })
    }

    @Test
    fun `开关不影响六系主线内核`() {
        // 6.x 是主线，开关开不开都必须能构建 —— 否则开关就变成了"全局禁用"
        for (allow in listOf(false, true)) {
            val d = BuildGate.evaluate(
                kernelRelease = k66,
                baselineAbiSeries = "6.6",
                override = SeriesOverride.AUTO,
                allowMismatch = false,
                allowTestKernel = allow,
            )
            assertTrue("6.6 不应受 5.x 开关影响（allowTestKernel=$allow），实际 $d", d.ok)
        }
    }

    @Test
    fun `开关默认值是关闭`() {
        // 漏传参数时必须落在安全侧：拒绝未验证的 5.x
        val d = BuildGate.evaluate(
            kernelRelease = vivo510,
            baselineAbiSeries = null,
            override = SeriesOverride.AUTO,
            allowMismatch = false,
        )
        assertTrue("默认应拒绝 5.x，实际 $d", d is GateDecision.Blocked)
    }

    // ================= 任务1：闸门接入 BaselineRegistry =================

    /** 取出判定结果里给用户看的 notes。 */
    private fun warningsOf(d: GateDecision): List<String> =
        if (d is GateDecision.ProceedWithWarning) d.notes else emptyList()

    @Test
    fun `方案未知时闸门跳过注册表建议`() {
        // 方案都不知道就去比对四元组，报出来的多半是假警报 —— 所以默认不查。
        // 这条同时锁住向后兼容：默认参数下 Proceed 语义与改造前一致。
        val d = BuildGate.evaluate(k66, "6.6", SeriesOverride.AUTO, allowMismatch = false)
        assertTrue("方案未知应保持原语义 Proceed，实际 $d", d is GateDecision.Proceed)
    }

    @Test
    fun `方案已知且四元组命中时给出具体建议`() {
        val d = BuildGate.evaluate(
            kernelRelease = k66,
            baselineAbiSeries = "6.6",
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            scheme = BaselineScheme.VIVO,
        )
        val notes = warningsOf(d)
        assertTrue("应带出注册表建议，实际 $d", notes.isNotEmpty())
        assertTrue(
            "建议里要点出匹配到的档位，实际：$notes",
            notes.any { it.contains("PD2520") && it.contains("基线建议") },
        )
    }

    @Test
    fun `选中的档位与注册表推荐不一致时明确提示`() {
        // 用户选了 IonStack 通用档，但 vivo 方案下注册表推荐 PD2520
        val d = BuildGate.evaluate(
            kernelRelease = k66,
            baselineAbiSeries = "6.6",
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            scheme = BaselineScheme.VIVO,
            profileId = "IONSTACK-P10-CP2A.260605.012",
        )
        val notes = warningsOf(d)
        assertTrue(
            "应提示更贴合的档位，实际：$notes",
            notes.any { it.contains("更贴合") && it.contains("PD2520") },
        )
    }

    @Test
    fun `缺基线时如实上报并附获取途径`() {
        // 用户那台 5.10.246 的 VIVO300I.img：注册表里确实没有 5.10 基线
        val d = BuildGate.evaluate(
            kernelRelease = vivo510,
            baselineAbiSeries = null,
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            scheme = BaselineScheme.VIVO,
            allowTestKernel = true,
        )
        val notes = warningsOf(d)
        assertTrue("应报缺基线，实际 $d", notes.any { it.contains("基线缺失") })
        assertTrue(
            "缺基线必须附获取途径（点名已核实来源），实际：$notes",
            notes.any { it.contains("GhostLock-H80GT") },
        )
        // 关键：缺基线**不阻断** —— 阻断只留给确定性的 ABI 冲突
        assertTrue("缺基线不应阻断构建，实际 $d", d.ok)
    }

    @Test
    fun `缺基线但用户选了别的系列的档位要点名`() {
        val d = BuildGate.evaluate(
            kernelRelease = vivo510,
            baselineAbiSeries = null,
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            scheme = BaselineScheme.VIVO,
            profileId = "PD2520-BP2A.250605.031.A3",
            allowTestKernel = true,
        )
        val notes = warningsOf(d)
        assertTrue(
            "应点名所选档位没有登记在 5.10 档下，实际：$notes",
            notes.any { it.contains("PD2520") && it.contains("没有登记") },
        )
    }

    @Test
    fun `ABI 冲突仍然优先阻断且不受注册表影响`() {
        // 5.10 实测 + 6.6 基线 = 原始事故场景，必须仍然硬拦
        val d = BuildGate.evaluate(
            kernelRelease = vivo510,
            baselineAbiSeries = "6.6",
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            scheme = BaselineScheme.VIVO,
            allowTestKernel = true,
        )
        assertTrue("ABI 冲突必须仍然阻断，实际 $d", d is GateDecision.Blocked)
    }

    @Test
    fun `六点一强配六点六基线仍被拦下`() {
        // 与既有用例同场景，但这次带上 scheme，确认接入注册表后拦截没有变松
        val d = BuildGate.evaluate(
            kernelRelease = k61,
            baselineAbiSeries = "6.6",
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            scheme = BaselineScheme.VIVO,
            profileId = "PD2520-BP2A.250605.031.A3",
        )
        assertTrue("6.1 配 6.6 基线必须阻断，实际 $d", d is GateDecision.Blocked)
    }
}
