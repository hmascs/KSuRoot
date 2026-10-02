/*
 * 本文件解决的问题：**用户下载的「完整刷机包」怎么直接取出 boot / vendor_boot**。
 *
 * 与 `com.kernelpack.ota.OtaPayloadExtractor` 的分工（别搞混）：
 *   · `OtaPayloadExtractor` 处理的是**远端 HTTP 直链**，靠 Range 请求只下需要的那几块；
 *   · 本文件处理的是**已经躺在手机上的本地包**，用 `RandomAccessFile` / 流式读，绝不整包进内存。
 * 两者共用 ZIP 结构解析（`ZipCentralDirectory`）与 payload 清单解析（`PayloadBinUtils`），
 * 但「怎么拿到字节」这一层完全不同 —— 远端要发 Range，本地要 seek 或顺序跳过。
 *
 * 两种包形态（实测都存在于用户手上）：
 *   A. OTA zip：`payload.bin`（CrAU）在里面，boot 分区要靠 install operation 还原；
 *   B. fastboot ROM（`.tgz` / `.tar.gz`）：gzip + tar 里直接放 `images/boot.img`。
 *
 * 为什么不用现成的库：
 *   · `java.util.zip.ZipFile` 要整包在手（包是 4–8 GiB）；
 *   · `java.util.zip.GZIPInputStream` 之上没有 tar（JDK 不带 tar，`commons-compress`
 *     本工程没有依赖，也不为了这件事引一个新依赖 —— 见 `KernelDecompressor` 头部的同款决定）。
 */
package com.kernelpack.boot

import com.kernelpack.ota.OtaPayloadExtractor
import com.kernelpack.ota.PayloadBinUtils
import com.kernelpack.ota.XzDecoder
import com.kernelpack.ota.XzException
import com.kernelpack.ota.ZipCentralDirectory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * 从**完整刷机包**里取出 boot / vendor_boot 镜像。
 *
 * 这个类**不接受**"猜"：认不出格式、包里没有要的分区、解压失败，一律抛
 * [ExtractException]，并且错误文案里必须能读出三件事 ——
 * 「这是什么格式」「包里到底有什么」「为什么取不到」。这个工程吃过太多次
 * "静默返回空结果"的亏：上层看到空 map 会当成"这个包没有 boot 分区"，
 * 于是用户拿着一个完好的包被告知"包不对"。
 */
object RomPackageExtractor {

    /** 包形态。 */
    enum class Kind {
        /** ZIP：要么内含 `payload.bin`（A/B OTA），要么直接放 `boot.img`。 */
        OTA_ZIP,

        /** gzip + tar：fastboot ROM（小米等厂商的 `.tgz` / `.tar.gz`）。 */
        FASTBOOT_TGZ,

        /** 认不出来。 */
        UNKNOWN,
    }

    /**
     * 取到的一份镜像。
     *
     * [name] 是**包内条目的文件名**：OTA 包的分区名没有后缀（`boot` / `vendor_boot`），
     * fastboot 包里是文件名（`boot.img`，或 `images/boot.img` 的 basename）。
     * 它与 [extract] 的键（永远是**请求名**）不同，见 [extract] 的说明。
     */
    data class Found(
        val name: String,
        val bytes: ByteArray,
    )

    /** 取不到就抛这个，文案是人话（见类注释）。 */
    class ExtractException(message: String) : Exception(message)

    // ─────────────────────────── 各种上限：防的是"把包有问题伪装成应用崩了" ───────────────────────────

    /** ZIP 尾部窗口：中央目录（含 ZIP64 记录）一定能在这个窗口里找到。 */
    private const val TAIL_BYTES = 65536

    /**
     * 中央目录本体上限。真实包只有几十 KiB；放到 64 MiB 是为了容忍
     * "一个包里塞了几万个条目"，同时挡住"中央目录字段被改坏 → 申请几 GB"。
     */
    private const val MAX_CENTRAL_DIRECTORY = 64 shl 20

    private const val LOCAL_HEADER_PROBE = 256

    /**
     * 单个 install operation 解出来的字节上限（256 MiB）。
     *
     * 不是为了限制什么 —— 真实的 boot 分区操作块只有几 MiB —— 而是防两件事：
     * ① `dataLength.toInt()` 溢出成负数，随后变成一个看不懂的异常；
     * ② 被改坏的清单让应用去申请几个 GB。
     */
    private const val MAX_OP_BYTES = 256L shl 20

    /**
     * 单份镜像上限（512 MiB）。本接口返回 `ByteArray`，而手机 JVM 里
     * 分配 1 GiB 连续数组多半直接 OOM —— 那会表现成"应用崩了"，
     * 而不是"这个包我不处理"。宁可明确拒绝。
     */
    private const val MAX_IMAGE_BYTES = 512L shl 20

    /** tar 包内条目名最多记多少个（只用于报错文案，防止畸形包把内存吃光）。 */
    private const val MAX_LISTED_ENTRIES = 80

    // ─────────────────────────── install operation 类型 ───────────────────────────
    //
    // `PayloadBinUtils` 只有 REPLACE / REPLACE_BZ / ZERO / DISCARD / REPLACE_XZ 五个常量
    // （那个文件是别人在真机上跑通的实现，逐字节原样移植，不能动）。这里补上缺的：
    // 14 是**实测一定会用到**的 —— 手上的 OPPO/一加系完整包里 37 个分区**全部**是 REPLACE_ZSTD。
    private const val OP_MOVE = 2L
    private const val OP_BSDIFF = 3L
    private const val OP_SOURCE_COPY = 4L
    private const val OP_SOURCE_BSDIFF = 5L
    private const val OP_REPLACE_ZSTD = 14L

    private val OP_NAMES = mapOf(
        PayloadBinUtils.OP_REPLACE to "REPLACE",
        PayloadBinUtils.OP_REPLACE_BZ to "REPLACE_BZ",
        OP_MOVE to "MOVE",
        OP_BSDIFF to "BSDIFF",
        OP_SOURCE_COPY to "SOURCE_COPY",
        OP_SOURCE_BSDIFF to "SOURCE_BSDIFF",
        PayloadBinUtils.OP_ZERO to "ZERO",
        PayloadBinUtils.OP_DISCARD to "DISCARD",
        PayloadBinUtils.OP_REPLACE_XZ to "REPLACE_XZ",
        9L to "PUFFDIFF",
        10L to "BROTLI_BSDIFF",
        11L to "ZUCCHINI",
        12L to "LZ4DIFF_BSDIFF",
        13L to "LZ4DIFF_PUFFDIFF",
        OP_REPLACE_ZSTD to "REPLACE_ZSTD",
    )

    // ─────────────────────────── 对外接口 ───────────────────────────

    /** 只看格式，不解内容：读文件头几个字节就够，几 GB 的包也是毫秒级。 */
    fun detect(file: File): Kind {
        if (!file.isFile) return Kind.UNKNOWN
        val head = try {
            FileInputStream(file).use { input -> ByteArray(265).also { input.read(it) } }
        } catch (_: IOException) {
            return Kind.UNKNOWN
        } catch (_: SecurityException) {
            return Kind.UNKNOWN
        }
        return detectByHead(head)
    }

