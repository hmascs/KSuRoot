/*
 * 出处：YuKongA/ghostlock-app（Apache-2.0）
 *   app/src/main/kotlin/com/ghostlock/app/data/ota/OtaPayloadExtractor.kt
 *
 * 改动（逐条列出，便于日后核对）：
 *   ① package 名；对象名不变；
 *   ② `ZipFileUtils` → `ZipCentralDirectory`；
 *   ③ **去掉 `REPLACE_BZ` 支持**：它要 `commons-compress`，而现代 OTA 的 boot 分区
 *      用的是 `REPLACE` / `REPLACE_XZ`。为了一个几乎不会出现的旧操作类型引一个
 *      第三方依赖不值得 —— 遇到时给**明确**的报错，而不是静默失败；
 *   ④ 新增**直取条目**路径：recovery 风格完整包（国内厂商常见）里根本没有
 *      `payload.bin`，而是直接放 `boot.img` / `init_boot.img`；
 *   ⑤ `xbl_config` 改为**可选**（默认不取）：本工程走 kallsyms 扫符号，
 *      不需要物理加载地址，取它只是白白多下几十 MiB；
 *   ⑥ 日志中文化，产物文件名前缀 `ghostlock_ota_` → `ksuroot_ota_`。
 *
 * 许可证见 app/src/main/assets/GL_LICENSE_Apache2.txt
 */

package com.kernelpack.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * 纯 Kotlin 的**流式** OTA 分区提取器：只下载 payload 元数据，以及
 * `boot`（可选 `xbl_config`）真正用到的那几块数据。
 *
 * 一次「解析完整包链接」的完整网络代价大致是：
 *   尾部 64 KiB（找中央目录）+ 中央目录本体 + payload 头 24 B + 清单（几百 KiB）
 *   + `boot` 分区那 ~64–96 MiB
 * 而完整包本体是 **4–8 GiB**。这个差距就是这个功能存在的理由。
 */
object OtaPayloadExtractor {

    data class ExtractedPartitions(
        val bootFile: File,
        val xblConfigFile: File? = null,
    )

    private const val END_BYTES_SIZE = 65536

    /**
     * 单个操作数据长度的上限（256 MiB）。
     *
     * 不是为了"限制"什么 —— 真实操作都只有几 MiB —— 而是防两件事：
     * ① `dataLength.toInt()` 溢出成负数，随后变成一个看不懂的异常；
     * ② 一个畸形清单让应用去 `ByteArray` 申请几个 GB。
     * 两者都会把"包有问题"伪装成"应用崩了"。
     */
    private const val MAX_OP_BYTES = 256L shl 20
    private const val LOCAL_HEADER_PROBE_SIZE = 256

