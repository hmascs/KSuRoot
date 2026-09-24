package com.kernelpack.boot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * 内核镜像解析失败的**具体**原因。
 *
 * 为什么要有这个类型
 * ------------------
 * 原来所有失败都汇成一句「镜像里找不到 `Linux version` 字符串，可能不是内核 Image」，
 * 但这三种情况的处理方式完全不同：
 * ```
 *   ① 压缩格式没解（lz4_legacy 等）   → 补解压实现就能修
 *   ② 解压了但段偏移切错（MTK 私有头）→ 修段计算
 *   ③ 真的不是内核（vendor_boot/init_boot）→ 换文件
 * ```
 * 混成一句话的后果：用户拿着 6.1.145 的 boot.img 反复重试，而真正的原因
 * （内核是 lz4_legacy 压缩的）根本没被说出来。
 */
sealed class ParseError {
    /** 面向用户/日志的一句话说明。 */
    abstract val readable: String

    /** ① 压缩格式认出来了，但本工程没有对应解码器（当前只有 zstd 会走到这）。 */
    data class UnsupportedCompression(val format: String, val magicHex: String) : ParseError() {
        override val readable: String
            get() = "内核段是 $format 压缩（魔数 $magicHex），本版本还不支持解压该格式。" +
                "请改用对应工具的镜像，或等待支持后重试。"
    }

    /** ② 格式认出来了、解码器也有，但数据解不开（截断/损坏/不是真正的该格式）。 */
    data class DecompressFailed(val format: String, val reason: String) : ParseError() {
        override val readable: String get() = "$format 解压失败：$reason"
    }

    /** ③ v3/v4 头部字段自相矛盾。 */
    data class HeaderVersionUnknown(val version: Int, val headerSize: Long) : ParseError() {
        override val readable: String
            get() = "boot 头异常：header_version=$version、header_size=$headerSize " +
                "既不是 v3(1580) 也不是 v4(1584)，无法确定内核段起点。"
    }

    /** ④ 段区间越界（典型：MTK 私有头导致整体偏移了 512 字节）。 */
    data class SegmentOutOfRange(val kernelOffset: Long, val kernelSize: Long, val fileSize: Int) : ParseError() {
        override val readable: String
            get() = "内核区间 [0x${java.lang.Long.toHexString(kernelOffset)}, " +
                "0x${java.lang.Long.toHexString(kernelOffset + kernelSize)}) 超出文件末尾 " +
                "（${fileSize} 字节）—— 段偏移算错了（MTK 私有头？头部版本判错？）"
        }

    /** ⑤ 这个容器本来就不放内核。 */
    data class NoKernelSegment(val container: String, val hint: String) : ParseError() {
        override val readable: String get() = "$container 里没有内核段：$hint"
    }

    /** ⑥ 拿到内核段了，但里面找不到 `Linux version`。 */
    data class BannerNotFound(val decompressed: Boolean, val format: String, val imageSize: Int) : ParseError() {
        override val readable: String
            get() = if (!decompressed) {
                "内核段（$imageSize 字节，识别为 $format）里找不到 `Linux version`：" +
                    "**数据仍是压缩状态**，字符串搜索在压缩流上不可能命中。"
            } else {
                "解压后（$imageSize 字节）仍找不到 `Linux version` banner，可能段偏移切错或镜像损坏。"
            }
    }

    /** ⑦ 既不是 arm64 Image，也不带任何已知压缩魔数。 */
    object NotKernelImage : ParseError() {
        override val readable: String
            get() = "内核段既不是 arm64 Image（偏移 0x38 处无 `ARMd`），" +
                "也不带任何已知压缩魔数（gzip / lz4 / zstd / xz / bzip2）。"
    }
}

/** 一次解压尝试的结果。 */
sealed class DecompressOutcome {
    data class Ok(val bytes: ByteArray, val format: String) : DecompressOutcome()
    data class Unsupported(val format: String, val magicHex: String) : DecompressOutcome()
    data class Failed(val format: String, val reason: String) : DecompressOutcome()
    object NotCompressed : DecompressOutcome()
}

/**
 * 多格式内核解压器。
 *
 * 支持矩阵（**实测确认过的组合标 ✓**）：
 * ```
 *   gzip        ✓ 6.6.89 的 boot.img（GKI 常见 Image.gz）
 *   lz4_legacy  ✓ 6.1.145 的 boot.img（5 块 / 36,952,576 字节，逐块独立可解）
 *   lz4 (frame) 支持，未遇到真实样本
 *   zstd/xz/bzip2  识别但**不支持** —— 明确报 UnsupportedCompression，不静默失败
 *                  （本工程没有这几个解压库，不为此引入新依赖）
 * ```
 */
