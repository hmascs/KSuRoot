package com.kernelpack.profile

import com.kernelpack.offset.OffsetNote
import com.kernelpack.offset.OffsetSet
import com.kernelpack.offset.OffsetStatus
import com.kernelpack.offset.PselectFeasibility
import com.kernelpack.offset.SourceTier

/**
 * 基线注册表：按 **(机型, 固件, 内核系列, GKI 分支) 四元组**索引基线。
 *
 * 为什么是四元组（每一步都有实测依据）
 * ------------------------------------
 * 1. **不能"一个 GKI 版本一个基线"**：两个已核实的开源实现都是「一机一档」——
 *    `yakidango-official/GhostLock-H80GT` 有 18 份 target.h（命名 `annap-AGT-AN00_<MagicOS版本>`，
 *    内核**全是** 5.10.236，固件从 8.0.0.128 到 9.0.0.230）；`boxiaolanya2008/...-Neo11Plus`
 *    ~20 份（命名 `komodo-CP2A.260605.012`）。→ 所以要有 **(机型, 固件)**。
 * 2. **不能只看内核系列**：`233laoliu/mt6985-CVE-2026-43499` 证明同一内核大版本、不同 GKI 分支，
 *    `task_struct` 能差 0x40~0x88；照 AOSP 通用分支的 ABI XML 抄 `file_operations` **错了 6 处**。
 *    → 所以要有 **GKI 分支**（`android14-11` / `android15-8` 这种）。
 * 3. **不能按内核版本外推布局**：本工程实测 6.1.145 的 `rt_mutex_waiter` 是 88 字节，
 *    而上游 `JoinChang/ghostlock-oneplus` 把 6.1 一概当 80 字节的 compact 形态。
 *    同一厂商同一内核版本号，形态都可能不同。→ [BaselineEntry.waiterLayout] **必须逐机型实测**。
 *
 * [风险] 注册表只做"索引与匹配"，不含任何偏移数值。基线里的偏移必须来自真实产物
 * （载荷 .so / 上游 target.h / 目标内核的 BTF 或反汇编），本工程**不生成也不推测**偏移。
 */
enum class BaselineScheme(val label: String) {
    /** 通用方案：IonStack 上游分支（Pixel/GKI，不含厂商绕过）。 */
    UNIVERSAL("通用方案"),

    /** vivo / iQOO 专用：额外做 vr.ko 反 root 绕过。 */
    VIVO("vivo / iQOO"),

    UNKNOWN("未标注"),
}

/** 一条基线 + 它的适用面、出处与逐项可信度。 */
data class BaselineEntry(
    val profile: BaselineProfile,
    val scheme: BaselineScheme,

    // ---------- 四元组的后两项（机型由 profile.id / variantLabel 表达，固件见 firmware） ----------

    /** 机型代号，如 `PD2520`、`annap-AGT-AN00`、`komodo`。 */
    val device: String,
    /** 固件 / 构建号，如 `BP2A.250605.031.A3`、`CP2A.260605.012`、`9.0.0.230`。 */
    val firmware: String,
    /** 内核系列，如 `6.6`。 */
    val kernelSeries: String,
    /**
     * GKI 分支，如 `android15-8`、`android14-11`；**未知就填 null，不要猜**。
     * 取值来自内核 release 串的 `-androidNN-K-` 段（见 [BaselineRegistry.gkiBranchOf]）。
     */
    val gkiBranch: String?,

    /** 出处（哪个仓库的哪份 target.h / 哪次 release），**人可核查**。 */
    val source: String,

    /** 整套偏移 + 逐条来源标注。 */
    val offsets: OffsetSet,

    /** pselect 栈覆盖可行性（任务 7 引入）。null = 本工程没有该机型的落点实测。 */
    val feasibility: PselectFeasibility? = null,

    /** 补充说明（例如"厂商私有字段不可跨厂商照搬"）。 */
    val notes: List<String> = emptyList(),
) {
    /** 兼容旧字段名：整体来源标注取 [OffsetSet] 的状态标签。 */
    val provenance: String get() = offsets.status.label

    /** 是否声明了该内核系列。用于筛选，不用于"是否可用"的最终判定。 */
    fun matchesSeries(series: String?): Boolean = series != null && series == kernelSeries

    /** 是否可以直接用于构建（偏移组必须是 VERIFIED）。 */
    val buildable: Boolean get() = offsets.usable

    /** 四元组的紧凑表示，用于 UI 与日志。 */
    fun quad(): String = "$device/$firmware/$kernelSeries/${gkiBranch ?: "分支未知"}"

    /**
     * 与另一个内核档位的**差异清单** —— 回答"为什么不能用这条基线"。
     * 缺基线时要报给用户的就是这个，而不是一句"不匹配"。
     */
    fun diffAgainst(otherSeries: String, otherBranch: String?): List<String> {
        val out = ArrayList<String>()
        if (otherSeries != kernelSeries) out.add("内核系列 $kernelSeries ≠ $otherSeries")
        if (gkiBranch != null && otherBranch != null && gkiBranch != otherBranch) {
            out.add("GKI 分支 $gkiBranch ≠ $otherBranch")
        }
        return out
    }
}

