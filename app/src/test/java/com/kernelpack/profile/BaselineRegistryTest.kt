package com.kernelpack.profile

import com.kernelpack.offset.OffsetNote
import com.kernelpack.offset.OffsetSet
import com.kernelpack.offset.OffsetStatus
import com.kernelpack.offset.PselectFeasibility
import com.kernelpack.offset.SourceTier
import com.kernelpack.offset.StackLayoutVerdict
import com.kernelpack.offset.WaiterLayout
import com.kernelpack.policy.BuildGate
import com.kernelpack.policy.GateDecision
import com.kernelpack.policy.KernelTier
import com.kernelpack.policy.LayoutFamily
import com.kernelpack.policy.SeriesOverride
import com.kernelpack.policy.tierOfRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四元组索引 + 来源标注 + pselect 可行性的测试。
 *
 * 用例全部来自**真实产物**，不是编的：
 * - 三个真实内核 release 串（本机 6.6.89 / 用户提供的 6.1.145 镜像 / vivo 5.10.246）
 * - 三种 waiter 形态的字段值（H80GT 5.10.236 target.h / 6.1.145 BTF / 本机 6.6.89 BTF）
 * - 上游 JoinChang 的落点实测表（word=3 / word=13 / word=-11 等）
 */
class BaselineRegistryTest {

    private val k66 = "6.6.89-android15-8-g1f71897ac249-abogki467805059-4k"
    private val k61 = "6.1.145-android14-11-maybe-dirty"
    private val k510 = "5.10.246-gki-g786fdfdbfaf0-dirty"

    // ---------------------------------------------------------------- GKI 分支解析

    @Test
    fun `真实 release 串能解析出 GKI 分支`() {
        assertEquals("android15-8", BaselineRegistry.gkiBranchOf(k66))
        assertEquals("android14-11", BaselineRegistry.gkiBranchOf(k61))
        assertEquals(
            "android16-5",
            BaselineRegistry.gkiBranchOf("6.12.23-android16-5-g82efd98459a2-ab14457512-4k"),
        )
    }

    @Test
    fun `厂商改过的 release 串解析不出分支时返回 null 而不是猜`() {
        // vivo 5.10.246 这条串里没有 -androidNN-K- 段
        assertNull(BaselineRegistry.gkiBranchOf(k510))
    }

    @Test
    fun `内核系列解析`() {
        assertEquals("6.6", BaselineRegistry.seriesOf(k66))
        assertEquals("6.1", BaselineRegistry.seriesOf(k61))
        assertEquals("5.10", BaselineRegistry.seriesOf(k510))
        assertNull(BaselineRegistry.seriesOf("没有版本号的串"))
    }

    // ---------------------------------------------------------------- 四元组查询

    @Test
    fun `六点六加正确分支命中且不降级`() {
        val r = BaselineRegistry.lookup(k66, BaselineScheme.VIVO, "android15-8")
        assertTrue(r is BaselineLookup.Found)
        val f = r as BaselineLookup.Found
        assertFalse(f.degraded)
        assertEquals("6.6", f.entry.kernelSeries)
        assertEquals("android15-8", f.entry.gkiBranch)
        assertEquals("PD2520", f.entry.device)
    }

    @Test
    fun `分支对不上时降级放行但必须明确提示`() {
        val r = BaselineRegistry.lookup(
            "6.6.89-android99-9-gdeadbeef", BaselineScheme.VIVO, "android99-9",
        )
        assertTrue(r is BaselineLookup.Found)
        val f = r as BaselineLookup.Found
        assertTrue("分支不匹配必须标 degraded", f.degraded)
        assertTrue("降级必须给出可读原因", f.notes.any { it.contains("GKI 分支不匹配") })
    }

    @Test
    fun `五点十没有基线时必须报 Missing 且给出来源`() {
        val r = BaselineRegistry.lookup(k510, BaselineScheme.VIVO, null)
        assertTrue(r is BaselineLookup.Missing)
        val m = r as BaselineLookup.Missing
        assertEquals("5.10", m.kernelSeries)
        assertTrue("必须列出获取途径", m.howTo.isNotEmpty())
        assertTrue("缺失报告里要点名已核实的来源仓库", m.howTo.any { it.contains("GhostLock-H80GT") })
        assertTrue("必须列出目前实际有哪些基线", m.available.isNotEmpty())
    }

