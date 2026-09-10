package com.kernelpack.kallsyms

import com.kernelpack.model.KallsymsLayout
import com.kernelpack.model.KernelSymbol

/** kallsyms 结构定位失败（可安全回退到其它策略）。 */
class KallsymsNotFoundException(message: String) : Exception(message)

/** 定位参数。 */
class KallsymsOptions(
    /** 强制 32/64 位；null = 自动探测。 */
    val bitSize: Int? = null,
    /** 假定地址表存的是绝对地址（禁用 base-relative 猜测）。 */
    val useAbsolute: Boolean = false,
    /** 强制指定内核链接基址。 */
    val baseAddress: Long? = null,
    val log: (String) -> Unit = {},
)

/** 定位 + 解码结果。 */
class KallsymsResult(
    val versionString: String,
    val versionNumber: String,
    val architecture: String,
    val is64Bits: Boolean,
    val isBigEndian: Boolean,
    val baseAddress: Long,
    val layout: KallsymsLayout,
    val symbols: List<KernelSymbol>,
    val log: List<String>,
)

/**
 * kallsyms 符号表定位器 —— `symbols.js`(`KallsymsFinder`) 的 Kotlin 移植。
 *
 * 算法来源于 marin-m/vmlinux-to-elf 的 `core/kallsyms.py`：内核把符号名切成 token 压缩存放，
 * 因此只要在镜像里逆向找出 `kallsyms_token_table` / `token_index` / `markers` / `names` /
 * `num_syms` / `addresses`(或 `offsets`) 这六张表的位置，就能把 10 万+ 个符号的
 * **名字 → 链接地址** 完整还原出来。有了它，`<符号名>` 到 `<相对 KIMAGE_TEXT_BASE 的偏移>`
 * 的换算就是一次减法。
 *
 * 与 JS 版的差异（均为增强，不影响常规路径结果）：
 *  - 架构探测直接扫字节，不构造 100MB 的 latin1 字符串（快得多）；
 *  - 真正实现了 uncompressed 回退路径（JS 版那两个方法不存在，回退是死代码）；
 *  - 记录日志到列表，便于上层展示。
 */
class KallsymsFinder(private val img: ByteArray, private val opts: KallsymsOptions = KallsymsOptions()) {

    private val r = ByteReader(img)
    private val logs = ArrayList<String>()

    private fun log(msg: String) {
        logs.add(msg)
        opts.log(msg)
    }

    private var is64Bits: Boolean = opts.bitSize?.let { it == 64 } ?: false
    private var is64BitsKnown: Boolean = opts.bitSize != null
    private var isBigEndian: Boolean = false
    private var architecture: String = "unknown"

    private val overrideRelativeBase: Boolean = opts.useAbsolute
    private val explicitBaseAddress: Long? = opts.baseAddress
    private var kernelTextCandidate: Long? = null

    private var tokenTableOffset: Int = -1
    private var tokenIndexOffset: Int = -1
    private var tokenIndexEndOffset: Int = -1
    private var markersOffset: Int = -1
    private var namesOffset: Int = -1
    private var numSymsOffset: Int = -1
    private var addressesOffset: Int = -1
    private var offsetTableElementSize: Int = 0
    private var uncompressedKallsyms: Boolean = false

    private var versionString: String = ""
    private var versionNumber: String = ""

    private var numSymbols: Int = 0
    private var hasBaseRelative: Boolean = false
    private var hasAbsolutePercpu: Boolean = false
    private var relativeBaseAddress: Long = 0
    private var kernelAddresses: LongArray = LongArray(0)

    // ---------------------------------------------------------------- 入口

    fun run(): KallsymsResult {
        findLinuxKernelVersion()
        guessArchitecture()

        try {
            findKallsymsTokenTable()
            findKallsymsTokenIndex()
            uncompressedKallsyms = false
        } catch (first: KallsymsNotFoundException) {
            try {
                findKallsymsNamesUncompressed()
                findKallsymsMarkersUncompressed()
                uncompressedKallsyms = true
            } catch (_: Throwable) {
                throw first
            }
        }

        if (!uncompressedKallsyms) {
            findKallsymsMarkers()
            findKallsymsNames()
        }
        findKallsymsNumSyms()
        findKallsymsAddressesOrSymbols()
        val symbols = parseSymbolTable()

        var base = kernelTextCandidate
        if (base == null) {
            base = inferBaseAddressFromSyms(symbols)
        }
        if (explicitBaseAddress != null) base = explicitBaseAddress

        val layout = KallsymsLayout(
            tokenTable = tokenTableOffset.toLong(),
            tokenIndex = tokenIndexOffset.toLong(),
            tokenIndexEnd = tokenIndexEndOffset.toLong(),
            markers = markersOffset.toLong(),
            names = namesOffset.toLong(),
            numSyms = numSymsOffset.toLong(),
            addresses = addressesOffset.toLong(),
            offsetTableElementSize = offsetTableElementSize,
            hasBaseRelative = hasBaseRelative,
            hasAbsolutePercpu = hasAbsolutePercpu,
            relativeBaseAddress = if (hasBaseRelative) relativeBaseAddress else null,
        )
        return KallsymsResult(
            versionString = versionString,
            versionNumber = versionNumber,
            architecture = architecture,
            is64Bits = is64Bits,
            isBigEndian = isBigEndian,
            baseAddress = base,
            layout = layout,
            symbols = symbols,
            log = logs.toList(),
        )
    }

