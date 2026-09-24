package com.kernelpack.offsets

/**
 * 偏移的**来源**。决定它在界面上的可信度标注 —— 绝不能混着显示。
 */
enum class OffsetOrigin {
    /** 来自运行时的 `offsets.json`（GhostLock 偏移表方案）。 */
    OFFSETS_JSON,

    /** 来自我方编译期基线（`BaselineRegistry` 登记的逐条标注）。 */
    COMPILED_BASELINE,

    /** 来自载荷里烤死的兜底常量（`target.h`）。可信度最低，但永远存在。 */
    COMPILED_FALLBACK,
}

/** 一个字段的解析结果：值 + 它究竟从哪来。 */
data class ResolvedOffset(
    val key: String,
    val value: Long,
    val origin: OffsetOrigin,
)

/**
 * 把「GhostLock 偏移表方案」与「软件原本的动态载荷方案」**合成一条解析流水线**。
 *
 * ### 为什么能合，而不是两套并存
 *
 * 两者的前半段是同一件事：`boot.img → 内核符号 → 一套偏移`。
 * 差别只在交付 —— 我方把偏移**写进 .so**（编译期），
 * GhostLock 把偏移**存成 json 运行时读**。所以真正的合并点不是"两份数据取长补短"，
 * 而是**照搬它的 `_RSO(field, fallback)` 覆盖模型**：
 *
 * ```
 *   运行时 offsets.json  >  我方编译期基线  >  载荷里烤死的兜底常量
 * ```
 *
 * 优先级高的存在就用它，否则逐级回落。这样一来：
 *
 * - **23 MB 的专用载荷继续当安全默认** —— 什么都没配的机器行为完全不变；
 * - **offsets.json 只作增强** —— 能在不新增任何 `.so` 的前提下，
 *   把覆盖面扩到我们**没有编译期基线**的内核版本上（例如上游那 50 个）；
 * - **每个字段都带来源** —— 满足本工程铁律：偏移必须可追溯，缺就报缺，
 *   不允许出现"某个值不知道从哪来"。
 *
 * ### 与「外推」的区别（必须说清）
 *
 * 本解析器**不做任何跨版本推断**。它只是在**三个已经存在的来源**之间按优先级取值：
 * 每个值都来自某份真实产物（json / 基线 / 载荷常量）。
 * 一个来源里没有的键，就是没有 —— 会留在 [UnifiedOffsets.missing] 里报缺，
 * **绝不**拿别的内核的数值顶上。
 */
object UnifiedOffsetResolver {

    /**
     * @param json 运行时的 offsets.json；`null` 表示用户没提供。
     * @param baseline 我方编译期基线（`BaselineEntry.offsets` 展平后的键值）。
     * @param fallback 载荷里烤死的兜底常量。
     */
    fun resolve(
        json: OffsetsDocument?,
        baseline: Map<String, Long>,
        fallback: Map<String, Long>,
    ): UnifiedOffsets {
        val keys = LinkedHashSet<String>()
        keys += json?.symbols.orEmpty().keys
        keys += json?.structFields.orEmpty().keys
        keys += baseline.keys
        keys += fallback.keys

        val resolved = LinkedHashMap<String, ResolvedOffset>()
        val missing = mutableListOf<String>()

        for (k in keys) {
            val fromJson = json?.symbols?.get(k) ?: json?.structFields?.get(k)
            val fromBaseline = baseline[k]
            val fromFallback = fallback[k]
            when {
                fromJson != null -> resolved[k] = ResolvedOffset(k, fromJson, OffsetOrigin.OFFSETS_JSON)
                fromBaseline != null -> resolved[k] = ResolvedOffset(k, fromBaseline, OffsetOrigin.COMPILED_BASELINE)
                fromFallback != null -> resolved[k] = ResolvedOffset(k, fromFallback, OffsetOrigin.COMPILED_FALLBACK)
                // 三个来源都没有 —— **如实报缺**，不猜
                else -> missing += k
            }
        }
        return UnifiedOffsets(resolved, missing)
    }
}

/** 统一解析的结果。 */
data class UnifiedOffsets(
    val fields: Map<String, ResolvedOffset>,
    /** 三个来源都没有的键 —— 如实报缺，调用方应据此阻断或提示，而不是外推。 */
    val missing: List<String>,
) {
    operator fun get(key: String): Long? = fields[key]?.value
    fun originOf(key: String): OffsetOrigin? = fields[key]?.origin

    /** 有多少字段是靠运行时表覆盖来的（界面可据此标注"已用导入的偏移表增强"）。 */
    val overriddenCount: Int get() = fields.values.count { it.origin == OffsetOrigin.OFFSETS_JSON }

    val isComplete: Boolean get() = missing.isEmpty()

    /** 按来源分组的统计，便于界面如实呈现"这套偏移是怎么来的"。 */
    fun countByOrigin(): Map<OffsetOrigin, Int> =
        fields.values.groupingBy { it.origin }.eachCount()
}
