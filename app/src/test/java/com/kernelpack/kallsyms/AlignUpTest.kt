package com.kernelpack.kallsyms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `alignUp` 的语义守卫。
 *
 * 这是「6.4+ 内核整档失败」的直接病灶：原来的写法
 * `position += (-position) % alignSize` 依赖 Python 的取模语义，
 * 而 Kotlin/Java 的 `%` 对负数返回**负值** —— 向上对齐变成了往回退。
 */
class AlignUpTest {

    @Test
    fun `已对齐时原地不动`() {
        assertEquals(0x144d108, alignUp(0x144d108, 8))
        assertEquals(64, alignUp(64, 8))
        assertEquals(0, alignUp(0, 8))
    }

    @Test
    fun `未对齐时向上取整`() {
        assertEquals(16, alignUp(9, 8))
        assertEquals(16, alignUp(15, 8))
        assertEquals(0x14b4c88, alignUp(0x14b4c84, 8))
    }

    @Test
    fun `这是 6_6_30 上真实爆掉的那一步`() {
        // tokenIndexEnd(0x144d108) + numSymbols*4(0x67b7c) = 0x14b4c84
        val afterOffsets = 0x144d108 + 106207 * 4
        assertEquals(0x14b4c84, afterOffsets)
        assertEquals("必须向上到 0x14b4c88", 0x14b4c88, alignUp(afterOffsets, 8))
    }

    @Test
    fun `旧的写法会往回退 —— 钉住这个错误`() {
        // 旧写法：x += (-x) % a。Kotlin 里 (-0x14b4c84) % 8 == -4，
        // 于是落到 0x14b4c80，整整偏掉 8 字节，偏移表读成垃圾。
        val afterOffsets = 0x14b4c84
        val wrong = afterOffsets + (-afterOffsets) % 8
        assertEquals("旧算式确实往回退了 4 字节", 0x14b4c80, wrong)
        assertTrue("新写法必须比旧写法大", alignUp(afterOffsets, 8) > wrong)
    }

    @Test
    fun `align 为 1 或更小时是恒等`() {
        assertEquals(7, alignUp(7, 1))
        assertEquals(7, alignUp(7, 0))
        assertEquals(7, alignUp(7, -3))
    }
}