/** 查询结果。 */
sealed class BaselineLookup {

    /** 匹配到的元信息，Found 与 Missing 共用（Missing 也要能解释"现有的是什么"）。 */
    abstract val available: List<String>
    abstract val notes: List<String>

    /** 找到可用基线。 */
    data class Found(
        val entry: BaselineEntry,
        override val notes: List<String>,
        /** true = 只有内核系列对上了，GKI 分支/机型没对上（降级匹配，必须提示）。 */
        val degraded: Boolean = false,
    ) : BaselineLookup() {
        override val available: List<String> get() = emptyList()
    }

    /**
     * **没有**适配该内核/分支/方案的基线。
     *
     * 这不是异常，而是正常会发生的状态（5.x / 6.1 目前就没有）。
     * 调用方应当把它显示给用户，并给出获取途径，而不是回退到别的基线。
     */
    data class Missing(
        val kernelSeries: String,
        val scheme: BaselineScheme,
        /** 查询里给出的 GKI 分支（可能为 null = 没测出来）。 */
        val gkiBranch: String?,
        override val available: List<String>,
        override val notes: List<String>,
        val howTo: List<String>,
    ) : BaselineLookup()
}

object BaselineRegistry {

    /**
     * **产品口径上声明支持**的内核系列（2026-09-12 定的）。
     *
     * ```
     *   主线  6.6 / 6.12        —— 「通用方案」与「vivo / iQOO 方案」**两个都要覆盖**
     *   测试  5.x               —— 仅 beta，UI 必须标注「测试」
     *
     * [2026-09-12 口径变更] 6.1 从主线移除（用户指定只支持 6.6 / 6.12）。
     * 6.1 既不是主线也不走 5.x 的 beta 开关 —— 构建前会被直接拒绝。
     * ```
     *
     * ⚠️ **这是「支持」而不是「已登记」**，两者必须分开看：
     * - 「支持」= 产品承诺覆盖这些内核，缺基线时要**明确报缺并给获取途径**；
     * - 「已登记」= [entries] 里真有那一档的偏移产物。
     *
     * 混为一谈就会出现"声称支持 6.12，实际一条基线都没有，却静默放行"——
     * 那正是本工程一直在防的那种假象。所以本表只用来生成提示文案与覆盖率报告，
     * **绝不**参与匹配判定（匹配只看 [entries]）。
     */
    val MAINLINE_SERIES: List<String> = listOf("6.6", "6.12")

    /** 声明为测试线、仅 beta 的内核系列。 */
    val TEST_SERIES: List<String> = listOf("5.10", "5.15")