    @Test
    fun `解析不出内核版本时也必须报 Missing 而不是异常`() {
        val r = BaselineRegistry.lookup("这不是版本号", BaselineScheme.VIVO, null)
        assertTrue(r is BaselineLookup.Missing)
        assertEquals("未知", (r as BaselineLookup.Missing).kernelSeries)
    }

    // ---------------------------------------------------------------- 来源标注

    @Test
    fun `两条内置基线的偏移组都是已验证档`() {
        for (e in BaselineRegistry.entries) {
            assertEquals(
                "${e.profile.id} 的偏移组应为 VERIFIED（都是内置载荷的编译期常量）",
                OffsetStatus.VERIFIED,
                e.offsets.status,
            )
            assertTrue(e.buildable)
        }
    }

    @Test
    fun `偏移组里混进占位符就整体降为占位符`() {
        val set = OffsetSet(
            OffsetNote("WAITER_TASK_OFF", SourceTier.MEASURED, "6.1.145", "本机 BTF"),
            // Meowkis/ghostlock-samsung-research 的 5.15.h 就是这种：文件名写着 5.15，数值不是量出来的
            OffsetNote("WAITER_LOCK_OFF", SourceTier.PLACEHOLDER, "5.15(未验证)", "Meowkis · 5.15.h"),
        )
        assertEquals(OffsetStatus.PLACEHOLDER, set.status)
        assertFalse("占位符组绝不能用于构建", set.usable)
        assertEquals(listOf("WAITER_LOCK_OFF"), set.weakest().map { it.key })
    }

    @Test
    fun `单条交叉参考就能把整组降为交叉参考`() {
        // 这正是 mt6985 VERIFICATION.md 的教训：照 AOSP 通用 GKI 分支抄 file_operations 错了 6 处
        val set = OffsetSet(
            OffsetNote("INIT_TASK", SourceTier.MEASURED, "6.6.89", "本机 BTF"),
            OffsetNote("FOPS_IOCTL", SourceTier.CROSS_REFERENCE, "6.6.89", "AOSP 通用分支 ABI XML"),
        )
        assertEquals(OffsetStatus.CROSS_REFERENCE, set.status)
        assertFalse(set.usable)
        assertTrue(set.summary().contains("FOPS_IOCTL"))
    }

    @Test
    fun `跨内核拼出来的组也算交叉参考`() {
        val set = OffsetSet(
            OffsetNote("A", SourceTier.MEASURED, "6.1.145", "x"),
            OffsetNote("B", SourceTier.MEASURED, "6.6.89", "y"),
        )
        assertEquals(OffsetStatus.CROSS_REFERENCE, set.status)
    }

    @Test
    fun `出处精度不同但内核同一个不算跨内核拼凑`() {
        // 回归用例：本工程第一版拿 measuredOn 做判定，把两条内置基线全降级了 ——
        // 载荷常量只能追到 "6.6.89"，而布局量自完整的
        // "6.6.89-android15-8-g1f71897ac249-abogki467805059-4k"，内核其实是同一个。
        val set = OffsetSet(
            OffsetNote(
                key = "INIT_TASK",
                tier = SourceTier.PAYLOAD_BAKED,
                measuredOn = "6.6.89",
                source = "载荷 .so",
                anchor = "6.6.89",
            ),
            OffsetNote(
                key = "RT_WAITER_LAYOUT",
                tier = SourceTier.MEASURED,
                measuredOn = "6.6.89-android15-8-g1f71897ac249-abogki467805059-4k",
                source = "本机 BTF",
                anchor = "6.6.89",
            ),
        )
        assertEquals(OffsetStatus.VERIFIED, set.status)
        assertTrue(set.usable)
        assertEquals("6.6.89", set.anchor)
    }

    @Test
    fun `同一偏移重复标注直接拒绝构造`() {
        val dup = runCatching {
            OffsetSet(
                OffsetNote("WAITER_TASK_OFF", SourceTier.MEASURED, "6.1.145", "a"),
                OffsetNote("WAITER_TASK_OFF", SourceTier.PLACEHOLDER, "6.1.145", "b"),
            )
        }
        assertTrue("重复键必须抛异常，不能静默取第一条", dup.isFailure)
    }

    // ---------------------------------------------------------------- 布局与可行性

