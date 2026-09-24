package com.kernelpack.offsets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实的**荣耀 6.12.38 `offsets.json`（BTF 实测样本，来自
 * `pyyyc/honor-6.12.38-43499-research`）做往返验证。
 *
 * 为什么要单独一组、不用自造样本：
 * 自造样本只能验证"我按自己以为的格式读写没问题"，
 * 而这份文件是**别人产出的**——它才代表真实生态里的写法。
 * 我方要互通的对象是它，不是我的想象。
 *
 * 该文件同时是我们与 GhostLock 源码 11/11 对上的那份证据（见 `VR.ko处理-源码实现.md`）。
 */
class HonorOffsetsRoundTripTest {

    private fun loadSample(): String =
        javaClass.getResourceAsStream("/honor_6.12.38_offsets.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("测试资源缺失：honor_6.12.38_offsets.json")

    @Test
    fun `真实荣耀样本能解析出内核版本与关键字段`() {
        val d = GhostLockOffsetsIo.read(loadSample())
        assertEquals(
            "6.12.38-android16-5-gfde7767f6ef6-abogki481467632-4k",
            d.release,
        )
        // 这几个值同时出现在 GhostLock 源码的编译期兜底里（11/11 对上的其中几个）
        assertEquals(2540L, d.structFields["task_pi_lock"])   // 0x9EC
        assertEquals(2304L, d.structFields["task_cred"])      // 0x900
        assertEquals(1592L, d.structFields["task_tasks"])     // 0x638
        assertEquals(112L, d.structFields["rt_mutex_waiter_size"])
        assertEquals(80L, d.structFields["rt_mutex_waiter_task"])
    }

    @Test
    fun `真实样本的关键符号地址`() {
        val d = GhostLockOffsetsIo.read(loadSample())
        assertEquals(37670336L, d.symbols["off_init_task"])
        assertEquals(37760176L, d.symbols["off_init_cred"])
        assertNotNull(d.symbols["off_remove_waiter"])
        assertNotNull(d.symbols["off_rt_mutex_adjust_prio_chain"])
    }

    @Test
    fun `真实样本往返后逐字段一致`() {
        val first = GhostLockOffsetsIo.read(loadSample())
        val reRead = GhostLockOffsetsIo.read(GhostLockOffsetsIo.write(first))
        assertEquals("标量往返后不一致", first.scalars, reRead.scalars)
        assertEquals("symbols 往返后不一致", first.symbols, reRead.symbols)
        assertEquals("struct_fields 往返后不一致", first.structFields, reRead.structFields)
        assertEquals("未知键往返后不一致", first.unknown.keys, reRead.unknown.keys)
    }

    @Test
    fun `真实样本不含我们自己发明的键 —— 证明字段表不是照抄自己的想象`() {
        val d = GhostLockOffsetsIo.read(loadSample())
        val ours = setOf("variantLabel", "versionNumber", "architecture", "imageBase",
            "symbolOffsets", "structOffsets", "unresolved")
        val leaked = ours intersect (d.scalars.keys + d.unknown.keys)
        assertTrue(
            "真实生态文件里不该出现我方自有 schema 的键，出现了说明字段表写串了：$leaked",
            leaked.isEmpty(),
        )
    }

    @Test
    fun `字段表已完整覆盖真实生态 —— 荣耀样本没有任何缺失键`() {
        val d = GhostLockOffsetsIo.read(loadSample())
        // 我原本以为"样本不完整、有缺失才正常"，结果这条断言直接把测试打挂了 ——
        // 荣耀那份样本在 [OffsetsSchema] 下**一个缺失键都没有**：
        // 17 个符号键 + 20 个结构体键全部命中。
        // 也就是说这份 schema 不是我拼凑的，它确实覆盖了真实格式的全集。
        // 反过来，如果哪天这里开始报缺失，就说明对方扩展了格式，该更新字段表了。
        assertEquals(
            "符号键出现缺失，对方可能扩展了格式：" + d.missingSymbols(),
            emptyList<String>(),
            d.missingSymbols(),
        )
        assertEquals(
            "结构体键出现缺失，对方可能扩展了格式：" + d.missingStructFields(),
            emptyList<String>(),
            d.missingStructFields(),
        )
    }
}
