package com.kernelpack.offset

/**
 * 偏移的来源层级与条目状态 —— **本工程最不能省的一层**。
 *
 * 为什么必须分开记（两个真实踩坑）
 * ----------------------------------
 * 1. `Meowkis/ghostlock-samsung-research` 的 `src/offsets/5.15.h` 是**占位符**：
 *    文件名写着 5.15，数值却不是从任何 5.15 内核量出来的。若把它当"可用基线"收录，
 *    使用者会拿一组假数字去改内核内存。所以「有这份文件」与「这份文件可信」必须分开表达。
 * 2. `233laoliu/mt6985-CVE-2026-43499` 的 `VERIFICATION.md` 记录了一个反例：
 *    照 AOSP 通用 GKI 分支的 ABI XML 抄 `file_operations`，**错了 6 处** ——
 *    因为厂商内核改了结构体。所以「同版本 AOSP 抄来的」只能算交叉参考，不能算实测。
 *
 * 结论：每个偏移都要能回答两个问题 ——「谁量的？」「量的是哪台机器？」。
 * 本文件不产生任何数值，只负责**标注可信度**；数值只能来自真实产物。
 */
enum class SourceTier(val label: String, val trustworthy: Boolean) {
    /**
     * 从**目标内核本身**量出来的：BTF / kallsyms / 反汇编 / init_task 静态数据。
     * 这是唯一可以直接采信的一档。
     */
    MEASURED("实测", true),

    /**
     * 从**为该内核编译的载荷 .so** 里提取出来的烘焙常量。
     * 之所以单独一档：.so 的常量可能由构建脚本生成，未必逐项复核过，
     * 但它确实是为该内核编的，比跨版本外推强得多。
     */
    PAYLOAD_BAKED("载荷 .so 烘焙常量", true),

    /**
     * 上游仓库的 `target.h` 原文抄录（如 H80GT / Neo11Plus 的 18 份与 20 份）。
     * 可信，但注意：**厂商私有字段不可跨厂商照搬**（荣耀的 vr.ko/反 root 与 vivo 不同）。
     */
    UPSTREAM_TARGET_H("上游 target.h", true),

    /**
     * 交叉参考：来自**同内核版本的其它机型**，或通用 AOSP/GKI 分支。
     * 只能用来"缩小范围"，**不可直接用于构建**（mt6985 的 file_operations 错 6 处就是这一类）。
     */
    CROSS_REFERENCE("交叉参考（不可直接使用）", false),

    /**
     * 占位符：文件里有这个值，但它不是从目标内核量出来的。
     * 见到这一档必须当成"没有基线"处理。
     */
    PLACEHOLDER("占位符（不可使用）", false),
}

/** 单条偏移的可信度标注：这条值是给**哪个内核**量的、谁量的、怎么量的。 */
data class OffsetNote(
    /** 偏移名，与 target.h 里的宏名一致，如 `WAITER_TASK_OFF`。 */
    val key: String,
    val tier: SourceTier,
    /** 量出这条值的内核 release 串，如 `6.1.145-android14-11-maybe-dirty`。**用于展示**。 */
    val measuredOn: String,
    /** 出处（仓库 + 文件路径，或"本机 /sys/kernel/btf/vmlinux"）。 */
    val source: String,
    /** 补充说明，例如"该值在 CROSS_REFERENCE 档只作范围参考"。 */
    val note: String = "",
    /**
     * 归属的内核锚点，**默认取 [measuredOn]**。用于一致性判定，不用于展示。
     *
     * 为什么单独立一个字段：同一条基线里，各偏移的"出处精度"天然不同 ——
     * 载荷烘焙常量只能追溯到"这份 .so 是给哪条内核编的"（如 `6.6.89`），
     * 而布局是从某台机器上量出来的（release 串可能是
     * `6.6.89-android15-8-g1f71897ac249-abogki467805059-4k`）。
     * 若直接拿 [measuredOn] 做判定，这两个**精度不同但内核同一个**的值
     * 会被误判成"跨内核拼凑"，把一条本来可信的基线降级成不可用。
     *
     * 本工程第一次跑这套测试时就踩了这个：两条内置基线都被判成了 CROSS_REFERENCE。
     */
    val anchor: String = measuredOn,
)

