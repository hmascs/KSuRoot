package com.kernelpack.resolve

import com.kernelpack.profile.BaselineRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.12（ashmem 换成 Rust 实现）之后新增的几条约定。
 *
 * 这些不是风格问题 —— 每一条背后都对应一次**静默**的失败，
 * 写在这里是为了让下一个人改坏时立刻红。
 */
class RustAshmemFallbackTest {

    @Test
    fun `七条 ashmem 回退片段都在，且都进得了模糊索引`() {
        val frags = listOf(
            SymbolCatalog.RUST_ASHMEM_FOPS_PTR,
            SymbolCatalog.RUST_ASHMEM_IOCTL,
            SymbolCatalog.RUST_ASHMEM_COMPAT_IOCTL,
            SymbolCatalog.RUST_ASHMEM_MMAP,
            SymbolCatalog.RUST_ASHMEM_OPEN,
            SymbolCatalog.RUST_ASHMEM_RELEASE,
            SymbolCatalog.RUST_ASHMEM_SHOW_FDINFO,
        )
        assertEquals("六种方法 + miscdevice 本体", 7, frags.distinct().size)
        for (f in frags) {
            assertTrue(
                "片段 $f 太短，会被 MIN_FUZZY_LEN 挡掉，模糊匹配根本不会启动",
                f.length >= OffsetResolver.MIN_FUZZY_LEN,
            )
        }
    }

    @Test
    fun `片段互不为子串 —— 否则会互相抢命中`() {
        // `6AshmemE5ioctl` 是 `6AshmemE12compat_ioctl` 的子串吗？不是（长度编码不同）。
        // 但这条必须钉死：包含匹配一旦互相嵌套，唯一性判据就会失效。
        val frags = listOf(
            SymbolCatalog.RUST_ASHMEM_IOCTL,
            SymbolCatalog.RUST_ASHMEM_COMPAT_IOCTL,
            SymbolCatalog.RUST_ASHMEM_MMAP,
            SymbolCatalog.RUST_ASHMEM_OPEN,
            SymbolCatalog.RUST_ASHMEM_RELEASE,
            SymbolCatalog.RUST_ASHMEM_SHOW_FDINFO,
        )
        for (a in frags) for (b in frags) {
            if (a !== b) assertTrue("$b 含 $a，两者会互相抢命中", !b.contains(a))
        }
    }

    @Test
    fun `6_12 档位的 imageBase 必须是载荷编译时的常量`() {
        // 这一条是真出事过的：填成目标内核的运行基址（0xffffffc080000000）之后，
        // `from == to` 让 KIMAGE_TEXT_BASE 判成"无需修改"，
        // 而绝对地址形式的构造点全部漏改 —— 替换数从 39 处掉到 9 处，且毫无报错。
        val p = BaselineRegistry.BASELINE_6_12
        assertEquals(
            "必须等于 载荷构建/targets/baseline-6.12-gki/target.h 里的 KIMAGE_TEXT_BASE",
            0xffffffc008000000uL.toLong(),
            p.imageBase,
        )
    }

    @Test
    fun `6_12 档位不再登记那两个 6_12 上走不通的键`() {
        val keys = BaselineRegistry.BASELINE_6_12.symbolOffsets.keys
        assertTrue(
            "ASHMEM_FOPS：6.12 的表没有符号，已改为运行时从 miscdevice 的 fops 槽读",
            "ASHMEM_FOPS" !in keys,
        )
        assertTrue(
            "SECURITY_HOOK_HEADS：Linux 6.4+ 改成 static_call，6.12 符号表里没有",
            "SECURITY_HOOK_HEADS" !in keys,
        )
    }

    @Test
    fun `6_1 档位不受影响 —— 它的 ASHMEM_FOPS 仍然解析得出`() {
        val keys = BaselineRegistry.BASELINE_6_1.symbolOffsets.keys
        assertTrue("6.1 上 ashmem 还是 C 实现，这个键必须留着", "ASHMEM_FOPS" in keys)
        assertEquals(
            "6.1 的 imageBase 一直是对的，不要被上面那条改动带跑",
            0xffffffc008000000uL.toLong(),
            BaselineRegistry.BASELINE_6_1.imageBase,
        )
    }
}
