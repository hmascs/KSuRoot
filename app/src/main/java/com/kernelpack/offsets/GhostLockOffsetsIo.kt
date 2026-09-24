package com.kernelpack.offsets

/** 合并两份 offsets.json 时的取值策略。 */
enum class MergePolicy {
    /** 覆盖方优先（`overlay` 非空处一律取胜）。 */
    PREFER_OVERLAY,

    /** 基准方优先（只把 `overlay` 里**基准没有**的键补进来）。 */
    ONLY_FILL_MISSING,

    /** 冲突时保留基准，但有冲突就记进 [MergeResult.conflicts] 让调用方提示用户。 */
    REPORT_CONFLICTS,
}

/** 一次合并的结果。 */
data class MergeResult(
    val document: OffsetsDocument,
    /** 两边都有、但取值不同的键：`键 → (基准值, 覆盖值)`。 */
    val conflicts: Map<String, Pair<String, String>>,
)

/**
 * offsets.json 的读 / 写 / 合并。
 *
 * 三件事都在这里，是因为它们共享同一张 [OffsetsSchema] 字段表 ——
 * 拆开就会出现"读认得某键、写不认得"的漂移。
 */
object GhostLockOffsetsIo {

    /**
     * 解析一份 offsets.json。
     *
     * - 认识的键进 [OffsetsDocument.scalars] / `symbols` / `structFields`；
     * - **不认识的键进 [OffsetsDocument.unknown] 原样保留** —— 这是往返不丢数据的前提；
     * - 标量按**原样字符串**保存：里面既有 64 位地址（`"0xffffffc0…"`）也有标志位（`"0"`），
     *   统一转数值会掉精度或误判。
     */
    fun read(text: String): OffsetsDocument {
        val root = MiniJsonCodec.parse(text)
        require(root is MiniJson.Obj) { "offsets.json 顶层必须是对象" }
        val scalars = LinkedHashMap<String, String>()
        val symbols = LinkedHashMap<String, Long>()
        val structs = LinkedHashMap<String, Long>()
        val unknown = LinkedHashMap<String, MiniJson>()

        for ((k, v) in root.fields) {
            when {
                k == "symbols" -> symbols.putAll(numMap(v, k))
                k == "struct_fields" -> structs.putAll(numMap(v, k))
                k in OffsetsSchema.SCALARS -> scalars[k] = scalarText(v)
                else -> unknown[k] = v
            }
        }
        return OffsetsDocument(scalars, symbols, structs, unknown)
    }

    /** 输出一份 offsets.json。认识的键按 schema 表顺序写，未知键附在最后。 */
    fun write(doc: OffsetsDocument, indent: String = "  "): String {
        val fields = LinkedHashMap<String, MiniJson>()
        for (k in OffsetsSchema.SCALARS) doc.scalars[k]?.let { fields[k] = MiniJson.Str(it) }
        // 不在 schema 里但调用方显式给了的标量，也一并写出（不吞）
        for ((k, v) in doc.scalars) if (k !in fields) fields[k] = MiniJson.Str(v)
        fields["symbols"] = nums(doc.symbols, OffsetsSchema.SYMBOL_KEYS)
        fields["struct_fields"] = nums(doc.structFields, OffsetsSchema.STRUCT_KEYS)
        for ((k, v) in doc.unknown) if (k !in fields) fields[k] = v
        return MiniJsonCodec.emit(MiniJson.Obj(fields), indent)
    }

    /**
     * 合并两份文档。用于「同一个内核、两次提取结果取长补短」，
     * 以及「用新提取的 json 补一份旧的、缺字段的 json」。
     */
    fun merge(base: OffsetsDocument, overlay: OffsetsDocument, policy: MergePolicy): MergeResult {
        val conflicts = LinkedHashMap<String, Pair<String, String>>()
        val scalars = LinkedHashMap(base.scalars)
        for ((k, v) in overlay.scalars) {
            val old = scalars[k]
            if (old == null) { scalars[k] = v; continue }
            if (old == v) continue
            conflicts[k] = old to v
            if (policy == MergePolicy.PREFER_OVERLAY) scalars[k] = v
        }
        val symbols = mergeNums(base.symbols, overlay.symbols, policy, conflicts)
        val structs = mergeNums(base.structFields, overlay.structFields, policy, conflicts)
        // 未知键：两边取并集，冲突同样按策略
        val unknown = LinkedHashMap(base.unknown)
        for ((k, v) in overlay.unknown) {
            val old = unknown[k]
            if (old == null) { unknown[k] = v; continue }
            if (MiniJsonCodec.emit(old) == MiniJsonCodec.emit(v)) continue
            conflicts[k] = MiniJsonCodec.emit(old) to MiniJsonCodec.emit(v)
            if (policy == MergePolicy.PREFER_OVERLAY) unknown[k] = v
        }
        return MergeResult(OffsetsDocument(scalars, symbols, structs, unknown), conflicts)
    }

    private fun mergeNums(
        base: Map<String, Long>,
        overlay: Map<String, Long>,
        policy: MergePolicy,
        conflicts: MutableMap<String, Pair<String, String>>,
    ): Map<String, Long> {
        val out = LinkedHashMap(base)
        for ((k, v) in overlay) {
            val old = out[k]
            if (old == null) { out[k] = v; continue }
            if (old == v) continue
            conflicts[k] = old.toString() to v.toString()
            if (policy == MergePolicy.PREFER_OVERLAY) out[k] = v
        }
        return out
    }

    private fun nums(src: Map<String, Long>, order: List<String>): MiniJson.Obj {
        val m = LinkedHashMap<String, MiniJson>()
        for (k in order) src[k]?.let { m[k] = MiniJson.Num(it.toString()) }
        for ((k, v) in src) if (k !in m) m[k] = MiniJson.Num(v.toString())
        return MiniJson.Obj(m)
    }

    private fun numMap(v: MiniJson, where: String): Map<String, Long> {
        require(v is MiniJson.Obj) { "$where 必须是对象" }
        val out = LinkedHashMap<String, Long>()
        for ((k, item) in v.fields) {
            out[k] = when (item) {
                is MiniJson.Num -> item.raw.toLongOrNull()
                // 有些提取器把整数写成字符串；两种都收
                is MiniJson.Str -> item.value.toLongOrNull()
                else -> null
            } ?: throw IllegalArgumentException("$where.$k 不是整数：${MiniJsonCodec.emit(item)}")
        }
        return out
    }

    private fun scalarText(v: MiniJson): String = when (v) {
        is MiniJson.Str -> v.value
        is MiniJson.Num -> v.raw
        is MiniJson.Bool -> if (v.value) "1" else "0"
        MiniJson.Null -> ""
        else -> MiniJsonCodec.emit(v)
    }
}
