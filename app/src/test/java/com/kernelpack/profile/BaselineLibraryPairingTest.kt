package com.kernelpack.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守「**库与档位必须同源**」这条不变量。
 *
 * 由来（真机上实测到的）：X100 Pro 6.1.145 上，
 * - 「选哪份库」按结构体族走 → `libbaseline_6_1.so`；
 * - 「选哪一档」按三级路由走 → 完整内核串命中了上游档 `up-6-1-145-…`。
 *
 * 两条路选岔之后，补丁拿上游档的旧值去这份 .so 里找字面量，**26 项一处都没找到**，
 * 产物等于原样拷贝 —— 而界面显示的是「需要改写 26 项 · 校验通过 0 项」，
 * 看起来像"跑完了"，其实是"什么都没改"。
 *
 * 这组用例把「自编库必须配自己的档」钉死；谁把这条配对去掉，这里必须变红。
 */
class BaselineLibraryPairingTest {

    @Test
    fun `自编族基线必须映射到它自己的档位`() {
        assertEquals(
            BaselineRegistry.BASELINE_6_1.id,
            BaselineRegistry.BaselineLibraries.profileIdForLibrary(
                BaselineRegistry.BaselineLibraries.SIX_ONE,
            ),
        )
        assertEquals(
            BaselineRegistry.BASELINE_6_12.id,
            BaselineRegistry.BaselineLibraries.profileIdForLibrary(
                BaselineRegistry.BaselineLibraries.SIX_TWELVE,
            ),
        )
    }

    @Test
    fun `方案自带的 6_6 两份库也必须映射到自己的档`() {
        // 真机上漏出来的一条：PD2463（6.6.89）上路由按完整内核串命中了**上游档**
        // `up-6-6-89-…-vivo`（旧值来自 GhostLock 的 offsets.h），而基础库是 `libbs.so`
        // （旧值是 PD2520 那份 target.h 里的）→ 25 项一处都没匹配上，
        // 被零命中闸门当场拦下。拦得对，但根因是这张映射表漏了 6.6。
        //
        // 这张用例把四份**方案自带/自编**的库全部钉死；谁再漏一份，这里就红。
        assertEquals(
            BaselineProfiles.PD2520.id,
            BaselineRegistry.BaselineLibraries.profileIdForLibrary("libbs.so"),
        )
        assertEquals(
            BaselineProfiles.IONSTACK_P10.id,
            BaselineRegistry.BaselineLibraries.profileIdForLibrary("libionstack.so"),
        )
    }

    @Test
    fun `四份自带库都有映射，一个不漏`() {
        val libs = listOf(
            BaselineRegistry.BaselineLibraries.SIX_ONE,
            BaselineRegistry.BaselineLibraries.SIX_TWELVE,
            "libbs.so",
            "libionstack.so",
        )
        for (lib in libs) {
            assertNotNull("$lib 没有对应的档位映射 —— 补上，否则路由会选到别的档，产物静默错值", 
                BaselineRegistry.BaselineLibraries.profileIdForLibrary(lib))
        }
    }

    @Test
    fun `认不出的库不做映射 —— 不猜`() {
        // ⚠️ 这条用例原来写的是 assertNull("libbs.so") / assertNull("libionstack.so") ——
        // 那是当时"只覆盖了自编 6.1/6.12"的**旧假设**。真机上 PD2463（6.6.89）
        // 正是因为这两份没映射，路由选到了上游档 `up-6-6-89-…`，25 项偏移一处都没匹配上。
        // 所以那两条断言不是"保护"，是**把缺陷固化进了用例**。
        //
        // 现在只断言"真的认不出的"返回 null：随包收集的那些第三方 libksu_*.so
        // 没有、也不该有档位映射（它们的旧值我们根本没登记）。
        assertNull(BaselineRegistry.BaselineLibraries.profileIdForLibrary("libksu_vivo_x.so"))
        assertNull(BaselineRegistry.BaselineLibraries.profileIdForLibrary("libcve43499root.so"))
        assertNull(BaselineRegistry.BaselineLibraries.profileIdForLibrary(""))
    }

    @Test
    fun `自编档与上游档的旧值确实没有交集 —— 这就是零命中的根因`() {
        // 这条不是在测代码，是在把"为什么必须配对"变成可执行的事实：
        // 两边键集就算一样，**值**也不同。谁哪天把两边的值改成一致了，这条会红 ——
        // 那时候配对就不再是必须的，可以重新评估这条规则。
        val own = BaselineRegistry.BASELINE_6_1.symbolOffsets
        val upstream = BaselineRegistry.allEntries
            .first { it.profile.id.startsWith("up-6-1-145") }
            .profile.symbolOffsets

        val shared = own.keys.intersect(upstream.keys)
        assertTrue("两边应当有共同键（否则这条用例没意义）", shared.isNotEmpty())

        val identical = shared.count { own[it] == upstream[it] }
        assertNotEquals(
            "自编档与上游档在共同键上的值**应当不同**（实测就是一处都对不上）；" +
                "若这里变成全部相同，说明两套旧值已经统一，配对规则可以重新评估",
            shared.size,
            identical,
        )
    }

    @Test
    fun `自编档的旧值确实存在（不是空壳）`() {
        // 配对的前提是自编档**真的登记了**旧值。留空的话补丁无从定位，
        // 又会回到"一项都改不了"的老问题。
        assertTrue(BaselineRegistry.BASELINE_6_1.symbolOffsets.isNotEmpty())
        assertTrue(BaselineRegistry.BASELINE_6_12.symbolOffsets.isNotEmpty())
    }
}
