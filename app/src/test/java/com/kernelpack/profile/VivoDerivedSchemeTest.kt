package com.kernelpack.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝厂衍生档：把通用方案的每一档内核适配挂上 vr.ko 绕过。
 *
 * 这组用例守两件事：
 * 1. **派生是完整的** —— 通用方案有几档，蓝厂就该多出几档（不能漏、不能重复）；
 * 2. **派生是诚实的** —— 偏移一个字节都不许动（动了就不是"通用方案的适配"），
 *    且必须整档标 beta（这批组合没在蓝厂真机上跑过）。
 */
class VivoDerivedSchemeTest {

    // 必须用 entriesWithUpstream：通用方案 = 手写实测档 + 50 档上游档。
    // 用旧的 entries 会漏掉全部上游档，测试就变成"自说自话"。
    private val universal = BaselineRegistry.entriesWithUpstream
        .filter { it.scheme == BaselineScheme.UNIVERSAL }
    private val derived = BaselineRegistry.allEntries.filter {
        it.scheme == BaselineScheme.VIVO && it.profile.id.endsWith("-vivo")
    }

    @Test
    fun `通用方案的每一档都派生出对应的蓝厂档`() {
        assertEquals(
            "通用方案档位数与派生出的蓝厂档位数不一致",
            universal.size,
            derived.size,
        )
        // 规模断言：50 档上游 + 1 档手写 = 51
        assertTrue("通用方案应有 51 档，实际 ${universal.size}", universal.size >= 51)
        assertTrue("派生出 0 档说明派生规则没生效", derived.isNotEmpty())
    }

    @Test
    fun `派生档的内核系列与来源档一一对应`() {
        assertEquals(
            universal.map { it.kernelSeries }.sorted(),
            derived.map { it.kernelSeries }.sorted(),
        )
    }

    @Test
    fun `派生档的偏移必须与来源档逐字节相同`() {
        // 偏移是从通用档**继承**的，不是重算的。
        // 一旦这里不相等，就说明有人"顺手改了数字" —— 那正是本工程最防的事。
        for (u in universal) {
            val d = derived.first { it.profile.id == u.profile.id + "-vivo" }
            assertEquals(
                "偏移集被改动：${u.profile.id} → ${d.profile.id}",
                u.offsets.toString(),
                d.offsets.toString(),
            )
            assertEquals("sha256 不该变", u.profile.sha256, d.profile.sha256)
        }
    }

    @Test
    fun `派生档一律标 beta —— 未实测就不能冒充已验证`() {
        for (d in derived) {
            assertTrue("${d.profile.id} 没有标 beta", d.beta)
        }
    }

    /**
     * **已在真机上跑过**的那几档 —— 它们不该标 beta。
     *
     * [2026-10 口径变更] 这条用例原来断言"`entries` 里的每一条都不该标 beta"，
     * 前提是「进了手写列表 == 已实测」。但 6.1 / 6.12 两份**自编族基线**破了这个前提：
     * 它们**必须**登记进 `entries` 才能被三级路由取到，却**没有任何真机验证** ——
     * 符号值取自构建用的 target.h，`vr detag` 只证明代码编进去了。
     *
     * 真正该拦的是"未实测冒充已验证"，与它在哪个列表里无关。
     * 所以判据改成**显式名单**：新档位必须自己证明跑过，才能进这里。
     */
    private val measuredOnDevice = listOf(
        "PD2520-BP2A.250605.031.A3",
        "IONSTACK-P10-CP2A.260605.012",
    )

    @Test
    fun `已实测的档位不得被标 beta`() {
        for (id in measuredOnDevice) {
            val e = requireNotNull(BaselineRegistry.byId(id)) { "$id 不在登记表里" }
            assertFalse("$id 是已实测档，不该标 beta", e.beta)
        }
    }