    // ---------------------------------------------------- 步骤 1：内核版本

    private fun findLinuxKernelVersion() {
        // 找 "Linux version <X.Y.Z> " 这一行（限制在镜像前 32MB 内，实际总在很靠前的位置）
        // 整图扫描：banner 可能落在很靠后的位置（实测 GKI 5.15 在 32.4MB 处），
        // symbols.js 的正则也是扫全图，这里保持一致。
        val limit = img.size
        val needle = "Linux version ".toByteArray(Charsets.US_ASCII)
        var at = -1
        var scan = 0
        while (scan < limit) {
            val p = r.find(needle, scan)
            if (p < 0 || p + needle.size >= limit) break
            // 内核里还有一个 printf 格式串 `Linux version %s (%s)`，必须要求后面紧跟版本号，
            // 否则会误命中（与 symbols.js 的正则 `Linux version (\d+\.\d...)` 行为一致）。
            val c = img[p + needle.size].toInt() and 0xff
            if (c in 0x30..0x39) { at = p; break }
            scan = p + 1
        }
        if (at < 0) throw IllegalStateException("镜像里找不到 `Linux version` 字符串，可能不是内核 Image")

        val sb = StringBuilder()
        var i = at
        while (i < img.size && sb.length < 512) {
            val b = img[i].toInt() and 0xff
            if (b == 0 || b < 0x20 || b > 0x7e) break
            sb.append(b.toChar())
            i++
        }
        versionString = sb.toString()

        val m = Regex("Linux version (\\d+\\.[\\d.]*\\d)").find(versionString)
            ?: throw IllegalStateException("无法从 `$versionString` 解析内核版本号")
        versionNumber = m.groupValues[1]
        log("[+] Version string: $versionString")
    }

    // ---------------------------------------------------- 步骤 2：架构探测

    private fun guessArchitecture() {
        val guess = guessArchitectureFromImage(img)
        if (guess != null) {
            architecture = guess.name
            if (!is64BitsKnown) {
                is64Bits = guess.is64Bit
                is64BitsKnown = true
            }
            isBigEndian = guess.isBigEndian
            log("[+] Guessed architecture: ${guess.display}")
        } else if (!is64BitsKnown) {
            throw IllegalStateException("无法识别架构（需要 >= 100 个函数序言特征匹配）")
        }
    }

    // ------------------------------------------ 步骤 3：kallsyms_token_table

    private fun findKallsymsTokenTable() {
        // 连续块 "0\0 1\0 2\0 ... 9\0"
        val sequence = ByteArray(20)
        for (i in 0 until 10) {
            sequence[i * 2] = (0x30 + i).toByte()
            sequence[i * 2 + 1] = 0
        }
        val avoid = listOf(
            ByteReader.bytes(0x3a, 0x00),
            ByteReader.bytes(0x00, 0x00),
            ByteReader.bytes(0x00, 0x01),
            ByteReader.bytes(0x00, 0x02),
            ByteReader.bytes(0x41, 0x53, 0x43, 0x49, 0x49, 0x00),
        )

        val candidates = ArrayList<Int>()
        val followedByAscii = ArrayList<Int>()
        var position = 0
        while (true) {
            position = r.find(sequence, position + 1)
            if (position < 0) break
            val tail = position + sequence.size
            var avoidHit = false
            for (seq in avoid) {
                if (tail + seq.size > img.size) continue
                var ok = true
                for (k in seq.indices) {
                    if (img[tail + k] != seq[k]) { ok = false; break }
                }
                if (ok) { avoidHit = true; break }
            }
            if (avoidHit) continue
            candidates.add(position)
            if (tail < img.size) {
                val ch = img[tail].toInt() and 0xff
                if ((ch in 0x30..0x39) || (ch in 0x41..0x5a) || (ch in 0x61..0x7a)) {
                    followedByAscii.add(position)
                }
            }
        }

        var chosen = candidates
        if (candidates.size != 1) {
            if (followedByAscii.size == 1) {
                chosen = followedByAscii
            } else if (candidates.isEmpty()) {
                throw KallsymsNotFoundException("在内核镜像里找到 0 个 kallsyms_token_table 候选")
            } else {
                throw IllegalStateException("在内核镜像里找到 ${candidates.size} 个 kallsyms_token_table 候选，无法定位")
            }
        }
        position = chosen[0]

        // 从 '0' 往前回退 256-0x30 个 token
        var pos = position - 1
        if (pos < 0 || (img[pos].toInt() and 0xff) != 0) {
            throw IllegalStateException("该位置不是 kallsyms_token_table")
        }
        for (t in 0 until 0x30) {
            for (c in 0 until 50) {
                pos -= 1
                if (pos < 0) throw IllegalStateException("该位置不是 kallsyms_token_table")
                val b = img[pos].toInt() and 0xff
                if (b == 0 || b > 0x7a) break
                if (c >= 49) throw IllegalStateException("该位置不是 kallsyms_token_table")
            }
        }
        pos += 1
        pos += (-pos) % 4

        tokenTableOffset = pos
        log("[+] Found kallsyms_token_table at file offset 0x${java.lang.Long.toHexString(pos.toLong())}")
    }

