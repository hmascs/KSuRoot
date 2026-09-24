package com.kernelpack.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游内核清单的**结构与出处**约束。
 *
 * 这组用例守两件事：
 * 1. 清单形态稳定（去重、按小版本分组正确、排序是数值序而不是字典序）；
 * 2. **它没有被误当成我方实测数据** —— 这是项目铁律里最容易在搬运时被破坏的一条：
 *    别家的清单可以用，但必须留着"这是上游声称"的标记。
 */
class GhostLockKernelCatalogTest {

    private val c = GhostLockKernelCatalog

    @Test
    fun `清单一非空且规模合理`() {
        assertTrue("清单不该为空", c.VERSIONS.size >= 40)
    }

    @Test
    fun `清单无重复`() {
        assertEquals(c.VERSIONS.size, c.VERSIONS.toSet().size)
    }

    @Test
    fun `小版本分组的条目数之和等于总数 —— 没有条目在分组时被丢掉`() {
        assertEquals(c.VERSIONS.size, c.BY_MINOR.values.sumOf { it.size })
    }

    @Test
    fun `小版本排序是数值序而不是字典序`() {
        // 字典序会把 "6.12.23" 排到 "6.6.118" 前面；这里必须是 6.1 < 6.6 < 6.12
        val v = c.MINOR_VERSIONS
        assertTrue("6.1.x 应排在 6.6.x 之前", v.indexOfFirst { it.startsWith("6.1.") } < v.indexOfFirst { it.startsWith("6.6.") })
        assertTrue("6.6.x 应排在 6.12.x 之前", v.indexOfFirst { it.startsWith("6.6.") } < v.indexOfFirst { it.startsWith("6.12.") })
    }

    @Test
    fun `每条都能被 contains 精确命中`() {
        c.VERSIONS.forEach { assertTrue(it, c.contains(it)) }
    }

    @Test
    fun `contains 不做前缀推断 —— 未登记的小版本必须返回 false`() {
        // 这是条安全约束：如果我方按"同小版本就认"，会把未验证的内核当成支持的。
        assertFalse(c.contains("6.6.118-android15-8-gdeadbeefdead-ab000000000-4k"))
        assertFalse(c.contains("9.9.9-android99-9-g000000000000-ab000000000-4k"))
    }

    @Test
    fun `同小版本的兄弟条目可查且不含自身`() {
        val multi = c.BY_MINOR.entries.first { it.value.size > 1 }
        val one = multi.value.first()
        val sib = c.siblingsOf(one)
        assertFalse("兄弟列表不该含自己", sib.contains(one))
        assertTrue("兄弟列表不该为空（该小版本有多条）", sib.isNotEmpty())
        assertTrue(sib.all { it.substringBefore('-') == one.substringBefore('-') })
    }

    @Test
    fun `出处标记存在 —— 严禁被当成实测数据搬运`() {
        assertEquals("YuKongA/ghostlock-app", c.SOURCE_REPO)
        assertTrue(c.SOURCE_PATH.contains("src/kernels"))
    }

    @Test
    fun `覆盖到我方主线之外的小版本（这是并入它的主要理由）`() {
        // 6.1.x 是我方主线原本没有的；上游补上了这一块
        assertTrue("应覆盖 6.1.x", c.MINOR_VERSIONS.any { it.startsWith("6.1.") })
        assertTrue("应覆盖 6.6.x", c.MINOR_VERSIONS.any { it.startsWith("6.6.") })
        assertTrue("应覆盖 6.12.x", c.MINOR_VERSIONS.any { it.startsWith("6.12.") })
    }
}
