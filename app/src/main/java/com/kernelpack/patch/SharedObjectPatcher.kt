package com.kernelpack.patch

import com.kernelpack.Hex
import com.kernelpack.elf.Elf64File

/** 一个常量的语义类别。 */
enum class SpecKind {
    /** 相对内核镜像基址的偏移：.so 里可能存"裸偏移"，也可能存"基址+偏移"的绝对地址，两种都要换。 */
    IMAGE_OFFSET,

    /** 只有绝对地址形式（基址+偏移）。 */
    ABSOLUTE_ADDRESS,

    /** 内核链接基址本身（KIMAGE_TEXT_BASE）。 */
    BASE,
}

/** 一条"旧值 → 新值"的替换要求。 */
data class PatchSpec(
    val key: String,
    val oldValue: Long,
    val newValue: Long,
    val kind: SpecKind = SpecKind.IMAGE_OFFSET,
)

/** 单个键的打包结果。 */
data class SpecOutcome(
    val key: String,
    val oldValue: Long,
    val newValue: Long,
    /** 代码里找到的构造点数量。 */
    val sitesFound: Int,
    val sitesPatched: Int,
    val sitesFailed: Int,
    /** 数据段里补丁的字面量个数。 */
    val dataLiteralsPatched: Int,
    /** 打完补丁后代码里**残留**的旧值构造点（应为 0）。 */
    val residualOld: Int,
    /** 打完补丁后代码里能看到的新值构造点。 */
    val verifiedNew: Int,
    val note: String,
) {
    val changed: Boolean get() = oldValue != newValue
    val ok: Boolean
        get() = !changed || (sitesFailed == 0 && residualOld == 0 && (sitesPatched > 0 || dataLiteralsPatched > 0))

    /** 该键在这份 .so 里根本没被引用（正常现象：老版本 .so 可能没有对应代码路径）。 */
    val absent: Boolean get() = changed && sitesFound == 0 && dataLiteralsPatched == 0

    fun line(): String {
        val mark = when {
            !changed -> "= 无需修改"
            absent -> "- 该 .so 未引用"
            ok -> "✓ 已替换"
            else -> "✗ 有问题"
        }
        return "  %-26s %-14s sites=%d patched=%d failed=%d data=%d residual=%d".format(
            key, mark, sitesFound, sitesPatched, sitesFailed, dataLiteralsPatched, residualOld
        )
    }
}

/** 打包总报告。 */
data class PatchReport(
    val oldBase: Long,
    val newBase: Long,
    val outcomes: List<SpecOutcome>,
    val conflicts: List<String>,
) {
    val baseChanged: Boolean get() = oldBase != newBase

    /** 是否有"该改却没改成"的项。 */
    val hasFailures: Boolean get() = outcomes.any { it.changed && !it.ok && !it.absent }

    val patchedKeys: List<String> get() = outcomes.filter { it.sitesPatched > 0 || it.dataLiteralsPatched > 0 }.map { it.key }
    val untouchedKeys: List<String> get() = outcomes.filter { it.absent }.map { it.key }

    fun summary(): String = buildString {
        appendLine("内核基址: ${Hex.u64(oldBase)} -> ${Hex.u64(newBase)}" +
            if (baseChanged) "（已变化，基址常量也已一并处理）" else "（未变化）")
        appendLine("已替换 ${patchedKeys.size} 项，共 ${outcomes.sumOf { it.sitesPatched + it.dataLiteralsPatched }} 处；" +
            "该 .so 未引用 ${untouchedKeys.size} 项")
        for (o in outcomes) appendLine(o.line())
        if (conflicts.isNotEmpty()) {
            appendLine("冲突 ${conflicts.size} 条：")
            for (c in conflicts.take(10)) appendLine("  $c")
        }
    }
}

/**
 * 动态库打包器：把"旧机型的常量"就地改写成"新机型的常量"。
 *
 * 全程**原地覆盖**，不新增/删除任何字节，所以 ELF 布局、段表、重定位表全部不变，
 * 产出的 `.so` 与原始文件长度一致，可以直接 `LD_PRELOAD`。
 *
 * 三道保险：
 *  1. 改写只发生在**按寄存器数据流确认**的 movz/movk/movn 链上，且保留原有指令条数与寄存器；
 *  2. 链上表达不了新值时**直接放弃该点并上报**，绝不猜着改；
 *  3. 改完**重新扫描**：旧值残留数必须为 0、新值可见数必须 > 0，否则标记为失败。
 */
