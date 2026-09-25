package com.kernelpack.vivo

import com.kernelpack.profile.BaselineScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝厂 VR.ko 的**判定逻辑**（不再有地址规划 —— 见 [VrKoBypass] 的类注释）。
 *
 * 这组用例守的是**判定方向不能反**：
 * - `/proc/modules` 读不到时必须**保守认为需要抹标记**，否则会漏抹 → 子进程被杀；
 * - `vr` 模块的行首匹配不能误命中 `vrm` / `vradio`。
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
    fun `常量值与上游源码逐一一致`() {
        assertEquals(0x06L, VrKoBypass.VR_TAG_A_OFF)
        assertEquals(0x2cL, VrKoBypass.VR_TAG_B_OFF)
        assertEquals(0x400L, VrKoBypass.VR_SYSCALL_TP_FLAG)
    }

    @Test
    fun `tag A 与 tag B 必须同时清零 —— 它们都要在掩码里被覆盖`() {
        // vr 把"一个清一个没清"本身当作 tamper 证据，单独清一个会被杀。
        // 这里把两个偏移钉死：任何改动都必须重新核对 C 侧那两笔写。
        assertTrue("tag A 应落在 thread_info.flags 的头 8 字节内", VrKoBypass.VR_TAG_A_OFF in 0L..7L)
        assertTrue("tag B 应落在 +0x2c", VrKoBypass.VR_TAG_B_OFF == 0x2cL)
    }

    @Test
    fun `探到 vr ko 且当前不是蓝厂方案时给出提示`() {
        val modules = "ksud 1 0 - Live 0x0\nvr 2 0 - Live 0x0\n"
        assertTrue(VrKoBypass.shouldSuggestVivoScheme(modules, null, BaselineScheme.UNIVERSAL))
    }

    @Test
    fun `机型本身就是蓝厂时也提示 —— 此时 proc modules 根本读不到`() {
        // 这才是真机上最常见的情形：SELinux enforcing 下读不到 /proc/modules，
        // 唯一能拿到的正面证据就是"这台机器是蓝厂"。
        assertTrue(
            VrKoBypass.shouldSuggestVivoScheme(
                modulesText = null,
                deviceIdentityText = "vivo v2463a pd2520 v2463a",
                currentScheme = BaselineScheme.UNIVERSAL,
            ),
        )
        assertTrue(
            VrKoBypass.shouldSuggestVivoScheme(null, "iqoo i2401", BaselineScheme.UNIVERSAL),
        )
    }

    @Test
    fun `读不到 proc modules 时不能仅凭这一点就提示 —— 方向与 needsBypass 相反`() {
        // 若这里返回 true，每一台非蓝厂机器（SELinux 下同样读不到）都会看到
        // "建议改选蓝厂方案"，提示就变成噪音了。
        assertFalse(
            VrKoBypass.shouldSuggestVivoScheme(
                modulesText = null,
                deviceIdentityText = "google blazer panther",
                currentScheme = BaselineScheme.UNIVERSAL,
            ),
        )
    }

    @Test
    fun `已经选了蓝厂方案就不再提示改选蓝厂方案`() {
        val modules = "vr 2 0 - Live 0x0\n"
        assertFalse(VrKoBypass.shouldSuggestVivoScheme(modules, "vivo v2463a", BaselineScheme.VIVO))
    }

    @Test
    fun `机器上没有 vr ko 且不是蓝厂时不提示`() {
        assertFalse(
            VrKoBypass.shouldSuggestVivoScheme(
                "ksud 1 0 - Live 0x0\n",
                "google blazer",
                BaselineScheme.UNIVERSAL,
            ),
        )
    }
}
