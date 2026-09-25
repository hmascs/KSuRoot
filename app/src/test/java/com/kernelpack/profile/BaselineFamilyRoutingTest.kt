package com.kernelpack.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 基线库必须**按结构体族**选，而且所选的文件必须**真的在包里**。
 *
 * 这组用例的由来是三次同样的错：东西建好了、放进包了，但**没有代码用它**
 * （`GhostLockKernelCatalog`、`libgl_universal.so`、两份自编基线）。
 * 光有"文件存在"不算接通 —— 必须有一条断言走完「内核串 → 族 → 库名 → 文件存在」整条链。
 */
class BaselineFamilyRoutingTest {

    private val libs = BaselineRegistry.BaselineLibraries
    private val jniDir = File("src/main/jniLibs/arm64-v8a")

    @Test
    fun `6_1 内核路由到 6_1 族基线`() {
        assertEquals(
            libs.SIX_ONE,
            libs.resolve("libionstack.so", "6.1.145-android14-11-g09f1c0074ad7-ab14226177"),
        )
    }

    @Test
    fun `6_12 内核路由到 6_12 族基线`() {
        assertEquals(
            libs.SIX_TWELVE,
            libs.resolve("libionstack.so", "6.12.38-android16-5-gfde7767f6ef6-abogki481467632-4k"),
        )
    }

    @Test
    fun `6_6 内核沿用方案原有的库 —— 不改动既有行为`() {
        assertEquals("libionstack.so", libs.resolve("libionstack.so", "6.6.89-android15-8-g0889fe95bb10-ab14402178-4k"))
        assertEquals("libbs.so", libs.resolve("libbs.so", "6.6.89-android15-8-g0889fe95bb10-ab14402178-4k"))
    }

    @Test
    fun `认不出内核串时回落到方案原有的库 —— 不猜`() {
        assertEquals("libbs.so", libs.resolve("libbs.so", null))
        assertEquals("libbs.so", libs.resolve("libbs.so", ""))
        assertEquals("libbs.so", libs.resolve("libbs.so", "9.9.9-whatever"))
        assertNull("认不出就该是 null", libs.familyOf("9.9.9-whatever"))
    }

    @Test
    fun `族判定覆盖三族且互不混淆`() {
        val f = libs.familyOf("6.1.115-android14-11-ga2521ca27699-ab13294383")
        val s = libs.familyOf("6.6.118-android15-8-g2e6b9c3812c5-ab15114928-4k")
        val t = libs.familyOf("6.12.23-android16-5-gb2a876903b49-ab14541642-4k")
        assertEquals(GhostLockKernelOffsets.StructFamily.F6_1, f)
        assertEquals(GhostLockKernelOffsets.StructFamily.F6_6, s)
        assertEquals(GhostLockKernelOffsets.StructFamily.F6_12, t)
    }

    @Test
    fun `★ 被路由到的两份自编基线必须真的存在于 jniLibs —— 防止「建好没接线」`() {
        // 这条是本组用例的核心：光断言"函数返回值对"不够，
        // 还要断言那个文件**真的在包里**，否则就是把用户引向一个不存在的库。
        for (name in listOf(libs.SIX_ONE, libs.SIX_TWELVE)) {
            val f = File(jniDir, name)
            assertTrue(
                "路由指向 $name，但 jniLibs 里没有这个文件 —— 构建时会报『内置载荷缺失』",
                f.exists(),
            )
            assertTrue("$name 大小异常", f.length() > 50_000)
        }
    }

    @Test
    fun `每一档上游内核都能推出结构体族`() {
        val miss = GhostLockKernelOffsets.KERNELS.filter { libs.familyOf(it.release) == null }
        assertTrue("以下内核推不出族：${miss.map { it.release }}", miss.isEmpty())
    }

    @Test
    fun `上游内核推出的族与它自己声明的族一致`() {
        // 双重校验：路由用的族判定不能与数据层声明的族打架
        val bad = GhostLockKernelOffsets.KERNELS.filter {
            libs.familyOf(it.release) != it.family
        }
        assertTrue(
            "族判定与数据层声明不一致：${bad.map { it.release to it.family }}",
            bad.isEmpty(),
        )
    }
}