/**
 * 一组偏移的整体状态。由 [OffsetSet] 按**最弱一环**推导，不允许手工指定 ——
 * 手工指定就会出现"整体标 VERIFIED、里面混着占位符"这种假象。
 */
enum class OffsetStatus(val label: String) {
    /** 组内每条偏移都是可信档（[SourceTier.trustworthy]），且 [OffsetNote.anchor] 一致。 */
    VERIFIED("已验证"),

    /** 组内混有 [SourceTier.CROSS_REFERENCE]：可用来估算，**不可用于构建**。 */
    CROSS_REFERENCE("交叉参考"),

    /** 组内混有 [SourceTier.PLACEHOLDER]：等同"没有基线"，必须拒绝构建。 */
    PLACEHOLDER("占位符"),
}

/** 一个内核档位下的一整套偏移 + 逐条标注。 */
class OffsetSet(val notes: List<OffsetNote>) {

    constructor(vararg notes: OffsetNote) : this(notes.toList())

    init {
        val dup = notes.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "同一偏移被标注了多次：$dup" }
    }

    val keys: Set<String> get() = notes.map { it.key }.toSet()

    operator fun get(key: String): OffsetNote? = notes.firstOrNull { it.key == key }

    fun tierOf(key: String): SourceTier? = this[key]?.tier

    /**
     * 整体状态：**最弱一环决定**。
     *
     * 之所以不是"多数表决"：一组偏移是**一起**写进内核内存的，其中一条是占位符，
     * 整组就不能用。按比例投票会让"80% 可信"变成可用，那是假的。
     */
    val status: OffsetStatus
        get() = when {
            notes.isEmpty() -> OffsetStatus.PLACEHOLDER
            notes.any { it.tier == SourceTier.PLACEHOLDER } -> OffsetStatus.PLACEHOLDER
            notes.any { !it.tier.trustworthy } -> OffsetStatus.CROSS_REFERENCE
            // 都可信，但必须锚在同一个内核上：跨内核拼出来的组仍然是交叉参考。
            // 判据用 anchor 而不是 measuredOn —— 后者只是精度差异（见 OffsetNote.anchor 的说明）。
            notes.map { it.anchor }.distinct().size > 1 -> OffsetStatus.CROSS_REFERENCE
            else -> OffsetStatus.VERIFIED
        }

    /** 是否可以直接用于构建（只有 [OffsetStatus.VERIFIED] 可以）。 */
    val usable: Boolean get() = status == OffsetStatus.VERIFIED

    /** 锚定在哪个内核（唯一时给一条，不唯一时列出全部）。 */
    val anchor: String
        get() = notes.map { it.anchor }.distinct().joinToString(" / ")

    /** 各条偏移的出处精度不同时，展示用的完整列表。 */
    val measuredOn: String
        get() = notes.map { it.measuredOn }.distinct().joinToString(" / ")

    /** 哪些偏移把它拖成了不可用 —— 报缺要**点名**，不能只说"整体不可用"。 */
    fun weakest(): List<OffsetNote> = when (status) {
        OffsetStatus.VERIFIED -> emptyList()
        OffsetStatus.CROSS_REFERENCE -> notes.filter { !it.tier.trustworthy }
        OffsetStatus.PLACEHOLDER -> notes.filter { it.tier == SourceTier.PLACEHOLDER }
    }

    fun summary(): String {
        val bad = weakest()
        val tail = if (bad.isEmpty()) "" else
            "；拖累项：" + bad.joinToString("、") { "${it.key}(${it.tier.label})" }
        return "${status.label}（${notes.size} 条，锚点 $anchor）$tail"
    }
}
