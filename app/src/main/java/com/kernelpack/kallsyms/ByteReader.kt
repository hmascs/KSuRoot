package com.kernelpack.kallsyms

/**
 * 小端/大端字节读取器 —— `symbols.js` 里 `ByteReader` 的 Kotlin 等价实现。
 *
 * 内部持有 [ByteArray] 的一个**视图**（[offset] + [length]），所有下标都相对于视图起点，
 * 因此解析 boot.img 时可以把内核 Image 段直接当作独立缓冲区处理，不需要复制 100MB 数据。
 *
 * 所有读取都是**无符号**语义：`u8` 返回 0..255 的 Int，`u32` 返回 0..2^32-1 的 Long。
 */
class ByteReader(
    private val data: ByteArray,
    val offset: Int = 0,
    val length: Int = data.size - offset,
) {

    init {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "ByteReader 视图越界: offset=$offset length=$length size=${data.size}"
        }
    }

    /** 底层数组（配合 [offset] 使用）。 */
    fun backing(): ByteArray = data

    fun u8(i: Int): Int {
        bounds(i, 1)
        return data[offset + i].toInt() and 0xff
    }

    fun i8(i: Int): Int {
        bounds(i, 1)
        return data[offset + i].toInt()
    }

    fun u16(i: Int, le: Boolean = true): Int {
        bounds(i, 2)
        val a = data[offset + i].toInt() and 0xff
        val b = data[offset + i + 1].toInt() and 0xff
        return if (le) (b shl 8) or a else (a shl 8) or b
    }

    fun i16(i: Int, le: Boolean = true): Int = u16(i, le).toShort().toInt()

    fun u32(i: Int, le: Boolean = true): Long {
        bounds(i, 4)
        val b0 = (data[offset + i].toInt() and 0xff).toLong()
        val b1 = (data[offset + i + 1].toInt() and 0xff).toLong()
        val b2 = (data[offset + i + 2].toInt() and 0xff).toLong()
        val b3 = (data[offset + i + 3].toInt() and 0xff).toLong()
        return if (le) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    fun i32(i: Int, le: Boolean = true): Int = u32(i, le).toInt()

    /** 64 位读取，返回原始位模式（不解释符号）。 */
    fun u64(i: Int, le: Boolean = true): Long {
        bounds(i, 8)
        var v = 0L
        if (le) {
            for (k in 7 downTo 0) {
                v = (v shl 8) or ((data[offset + i + k].toInt() and 0xff).toLong())
            }
        } else {
            for (k in 0 until 8) {
                v = (v shl 8) or ((data[offset + i + k].toInt() and 0xff).toLong())
            }
        }
        return v
    }

    fun i64(i: Int, le: Boolean = true): Long = u64(i, le)

    /** 复制 `[from, to)` 区间为新的 [ByteArray]（相对视图下标）。 */
    fun slice(from: Int, to: Int): ByteArray {
        val f = from.coerceIn(0, length)
        val t = to.coerceIn(f, length)
        return data.copyOfRange(offset + f, offset + t)
    }

    /**
     * 前向查找字节序列，返回**最左**匹配的相对下标，找不到返回 -1。
     *
     * 用 Boyer-Moore-Horspool 跳过表实现：内核镜像动辄 50~100MB，朴素扫描太慢，
     * 而这里要在大图上反复找 "Linux version " / token 表特征串。
     * 语义与朴素扫描完全一致（都返回最左匹配）。
     */
    fun find(needle: ByteArray, from: Int = 0): Int {
        val n = needle.size
        if (n == 0 || n > length) return -1
        val start = if (from < 0) 0 else from
        if (start + n > length) return -1
        val base = offset

        if (n == 1) {
            val b = needle[0]
            for (i in start until length) if (data[base + i] == b) return i
            return -1
        }

        // 坏字符跳过表
        val skip = IntArray(256) { n }
        for (k in 0 until n - 1) skip[needle[k].toInt() and 0xff] = n - 1 - k

        var i = start
        val last = length - n
        while (i <= last) {
            val c = data[base + i + n - 1]
            if (c == needle[n - 1]) {
                var k = n - 2
                while (k >= 0 && data[base + i + k] == needle[k]) k--
                if (k < 0) return i
            }
            i += skip[c.toInt() and 0xff]
        }
        return -1
    }

    /** 后向查找：在 `[0, before)` 范围内找**最后一次**出现，找不到返回 -1。 */
    fun rfind(needle: ByteArray, before: Int = length): Int {
        val n = needle.size
        if (n == 0) return -1
        val b = if (before > length) length else before
        if (b < n) return -1
        val base = offset
        outer@ for (i in (b - n) downTo 0) {
            for (k in 0 until n) {
                if (data[base + i + k] != needle[k]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun bounds(i: Int, size: Int) {
        if (i < 0 || i + size > length) {
            throw IndexOutOfBoundsException("读取越界: i=$i size=$size length=$length")
        }
    }

    companion object {
        /** 把 Int 序列打包成字节串（便于构造 needle）。 */
        fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

        /** 把 64 位值编码为 `size` 字节的小端/大端字节串（与 JS `encodeUIntN` 等价）。 */
        fun encodeUIntN(value: Long, size: Int, le: Boolean): ByteArray {
            val out = ByteArray(size)
            for (i in 0 until size) {
                val shift = if (le) 8 * i else 8 * (size - 1 - i)
                out[i] = ((value ushr shift) and 0xff).toByte()
            }
            return out
        }
    }
}