    // ------------------------------------------ 步骤 4：kallsyms_token_index

    private fun findKallsymsTokenIndex() {
        var position = tokenTableOffset
        val allTokenOffsets = IntArray(256)
        position -= 1

        for (t in 0 until 256) {
            position += 1
            allTokenOffsets[t] = position - tokenTableOffset
            for (c in 0 until 50) {
                position += 1
                if (position >= img.size) throw IllegalStateException("该位置不是 kallsyms_token_table")
                if ((img[position].toInt() and 0xff) == 0) break
                if (c >= 49) throw IllegalStateException("该位置不是 kallsyms_token_table")
            }
        }

        val maxAlignment = 256
        val tokenIndexSize = 256 * 2
        val searchStart = position
        val searchEnd = minOf(img.size, position + tokenIndexSize + maxAlignment)

        val leBytes = ByteArray(allTokenOffsets.size * 2)
        val beBytes = ByteArray(allTokenOffsets.size * 2)
        for (i in allTokenOffsets.indices) {
            val v = allTokenOffsets[i]
            leBytes[i * 2] = (v and 0xff).toByte()
            leBytes[i * 2 + 1] = ((v ushr 8) and 0xff).toByte()
            beBytes[i * 2] = ((v ushr 8) and 0xff).toByte()
            beBytes[i * 2 + 1] = (v and 0xff).toByte()
        }
        val lePos = findInRange(leBytes, searchStart, searchEnd)
        val bePos = findInRange(beBytes, searchStart, searchEnd)

        when {
            lePos == -1 && bePos == -1 -> throw IllegalStateException("找不到 kallsyms_token_index")
            lePos > bePos -> {
                isBigEndian = false
                tokenIndexOffset = lePos
            }
            bePos > lePos -> {
                isBigEndian = true
                tokenIndexOffset = bePos
            }
            else -> throw IllegalStateException("找不到 kallsyms_token_index")
        }
        tokenIndexEndOffset = tokenIndexOffset + leBytes.size
        log("[+] Found kallsyms_token_index at file offset 0x${java.lang.Long.toHexString(tokenIndexOffset.toLong())}")
    }

    private fun findInRange(needle: ByteArray, from: Int, to: Int): Int {
        val n = needle.size
        if (n == 0 || from < 0) return -1
        val end = minOf(to, img.size)
        if (from + n > end) return -1
        outer@ for (i in from..(end - n)) {
            for (k in 0 until n) {
                if (img[i + k] != needle[k]) continue@outer
            }
            return i
        }
        return -1
    }

    // --------------------------------------------- 步骤 5：kallsyms_markers

    private fun findKallsymsMarkers() {
        val le = !isBigEndian
        for (size in intArrayOf(8, 4, 2)) {
            var position = tokenTableOffset
            var attempt = 0
            while (attempt < 32) {
                attempt++
                val found = r.rfind(ByteArray(size), position)
                if (found < 0) break
                position = found
                position -= position % size
                if (position + 4 * size > img.size) continue

                val entries = LongArray(4) { readUIntN(position + it * size, size, le) }
                if (entries[0] != 0L) continue
                var ok = true
                for (i in 1 until entries.size) {
                    if (!(entries[i - 1] + 0x200 < entries[i] && entries[i] < entries[i - 1] + 0x40000)) {
                        ok = false; break
                    }
                }
                if (ok) {
                    markersOffset = position
                    offsetTableElementSize = size
                    log("[+] Found kallsyms_markers at file offset 0x${java.lang.Long.toHexString(position.toLong())}")
                    return
                }
            }
        }
        throw IllegalStateException("找不到 kallsyms_markers")
    }

    // ----------------------------------------------- 步骤 6：kallsyms_names

    private fun findKallsymsNames() {
        val le = !isBigEndian
        val size = offsetTableElementSize

        var numEntries = (tokenTableOffset - markersOffset) / size
        if (numEntries > 3000) numEntries = 3000
        if (numEntries < 0) throw IllegalStateException("kallsyms_names 位置异常")

        val markers = LongArray(numEntries) { readUIntN(markersOffset + it * size, size, le) }
        var kept = markers.size
        for (i in 1 until markers.size) {
            if (!(markers[i - 1] + 0x200 < markers[i] && markers[i] < markers[i - 1] + 0x40000)) {
                kept = i; break
            }
        }
        var lastNonZero = 0L
        for (i in 0 until kept) if (markers[i] != 0L) lastNonZero = markers[i]

        var position = (markersOffset - lastNonZero).toInt()
        position += (-position) % size
        if (position <= 0) throw IllegalStateException("kallsyms_names 位置非法")
        namesOffset = position
    }

