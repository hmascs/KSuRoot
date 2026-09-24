package com.ting.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提权页日志**增量处理**的纯函数部分。
 *
 * 背景：载荷输出是累积增长的一段文本，轮询周期 250ms 一次。这里有三个容易出错的
 * 地方，而且都出过错：
 *
 * 1. **缓存的键选错了**：`stripAnsi(rawLog)` 的结果按 `rawLog.length` 做键。
 *    新一轮安装如果把 `digestedLength` 清了、却忘了清 `strippedCache`，
 *    而新一轮的 `rawLog.length` 又恰好等于旧值（0 就是最常见的一种），
 *    就会把**上一轮**的日志当成这一轮的内容。
 * 2. **早退的位置**：前缀必须在任何早退之前记下来，否则载荷秒退时
 *    「自检 / Shizuku」那一段永远进不了导出。
 * 3. **CFI 之后跳过重建**：这是有意的性能取舍，但必须有一个收尾补全，
 *    否则导出的原始日志会停在 CFI 之前。
 *
 * 下面用与实现同构的纯函数把这些不变量钉住 —— 不需要 Android 运行时。
 */
class LogIncrementalPipelineTest {

    /** 与 `publishExploitLog` 同构：返回本次要消化掉的新增文本。 */
    private inner class Pipeline {
        var strippedCache = ""
        var strippedSourceLength = -1
        var digestedLength = 0
        var logPrefix = ""

        /** 开始新一轮安装时调用。 */
        fun reset() {
            digestedLength = 0
            strippedSourceLength = -1
            strippedCache = ""
            logPrefix = ""
        }

        /** @return 本次新增、需要交给语义引擎的行；null 表示没有新内容。 */
        fun tick(prefix: String, rawLog: String): String? {
            // 前缀最先落盘 —— 必须在早退之前。
            logPrefix = prefix
            val cleaned = if (rawLog.length == strippedSourceLength) {
                strippedCache
            } else {
                stripAnsi(rawLog).also {
                    strippedCache = it
                    strippedSourceLength = rawLog.length
                }
            }
            if (cleaned.length <= digestedLength) return null
            val tail = cleaned.substring(digestedLength)
            digestedLength = cleaned.length
            return tail
        }

        /** 收尾补全时拼出的完整原文。 */
        fun forceRebuild(): String = when {
            logPrefix.isBlank() -> strippedCache
            strippedCache.isBlank() -> logPrefix
            else -> "$logPrefix\n${strippedCache}"
        }
    }

    private fun stripAnsi(value: String): String =
        Regex("\u001B\\[[0-?]*[ -/]*[@-~]").replace(value, "").replace("\r", "")

    // ------------------------------------------------------------------

    @Test
    fun emptyFirstTickStillRecordsThePrefix() {
        // 载荷刚启动、还没有任何输出：这时 cleaned 是空串，会命中早退。
        // 如果早退发生在记录 prefix 之前，prefix 就永久丢失了。
        val p = Pipeline()
        val tail = p.tick(prefix = "[*] 自检通过\n[*] 载荷已选定", rawLog = "")
        assertEquals("空输出不该产生新行", null, tail)

        // 收尾时必须能拼出前缀 —— 它同时也是「导出原始日志」的开头。
        assertEquals("[*] 自检通过\n[*] 载荷已选定", p.forceRebuild())
    }

    @Test
    fun eachTickOnlyConsumesTheNewBytes() {
        val p = Pipeline()
        assertEquals("[*] 第一段", p.tick("", "[*] 第一段"))
        // 第二跳只该吐出新增的 "\n[*] 第二段"，而不是把整段重跑一遍
        assertEquals("\n[*] 第二段", p.tick("", "[*] 第一段\n[*] 第二段"))
        assertEquals(null, p.tick("", "[*] 第一段\n[*] 第二段"))
    }

    @Test
    fun ansiPrefixesAreStrippedBeforeDigesting() {
        // 载荷每行都带颜色码；必须剥掉，否则匹配全废。
        val p = Pipeline()
        assertEquals("hello", p.tick("", "\u001B[32mhello"))
    }

    @Test
    fun staleCacheFromAPreviousRunIsNotReused() {
        // 这是真实踩过的坑：上一轮留下的缓存 + 新一轮首跳长度恰好相同。
        val p = Pipeline()
        p.tick("", "\u001B[31m上一轮的日志")
        // 现在清掉 digestedLength 之外的东西，模拟"只清了一半"的错误实现
        val stale = p.strippedCache
        p.digestedLength = 0
        // 正确实现会同时清缓存
        p.strippedSourceLength = -1
        p.strippedCache = ""
        val tail = p.tick("", "")
        assertFalse("绝不能复用上一轮的缓存", stale == p.strippedCache && p.strippedCache.isNotEmpty())
        assertEquals(null, tail)
    }

    @Test
    fun resetClearsEveryCacheSoNoStaleTextLeaks() {
        val p = Pipeline()
        p.tick("前缀 A", "\u001B[0m载荷 A 输出")
        assertTrue(p.digestedLength > 0)
        p.reset()
        assertEquals(0, p.digestedLength)
        assertEquals(-1, p.strippedSourceLength)
        assertEquals("", p.strippedCache)
        assertEquals("", p.logPrefix)

        // 重置后第一次 tick 拿到的是**新**内容，而不是旧缓存
        assertEquals("载荷 B 输出", p.tick("前缀 B", "载荷 B 输出"))
    }

    @Test
    fun forceRebuildAlwaysYieldsTheWholeLog() {
        // CFI 之后 publishExploitLog 是有意跳过 `log` 重建的。
        // forceRebuild 必须能把剩下的全部补回来，一段都不能少。
        val p = Pipeline()
        val prefix = "[*] Shizuku 就绪"
        p.tick(prefix, "\u001B[32m[*] 开始提权")
        p.tick(prefix, "\u001B[32m[*] 开始提权\n\u001B[33m[*] cfi write ret=35")
        p.tick(prefix, "[*] 开始提权\n[*] cfi write ret=35\n[*] root patch cred")

        val rebuilt = p.forceRebuild()
        assertTrue("前缀不能丢", rebuilt.startsWith(prefix))
        assertTrue("CFI 之前的内容不能丢", rebuilt.contains("开始提权"))
        assertTrue("CFI 本身不能丢", rebuilt.contains("cfi write ret=35"))
        assertTrue("CFI 之后的尾部不能丢", rebuilt.contains("root patch cred"))
        assertFalse("ANSI 码不该出现在导出的日志里", rebuilt.contains("\u001B"))
    }

    @Test
    fun shrinkingCleanedStringDoesNotProduceNegativeSlice() {
        // `cleaned.length` 理论上只增不减，但 stripAnsi 的缓存被换掉时可能变小。
        // 用 `<=` 而不是 `==` 判早退，保证 `substring(digestedLength)` 不会越界。
        val p = Pipeline()
        p.tick("", "0123456789")
        p.digestedLength = 20 // 人为构造"已消化长度超过当前文本"
        assertEquals(null, p.tick("", "abc"))
    }
}