object KernelDecompressor {

    enum class Format(val label: String) {
        NONE("未压缩"),
        GZIP("gzip"),
        LZ4_LEGACY("lz4_legacy"),
        LZ4_FRAME("lz4"),
        ZSTD("zstd"),
        XZ("xz"),
        BZIP2("bzip2"),
        UNKNOWN("未知"),
    }

    val MAGIC_ZSTD = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())
    val MAGIC_XZ = byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00)
    val MAGIC_BZIP2 = byteArrayOf(0x42, 0x5a, 0x68)

    /** 按魔数识别压缩格式。识别不出时返回 [Format.NONE]（表示"看起来没压缩"）。 */
    fun detect(data: ByteArray, offset: Int): Format = when {
        offset + 3 <= data.size && (data[offset].toInt() and 0xFF) == 0x1F &&
            (data[offset + 1].toInt() and 0xFF) == 0x8B -> Format.GZIP
        Lz4.isLegacy(data, offset) -> Format.LZ4_LEGACY
        Lz4.isFrame(data, offset) -> Format.LZ4_FRAME
        startsWith(data, offset, MAGIC_ZSTD) -> Format.ZSTD
        startsWith(data, offset, MAGIC_XZ) -> Format.XZ
        startsWith(data, offset, MAGIC_BZIP2) -> Format.BZIP2
        else -> Format.NONE
    }

    /** 取前几字节的十六进制，用于错误信息里给出证据。 */
    fun magicHex(data: ByteArray, offset: Int, n: Int = 4): String {
        if (offset >= data.size) return "<越界>"
        val end = minOf(offset + n, data.size)
        return (offset until end).joinToString(" ") { "%02x".format(data[it].toInt() and 0xFF) }
    }

    /**
     * 解压并给出**具体结果**（成功 / 不支持 / 失败 / 没压缩）。
     *
     * [风险] 绝不"猜格式"：只按魔数分派。用错误的算法硬解会产出看起来像内核的
     * 垃圾数据，进而算出错误偏移写坏内核 —— 宁可明确报 UnsupportedCompression。
     */
    fun decompress(
        data: ByteArray,
        offset: Int,
        length: Int,
        maxOutput: Int = Lz4.DEFAULT_MAX_OUTPUT,
    ): DecompressOutcome {
        val format = detect(data, offset)
        if (format == Format.NONE) return DecompressOutcome.NotCompressed
        return try {
            val out = when (format) {
                Format.GZIP -> gunzip(data, offset, length, maxOutput)
                Format.LZ4_LEGACY -> Lz4.decompressLegacy(data, offset, length, maxOutput)
                Format.LZ4_FRAME -> Lz4.decompressFrame(data, offset, length, maxOutput)
                // xz / bzip2 / zstd 都走 Unsupported 分支：
                // 本工程**没有** xz / commons-compress / zstd 依赖，凭空加依赖会改变
                // 工程的依赖面（还要联网下载），所以这里选择"如实说做不到"而不是硬塞实现。
                Format.XZ, Format.BZIP2, Format.ZSTD -> null
                else -> null
            }
            when {
                format == Format.ZSTD || format == Format.XZ || format == Format.BZIP2 ->
                    DecompressOutcome.Unsupported(format.label, magicHex(data, offset))
                out == null || out.isEmpty() -> DecompressOutcome.Failed(format.label, "解码器返回空结果（数据截断或损坏？）")
                else -> DecompressOutcome.Ok(out, format.label)
            }
        } catch (t: Throwable) {
            DecompressOutcome.Failed(format.label, t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * 给 [BootImageParser] 用的默认解压钩子：按魔数自动选择 decoder。
     *
     * 返回 null 表示"没解开"（可能是不支持的格式、或本来就没压缩），
     * 具体原因由 [BootImageParser] 通过 [decompress] 单独取。
     */
    val default: BootImageParser.Decompressor = BootImageParser.Decompressor { data, offset, length ->
        when (val r = decompress(data, offset, length)) {
            is DecompressOutcome.Ok -> r.bytes
            else -> null
        }
    }

    private fun gunzip(data: ByteArray, offset: Int, length: Int, maxOutput: Int): ByteArray? {
        GZIPInputStream(ByteArrayInputStream(data, offset, length)).use { input ->
            val out = ByteArrayOutputStream(length * 4)
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > maxOutput) return null
            }
            return out.toByteArray()
        }
    }

    private fun startsWith(data: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (offset < 0 || offset + magic.size > data.size) return false
        for (i in magic.indices) if (data[offset + i] != magic[i]) return false
        return true
    }
}
