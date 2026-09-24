package com.ting.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「并入的厂商载荷」登记表的**结构性不变式**。
 *
 * 这一组用例守的不是功能，而是**别把用户的内核改坏**：
 * [BundledPayloadImported] 是从几十个公开仓库里批量收进来的（83 份），
 * 其中 77 份二进制被 strip 过、读不出内核版本。批量数据最容易出的错就是
 * "某一条悄悄退化成不限内核"，然后它会在自动匹配里被当成万能选项选中 ——
 * 而载荷靠**编译期常量**寻址内核符号，拿错一份就是常量对不上、提权直接失败。
 *
 * 所以这里把三条硬约束钉死：
 * 1. 读不出内核版本 → 必须 `kernelUnknown = true`，且**不参与自动匹配**；
 * 2. 认不出机型的条目 → 关键词必须为空（空词组在 [BundledPayloadCatalog.matchesModel]
 *    里恒为 false），否则会按品牌级别误命中一大片；
 * 3. `library` 与 `sha256` 都必须唯一 —— 重名会让 `byLibrary` 静默覆盖，
 *    同一份内容重复登记则会让界面上出现两条一模一样的选项。
 */
class BundledPayloadImportedTest {

    private val imported = BundledPayloadImported.ALL

    @Test
    fun `批次非空 —— 否则这一组用例全是空转`() {
        // 没有这条断言的话，列表要是被误删干净，下面每条 all{} 都会"通过"。
        assertTrue("并入批次不应为空", imported.size >= 50)
    }

    @Test
    fun `读不出内核版本的条目必须标 kernelUnknown`() {
        val wrong = imported.filter { it.kernelVersion == null && !it.kernelUnknown }
        assertTrue(
            "以下条目没有内核版本却没标 kernelUnknown，会被当成『不限内核』自动匹配：\n" +
                wrong.joinToString("\n") { "  " + it.library },
            wrong.isEmpty(),
        )
    }

    @Test
    fun `标了 kernelUnknown 的条目不得同时给出内核版本`() {
        // 两者同时成立是自相矛盾：闸门按 kernelUnknown 拦截，展示却按 kernelVersion
        // 渲染版本号，会冒出"显示着版本号却不参与匹配"的鬼条目。
        val wrong = imported.filter { it.kernelUnknown && it.kernelVersion != null }
        assertTrue(wrong.joinToString("\n") { "  " + it.library }, wrong.isEmpty())
    }

    @Test
    fun `认不出机型的条目不得只用品牌名当关键词`() {
        // 反例：关键词写成 "oppo"，而 matchesModel 是"任一词组命中即可"，
        // 于是任何一台 OPPO 都会命中它。机型代号（m1q / tokay）是允许的 ——
        // 它精确对应 ro.product.device。
        val brandWords = setOf(
            "oppo", "vivo", "iqoo", "xiaomi", "redmi", "poco",
            "samsung", "google", "pixel", "honor", "realme", "oneplus", "asus", "meizu",
        )
        val bad = imported.filter { entry ->
            entry.model.orEmpty().any { it.trim().lowercase() in brandWords }
        }
        assertTrue(
            "以下条目只用品牌名当关键词，会命中该品牌全部机型：\n" +
                bad.joinToString("\n") { "  " + it.library + " → " + it.model },
            bad.isEmpty(),
        )
    }

    @Test
    fun `library 唯一 —— 重名会让 byLibrary 静默覆盖`() {
        val dup = imported.groupBy { it.library }.filterValues { it.size > 1 }.keys
        assertTrue("重复的 library：$dup", dup.isEmpty())
    }

    @Test
    fun `内容不重复 —— 同一份载荷不该登记两次`() {
        val dup = imported.groupBy { it.sha256 }.filterValues { it.size > 1 }
        assertTrue(
            "以下 sha256 被登记了多次（同内容不同名，界面上会出现两条一样的选项）：\n" +
                dup.entries.joinToString("\n") { "  " + it.key.take(16) + "… → " + it.value.map { e -> e.library } },
            dup.isEmpty(),
        )
    }

    @Test
    fun `并入条目不与手写清单重名`() {
        val importedNames = imported.map { it.library }.toSet()
        val manualNames = BundledPayloadCatalog.ALL
            .filterNot { it.library in importedNames }
            .map { it.library }
            .toSet()
        val overlap = importedNames intersect manualNames
        assertTrue("并入条目与手写条目重名：$overlap", overlap.isEmpty())
    }

    @Test
    fun `每条并入载荷都能按 library 从 ALL 取回`() {
        val missing = imported.filter { BundledPayloadCatalog.byLibrary(it.library) == null }
        assertTrue(missing.joinToString("\n") { "  " + it.library }, missing.isEmpty())
    }

    @Test
    fun `登记项的大小与 sha256 形态合法`() {
        imported.forEach { entry ->
            assertTrue(entry.library + " 大小应为正", entry.size > 0)
            assertEquals(entry.library + " sha256 应为 64 位", 64, entry.sha256.length)
            assertTrue(
                entry.library + " sha256 含非十六进制字符",
                entry.sha256.all { it in "0123456789abcdef" },
            )
            assertTrue(entry.library + " 应以 .so 结尾", entry.library.endsWith(".so"))
        }
    }

    @Test
    fun `认不出机型的条目确实匹配不到任何机型`() {
        // 这些条目关键词为空 —— 但 resolve 还会额外拿 displayName 当关键词组，
        // 所以必须确认「仓库名当显示名」这条路也命中不了真机。
        val samples = listOf(
            "v2463a pd2463 vivo iqoo 13",
            "sm-s928b e3q samsung galaxy s24 ultra",
            "tokay google pixel 9",
            "pja110 oppo",
            "rmx5200 realme",
        )
        val unknown = imported.filter { it.model.isNullOrEmpty() }
        assertTrue("本批次应当存在认不出机型的条目，否则这条用例没意义", unknown.isNotEmpty())
        unknown.forEach { entry ->
            samples.forEach { sample ->
                assertFalse(
                    entry.library + " 声称认不出机型，却命中了『" + sample + "』",
                    BundledPayloadCatalog.matchesModel(listOf(entry.displayName), sample),
                )
            }
        }
    }

    @Test
    fun `byLibrary 可用 —— 正向锚点`() {
        // 锚点必须取自 ALL 本身。最早写的是 `byLibrary("libbs.so")`，
        // 结果失败了 —— 因为 `libbs.so` 是 [BundledPayloadCatalog] 里**单独定义**的
        // 那个 all-in-one 条目，走 `resolve` 第 0 步的特例路径，**并不登记在 ALL 里**。
        // 拿它当锚点等于假设了一件不成立的事。
        val first = BundledPayloadCatalog.ALL.firstOrNull()
        assertNotNull("ALL 不应为空", first)
        assertNotNull(
            "按 library 取不回 ALL 的第一条，说明 byLibrary 与 ALL 脱钩了",
            BundledPayloadCatalog.byLibrary(first!!.library),
        )
    }
}
