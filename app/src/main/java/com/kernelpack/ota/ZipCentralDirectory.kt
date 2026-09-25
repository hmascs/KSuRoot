/*
 * 出处：YuKongA/ghostlock-app（Apache-2.0）
 *   app/src/main/kotlin/com/ghostlock/app/data/ota/ZipFileUtils.kt
 * 本文件在该实现之上做了三处**只加不改**的改动，均已在下方注释标出：
 *   ① 对象改名为 `ZipCentralDirectory`（原 `ZipFileUtils` 太泛）；
 *   ② 增加 [BOOT_ENTRY_CANDIDATES] 与 [isStored] 两个小工具；
 *   ③ 中央目录 / 条目字段的边界检查补齐（原实现有几处依赖调用方先校验）。
 * 许可证见 app/src/main/assets/GL_LICENSE_Apache2.txt
 */

package com.kernelpack.ota

/**
 * 只读 ZIP 结构解析：**从任意一段字节里**定位中央目录、读出指定条目的元信息。
 *
 * 为什么不用 `java.util.zip.ZipFile`：那是给**本地文件**用的，它要求整包在手。
 * 这里要解析的是**远端几 GB 的完整包**，只拿到尾部 64 KiB + 中央目录那一段，
 * 所以必须自己按 ZIP 规范走。
 */
object ZipCentralDirectory {

    private const val CENSIG = 0x02014b50L         // "PK\001\002" 中央目录文件头
    private const val LOCSIG = 0x04034b50L         // "PK\003\004" 本地文件头
    private const val ENDSIG = 0x06054b50L         // "PK\005\006" 中央目录结束记录
    private const val ENDHDR = 22                  // EOCD 最小长度
    private const val ZIP64_ENDSIG = 0x06064b50L   // "PK\006\006" ZIP64 中央目录结束记录
    private const val ZIP64_LOCSIG = 0x07064b50L   // "PK\006\007" ZIP64 定位器
    private const val ZIP64_LOCHDR = 20            // 定位器长度
    private const val ZIP64_MAGICVAL = 0xFFFFFFFFL // Zip64 字段的"去看扩展区"标记

    /**
     * 包内可能装着 boot 镜像的条目名，按**优先级**排列。
     *
     * - `payload.bin`：A/B 完整包（Google / 小米 fastboot 包），boot 分区在里面；
     * - `boot.img` / `init_boot.img`：recovery 风格完整包**直接**放镜像；
     * - `images/…`：小米 fastboot 包把它们放在 `images/` 子目录下。
     *
     * 顺序即优先级：`payload.bin` 优先，因为它是唯一带**分区清单**的形态，
     * 能同时拿到 `xbl_config`。
     */
    val BOOT_ENTRY_CANDIDATES: List<String> = listOf(
        "payload.bin",
        "boot.img", "images/boot.img",
        "init_boot.img", "images/init_boot.img",
    )

    /** 条目是否**未压缩存储**。`payload.bin` 必须是 Stored，否则无法按偏移随机读。 */
    fun isStored(entry: CdEntry): Boolean = entry.method == 0

    data class CentralDirectory(val offset: Long, val size: Long)

    /**
     * 从**文件尾部的一段字节**里找中央目录的位置。
     *
     * @param bytes 尾部字节（[tailSize] 那么多）
     * @param fileLength 远端文件总长，用来把 ZIP64 记录的文件绝对偏移换算成缓冲内下标
     */
    fun locateCentralDirectory(bytes: ByteArray, fileLength: Long): CentralDirectory {
        val searchStartPos = bytes.size - ENDHDR
        var cenSize = -1L
        var cenOffset = -1L

        for (currentScanPos in searchStartPos downTo 0) {
            // ③ 边界：尾部字节可能被截得比 EOCD 还短
            if (currentScanPos < 0 || currentScanPos + 4 > bytes.size) continue
            if ((bytes.getIntLe(currentScanPos).toLong() and 0xFFFFFFFFL) == ENDSIG) {
                val cenDirOffsetFieldPos = currentScanPos + 16
                val cenDirSizeFieldPos = currentScanPos + 12
                if (cenDirOffsetFieldPos + 4 > bytes.size) continue

                val offsetOfCentralDir = bytes.getIntLe(cenDirOffsetFieldPos).toLong() and 0xFFFFFFFFL
                val sizeOfCentralDir = bytes.getIntLe(cenDirSizeFieldPos).toLong() and 0xFFFFFFFFL

                if (offsetOfCentralDir == ZIP64_MAGICVAL || sizeOfCentralDir == ZIP64_MAGICVAL) {
                    // 完整包几乎一定 > 4 GiB，所以 ZIP64 是**常态**而不是例外
                    val zip64LocatorPos = currentScanPos - ZIP64_LOCHDR
                    if (zip64LocatorPos >= 0 &&
                        (bytes.getIntLe(zip64LocatorPos).toLong() and 0xFFFFFFFFL) == ZIP64_LOCSIG
                    ) {
                        val zip64EocdRecordOffsetInFile = bytes.getLongLe(zip64LocatorPos + 8)
                        val zip64EocdRecordOffsetInBuffer =
                            bytes.size - (fileLength - zip64EocdRecordOffsetInFile).toInt()
                        if (zip64EocdRecordOffsetInBuffer >= 0 &&
                            (zip64EocdRecordOffsetInBuffer + 56) <= bytes.size &&
                            (bytes.getIntLe(zip64EocdRecordOffsetInBuffer).toLong() and 0xFFFFFFFFL) == ZIP64_ENDSIG
                        ) {
                            cenSize = bytes.getLongLe(zip64EocdRecordOffsetInBuffer + 40)
                            cenOffset = bytes.getLongLe(zip64EocdRecordOffsetInBuffer + 48)
                            break
                        }
                    }
                } else {
                    cenSize = sizeOfCentralDir
                    cenOffset = offsetOfCentralDir
                    break
                }
            }
        }
        return CentralDirectory(cenOffset, cenSize)
    }