    /**
     * 现有基线。**只有两条，都是 6.6** —— 这是当前的真实状态，不要谎报覆盖面。
     *
     * 新增一条基线的正确方式（顺序很重要）：
     *   1. 拿到一份**为该内核编译的载荷 .so**（或上游仓库的 target.h）
     *   2. 用宿主侧工具从 .so 里提取烘焙常量 / 从 target.h 抄下来，
     *      并给**每一条**偏移写 [com.kernelpack.offset.OffsetNote]（来源 + 量自哪个内核）
     *   3. 若能把目标内核的 BTF 或反汇编拿到，填 [BaselineEntry.feasibility] 的落点实测
     *   4. 登记到本表，写清 [BaselineEntry.source]
     * **不要**照着另一档的数值改几个数字充数，也不要按内核版本外推布局。
     */
    val entries: List<BaselineEntry> = listOf(
        BaselineEntry(
            profile = BaselineProfiles.PD2520,
            scheme = BaselineScheme.VIVO,
            device = "PD2520",
            firmware = "BP2A.250605.031.A3",
            kernelSeries = "6.6",
            gkiBranch = "android15-8",
            source = "boxiaolanya2008/CVE-2026-43499-Neo11Plus · PD2520-BP2A.250605.031.A3",
            offsets = pd2520Offsets(),
            feasibility = null,
            notes = listOf(
                "vivo / iQOO 专用分支：比通用方案多一条 vr.ko 反 root 绕过。",
                "厂商私有字段（vr.ko / 反 root）不可跨厂商照搬。",
            ),
        ),
        BaselineEntry(
            profile = BaselineProfiles.IONSTACK_P10,
            scheme = BaselineScheme.UNIVERSAL,
            device = "P10",
            firmware = "CP2A.260605.012",
            kernelSeries = "6.6",
            gkiBranch = "android15-8",
            source = "NebuSec/CyberMeowfia · IonStack 通用分支（frankel-CP2A.260605.012）",
            offsets = ionstackOffsets(),
            feasibility = null,
            notes = listOf(
                "Pixel / GKI 通用分支，无厂商绕过。",
                "内置载荷的编译期常量取自上游 frankel-CP2A.260605.012/target.h。",
            ),
        ),
    )

    /**
     * 6.6 两条基线的偏移标注。
     *
     * 这两份数值是本工程**已经内置并跑通过端到端**的载荷常量（来源见每条的 source），
     * 所以它们本身按 [SourceTier.PAYLOAD_BAKED] 记；`rt_mutex_waiter` 的**布局**
     * 另有本机 BTF 实测支撑，记 [SourceTier.MEASURED]。
     *
     * [锚点] 两条都锚在各自 `profile.kernelVersion` 上，而**不是**锚在本机那条完整串
     * `6.6.89-android15-8-g1f71897ac249-abogki467805059-4k` 上 —— 后者只是**精度更高**，
     * 内核是同一个。本工程第一版用精确串当锚点，结果把两条基线都误判成"跨内核拼凑"
     * 而整体降级（测试抓出来的）。详见 [OffsetNote.anchor]。
     */
    private fun pd2520Offsets(): OffsetSet {
        val where = "boxiaolanya2008/CVE-2026-43499-Neo11Plus · PD2520-BP2A.250605.031.A3"
        return buildOffsets(BaselineProfiles.PD2520, where)
    }

    private fun ionstackOffsets(): OffsetSet {
        val where = "NebuSec/CyberMeowfia · IonStack · frankel-CP2A.260605.012"
        return buildOffsets(BaselineProfiles.IONSTACK_P10, where)
    }

