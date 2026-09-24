package com.kernelpack.boot

import com.kernelpack.model.BootImageInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Android boot.img 解析器。
 *
 * 移植自 `symbols.js` 的 `parseAndroidBootImage()`，并做了三处增强：
 *  1. 明确识别 `VNDRBOOT`（vendor_boot）并给出可读错误，而不是当成内核乱解析；
 *  2. 支持压缩内核（`Image.gz`，GKI 常见）——通过 [Decompressor] 插件式解压；
 *  3. 支持"裸 Image / vmlinux"直接输入（含 UEFI PE stub 形式）。
 *
 * boot image header 布局（`system/tools/mkbootimg/include/bootimg/bootimg.h`）：
 * ```
 * v0/v1/v2: magic[8] kernel_size(0x08) kernel_addr(0x0c) ramdisk_size(0x10)
 *           ramdisk_addr(0x14) second_size(0x18) second_addr(0x1c) tags_addr(0x20)
 *           page_size(0x24) header_version(0x28) os_version(0x2c) ...
 * v3/v4:    magic[8] kernel_size(0x08) ramdisk_size(0x0c) os_version(0x10)
 *           header_size(0x14) reserved[4](0x18) header_version(0x28) cmdline(0x2c)
 * ```
 * v3+ 的 `page_size` 固定 4096，内核紧跟在按 4096 对齐后的 header 之后。
 */
object BootImageParser {

    private val MAGIC_BOOT = "ANDROID!".toByteArray(Charsets.US_ASCII)
    private val MAGIC_VENDOR = "VNDRBOOT".toByteArray(Charsets.US_ASCII)

    /** 内核压缩格式识别 + 解压的可插拔钩子。 */
    fun interface Decompressor {
        /** 无法识别/无法解压时返回 null。 */
        fun decompress(data: ByteArray, offset: Int, length: Int): ByteArray?
    }

