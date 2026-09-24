package com.kernelpack.boot

/**
 * 纯 Kotlin 的 LZ4 解码 —— 覆盖内核镜像里实际出现的两种帧格式。
 *
 * 为什么必须自己实现
 * ------------------
 * 6.1.x 等内核的 `boot.img` 内核段常用 **lz4_legacy** 压缩（魔数 `02 21 4c 18`），
 * 而 Android 平台没有公开的 LZ4 解码 API，[BootImageParser] 原来只实现了 gzip，
 * 结果就是：**在压缩字节流里搜 `Linux version` 必然搜不到**，整台设备用不了。
 *
 * 两种帧格式的区别（关键）
 * ------------------------
 * ```
 * legacy : [4B 魔数 0x184C2102] 然后重复 [u32 块长度][块数据]      ← 无帧头、无校验
 * frame  : [4B 魔数 0x184D2204][FLG][BD][可选 ContentSize/DictID][HC] 然后 [u32 块长|标志][块数据]…[u32 0]
 * ```
 * legacy 每个块的解压上限是 **8 MB**（内核压缩时按 8 MB 分块）。
 *
 * [已实测确认] 内核 `-l` 产生的 legacy 流，**块之间相互独立**：
 * 对本机真实 boot.img（6.1.145，5 个块、共 36,952,576 字节）逐块统计，
 * 没有任何一次匹配的 offset 越过"本块已产出"范围，因此不需要跨块 64KB 字典。
 * 这条如果判错，每块前 64KB 会**静默解出错误数据**（不报错，只出错）。
 *
 * [风险] 本解码器与 Python 侧实现（`ghostlock5x/gl5x/bootimg/decompress.py`）
 * 语义逐行对齐，并由 `Lz4Test` 用真实镜像数据交叉验证；改这里必须同步改那边。
 */
object Lz4 {

    /** legacy 帧魔数（小端 0x184C2102）。 */
    val MAGIC_LEGACY = byteArrayOf(0x02, 0x21, 0x4c, 0x18)

    /** 标准 LZ4 Frame 魔数（小端 0x184D2204）。 */
    val MAGIC_FRAME = byteArrayOf(0x04, 0x22, 0x4d, 0x18)

    /** legacy 单块解压上限：内核按 8 MB 分块压缩。 */
    const val LEGACY_BLOCK_MAX = 8 shl 20

    /** 解压结果总量上限（内核 Image 量级，防御性）。 */
    const val DEFAULT_MAX_OUTPUT = 512 shl 20

    fun isLegacy(data: ByteArray, offset: Int): Boolean = matches(data, offset, MAGIC_LEGACY)

    fun isFrame(data: ByteArray, offset: Int): Boolean = matches(data, offset, MAGIC_FRAME)

    /**
     * 解码 legacy 帧：[4B 魔数] 后接若干 `[u32 块长度][块数据]`。
     *
     * @return 解码结果；数据不合法时返回 null（**不抛异常**，由调用方转成可读原因）
     */
    fun decompressLegacy(
        data: ByteArray,
        offset: Int,
        length: Int,
        maxOutput: Int = DEFAULT_MAX_OUTPUT,
    ): ByteArray? {
        if (!isLegacy(data, offset)) return null
        val sink = Sink(maxOutput)
        var pos = offset + 4
        val end = offset + length
        while (pos + 4 <= end) {
            val size = u32(data, pos)
            pos += 4
            if (size <= 0L || pos + size > end) break
            val block = decompressBlock(data, pos, size.toInt(), LEGACY_BLOCK_MAX, maxOutput - sink.length)
                ?: return null
            if (!sink.put(block, 0, block.size)) return null
            pos += size.toInt()
        }
        return if (sink.length > 0) sink.toByteArray() else null
    }

