package com.kernelpack.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游 9 个符号**覆盖**到基线 .so 的 25 个键上。
 *
 * 这组用例守的是"50 档到底能不能构建"这件事本身：
 * 若 `symbolOffsets` 只有上游那 9 个键，打补丁时基线 .so 里其余 16 个字面量
 * **改不了**，闸门会（正确地）拦住 —— 于是 50 档永远构建不出来。
 *
 * 所以必须用「基线符号集打底 + 上游覆盖」的语义。
 */
class UpstreamSymbolOverlayTest {

    private val base = BaselineProfiles.IONSTACK_P10.symbolOffsets
    private val ups = BaselineRegistry.entriesWithUpstream
        .filter { it.profile.id.startsWith("up-") }

    @Test
    fun `上游档的符号集等于基线的键集 —— 否则构建时会被闸门拦住`() {
        for (e in ups) {
            assertEquals(
                "${e.profile.id} 的键集与基线 .so 不一致，打补丁会缺字面量",
                base.keys,
                e.profile.symbolOffsets.keys,
            )
        }
    }

    @Test
    fun `基线的键数是 25`() {
        assertEquals(25, base.size)
    }

    @Test
    fun `上游提供的值确实覆盖上去了 —— 不是原样照搬基线`() {
        // 取一档，检查 INIT_TASK 这类能映射上的键，值应与基线不同
        val e = ups.first()
        val overridden = base.keys.count { base[it] != e.profile.symbolOffsets[it] }
        assertTrue(
            "${e.profile.id} 一个键都没被覆盖，说明 SymbolKeyMapping 没接上",
            overridden > 0,
        )
    }

    @Test
    fun `上游覆盖的键落在能映射的那几个上`() {
        // 上游 9 个键里，能映射到我方键的是 INIT_TASK / SLIDE_INIT_TASK /
        // ROOT_TASK_GROUP / SLIDE_ROOT_TASK_GROUP / SELINUX_BLOB_SIZES /
        // SELINUX_ENFORCING / SECURITY_HOOK_HEADS / SLIDE_NFULNL_LOGGER /
        // SLIDE_LOGGERS_0_1 / SLIDE_RANDOM_BOOT_ID_DATA / SLIDE_SYSCTL_BOOTID
        val e = ups.first()
        val changed = base.keys.filter { base[it] != e.profile.symbolOffsets[it] }
        assertTrue("应覆盖到 INIT_TASK", changed.contains("INIT_TASK"))
        assertTrue("应覆盖到 ROOT_TASK_GROUP", changed.contains("ROOT_TASK_GROUP"))
    }

    @Test
    fun `基线独有的 ashmem 键原样保留 —— 那是等 boot_img 补的`() {
        val e = ups.first()
        // ASHMEM_* 上游供不了，值应与基线一致（待构建时被提取值改写）
        for (k in listOf("ASHMEM_FOPS", "ASHMEM_IOCTL", "ASHMEM_MMAP")) {
            if (base.containsKey(k)) {
                assertEquals("$k 不该被上游改动", base[k], e.profile.symbolOffsets[k])
            }
        }
    }

    @Test
    fun `不同内核档覆盖出的值互不相同`() {
        // 6.6.89 与 6.6.118 的 init_task 地址必然不同；若相同说明覆盖没按档走
        val a = ups.firstOrNull { it.profile.id.contains("6-6-89") }
        val b = ups.firstOrNull { it.profile.id.contains("6-6-118") }
        if (a != null && b != null) {
            assertNotEquals(
                "两档的 INIT_TASK 值不该相同",
                a.profile.symbolOffsets["INIT_TASK"],
                b.profile.symbolOffsets["INIT_TASK"],
            )
        }
    }

    @Test
    fun `上游档的三族结构体偏移确实不同`() {
        val f = ups.map { it.profile.abi.structOffsets["task_pi_lock"] }.distinct()
        assertTrue("50 档应至少落在 2 个不同的 struct 族上，实际 $f", f.size >= 2)
    }
}
