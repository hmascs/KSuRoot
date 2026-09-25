package com.kernelpack.vivo

import com.ting.root.BundledPayloadCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ★ **标注表与真实字节必须对得上**。
 *
 * 这是"能力与它的闸门是一对"这条铁律在 vivo 这一档上的具体落地：
 *
 * - 构建期闸门（[VrKoPayloadGate]）拿**真实字节**现算 → 拦得住；
 * - 展示期标注（`BundledPayload.vivoVrBypass`）提前把坏消息说出来 → 用户不用撞一次墙。
 *
 * 两者**必须一致**。本文件把标注表和磁盘上的每一份蓝厂载荷逐条对一遍：
 * 任何一份实测"不带绕过"却没标注的载荷，都会让用例变红 ——
 * 也就是**不允许出现沉默的缺口**（用户看着它像正常选项，选中才知道会炸）。
 *
 * 为什么不直接让标注表当判据：表是手写的，手写的东西一定会漏。
 * 判据必须在字节上，表只负责"提前说"。
 */
class BundledPayloadVrKoAuditTest {

    private val jniDir = File("src/main/jniLibs/arm64-v8a")

    /** 两份**已实测不带**绕过的无源码蓝厂载荷 —— 用户决定：降级标注 + 选中时确认。 */
    private val knownWithoutBypass = listOf(
        "libksu_vivo_iqoo12_a15.so",
        "libksu_vivo_iqooz95gvivot35grootghos_any_3cc6.so",
    )

    @Test
    fun `两份无源码蓝厂载荷必须标注为不带绕过`() {
        for (library in knownWithoutBypass) {
            val entry = BundledPayloadCatalog.byLibrary(library)
                ?: error("$library 不在内置载荷登记表里")
            assertEquals(
                "$library 应标注 vivoVrBypass = false（它是已知不带绕过的）",
                false,
                entry.vivoVrBypass,
            )
        }
    }

    @Test
    fun `标注为 false 的每一条 —— 真实字节必须确实不带绕过`() {
        // 反向：不许"为了保险"把带绕过的载荷也标成不带。
        // 假警报比不报更糟 —— 那会让用户白白放弃一份能用的载荷。
        for (entry in BundledPayloadCatalog.ALL.filter { it.vivoVrBypass == false }) {
            val file = File(jniDir, entry.library)
            assertTrue("${entry.library} 不在 jniLibs 里", file.isFile)
            val result = VrKoPayloadCheck.check(file.readBytes())
            assertEquals(
                "${entry.library} 被标注为不带绕过，但实测是 ${result.status}",
                VrKoPayloadCheck.Status.ABSENT,
                result.status,
            )
        }
    }

    @Test
    fun `★ 磁盘上每一份实测不带绕过的蓝厂载荷都必须被标注 —— 不许有沉默的缺口`() {
        val files = jniDir.listFiles { f ->
            f.isFile && f.name.startsWith("libksu_vivo_") && f.name.endsWith(".so")
        }?.toList().orEmpty()
        assertTrue("jniLibs 里一份蓝厂载荷都没扫到，路径约定变了？", files.isNotEmpty())

        val silentlyMissing = files
            .filter { VrKoPayloadCheck.check(it.readBytes()).status != VrKoPayloadCheck.Status.PRESENT }
            .filter { BundledPayloadCatalog.byLibrary(it.name)?.vivoVrBypass != false }
            .map { it.name }

        assertEquals(
            "这些蓝厂载荷实测不带 vr.ko 绕过，但登记表里没标 vivoVrBypass = false：" +
                "用户会看到一个看起来正常的选项，选中之后才发现会炸。" +
                "要么修好载荷，要么如实标注。",
            emptyList<String>(),
            silentlyMissing,
        )
    }

    @Test
    fun `蓝厂载荷目录里绝大多数都带绕过 —— 判据不能把能用的判成不能用`() {
        val files = jniDir.listFiles { f ->
            f.isFile && f.name.startsWith("libksu_vivo_") && f.name.endsWith(".so")
        }?.toList().orEmpty()
        val present = files.count { VrKoPayloadCheck.check(it.readBytes()).status == VrKoPayloadCheck.Status.PRESENT }
        assertEquals("实测：34 份里 32 份带绕过、2 份不带", files.size - 2, present)
    }

    @Test
    fun `会被路由到蓝厂方案的三份载荷都必须带绕过`() {
        // 这三份是"蓝厂方案"实际会取到的载荷：
        //   6.6 → libbs.so（上游自带）· 6.1 / 6.12 → 自编族基线
        // 只要这三份带绕过，闸门就不会把正常的蓝厂构建拦下来。
        for (name in listOf("libbs.so", "libbaseline_6_1.so", "libbaseline_6_12.so")) {
            val file = File(jniDir, name)
            assertTrue("$name 不在 jniLibs 里", file.isFile)
            assertEquals(
                "$name 必须带 vr.ko 抹标记 —— 否则蓝厂方案根本走不通",
                VrKoPayloadCheck.Status.PRESENT,
                VrKoPayloadCheck.check(file.readBytes()).status,
            )
        }
    }
}
