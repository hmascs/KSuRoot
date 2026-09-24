package com.kernelpack.offsets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * offsets.json 符号键 → 我方 SymbolCatalog 键 的对照表。
 *
 * 这组用例守两件事：
 * 1. **映射目标必须真实存在** —— 键名写错会静默失效（查表查不到，但不报错），
 *    这是本项目踩过的那类"静默失效"坑；
 * 2. **不许硬凑** —— 对不上的键要如实留在"用不上"清单里，
 *    不能为了让覆盖率好看而把语义不同的键对上。
 */
class SymbolKeyMappingTest {

    /** `SymbolCatalog` 里实际存在的键（写死在这里，改那边就得改这边）。 */
    private val ourKeys = setOf(
        // 25 个必需键（从 SymbolCatalog 的 required 标记实测得出）
        "ASHMEM_MISC_FOPS", "ASHMEM_FOPS", "ASHMEM_IOCTL", "ASHMEM_COMPAT_IOCTL",
        "ASHMEM_MMAP", "ASHMEM_OPEN", "ASHMEM_RELEASE", "ASHMEM_SHOW_FDINFO",
        "CONFIGFS_READ_ITER", "CONFIGFS_BIN_WRITE_ITER", "COPY_SPLICE_READ", "NOOP_LLSEEK",
        "INIT_TASK", "ROOT_TASK_GROUP", "SELINUX_BLOB_SIZES", "SELINUX_ENFORCING",
        "SECURITY_HOOK_HEADS", "KMALLOC_CACHES", "ANON_PIPE_BUF_OPS",
        "SLIDE_LOGGERS_0_1", "SLIDE_NFULNL_LOGGER", "SLIDE_RANDOM_BOOT_ID_DATA",
        "SLIDE_SYSCTL_BOOTID", "SLIDE_INIT_TASK", "SLIDE_ROOT_TASK_GROUP",
        // 3 个可选键
        "SLIDE_NFULNL_LOG_PACKET", "SYS_EXIT_TP", "RVH_COMMIT_CREDS_TP",
    )

    @Test
    fun `每个映射目标都必须是我方真实存在的键`() {
        for ((jsonKey, oursList) in SymbolKeyMapping.DIRECT) {
            assertTrue("$jsonKey 没有映射目标", oursList.isNotEmpty())
            for (ours in oursList) {
                assertTrue(
                    "$jsonKey 映射到了不存在的我方键 '$ours' —— 查表会静默失效",
                    ours in ourKeys,
                )
            }
        }
    }

    @Test
    fun `一对多映射与上游用法一致`() {
        // GhostLock 的 runtime_struct_offsets.h 就是这么用的：
        // off_init_task 同时喂 INIT_TASK 与 SLIDE_INIT_TASK
        assertEquals(
            listOf("INIT_TASK", "SLIDE_INIT_TASK"),
            SymbolKeyMapping.DIRECT["off_init_task"],
        )
        // off_slide_boot_id 同时喂两个 slide 键
        assertEquals(
            listOf("SLIDE_RANDOM_BOOT_ID_DATA", "SLIDE_SYSCTL_BOOTID"),
            SymbolKeyMapping.DIRECT["off_slide_boot_id"],
        )
    }

    @Test
    fun `一对多展开后覆盖面显著提升（勘误回归）`() {
        // 最初写成一对一、只覆盖 5 个 —— 那是过于保守。
        // 这条守住"确实展开了"，防止有人又改回一对一。
        assertTrue(
            "可覆盖键数应 >= 11，实际 ${SymbolKeyMapping.coverableCount()}",
            SymbolKeyMapping.coverableCount() >= 11,
        )
    }

    @Test
    fun `我方独有清单里的键也必须是真实键`() {
        for (k in SymbolKeyMapping.JSON_CANNOT_SUPPLY) {
            assertTrue("$k 不是我方真实键", k in ourKeys)
        }
    }

    @Test
    fun `映射目标不与我方独有清单重叠`() {
        val allTargets = SymbolKeyMapping.DIRECT.values.flatten().toSet()
        val overlap = allTargets intersect SymbolKeyMapping.JSON_CANNOT_SUPPLY
        assertTrue("同一个键既说能对上、又说对不上：$overlap", overlap.isEmpty())
    }

    @Test
    fun `键名格式正确`() {
        for ((jsonKey, oursList) in SymbolKeyMapping.DIRECT) {
            assertTrue("$jsonKey 应带 off_ 前缀", jsonKey.startsWith("off_"))
            for (ours in oursList) assertEquals("$ours 应是大写键名", ours.uppercase(), ours)
        }
    }

    @Test
    fun `映射只搬运不换算 —— 数值原样保留且一对多都写到`() {
        val doc = GhostLockOffsetsIo.read(
            """{ "symbols": { "off_init_task": 37670336, "off_rb_erase": 12370420 } }""",
        )
        val m = SymbolKeyMapping.mapSymbols(doc)
        assertEquals("INIT_TASK 应拿到值", 37670336L, m.symbols["INIT_TASK"])
        assertEquals("SLIDE_INIT_TASK 也该拿到同一个值", 37670336L, m.symbols["SLIDE_INIT_TASK"])
        assertEquals(2, m.symbols.size)
        assertTrue(m.unusedFromJson.contains("off_rb_erase"))
    }

    @Test
    fun `用不上的键要如实列出`() {
        val doc = GhostLockOffsetsIo.read(
            """{ "symbols": { "off_remove_waiter": 1, "off_worker_thread": 2, "off_init_task": 3 } }""",
        )
        val m = SymbolKeyMapping.mapSymbols(doc)
        assertEquals(listOf("off_remove_waiter", "off_worker_thread"), m.unusedFromJson)
    }

    @Test
    fun `覆盖率小于 1 —— ashmem 那一路仍须走 boot_img 提取`() {
        val doc = GhostLockOffsetsIo.read(
            """{ "symbols": { "off_init_task": 1, "off_init_cred": 2, "off_root_task_group": 3,
                            "off_selinux_enforcing": 4, "off_selinux_blob_sizes": 5,
                            "off_security_hook_heads": 6, "off_slide_nfulnl_logger": 7,
                            "off_slide_loggers_0_1": 8, "off_slide_boot_id": 9,
                            "off_slide_sysctl_bootid": 10 } }""",
        )
        val cov = SymbolKeyMapping.coverage(doc)
        assertTrue("覆盖率不该是 1.0（ashmem 路线 json 供不了）：$cov", cov < 1.0)
        assertTrue("覆盖率也不该太低：$cov", cov > 0.3)
    }

    @Test
    fun `空文档不崩`() {
        val m = SymbolKeyMapping.mapSymbols(GhostLockOffsetsIo.read("{}"))
        assertTrue(m.symbols.isEmpty())
        assertEquals(0.0, SymbolKeyMapping.coverage(GhostLockOffsetsIo.read("{}")), 0.0001)
    }
}
