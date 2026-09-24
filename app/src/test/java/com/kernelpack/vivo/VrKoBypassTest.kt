package com.kernelpack.vivo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝厂 VR.ko 抹标记的地址规划。
 *
 * 这组用例守的是**地址算错就会写坏内核**：
 * 抹标记是直接往 `task_struct` 上写，算错一位就是改到别的字段上。
 * 上游作者自己都为其中一笔留了"请先实测"的警告，所以这里必须把
 * 对齐与串联关系钉死，不能靠"看起来对"。
 */
class VrKoBypassTest {

    @Test
    fun `vr 模块行首匹配 —— 但 vrm 之类不能被误命中`() {
        assertTrue(VrKoBypass.looksLikeVrModule("vr 12345 0 - Live 0x0000000000000000"))
        assertTrue(VrKoBypass.looksLikeVrModule("vr_ko 1 0 - Live 0x0"))
        assertFalse("vrm 不该命中", VrKoBypass.looksLikeVrModule("vrm 1 0 - Live 0x0"))
        assertFalse("vradio 不该命中", VrKoBypass.looksLikeVrModule("vradio 1 0 - Live 0x0"))
        assertFalse("短行不该崩也不该命中", VrKoBypass.looksLikeVrModule("vr"))
    }

    @Test
    fun `读不到 proc modules 时要保守判定为需要抹标记`() {
        // SELinux enforcing 下 untrusted_app 读不到 /proc/modules 是常态。
        // 若这里返回 false，就会**漏抹标记 → 子进程在 W2 校验时被杀**，
        // 表现出来是"提权成功但立刻失败"，极难排查。
        assertTrue(VrKoBypass.needsBypass(null))
    }

    @Test
    fun `已加载 vr ko 才需要抹标记`() {
        assertTrue(VrKoBypass.needsBypass("ksud 1 0 - Live 0x0\nvr 2 0 - Live 0x0\n"))
        assertFalse(VrKoBypass.needsBypass("ksud 1 0 - Live 0x0\n"))
    }

    @Test
    fun `第一笔写 thread_info_flags 自身`() {
        val p = VrKoBypass.plan(childTask = 0xFFFF0000_1000L, threadInfoFlagsOff = 0)
        assertEquals(0xFFFF0000_1000L, p.writes[0].address)
        assertEquals("VR: flags+tagA", p.writes[0].label)
        assertFalse("第一笔不该依赖前一笔", p.writes[0].dependsOnPrevious)
    }

    @Test
    fun `thread_info_flags 偏移非零时地址要跟着走`() {
        // 上游源码里这个偏移取自结构体表；写成 0 是错的，这里确保它真的被用上
        val p = VrKoBypass.plan(childTask = 0x1000L, threadInfoFlagsOff = 0x08)
        assertEquals(0x1008L, p.writes[0].address)
    }

    @Test
    fun `tagB 那笔必须向下对齐到 8 字节`() {
        // tag B 在 +0x2c，不是 8 的倍数；利用原语是 64 位粒度，
        // 所以实际写的是 +0x28（整字清零 0x28-0x2f）。
        val p = VrKoBypass.plan(childTask = 0x1000L, threadInfoFlagsOff = 0)
        val tagB = p.writes.first { it.label == "VR: tagB" }
        assertEquals(0x1028L, tagB.address)
        assertEquals("必须是 8 的倍数", 0L, tagB.address % 8)
    }

    @Test
    fun `tagB 那笔依赖前一筆成功`() {
        val p = VrKoBypass.plan(childTask = 0x1000L, threadInfoFlagsOff = 0)
        assertTrue(p.writes.first { it.label == "VR: tagB" }.dependsOnPrevious)
    }

    @Test
    fun `可以关掉 tagB 那笔（上游作者要求实测后再决定）`() {
        val p = VrKoBypass.plan(childTask = 0x1000L, threadInfoFlagsOff = 0, includeTagB = false)
        assertEquals(1, p.writes.size)
        assertTrue(p.writes.none { it.label == "VR: tagB" })
    }

    @Test
    fun `写入模式与臂取值和上游一致`() {
        val p = VrKoBypass.plan(childTask = 0x1000L, threadInfoFlagsOff = 0)
        // 这两个不是"值/长度"，是 pselect 路由参数；取错会写到别的地方
        assertEquals(1, p.mode)
        assertEquals(1, p.leaf)
    }

    @Test
    fun `常量值与上游源码逐一一致`() {
        assertEquals(0x06L, VrKoBypass.VR_TAG_A_OFF)
        assertEquals(0x2cL, VrKoBypass.VR_TAG_B_OFF)
        assertEquals(0x400L, VrKoBypass.VR_SYSCALL_TP_FLAG)
    }

    @Test
    fun `tag A 落在第一筆覆盖的 8 字节字内 —— 否则第一筆就白写了`() {
        // 第一笔清的是 [0,8) 这个字；tag A 在 +0x06 必须落在这个区间里
        assertTrue("tag A 偏移 ${VrKoBypass.VR_TAG_A_OFF} 不在 0..7 内",
            VrKoBypass.VR_TAG_A_OFF in 0L..7L)
    }

    @Test
    fun `tag B 落在 tagB 那筆覆盖的 8 字节字内`() {
        val aligned = (VrKoBypass.VR_TAG_B_OFF) and 7L.inv()
        assertTrue("tag B 偏移 ${VrKoBypass.VR_TAG_B_OFF} 不在 [$aligned, ${aligned + 8}) 内",
            VrKoBypass.VR_TAG_B_OFF in aligned until (aligned + 8))
    }
}