    // ------------------------------------------ 步骤 7：kallsyms_num_syms

    private fun findKallsymsNumSyms() {
        val tokenTable = getTokenTable()
        val possibleSymbolTypes = "ABDRTVWGNPCSU-?uvw"
        val le = !isBigEndian
        val size = offsetTableElementSize

        while (true) {
            // 第一个符号的首 token 必须是一个合法的 nm 类型字符
            if (namesOffset + 1 < img.size) {
                val firstTokenIdx = img[namesOffset + 1].toInt() and 0xff
                val firstToken = tokenTable[firstTokenIdx]
                val firstChar = firstToken.firstOrNull() ?: '\u0000'
                val lower = firstChar.lowercaseChar()
                val isWeak = lower == 'u' || lower == 'v' || lower == 'w'
                val isType = possibleSymbolTypes.contains(firstChar.uppercaseChar()) ||
                    (isWeak && possibleSymbolTypes.contains(lower))
                if (isType) {
                    val numSymbols = countSymbolsFromNames(namesOffset, markersOffset)
                    if (numSymbols >= 256) {
                        val encoded = ByteReader.encodeUIntN(numSymbols.toLong(), size, le)
                        val searchStart = maxOf(0, namesOffset - 256 - 20)
                        val needle = r.rfind(encoded, namesOffset)
                        if (needle >= 0 && needle >= searchStart) {
                            this.numSymbols = numSymbols
                            this.numSymsOffset = needle
                            log("[+] Found kallsyms_names at file offset 0x${java.lang.Long.toHexString(namesOffset.toLong())} ($numSymbols symbols)")
                            log("[+] Found kallsyms_num_syms at file offset 0x${java.lang.Long.toHexString(needle.toLong())}")
                            return
                        }
                    }
                }
            }
            namesOffset -= 4
            if (namesOffset < 0) throw IllegalStateException("找不到 kallsyms_names")
        }
    }

    /**
     * 从 names 区起点正向 DP 扫描到 markers 起点，统计符号个数。
     * 与 JS 版一致：反向遍历 + 向前跳转的 DP。
     */
    private fun countSymbolsFromNames(base: Int, end: Int): Int {
        val span = end - base
        if (span <= 0) return 0
        val explored = IntArray(span + 1)
        for (off in 0..span) {
            val idx = end - off
            val nextByte = if (idx in 0 until img.size) img[idx].toInt() and 0xff else 0
            val symbolSize: Int
            if ((nextByte and 0x80) != 0) {
                val lo = nextByte and 0x7f
                val hi = if (idx + 1 in 0 until img.size) img[idx + 1].toInt() and 0xff else 0
                symbolSize = (lo or (hi shl 7)) + 2
            } else {
                symbolSize = nextByte + 1
            }
            val nextHop = off - symbolSize
            explored[off] = when {
                nextByte == 0 -> if (off <= 256) 0 else -1
                nextHop < 0 || explored[nextHop] == -1 -> -1
                else -> explored[nextHop] + 1
            }
        }
        return explored[span]
    }

    // ------------------------ 步骤 8：kallsyms_addresses / kallsyms_offsets