    /**
     * 把 [BaselineProfile.symbolOffsets] 的键逐条转成带标注的 [OffsetSet]。
     *
     * 这里**不新增任何数值**：键和值都来自既有 profile，本函数只负责把它们包上来源。
     * 之所以要包：用户在意的正是"这个数字谁量的"，而旧结构只有一个整体 provenance 字符串，
     * 无法回答"这一条是实测还是抄的"。
     */
    private fun buildOffsets(
        profile: BaselineProfile,
        where: String,
    ): OffsetSet {
        val anchor = profile.kernelVersion
        val notes = profile.symbolOffsets.keys.map { key ->
            OffsetNote(
                key = key,
                tier = SourceTier.PAYLOAD_BAKED,
                measuredOn = anchor,
                source = where,
                note = "取自内置载荷的编译期常量；打包时会被 boot.img 解析出的新值逐项改写。",
                anchor = anchor,
            )
        } + listOf(
            OffsetNote(
                key = "RT_WAITER_LAYOUT",
                tier = SourceTier.MEASURED,
                measuredOn = "6.6.89-android15-8-g1f71897ac249-abogki467805059-4k",
                source = "本机 /sys/kernel/btf/vmlinux（140652 个类型）",
                note = "nested 形态：task@0x50 lock@0x58 wake_state@0x60 ww_ctx@0x68，112 字节。",
                anchor = anchor,
            ),
        )
        return OffsetSet(notes)
    }

    // ------------------------------------------------------------------ 查询

    fun forScheme(scheme: BaselineScheme): List<BaselineEntry> =
        entries.filter { it.scheme == scheme }.ifEmpty { entries }

    fun byId(id: String): BaselineEntry? = entries.firstOrNull { it.profile.id == id }

    /**
     * 按 **(方案, 内核系列)** 取应该用哪份载荷档位的 id。
     *
     * 这是「两个方案 × 两个主线系列」的**唯一路由点**：打包时先解析出内核系列，
     * 再来这里换档。返回 null = 该组合**还没有登记偏移产物** —— 调用方必须据此
     * **如实报缺、不要拿别的系列顶替**（拿 6.6 的数值去打 6.12 的内核内存，
     * 正是本工程两次勘误的同一种错）。
     *
     * 好处：以后补 6.12 只需往 [entries] 加一条，**不用改任何代码路径**。
     */
    fun profileIdFor(scheme: BaselineScheme, kernelSeries: String): String? =
        entries.firstOrNull { it.scheme == scheme && it.kernelSeries == kernelSeries }
            ?.profile?.id

    /**
     * 按内容识别基线：先 sha256 精确匹配，再退回 `BUILD_VARIANT_LABEL` 字符串匹配。
     *
     * 双轨的必要性：同一份 .so 可能服务多个固件（上游就是这么用的），
     * 只认 sha256 会把这种情况判成"未知载荷"。
     */
    fun findByBytes(baseLibrary: ByteArray): BaselineEntry? {
        val digest = BaselineProfiles.sha256Hex(baseLibrary)
        entries.firstOrNull { it.profile.sha256?.equals(digest, ignoreCase = true) == true }?.let { return it }
        val text = String(baseLibrary, Charsets.ISO_8859_1)
        return entries.firstOrNull { e ->
            val label = e.profile.variantLabel
            label.isNotBlank() && text.contains(label)
        }
    }

    /** 兼容旧签名：只有内核 release 与方案时的查询（不带 GKI 分支）。 */
    fun lookup(kernelRelease: String, scheme: BaselineScheme): BaselineLookup =
        lookup(kernelRelease, scheme, gkiBranch = gkiBranchOf(kernelRelease))

