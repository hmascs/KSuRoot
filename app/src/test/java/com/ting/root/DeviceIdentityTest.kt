package com.ting.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备身份解析的测试。
 *
 * 用例全部来自**真机实测**的属性值（本机 vivo：`ro.product.model=V2463A`、
 * `ro.product.device=PD2463`、三个市场名属性全空）。
 */
class DeviceIdentityTest {

    // ---------------------------------------------------------------- 市场名优先级

    @Test
    fun `标准 marketname 优先于厂商自定义`() {
        val props = mapOf(
            "ro.product.marketname" to "iQOO 13",
            "ro.vivo.product.model" to "V2463A",
        )
        assertEquals("iQOO 13", DeviceIdentity.marketName { props[it] })
    }

    @Test
    fun `标准属性为空时回落厂商自定义`() {
        val props = mapOf("ro.vivo.product.model" to "iQOO Neo10 Pro+")
        assertEquals("iQOO Neo10 Pro+", DeviceIdentity.marketName { props[it] })
    }

    @Test
    fun `本机实测：全部为空时返回 null 而不是拿型号代码冒充`() {
        // 真机实测：ro.product.marketname / ro.config.marketing_name /
        // ro.vivo.product.model 全为空。这时必须返回 null，
        // 让界面如实说"这是型号代码"，而不是把 V2463A 当市场名。
        assertNull(DeviceIdentity.marketName { null })
        assertNull(DeviceIdentity.marketName { "   " })
    }

    @Test
    fun `空白值不算命中`() {
        val props = mapOf(
            "ro.product.marketname" to "  ",
            "ro.config.marketing_name" to "iQOO 13",
        )
        assertEquals("iQOO 13", DeviceIdentity.marketName { props[it] })
    }

    // ---------------------------------------------------------------- 代码 vs 市场名

    @Test
    fun `型号代码能被识别出来`() {
        for (code in listOf("V2463A", "PD2463", "SM-S918B", "V2405A", "CPH2521")) {
            assertTrue("$code 应判为代码", DeviceIdentity.looksLikeCode(code))
        }
    }

    @Test
    fun `市场名不会误判成代码`() {
        for (name in listOf(
            "iQOO 13", "iQOO Neo10 Pro+", "vivo X200 Pro", "Pixel 9 Pro",
            "iQOO Neo11", "Redmi K70 Ultra", "Galaxy S23 Ultra",
        )) {
            assertFalse("$name 不该判为代码", DeviceIdentity.looksLikeCode(name))
        }
    }

    @Test
    fun `空值与 null 不判为代码`() {
        assertFalse(DeviceIdentity.looksLikeCode(null))
        assertFalse(DeviceIdentity.looksLikeCode(""))
        assertFalse(DeviceIdentity.looksLikeCode("   "))
    }

    // ---------------------------------------------------------------- haystack

    @Test
    fun `haystack 收全所有身份串且小写去重`() {
        val t = DeviceIdentity.identityText(
            manufacturer = "vivo", brand = "vivo", model = "V2463A",
            device = "PD2463", product = "PD2463", board = "kalama",
            market = "iQOO 13",
        )
        // 五个不同值（vivo 去重）都在
        for (token in listOf("vivo", "v2463a", "pd2463", "kalama", "iqoo 13")) {
            assertTrue("haystack 缺少 $token：$t", t.contains(token))
        }
        assertEquals("vivo 应去重", 1, Regex("\\bvivo\\b").findAll(t).count())
    }

    @Test
    fun `没有市场名时 haystack 仍可用 —— 靠型号与代号`() {
        val t = DeviceIdentity.identityText(
            manufacturer = "vivo", brand = "vivo", model = "V2463A",
            device = "PD2463", product = "PD2463", board = null, market = null,
        )
        assertTrue(t.contains("v2463a"))
        assertTrue(t.contains("pd2463"))
    }

    @Test
    fun `关键回归：市场名在别的字段时也能被 haystack 覆盖`() {
        // 这正是 vivo 机型匹配不上的根因：Build.MODEL 是代码，名字在别处。
        // haystack 把 device/product/市场名一起收进来后，无论落在哪个字段都能命中。
        val t = DeviceIdentity.identityText(
            manufacturer = "vivo", brand = "vivo", model = "V2463A",
            device = "PD2463", product = "iqoo 13", board = null, market = null,
        )
        assertTrue("名字在 product 里也要命中：$t", t.contains("iqoo 13"))
    }

    @Test
    fun `全空输入得到空串而不是异常`() {
        assertEquals("", DeviceIdentity.identityText(null, null, null, null, null, null, null))
    }
}