    /**
     * 解析一个**完整包链接**，把里面的 boot 镜像落到 [workDir]。
     *
     * @param includeXblConfig 是否顺带取 `xbl_config`。本工程**不需要**它
     *        （偏移来自 kallsyms，不是物理加载地址），默认 false 以省流量。
     * @param onLog 逐行进度；实现里已做 2 秒节流，可以安全地直接刷 UI。
     */
    suspend fun extractPartitions(
        url: String,
        workDir: File,
        includeXblConfig: Boolean = false,
        onLog: (String) -> Unit,
    ): ExtractedPartitions {
        val reader = HttpRangeReader()
        val remote = reader.probe(url)
            ?: throw IOException("连不上这个链接，或者服务器没有返回文件长度")
        val fileLength = remote.length
        if (fileLength <= 0L) throw IOException("远端文件长度非法（$fileLength）")
        if (!remote.acceptsRanges) {
            throw IOException(
                "这个服务器不支持 Range 请求，只能整包下载。" +
                    "完整包通常有 4–8 GiB，本应用不会在你没同意的情况下下这么多。"
            )
        }

        onLog("已连接，文件大小 ${formatSize(fileLength)}")

        if (!workDir.isDirectory && !workDir.mkdirs()) {
            throw IOException("无法创建工作目录：${workDir.absolutePath}")
        }

        // ① 头部就是裸 payload.bin？（有些 CDN 直接把 payload.bin 当完整包发）
        val head = reader.read(url, 0L, 4)
        if (head != null && head.size >= 4 && head.contentEquals(PayloadBinUtils.PAYLOAD_MAGIC)) {
            onLog("链接指向的是裸 payload.bin")
            return extractFromPayload(reader, url, fileLength, payloadStart = 0L, workDir, includeXblConfig, onLog)
        }

        // ② 当作 ZIP：尾部 → 中央目录 → 找候选条目
        onLog("正在定位 ZIP 中央目录…")
        val tailSize = min(fileLength, END_BYTES_SIZE.toLong()).toInt()
        val tailBytes = reader.read(url, fileLength - tailSize, tailSize)
            ?: throw IOException("读不到文件尾部（ZIP 中央目录在那里）")

        val cd = ZipCentralDirectory.locateCentralDirectory(tailBytes, fileLength)
        if (cd.offset < 0 || cd.size <= 0 || cd.offset + cd.size > fileLength) {
            throw IOException("这个链接指向的不是 ZIP 完整包（找不到中央目录）")
        }
        val cdBytes = reader.read(url, cd.offset, cd.size.toInt())
            ?: throw IOException("读不到 ZIP 中央目录（${formatSize(cd.size)}）")

        val entries = ZipCentralDirectory.locateEntries(
            cdBytes, ZipCentralDirectory.BOOT_ENTRY_CANDIDATES.toSet()
        )
        val payloadEntry = entries[PayloadBinUtils.PAYLOAD_ENTRY]
        if (payloadEntry != null) {
            if (!ZipCentralDirectory.isStored(payloadEntry)) {
                throw IOException(
                    "包里的 payload.bin 是压缩存放的（方式 ${payloadEntry.method}），" +
                        "无法按偏移随机读取。这不是本应用能处理的完整包。"
                )
            }
            val payloadStart = locateEntryDataStart(
                reader, url, fileLength, payloadEntry, PayloadBinUtils.PAYLOAD_ENTRY
            )
            onLog("在包里找到 payload.bin（${formatSize(payloadEntry.uncompressedSize)}）")
            return extractFromPayload(reader, url, fileLength, payloadStart, workDir, includeXblConfig, onLog)
        }

        // ③ recovery 风格完整包：直接放 boot.img / init_boot.img
        val direct = ZipCentralDirectory.BOOT_ENTRY_CANDIDATES
            .filter { it != PayloadBinUtils.PAYLOAD_ENTRY }
            .firstNotNullOfOrNull { entries[it] }
        if (direct != null) {
            onLog("包里没有 payload.bin，但直接找到 ${direct.fileName}")
            val boot = extractDirectEntry(reader, url, fileLength, direct, workDir, onLog)
            return ExtractedPartitions(boot, null)
        }

        throw IOException(
            "这个包里既没有 payload.bin，也没有 " +
                ZipCentralDirectory.BOOT_ENTRY_CANDIDATES
                    .filter { it != PayloadBinUtils.PAYLOAD_ENTRY }
                    .joinToString(" / ") +
                "。可能不是完整包，或者厂商用了自己的封装格式。"
        )
    }

    // ────────────────────────── A/B 完整包（payload.bin） ──────────────────────────

    private suspend fun extractFromPayload(
        reader: HttpRangeReader,
        url: String,
        fileLength: Long,
        payloadStart: Long,
        workDir: File,
        includeXblConfig: Boolean,
        onLog: (String) -> Unit,
    ): ExtractedPartitions {
        val headerBytes = reader.read(url, payloadStart, PayloadBinUtils.HEADER_SIZE)
            ?: throw IOException("读不到 payload.bin 头部（偏移 $payloadStart）")
        val header = PayloadBinUtils.parseHeader(headerBytes)
            ?: throw IOException("payload.bin 头部非法（没有 CrAU 魔数）")

        val manifestStart = payloadStart + PayloadBinUtils.HEADER_SIZE
        if (header.manifestSize > Int.MAX_VALUE) {
            throw IOException("payload 清单过大（${header.manifestSize} 字节）")
        }
        val manifest = reader.read(url, manifestStart, header.manifestSize.toInt())
            ?: throw IOException("读不到 payload 清单（${formatSize(header.manifestSize)}）")

        val partitions = PayloadBinUtils.listPartitions(manifest)
        if ("boot" !in partitions) {
            throw IOException(
                "payload 里没有 boot 分区（找到的是：${partitions.joinToString().ifBlank { "无" }}）"
            )
        }
        val want = mutableListOf("boot")
        if (includeXblConfig && "xbl_config" in partitions) want.add("xbl_config")
        onLog("payload 内分区：${partitions.size} 个，准备提取 ${want.joinToString(", ")}")

        val blockSize = PayloadBinUtils.blockSize(manifest)
        val dataBase = payloadStart + PayloadBinUtils.HEADER_SIZE +
            header.manifestSize + header.signatureSize

        val partitionOps = want.associateWith { name ->
            PayloadBinUtils.partitionOperations(manifest, name)
                .ifEmpty { throw IOException("分区 '$name' 在 payload 里没有任何操作") }
        }

        val totalDownloadBytes = partitionOps.values.sumOf { ops -> ops.sumOf { it.dataLength } }
        var downloaded = 0L
        var lastLogTime = 0L

        fun reportProgress(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastLogTime >= 2000) {
                lastLogTime = now
                val percent = if (totalDownloadBytes > 0) {
                    downloaded.toDouble() / totalDownloadBytes * 100.0
                } else 0.0
                onLog(
                    "下载中 ${formatSize(downloaded)} / ${formatSize(totalDownloadBytes)}" +
                        "（" + String.format(Locale.US, "%.1f", percent) + "%）"
                )
            }
        }

