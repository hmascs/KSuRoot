package com.kernelpack.offsets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两方案合一后的**取值优先级与追溯**。
 *
 * 这组用例守的是本工程最核心的那条铁律：
 * **每个偏移都必须能说清它从哪来；没有就报缺，不许外推。**
 *
 * 合并最大的风险不是取错值，而是**悄悄取错值还看不出来** ——
 * 所以这里逐条断言来源标记，而不是只断言数值。
 */
class UnifiedOffsetResolverTest {

    private val json = GhostLockOffsetsIo.read(
        """
        {
          "release": "6.6.118-android15-8-g2e6b9c3812c5-ab15114928-4k",
          "symbols": { "off_init_task": 111 },
          "struct_fields": { "task_pi_lock": 222 }
        }
        """.trimIndent(),
    )

    private val baseline = mapOf("off_init_task" to 999L, "task_pi_lock" to 888L, "task_cred" to 777L)
    private val fallback = mapOf("task_pi_lock" to 1L, "task_cred" to 2L, "task_tasks" to 3L)

    @Test
    fun `运行时表优先于编译期基线与兜底`() {
        val r = UnifiedOffsetResolver.resolve(json, baseline, fallback)
        assertEquals(111L, r["off_init_task"])
        assertEquals(OffsetOrigin.OFFSETS_JSON, r.originOf("off_init_task"))
        assertEquals(222L, r["task_pi_lock"])
        assertEquals(OffsetOrigin.OFFSETS_JSON, r.originOf("task_pi_lock"))
    }

    @Test
    fun `运行时表没有的键回落到编译期基线`() {
        val r = UnifiedOffsetResolver.resolve(json, baseline, fallback)
        assertEquals("基线里有、json 里没有，应该用基线", 777L, r["task_cred"])
        assertEquals(OffsetOrigin.COMPILED_BASELINE, r.originOf("task_cred"))
    }

    @Test
    fun `基线也没有才回落到兜底常量`() {
        val r = UnifiedOffsetResolver.resolve(json, baseline, fallback)
        assertEquals(3L, r["task_tasks"])
        assertEquals(OffsetOrigin.COMPILED_FALLBACK, r.originOf("task_tasks"))
    }

    @Test
    fun `三个来源都没有的键如实报缺 —— 绝不外推`() {
        val r = UnifiedOffsetResolver.resolve(
            json,
            baseline,
            fallback,
        )
        // 显式请求一个谁都没有的键：它必须"取不到"，而不是拿到别的内核的数值
        assertNull("不存在的键不该有值", r["task_nonexistent"])
        assertNull("不存在的键不该有来源", r.originOf("task_nonexistent"))
    }

    @Test
    fun `缺失清单来自调用方给出的键集合，不是凭空猜测`() {
        // baseline 里放一个三来源都没有对应值的键是不可能的（它就是来源之一），
        // 所以这里验证的是"missing 只可能为空或来自真实缺口"这一语义：
        val r = UnifiedOffsetResolver.resolve(json, baseline, fallback)
        // 所有键都至少有一个来源 → 不该有缺失
        assertTrue("这三个来源覆盖了全部键，missing 应为空", r.isComplete)
        assertEquals(emptyList<String>(), r.missing)
    }

    @Test
    fun `没有运行时表时行为与合并前完全一致（关键回归）`() {
        // 纯基线 + 兜底，等价于「软件原本的动态载荷方案」。
        // 这条保证合并**不改变任何没配 offsets.json 的机器的行为**。
        val r = UnifiedOffsetResolver.resolve(null, baseline, fallback)
        assertEquals(999L, r["off_init_task"])
        assertEquals(OffsetOrigin.COMPILED_BASELINE, r.originOf("off_init_task"))
        assertEquals(777L, r["task_cred"])
        assertEquals(3L, r["task_tasks"])
        assertEquals(0, r.overriddenCount)
    }

    @Test
    fun `覆盖率统计如实反映有多少字段被运行时表覆盖`() {
        val r = UnifiedOffsetResolver.resolve(json, baseline, fallback)
        // json 提供了 2 个键，它们都覆盖了基线
        assertEquals(2, r.overriddenCount)
        val byOrigin = r.countByOrigin()
        assertEquals(2, byOrigin[OffsetOrigin.OFFSETS_JSON])
        assertEquals(1, byOrigin[OffsetOrigin.COMPILED_BASELINE])
        assertEquals(1, byOrigin[OffsetOrigin.COMPILED_FALLBACK])
    }

    @Test
    fun `符号与结构体字段在同一个命名空间里解析`() {
        // json 把两者放在两个子对象里，但解析后是同一张表 ——
        // 否则 task_pi_lock 这类结构体字段会查不到
        val r = UnifiedOffsetResolver.resolve(json, emptyMap(), emptyMap())
        assertEquals(222L, r["task_pi_lock"])
        assertEquals(111L, r["off_init_task"])
    }

    @Test
    fun `不做跨版本推断 —— 换一份 json 只影响它自己声明的键`() {
        val other = GhostLockOffsetsIo.read("""{ "struct_fields": { "task_cred": 555 } }""")
        val r = UnifiedOffsetResolver.resolve(other, baseline, fallback)
        assertEquals("json 声明了就覆盖", 555L, r["task_cred"])
        assertEquals(OffsetOrigin.OFFSETS_JSON, r.originOf("task_cred"))
        // json 没声明的键仍走基线 —— 没有任何"按版本外推"的行为
        assertEquals(999L, r["off_init_task"])
        assertEquals(OffsetOrigin.COMPILED_BASELINE, r.originOf("off_init_task"))
    }

    @Test
    fun `空输入不崩且结果为空`() {
        val r = UnifiedOffsetResolver.resolve(null, emptyMap(), emptyMap())
        assertTrue(r.fields.isEmpty())
        assertTrue(r.missing.isEmpty())
        assertFalse(r.isComplete.not()) // 空集也算"完整"，语义上是"没有缺口"
    }
}
