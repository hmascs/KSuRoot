package com.kernelpack.profile

import com.kernelpack.policy.BuildGate
import com.kernelpack.policy.SeriesOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「路由用的表 / 词汇」与「打包查的表 / 词汇」必须**一一对上**。
 *
 * ### 这组用例来自一次真实事故（2026-09-25，用户拿 6.1.145 的 boot.img 实测）
 *
 * 用户看到的是「基线 ABI 冲突，已拒绝构建」，而根因有**两层**，都在"两套东西对不上"：
 *
 * 1. **两张表**：路由 [BaselineRegistry.profileIdFor] 搜的是 `allEntries`
 *    （手写 2 档 + 上游 50 档 + 蓝厂派生），给 6.1 正确返回了档位 id；
 *    而打包时查的是 [BaselineProfiles.byId] —— 那张表**只有 2 份 6.6 手写档**，
 *    查不到 → 上层 `?:` 兜底回落到 PD2520（6.6）→ 闸门报
 *    「基线 ABI 档位是 GKI 6.6，而 boot.img 是 6.1」。
 * 2. **两套词汇**：上游档的 `profile.abi.kernelSeries` 填的是**三段小版本**（`6.6.118`），
 *    而 [BuildGate.evaluate] 拿它去比 `seriesOf(release)` —— 后者**只取两段**（`6.6`）。
 *    于是**每一条上游档都会被判成 ABI 冲突**，50 档一条都构建不出来。
 *
 * 两层都表现为同一句"ABI 冲突"，但修法完全不同。所以这里把两条不变量都写成断言。
 */
class BaselineRoutingLookupTest {

    private val jniDir = File("src/main/jniLibs/arm64-v8a")
    private val entries = BaselineRegistry.allEntries

    /** 路由会走到的全部 (方案, 系列) 组合：主线三系 + 测试两系，两个方案都覆盖。 */
    private val routedCombos: List<Pair<BaselineScheme, String>> = buildList {
        for (scheme in listOf(BaselineScheme.UNIVERSAL, BaselineScheme.VIVO)) {
            for (series in BaselineRegistry.MAINLINE_SERIES + BaselineRegistry.TEST_SERIES) {
                add(scheme to series)
            }
        }
    }

    // ------------------------------------------------- 词汇不变量（事故第 2 层）

    @Test
    fun `★ 每一档的 abi_kernelSeries 必须是闸门比较用的两段式大系列`() {
        // 这是事故第 2 层的直接回归：只要有人再把三段小版本填进去，这里立刻红。
        val bad = entries.filter { e ->
            e.profile.abi.kernelSeries != BaselineRegistry.gkiSeriesOf(e.kernelSeries)
        }
        assertTrue(
            "以下档位的 abi.kernelSeries 与闸门用的两段式系列不一致 —— " +
                "BuildGate 会拿它去比 seriesOf(release)，必然报「基线 ABI 冲突」：\n" +
                bad.joinToString("\n") {
                    "  ${it.profile.id}: abi=${it.profile.abi.kernelSeries} " +
                        "而 gkiSeriesOf(${it.kernelSeries})=${BaselineRegistry.gkiSeriesOf(it.kernelSeries)}"
                },
            bad.isEmpty(),
        )
    }

    @Test
    fun `★ 每一档都必须能通过 ABI 硬闸门 —— 用它的真实 release 串端到端跑一遍`() {
        // 这条是本组最有价值的一条：它复现的就是用户看到的那句
        // 「基线 ABI 冲突，已拒绝构建」，只不过是在单元测试里。
        val blocked = mutableListOf<String>()
        for (e in entries) {
            // ⚠️ 用 [BaselineProfile.kernelVersion] 而不是 [BaselineEntry.firmware]：
            // 手写档的 firmware 是 **Android 构建号**（`BP2A.250605.031.A3`），
            // 不是内核串 —— 拿它当 kernelRelease 会让闸门报「无法识别内核版本」。
            // 上游档两者恰好相同（firmware 就是内核串），所以这个坑只有手写档会踩到。
            val gate = BuildGate.evaluate(
                kernelRelease = e.profile.kernelVersion,
                baselineAbiSeries = e.profile.abi.kernelSeries,
                override = SeriesOverride.AUTO,
                allowMismatch = false,
                scheme = e.scheme,
                profileId = e.profile.id,
                allowTestKernel = true,
            )
            if (!gate.ok) {
                blocked += "${e.profile.id}（${e.scheme.label} × ${e.profile.kernelVersion}）：" +
                    (gate as? com.kernelpack.policy.GateDecision.Blocked)?.title.orEmpty()
            }
        }
        assertTrue(
            "以下档位会被自己的硬闸门拒绝 —— 登记了却构建不出来：\n" + blocked.joinToString("\n"),
            blocked.isEmpty(),
        )
    }

