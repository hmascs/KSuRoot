package com.kernelpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打补丁前的符号对齐闸门。
 *
 * 这组用例守的是**「不许产出新旧混血的 .so」**：
 * 载荷靠编译期常量寻址内核符号，只改写一部分、剩下的留旧值，
 * 等于拿旧内核的常量去改新内核的内存 —— 而且**静默**，装机后才炸。
 *
 * 上游 50 档每档只有 9 个符号、我方必需键 25 个，正是这个缺口触发了这道闸门。
 */
class SymbolAlignmentTest {

    private val base = setOf("INIT_TASK", "ROOT_TASK_GROUP", "TASK_CRED", "ASHMEM_FOPS")

    @Test
    fun `完全对齐时放行`() {
        val r = SymbolAlignment.check(base, base)
        assertTrue("同集合应判对齐", r.aligned)
        assertTrue(r.unpatchable.isEmpty())
        assertTrue(r.unresolved.isEmpty())
    }

    @Test
    fun `解析到但基线没有 —— 必须判不对齐（改不了，会留旧值）`() {
        val r = SymbolAlignment.check(base, base + "SLIDE_NFULNL_LOGGER")
        assertFalse("多出来的符号改不了，不能放行", r.aligned)
        assertEquals(listOf("SLIDE_NFULNL_LOGGER"), r.unpatchable)
        assertTrue(r.unresolved.isEmpty())
    }

    @Test
    fun `基线有但没解析出 —— 同样必须判不对齐（也留旧值）`() {
        val r = SymbolAlignment.check(base, base - "ASHMEM_FOPS")
        assertFalse("少了符号同样会留旧值，不能放行", r.aligned)
        assertEquals(listOf("ASHMEM_FOPS"), r.unresolved)
        assertTrue(r.unpatchable.isEmpty())
    }

    @Test
    fun `上游 50 档的真实缺口 —— 9 个符号对 25 个必需键，必须拦住`() {
        // 这是本次引入闸门的直接原因，写成用例防止有人把闸门去掉
        val upstreamNine = setOf(
            "off_init_task", "off_init_cred", "off_root_task_group", "off_selinux_enforcing",
            "off_selinux_blob_sizes", "off_security_hook_heads", "off_slide_nfulnl_logger",
            "off_slide_boot_id", "off_slide_loggers_0_1",
        )
        val r = SymbolAlignment.check(base, upstreamNine)
        assertFalse("上游档的符号与基线完全对不上，必须拦住", r.aligned)
        assertTrue("两边应互相都有缺口", r.unpatchable.isNotEmpty() && r.unresolved.isNotEmpty())
    }

    @Test
    fun `阻断说明要给出可操作的信息`() {
        val r = SymbolAlignment.check(base, base + "EXTRA")
        val msg = r.blockMessage("P10", "6.6.89-android15-8-g1f71897ac249-abogki467805059-4k").joinToString("\n")
        assertTrue("应说明已停止打包", msg.contains("已停止打包"))
        assertTrue("应带上基线 id", msg.contains("P10"))
        assertTrue("应带上基线内核版本", msg.contains("6.6.89"))
        assertTrue("应点名具体缺的符号", msg.contains("EXTRA"))
        assertTrue("应给出补救方式", msg.contains("补救"))
    }

    @Test
    fun `统计数字准确`() {
        val r = SymbolAlignment.check(base, setOf("INIT_TASK"))
        assertEquals(4, r.baseKeyCount)
        assertEquals(1, r.resolvedKeyCount)
        assertEquals(3, r.unresolved.size)
        assertEquals(0, r.unpatchable.size)
    }

    @Test
    fun `两个空集合算对齐 —— 不该误报`() {
        assertTrue(SymbolAlignment.check(emptySet(), emptySet()).aligned)
    }
}