    /**
     * 解码标准 LZ4 Frame：解析 FLG/BD（以及可选的 ContentSize / DictID）后逐块解压。
     * 未压缩块（块长度最高位为 1）与 EndMark（长度 0）都按规范处理。
     */
    fun decompressFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        maxOutput: Int = DEFAULT_MAX_OUTPUT,
    ): ByteArray? {
        if (!isFrame(data, offset)) return null
        val end = offset + length
        if (offset + 7 > end) return null
        val flg = data[offset + 4].toInt() and 0xFF
        var pos = offset + 6                       // 跳过魔数(4) + FLG(1) + BD(1)
        val contentSizePresent = (flg and 0x08) != 0
        val dictIdPresent = (flg and 0x01) != 0
        val blockChecksum = (flg and 0x10) != 0
        if (contentSizePresent) pos += 8
        if (dictIdPresent) pos += 4
        pos += 1                                   // HC 校验字节

        val sink = Sink(maxOutput)
        while (pos + 4 <= end) {
            val raw = u32(data, pos)
            pos += 4
            if (raw == 0L) break                   // EndMark
            val uncompressed = (raw and 0x80000000L) != 0L
            val size = (raw and 0x7FFFFFFFL).toInt()
            if (size <= 0 || pos + size > end) break
            val out = if (uncompressed) {
                data.copyOfRange(pos, pos + size)
            } else {
                decompressBlock(data, pos, size, 0, maxOutput - sink.length) ?: return null
            }
            if (!sink.put(out, 0, out.size)) return null
            pos += size
            if (blockChecksum) pos += 4            // 块校验和，跳过不校验
        }
        return if (sink.length > 0) sink.toByteArray() else null
    }

    /**
     * 解码**单个 LZ4 block**（无帧头）。
     *
     * 格式：若干 sequence，每个 sequence =
     * ```
     * token(1B: 高 4 位=字面量长度, 低 4 位=匹配长度-4)
     * [字面量长度扩展字节…（每 255 继续）] 字面量字节
     * [偏移(2B 小端)] [匹配长度扩展字节…]
     * ```
     * 匹配**可能重叠**（offset < 匹配长度），必须逐字节复制 —— 用整块 memcpy 会出错。
     *
     * @param expectedSize >0 时解到该长度即停（legacy 的分块提示）；0 表示解到输入耗尽
     */
    fun decompressBlock(
        src: ByteArray,
        offset: Int,
        length: Int,
        expectedSize: Int = 0,
        maxOutput: Int = DEFAULT_MAX_OUTPUT,
    ): ByteArray? {
        val end = offset + length
        if (end > src.size) return null
        val sink = Sink(maxOutput)
        var i = offset
        while (i < end) {
            val token = src[i].toInt() and 0xFF
            i++
            var litLen = token ushr 4
            if (litLen == 15) {
                while (i < end) {
                    val b = src[i].toInt() and 0xFF
                    i++
                    litLen += b
                    if (b != 255) break
                }
            }
            if (litLen > 0) {
                // 输入被截断时只取可用的部分（与 Python 侧行为一致，便于用前缀向量交叉验证）
                val avail = if (i + litLen > end) end - i else litLen
                if (avail > 0 && !sink.put(src, i, avail)) return null
                i += litLen
            }
            if (i >= end) break                    // 最后一个 sequence 只有字面量
            if (i + 2 > end) return null           // 偏移被截断
            val matchOffset = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            if (matchOffset == 0) return null
            var matchLen = token and 0x0F
            if (matchLen == 15) {
                while (i < end) {
                    val b = src[i].toInt() and 0xFF
                    i++
                    matchLen += b
                    if (b != 255) break
                }
            }
            matchLen += 4
            if (!sink.copyMatch(matchOffset, matchLen)) return null
            if (expectedSize > 0 && sink.length >= expectedSize) break
        }
        return sink.toByteArray()
    }

    // ---- 内部工具 --------------------------------------------------------

    /** 可增长输出缓冲，支持**随机访问历史字节**（重叠复制的必要能力）。 */
    private class Sink(private val max: Int) {
        private var buf = ByteArray(1 shl 16)
        var length = 0
            private set

        fun ensure(extra: Int): Boolean {
            if (length + extra > max) return false
            if (length + extra > buf.size) {
                var n = buf.size
                val need = length + extra
                while (n < need) n = if (n > max / 2) max else n * 2
                buf = buf.copyOf(n)
            }
            return true
        }

        fun put(src: ByteArray, off: Int, n: Int): Boolean {
            if (n <= 0) return true
            if (!ensure(n)) return false
            System.arraycopy(src, off, buf, length, n)
            length += n
            return true
        }

        /** 重叠复制：offset 可以小于 n（此时源与目标重叠，逐字节推进才对）。 */
        fun copyMatch(offset: Int, n: Int): Boolean {
            if (!ensure(n)) return false
            val start = length - offset
            if (start < 0) return false
            for (k in 0 until n) {
                buf[length] = buf[start + k]
                length++
            }
            return true
        }

        fun toByteArray(): ByteArray = buf.copyOf(length)
    }

    private fun matches(data: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (offset < 0 || offset + magic.size > data.size) return false
        for (i in magic.indices) if (data[offset + i] != magic[i]) return false
        return true
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return -1
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        return v
    }
}