    // ------------------------------------------------- 同表不变量（事故第 1 层）

    @Test
    fun `★ 路由给出的每一个 profileId 都必须能被 byId 取到`() {
        val broken = mutableListOf<String>()
        // ① 带完整内核串的三级路由（上游 50 档是按小版本登记的，只测大系列测不到它们）
        // ② 只用大系列的两级路由
        for (e in entries) {
            for ((label, id) in listOf(
                "三级路由" to BaselineRegistry.profileIdFor(e.scheme, e.kernelSeries, e.firmware),
                "两级路由" to BaselineRegistry.profileIdFor(e.scheme, e.kernelSeries),
            )) {
                if (id == null) {
                    broken += "$label：${e.scheme.label} × ${e.kernelSeries} 取不到档位（本档是 ${e.profile.id}）"
                    continue
                }
                if (BaselineRegistry.byId(id) == null) {
                    broken += "$label：${e.scheme.label} × ${e.kernelSeries} → id=$id，但 byId 查不到"
                }
            }
        }
        assertTrue(
            "路由指向了 byId 取不到的档位 —— 打包时会兜底回落到别的系列，" +
                "用户看到的就是「基线 ABI 冲突」：\n" + broken.joinToString("\n"),
            broken.isEmpty(),
        )
    }

    @Test
    fun `★ 两级路由（只给大系列）只对 6_6 有效 —— 6_1 与 6_12 必须带完整内核串`() {
        // 这条把**当前的真实边界**钉下来，而不是假装它不存在。
        //
        // 现状：只有 6.6 有"大系列级"的登记档（PD2520 / IONSTACK_P10，两条手写实测档）。
        // 6.1 / 6.12 的登记档全部是**按小版本**建的（上游 50 档 + 蓝厂派生），
        // `kernelSeries` 形如 `6.1.145` —— 所以只给 `6.1` 是查不到的。
        //
        // 这不是缺陷：调用方（PayloadBuilderViewModel）走的是**三级路由**，
        // 会把 boot.img 实测的完整内核串传进来。但**边界必须被测出来** ——
        // 否则哪天三级路由退化成两级，6.1 / 6.12 会静默变成"没有基线"。
        for (scheme in listOf(BaselineScheme.UNIVERSAL, BaselineScheme.VIVO)) {
            assertNotNull("${scheme.label} × 6.6 应当有的大系列级登记档", BaselineRegistry.profileIdFor(scheme, "6.6"))
            for (series in listOf("6.1", "6.12")) {
                assertNull(
                    "${scheme.label} × $series 居然有了大系列级登记档 —— " +
                        "若这是有意新增的，请同步更新本用例与 MAINLINE_SERIES 的文档",
                    BaselineRegistry.profileIdFor(scheme, series),
                )
            }
        }
    }

    @Test
    fun `★ 三级路由（带完整内核串）能覆盖三个主线系列的两个方案`() {
        // 与上一条配对：两级不行的地方，三级必须行 —— 这才是调用方真正走的路径。
        for (scheme in listOf(BaselineScheme.UNIVERSAL, BaselineScheme.VIVO)) {
            for (series in BaselineRegistry.MAINLINE_SERIES) {
                val probe = entries
                    .firstOrNull { it.scheme == scheme && BaselineRegistry.gkiSeriesOf(it.kernelSeries) == series }
                    ?.firmware
                assertNotNull("$series / ${scheme.label} 连一档都没有登记", probe)
                val id = BaselineRegistry.profileIdFor(scheme, series, probe)
                assertNotNull("三级路由：${scheme.label} × $series × $probe 取不到档位", id)
                assertNotNull("三级路由给出 $id，byId 却取不到", BaselineRegistry.byId(id!!))
            }
        }
    }

    @Test
    fun `★ byId 必须覆盖 allEntries 的每一档，且取回的是同一条`() {
        val bad = entries.filter { e ->
            BaselineRegistry.byId(e.profile.id)?.profile?.id != e.profile.id
        }
        assertTrue("byId 漏了这些档：${bad.map { it.profile.id }}", bad.isEmpty())
    }