    /**
     * 从包里取出指定的分区。
     *
     * @param wanted 想要的名字集合，如 `setOf("boot", "vendor_boot")`。
     *        **用分区名，不带 `.img`** —— OTA 里本来就没有后缀；fastboot 包里
     *        `boot` 会去匹配 `boot.img` / `images/boot.img`。
     * @return 请求名 → 字节。**取不到的键不会出现**（不会塞 null）：
     *         `setOf("boot", "vendor_boot")` 只要拿到一个就会返回那一个，
     *         所以调用方**必须**用 `map["vendor_boot"] != null` 对账，
     *         或者先用 [listPartitions] 看包里有什么。
     *         只有**一个都没取到**时才抛异常。
     * @throws ExtractException 格式不认识 / 包里没有这些分区 / 解压失败。
     */
    fun extract(file: File, wanted: Set<String>): Map<String, ByteArray> {
        val results = LinkedHashMap<String, ByteArray>(wanted.size)
        for (found in find(file, wanted)) {
            // Found.name 对 fastboot 包是 "boot.img"；对外统一成请求名，
            // 免得调用方为了拿 boot 还要判断包里那份到底叫 boot 还是 boot.img。
            val key = wanted.firstOrNull { normalizeName(it) == normalizeName(found.name) } ?: found.name
            results[key] = found.bytes
        }
        return results
    }

    /** 同 [extract]，但保留"包内条目名"，并把结果按 [wanted] 的顺序排好。 */
    fun find(file: File, wanted: Set<String>): List<Found> {
        if (wanted.isEmpty()) {
            throw ExtractException("没有指定要取哪个分区（wanted 是空集合）—— 本方法不会替你猜。")
        }
        if (!file.isFile) {
            throw ExtractException(
                "文件不存在或者不是一个普通文件：${file.absolutePath}" +
                    if (file.exists()) "（它是个目录？）" else "（路径打错了？）"
            )
        }
        // 统一按**归一化名称**（去掉目录、去掉 .img）去找：调用方写 "boot" 还是
        // "boot.img" 都该能用 —— 用户从包里看到的名字与分区名经常对不上。
        val targets = LinkedHashSet<String>()
        wanted.forEach { targets.add(normalizeName(it)) }

        val collected: Map<String, Found> = when (detect(file)) {
            Kind.OTA_ZIP -> findByZip(file, targets)
            Kind.FASTBOOT_TGZ -> findByTar(file, targets)
            Kind.UNKNOWN -> throw ExtractException(unknownFormatMessage(file))
        }
        // 按请求顺序返回，顺带把"取不到"的键滤掉（约定：不塞 null）。
        return wanted.mapNotNull { name -> collected[normalizeName(name)] }
    }

    /**
     * 这个包里**到底有什么**（人话诊断用；[find] 的报错文案也走这条路径）。
     *
     * ZIP：有 `payload.bin` 就列 payload 清单里的**分区名**（那才是"包里的分区"），
     *      否则列 zip 条目名。
     * fastboot 包：列 tar 条目名。注意这必须把整个 gzip 流过一遍 ——
     *      8 GB 的包会慢，这是格式本身决定的（tar 没有索引）。
     */
    fun listPartitions(file: File): List<String> {
        if (!file.isFile) throw ExtractException("文件不存在：${file.absolutePath}")
        return when (detect(file)) {
            Kind.OTA_ZIP -> listZipContents(file)
            Kind.FASTBOOT_TGZ -> listTarContents(file)
            Kind.UNKNOWN -> throw ExtractException(unknownFormatMessage(file))
        }
    }

    // ─────────────────────────── 格式识别 ───────────────────────────

