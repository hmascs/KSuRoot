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

    /** 内核镜像大小上限（符号偏移落在这个范围内才认为合理）。 */
    private val maxOffset = 0x2000_0000L

    private val failures = ArrayList<String>()

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
            val fops = lookup(strategy.fops, Kind.DATA) ?: return null
            val slotAddr = fops.address + strategy.slot
            val v = image.readU64(slotAddr)
            if (v != null && v != 0L && image.contains(v)) {
                Hit(v, ResolveSource.IMAGE_READ, "读 ${fops.name}+${com.kernelpack.Hex.u(strategy.slot)}")
            } else {
                val fb = lookup(strategy.fallback, Kind.TEXT) ?: return null
                Hit(fb.address, ResolveSource.KALLSYMS, "kallsyms 回退: ${fb.name}")
            }
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
        if (kind != Kind.ANY) {
            for (n in names) {
                val s = index[n] ?: continue
                return s
            }
        }
        return null
    }

    private fun signed(v: Long): String = if (v < 0) "-0x${java.lang.Long.toHexString(-v)}" else "+0x${java.lang.Long.toHexString(v)}"
}