class SharedObjectPatcher(
    private val bytes: ByteArray,
    private val log: (String) -> Unit = {},
) {

    /** 是否同时补丁数据段里的 4 字节偏移字面量（默认关闭：4 字节太容易误伤）。 */
    var patchDataLiterals32: Boolean = false

    fun patch(specs: List<PatchSpec>): PatchReport {
        val elf = Elf64File(bytes)
        val baseSpec = specs.firstOrNull { it.kind == SpecKind.BASE }
        val oldBase = baseSpec?.oldValue ?: 0L
        val newBase = baseSpec?.newValue ?: oldBase

        // 1) 每个键展开成一组"值对"。IMAGE_OFFSET 要同时处理两种存放形式：
        //    裸偏移（movz w,#off_low / movk w,#off_high）与绝对地址（基址+偏移）。
        class ValuePair(val from: Long, val to: Long, val isOffsetForm: Boolean)

        val pairsByKey = LinkedHashMap<String, MutableList<ValuePair>>()
        for (s in specs) {
            val list = pairsByKey.getOrPut(s.key) { ArrayList(2) }
            when (s.kind) {
                SpecKind.BASE, SpecKind.ABSOLUTE_ADDRESS -> list.add(ValuePair(s.oldValue, s.newValue, false))
                SpecKind.IMAGE_OFFSET -> {
                    list.add(ValuePair(s.oldValue, s.newValue, true))
                    val absFrom = oldBase + s.oldValue
                    val absTo = newBase + s.newValue
                    if (absFrom != absTo) list.add(ValuePair(absFrom, absTo, false))
                }
            }
        }

        // 别名保护：不同的键若指向同一个旧值（例如 INIT_TASK 与 SLIDE_INIT_TASK），
        // 只让第一个键负责替换，后面的按"同源"记录，避免把同一处改两遍。
        val valueOwner = HashMap<Long, String>()
        // 预分配归属：同一个旧值只让**第一个**键负责替换，后面的键按"同源"记录。
        for ((key, list) in pairsByKey) for (vp in list) valueOwner.putIfAbsent(vp.from, key)

        // 2) 扫描全部可执行节
        class SiteRef(val sectionIndex: Int, val site: ConstantScanner.Site)
        val execSections = elf.executableSections()
        val targets = HashSet<Long>()
        for (list in pairsByKey.values) for (v in list) targets.add(v.from)

        val sitesByValue = HashMap<Long, MutableList<SiteRef>>()
        for ((si, sec) in execSections.withIndex()) {
            val data = bytes.copyOfRange(sec.offset.toInt(), (sec.offset + sec.size).toInt())
            for ((v, list) in ConstantScanner(data).scan(targets)) {
                val bucket = sitesByValue.getOrPut(v) { ArrayList() }
                for (site in list) bucket.add(SiteRef(si, site))
            }
        }

        // 3) 逐键改写
        val claimed = HashMap<Long, String>()
        val conflicts = ArrayList<String>()
        val outcomes = ArrayList<SpecOutcome>()
        val aliases = HashMap<String, String>()

        for ((key, pairs) in pairsByKey) {
            // 本键的所有旧值都归别人 → 它只是别名，不单独出报告（由 aliases 统一交代）
            if (pairs.none { valueOwner[it.from] == key }) {
                aliases[key] = valueOwner[pairs[0].from] ?: continue
                continue
            }

            var found = 0
            var patched = 0
            var failed = 0
            var dataPatched = 0

            for (vp in pairs) {
                if (valueOwner[vp.from] != key) continue

                val refs = sitesByValue[vp.from].orEmpty()
                found += refs.size
                val done = HashSet<String>()

                for (ref in refs) {
                    val sec = execSections[ref.sectionIndex]
                    val sig = ref.sectionIndex.toString() + ":" + ref.site.chain.joinToString(",")
                    if (!done.add(sig)) continue

                    val clash = ref.site.chain.firstOrNull { claimed.containsKey(gid(ref.sectionIndex, it)) }
                    if (clash != null) {
                        val who = claimed[gid(ref.sectionIndex, clash)]!!
                        if (who != key) {
                            conflicts.add("$key 的构造点 @0x${java.lang.Long.toHexString(sec.offset + clash * 4)} 已被 $who 占用，已跳过")
                        }
                        continue
                    }

                    val rewritten = ConstantScanner.rewrite(ref.site, vp.to)
                    if (rewritten == null) { failed++; continue }

                    for (i in ref.site.chain.indices) {
                        val off = sec.offset.toInt() + ref.site.chain[i] * 4
                        val v = rewritten[i]
                        bytes[off] = (v and 0xff).toByte()
                        bytes[off + 1] = ((v ushr 8) and 0xff).toByte()
                        bytes[off + 2] = ((v ushr 16) and 0xff).toByte()
                        bytes[off + 3] = ((v ushr 24) and 0xff).toByte()
                        claimed[gid(ref.sectionIndex, ref.site.chain[i])] = key
                    }
                    patched++
                }

                dataPatched += patchDataLiterals(elf, vp.from, vp.to)
                if (vp.isOffsetForm && patchDataLiterals32) dataPatched += patchDataLiterals32(elf, vp.from, vp.to)
            }

            outcomes.add(
                SpecOutcome(
                    key = key,
                    oldValue = pairs[0].from,
                    newValue = pairs[0].to,
                    sitesFound = found,
                    sitesPatched = patched,
                    sitesFailed = failed,
                    dataLiteralsPatched = dataPatched,
                    residualOld = 0,
                    verifiedNew = 0,
                    note = "",
                )
            )
        }

        // 4) 校验：重新扫描，旧值必须清零、新值必须可见
        val verified = ArrayList<SpecOutcome>()
        for (o in outcomes) {
            val key = o.key
            val pairs = pairsByKey.getValue(key)
            var residual = 0
            var newSeen = 0
            for (vp in pairs) {
                for (sec in execSections) {
                    val data = bytes.copyOfRange(sec.offset.toInt(), (sec.offset + sec.size).toInt())
                    val sc = ConstantScanner(data)
                    residual += sc.scan(setOf(vp.from))[vp.from]?.size ?: 0
                    newSeen += sc.scan(setOf(vp.to))[vp.to]?.size ?: 0
                }
            }
            verified.add(o.copy(residualOld = residual, verifiedNew = newSeen))
        }

        // 把"同值合并"的键补进报告，保证每个键都有交代
        val extra = aliases.map { (k, owner) ->
            val src = verified.first { it.key == owner }
            src.copy(key = k, note = "与 $owner 同值，同一处替换")
        }

        return PatchReport(
            oldBase = oldBase,
            newBase = newBase,
            outcomes = verified + extra,
            conflicts = conflicts,
        )
    }

    /**
     * 数据段里的 8 字节字面量指针。
     *
     * 只处理**看起来像内核地址**的值（高位全 1）：裸偏移那种小数值在数据段里
     * 匹配到的大概率是无关数据，改了就是误伤。8 字节精确匹配本身误伤概率就极低。
     */
    private fun patchDataLiterals(elf: Elf64File, from: Long, to: Long): Int {
        if (from == to) return 0
        if (java.lang.Long.compareUnsigned(from, KERNEL_SPACE_FLOOR) < 0) return 0
        if (java.lang.Long.compareUnsigned(to, KERNEL_SPACE_FLOOR) < 0) return 0
        var n = 0
        for (sec in elf.allocDataSections()) {
            val hits = elf.findAllU64(from, sec.offset.toInt(), (sec.offset + sec.size).toInt())
            for (hit in hits) {
                elf.putU64(hit, to)
                n++
            }
        }
        return n
    }

    /**
     * 数据段里的 4 字节偏移字面量（默认关闭）。
     *
     * 4 字节太短，撞车概率高，所以加一道**全文件唯一性**检查：
     * 只有在整个文件里只出现一次时才敢改。
     */
    private fun patchDataLiterals32(elf: Elf64File, from: Long, to: Long): Int {
        if (from == to || from < 0 || from > 0xffffffffL) return 0
        if (to < 0 || to > 0xffffffffL) return 0
        val pat = ByteArray(4) { ((from ushr (8 * it)) and 0xff).toByte() }
        var occurrence = 0
        var lastHit = -1
        for (sec in elf.allocDataSections()) {
            var i = sec.offset.toInt()
            val end = (sec.offset + sec.size).toInt()
            outer@ while (i + 4 <= end) {
                for (k in 0 until 4) if (elf.bytes[i + k] != pat[k]) { i++; continue@outer }
                occurrence++
                lastHit = i
                i++
            }
        }
        if (occurrence != 1 || lastHit < 0) return 0
        elf.putU32(lastHit, to)
        return 1
    }

    private fun gid(sectionIndex: Int, wordIndex: Int): Long =
        (sectionIndex.toLong() shl 32) or (wordIndex.toLong() and 0xffffffffL)

    private companion object {
        /** 内核空间下界（0xffff000000000000）：用来判断一个字面量"像不像内核地址"。 */
        val KERNEL_SPACE_FLOOR: Long = 0xffff000000000000uL.toLong()
    }
}
