package com.ting.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 载荷目录的机型关键词匹配。
 *
 * 这一组用例的存在理由：这里踩过一个**静默失效**的坑 ——
 * `MODEL_SEPARATOR` 的字符类只认小写，而 `displayName` 是 `"iQOO 13"` 这种大写，
 * 于是新增的 displayName 匹配一条都命中不了，而且**不报错**。
 * 所以必须把大小写与 `+` / `-` 的语义都钉死。
 */
class BundledPayloadMatchTest {

    @Test
    fun `大小写不敏感 —— displayName 是大写也能命中`() {
        // 回归：曾经 "iQOO" 匹配不上小写 haystack
        assertTrue(
            BundledPayloadCatalog.matchesModel(listOf("iQOO 13"), "vivo v2463a pd2463 iqoo 13"),
        )
        assertTrue(
            BundledPayloadCatalog.matchesModel(listOf("iQOO Neo10 Pro+"), "iqoo neo10 pro+ 6.6.89"),
        )
    }

    @Test
    fun `关键词必须全部出现（不是任一个）`() {
        assertFalse(
            "只有 iqoo 没有 13 时不该命中 iQOO 13",
            BundledPayloadCatalog.matchesModel(listOf("iqoo 13"), "vivo iqoo neo11"),
        )
        assertTrue(
            BundledPayloadCatalog.matchesModel(listOf("iqoo neo11"), "vivo iqoo neo11 pd2509"),
        )
    }

    @Test
    fun `加号是词的一部分 —— Neo10 Pro 与 Neo10 Pro+ 必须区分开`() {
        val plus = "iqoo neo10 pro+ 6.6.89"
        val plain = "iqoo neo10 pro 6.6.89"
        assertTrue(BundledPayloadCatalog.matchesModel(listOf("iqoo neo10 pro+"), plus))
        assertFalse("Pro 不该命中 Pro+", BundledPayloadCatalog.matchesModel(listOf("iqoo neo10 pro+"), plain))
        assertTrue(BundledPayloadCatalog.matchesModel(listOf("iqoo neo10 pro"), plain))
        assertFalse("Pro+ 不该命中 Pro", BundledPayloadCatalog.matchesModel(listOf("iqoo neo10 pro"), plus))
    }

    @Test
    fun `连字符同样保留在词内`() {
        assertTrue(BundledPayloadCatalog.matchesModel(listOf("sm-s918b"), "samsung sm-s918b"))
    }

    @Test
    fun `空关键词组不命中任何东西`() {
        assertFalse(BundledPayloadCatalog.matchesModel(listOf(""), "vivo iqoo 13"))
        assertFalse(BundledPayloadCatalog.matchesModel(emptyList(), "vivo iqoo 13"))
    }

    @Test
    fun `改版后的 haystack 形态能命中市场名`() {
        // 这是 vivo 机型的真实目标：haystack 由全部身份串拼成，
        // 只要厂商把市场名放在**任意一个**字段里，就能命中目录条目。
        val haystack = "vivo v2463a pd2463 sun iqoo 13"
        assertTrue(BundledPayloadCatalog.matchesModel(listOf("iqoo 13"), haystack))
    }
}