    private fun findKallsymsAddressesOrSymbols() {
        val parts = versionNumber.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0

        // CONFIG_KALLSYMS_BASE_RELATIVE：4.6+ 非 ia64。注意 JS 的 && 优先级高于 ||，这里保持一致。
        val isIa64 = Regex("ia64|itanium", RegexOption.IGNORE_CASE).containsMatchIn(versionString)
        val likelyHasBaseRelative =
            (major > 4 && major < 7) || ((major == 4 && minor >= 6) && !isIa64)

        val params: List<Triple<Boolean, Boolean, Boolean>> = if (major >= 7) {
            listOf(Triple(false, true, true), Triple(false, false, false))
        } else {
            var p = if (likelyHasBaseRelative) {
                listOf(Triple(true, false, true), Triple(false, false, false))
            } else {
                listOf(Triple(false, false, true), Triple(false, false, false))
            }
            if (overrideRelativeBase) p = listOf(Triple(false, false, false))
            p
        }

        val le = !isBigEndian
        var lastError = ""

        for ((hasBaseRelativeTry, pcRelative, canSkip) in params) {
            val addressByteSize = if (is64Bits) 8 else offsetTableElementSize
            val offsetByteSize = minOf(4, offsetTableElementSize)
            if (addressByteSize <= 0 || offsetByteSize <= 0) {
                lastError = "地址表元素宽度未知"; continue
            }

            var position: Int
            if (major > 6 || (major == 6 && minor >= 4)) {
                // Linux 6.4+：地址/偏移表排在 kallsyms_token_index 之后
                val alignSize = if (is64Bits && !pcRelative) 8 else 4
                position = tokenIndexEndOffset
                position += (-position) % alignSize
                if (hasBaseRelativeTry) {
                    position += numSymbols * offsetByteSize
                    position += (-position) % alignSize
                    position += addressByteSize
                } else if (pcRelative) {
                    position += numSymbols * offsetByteSize
                } else {
                    position += numSymbols * addressByteSize
                }
            } else {
                position = numSymsOffset
            }

            // 跳过前导的全零地址字
            while (position > addressByteSize && position <= img.size) {
                var allZero = true
                for (k in 0 until addressByteSize) {
                    if ((img[position - addressByteSize + k].toInt() and 0xff) != 0) { allZero = false; break }
                }
                if (!allZero) break
                position -= addressByteSize
            }

            var values: LongArray
            var localHasBaseRelative = false
            var localRelativeBase = 0L
            var localPcRelative = false

            if (hasBaseRelativeTry) {
                localHasBaseRelative = true
                position -= addressByteSize
                if (position < 0 || position + addressByteSize > img.size) { lastError = "地址表越界"; continue }
                localRelativeBase = readUIntN(position, addressByteSize, le)
                relativeBaseAddress = localRelativeBase
                // 跳过前导的全零偏移字
                while (position > offsetByteSize) {
                    var allZero = true
                    for (k in 0 until offsetByteSize) {
                        if ((img[position - offsetByteSize + k].toInt() and 0xff) != 0) { allZero = false; break }
                    }
                    if (!allZero) break
                    position -= offsetByteSize
                }
                position -= numSymbols * offsetByteSize
                if (position < 0) { lastError = "地址表越界"; continue }
                values = readSignedArray(position, numSymbols, offsetByteSize, le)
                if (canSkip && values.size >= 3) {
                    if (!(values[0] <= values[1] && values[1] <= values[2])) { lastError = "偏移表非单调"; continue }
                }
            } else if (pcRelative) {
                localPcRelative = true
                position -= numSymbols * offsetByteSize
                if (position < 0) { lastError = "地址表越界"; continue }
                values = readSignedArray(position, numSymbols, offsetByteSize, le)
            } else {
                if (position < 0 || position + numSymbols.toLong() * addressByteSize > img.size) {
                    lastError = "地址表越界"; continue
                }
                values = readUnsignedArray(position, numSymbols, addressByteSize, le)
            }

            if (values.isEmpty()) { lastError = "地址表为空"; continue }

            // 统计与启发式（沿用 kallsyms.py）
            if (localHasBaseRelative) {
                val bits = if (is64Bits) 64 else 32
                val negMask = 0xfffL shl (bits - 12)
                val absMask = 0x3fL shl (bits - 8)
                var negativeItems = 0
                var heuristicallyNegative = 0
                var heuristicallyAbsolute = 0
                for (v in values) {
                    if (v < 0) negativeItems++
                    if ((v and negMask) == negMask) heuristicallyNegative++
                    if ((v and absMask) == 0L) heuristicallyAbsolute++
                }
                val negPct = heuristicallyNegative.toDouble() / values.size
                val absPct = heuristicallyAbsolute.toDouble() / values.size
                if (negPct < 0.5) log("[!] WARNING: 负偏移占比 < 50%（${(negPct * 100).toInt()}%）")
                if (absPct > 0.5) log("[!] WARNING: 超过一半（${(absPct * 100).toInt()}%）的偏移看起来像绝对地址")
                log("[+] Negative offsets overall: ${negativeItems * 100 / values.size}%")

                if (negativeItems.toDouble() / values.size >= 0.5) {
                    hasAbsolutePercpu = true
                    kernelAddresses = LongArray(values.size) { i ->
                        val v = values[i]
                        if (v < 0) localRelativeBase - 1 - v else v
                    }
                } else {
                    hasAbsolutePercpu = false
                    kernelAddresses = LongArray(values.size) { i -> values[i] + localRelativeBase }
                }
            } else if (localPcRelative) {
                val text = values[0]
                val last = values[values.size - 1]
                if (!(text <= 0 && last >= 0) && canSkip) { lastError = "pc-relative 表范围异常"; continue }
                val phdrOffset = readUIntN(0x18 + addressByteSize, addressByteSize, le) + (if (is64Bits) 0x10 else 0x08)
                val baseAddress = if (phdrOffset >= 0 && phdrOffset + addressByteSize <= img.size) {
                    readUIntN(phdrOffset.toInt(), addressByteSize, le)
                } else 0L
                hasAbsolutePercpu = false
                kernelAddresses = LongArray(values.size) { i ->
                    baseAddress + values[i] - text + i.toLong() * offsetByteSize
                }
            } else {
                hasAbsolutePercpu = false
                kernelAddresses = values.copyOf()
            }

            var nullItems = 0
            for (a in kernelAddresses) if (a == 0L) nullItems++
            log("[+] Null addresses overall: ${nullItems * 100 / kernelAddresses.size}%")
            if (nullItems.toDouble() / kernelAddresses.size >= 0.2 && canSkip) {
                lastError = "零地址占比过高"; continue
            }

            hasBaseRelative = localHasBaseRelative
            addressesOffset = position
            log("[+] Found ${if (localHasBaseRelative) "kallsyms_offsets" else "kallsyms_addresses"} at file offset 0x${java.lang.Long.toHexString(position.toLong())}")
            return
        }
        throw IllegalStateException("找不到 kallsyms_addresses / kallsyms_offsets${if (lastError.isNotEmpty()) "（$lastError）" else ""}")
    }

