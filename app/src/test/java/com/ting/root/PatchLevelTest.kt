package com.ting.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 补丁等级判定的测试 —— 阈值与"解析不出来不许猜"两条都要钉死。 */
class PatchLevelTest {

    @Test
    fun `六月是可能已修复`() {
        assertEquals(PatchRisk.MAYBE_FIXED, PatchLevel.evaluate("2026-06-01"))
        assertEquals(PatchRisk.MAYBE_FIXED, PatchLevel.evaluate("2026-06-05"))
        assertEquals(PatchRisk.MAYBE_FIXED, PatchLevel.evaluate("2026-06"))
    }

    @Test
    fun `七月及以上是大概率已修复`() {
        for (p in listOf("2026-07-01", "2026-07-05", "2026-08-01", "2026-12-01", "2027-01-01", "2027-06-01")) {
            assertEquals("$p 应判为 LIKELY_FIXED", PatchRisk.LIKELY_FIXED, PatchLevel.evaluate(p))
        }
    }

    @Test
    fun `六月之前不提示`() {
        for (p in listOf("2026-05-01", "2026-05-05", "2026-01-01", "2025-12-01", "2024-06-01")) {
            assertEquals("$p 不该有警告", PatchRisk.NONE, PatchLevel.evaluate(p))
        }
    }

    @Test
    fun `解析不出来时不许猜`() {
        for (bad in listOf(null, "", "  ", "unknown", "2026", "abc-06-01", "2026-13-01", "2026-00-01")) {
            assertNull("「$bad」不该解析出月份", PatchLevel.parse(bad))
            assertEquals("「$bad」不该给警告", PatchRisk.NONE, PatchLevel.evaluate(bad))
        }
    }

    @Test
    fun `斜杠分隔也能解析`() {
        assertEquals(PatchRisk.MAYBE_FIXED, PatchLevel.evaluate("2026/06/01"))
        assertEquals(PatchRisk.LIKELY_FIXED, PatchLevel.evaluate("2026/07/01"))
    }

    @Test
    fun `显示文本是纯文本 —— 颜色交给图标`() {
        // 回归：曾经在这里拼 ⚠/❗，在设备上渲染成白色，两级区分不出来。
        assertEquals("2026-05-01", PatchLevel.display("2026-05-01"))
        assertEquals("2026-06-01", PatchLevel.display("2026-06-01"))
        assertEquals("2026-07-01", PatchLevel.display("2026-07-01"))
        for (p in listOf("2026-06-01", "2026-07-01")) {
            val d = PatchLevel.display(p)
            assertFalse("不该再带 emoji：$d", d.contains("⚠") || d.contains("❗"))
        }
    }

    @Test
    fun `补丁为空时显示未知而不是崩`() {
        assertEquals("未知", PatchLevel.display(null))
        assertEquals("未知", PatchLevel.display("   "))
    }

    @Test
    fun `两级都只提示不拦截`() {
        // 关键语义：枚举里**没有**"拒绝"这一档；两级都 hasWarning，都由 UI 弹窗、不阻断
        assertTrue(PatchRisk.MAYBE_FIXED.hasWarning)
        assertTrue(PatchRisk.LIKELY_FIXED.hasWarning)
        assertFalse(PatchRisk.NONE.hasWarning)
        assertTrue(PatchRisk.LIKELY_FIXED.level > PatchRisk.MAYBE_FIXED.level)
    }
}
