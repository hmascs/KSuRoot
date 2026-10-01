package com.kernelpack.kallsyms

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `kallsyms_offsets` 表的**形状**判据。
 *
 * 这组用例守的是 2026-10 那次事故的同类：偏移表定位算错之后，
 * 靠「前三项单调」放行，一路走到「基址取到一段 ASCII、全部符号解析失败」。
 *
 * 关键在于**单调性几乎没有区分度** —— 偏移表是按地址排好序的，
 * 从它中间任意位置读都单调。所以判据必须换成表的形状。
 */
class OffsetTableShapeTest {

    /** 真表的样子：`kallsyms_offsets[i] = 符号地址 - relative_base`，首项就是 `_text` → 0。 */
    private fun realTable(n: Int): LongArray =
        LongArray(n) { i -> (i * 4096L) }          // 首项 0，递增

    @Test
    fun `真表通过`() {
        assertNull(whyNotOffsetTable(realTable(1000)))
    }

    @Test
    fun `从表内部读起 —— 必须拒绝（这正是当时漏掉的情形）`() {
        // 实测两个内核的「表内部」起点都是这样：完全单调，但首项在两千万以上。
        // 6.6.30 的 0x149ada8 → 29691400；6.12.38 的 0x179fc14 → 27299564。
        val fromMiddle = LongArray(1000) { i -> 29_691_400L + i * 4096L }
        // 先确认它确实单调 —— 否则这条用例就没在守「单调不够用」这件事
        assertTrue("构造的样本必须单调", (1 until 1000).all { fromMiddle[it - 1] <= fromMiddle[it] })
        val why = whyNotOffsetTable(fromMiddle)
        assertNotNull("单调但首项太大 —— 必须拒绝", why)
        assertTrue("原因应指向首项", why!!.contains("首项"))
    }

    @Test
    fun `首项不是最小值 —— 拒绝`() {
        val t = realTable(500)
        val bad = t.copyOf()
        bad[0] = 5_000_000L                        // 首项比后面还大
        assertNotNull(whyNotOffsetTable(bad))
    }

    @Test
    fun `首项为负 —— 拒绝`() {
        val bad = realTable(500).copyOf()
        bad[0] = -1L
        assertTrue(whyNotOffsetTable(bad)!!.contains("首项"))
    }

    @Test
    fun `末项大得离谱 —— 拒绝`() {
        val bad = realTable(500).copyOf()
        bad[499] = 0x4000_0000L                    // 1 GB，内核符号不会到那
        assertTrue(whyNotOffsetTable(bad)!!.contains("末项"))
    }

    @Test
    fun `边界：首项 64KB 以内算通过，末项 512MB 以内算通过`() {
        // 首项**恰好**等于上界，之后一路递增 —— 卡在边界上的正例。
        val edge = LongArray(64) { i -> FIRST_OFFSET_MAX + i * 1024L }
        edge[63] = LAST_OFFSET_MAX
        assertTrue("构造的边缘样本必须单调", (1 until 64).all { edge[it - 1] <= edge[it] })
        assertNull("恰好卡在上界内应当通过", whyNotOffsetTable(edge))
    }

    @Test
    fun `表过短 —— 拒绝而不是放行`() {
        assertNotNull(whyNotOffsetTable(longArrayOf(0L)))
        assertNotNull(whyNotOffsetTable(LongArray(0)))
    }
}