    @Test
    fun `三档布局的字段字下标与实测一致`() {
        // 5.10.236：yakidango-official/GhostLock-H80GT annap-AGT-AN00_9.0.0.230/target.h
        assertEquals(0x30, WaiterLayout.COMPACT_5_10.taskWordRel * 8)
        assertEquals(0x38, WaiterLayout.COMPACT_5_10.lockWordRel * 8)
        assertEquals(80, WaiterLayout.COMPACT_5_10.sizeBytes)

        // 6.1.145：本轮从用户 boot.img 解出的裸内核里扫 BTF 实测
        assertEquals(0x30, WaiterLayout.FLAT_6_1.taskWordRel * 8)
        assertEquals(0x38, WaiterLayout.FLAT_6_1.lockWordRel * 8)
        assertEquals(88, WaiterLayout.FLAT_6_1.sizeBytes)

        // 6.6.89：本机 /sys/kernel/btf/vmlinux
        assertEquals(0x50, WaiterLayout.NESTED_6_6.taskWordRel * 8)
        assertEquals(0x58, WaiterLayout.NESTED_6_6.lockWordRel * 8)
        assertEquals(112, WaiterLayout.NESTED_6_6.sizeBytes)
    }

    @Test
    fun `形态按字下标反查而不是按内核版本猜`() {
        // 唯一的情况可以定型
        assertEquals(WaiterLayout.NESTED_6_6, WaiterLayout.of(10, 11))
        // 5.10 与 6.1 的 task/lock 字下标完全相同 → 这一对输入不足以定型，必须返回 UNKNOWN
        assertEquals(WaiterLayout.UNKNOWN, WaiterLayout.of(6, 7))
        assertTrue(
            "候选集必须同时列出 5.10 与 6.1 两档",
            WaiterLayout.fromWordRels(6, 7).containsAll(
                listOf(WaiterLayout.COMPACT_5_10, WaiterLayout.FLAT_6_1),
            ),
        )
        // 给不出任何形态的输入
        assertEquals(WaiterLayout.UNKNOWN, WaiterLayout.of(13, 14))
    }

    @Test
    fun `补上字节数或 wake_state 就能唯一确定形态`() {
        // 本轮实测：6.1.145 是 88 字节且**有** wake_state；5.10.236 是 80 字节且没有
        assertEquals(WaiterLayout.FLAT_6_1, WaiterLayout.of(6, 7, sizeBytes = 88))
        assertEquals(WaiterLayout.COMPACT_5_10, WaiterLayout.of(6, 7, sizeBytes = 80))
        assertEquals(WaiterLayout.FLAT_6_1, WaiterLayout.of(6, 7, hasWakeState = true))
        assertEquals(WaiterLayout.COMPACT_5_10, WaiterLayout.of(6, 7, hasWakeState = false))
        // 自相矛盾的输入不许硬凑一个答案
        assertEquals(WaiterLayout.UNKNOWN, WaiterLayout.of(6, 7, sizeBytes = 96))
    }

    @Test
    fun `落点上限采用上游文档值 3 与 7`() {
        assertEquals(7, WaiterLayout.COMPACT_5_10.maxFeasibleWord)
        assertEquals(7, WaiterLayout.FLAT_6_1.maxFeasibleWord)
        assertEquals(3, WaiterLayout.NESTED_6_6.maxFeasibleWord)
        assertEquals(-1, WaiterLayout.UNKNOWN.maxFeasibleWord)
    }

    @Test
    fun `vivo 六点一的实测落点 3 判为可行`() {
        // 上游 README：Vivo T4 / X Fold3 Pro「6.1 compact waiter, waiter word=3」
        val f = PselectFeasibility(
            layout = WaiterLayout.FLAT_6_1,
            measuredWord = 3,
            measuredOn = "JoinChang/ghostlock-oneplus · vivo T4",
        )
        assertEquals(StackLayoutVerdict.FEASIBLE, f.verdict)
        assertTrue(f.verdict.feasible)
    }

    @Test
    fun `OPLUS 六点一的落点 13 判为 PGO 阻断`() {
        // 上游 README：OnePlus 12 6.1.141「PGO inlines do_futex → waiter word=13/19」
        val f = PselectFeasibility(
            layout = WaiterLayout.FLAT_6_1,
            measuredWord = 13,
            measuredOn = "JoinChang/ghostlock-oneplus · OnePlus 12",
        )
        assertEquals(StackLayoutVerdict.PGO_BLOCKED, f.verdict)
        assertFalse(f.verdict.feasible)
    }