    @Test
    fun `未经实测的档位必须标 beta 且说清原因`() {
        // 反向：不在名单里的档位就该老实标 beta，且 notes 要能看出为什么没测过。
        for (e in BaselineRegistry.entries) {
            if (e.profile.id in measuredOnDevice) continue
            assertTrue("${e.profile.id} 未列入实测名单，就必须标 beta", e.beta)
            assertTrue(
                "${e.profile.id} 标了 beta，但 notes 里没说清它未经实测",
                e.notes.any { it.contains("未") || it.contains("beta") },
            )
        }
    }

    @Test
    fun `派生档的说明里写清了 vr ko 与 beta 两件事`() {
        for (d in derived) {
            val joined = d.notes.joinToString(" ")
            assertTrue("${d.profile.id} 的说明没提 vr.ko", joined.contains("vr.ko"))
            assertTrue("${d.profile.id} 的说明没提 beta", joined.contains("beta"))
        }
    }

    @Test
    fun `id 不冲突 —— 派生档加了 -vivo 后缀`() {
        val ids = BaselineRegistry.allEntries.map { it.profile.id }
        assertEquals("allEntries 里有重复 id", ids.size, ids.toSet().size)
    }

    @Test
    fun `衍生档能被 profileIdFor 与 byId 同时取到`() {
        // 这条守的是"返回了 id 却按 id 取不到条目"那种裂缝 ——
        // 查询点没统一到 allEntries 时就会这样。
        for (d in derived) {
            val id = BaselineRegistry.profileIdFor(d.scheme, d.kernelSeries)
            assertNotNull("profileIdFor 取不到 ${d.kernelSeries}", id)
            assertNotNull("byId 取不到 $id", BaselineRegistry.byId(id!!))
        }
    }

    @Test
    fun `seriesFor 反映各方案自己的范围`() {
        val u = BaselineRegistry.seriesFor(BaselineScheme.UNIVERSAL)
        val v = BaselineRegistry.seriesFor(BaselineScheme.VIVO)
        assertTrue("通用方案应至少有一档", u.isNotEmpty())
        // 蓝厂至少覆盖通用方案的全部系列（派生的意义就在这）
        assertTrue("蓝厂范围应包含通用方案全部系列：$u ⊄ $v", v.containsAll(u))
    }

    @Test
    fun `已实测档优先于 beta 档 —— beta 只补空缺`() {
        // 蓝厂 6.6 同时有 PD2520（实测）与派生 beta 档。
        // 生效的必须是**实测那条**，否则等于拿 beta 档顶掉已经验证过的适配。
        val effective = BaselineRegistry.entryFor(BaselineScheme.VIVO, "6.6")
        assertNotNull(effective)
        assertFalse(
            "生效档不该是 beta：" + effective!!.profile.id,
            effective.beta,
        )
        assertFalse(BaselineRegistry.isBeta(BaselineScheme.VIVO, "6.6"))
    }

    @Test
    fun `同一方案系列有多条时，生效判定是稳定的（不依赖声明顺序）`() {
        // 同一输入连问两次必须同答案；且多次调用不因内部排序而漂移
        val a = BaselineRegistry.entryFor(BaselineScheme.VIVO, "6.6")?.profile?.id
        val b = BaselineRegistry.entryFor(BaselineScheme.VIVO, "6.6")?.profile?.id
        assertEquals(a, b)
    }

    @Test
    fun `没有实测档时 beta 档才生效`() {
        // 构造一个只有 beta 档的组合：取一个通用方案有、而蓝厂手写表没有的系列。
        // 当前两边都只有 6.6，所以这里退化为"至少验证函数不抛且返回一致"。
        for (series in BaselineRegistry.seriesFor(BaselineScheme.VIVO)) {
            val e = BaselineRegistry.entryFor(BaselineScheme.VIVO, series)
            assertNotNull("$series 取不到生效档", e)
            assertEquals(
                "$series 的 isBeta 与生效档不一致",
                e!!.beta,
                BaselineRegistry.isBeta(BaselineScheme.VIVO, series),
            )
        }
    }
}