    @Test
    fun `★ 路由出的档位，其 ABI 系列必须等于请求的系列`() {
        for ((scheme, series) in routedCombos) {
            val id = BaselineRegistry.profileIdFor(scheme, series) ?: continue
            val entry = BaselineRegistry.byId(id)
            assertNotNull("$scheme × $series", entry)
            // 测试线 5.x 目前没有手写档，命中上游档时可能落在别的 5.x 上，故只比主线。
            if (series in BaselineRegistry.MAINLINE_SERIES) {
                assertEquals(
                    "路由把 $series 路由到了 ${entry!!.profile.abi.kernelSeries} 的档位（$id）",
                    series,
                    entry!!.profile.abi.kernelSeries,
                )
            }
        }
    }

    @Test
    fun `旧表 BaselineProfiles 取不到上游档 —— 留档说明当初为何误报 ABI 冲突`() {
        // 不是"期望旧表坏掉"，而是把事故根因钉住：旧表只有 2 条、且都是 6.6，
        // 所以它**不能**当通用查表用。这条断言在提醒后来者别再犯。
        assertEquals(2, BaselineProfiles.ALL.size)
        assertTrue(BaselineProfiles.ALL.all { it.abi.kernelSeries == "6.6" })

        val upstreamId = entries.first { it.profile.id.startsWith("up-") }.profile.id
        assertNull(
            "旧表居然取到了上游档 $upstreamId —— 若真如此，说明旧表已被改造，本用例需重新评估",
            BaselineProfiles.byId(upstreamId),
        )
    }

    // ------------------------------------------------- 兜底链（KernelPack 的第二处调用点）

    @Test
    fun `★ findByBytes 能按 sha256 认出方案原有的两份 6_6 基线库`() {
        // 这两份是**已登记档位的库本体**（PD2520 的 sha256 就是 libbs.so 的 sha256），
        // 所以 KernelPack 的兜底路径能自证身份 —— 不需要任何猜测。
        for ((name, id) in listOf("libbs.so" to "PD2520", "libionstack.so" to "IONSTACK-P10")) {
            val f = File(jniDir, name)
            assertTrue("$name 不在 jniLibs 里，兜底链无法验证", f.exists())
            val entry = BaselineRegistry.findByBytes(f.readBytes())
            assertNotNull("findByBytes 认不出 $name（sha256 应当命中）", entry)
            assertTrue(
                "$name 被认成了 ${entry!!.profile.id}，应当是以 $id 开头的那一档",
                entry!!.profile.id.startsWith(id),
            )
            assertEquals("6.6", entry!!.profile.abi.kernelSeries)
        }
    }

    @Test
    fun `★ 两份族基线认不出来 —— 这是**有意的**，KernelPack 会据此安全拒绝而不是猜`() {
        // 事实：`libbaseline_6_1.so` / `libbaseline_6_12.so` **没有登记进偏移表**
        // （见 [BaselineRegistry.BASELINE_6_1] 的说明：它们的编译期符号值没有逐项核实，
        // 所以 profile 里 symbolOffsets 是空的）。没有 sha256、也没有对应 variantLabel 的条目，
        // 因此 findByBytes 认不出它们。
        //
        // 这不是"漏了接线"，而是**当前能力的真实边界**，且已经被安全化：
        // KernelPack 现在只认能自证身份的匹配，认不出就**如实报缺并停止打包**，
        // 不再像以前那样兜底取 PD2520（那等于拿 6.6 的旧值去打 6.1 的库）。
        //
        // 支持的路径是：调用方（PayloadBuilderViewModel）显式传入 PackRequest.baseline ——
        // 它由三级路由从 50 档上游档里选出来，那条路是通的（见上面的三级路由用例）。
        val libs = BaselineRegistry.BaselineLibraries
        for (name in listOf(libs.SIX_ONE, libs.SIX_TWELVE)) {
            val f = File(jniDir, name)
            assertTrue("$name 不在 jniLibs 里", f.exists())
            assertNull(
                "findByBytes 竟然认出了 $name —— 若已登记进偏移表，请更新本用例的说明",
                BaselineRegistry.findByBytes(f.readBytes()),
            )
        }
        // 族基线不在注册表里：带 sha256 的**库本体**只有那两份 6.6 手写实测档。
        // （按 distinct 数，不按条目数 —— 蓝厂衍生档会 copy 出同样的 sha256。）
        val shas = entries.mapNotNull { it.profile.sha256 }.toSet()
        assertEquals(
            "能被 sha256 自证身份的库应当只有 libbs.so 与 libionstack.so 两份：$shas",
            2,
            shas.size,
        )
    }

    @Test
    fun `findByBytes 对陌生字节返回 null —— 不猜、也不乱指档位`() {
        // 返回 null 时调用方才会落到旧表 detect()，进而被硬闸门拦住 —— 安全的失败。
        assertNull(BaselineRegistry.findByBytes(ByteArray(4096) { 0x41 }))
    }
}