    // -------------------------------------------------- 步骤 9：解析符号表

    private fun getTokenTable(): Array<String> {
        if (uncompressedKallsyms) {
            return Array(256) { it.toChar().toString() }
        }
        val tokens = Array(256) { "" }
        var position = tokenTableOffset
        for (i in 0 until 256) {
            val sb = StringBuilder()
            while (position < img.size && (img[position].toInt() and 0xff) != 0) {
                sb.append((img[position].toInt() and 0xff).toChar())
                position++
            }
            position++
            tokens[i] = sb.toString()
        }
        return tokens
    }

    private fun parseSymbolTable(): List<KernelSymbol> {
        val tokens = getTokenTable()
        val names = arrayOfNulls<String>(numSymbols)
        var position = namesOffset
        for (n in 0 until numSymbols) {
            if (position >= img.size) break
            var length = img[position].toInt() and 0xff
            position++
            if ((length and 0x80) != 0) {
                if (position >= img.size) break
                length = (length and 0x7f) or ((img[position].toInt() and 0xff) shl 7)
                position++
            }
            val sb = StringBuilder(length + 1)
            for (i in 0 until length) {
                if (position >= img.size) break
                sb.append(tokens[img[position].toInt() and 0xff])
                position++
            }
            names[n] = sb.toString()
        }

        val out = ArrayList<KernelSymbol>(kernelAddresses.size)
        for (i in kernelAddresses.indices) {
            val raw = names.getOrNull(i) ?: ""
            val typeChar = raw.firstOrNull() ?: '?'
            val lower = typeChar.lowercaseChar()
            val isWeak = lower == 'u' || lower == 'v' || lower == 'w'
            val isGlobal = if (isWeak) true
            else typeChar.isUpperCase() && typeChar != typeChar.lowercaseChar()
            out.add(
                KernelSymbol(
                    address = kernelAddresses[i],
                    type = typeChar,
                    name = if (raw.isEmpty()) "" else raw.substring(1),
                    isGlobal = isGlobal,
                )
            )
        }
        return out
    }

    // ------------------------------------------------ 步骤 10：基址推导

    private fun inferBaseAddressFromSyms(symbols: List<KernelSymbol>): Long {
        val firstText = symbols.firstOrNull { it.type == 'T' }
        val firstSymAddr = firstText?.address ?: 0L
        val mask = 0x1fffL.inv()

        val candidate: Long
        if (hasBaseRelative && java.lang.Long.compareUnsigned(relativeBaseAddress, firstSymAddr) < 0) {
            candidate = relativeBaseAddress and mask
            if (candidate != relativeBaseAddress) {
                log("[+] Guessed the base address using the kallsyms_relative_base value (0x${java.lang.Long.toHexString(relativeBaseAddress)} aligned to 0x${java.lang.Long.toHexString(candidate)})")
            } else {
                log("[+] Guessed the base address using the kallsyms_relative_base value (0x${java.lang.Long.toHexString(candidate)})")
            }
        } else {
            candidate = firstSymAddr and mask
            log("[+] Guessed the base address using the first_symbol_virtual_address fallback heuristic (0x${java.lang.Long.toHexString(candidate)})")
        }
        kernelTextCandidate = candidate
        return candidate
    }

    // --------------------------------------- uncompressed 回退（增强路径）

    private fun findKallsymsNamesUncompressed() {
        // 无 token 表时，符号名以明文 [len][name...] 存放。
        // 扫描一段连续、结构合法的记录区作为 kallsyms_names。
        val minRun = 4096
        val limit = img.size
        var i = 0
        while (i < limit - minRun) {
            val start = i
            var pos = i
            var count = 0
            var ok = true
            while (count < minRun && pos + 1 < limit) {
                val len = img[pos].toInt() and 0xff
                if (len == 0 || len > 96 || pos + 1 + len > limit) { ok = false; break }
                val first = img[pos + 1].toInt() and 0xff
                if (!(first in 0x41..0x5a || first in 0x61..0x7a || first == 0x5f)) { ok = false; break }
                var printable = true
                for (k in 1 until len) {
                    val c = img[pos + 1 + k].toInt() and 0xff
                    if (c < 0x21 || c > 0x7e) { printable = false; break }
                }
                if (!printable) { ok = false; break }
                pos += 1 + len
                count++
            }
            if (ok && count >= minRun) {
                if (positionsComposeMarkers(pos)) {
                    namesOffset = start
                    uncompressedKallsyms = true
                    return
                }
            }
            i = start + 1
        }
        throw KallsymsNotFoundException("无法在内核镜像里定位 kallsyms_names（uncompressed 回退也失败）")
    }