    /**
     * 为「内核 release + 方案 + GKI 分支」找基线。
     *
     * 匹配强度分三级，**每一级都会如实回报**：
     * ```
     *   1. 系列 + 方案 + 分支 全中        → Found(degraded = false)
     *   2. 系列 + 方案 中、分支没中/不可知 → Found(degraded = true)  ← 必须提示用户
     *   3. 系列没中                       → Missing（附差异清单与获取途径）
     * ```
     * 之所以"分支没中"还要放行（而不是直接 Missing）：很多设备从 boot.img 里
     * 解析不出 GKI 分支（release 串被厂商改过）。此时**不应该**让用户彻底无法选基线，
     * 但也不能假装匹配得很准 —— 所以降级 + 明确提示。
     *
     * 找不到时**不返回默认档位**，而是返回 [BaselineLookup.Missing] 并附带获取途径。
     */
    fun lookup(
        kernelRelease: String,
        scheme: BaselineScheme,
        gkiBranch: String?,
    ): BaselineLookup {
        val series = seriesOf(kernelRelease)
            ?: return BaselineLookup.Missing(
                kernelSeries = "未知",
                scheme = scheme,
                gkiBranch = gkiBranch,
                available = availableLabels(),
                notes = listOf("boot.img 的 release 串是「$kernelRelease」，解析不出主.次版本号。"),
                howTo = listOf("请确认这个 boot.img 确实来自目标设备。"),
            )

        val sameSeries = entries.filter { it.kernelSeries == series && it.scheme == scheme }

        // 第 1 级：分支也对上
        if (gkiBranch != null) {
            sameSeries.firstOrNull { it.gkiBranch == gkiBranch }?.let { hit ->
                val notes = ArrayList<String>()
                if (sameSeries.size > 1) {
                    notes.add("该内核+方案下有 ${sameSeries.size} 条基线，已取第一条；" +
                        "多条基线通常对应不同机型，请核对机型代号")
                }
                notes.addAll(entryNotes(hit))
                return BaselineLookup.Found(hit, notes, degraded = false)
            }
        }

        // 第 2 级：系列+方案对上，分支对不上（或分支不可知）→ 降级放行 + 提示
        sameSeries.firstOrNull()?.let { hit ->
            val notes = ArrayList<String>()
            val why = if (gkiBranch == null) {
                "没能从 release 串解析出 GKI 分支，已按「内核系列 + 方案」降级匹配"
            } else {
                "GKI 分支不匹配：基线是 ${hit.gkiBranch ?: "未标注"}，实测是 $gkiBranch"
            }
            notes.add("[注意] $why")
            notes.add("同一内核大版本、不同 GKI 分支的结构体布局可能不同" +
                "（mt6985 案例里 file_operations 差了 6 处），请核对机型代号再构建。")
            notes.addAll(entryNotes(hit))
            return BaselineLookup.Found(hit, notes, degraded = true)
        }

        // 第 3 级：缺基线
        val howTo = ArrayList<String>()
        howTo.add("需要的是一份**为该内核（$kernelRelease）编译的载荷 .so**，" +
            "或上游仓库里对应机型的 target.h。")
        val sameScheme = entries.filter { it.scheme == scheme }
        if (sameScheme.isNotEmpty()) {
            howTo.add("现有 ${scheme.label} 基线只覆盖：" +
                sameScheme.joinToString("、") { "${it.kernelSeries}（${it.quad()}）"} +
                " —— 内核系列不同，不可替代。")
        }
        // [支持 ≠ 已登记] 这两句话必须分开说，否则用户会以为"声明支持"就等于"能用"。
        val tierWord = if (series in MAINLINE_SERIES) "主线" else "测试"
        howTo.add(
            "产品口径：$series 属**$tierWord**线" +
                "（主线 ${MAINLINE_SERIES.joinToString(" / ")}；测试 ${TEST_SERIES.joinToString(" / ")}）。" +
                if (series in MAINLINE_SERIES) {
                    "本工程承诺覆盖它，但**当前还没有登记这一档的偏移产物** —— " +
                        "所以这次只能报缺，不能拿别的系列顶替。"
                } else {
                    "测试线只做 beta 验证，未登记即不产出。"
                }
        )
        howTo.add("可参考的已核实来源：yakidango-official/GhostLock-H80GT（5.10.236，18 份 target.h）、" +
            "boxiaolanya2008/CVE-2026-43499-Neo11Plus（多机型 target.h）、" +
            "JoinChang/ghostlock-oneplus（5.10/6.1/6.6/6.12 多机型，含 pselect 可行性方法论）")
        howTo.add("注意：厂商私有字段不可跨厂商照搬（荣耀的 vr.ko/反 root 与 vivo 不同）。")

        return BaselineLookup.Missing(
            kernelSeries = series,
            scheme = scheme,
            gkiBranch = gkiBranch,
            available = availableLabels(),
            notes = listOf(
                "没有 $series（分支 ${gkiBranch ?: "未知"}）的 ${scheme.label} 基线。",
                "（$series 已列入${if (series in MAINLINE_SERIES) "主线" else "测试"}支持范围，" +
                    "但尚未登记偏移产物。）",
            ),
            howTo = howTo,
        )
    }