    /** 只处理 gzip（历史实现，保留兼容）。新代码请用 [KernelDecompressor.default]。 */
    val gzipDecompressor = Decompressor { data, offset, length ->
        if (isGzip(data, offset)) {
            try {
                GZIPInputStream(ByteArrayInputStream(data, offset, length)).use { input ->
                    val out = ByteArrayOutputStream(length * 4)
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        // 防御：解压结果不可能超过 512MB（内核 Image 量级）
                        if (out.size() > 512 shl 20) return@Decompressor null
                    }
                    out.toByteArray()
                }
            } catch (_: Throwable) {
                null
            }
        } else null
    }

    /**
     * 解析结果。
     *
     * [diagnosis] 是**失败的具体原因**（人类可读）。为空表示没出现问题；
     * 有值时调用方应把它显示给用户，而不是继续用笼统的"找不到 Linux version"。
     */
    class Result(
        val image: ByteArray,
        val info: BootImageInfo,
        val diagnosis: String? = null,
    )

    /**
     * 解析输入文件，返回内核 Image 字节。
     *
     * @param fileBytes 完整的 boot.img / Image / vmlinux 内容
     * @param decompressor 压缩内核解压钩子，null 表示不解压
     */
    @JvmOverloads
    @JvmStatic
    fun parse(fileBytes: ByteArray, decompressor: Decompressor? = KernelDecompressor.default): Result {
        require(fileBytes.size >= 64) { "输入文件过小（${fileBytes.size} 字节），不是有效的 boot.img / Image" }

        return when {
            startsWith(fileBytes, 0, MAGIC_BOOT) -> parseAndroidBoot(fileBytes, decompressor)
            startsWith(fileBytes, 0, MAGIC_VENDOR) -> {
                // 格式识别层会把"内核在哪儿"说清楚，比笼统报错有用得多。
                val info = BootFormatDetector.detect(fileBytes)
                throw IllegalArgumentException(
                    listOfNotNull(
                        info.summary,
                        info.evidence.joinToString("；").takeIf { it.isNotBlank() },
                        info.advice,
                    ).joinToString("\n"),
                )
            }
            else -> parseRawImage(fileBytes, decompressor)
        }
    }

    /**
     * 只做**格式识别**，不解析内核。
     *
     * 给 UI 用：用户选完文件就能立刻看到"这是 vendor_boot.img，内核不在这里"，
     * 而不是等构建跑一半才被拒。
     */
    @JvmStatic
    fun detectFormat(fileBytes: ByteArray): BootFormatDetector.Info =
        BootFormatDetector.detect(fileBytes)

    /** 直接解析（跳过 boot 头）。 */
    private fun parseRawImage(fileBytes: ByteArray, decompressor: Decompressor?): Result {
        var image = fileBytes
        var decompressed = false
        if (decompressor != null) {
            val out = decompressor.decompress(fileBytes, 0, fileBytes.size)
            if (out != null) {
                image = out
                decompressed = true
            }
        }
        val arm64 = isArm64Image(image, 0)
        val info = BootImageInfo(
            container = BootImageInfo.Container.RAW_IMAGE,
            headerVersion = null,
            headerSize = null,
            pageSize = null,
            kernelOffset = 0,
            declaredKernelSize = fileBytes.size.toLong(),
            imageLength = image.size,
            decompressed = decompressed,
            arm64Image = arm64,
        )
        return Result(image, info)
    }

    private fun parseAndroidBoot(fileBytes: ByteArray, decompressor: Decompressor?): Result {
        val kernelSize = u32(fileBytes, 0x08)
        val headerSize = u32(fileBytes, 0x14)
        val headerVersion = (u32(fileBytes, 0x28) and 0xffffL).toInt()
        val pageSize = if (headerVersion >= 3) 4096 else (u32(fileBytes, 0x24).toInt().takeIf { it != 0 } ?: 4096)

        // v3/v4：内核在 header 之后、按 page_size 对齐；v0..v2：固定在第一个 page 之后。
        val kernelOffsetLong = if (headerVersion >= 3) alignUp(headerSize, pageSize.toLong()) else pageSize.toLong()

        if (kernelOffsetLong > Int.MAX_VALUE) {
            throw IllegalArgumentException("boot 头异常：kernel_offset=${java.lang.Long.toHexString(kernelOffsetLong)}")
        }
        val kernelOffset = kernelOffsetLong.toInt()

        if (kernelSize <= 0) {
            throw IllegalArgumentException("boot 头里 kernel_size=0，该 boot.img 不含内核（可能是 init_boot.img，只放 ramdisk）")
        }
        if (kernelOffset.toLong() + kernelSize > fileBytes.size) {
            throw IllegalArgumentException(
                "boot.img 内核区间 [0x${java.lang.Long.toHexString(kernelOffsetLong)}, " +
                    "0x${java.lang.Long.toHexString(kernelOffsetLong + kernelSize)}) 超出文件末尾 " +
                    "(0x${java.lang.Long.toHexString(fileBytes.size.toLong())})"
            )
        }

        var image = fileBytes.copyOfRange(kernelOffset, kernelOffset + kernelSize.toInt())
        var decompressed = false
        var diagnosis: String? = null

        // arm64 Image 头：text_offset@0x08, image_size@0x10, flags@0x18, magic@0x38="ARMd"
        if (isArm64Image(image, 0)) {
            val arm64ImageSize = u64(image, 0x10)
            if (arm64ImageSize > 0 && java.lang.Long.compareUnsigned(arm64ImageSize, kernelSize) <= 0) {
                image = image.copyOfRange(0, arm64ImageSize.toInt())
            }
        } else if (decompressor != null) {
            val (out, why) = tryDecompress(image, decompressor)
            if (out != null) {
                image = out
                decompressed = true
                if (isArm64Image(image, 0)) {
                    val arm64ImageSize = u64(image, 0x10)
                    if (arm64ImageSize > 0 && java.lang.Long.compareUnsigned(arm64ImageSize, image.size.toLong()) <= 0) {
                        image = image.copyOfRange(0, arm64ImageSize.toInt())
                    }
                }
            } else {
                diagnosis = why ?: if (!isArm64Image(image, 0) &&
                    KernelDecompressor.detect(image, 0) == KernelDecompressor.Format.NONE
                ) ParseError.NotKernelImage.readable else null
            }
        }

        // v3/v4 的 header_size 必须能对上，否则内核段起点就是猜的
        if (diagnosis == null && headerVersion >= 3 && headerSize != 1580L && headerSize != 1584L) {
            diagnosis = ParseError.HeaderVersionUnknown(headerVersion, headerSize).readable
        }

        val info = BootImageInfo(
            container = BootImageInfo.Container.ANDROID_BOOT,
            headerVersion = headerVersion,
            headerSize = headerSize,
            pageSize = pageSize,
            kernelOffset = kernelOffset,
            declaredKernelSize = kernelSize,
            imageLength = image.size,
            decompressed = decompressed,
            arm64Image = isArm64Image(image, 0),
        )
        return Result(image, info, diagnosis)
    }

    /**
     * 解压内核段，**同时把失败原因带出来**。
     *
     * 用默认解压器时走 [KernelDecompressor.decompress] 的详细 API，能区分
     * "格式不支持" / "解码失败" / "根本没压缩"；调用方注入了自定义实现时，
     * 接口只能返回 null，拿不到原因（保持向后兼容）。
     */
    private fun tryDecompress(data: ByteArray, decompressor: Decompressor): Pair<ByteArray?, String?> =
        if (decompressor === KernelDecompressor.default) {
            when (val r = KernelDecompressor.decompress(data, 0, data.size)) {
                is DecompressOutcome.Ok -> r.bytes to null
                is DecompressOutcome.Unsupported ->
                    null to ParseError.UnsupportedCompression(r.format, r.magicHex).readable
                is DecompressOutcome.Failed -> null to ParseError.DecompressFailed(r.format, r.reason).readable
                DecompressOutcome.NotCompressed -> null to null
            }
        } else {
            decompressor.decompress(data, 0, data.size) to null
        }

    /**
     * 是否 arm64 Image：
     *  - 原生 Image：文件偏移 0x38 处为 `ARMd`
     *  - UEFI PE stub：0x00 处 `MZ`，0x38 处 `ARMd`
     */
    fun isArm64Image(data: ByteArray, offset: Int): Boolean {
        if (offset + 0x3c > data.size) return false
        return data[offset + 0x38] == 'A'.code.toByte() &&
            data[offset + 0x39] == 'R'.code.toByte() &&
            data[offset + 0x3a] == 'M'.code.toByte() &&
            data[offset + 0x3b] == 'd'.code.toByte()
    }

    fun isGzip(data: ByteArray, offset: Int): Boolean =
        offset + 2 <= data.size && (data[offset].toInt() and 0xff) == 0x1f && (data[offset + 1].toInt() and 0xff) == 0x8b

    fun alignUp(value: Long, alignment: Long): Long {
        val m = value % alignment
        return if (m == 0L) value else value + (alignment - m)
    }

    private fun startsWith(data: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (offset + magic.size > data.size) return false
        for (i in magic.indices) if (data[offset + i] != magic[i]) return false
        return true
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (data[offset + i].toLong() and 0xff)
        return v
    }

    private fun u64(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (data[offset + i].toLong() and 0xff)
        return v
    }
}