    private fun positionsComposeMarkers(afterNames: Int): Boolean {
        val le = true
        for (size in intArrayOf(4, 8)) {
            val aligned = afterNames + ((size - afterNames % size) % size)
            if (aligned + 4 * size > img.size) continue
            val e = LongArray(4) { readUIntN(aligned + it * size, size, le) }
            if (e[0] != 0L) continue
            var ok = true
            for (k in 1 until 4) {
                if (!(e[k - 1] + 0x200 < e[k] && e[k] < e[k - 1] + 0x40000)) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }

    private fun findKallsymsMarkersUncompressed() {
        // names 之后紧跟 markers；元素宽度取第一个能通过结构校验的。
        val le = true
        for (size in intArrayOf(8, 4, 2)) {
            var position = tokenTableOffset.takeIf { it > 0 } ?: namesOffset
            var attempt = 0
            while (attempt < 64) {
                attempt++
                val found = r.rfind(ByteArray(size), position)
                if (found < 0) break
                position = found
                position -= position % size
                if (position <= namesOffset) break
                if (position + 4 * size > img.size) continue
                val e = LongArray(4) { readUIntN(position + it * size, size, le) }
                if (e[0] != 0L) continue
                var ok = true
                for (k in 1 until 4) {
                    if (!(e[k - 1] + 0x200 < e[k] && e[k] < e[k - 1] + 0x40000)) { ok = false; break }
                }
                if (ok) {
                    markersOffset = position
                    offsetTableElementSize = size
                    return
                }
            }
        }
        // 兜底：names 之后按 4 字节对齐直接当 markers
        markersOffset = namesOffset + ((4 - namesOffset % 4) % 4)
        offsetTableElementSize = 4
    }

    // ------------------------------------------------------------- 小工具

    private fun readUIntN(offset: Int, size: Int, le: Boolean): Long = when (size) {
        1 -> (img[offset].toInt() and 0xff).toLong()
        2 -> r.u16(offset, le).toLong()
        4 -> r.u32(offset, le)
        8 -> r.u64(offset, le)
        else -> throw IllegalArgumentException("元素宽度非法: $size")
    }

    private fun readSignedArray(offset: Int, count: Int, size: Int, le: Boolean): LongArray {
        val out = LongArray(count)
        for (i in 0 until count) {
            val o = offset + i * size
            out[i] = when (size) {
                1 -> img[o].toLong()
                2 -> r.i16(o, le).toLong()
                4 -> r.i32(o, le).toLong()
                8 -> r.i64(o, le)
                else -> throw IllegalArgumentException("元素宽度非法: $size")
            }
        }
        return out
    }

    private fun readUnsignedArray(offset: Int, count: Int, size: Int, le: Boolean): LongArray {
        val out = LongArray(count)
        for (i in 0 until count) out[i] = readUIntN(offset + i * size, size, le)
        return out
    }

    // --------------------------------------------------- 架构特征（字节级）

    private class Alt(val mask: IntArray, val value: IntArray) {
        val size get() = mask.size
    }

    private class ArchPattern(val name: String, val display: String, val alts: List<Alt>)

    class ArchGuess(val name: String, val display: String, val is64Bit: Boolean, val isBigEndian: Boolean)

    companion object {

        /** 与 symbols.js 的 ARCH_PROLOGUES 一一对应，顺序也保持一致（影响并列时的取舍）。 */
        private val ARCH_PATTERNS: List<ArchPattern> = listOf(
            arch("mipsle", "Little-endian MIPS", alt(b(0xA0, 0), b(0, 0xFF), b(0, 0xBD), b(0, 0x27), b(0xA0, 0), b(0xA0, 0), b(0xF0, 0xA0), b(0, 0xAF))),
            arch("mipsbe", "Big-endian MIPS", alt(b(0, 0x27), b(0, 0xBD), b(0, 0xFF), b(0xA0, 0), b(0, 0xAF), b(0xF0, 0xA0), b(0xA0, 0), b(0xA0, 0))),
            arch("mips64le", "Little-endian MIPS64", alt(b(0xA0, 0), b(0, 0xFF), b(0, 0xBD), b(0, 0x67), b(0xA0, 0), b(0xA0, 0), b(0xF0, 0xA0), b(0, 0xFF))),
            arch("mips64be", "Big-endian MIPS64", alt(b(0, 0x67), b(0, 0xBD), b(0, 0xFF), b(0xA0, 0), b(0, 0xFF), b(0xF0, 0xA0), b(0xA0, 0), b(0xA0, 0))),
            arch(
                "x86", "32-bit x86",
                alt(b(0, 0x55), b(0, 0x89), b(0, 0xE5), b(0, 0x83), b(0, 0xEC)),
                alt(b(0, 0x55), b(0, 0x89), b(0, 0xE5), b(0, 0x57), b(0, 0x56)),
            ),
            arch("x86_64", "64-bit x86", alt(b(0, 0x55), b(0, 0x48), b(0, 0x89), b(0, 0xE5))),
            arch("powerpcbe", "Big-endian PowerPC", alt(b(0, 0x7C), b(0, 0x08), b(0, 0x02), b(0, 0xA6))),
            arch("powerpcle", "Little-endian PowerPC", alt(b(0, 0xA6), b(0, 0x02), b(0, 0x08), b(0, 0x7C))),
            arch(
                "armbe", "Big-endian ARM",
                alt(b(0, 0xE9), b(0, 0x2D), b(0xA0, 0), b(0xA0, 0), b(0xF0, 0xE0), b(0xA0, 0), b(0xA0, 0), b(0xA0, 0), b(0xF0, 0xE0), b(0xA0, 0), b(0xA0, 0), b(0xA0, 0)),
            ),
            arch(
                "armle", "Little-endian ARM",
                alt(b(0, 0x2D), b(0, 0xE9), b(0xA0, 0), b(0xA0, 0), b(0xA0, 0), b(0xF0, 0xE0), b(0xA0, 0), b(0xA0, 0), b(0xA0, 0), b(0xF0, 0xE0)),
            ),
            arch("mips16e", "Big-endian MIPS16e", alt(b(0, 0xF0), b(0, 0x08), b(0, 0x64), b(0xA0, 0), b(0, 0x01), b(0xA0, 0))),
            arch("superhle", "Little-endian SuperH", alt(b(0, 0xF6), b(0, 0x69), b(0, 0x0B), b(0, 0x00), b(0, 0xF6), b(0, 0x68))),
            arch("superhbe", "Big-endian SuperH", alt(b(0, 0x69), b(0, 0xF6), b(0, 0x00), b(0, 0x0B), b(0, 0x68), b(0, 0xF6))),
            arch("aarch64", "Little-endian ARM64", alt(b(0, 0xC0), b(0, 0x03), b(0, 0x5F), b(0, 0xD6))),
            arch("sparc", "SPARC", alt(b(0, 0x81), b(0, 0xC7), b(0, 0xE0), b(0, 0x08), b(0, 0x81), b(0, 0xE8))),
            arch("arcompact", "ARCompact", alt(b(0, 0xF1), b(0, 0xC0), b(0xA0, 0), b(0, 0x1C), b(0, 0x48), b(0xF0, 0xB0))),
        )

        private fun arch(name: String, display: String, vararg alts: Alt) = ArchPattern(name, display, alts.toList())

        /** mask 位为 1 表示该字节必须等于 value。0xF0 用于半字节范围（[\xa0-\xbf] 等）。 */
        private fun b(mask: Int, value: Int) = mask to value

        private fun alt(vararg pairs: Pair<Int, Int>): Alt =
            Alt(IntArray(pairs.size) { pairs[it].first }, IntArray(pairs.size) { pairs[it].second })

        /**
         * 字节级架构探测。与 JS 正则 /g 语义一致：从左到右、最左匹配、匹配后跳过整个匹配长度。
         */
        fun guessArchitectureFromImage(img: ByteArray): ArchGuess? {
            // UEFI PE stub on ARM64: "MZ" 开头且 0x38 处 "ARMd"
            if (img.size > 0x3c && img[0].toInt() == 0x4d && img[1].toInt() == 0x5a &&
                img[0x38].toInt() == 'A'.code && img[0x39].toInt() == 'R'.code &&
                img[0x3a].toInt() == 'M'.code && img[0x3b].toInt() == 'd'.code
            ) {
                return ArchGuess("aarch64", "Little-endian ARM64", true, false)
            }

            var best: ArchPattern? = null
            var bestCount = 0
            for (p in ARCH_PATTERNS) {
                val c = countMatches(img, p)
                if (c > bestCount) { bestCount = c; best = p }
            }
            if (bestCount < 100 || best == null) return null
            val is64 = best.name == "aarch64" || best.name == "x86_64" ||
                best.name == "mips64le" || best.name == "mips64be"
            val be = best.name in setOf("mipsbe", "mips64be", "powerpcbe", "armbe", "superhbe")
            return ArchGuess(best.name, best.display, is64, be)
        }

        private fun countMatches(img: ByteArray, p: ArchPattern): Int {
            var count = 0
            var i = 0
            val n = img.size
            outer@ while (i < n) {
                for (a in p.alts) {
                    if (i + a.size > n) continue
                    var ok = true
                    for (k in 0 until a.size) {
                        val byte = img[i + k].toInt() and 0xff
                        if ((byte and a.mask[k]) != a.value[k]) { ok = false; break }
                    }
                    if (ok) {
                        count++
                        i += a.size
                        continue@outer
                    }
                }
                i++
            }
            return count
        }
    }
}