    private fun entryNotes(e: BaselineEntry): List<String> = buildList {
        add("匹配：${e.quad()}　出处：${e.source}")
        add("偏移状态：${e.offsets.summary()}")
        if (!e.buildable) {
            add("[拒绝构建] 该基线偏移组不是 VERIFIED 档，" +
                "拖累项：" + e.offsets.weakest().joinToString("、") { "${it.key}(${it.tier.label})" })
        }
        e.feasibility?.let { add("pselect 可行性：${it.summary()}") }
        addAll(e.notes)
    }

    private fun availableLabels(): List<String> =
        entries.map { "${it.quad()}·${it.scheme.label}·${it.offsets.status.label}" }

    // ------------------------------------------------------------------ 解析

    fun seriesOf(release: String): String? =
        Regex("^(\\d+)\\.(\\d+)").find(release.trim())
            ?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }

    /**
     * 从内核 release 串里取 GKI 分支。
     *
     * 依据真实串形态：
     * ```
     *   6.6.89-android15-8-g1f71897ac249-abogki467805059-4k   → android15-8
     *   6.1.145-android14-11-maybe-dirty                     → android14-11
     *   6.12.23-android16-5-g82efd98459a2-ab14457512-4k      → android16-5
     * ```
     * 取不到就返回 null（**不猜**）。厂商改过 release 串的设备确实取不到，
     * 此时上层走降级匹配并提示。
     */
    fun gkiBranchOf(release: String): String? =
        Regex("-(android\\d+-\\d+)(?:-|$)").find(release.trim())?.groupValues?.get(1)

    /** 偏移状态直方图，用于设置页/诊断一句话概览。 */
    fun statusCounts(): Map<OffsetStatus, Int> =
        entries.groupingBy { it.offsets.status }.eachCount()

    /**
     * 覆盖率报告：**声明支持** vs **实际已登记**。
     *
     * 这是本工程最容易自欺的一个角度 —— "我们支持 6.12" 与 "我们有一条 6.12 基线"
     * 是两句完全不同的话。把两者并排打出来，缺哪一档一眼可见。
     */
    fun coverageReport(): List<String> = buildList {
        for (scheme in listOf(BaselineScheme.UNIVERSAL, BaselineScheme.VIVO)) {
            val registered = entries.filter { it.scheme == scheme }.map { it.kernelSeries }.toSet()
            for (series in MAINLINE_SERIES) {
                val mark = if (series in registered) "✅ 已登记" else "❌ 未登记（会明确报缺）"
                add("$series · ${scheme.label}：$mark")
            }
        }
        val testRegistered = entries.filter { it.kernelSeries in TEST_SERIES }
        add(
            "测试线 ${TEST_SERIES.joinToString(" / ")}：已登记 ${testRegistered.size} 档" +
                "（仅 beta，设置页已标「测试」）"
        )
    }

    /** 可行性能实测出来的基线占比 —— 缺基线时最该看的一个数。 */
    fun feasibilityCoverage(): String {
        val withLayout = entries.count { it.feasibility?.layout?.known == true }
        val measured = entries.count { it.feasibility?.measuredWord != null }
        return "布局已知 $withLayout/${entries.size}，落点已实测 $measured/${entries.size}"
    }

    fun summary(): String = entries.joinToString("；") {
        "${it.quad()}（${it.scheme.label}·${it.offsets.status.label}）"
    }
}