    @Test
    fun `负落点判为阻断`() {
        // iQOO Neo 10 CN 6.1.84 word=-11；OPPO PKW110 5.15.180 word=-29
        val f = PselectFeasibility(WaiterLayout.FLAT_6_1, measuredWord = -11, measuredOn = "iQOO Neo 10 CN")
        assertEquals(StackLayoutVerdict.PGO_BLOCKED, f.verdict)
    }

    @Test
    fun `没实测落点就是未实测而不是不可行`() {
        val f = PselectFeasibility.unmeasured(WaiterLayout.NESTED_6_6, "本工程未在那台机器上量过")
        assertEquals(StackLayoutVerdict.UNKNOWN, f.verdict)
        assertFalse("不知道不等于可行", f.verdict.feasible)
        assertTrue(f.summary().contains("未实测"))
    }

    @Test
    fun `未知布局即使给了落点也不下结论`() {
        val f = PselectFeasibility(WaiterLayout.UNKNOWN, measuredWord = 3, measuredOn = "x")
        assertEquals(StackLayoutVerdict.UNKNOWN, f.verdict)
    }

    @Test
    fun `边界落点恰好等于上限时判为可行`() {
        val f = PselectFeasibility(WaiterLayout.NESTED_6_6, measuredWord = 3, measuredOn = "边界用例")
        assertEquals(StackLayoutVerdict.FEASIBLE, f.verdict)
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `按字节识别基线仍能命中内置载荷标签`() {
        // 用 variantLabel 走字符串匹配那一路（sha256 对不上任何一条）
        val blob = "xxxx pd2520-bp2a.250605.031.a3 yyyy".toByteArray(Charsets.ISO_8859_1)
        val hit = BaselineRegistry.findByBytes(blob)
        assertNotNull(hit)
        assertEquals("PD2520", hit!!.device)
    }

    @Test
    fun `从未知载荷里认不出基线时返回 null`() {
        assertNull(BaselineRegistry.findByBytes("完全不认识的二进制".toByteArray()))
    }

    @Test
    fun `四元组字符串包含四个维度`() {
        val e = BaselineRegistry.entries.first { it.profile.id.startsWith("PD2520") }
        val q = e.quad()
        assertTrue(q.contains("PD2520"))
        assertTrue(q.contains("BP2A.250605.031.A3"))
        assertTrue(q.contains("6.6"))
        assertTrue(q.contains("android15-8"))
    }

    @Test
    fun `差异清单能解释为什么不匹配`() {
        val e = BaselineRegistry.entries.first()
        val d = e.diffAgainst("5.10", "android12-9")
        assertEquals(2, d.size)
        assertTrue(d.any { it.contains("内核系列") })
        assertTrue(d.any { it.contains("GKI 分支") })
    }
    // ---------------------------------------------------------------- 支持分级（2026-09-12 口径）

    @Test
    fun `主线只列六点六与六点十二`() {
        // 产品口径：通用方案与 vivo 方案都覆盖 6.6 / 6.12
        assertEquals(listOf("6.6", "6.12"), BaselineRegistry.MAINLINE_SERIES)
        assertTrue(BaselineRegistry.TEST_SERIES.all { it.startsWith("5.") })
    }

    @Test
    fun `内核支持分级按主版本判定`() {
        // 6.x 一律主线 —— 含 6.12（虽然它与 6.6 同 nested 族）
        assertEquals(KernelTier.MAINLINE, tierOfRelease("6.12.23-android16-5-g82efd98459a2"))
        assertEquals(KernelTier.MAINLINE, tierOfRelease(k66))
        // [2026-09-12] 6.1 已从主线移除：它是 6.x 但不在支持清单里 → 非主线
        assertEquals(KernelTier.TEST, tierOfRelease(k61))
        // 5.x 一律测试
        assertEquals(KernelTier.TEST, tierOfRelease(k510))
        assertEquals(KernelTier.TEST, tierOfRelease("5.15.178-android13-8-gfb31f5bdd612"))
        // 解析不出来的也不许当主线
        assertEquals(KernelTier.TEST, tierOfRelease("不是版本号"))
    }

    @Test
    fun `设置页的三个选项带上分级标签`() {
        assertEquals(KernelTier.TEST, SeriesOverride.FORCE_5.tier)
        assertEquals(KernelTier.MAINLINE, SeriesOverride.FORCE_6.tier)
        // UI 文案里必须出现「测试」两个字，用户才看得见
        assertTrue("5.x 选项标签必须标测试：${SeriesOverride.FORCE_5.label}",
            SeriesOverride.FORCE_5.label.contains("测试"))
        assertTrue("6.x 选项标签应标主线：${SeriesOverride.FORCE_6.label}",
            SeriesOverride.FORCE_6.label.contains("主线"))
        // wireValue 是持久化键，**不能**随着文案改动而变，否则老用户的设置会丢
        assertEquals("5", SeriesOverride.FORCE_5.wireValue)
        assertEquals("6", SeriesOverride.FORCE_6.wireValue)
        assertEquals("auto", SeriesOverride.AUTO.wireValue)
    }

    @Test
    fun `六点十二归入 nested 族而不是被当成未知`() {
        // 6.12 与 6.6 同族（JoinChang 的 STRUCT_OFFSETS_6_12 与 6_6 都是 nested 形态）
        assertEquals(LayoutFamily.NESTED_6_6, BuildGate.familyForRelease("6.12.23-android16-5"))
        assertEquals(LayoutFamily.NESTED_6_6, BuildGate.familyForRelease(k66))
        assertEquals(LayoutFamily.FLAT_11W, BuildGate.familyForRelease(k61))
        assertEquals(LayoutFamily.FLAT_10W, BuildGate.familyForRelease(k510))
        assertEquals(LayoutFamily.FLAT_11W, BuildGate.familyForRelease("5.15.178-android13-8"))
    }

    @Test
    fun `六点十二没有基线时报缺并说明它属于主线支持范围`() {
        val r = BaselineRegistry.lookup("6.12.23-android16-5-g82efd98459a2", BaselineScheme.VIVO, "android16-5")
        assertTrue("6.12 目前确实没有登记基线", r is BaselineLookup.Missing)
        val m = r as BaselineLookup.Missing
        assertTrue("要点明 6.12 属主线支持范围", m.notes.any { it.contains("主线") })
        assertTrue("要区分「支持」与「已登记」",
            m.howTo.any { it.contains("还没有登记") || it.contains("尚未登记") })
    }

    @Test
    fun `覆盖率报告把两个方案的三个主线档都列出来`() {
        val rep = BaselineRegistry.coverageReport()
        for (scheme in listOf(BaselineScheme.UNIVERSAL, BaselineScheme.VIVO)) {
            for (series in listOf("6.6", "6.12")) {
                assertTrue("报告缺少 $series / ${scheme.label}", rep.any { it.contains(series) && it.contains(scheme.label) })
            }
        }
        // 6.6 两条已登记，6.12 / 6.1 应显示未登记
        assertTrue(rep.any { it.contains("6.6") && it.contains("已登记") && !it.contains("未登记") })
        assertTrue(rep.any { it.contains("6.12") && it.contains("未登记") })
    }

    @Test
    fun `五点十走测试线时闸门会给出测试内核提示`() {
        val d = BuildGate.evaluate(
            kernelRelease = k510,
            baselineAbiSeries = null,
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            scheme = BaselineScheme.VIVO,
            // 本轮起 5.x 默认被开关拦下，要验"测试线提示"必须先开开关
            allowTestKernel = true,
        )
        val notes = if (d is GateDecision.ProceedWithWarning) d.notes else emptyList()
        assertTrue("5.x 必须提示属测试线，实际：$notes", notes.any { it.contains("测试内核") })
        assertTrue("提示里要点明当前主线是 6.6 / 6.12", notes.any { it.contains("6.6") && it.contains("6.12") })
        assertTrue("测试线仍应放行（不阻断），实际 $d", d.ok)
    }

    @Test
    fun `六系内核不会收到测试线提示`() {
        val d = BuildGate.evaluate(
            kernelRelease = k66,
            baselineAbiSeries = "6.6",
            override = SeriesOverride.AUTO,
            allowMismatch = false,
            scheme = BaselineScheme.VIVO,
        )
        val notes = if (d is GateDecision.ProceedWithWarning) d.notes else emptyList()
        assertFalse("6.6 是主线，不该出现测试内核提示：$notes",
            notes.any { it.contains("测试内核") })
    }
}
