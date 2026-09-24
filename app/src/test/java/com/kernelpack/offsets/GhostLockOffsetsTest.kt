package com.kernelpack.offsets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * offsets.json 互通层的往返与合并。
 *
 * 这一组用例守的是**「导入再导出不能丢数据」**：
 * 该格式属于外部生态（GhostLock / IonStack），字段会随对方更新而增加。
 * 如果我们只认自己知道的键、把不认识的丢掉，用户"导入→导出"一次就会静默截断 ——
 * 而这类丢失装机看不出来，只有到下个内核版本不匹配时才会暴露。
 */
class GhostLockOffsetsTest {

    /** 与荣耀 6.12.38 实测样本同构的最小样本（含一个**我们还不认识**的键）。 */
    private val sample = """
        {
          "release": "6.12.38-android16-5-gfde7767f6ef6-abogki481467632-4k",
          "kernel_sha256": "48b622a20a700cdde0b8f2f6e83959df00a7efedf52f347377cf542f7b58948e",
          "kernel_phys_load": 2147483648,
          "pselect_waiter_shift": 0,
          "compact_waiter": 0,
          "mm_struct_sz": 1280,
          "symbols": { "off_init_task": 37670336, "off_init_cred": 37760176 },
          "struct_fields": { "rt_mutex_waiter_size": 112, "task_pi_lock": 2540 },
          "future_field_we_do_not_know": { "nested": [1, 2, 3], "flag": true }
        }
    """.trimIndent()

    @Test
    fun `解析出顶层标量与两组整数`() {
        val d = GhostLockOffsetsIo.read(sample)
        assertEquals(
            "6.12.38-android16-5-gfde7767f6ef6-abogki481467632-4k",
            d.release,
        )
        assertEquals(2, d.symbols.size)
        assertEquals(37670336L, d.symbols["off_init_task"])
        assertEquals(112L, d.structFields["rt_mutex_waiter_size"])
        assertEquals(2540L, d.structFields["task_pi_lock"])
    }

    @Test
    fun `布局判别字段能读出`() {
        val d = GhostLockOffsetsIo.read(sample)
        // 这两个是我们与 PselectFeasibility 的跨模块契约，读错会让布局判定整体跑偏
        assertFalse("compact_waiter=0 应判为非 compact", d.compactWaiter)
        assertEquals(0L, d.pselectWaiterShift)
    }

    @Test
    fun `不认识的键必须原样保留 —— 往返不丢数据`() {
        val d = GhostLockOffsetsIo.read(sample)
        assertTrue("未知键没被保留", d.unknown.containsKey("future_field_we_do_not_know"))
        val out = GhostLockOffsetsIo.write(d)
        assertTrue("往返后未知键丢了", out.contains("future_field_we_do_not_know"))
        assertTrue("未知键的子字段丢了", out.contains("nested"))
        assertTrue("未知键的布尔值丢了", out.contains("true"))
    }

    @Test
    fun `往返两次结果稳定（幂等）`() {
        val once = GhostLockOffsetsIo.write(GhostLockOffsetsIo.read(sample))
        val twice = GhostLockOffsetsIo.write(GhostLockOffsetsIo.read(once))
        assertEquals(once, twice)
    }

    @Test
    fun `缺失键能被自检出来`() {
        val d = GhostLockOffsetsIo.read(sample)
        // 样本只给了 2 个符号，完整集有 17 个
        assertTrue(d.missingSymbols().contains("off_remove_waiter"))
        assertTrue(d.missingStructFields().contains("task_cred"))
    }

    @Test
    fun `ONLY_FILL_MISSING 不会覆盖基准已有的值`() {
        val base = GhostLockOffsetsIo.read(sample)
        val overlay = GhostLockOffsetsIo.read(
            """{ "symbols": { "off_init_task": 999, "off_remove_waiter": 12345 } }""",
        )
        val r = GhostLockOffsetsIo.merge(base, overlay, MergePolicy.ONLY_FILL_MISSING)
        assertEquals("基准已有的值不该被改", 37670336L, r.document.symbols["off_init_task"])
        assertEquals("基准缺的键应被补上", 12345L, r.document.symbols["off_remove_waiter"])
        assertTrue("冲突要被记录", r.conflicts.containsKey("off_init_task"))
    }

    @Test
    fun `PREFER_OVERLAY 用覆盖方的值`() {
        val base = GhostLockOffsetsIo.read(sample)
        val overlay = GhostLockOffsetsIo.read("""{ "mm_struct_sz": 1216 }""")
        val r = GhostLockOffsetsIo.merge(base, overlay, MergePolicy.PREFER_OVERLAY)
        assertEquals("1216", r.document.scalars["mm_struct_sz"])
        assertTrue(r.conflicts.containsKey("mm_struct_sz"))
    }

    @Test
    fun `没有冲突时不报冲突`() {
        val base = GhostLockOffsetsIo.read(sample)
        val overlay = GhostLockOffsetsIo.read("""{ "symbols": { "off_rb_erase": 1 } }""")
        val r = GhostLockOffsetsIo.merge(base, overlay, MergePolicy.REPORT_CONFLICTS)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun `顶层不是对象时要报错而不是静默返回空`() {
        var threw = false
        try {
            GhostLockOffsetsIo.read("[1, 2, 3]")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("顶层是数组却没有报错 —— 静默返回空文档比崩溃更危险", threw)
    }

    @Test
    fun `符号值写成字符串也认（不同提取器写法不一）`() {
        val d = GhostLockOffsetsIo.read("""{ "symbols": { "off_init_task": "37670336" } }""")
        assertEquals(37670336L, d.symbols["off_init_task"])
    }

    @Test
    fun `十六进制标量原样保留不转数值`() {
        val d = GhostLockOffsetsIo.read("""{ "kernel_phys_load": "0x80000000" }""")
        assertEquals("0x80000000", d.scalars["kernel_phys_load"])
        assertNotNull(GhostLockOffsetsIo.write(d))
    }

    @Test
    fun `未知键在合并时取并集`() {
        val a = GhostLockOffsetsIo.read("""{ "alpha_unknown": 1 }""")
        val b = GhostLockOffsetsIo.read("""{ "beta_unknown": 2 }""")
        val r = GhostLockOffsetsIo.merge(a, b, MergePolicy.PREFER_OVERLAY)
        assertTrue(r.document.unknown.containsKey("alpha_unknown"))
        assertTrue(r.document.unknown.containsKey("beta_unknown"))
    }
}
