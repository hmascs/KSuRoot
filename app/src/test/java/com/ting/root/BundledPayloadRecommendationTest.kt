package com.ting.root

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守「不建议使用的载荷必须**说出来**」这条不变量。
 *
 * 由来：`libksu_oppo_oppoa5pro5gcve202643499_any_d77f.so` 与
 * [`qsvggff-spec/oppo-A5-PRO-5G-CVE-2026-43499`](https://github.com/qsvggff-spec/oppo-A5-PRO-5G-CVE-2026-43499)
 * 的 `preload-tokay-PKP110.so` **sha256 完全相同** —— 而那个仓库是**失败研究的归档**，
 * 作者自己的结论是「利用链无法收敛」。
 *
 * 它随包保留（有人可能想拿它做对照），但**不能长得像一份能用的载荷**：
 * 不标出来的话，用户在列表里看到的是一个和别的选项没有任何区别的条目。
 * 这正是本工程一直在防的那种假象。
 */
class BundledPayloadRecommendationTest {

    private val oppoA5 = "libksu_oppo_oppoa5pro5gcve202643499_any_d77f.so"

    @Test
    fun `那份上游自己说走不通的载荷必须带「不建议使用」原因`() {
        val entry = BundledPayloadCatalog.ALL.firstOrNull { it.library == oppoA5 }
        assertNotNull("这份载荷应当仍在包内（仍可做对照）", entry)
        val reason = entry!!.notRecommended
        assertNotNull("它必须在登记表里带上「不建议使用」的原因，不能只留一个普通条目", reason)
        assertTrue(
            "原因里要写清是**上游作者自己的结论**，而不是我们主观不推荐：$reason",
            reason!!.contains("收敛") || reason.contains("上游"),
        )
    }

    @Test
    fun `不建议的原因必须是可读的人话，不是一个布尔或代号`() {
        for (entry in BundledPayloadCatalog.ALL) {
            val reason = entry.notRecommended ?: continue
            assertTrue(
                "${entry.library} 的 notRecommended 太短，用户看不出为什么：$reason",
                reason.length >= 20,
            )
        }
    }

    @Test
    fun `其余载荷不应被误标`() {
        // 反向：这条标记只该落在确实有问题的那些上。
        // 大面积误标会让"不建议"变成噪音，用户连真该看的那一次一起忽略掉。
        val marked = BundledPayloadCatalog.ALL.filter { it.notRecommended != null }
        assertTrue("被标「不建议」的不该是绝大多数", marked.size <= BundledPayloadCatalog.ALL.size / 10)
        assertNull(
            "libbs.so 是 6.6 蓝厂主力载荷，不该被标成不建议",
            BundledPayloadCatalog.byLibrary("libbs.so")?.notRecommended,
        )
    }
}
