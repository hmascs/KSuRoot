package com.kernelpack.resolve

import com.kernelpack.model.KernelImageAnalysis
import com.kernelpack.model.KernelSymbol
import com.kernelpack.model.OffsetEntry
import com.kernelpack.model.ResolveSource

/** 单个偏移解析失败的说明。 */
class ResolutionFailure(val key: String, val reason: String) : Exception("$key: $reason")

/**
 * 把"内核符号表 + 内核镜像"变成"一串可直接写进 .so 的偏移量"。
 *
 * 解析链设计成**多策略回退**：每种偏移先试最可靠的方式，失败再退到次选，
 * 每条都记录来源（[ResolveSource]）与人类可读的来历，方便在真机上排错。
 */
class OffsetResolver(
    private val analysis: KernelImageAnalysis,
    private val image: KernelImage,
    private val specs: List<SymbolSpec> = SymbolCatalog.NEO11_OFFSETS,
) {

    private val index: Map<String, KernelSymbol> = analysis.byName

    /**
     * 全部策略里出现过的候选符号名 —— 用来预建 [fuzzy] 索引。
     */
    private val candidates: Set<String> = buildSet {
        fun add(l: List<String>) = addAll(l)
        for (spec in specs) {
            for (st in spec.strategies) {
                when (st) {
                    is Strategy.Symbol -> add(st.names)
                    is Strategy.SymbolPlus -> add(st.base)
                    is Strategy.FopsSlot -> { add(st.fops); add(st.fallback) }
                    is Strategy.ImageU32 -> add(st.base)
                    is Strategy.CtlTableData -> add(st.dataSymbols)
                }
            }
        }
    }

    /**
     * 「名字里**含**这个片段」的索引 —— 只收录**唯一命中**的片段。
     *
     * ### 为什么需要它
     *
     * Linux 6.12 把 ashmem 改成了 **Rust 实现**，kallsyms 里是 legacy mangled 名：
     *
     * ```
     * _RNvMs4_NtCsdfZWD8DztAw_6kernel10miscdeviceINtB5_16MiscdeviceVTable
     *   NtCs232Q5cNN6Ho_11ashmem_rust6AshmemE5ioctlB14_
     * ```
     *
     * 里面既有 **crate hash**（`Cs232Q5cNN6Ho_`）也有**回代索引**（`B14_`），
     * 换一次编译就变 —— 全名**根本没法硬编码**。
     * 但尾段 `6AshmemE5ioctl` 是稳定的（Rust 用 `<长度><名字>` 编码，长度由名字本身决定），
     * 所以按它做**包含**匹配。
     *
     * ### 三条自我约束
     *
     * - **只在精确名没命中时才用**：`index` 里有的一律走精确匹配，避免节外生枝。
     * - **歧义即放弃**：同一片段命中多于一个符号就不收录。宁可解析不出，
     *   也不要随手挑一个 —— 挑错就是往错位置写内存，而且是静默的。
     * - **片段太短不参与**：长度 < [MIN_FUZZY_LEN] 的（`init_task`、`ashmem_fops` 这类）
     *   很容易是别人的子串，模糊匹配对它们只有害处。
     */
    private val fuzzy: Map<String, KernelSymbol> = run {
        val hits = HashMap<String, MutableList<KernelSymbol>>()
        for (frag in candidates) {
            if (frag.length < MIN_FUZZY_LEN) continue
            if (index.containsKey(frag)) continue
            for (s in analysis.symbols) {
                if (s.name.contains(frag)) hits.getOrPut(frag) { ArrayList() }.add(s)
            }
        }
        hits.filterValues { it.size == 1 }.mapValues { it.value[0] }
    }

    /** 内核镜像大小上限（符号偏移落在这个范围内才认为合理）。 */
    private val maxOffset = 0x2000_0000L

    private val failures = ArrayList<String>()

    companion object {
        /**
         * 片段短于这个长度就不参与模糊匹配。
         *
         * 12 是照着真实候选定的：`init_task`(9)、`ashmem_fops`(11) 这类通用名
         * 太容易是别人名字的子串；而需要模糊匹配的那几个都远长于它 ——
         * `6AshmemE5ioctl`(14)、`15ASHMEM_FOPS_PTR`(17)、`generic_file_splice_read`(23)。
         */
        const val MIN_FUZZY_LEN = 12
    }

    fun failures(): List<String> = failures.toList()

    fun resolve(): Map<String, OffsetEntry> {
        val out = LinkedHashMap<String, OffsetEntry>()
        for (spec in specs) {
            out[spec.key] = resolveOne(spec)
        }
        // 别名：同一符号的第二次出现不再查表，直接复制（保证两份值一定一致）
        for ((alias, target) in SymbolCatalog.ALIASES) {
            if (alias == target) continue
            val t = out[target] ?: continue
            val a = out[alias]
            if (a == null || !a.resolved) {
                out[alias] = t.copy(key = alias, detail = "${t.detail}（与 $target 同源）")
            }
        }
        return out
    }

    private fun resolveOne(spec: SymbolSpec): OffsetEntry {
        for (strategy in spec.strategies) {
            val hit = tryStrategy(strategy) ?: continue
            val (address, source, detail) = hit
            val offset = address - analysis.baseAddress
            if (offset < 0 || offset > maxOffset) {
                failures.add("${spec.key}: 策略命中但偏移越界（addr=${com.kernelpack.Hex.u64(address)} off=${com.kernelpack.Hex.u(offset)}）")
                continue
            }
            return OffsetEntry(spec.key, offset, address, source, detail)
        }
        failures.add("${spec.key}: 全部策略均未命中${if (spec.note.isNotEmpty()) "（${spec.note}）" else ""}")
        return OffsetEntry(spec.key, null, null, ResolveSource.UNAVAILABLE, "未解析")
    }

    private data class Hit(val address: Long, val source: ResolveSource, val detail: String)

    private fun tryStrategy(strategy: Strategy): Hit? = when (strategy) {
        is Strategy.Symbol -> {
            val s = lookup(strategy.names, strategy.kind) ?: return null
            Hit(s.address, ResolveSource.KALLSYMS, "kallsyms: ${s.name}")
        }

        is Strategy.SymbolPlus -> {
            val s = lookup(strategy.base, strategy.kind) ?: return null
            val addr = s.address + strategy.delta
            Hit(addr, ResolveSource.KALLSYMS_DERIVED, "kallsyms: ${s.name} ${signed(strategy.delta)}")
        }

        is Strategy.FopsSlot -> {
            // [2026-10 修] 原来 `fops` 一查不到就 `return null`，**fallback 根本没机会**：
            // fallback 只在"表找到了、但那个槽读不出来"时才用得上。
            // 6.12 正好卡在这条上 —— 它把 ashmem 换成了 Rust 实现，`ashmem_fops`
            // 这个符号压根不存在，于是连"退到 mangled 名"这一步都走不到。
            // 表找不到与槽读不出，对 fallback 来说是同一件事：直接查符号名。
            val fops = lookup(strategy.fops, Kind.DATA)
            if (fops != null) {
                val slotAddr = fops.address + strategy.slot
                val v = image.readU64(slotAddr)
                if (v != null && v != 0L && image.contains(v)) {
                    return Hit(v, ResolveSource.IMAGE_READ, "读 ${fops.name}+${com.kernelpack.Hex.u(strategy.slot)}")
                }
            }
            val fb = lookup(strategy.fallback, Kind.TEXT) ?: return null
            Hit(fb.address, ResolveSource.KALLSYMS, "kallsyms 回退: ${fb.name}")
        }

        is Strategy.ImageU32 -> {
            val s = lookup(strategy.base, Kind.ANY) ?: return null
            val v = image.readU32(s.address + strategy.delta) ?: return null
            Hit(v, ResolveSource.IMAGE_READ, "读 u32 ${s.name}+${com.kernelpack.Hex.u(strategy.delta)}")
        }

        is Strategy.CtlTableData -> {
            val target = lookup(strategy.dataSymbols, Kind.DATA) ?: return null
            val field = image.findCtlTableDataField(strategy.procName, target.address) ?: return null
            Hit(field, ResolveSource.IMAGE_SCAN, "ctl_table \"${strategy.procName}\" 的 data 字段（→ ${target.name}）")
        }
    }

    /**
     * 按名字 + 段类型查符号。先严格匹配类型；找不到再放宽到任意类型
     * （有些内核会把数据符号塞进别的段，放宽能显著提高跨机型命中率）。
     */
    private fun lookup(names: List<String>, kind: Kind): KernelSymbol? {
        for (n in names) {
            val s = index[n] ?: continue
            if (kind.matches(s)) return s
        }
        // 精确名全不中 → 试「名字里含这个片段」（Rust mangled 名走这条，见 [fuzzy]）。
        for (n in names) {
            val s = fuzzy[n] ?: continue
            if (kind.matches(s)) return s
        }
        if (kind != Kind.ANY) {
            for (n in names) {
                val s = index[n] ?: continue
                return s
            }
            for (n in names) {
                val s = fuzzy[n] ?: continue
                return s
            }
        }
        return null
    }

    private fun signed(v: Long): String = if (v < 0) "-0x${java.lang.Long.toHexString(-v)}" else "+0x${java.lang.Long.toHexString(v)}"
}