    data class CdEntry(
        val fileName: String,
        val localHeaderOffset: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val method: Int,
    )

    /** 在中央目录字节里挑出 [fileNames] 里出现过的条目；没出现的不会在返回值里。 */
    fun locateEntries(bytes: ByteArray, fileNames: Set<String>): Map<String, CdEntry> {
        val results = HashMap<String, CdEntry>(fileNames.size)
        var pos = 0
        while (pos + 46 <= bytes.size) {
            if ((bytes.getIntLe(pos).toLong() and 0xFFFFFFFFL) != CENSIG) break

            val method = bytes.getShortLe(pos + 10).toInt() and 0xFFFF
            var compressedSize = bytes.getIntLe(pos + 20).toLong() and 0xFFFFFFFFL
            var uncompressedSize = bytes.getIntLe(pos + 24).toLong() and 0xFFFFFFFFL
            val fileNameLength = bytes.getShortLe(pos + 28).toInt() and 0xFFFF
            val extraFieldLength = bytes.getShortLe(pos + 30).toInt() and 0xFFFF
            val fileCommentLength = bytes.getShortLe(pos + 32).toInt() and 0xFFFF
            var localHeaderOffset = bytes.getIntLe(pos + 42).toLong() and 0xFFFFFFFFL

            val fileNameStartPos = pos + 46
            if (fileNameStartPos + fileNameLength > bytes.size) break

            val currentFileName = bytes.decodeToString(fileNameStartPos, fileNameStartPos + fileNameLength)
            if (currentFileName in fileNames) {
                val extraStart = fileNameStartPos + fileNameLength
                val extraEnd = minOf(extraStart + extraFieldLength, bytes.size)
                if (uncompressedSize == ZIP64_MAGICVAL ||
                    compressedSize == ZIP64_MAGICVAL ||
                    localHeaderOffset == ZIP64_MAGICVAL
                ) {
                    // Zip64 扩展区（id 0x0001）：三个字段按**固定顺序**只补那些为 magic 的
                    var extraPos = extraStart
                    while (extraPos + 4 <= extraEnd) {
                        val id = bytes.getShortLe(extraPos).toInt() and 0xFFFF
                        val size = bytes.getShortLe(extraPos + 2).toInt() and 0xFFFF
                        val dataStart = extraPos + 4
                        if (dataStart + size > extraEnd) break
                        if (id == 0x0001) {
                            var fieldPos = dataStart
                            if (uncompressedSize == ZIP64_MAGICVAL && fieldPos + 8 <= dataStart + size) {
                                uncompressedSize = bytes.getLongLe(fieldPos); fieldPos += 8
                            }
                            if (compressedSize == ZIP64_MAGICVAL && fieldPos + 8 <= dataStart + size) {
                                compressedSize = bytes.getLongLe(fieldPos); fieldPos += 8
                            }
                            if (localHeaderOffset == ZIP64_MAGICVAL && fieldPos + 8 <= dataStart + size) {
                                localHeaderOffset = bytes.getLongLe(fieldPos)
                            }
                            break
                        }
                        extraPos = dataStart + size
                    }
                }
                results[currentFileName] = CdEntry(
                    fileName = currentFileName,
                    localHeaderOffset = localHeaderOffset,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    method = method,
                )
                if (results.size == fileNames.size) break
            }
            pos = fileNameStartPos + fileNameLength + extraFieldLength + fileCommentLength
        }
        return results
    }

    /**
     * 本地文件头里"数据真正开始"的相对偏移（= 30 + 文件名长 + 扩展区长）。
     *
     * 中央目录里的 `localHeaderOffset` 指向**本地头**，不是数据；两者之间还隔着
     * 文件名与扩展区，长度只有读到那 30 字节才知道。少了这一步会从文件名的
     * 第 30 个字节开始当 payload 读，表现是"魔数不对"。
     */
    fun locateLocalFileOffset(bytes: ByteArray): Long {
        if (bytes.size >= 30 && (bytes.getIntLe(0).toLong() and 0xFFFFFFFFL) == LOCSIG) {
            val fileNameLength = bytes.getShortLe(26).toInt() and 0xFFFF
            val extraFieldLength = bytes.getShortLe(28).toInt() and 0xFFFF
            return (30L + fileNameLength + extraFieldLength)
        }
        return -1L
    }

    private fun ByteArray.getIntLe(pos: Int): Int =
        (this[pos].toInt() and 0xFF) or ((this[pos + 1].toInt() and 0xFF) shl 8) or
            ((this[pos + 2].toInt() and 0xFF) shl 16) or ((this[pos + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.getShortLe(pos: Int): Short =
        ((this[pos].toInt() and 0xFF) or ((this[pos + 1].toInt() and 0xFF) shl 8)).toShort()

    private fun ByteArray.getLongLe(pos: Int): Long =
        (getIntLe(pos).toLong() and ZIP64_MAGICVAL) or (getIntLe(pos + 4).toLong() shl 32)
}