    private fun detectByHead(head: ByteArray): Kind {
        // ZIP：本地文件头 PK\003\004 / 空包 PK\005\006 / 分卷 PK\007\008
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 0x03.toByte() &&
            (head[3] == 0x04.toByte() || head[3] == 0x06.toByte() || head[3] == 0x08.toByte())
        ) {
            return Kind.OTA_ZIP
        }
        // gzip：1f 8b
        if (head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) {
            return Kind.FASTBOOT_TGZ
        }
        // 未压缩的 tar：偏移 257 处是 "ustar"。有些厂商的 fastboot 包是纯 .tar，
        // 容器结构与 .tgz 一模一样，没必要让用户先自己 gzip 一遍。
        if (head.size >= 263 &&
            head[257] == 'u'.code.toByte() && head[258] == 's'.code.toByte() &&
            head[259] == 't'.code.toByte() && head[260] == 'a'.code.toByte() &&
            head[261] == 'r'.code.toByte()
        ) {
            return Kind.FASTBOOT_TGZ
        }
        return Kind.UNKNOWN
    }

    private fun unknownFormatMessage(file: File): String {
        val hex = try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(8).also { input.read(it) }
                buf.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
            }
        } catch (_: IOException) {
            "<读不了>"
        } catch (_: SecurityException) {
            "<读不了>"
        }
        return "认不出这个包的格式：`${file.name}`（${OtaPayloadExtractor.formatSize(file.length())}）" +
            "开头 8 字节是 $hex。\n" +
            "本工具认识两种完整刷机包：\n" +
            "  · OTA zip（开头 `50 4b 03 04`，里面是 payload.bin）；\n" +
            "  · fastboot 包 `.tgz` / `.tar.gz`（开头 `1f 8b`，或未压缩 tar 的偏移 257 处 `ustar`）。\n" +
            "如果这是别的封装（厂商私有格式、分卷压缩包、`.tar.md5` 等），需要先解出上面两种之一。"
    }

    private fun normalizeName(name: String): String {
        val base = name.substringAfterLast('/')
        return if (base.endsWith(".img")) base.dropLast(4) else base
    }

    // ─────────────────────────── 形态 A：ZIP（含 payload.bin 的 OTA 包） ───────────────────────────

    private fun findByZip(file: File, wanted: Set<String>): Map<String, Found> {
        openReadOnly(file).use { raf ->
            val fileLength = raf.length()
            if (fileLength < 22) {
                throw ExtractException("`${file.name}` 只有 $fileLength 字节，连 ZIP 尾部记录都放不下。")
            }

            // ① 尾部窗口 → 中央目录（ZIP64 由 ZipCentralDirectory 内部处理；
            //    完整包几乎一定 > 4 GiB，所以 ZIP64 是常态而不是例外）
            val tailSize = min(fileLength, TAIL_BYTES.toLong()).toInt()
            val tail = readAt(raf, fileLength - tailSize, tailSize)
            val cd = ZipCentralDirectory.locateCentralDirectory(tail, fileLength)
            if (cd.offset < 0 || cd.size <= 0L || cd.offset + cd.size > fileLength) {
                throw ExtractException(
                    "`${file.name}` 不是 ZIP 完整包：尾部 ${OtaPayloadExtractor.formatSize(tailSize.toLong())}" +
                        "窗口里找不到中央目录结束记录（PK\\x05\\x06）。\n" +
                        "注意：扩展名是 .zip 不代表内容真是 zip（下载中断会留下半个文件）。"
                )
            }
            if (cd.size > MAX_CENTRAL_DIRECTORY) {
                throw ExtractException(
                    "`${file.name}` 的中央目录声明了 ${OtaPayloadExtractor.formatSize(cd.size)}，" +
                        "超过本工具的上限（${OtaPayloadExtractor.formatSize(MAX_CENTRAL_DIRECTORY.toLong())}）——" +
                        "这更像是文件被改坏了，而不是一个正常完整包。"
                )
            }
            val cdBytes = readAt(raf, cd.offset, cd.size.toInt())

            // ② 复用已有的中央目录条目定位（关键：中央目录里的 localHeaderOffset 指向
            //    **本地头**，数据还在文件名与扩展区之后 —— 少算这一步就会从名字中间开始读，
            //    表现是"魔数不对"）
            val candidates = zipCandidateNames(wanted)
            val entries = ZipCentralDirectory.locateEntries(cdBytes, candidates)
            val zipNames = listCentralDirectoryNames(cdBytes)

            val payloadEntry = entries[PayloadBinUtils.PAYLOAD_ENTRY]
            if (payloadEntry != null) {
                return extractFromPayload(raf, fileLength, payloadEntry, wanted, zipNames)
            }

            // ③ 没有 payload.bin：recovery 风格完整包直接放 boot.img / images/boot.img
            val direct = LinkedHashMap<String, ZipCentralDirectory.CdEntry>()
            for (name in wanted) {
                val normalized = normalizeName(name)
                for (candidate in directEntryCandidates(normalized)) {
                    val entry = entries[candidate] ?: continue
                    direct[normalized] = entry
                    break
                }
            }
            if (direct.isNotEmpty()) {
                val out = LinkedHashMap<String, Found>(direct.size)
                for ((normalized, entry) in direct) {
                    out[normalized] = Found(entry.fileName, readZipEntry(raf, fileLength, entry))
                }
                return out
            }

            throw ExtractException(
                "`${file.name}` 是个 ZIP，但里面既没有 `payload.bin`，也没有 " +
                    wanted.joinToString(" / ") { "$it.img" } + "。\n" +
                    "包内条目（${zipNames.size} 个）：" + listForMessage(zipNames) +
                    "\n" + directEntryHint(entries, wanted)
            )
        }
    }

    /** 请求名 → 包内可能出现的条目名，按优先级。 */
    private fun zipCandidateNames(wanted: Set<String>): Set<String> {
        val names = LinkedHashSet<String>()
        names.add(PayloadBinUtils.PAYLOAD_ENTRY)
        for (name in wanted) {
            names.addAll(directEntryCandidates(normalizeName(name)))
        }
        return names
    }

    private fun directEntryCandidates(normalized: String): List<String> = listOf(
        "$normalized.img",
        "images/$normalized.img",
        normalized,
        "images/$normalized",
    )

    /**
     * 包里没有要的分区时，尽量替用户指出"是不是拿错分区了"。
     *
     * `boot` / `init_boot` / `vendor_boot` 是**三个不同的分区**（Android 13 起
     * 内核段搬到 init_boot，vendor_boot 只放 ramdisk），用户很容易下错；
     * 这个区别直接决定后面能不能刷进去，所以宁可多嘴一句。
     */
    private fun directEntryHint(
        entries: Map<String, ZipCentralDirectory.CdEntry>,
        wanted: Set<String>,
    ): String {
        val hints = ArrayList<String>()
        for (name in wanted) {
            val normalized = normalizeName(name)
            val alternatives = listOf("boot", "init_boot", "vendor_boot")
                .filter { it != normalized }
                .filter { alt -> entries.keys.any { normalizeName(it) == alt } }
            if (alternatives.isNotEmpty()) {
                hints.add(
                    "你要的是 `$normalized`，但包里只有 " +
                        alternatives.joinToString(" / ") { "`$it`" } + " —— 它们是不同的分区。"
                )
            }
        }
        return hints.joinToString("\n")
    }

    /** 从 `payload.bin` 还原分区。stored 与 deflate 两种存放都要认。 */
    private fun extractFromPayload(
        raf: RandomAccessFile,
        fileLength: Long,
        payloadEntry: ZipCentralDirectory.CdEntry,
        wanted: Set<String>,
        zipNames: List<String>,
    ): Map<String, Found> {
        val dataStart = locateEntryDataStart(raf, fileLength, payloadEntry)
        // 数据读取抽象：stored 可以随便 seek；deflate 只能顺序往前走（见 ZipDeflatePayloadReader）。
        val reader: PayloadReader = when (payloadEntry.method) {
            0 -> StoredPayloadReader(raf, dataStart)
            8 -> ZipDeflatePayloadReader(raf, dataStart, payloadEntry.compressedSize)
            else -> throw ExtractException(
                "`payload.bin` 用了压缩方式 ${payloadEntry.method}（既不是 stored 0 也不是 deflate 8）——" +
                    "本工具不认这种存放方式，不敢按偏移乱读。"
            )
        }
        try {
            // 头 24 字节：magic(4) + version(8) + manifest_size(8) + signature_size(4)
            val headerBytes = reader.read(0L, PayloadBinUtils.HEADER_SIZE)
            val header = PayloadBinUtils.parseHeader(headerBytes)
                ?: throw ExtractException(
                    "`payload.bin` 头部非法：前 4 字节是 " +
                        headerBytes.take(4).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } +
                        "（应为 `43 72 41 55` = \"CrAU\"），或者清单长度字段超出了合理范围。"
                )
            if (header.manifestSize > Int.MAX_VALUE) {
                throw ExtractException("payload 清单声明 ${header.manifestSize} 字节，超过本工具上限。")
            }
            val manifest = reader.read(
                PayloadBinUtils.HEADER_SIZE.toLong(), header.manifestSize.toInt()
            )
            val partitions = PayloadBinUtils.listPartitions(manifest)
            val blockSize = PayloadBinUtils.blockSize(manifest)

            val present = wanted.filter { it in partitions }
            if (present.isEmpty()) {
                throw ExtractException(
                    "`payload.bin` 里没有你要的分区（${wanted.joinToString(", ")}）。\n" +
                        "包里实际有 ${partitions.size} 个分区：" + listForMessage(partitions.sorted()) + "\n" +
                        "（payload.bin 存放在 zip 里的方式：method=${payloadEntry.method}；" +
                        "zip 里另有 ${zipNames.size} 个条目）"
                )
            }

            val dataBase = PayloadBinUtils.HEADER_SIZE.toLong() + header.manifestSize + header.signatureSize
            val found = LinkedHashMap<String, Found>(present.size)
            for (name in wanted) {
                if (name !in present) continue
                found[name] = Found(name, assemblePartition(reader, manifest, name, blockSize, dataBase))
            }
            return found
        } finally {
            (reader as? Closeable)?.close()
        }
    }

    /**
     * 把某个分区的 install operation 逐个还原进一块内存缓冲。
     *
     * 为什么按 `dataOffset` 排序：deflate 存放的 payload 只能顺序读（见
     * [ZipDeflatePayloadReader]），而清单里的操作**通常**已经是升序的；
     * 排序让"通常"变成"一定"。extent 之间互不重叠，所以写回顺序不影响结果。
     */
    private fun assemblePartition(
        reader: PayloadReader,
        manifest: ByteArray,
        name: String,
        blockSize: Long,
        dataBase: Long,
    ): ByteArray {
        val ops = PayloadBinUtils.partitionOperations(manifest, name)
        if (ops.isEmpty()) {
            throw ExtractException(
                "分区 `$name` 在 payload 清单里存在，但没有任何 install operation ——" +
                    "这个清单是坏的（正常分区至少有 1 个操作）。"
            )
        }

        // 镜像长度 = 所有目标区间里最远的那个块的末尾。
        var lastBlockEnd = 0L
        for (op in ops) {
            for (extent in op.destExtents) {
                val end = extent.startBlock + extent.numBlocks
                if (end > lastBlockEnd) lastBlockEnd = end
            }
        }
        val imageSize = lastBlockEnd * blockSize
        if (imageSize <= 0L) throw ExtractException("分区 `$name` 的目标长度算出来是 0 —— 清单坏了。")
        if (imageSize > MAX_IMAGE_BYTES) {
            throw ExtractException(
                "分区 `$name` 的镜像长 ${OtaPayloadExtractor.formatSize(imageSize)}，" +
                    "超过本接口的处理上限（${OtaPayloadExtractor.formatSize(MAX_IMAGE_BYTES)}）。" +
                    "本接口返回 ByteArray，再大就会 OOM —— 与其崩掉不如在这里说清楚。"
            )
        }
        val image = ByteArray(imageSize.toInt())

        val ordered = ops.withIndex().sortedBy { it.value.dataOffset }
        for ((opIndex, op) in ordered) {
            // data_length == 0 是 payload 规范允许的写法：目标区间全零，数据段一个字节都不占。
            // boot 分区末尾的填充经常长这样。缓冲初始化就是 0，直接跳过即可。
            if (op.type == PayloadBinUtils.OP_ZERO || op.type == PayloadBinUtils.OP_DISCARD ||
                op.dataLength == 0L
            ) {
                continue
            }
            val expected = op.destExtents.sumOf { it.numBlocks } * blockSize
            if (expected < 0L || expected > MAX_OP_BYTES) {
                throw ExtractException(
                    "分区 `$name` 第 ${opIndex + 1} 个操作声明了 $expected 字节的目标区间，" +
                        "超出上限（${OtaPayloadExtractor.formatSize(MAX_OP_BYTES)}）—— 清单被改坏？"
                )
            }
            val payload = decodeOperation(reader, name, opIndex, op, expected.toInt(), dataBase)
            writeExtents(image, name, payload, op.destExtents, blockSize)
        }
        return image
    }

    /** 单个操作 → 明文字节。所有失败路径都带上分区名与操作序号，便于对账。 */
    private fun decodeOperation(
        reader: PayloadReader,
        name: String,
        opIndex: Int,
        op: PayloadBinUtils.PayloadOperation,
        expected: Int,
        dataBase: Long,
    ): ByteArray {
        val label = "分区 `$name` 第 ${opIndex + 1} 个操作（${OP_NAMES[op.type] ?: "type=${op.type}"}）"
        if (op.dataLength < 0L || op.dataLength > MAX_OP_BYTES) {
            throw ExtractException(
                "$label 声明数据长度 ${op.dataLength} 字节，超出上限" +
                    "（${OtaPayloadExtractor.formatSize(MAX_OP_BYTES)}）—— 清单被改坏？"
            )
        }
        val data = reader.read(dataBase + op.dataOffset, op.dataLength.toInt())
        return when (op.type) {
            PayloadBinUtils.OP_REPLACE -> {
                if (data.size != expected) {
                    throw ExtractException(
                        "$label 是未压缩的 REPLACE，数据 ${data.size} 字节，" +
                            "但目标区间是 $expected 字节 —— 两者必须相等，这份清单与数据对不上。"
                    )
                }
                data
            }

            PayloadBinUtils.OP_REPLACE_XZ -> {
                // 先用内置的纯 Kotlin 解码器（不依赖外部进程）；它是**单块流**实现，
                // 遇到多块流会抛 XzException —— 那时再退到 xz CLI 兜底，
                // 而不是把一句 "match distance beyond decoded data" 丢给用户。
                try {
                    val out = XzDecoder.decode(data, maxOutput = expected)
                    requireSize(out, expected, label, "XZ")
                    out
                } catch (e: XzException) {
                    val out = CliCodec.run(
                        tool = CliCodec.Tool.XZ,
                        data = data,
                        expectedSize = expected,
                        label = label,
                        whyItFailed = "内置 XZ 解码器只支持单块流，这份数据它解不开（${e.message}）",
                    )
                    requireSize(out, expected, label, "XZ")
                    out
                }
            }

            PayloadBinUtils.OP_REPLACE_BZ -> {
                // bzip2：JDK 没有，本工程也没有 commons-compress（不为此引依赖），
                // 所以走外部 bzip2 CLI；找不到就明确报错，绝不"跳过这个操作"。
                val out = CliCodec.run(
                    tool = CliCodec.Tool.BZIP2, data = data, expectedSize = expected,
                    label = label, whyItFailed = null,
                )
                requireSize(out, expected, label, "bzip2")
                out
            }

            OP_REPLACE_ZSTD -> {
                // 实测：手上的 OPPO/一加系完整包 37 个分区**全部**是这一种。
                // 本工程没有 zstd 解码库（见 KernelDecompressor 头部：不为此引新依赖），
                // 所以调参考实现（zstd CLI）。自己手写一个"可能悄悄解错"的 zstd 解码器
                // 在这里是**更坏**的选择 —— boot 镜像解错 = 刷进去变砖。
                if (data.size < 4 || data[0] != 0x28.toByte() || data[1] != 0xB5.toByte() ||
                    data[2] != 0x2F.toByte() || data[3] != 0xFD.toByte()
                ) {
                    throw ExtractException(
                        "$label 声称是 REPLACE_ZSTD，但数据开头不是 zstd 魔数 `28 b5 2f fd`（实际 " +
                            data.take(4).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } +
                            "）—— 清单与数据段错位了，继续解只会产出垃圾。"
                    )
                }
                val out = CliCodec.run(
                    tool = CliCodec.Tool.ZSTD, data = data, expectedSize = expected,
                    label = label, whyItFailed = null,
                )
                requireSize(out, expected, label, "zstd")
                out
            }

            PayloadBinUtils.OP_ZERO, PayloadBinUtils.OP_DISCARD -> ByteArray(0)

            else -> throw ExtractException(
                "$label 的类型本工具不支持（${OP_NAMES[op.type] ?: "未知类型 ${op.type}"}）。" +
                    if (op.type == OP_MOVE || op.type == OP_BSDIFF ||
                        op.type == OP_SOURCE_COPY || op.type == OP_SOURCE_BSDIFF
                    ) {
                        "\n这是**增量包**才有的操作（要拿旧镜像当输入才能还原）——" +
                            "完整包里不该出现它，请换一份 `*-update-full*.zip`。"
                    } else {
                        "\n这是一份完整包，却用了本工具没实现的操作类型；请把包名与这个类型号一并反馈。"
                    }
            )
        }
    }

    /**
     * 解压结果的尺寸必须**恰好**等于目标区间。
     *
     * 这是本文件最要紧的一道完整性校验：zstd/xz 解出来的字节数不对，
     * 说明清单与数据段对不上（或数据损坏）。少了这一步，错误数据会被
     * 原样写进 boot 镜像 —— 而那种镜像要刷进去才知道坏。
     */
    private fun requireSize(out: ByteArray, expected: Int, label: String, format: String) {
        if (out.size != expected) {
            throw ExtractException(
                "$label 的 $format 数据解出 ${out.size} 字节，但目标区间是 $expected 字节 ——" +
                    "两者必须相等。这份包的数据段与清单对不上（损坏？被改过？），不敢写入。"
            )
        }
    }

    private fun locateEntryDataStart(
        raf: RandomAccessFile,
        fileLength: Long,
        entry: ZipCentralDirectory.CdEntry,
    ): Long {
        val headerOffset = entry.localHeaderOffset
        if (headerOffset < 0L || headerOffset >= fileLength) {
            throw ExtractException(
                "ZIP 里 `${entry.fileName}` 的本地头偏移非法（$headerOffset，文件长 $fileLength）—— 中央目录被改坏了。"
            )
        }
        val probeSize = min(fileLength - headerOffset, LOCAL_HEADER_PROBE.toLong()).toInt()
        val probe = readAt(raf, headerOffset, probeSize)
        val inner = ZipCentralDirectory.locateLocalFileOffset(probe)
        if (inner < 0L || inner > probeSize.toLong()) {
            throw ExtractException("解析不出 `${entry.fileName}` 的本地文件头长度（偏移 $headerOffset）。")
        }
        return headerOffset + inner
    }

    /** 直接取 ZIP 条目（recovery 风格包里的 boot.img）：stored 原样、deflate 流式解。 */
    private fun readZipEntry(
        raf: RandomAccessFile,
        fileLength: Long,
        entry: ZipCentralDirectory.CdEntry,
    ): ByteArray {
        if (entry.uncompressedSize > MAX_IMAGE_BYTES) {
            throw ExtractException(
                "ZIP 条目 `${entry.fileName}` 有 ${OtaPayloadExtractor.formatSize(entry.uncompressedSize)}，" +
                    "超过本接口上限（${OtaPayloadExtractor.formatSize(MAX_IMAGE_BYTES)}）。"
            )
        }
        val dataStart = locateEntryDataStart(raf, fileLength, entry)
        return when (entry.method) {
            0 -> readAt(raf, dataStart, entry.compressedSize.toInt())
            8 -> {
                val limited = BoundedRandomAccessStream(raf, dataStart, entry.compressedSize)
                // ZIP 里的 deflate 是**裸**流（RFC 1951），没有 zlib 那两字节头 ——
                // 所以必须 Inflater(true)。用默认的 Inflater(false) 会去找 `78 9c`，
                // 于是直接报 "incorrect header check"（这个坑是被 fixture 用例当场抓住的：
                // 真实 OTA 里 payload.bin 都是 stored，deflate 这条路径只能靠造包来测）。
                InflaterInputStream(BufferedInputStream(limited, 1 shl 16), Inflater(true)).use { input ->
                    try {
                        input.readCapped(entry.uncompressedSize, entry.fileName)
                    } catch (e: IOException) {
                        throw ExtractException(
                            "ZIP 条目 `${entry.fileName}` 的 deflate 数据解不开：${e.message}" +
                                "（压缩流被截断，或者它根本不是 deflate 流）"
                        )
                    }
                }
            }

            else -> throw ExtractException(
                "ZIP 条目 `${entry.fileName}` 用了压缩方式 ${entry.method}（本工具支持 stored 0 与 deflate 8）。"
            )
        }
    }

    /**
     * 极简中央目录遍历：**只为报错文案**列出包内条目名。
     *
     * 为什么不复用 `ZipCentralDirectory.locateEntries`：那个方法只回答
     * "我要的这几个条目在不在"（传进去是一个名字集合），拿不到"包里都有什么"。
     * 而"包里到底有什么"正是这个工程要求错误信息必须说清的三件事之一。
     */
    private fun listCentralDirectoryNames(cdBytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        var pos = 0
        while (pos + 46 <= cdBytes.size) {
            if (cdBytes.u32le(pos) != 0x02014b50L) break
            val nameLength = cdBytes.u16le(pos + 28)
            val extraLength = cdBytes.u16le(pos + 30)
            val commentLength = cdBytes.u16le(pos + 32)
            val nameStart = pos + 46
            if (nameStart + nameLength > cdBytes.size) break
            names.add(cdBytes.decodeToString(nameStart, nameStart + nameLength))
            pos = nameStart + nameLength + extraLength + commentLength
        }
        return names
    }

    private fun listZipContents(file: File): List<String> {
        openReadOnly(file).use { raf ->
            val fileLength = raf.length()
            val tailSize = min(fileLength, TAIL_BYTES.toLong()).toInt()
            val tail = readAt(raf, fileLength - tailSize, tailSize)
            val cd = ZipCentralDirectory.locateCentralDirectory(tail, fileLength)
            if (cd.offset < 0 || cd.size <= 0L || cd.offset + cd.size > fileLength) {
                throw ExtractException("`${file.name}` 里找不到 ZIP 中央目录。")
            }
            val cdBytes = readAt(raf, cd.offset, cd.size.toInt())
            val entries = ZipCentralDirectory.locateEntries(
                cdBytes, setOf(PayloadBinUtils.PAYLOAD_ENTRY)
            )
            val payloadEntry = entries[PayloadBinUtils.PAYLOAD_ENTRY]
                ?: return listCentralDirectoryNames(cdBytes)
            val reader: PayloadReader = when (payloadEntry.method) {
                0 -> StoredPayloadReader(raf, locateEntryDataStart(raf, fileLength, payloadEntry))
                8 -> ZipDeflatePayloadReader(
                    raf, locateEntryDataStart(raf, fileLength, payloadEntry), payloadEntry.compressedSize
                )

                else -> return listCentralDirectoryNames(cdBytes)
            }
            try {
                val header = PayloadBinUtils.parseHeader(reader.read(0L, PayloadBinUtils.HEADER_SIZE))
                    ?: return listCentralDirectoryNames(cdBytes)
                val manifest = reader.read(
                    PayloadBinUtils.HEADER_SIZE.toLong(), header.manifestSize.toInt()
                )
                return PayloadBinUtils.listPartitions(manifest)
            } finally {
                (reader as? Closeable)?.close()
            }
        }
    }

    // ─────────────────────────── 形态 B：fastboot 包（gzip + tar） ───────────────────────────

    /**
     * 流式走一遍 tar，只把目标条目的字节读进内存。
     *
     * 为什么要"流式"：小米的 fastboot 包 5–8 GB，里面还有一个几 GB 的 super.img。
     * 整包读进内存不可能；而 gzip **没有随机访问**，所以只能顺序前进 ——
     * 目标条目找齐之后，后面的字节**一个都不读**（这是唯一能省时间的地方）。
     */
    private fun findByTar(file: File, wanted: Set<String>): Map<String, Found> {
        val found = LinkedHashMap<String, Found>()
        val names = ArrayList<String>()
        openTarStream(file).use { stream ->
            walkTar(stream, names) { entryName, size, input ->
                val normalized = normalizeName(entryName)
                if (normalized in wanted && !found.containsKey(normalized)) {
                    if (size > MAX_IMAGE_BYTES) {
                        throw ExtractException(
                            "包里的 `$entryName` 有 ${OtaPayloadExtractor.formatSize(size)}，" +
                                "超过本接口上限（${OtaPayloadExtractor.formatSize(MAX_IMAGE_BYTES)}）。"
                        )
                    }
                    found[normalized] = Found(entryName, input.readCapped(size, entryName))
                    // 找齐了就停：包可能有 8 GB，后面的字节不值得再走一遍 gzip。
                    found.size == wanted.size
                } else {
                    skipFully(input, size, entryName)
                    false
                }
            }
        }
        if (found.isEmpty()) {
            throw ExtractException(
                "`${file.name}` 是个 fastboot 包（gzip + tar），但里面没有 " +
                    wanted.joinToString(" / ") { "$it.img" } + "。\n" +
                    "包内条目（${names.size} 个）：" + listForMessage(names) +
                    "\n（fastboot 包里镜像一般在 `images/` 子目录下；" +
                    "如果只有 `super.img`，那 boot 在 super 的逻辑分区里，需要别的工具再切一层。）"
            )
        }
        return found
    }

    private fun listTarContents(file: File): List<String> {
        val names = ArrayList<String>()
        openTarStream(file).use { stream ->
            walkTar(stream, names) { _, size, input ->
                skipFully(input, size, "tar 条目")
                false
            }
        }
        return names
    }

    private fun openTarStream(file: File): InputStream {
        // gzip 魔数要"看一眼再放回去"：tar 流的头两字节得先读出来才知道要不要套 GZIPInputStream。
        val pushback = try {
            PushbackInputStream(BufferedInputStream(FileInputStream(file), 1 shl 20), 2)
        } catch (e: IOException) {
            throw ExtractException("打不开 ${file.absolutePath}：${e.message}")
        }
        val magic = ByteArray(2)
        val read = pushback.read(magic)
        if (read > 0) pushback.unread(magic, 0, read)
        return if (read == 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) {
            GZIPInputStream(pushback, 1 shl 20)
        } else {
            pushback
        }
    }

    /**
     * tar 条目遍历。`visit` 返回 true 表示"够了，别往下走了"。
     *
     * 处理了三种"名字/长度不在固定字段里"的情况 —— 少一种就会把正常包读错：
     *   · ustar 的 `prefix` 字段（长路径被拆成 prefix + "/" + name）；
     *   · GNU 的 `L` 类型条目（超长名字单独存一条，作用于**下一条**）；
     *   · pax 的 `x` 条目（`path=` / `size=` 覆盖下一条）。
     * 每个分支自己负责把数据段与补位跳干净 —— 否则下一个 512 字节头会从数据中间开始读，
     * 表现是"条目名是一堆乱码"。
     */
    private inline fun walkTar(
        stream: InputStream,
        namesOut: MutableList<String>,
        visit: (entryName: String, size: Long, input: InputStream) -> Boolean,
    ) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var pendingPaxPath: String? = null
        var pendingPaxSize: Long? = null

        while (true) {
            if (!readTarHeader(stream, header)) return
            if (header.all { it == 0.toByte() }) return // 全零块 = 归档结束
            val size = tarNumber(header, 124, 12)
            if (size < 0L) throw ExtractException("tar 头里的长度字段不是合法的八进制数 —— 包损坏？")
            val type = header[156].toInt().toChar()
            val rawName = tarName(header)

            when (type) {
                'L' -> { // GNU 长文件名：名字存在数据段里
                    val bytes = readExact(stream, size, "GNU 长文件名条目")
                    pendingLongName = bytes.decodeToString().trimEnd('\u0000', '\n')
                    skipFully(stream, tarPadding(size), rawName)
                }

                'x', 'g' -> { // pax 扩展头
                    val bytes = readExact(stream, size, "pax 扩展头")
                    for (line in bytes.decodeToString().split('\n')) {
                        val eq = line.indexOf('=')
                        val space = line.indexOf(' ')
                        if (eq <= 0 || space < 0 || space > eq) continue
                        val key = line.substring(space + 1, eq)
                        val value = line.substring(eq + 1)
                        when (key) {
                            "path" -> pendingPaxPath = value
                            "size" -> pendingPaxSize = value.toLongOrNull()
                        }
                    }
                    skipFully(stream, tarPadding(size), rawName)
                }

                '0', '\u0000', '7' -> { // 常规文件
                    val entryName = pendingPaxPath ?: pendingLongName ?: rawName
                    val entrySize = pendingPaxSize ?: size
                    pendingLongName = null; pendingPaxPath = null; pendingPaxSize = null
                    rememberName(namesOut, entryName)
                    val stop = visit(entryName, entrySize, stream)
                    skipFully(stream, tarPadding(entrySize), entryName)
                    if (stop) return
                }

                '5' -> { // 目录：没有数据段
                    val entryName = pendingPaxPath ?: pendingLongName ?: rawName
                    pendingLongName = null; pendingPaxPath = null; pendingPaxSize = null
                    rememberName(namesOut, entryName)
                }

                else -> {
                    // 硬链接/软链接（'1' '2'）、设备节点（'3' '4'）、未知类型：数据段直接跳过。
                    pendingLongName = null; pendingPaxPath = null; pendingPaxSize = null
                    skipFully(stream, size + tarPadding(size), rawName)
                }
            }
        }
    }

    private fun rememberName(names: MutableList<String>, name: String) {
        // 只用于报错文案，所以封顶；畸形包塞十万个条目也不该把内存吃光。
        if (names.size < MAX_LISTED_ENTRIES * 4) names.add(name)
    }

    private fun readTarHeader(stream: InputStream, header: ByteArray): Boolean {
        var read = 0
        while (read < 512) {
            val n = stream.read(header, read, 512 - read)
            if (n < 0) {
                if (read == 0) return false
                throw ExtractException("tar 头只读到 $read 字节就断了 —— 包被截断（下载没完成？）")
            }
            read += n
        }
        return true
    }

    private fun tarPadding(size: Long): Long = if (size % 512L == 0L) 0L else 512L - (size % 512L)

    private fun tarName(header: ByteArray): String {
        val name = tarField(header, 0, 100)
        val prefix = if (header.size >= 265 && header[257] == 'u'.code.toByte()) {
            tarField(header, 345, 155)
        } else {
            ""
        }
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun tarField(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = min(offset + length, header.size)
        while (end < limit && header[end] != 0.toByte()) end++
        return header.decodeToString(offset, end)
    }

    /**
     * tar 的数字字段：正常是八进制 ASCII（尾部 NUL/空格），但 GNU 对 >8 GiB 的条目
     * 会用 base-256（首字节最高位置 1，后面按大端拼）。不认后者会把 super.img
     * 的长度读成一个天文数字或负数。
     */
    private fun tarNumber(header: ByteArray, offset: Int, length: Int): Long {
        if ((header[offset].toInt() and 0x80) != 0) {
            var value = header[offset].toLong() and 0x7F
            for (i in 1 until length) value = (value shl 8) or (header[offset + i].toLong() and 0xFF)
            return value
        }
        var value = 0L
        var seen = false
        for (i in offset until offset + length) {
            val c = header[i].toInt()
            if (c == 0 || c == ' '.code) {
                if (seen) break else continue
            }
            if (c !in '0'.code..'7'.code) return -1L
            value = (value shl 3) or (c - '0'.code).toLong()
            seen = true
        }
        return value
    }

    // ─────────────────────────── 数据读取抽象 ───────────────────────────

    /** 按 payload.bin **自身**的偏移空间读取（0 = "CrAU" 的 C）。 */
    private interface PayloadReader {
        fun read(offset: Long, length: Int): ByteArray
    }

    /** stored 存放：想读哪就读哪。 */
    private class StoredPayloadReader(
        private val raf: RandomAccessFile,
        private val payloadStart: Long,
    ) : PayloadReader, Closeable {
        override fun read(offset: Long, length: Int): ByteArray =
            readAt(raf, payloadStart + offset, length)

        override fun close() = Unit // raf 由调用方的 use 关，这里不能抢着关
    }

    /**
     * deflate 存放的 `payload.bin`：**只能顺序往前读**。
     *
     * 为什么不能 seek：deflate 是流式格式，第 N 个字节在哪只有解过前 N-1 个字节才知道。
     * 所以这里维护一个"已经解到哪"的位置，允许往前跳过（丢掉中间的字节），
     * 但调用方一旦要回头读，就明确报错 —— 而不是悄悄返回错位的字节。
     * 真实 OTA 里 payload.bin 几乎都是 stored（把一个 6 GB 的条目 deflate 没有意义），
     * 但用户手上的包什么都有，所以这条路径必须能跑，而且必须响亮。
     */
    private class ZipDeflatePayloadReader(
        raf: RandomAccessFile,
        dataStart: Long,
        compressedSize: Long,
    ) : PayloadReader, Closeable {
        private val stream = InflaterInputStream(
            BufferedInputStream(BoundedRandomAccessStream(raf, dataStart, compressedSize), 1 shl 16),
            Inflater(true), // 见 readZipEntry：ZIP 的 deflate 是裸流，没有 zlib 头
        )
        private var position = 0L

        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < position) {
                throw ExtractException(
                    "`payload.bin` 是 deflate 压缩存放的，只能顺序读；但清单要求回到第 $offset 字节" +
                        "（已经读到 $position）—— 这份包的 install operation 不是按数据偏移升序排的，" +
                        "本工具无法处理。请换一份 payload.bin 未压缩存放的完整包。"
                )
            }
            try {
                skipFully(stream, offset - position, "payload.bin 压缩流")
                val out = readExact(stream, length.toLong(), "payload.bin 第 $offset 字节处的 $length 字节")
                // **position 是"读完之后的流位置"**，不是这次请求的起始偏移。
                // 这里原来写的是 position = offset：于是第二次读（清单，偏移 24）会
                // 以为"还在 0"，白跳过 24 字节 —— 清单从第 24 字节开始读，
                // 分区表只剩后半截（实测表现：37 个分区变成只剩 init_boot）。
                // 真实 OTA 的 payload.bin 全是 stored，这条路径只有造包 fixture 能覆盖。
                position = offset + out.size
                return out
            } catch (e: IOException) {
                // 不要把 ZipException("incorrect header check") 这种话直接丢给用户
                throw ExtractException(
                    "`payload.bin` 是 deflate 压缩存放的，解压流在这里断了：${e.message}" +
                        "（压缩数据损坏，或者它其实不是 deflate 流）"
                )
            }
        }

        override fun close() = stream.close()
    }

    /** 把 `RandomAccessFile` 的一段包成 `InputStream`（InflaterInputStream 要的是流）。 */
    private class BoundedRandomAccessStream(
        private val raf: RandomAccessFile,
        private val start: Long,
        private val length: Long,
    ) : InputStream() {
        private var consumed = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= length) return -1
            val want = min(len.toLong(), length - consumed).toInt()
            // **每次都要显式 seek**：这个 raf 是和"读本地头 / 读中央目录"共用的，
            // 文件指针早就不知道飘到哪了。当初漏了这一步，deflate 的 payload 就从
            // 本地头后面 200 多字节处开始当压缩流读，Inflater 报 "invalid code lengths set"
            // —— 是 fixture 用例（真实 OTA 里没有 deflate 的 payload）把它抓出来的。
            raf.seek(start + consumed)
            val n = raf.read(b, off, want)
            if (n > 0) consumed += n
            return n
        }

        override fun close() = Unit // raf 归调用方
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private fun openReadOnly(file: File): RandomAccessFile = try {
        RandomAccessFile(file, "r")
    } catch (e: IOException) {
        throw ExtractException("打不开 ${file.absolutePath}：${e.message}（权限？文件被删了？）")
    }

    private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
        if (offset < 0L || length < 0) {
            throw ExtractException("内部错误：越界读取（offset=$offset length=$length）")
        }
        val out = ByteArray(length)
        try {
            raf.seek(offset)
            raf.readFully(out)
        } catch (e: IOException) {
            throw ExtractException(
                "读文件第 $offset 字节起的 $length 字节失败：${e.message} —— 包被截断（下载没完成？）"
            )
        }
        return out
    }

    private fun readExact(stream: InputStream, count: Long, what: String): ByteArray {
        if (count < 0L || count > MAX_IMAGE_BYTES) {
            throw ExtractException("$what 声明 $count 字节，超出本工具上限。")
        }
        val out = ByteArray(count.toInt())
        var read = 0
        while (read < out.size) {
            val n = stream.read(out, read, out.size - read)
            if (n < 0) {
                throw ExtractException("读 $what 时提前遇到文件结尾（读到 $read / ${out.size} 字节）—— 包被截断？")
            }
            read += n
        }
        return out
    }

    private fun InputStream.readCapped(count: Long, what: String): ByteArray = readExact(this, count, what)

    private fun skipFully(stream: InputStream, count: Long, what: String) {
        var remaining = count
        val buffer = ByteArray(1 shl 16)
        while (remaining > 0L) {
            val n = stream.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (n < 0) {
                throw ExtractException("跳过 $what 之后的数据时提前遇到结尾（还差 $remaining 字节）—— 包被截断？")
            }
            remaining -= n
        }
    }

    private fun listForMessage(names: List<String>): String {
        val head = names.take(MAX_LISTED_ENTRIES).joinToString(", ")
        return if (names.size > MAX_LISTED_ENTRIES) {
            "$head …（还有 ${names.size - MAX_LISTED_ENTRIES} 个）"
        } else {
            head
        }
    }

    private fun ByteArray.u16le(pos: Int): Int =
        (this[pos].toInt() and 0xFF) or ((this[pos + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32le(pos: Int): Long =
        (this[pos].toLong() and 0xFF) or ((this[pos + 1].toLong() and 0xFF) shl 8) or
            ((this[pos + 2].toLong() and 0xFF) shl 16) or ((this[pos + 3].toLong() and 0xFF) shl 24)

    /**
     * 把解出来的字节按目标区间写进镜像。
     *
     * extent 是**块**为单位（`numBlocks * blockSize`），不是字节 —— 少乘一次
     * 就会把 boot 镜像写成一团错位的数据，而那种镜像要刷进去才发现坏。
     */
    private fun writeExtents(
        image: ByteArray,
        partition: String,
        data: ByteArray,
        extents: List<PayloadBinUtils.Extent>,
        blockSize: Long,
    ) {
        var consumed = 0
        for (extent in extents) {
            val length = (extent.numBlocks * blockSize).toInt()
            val start = (extent.startBlock * blockSize).toInt()
            if (start < 0 || length < 0 || start + length > image.size) {
                throw ExtractException(
                    "分区 `$partition` 的目标区间 [$start, ${start + length}) 超出镜像长度 ${image.size} —— 清单坏了。"
                )
            }
            val available = min(length, data.size - consumed)
            if (available > 0) {
                System.arraycopy(data, consumed, image, start, available)
                consumed += available
            }
        }
        if (consumed != data.size) {
            throw ExtractException(
                "分区 `$partition` 解出 ${data.size} 字节，但目标区间只放得下 $consumed 字节 —— 清单与数据对不上。"
            )
        }
    }

    // ─────────────────────────── 外部解码器（zstd / bzip2 / xz 兜底） ───────────────────────────

    /**
     * 为什么要调外部命令：本工程刻意不引入 zstd / bzip2 / commons-compress 依赖
     * （见 `KernelDecompressor` 头部），而 `REPLACE_ZSTD` 在真实完整包里是**主力**
     * 操作类型。与其手写一个可能悄悄解错的解码器，不如调参考实现 —— 前提是
     * ①找得到它、②解出来的尺寸必须与清单完全一致、③找不到就明确报错。
     */
    private object CliCodec {

        enum class Tool(
            val label: String,
            val args: List<String>,
            val envKeys: List<String>,
            val knownPaths: List<String>,
        ) {
            ZSTD(
                label = "zstd",
                args = listOf("-d", "-c", "--no-progress"),
                envKeys = listOf("KSU_ZSTD_BIN", "ZSTD_BIN"),
                // 本机（Termux / dsh 构建）的 zstd 就在这里；PATH 里通常也有，
                // 但应用进程的 PATH 往往被清空，所以必须显式列出来。
                knownPaths = listOf(
                    "/data/data/com.dsharnessmobile.shell/files/usr/bin/zstd",
                    "/system/bin/zstd",
                    "/vendor/bin/zstd",
                    "/data/adb/ksu/bin/zstd",
                ),
            ),
            BZIP2(
                label = "bzip2",
                args = listOf("-d", "-c"),
                envKeys = listOf("KSU_BZIP2_BIN", "BZIP2_BIN"),
                knownPaths = listOf("/system/bin/bzip2", "/vendor/bin/bzip2", "/data/adb/ksu/bin/bzip2"),
            ),
            XZ(
                label = "xz",
                args = listOf("-d", "-c", "--no-warn"),
                envKeys = listOf("KSU_XZ_BIN", "XZ_BIN"),
                knownPaths = listOf("/system/bin/xz", "/vendor/bin/xz", "/data/adb/ksu/bin/xz"),
            ),
        }

        /** 找解码器：环境变量 → 系统属性 → PATH → 常见绝对路径。找不到返回 null。 */
        fun locate(tool: Tool): File? {
            for (key in tool.envKeys) {
                val value = System.getenv(key)
                if (!value.isNullOrBlank() && File(value).canExecute()) return File(value)
            }
            for (key in tool.envKeys) {
                val value = System.getProperty(key.lowercase().replace('_', '.'))
                if (!value.isNullOrBlank() && File(value).canExecute()) return File(value)
            }
            val path = System.getenv("PATH").orEmpty()
            for (dir in path.split(File.pathSeparatorChar)) {
                if (dir.isBlank()) continue
                val candidate = File(dir, tool.label)
                if (candidate.isFile && candidate.canExecute()) return candidate
            }
            val prefix = System.getenv("PREFIX").orEmpty()
            if (prefix.isNotBlank()) {
                val candidate = File(prefix, "bin/${tool.label}")
                if (candidate.isFile && candidate.canExecute()) return candidate
            }
            for (known in tool.knownPaths) {
                val candidate = File(known)
                if (candidate.isFile && candidate.canExecute()) return candidate
            }
            return null
        }

        fun run(
            tool: Tool,
            data: ByteArray,
            expectedSize: Int,
            label: String,
            whyItFailed: String?,
        ): ByteArray {
            val binary = locate(tool)
            val reasonPrefix = if (whyItFailed.isNullOrBlank()) "" else "$whyItFailed，"
            if (binary == null) {
                throw ExtractException(
                    "$label 需要 ${tool.label} 解码器，但这台机器上找不到它。\n" +
                        reasonPrefix +
                        "找过的位置：环境变量 ${tool.envKeys.joinToString(" / ")}、PATH 的每个目录、" +
                        "以及 ${tool.knownPaths.joinToString(", ")}。\n" +
                        "装一个（Termux：`pkg install ${tool.label}`），" +
                        "或者用环境变量 ${tool.envKeys.first()} 指到可执行文件上即可。\n" +
                        "本工具**不会**跳过这个操作继续解 —— 那会产出一份缺了半截的 boot 镜像。"
                )
            }

            val process = try {
                ProcessBuilder(listOf(binary.absolutePath) + tool.args).start()
            } catch (e: IOException) {
                throw ExtractException(
                    "找到 ${tool.label}（${binary.absolutePath}）但调不起来：${e.message}。" +
                        "应用沙箱里可能读不到别的应用目录 —— 换一个当前用户可执行的路径。"
                )
            }

            // stderr 必须有人收，否则子进程写满管道会卡住（死锁）。
            val stderr = ByteArrayOutputStream()
            val stderrThread = Thread {
                try {
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val n = process.errorStream.read(buffer)
                        if (n < 0) break
                        if (total < 8192) {
                            stderr.write(buffer, 0, min(n, 8192 - total))
                            total += n
                        }
                    }
                } catch (_: IOException) {
                }
            }
            stderrThread.isDaemon = true
            stderrThread.start()

            // 压缩数据可能几 MiB 到几十 MiB，写入必须与"读 stdout"并行，否则管道写满即死锁。
            val writerThread = Thread {
                try {
                    process.outputStream.use { it.write(data) }
                } catch (_: IOException) {
                    // 解码器提前退出（数据坏了）会让这里 EPIPE —— 真正的原因在退出码与 stderr 里。
                }
            }
            writerThread.isDaemon = true
            writerThread.start()

            val out = ByteArrayOutputStream(min(expectedSize.coerceAtLeast(1 shl 16), 1 shl 22))
            val readFailure = try {
                process.inputStream.use { it.copyTo(out, 1 shl 16) }
                null
            } catch (e: IOException) {
                e
            }
            writerThread.join()
            val exit = process.waitFor()
            stderrThread.join(1000)

            if (readFailure != null) {
                throw ExtractException("读 ${tool.label} 的输出时出错：${readFailure.message}")
            }
            if (exit != 0) {
                val message = stderr.toString(Charsets.UTF_8.name()).trim().take(500)
                throw ExtractException(
                    "$label 用 ${tool.label} 解压失败（退出码 $exit）。" +
                        (if (whyItFailed.isNullOrBlank()) "" else "$whyItFailed。") +
                        (if (message.isEmpty()) "" else "\n${tool.label} 说：$message")
                )
            }
            return out.toByteArray()
        }
    }
}