        val timestamp = System.currentTimeMillis()
        var bootFile: File? = null
        var xblConfigFile: File? = null

        try {
            for ((name, ops) in partitionOps) {
                val outFile = File(workDir, "ksuroot_ota_${name}_$timestamp.img")
                if (name == "boot") bootFile = outFile else xblConfigFile = outFile

                val totalSize = ops.flatMap { it.destExtents }
                    .maxOfOrNull { (it.startBlock + it.numBlocks) * blockSize }
                    ?: throw IOException("分区 '$name' 长度为 0")

                withContext(Dispatchers.IO) {
                    RandomAccessFile(outFile, "rw").use { raf ->
                        raf.setLength(totalSize)

                        for (op in ops) {
                            when (op.type) {
                                PayloadBinUtils.OP_ZERO, PayloadBinUtils.OP_DISCARD -> {
                                    // setLength 已经填零
                                }

                                PayloadBinUtils.OP_REPLACE -> {
                                    requireSaneOpLength(name, op.dataLength)
                                    val data = reader.read(url, dataBase + op.dataOffset, op.dataLength.toInt())
                                        ?: throw IOException("读取 REPLACE 操作失败（$name @ ${dataBase + op.dataOffset}）")
                                    writeExtents(raf, data, op.destExtents, blockSize)
                                    downloaded += op.dataLength
                                    reportProgress()
                                }

                                PayloadBinUtils.OP_REPLACE_XZ -> {
                                    requireSaneOpLength(name, op.dataLength)
                                    val compressed = reader.read(url, dataBase + op.dataOffset, op.dataLength.toInt())
                                        ?: throw IOException("读取 REPLACE_XZ 操作失败（$name @ ${dataBase + op.dataOffset}）")
                                    val uncompressedSize = op.destExtents.sumOf { it.numBlocks } * blockSize
                                    // 内置 XZ 解码器只支持**单块**流（实测，见 XzDecoder 头部说明）。
                                    // 这里把它翻成一句能看懂的话 —— 否则用户拿到的是一句
                                    // "match distance beyond decoded data"，那对排查毫无帮助。
                                    val decompressed = try {
                                        XzDecoder.decode(compressed, uncompressedSize.toInt())
                                    } catch (e: XzException) {
                                        throw IOException(
                                            "分区「$name」的 XZ 数据解不开（${e.message}）。" +
                                                "本应用内置的 XZ 解码器只支持单块 XZ 流；" +
                                                "如果这个完整包用了多块压缩，就会停在这里。" +
                                                "这是本应用的能力边界，不是文件损坏。",
                                            e,
                                        )
                                    }
                                    writeExtents(raf, decompressed, op.destExtents, blockSize)
                                    downloaded += op.dataLength
                                    reportProgress()
                                }

                                PayloadBinUtils.OP_REPLACE_BZ -> {
                                    // ③ 见文件头说明：不引 commons-compress，明确报错
                                    throw IOException(
                                        "分区 '$name' 用了 REPLACE_BZ（bzip2）操作。" +
                                            "本应用没有内置 bzip2 解码器 —— 这类完整包请先用官方工具转成 boot.img。"
                                    )
                                }

                                else -> throw IOException("分区 '$name' 里有不支持的操作类型 ${op.type}")
                            }
                        }
                    }
                }
                onLog("已还原 $name → ${outFile.name}（${formatSize(outFile.length())}）")
            }
            reportProgress(force = true)
            return ExtractedPartitions(bootFile!!, xblConfigFile)
        } catch (e: Exception) {
            bootFile?.delete()
            xblConfigFile?.delete()
            throw e
        }
    }

    // ────────────────────────── recovery 完整包（直接放镜像） ──────────────────────────

    /** ④ 直接把 ZIP 条目解出来。Stored 原样落盘；Deflate 流式解压。 */
    private suspend fun extractDirectEntry(
        reader: HttpRangeReader,
        url: String,
        fileLength: Long,
        entry: ZipCentralDirectory.CdEntry,
        workDir: File,
        onLog: (String) -> Unit,
    ): File {
        val dataStart = locateEntryDataStart(reader, url, fileLength, entry, entry.fileName)
        val baseName = entry.fileName.substringAfterLast('/')
        val outFile = File(workDir, "ksuroot_ota_${baseName}_${System.currentTimeMillis()}.img")

        onLog("下载 ${entry.fileName}（${formatSize(entry.compressedSize)}）…")
        when (entry.method) {
            0 -> {
                reader.readToFile(url, dataStart, entry.compressedSize, outFile) { written ->
                    onLog("下载中 ${formatSize(written)} / ${formatSize(entry.compressedSize)}")
                }
            }

            8 -> {
                // Deflate：必须先拿到整段压缩数据才能解 —— 但解压是流式的，
                // 所以峰值内存只是压缩后的那一份（几十 MiB），不是解压后的。
                val tmp = File(workDir, outFile.name + ".part")
                try {
                    reader.readToFile(url, dataStart, entry.compressedSize, tmp) { written ->
                        onLog("下载中 ${formatSize(written)} / ${formatSize(entry.compressedSize)}")
                    }
                    withContext(Dispatchers.IO) {
                        InflaterInputStream(ByteArrayInputStream(tmp.readBytes())).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                } finally {
                    tmp.delete()
                }
            }

            else -> throw IOException("${entry.fileName} 用了不支持的压缩方式 ${entry.method}")
        }

        if (!outFile.isFile || outFile.length() <= 0L) {
            outFile.delete()
            throw IOException("解出的 ${entry.fileName} 是空的")
        }
        onLog("已解出 ${outFile.name}（${formatSize(outFile.length())}）")
        return outFile
    }

    // ────────────────────────── 公共小工具 ──────────────────────────

    /**
     * 中央目录里的 `localHeaderOffset` 指向**本地头**；数据在本地头之后，
     * 中间还隔着文件名与扩展区（长度只有读到那 30 字节才知道）。
     */
    private suspend fun locateEntryDataStart(
        reader: HttpRangeReader,
        url: String,
        fileLength: Long,
        entry: ZipCentralDirectory.CdEntry,
        label: String,
    ): Long {
        val headerOffset = entry.localHeaderOffset
        if (headerOffset !in 0..<fileLength) {
            throw IOException("ZIP 里 $label 的本地头偏移非法：$headerOffset")
        }
        val probeSize = min(fileLength - headerOffset, LOCAL_HEADER_PROBE_SIZE.toLong()).toInt()
        val localHeader = reader.read(url, headerOffset, probeSize)
            ?: throw IOException("读不到 $label 的本地文件头")
        val internalOffset = ZipCentralDirectory.locateLocalFileOffset(localHeader)
        if (internalOffset !in 0..probeSize.toLong()) {
            throw IOException("解析不出 $label 的本地文件头长度")
        }
        return headerOffset + internalOffset
    }

    /** 见 [MAX_OP_BYTES]。超限时给出一句能定位到分区的话，而不是让 Int 悄悄溢出。 */
    private fun requireSaneOpLength(partition: String, dataLength: Long) {
        if (dataLength < 0 || dataLength > MAX_OP_BYTES) {
            throw IOException(
                "分区「$partition」里有一个操作声明了 $dataLength 字节的数据长度，" +
                    "超出本应用处理的上限（${formatSize(MAX_OP_BYTES)}）。" +
                    "这通常说明清单被改坏了，或者这不是一个正常的完整包。"
            )
        }
    }

    private fun writeExtents(
        raf: RandomAccessFile,
        data: ByteArray,
        extents: List<PayloadBinUtils.Extent>,
        blockSize: Long,
    ) {
        var dataOffset = 0
        for (extent in extents) {
            val extentBytes = (extent.numBlocks * blockSize).toInt()
            val end = min(dataOffset + extentBytes, data.size)
            if (dataOffset < end) {
                raf.seek(extent.startBlock * blockSize)
                raf.write(data, dataOffset, end - dataOffset)
                dataOffset = end
            }
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "0 B"
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit + 1 < units.size) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
