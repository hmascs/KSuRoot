package com.kernelpack

import com.kernelpack.resolve.Strategy
import com.kernelpack.resolve.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 符号**回退链**的守卫。
 *
 * kallsyms 里的符号名会**跨内核版本改名**，而载荷只认地址 ——
 * 于是"名字对不上"会直接表现成整档适配失败。这里把已经实证过的
 * 几条回退链钉死，防止有人把回退名删掉又退回只认一个名字。
 *
 * 每条回退都注明了实证来源，不是猜的。
 */
class SymbolCatalogTest {

    private fun spec(key: String) =
        SymbolCatalog.NEO11_OFFSETS.first { it.key == key }

    private fun candidatesOf(key: String): List<String> =
        spec(key).strategies.flatMap {
            when (it) {
                is Strategy.Symbol -> it.names
                is Strategy.SymbolPlus -> it.base
                is Strategy.FopsSlot -> it.fops + it.fallback
                is Strategy.ImageU32 -> it.base
                is Strategy.CtlTableData -> it.dataSymbols
            }
        }

    @Test
    fun `COPY_SPLICE_READ 必须有 6_1 的回退名`() {
        // 实证：copy_splice_read 是 Linux 6.5 才有的；
        // 厂商 6.1（OPPO 6.1.75）没有，上游 GKI 6.1（Pixel 6.1.145）backport 了。
        val c = candidatesOf("COPY_SPLICE_READ")
        assertTrue("首选应是 copy_splice_read", c.contains("copy_splice_read"))
        assertTrue(
            "6.1 厂商内核只认 generic_file_splice_read，回退名不能删",
            c.contains("generic_file_splice_read"),
        )
    }

    @Test
    fun `ASHMEM_MISC_FOPS 必须认两个时期的符号名`() {
        // 实证：在 6.1.145 的解压内核里读初值，
        // ashmem_miscs+0x10 == &ashmem_fops，与 ashmem_misc+0x10 同一布局。
        val c = candidatesOf("ASHMEM_MISC_FOPS")
        assertTrue("厂商 6.1 叫 ashmem_misc", c.contains("ashmem_misc"))
        assertTrue("上游 GKI 6.1 叫 ashmem_miscs，不能只认单数", c.contains("ashmem_miscs"))
    }

    @Test
    fun `这两条只是回退、不是降级为可选`() {
        // 有回退 ≠ 可以缺席：解析不出来仍然必须阻断打包。
        assertTrue("COPY_SPLICE_READ 仍属必需键", spec("COPY_SPLICE_READ").required)
        assertTrue("ASHMEM_MISC_FOPS 仍属必需键", spec("ASHMEM_MISC_FOPS").required)
        assertFalse(SymbolCatalog.OPTIONAL_KEYS.contains("COPY_SPLICE_READ"))
        assertFalse(SymbolCatalog.OPTIONAL_KEYS.contains("ASHMEM_MISC_FOPS"))
    }

    @Test
    fun `每条策略的候选名单都不许为空`() {
        // 空名单 = 这条策略永远解析不出来，等于悄悄少改一个符号。
        for (s in SymbolCatalog.NEO11_OFFSETS) {
            for (st in s.strategies) {
                val n = when (st) {
                    is Strategy.Symbol -> st.names
                    is Strategy.SymbolPlus -> st.base
                    is Strategy.FopsSlot -> st.fops
                    is Strategy.ImageU32 -> st.base
                    is Strategy.CtlTableData -> st.dataSymbols
                }
                assertTrue("${s.key} 的策略候选名单为空", n.isNotEmpty())
            }
        }
    }

    @Test
    fun `可选键集合与该目录里的声明一致`() {
        val declared = SymbolCatalog.NEO11_OFFSETS.filterNot { it.required }.map { it.key }.toSet()
        assertEquals("OPTIONAL_KEYS 必须从 required 推导，不能另立一份", declared, SymbolCatalog.OPTIONAL_KEYS)
    }
}
