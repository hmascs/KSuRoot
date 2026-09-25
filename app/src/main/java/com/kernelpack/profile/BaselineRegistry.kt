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

    /**
     * **beta 标记** —— 这一档还没有在真机上验证过。
     *
     * 存在的理由：蓝厂方案的若干档是**由通用方案的偏移派生**出来的
     * （内核偏移本身与厂商无关，厂商差异只在 vr.ko 那一层），
     * 派生出来的组合**没有在任何蓝厂设备上跑过**。
     * 不标出来就等于谎报验证状态 —— 那正是本工程一直在防的假象。
     * 界面上必须与已实测的档位区分显示。
     */
    val beta: Boolean = false,
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
     * **产品口径上声明支持**的内核系列。
     *
     * ```
     *   主线  6.1 / 6.6 / 6.12  —— 「通用方案」与「vivo / iQOO 方案」**两个都要覆盖**
     *   测试  5.x               —— 仅 beta，UI 必须标注「测试」
     * ```
     *
     * ### 6.1 的两次口径变更（留档，别再改回去）
     *
     * - **[2026-09-12] 从主线移除** —— 当时的理由：6.1 是 flat 形态
     *   （88 字节 / task@0x30），与 6.6/6.12 的 nested（112 字节 / task@0x50）不是一套，
     *   硬按主线偏移构建会打到错误的结构体字段上。**这个理由本身是对的。**
     * - **[2026-09-25] 重新加入主线** —— 因为**我们为 6.1 编出了专属基线**
     *   （`libbaseline_6_1.so`，结构体偏移取 6_1 族），
     *   并且基线库已改成**按结构体族选择**（见 [BaselineLibraries]）。
     *   也就是说：当初拒绝的理由（拿 6.6 的偏移硬打 6.1）现在**不再成立**。
     *
     * ⚠️ 这两条要一起读：**6.1 能进主线的唯一前提是"它有自己的族基线"**。
     * 若哪天那份基线被拿掉，6.1 必须同时退回拒绝 —— 否则就真的会拿 6.6 的偏移去打 6.1。
     *
     * ⚠️ **这是「支持」而不是「已登记」**，两者必须分开看：
     * - 「支持」= 产品承诺覆盖这些内核，缺基线时要**明确报缺并给获取途径**；
     * - 「已登记」= [entries] 里真有那一档的偏移产物。
     *
     * 混为一谈就会出现"声称支持 6.12，实际一条基线都没有，却静默放行"——
     * 那正是本工程一直在防的那种假象。所以本表只用来生成提示文案与覆盖率报告，
     * **绝不**参与匹配判定（匹配只看 [entries]）。
     */
    /**
     * 声明为主线、可正常构建的内核系列。
     *
     * [2026-09-25] 加入 **`6.1`** —— 此前它被排除在外，理由是"6.1 是 flat 形态
     * （88 字节 / task@0x30），与 6.6/6.12 的 nested 不是一套"。
     * 那个理由本身没错，**但结论已经过时**：我们已经为 6.1 编出了**专属基线**
     * （`libbaseline_6_1.so`，结构体偏移取 tokay target.h 的 6_1 族），
     * 所以"拿 6.6 的偏移打 6.1"这个危险不再存在 —— 现在走的是 6.1 自己的族。
     */
    val MAINLINE_SERIES: List<String> = listOf("6.1", "6.6", "6.12")

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
     * ★ 结构体族 → 基线库文件名。
     *
     * ### 为什么必须按族选，而不是每个方案一个固定库
     *
     * `PayloadScheme` 原本硬编码一个库（通用→`libionstack.so`、蓝厂→`libbs.so`），
     * 而那两份**都是 6.6 族**的。拿它去打 6.1 或 6.12 的内核时，
     * **结构体偏移是错的** —— 而结构体偏移是**编译期烤死的，patch 改不了**。
     *
     * 后果比"符号对不上"更隐蔽：符号可以 patch 对，看起来一切正常，
     * 实际写的却是错位置的字段。
     *
     * 所以基线库按**目标内核的结构体族**选：
     * - 6.1  → `libbaseline_6_1.so`（自编，tokay target.h）
     * - 6.6  → 沿用各方案原有的库（`libionstack.so` / `libbs.so`）
     * - 6.12 → `libbaseline_6_12.so`（自编，GhostLock 6_12 + 荣耀 BTF）
     */
    object BaselineLibraries {
        const val SIX_ONE = "libbaseline_6_1.so"
        const val SIX_TWELVE = "libbaseline_6_12.so"

        /** 6.1 / 6.12 用自编基线；6.6 沿用方案自带的那份（`libionstack.so` / `libbs.so`）。 */
        fun forFamily(family: GhostLockKernelOffsets.StructFamily): String = when (family) {
            GhostLockKernelOffsets.StructFamily.F6_1 -> SIX_ONE
            GhostLockKernelOffsets.StructFamily.F6_12 -> SIX_TWELVE
            GhostLockKernelOffsets.StructFamily.F6_6 -> "" // 交给调用方用方案原有的库
        }

        /** 从内核串推出结构体族。**认不出返回 null，不猜**。 */
        fun familyOf(kernelRelease: String?): GhostLockKernelOffsets.StructFamily? {
            val v = kernelRelease?.substringBefore('-') ?: return null
            return when {
                v.startsWith("6.1.") -> GhostLockKernelOffsets.StructFamily.F6_1
                v.startsWith("6.6.") -> GhostLockKernelOffsets.StructFamily.F6_6
                v.startsWith("6.12.") -> GhostLockKernelOffsets.StructFamily.F6_12
                else -> null
            }
        }

        /**
         * 该用哪个库文件。
         *
         * @param schemeLibrary 方案原本的库（6.6 族时使用）。
         * @return 库文件名；族认不出时**回落到方案原有的库**并保持原行为。
         */
        fun resolve(schemeLibrary: String, kernelRelease: String?): String {
            val fam = familyOf(kernelRelease) ?: return schemeLibrary
            val byFamily = forFamily(fam)
            return byFamily.ifBlank { schemeLibrary }
        }
    }

    /**
     * ★ 6.1 族基线（自行编译）。
     *
     * ### 它解决什么
     *
     * 蓝厂与通用原本**只有 6.6 族**的基线 `.so`，而 6.1 族的 `task_struct` 布局不同
     * （`pi_lock 0x924` / `cred 0x838`，且 `compact_waiter=1`）——
     * 拿 6.6 的 `.so` 去打 6.1 的内核，**符号 patch 再多也救不回来**，
     * 所以 6.1 那 15 档一直构建不出来。
     *
     * ### 数据来源（可核查）
     *
     * - **符号（25 键齐全）**：`ctnBobong32/CVE-2026-43499-so-build` 的
     *   `src/targets/tokay-CP2A.260605.012/target.h`（`pi_lock=0x924`，6.1 族）
     * - **载荷源码与构建方式**：`boxiaolanya2008/CVE-2026-43499-Neo11Plus`
     *   的 `exploit/src/`（本项目主打方案的上游）
     * - 用容器内 NDK r30 编出 `libbaseline_6_1.so`
     *
     * ### 2026-09-25 重编（两个缺陷）
     *
     * 产物由 135184 B → **175792 B**，可重放配方见 `载荷构建/README.md`。
     *
     * 1. **不带 vr.ko 反 root 绕过**：蓝厂机型上会"提权成功后被子进程探针杀掉"。
     *    换成带 `patch_task_vr_tag()` 的 `root.c` 后，`strings | grep -c "vr detag"` = 2。
     * 2. **旧产物根本 load 不起来**（重编时才发现的）：旧构建没把 `root.c` /
     *    `io_daemon.c` 编进去，也没提供 `wallpaper_blob.S` 需要的
     *    `assets/wallpaper.webp`，于是 `install_android_root` / `io_daemon_main` /
     *    `embedded_wallpaper_start` 全是**指向 UND 的动态重定位** ——
     *    `dlopen` 解析不了 `GLOB_DAT` 会直接失败，压根走不到抹标记那一步。
     *    现在 `NEEDED` 只剩 `libdl.so` / `libc.so`。
     *
     * ### 可信度
     *
     * `beta = true`：编译产物合法（ELF/init_array 已验证，动态符号表已无 UND），
     * 但**未在真机上跑过** —— 包括 `vr.ko` 抹标记，只有"代码编进去了"这一级的证据。
     */
    val BASELINE_6_1: BaselineProfile = BaselineProfile(
        id = "baseline-6-1-tokay",
        variantLabel = "tokay-CP2A.260605.012",
        kernelVersion = "6.1",
        imageBase = 0xffffffc008000000uL.toLong(),
        abi = com.kernelpack.profile.AbiProfile(
            id = "abi-6-1-tokay",
            kernelSeries = "6.1",
            memoryLayout = emptyMap(),
            structOffsets = GhostLockKernelOffsets.structFields(
                GhostLockKernelOffsets.StructFamily.F6_1,
            ),
        ),
        // 符号集由 6.1 族的 target.h 提供（25 键齐全），此处留空 ——
        // 真正的值在构建时由 boot.img 解析或从该 target.h 导入，
        // 这里不写死任何数值，避免出现"看着有值、其实是别的内核的"。
        symbolOffsets = emptyMap(),
    )

    /**
     * ★ 6.12 族基线（自行编译）。
     *
     * ### 数据来源与可信度（逐项标注，不混着说）
     *
     * | 部分 | 来源 | 可信度 |
     * |---|---|---|
     * | **结构体偏移** | GhostLock `STRUCT_OFFSETS_6_12` **＋** 荣耀 6.12.38 的 BTF 实测 `offsets.json` | 两者**逐字段一致**（15 项 0 差异）→ 可信 |
     * | **符号** | 荣耀 6.12.38 实测 `offsets.json`（8 个） | `CROSS_REFERENCE` —— 那是**别的机型**，不是我方目标机 |
     * | 载荷源码与构建 | `boxiaolanya2008/CVE-2026-43499-Neo11Plus` | 本项目主打方案的上游 |
     *
     * ### 符号为什么可以是 CROSS_REFERENCE
     *
     * 基线 `.so` 里的符号值**在构建时会被 boot.img 解析出的新值逐项改写**；
     * 真正决定"这份 .so 能不能用于某内核"的是**结构体偏移**（编译期烤死、patch 改不了）。
     * 所以结构体可信、符号待改写，这个组合是成立的 ——
     * 且 [com.kernelpack.SymbolAlignment] 闸门保证：改写不齐就**阻断**，不会静默留旧值。
     */
    val BASELINE_6_12: BaselineProfile = BaselineProfile(
        id = "baseline-6-12-honor",
        variantLabel = "honor-ylp-w00-6.12.38",
        kernelVersion = "6.12",
        imageBase = 0xffffffc080000000uL.toLong(),
        abi = com.kernelpack.profile.AbiProfile(
            id = "abi-6-12-honor",
            kernelSeries = "6.12",
            memoryLayout = emptyMap(),
            structOffsets = GhostLockKernelOffsets.structFields(
                GhostLockKernelOffsets.StructFamily.F6_12,
            ),
        ),
        symbolOffsets = emptyMap(),
    )

    /**
     * ★ 上游（GhostLock）内核档：把 `src/kernels/<release>/offsets.h` 的**真实偏移**
     * 登记成通用方案的适配档。
     *
     * ### 数据是真的，可信度是 UPSTREAM
     *
     * 每档 9 个符号地址 + 归属的结构体族，逐条抓自上游源码（见 [GhostLockKernelOffsets]）。
     * 所以它们**不是空壳** —— 有可用的偏移表。
     * 但**不是我方实测**，因此：
     * - 偏移标注一律用 [SourceTier.UPSTREAM_TARGET_H]；
     * - 整档 `beta = true`；
     * - [kernelSeries] 用**小版本**（`6.6.118`）而不是大系列（`6.6`），
     *   否则 50 档会撞成同一个路由键，`profileIdFor` 只能返回一条。
     */
    private val upstreamEntries: List<BaselineEntry> =
        GhostLockKernelOffsets.KERNELS.map { k ->
            val minor = k.release.substringBefore('-')
            val profile = BaselineProfile(
                id = "up-" + k.release.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-'),
                variantLabel = k.release,
                kernelVersion = k.release,
                imageBase = 0xffffffc080000000uL.toLong(),
                abi = com.kernelpack.profile.AbiProfile(
                    id = "up-abi-" + k.release.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-'),
                    // ⚠️ 这里**必须**是两段式大系列（`6.6`），不能是 `k.release` 的小版本（`6.6.118`）。
                    //
                    // [2026-09-25 修 · 与"两张表"同源的第二种裂缝]
                    // `BuildGate.evaluate` 拿 `abi.kernelSeries` 去比 `seriesOf(kernelRelease)`，
                    // 而后者**只取两段**（`6.6.118-android15-…` → `6.6`）。
                    // 原来这里填的是三段的小版本，于是**每一条上游档都会被判成 ABI 冲突**：
                    //   「基线 ABI 档位是 GKI 6.6.118，而 boot.img 是 6.6」→ 构建被拒。
                    // 50 档登记得再全，也一档都构建不出来 —— 而且报的还是"ABI 冲突"，
                    // 让人往"库选错了"的方向查，与真正的原因（两套词汇）完全不搭边。
                    //
                    // 路由键是 [BaselineEntry.kernelSeries]（保留三段小版本，三级路由要用），
                    // ABI 比较键是这里（两段大系列）—— 两者**同名不同义**，所以两边都要写清楚。
                    kernelSeries = gkiSeriesOf(k.release),
                    memoryLayout = emptyMap(),
                    // 结构体偏移取它归属的那一族 —— 三族互不相同，这正是不能按版本外推的证据
                    structOffsets = GhostLockKernelOffsets.structFields(k.family),
                ),
                // ★ 关键：符号集 = **基线 .so 的 25 个键** 打底，上游那 9 个**覆盖**上去。
                //
                // 为什么不能只放上游那 9 个：打补丁只能改写**基线 .so 里本来就有的字面量**。
                // 若 symbolOffsets 只有 9 个键，闸门会算出"基线里没有的键有 16 个" → 永远拦住，
                // 于是这 50 档登记得再全也构建不出来。
                //
                // 正确语义是：
                //   - 25 个键的存在性来自基线 .so（决定**能改哪些**）；
                //   - 其中 9 个的**目标值**来自上游（对这个内核是对的）；
                //   - 其余 16 个待构建时从 boot.img 提取补齐，补不齐就被闸门拦住。
                //
                // `SymbolKeyMapping` 在这里第一次真正接进流程 —— 它负责把上游的
                // `off_init_task` 这套词汇换成我方的 `INIT_TASK` 这套。
                symbolOffsets = upstreamSymbolOverlay(k),
            )
            BaselineEntry(
                profile = profile,
                scheme = BaselineScheme.UNIVERSAL,
                device = "GKI",
                firmware = k.release,
                kernelSeries = minor,
                gkiBranch = Regex("-android(\\d+-\\d+)-").find(k.release)?.groupValues?.get(1),
                source = "YuKongA/ghostlock-app · src/kernels/${k.release}/offsets.h",
                offsets = upstreamOffsetSet(profile),
                beta = true,
                notes = listOf(
                    "上游清单档：偏移取自 GhostLock 源码，**我方未实测**。",
                    "结构体族：" + k.family.name + "（三族布局互不相同，不可按版本外推）。",
                ),
            )
        }

    /**
     * 把上游 9 个符号**覆盖**到基线 .so 的 25 个键上。
     *
     * 覆盖不到的上游键（`SymbolKeyMapping` 里判为"我方用不上"的）会被忽略 ——
     * 它们属于上游自己的 slide/rt_mutex 路线，我方这条 ashmem 路线不需要。
     */
    private fun upstreamSymbolOverlay(k: GhostLockKernelOffsets.KernelOffsets): Map<String, Long> {
        val base = BaselineProfiles.IONSTACK_P10.symbolOffsets
        // 上游键 → 我方键（一对多），再取其值
        val overlay = LinkedHashMap<String, Long>()
        for ((upstreamKey, value) in k.symbols) {
            val ours = com.kernelpack.offsets.SymbolKeyMapping.DIRECT[upstreamKey] ?: continue
            for (ourKey in ours) {
                // 只覆盖基线里真有的键：基线没有的字面量，改了也没处写
                if (base.containsKey(ourKey)) overlay[ourKey] = value
            }
        }
        return base + overlay
    }

    /** 上游档的偏移标注：逐条标 [SourceTier.UPSTREAM_TARGET_H]，与实测档区分开。 */
    private fun upstreamOffsetSet(profile: BaselineProfile): OffsetSet {
        val anchor = profile.kernelVersion
        val where = "YuKongA/ghostlock-app · src/kernels/$anchor/offsets.h"
        val notes = profile.symbolOffsets.keys.map { key ->
            OffsetNote(
                key = key,
                tier = SourceTier.UPSTREAM_TARGET_H,
                measuredOn = anchor,
                source = where,
                note = "上游源码登记值；我方未在真机上验证。",
                anchor = anchor,
            )
        }
        return OffsetSet(notes)
    }

    /**
     * ★ 蓝厂衍生档：把**通用方案的每一档内核适配**都挂上蓝厂独有的 vr.ko 反 root 绕过。
     *
     * ### 为什么可以这样派生
     *
     * 通用方案与本方案共用同一套**内核偏移** —— `rt_mutex_waiter` / `task_struct` /
     * `selinux_state` 这些都是内核层的东西，与厂商无关；
     * 厂商差异只体现在**绕过层**（vivo 多了 `vr.ko` 的 per-task 标记）。
     * 所以「通用偏移 + 蓝厂绕过」在架构上是成立的组合，而不是硬凑。
     *
     * ### 为什么全部标 beta
     *
     * 因为**没有一条在蓝厂真机上跑过**。偏移是从通用档继承的、绕过是从上游源码读来的，
     * 两者各自有依据，但**这个组合没有实测**。标 beta 是如实呈现验证状态。
     *
     * ### 派生规则
     *
     * 逐条复制通用方案条目，只改三处：方案归属、id 后缀（避免与通用档撞 id）、beta 标记。
     * **偏移一个字节都不动** —— 动了就不再是"通用方案的适配"了。
     */
    /** 全部条目：手写实测档 + 上游档（均属通用方案）。 */
    val entriesWithUpstream: List<BaselineEntry> = entries + upstreamEntries

    private val vivoDerivedEntries: List<BaselineEntry> =
        entriesWithUpstream.filter { it.scheme == BaselineScheme.UNIVERSAL }.map { universal ->
            universal.copy(
                profile = universal.profile.copy(id = universal.profile.id + "-vivo"),
                scheme = BaselineScheme.VIVO,
                beta = true,
                notes = universal.notes + listOf(
                    "蓝厂衍生档：内核偏移沿用通用方案，额外挂 vr.ko 反 root 绕过。",
                    "beta：该组合未在蓝厂真机上验证过，偏移继承自通用档。",
                ),
            )
        }

    /**
     * **匹配判定唯一依据** —— 手写条目 + 蓝厂衍生档。
     *
     * [MAINLINE_SERIES] 只用于生成文案与覆盖率报告，不参与匹配（见其文档）。
     */
    val allEntries: List<BaselineEntry> = entriesWithUpstream + vivoDerivedEntries

    /** 一个方案下已登记的（含派生）内核系列。 */
    fun seriesFor(scheme: BaselineScheme): List<String> =
        allEntries.filter { it.scheme == scheme }.map { it.kernelSeries }.distinct().sorted()

    /**
     * 该「方案 × 系列」**实际生效**的那一档是不是 beta（未实测）。
     *
     * 注意它跟随 [entryFor] 的取舍 —— 已有实测档时返回 false，
     * 而不是"存在任一 beta 档就返回 true"。否则界面会对一台有实测档的机器显示 beta 提示。
     */
    fun isBeta(scheme: BaselineScheme, kernelSeries: String): Boolean =
        entryFor(scheme, kernelSeries)?.beta == true

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
        allEntries.filter { it.scheme == scheme }.ifEmpty { allEntries }

    // 必须查 allEntries：蓝厂衍生档的 id 带 `-vivo` 后缀、只存在于派生表里，
    // 查旧表会出现"profileIdFor 返回了 id，byId 却取不到条目"的裂缝。
    fun byId(id: String): BaselineEntry? = allEntries.firstOrNull { it.profile.id == id }

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
        entryFor(scheme, kernelSeries)?.profile?.id

    /**
     * **优先按完整内核串路由，再退回大系列。**
     *
     * ### 为什么必须有这个重载
     *
     * 上游那 50 档是按**小版本**登记的（`6.6.118`），而 `KernelSchemeSelector`
     * 给出的是**大系列**（`6.6`）。只按大系列查的话，这 50 档**一次都不会被选中** ——
     * 登记得再全也是死数据。
     *
     * 所以路由顺序是：
     * 1. **完整内核串精确命中**（`6.6.118-android15-8-g2e6b9c3812c5-ab15114928-4k`）；
     * 2. 退回**小版本**（`6.6.118`）；
     * 3. 再退回**大系列**（`6.6`）—— 手写实测档走的是这一层。
     *
     * 三级都要求**精确相等**，不做前缀推断：认不出来就是没有，
     * 绝不能拿邻近版本顶替（那是本工程最防的错）。
     */
    fun profileIdFor(
        scheme: BaselineScheme,
        kernelSeries: String,
        kernelRelease: String?,
    ): String? {
        if (!kernelRelease.isNullOrBlank()) {
            // 1) 完整串
            allEntries.firstOrNull { it.scheme == scheme && it.firmware == kernelRelease }
                ?.let { return it.profile.id }
            // 2) 小版本
            val minor = kernelRelease.substringBefore('-')
            entryFor(scheme, minor)?.let { return it.profile.id }
        }
        // 3) 大系列
        return profileIdFor(scheme, kernelSeries)
    }

    /** 该组合是不是 beta（含三级路由）。 */
    fun isBetaFor(
        scheme: BaselineScheme,
        kernelSeries: String,
        kernelRelease: String?,
    ): Boolean {
        if (!kernelRelease.isNullOrBlank()) {
            allEntries.firstOrNull { it.scheme == scheme && it.firmware == kernelRelease }
                ?.let { return it.beta }
            entryFor(scheme, kernelRelease.substringBefore('-'))?.let { return it.beta }
        }
        return isBeta(scheme, kernelSeries)
    }

    /**
     * 某个「方案 × 内核系列」**实际生效**的那一条。
     *
     * ### 为什么需要它，而不是直接 firstOrNull
     *
     * 引入蓝厂衍生档之后，同一个 (方案, 系列) 会有**多条**：
     * 例如蓝厂 6.6 既有手写实测的 `PD2520`，又有从通用方案派生的 beta 档。
     * `firstOrNull` 的语义会变成"看列表顺序"，而列表顺序是偶然的 ——
     * 那就会出现"实测档明明在，却选中了 beta 档"这种说不通的结果。
     *
     * 所以这里的规则是显式的：**已实测优先；beta 档只用来补空缺**。
     * 排序稳定（同优先级按 id），不依赖声明顺序。
     */
    fun entryFor(scheme: BaselineScheme, kernelSeries: String): BaselineEntry? =
        allEntries
            .filter { it.scheme == scheme && it.kernelSeries == kernelSeries }
            .sortedWith(compareBy({ if (it.beta) 1 else 0 }, { it.profile.id }))
            .firstOrNull()

    /**
     * 按内容识别基线：先 sha256 精确匹配，再退回 `BUILD_VARIANT_LABEL` 字符串匹配。
     *
     * 双轨的必要性：同一份 .so 可能服务多个固件（上游就是这么用的），
     * 只认 sha256 会把这种情况判成"未知载荷"。
     */
    fun findByBytes(baseLibrary: ByteArray): BaselineEntry? {
        val digest = BaselineProfiles.sha256Hex(baseLibrary)
        allEntries.firstOrNull { it.profile.sha256?.equals(digest, ignoreCase = true) == true }?.let { return it }
        val text = String(baseLibrary, Charsets.ISO_8859_1)
        return allEntries.firstOrNull { e ->
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

        // [2026-09-25 修] 原来这里查的是 `entries` —— 只有那 2 条手写档，
        // 于是上游 50 档 + 蓝厂衍生 51 档在"基线建议/报缺"这条路上**等于不存在**：
        // 用户明明有 6.1.145 的登记档，界面却说「没有 6.1 的通用方案基线」。
        // 现在查 allEntries，并且**按两段式大系列比较** —— 因为上游档的
        // kernelSeries 是三段小版本（`6.1.145`），直接 `== "6.1"` 一条都对不上。
        val sameSeries = allEntries.filter {
            it.scheme == scheme && (seriesOf(it.kernelSeries) ?: it.kernelSeries) == series
        }

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
        // 同一处修正：报缺时列出的是**注册表实际覆盖面**，不是那 2 条手写档。
        // 按大系列归并，否则 101 条会糊满整个日志。
        val sameScheme = allEntries
            .filter { it.scheme == scheme }
            .map { seriesOf(it.kernelSeries) ?: it.kernelSeries }
            .distinct()
            .sorted()
        if (sameScheme.isNotEmpty()) {
            howTo.add("现有 ${scheme.label} 基线覆盖的大系列：" +
                sameScheme.joinToString("、") +
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

    /**
     * 注册表里**实际有哪些档** —— 按 (方案 × 大系列) 汇总。
     *
     * 原来这里是 `entries.map { it.quad() }`，只列那 2 条手写档；
     * 而上游 50 档 + 蓝厂 50 档衍生共 101 条**一条都不显示**，
     * 于是"报缺"文案会在明明有档位时说"没有基线"。
     * 现在汇总成几行，既不刷屏也不漏报。
     */
    private fun availableLabels(): List<String> =
        allEntries
            .groupBy { it.scheme.label to (seriesOf(it.kernelSeries) ?: it.kernelSeries) }
            .entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (k, v) ->
                "${k.first} × ${k.second}：${v.size} 档" +
                    "（实测 ${v.count { !it.beta }} / beta ${v.count { it.beta }}）"
            }

    // ------------------------------------------------------------------ 解析

    fun seriesOf(release: String): String? =
        Regex("^(\\d+)\\.(\\d+)").find(release.trim())
            ?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }

    /**
     * 把任意形态的内核串压成**两段式大系列**（`6.6.118-android15-…` → `6.6`）。
     *
     * 与 [seriesOf] 的区别只有容错：解析不出来时**退回 `substringBefore('-')`**，
     * 而不是返回 null。用在注册表初始化里 —— 那里需要的是"一定有个串可用"，
     * 而"认不出"这件事由闸门去说，不该让注册表构造不出来。
     *
     * ⚠️ 之所以要这个函数：`abi.kernelSeries` 的语义是**闸门比较键**（两段），
     * 而 `BaselineEntry.kernelSeries` 的语义是**路由键**（三段小版本）。
     * 两者同名不同义 —— 混用就会让每一条上游档都被判成 ABI 冲突。
     */
    fun gkiSeriesOf(release: String): String =
        seriesOf(release) ?: release.trim().substringBefore('-')

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
        // [2026-09-25 修] 原来这里查的是 `entries`（只有 2 条手写档），
        // 于是报告会说「6.12 · 通用方案：❌ 未登记」—— 而上游 6.12.23/30/38 共 12 档
        // 明明登记在 [upstreamEntries] 里。**报告说没登记，路由却能选到** ——
        // 又是"两张表"：一处修了，另一处还在骗人。
        // 现在统一查 allEntries，并把**大系列**作为归并口径（上游档的 kernelSeries 是三段小版本）。
        for (scheme in listOf(BaselineScheme.UNIVERSAL, BaselineScheme.VIVO)) {
            for (series in MAINLINE_SERIES) {
                val hits = allEntries.filter {
                    it.scheme == scheme && gkiSeriesOf(it.kernelSeries) == series
                }
                val mark = if (hits.isEmpty()) {
                    "❌ 未登记（会明确报缺）"
                } else {
                    val measured = hits.count { !it.beta }
                    "✅ 已登记 ${hits.size} 档（实测 $measured / beta ${hits.size - measured}）"
                }
                add("$series · ${scheme.label}：$mark")
            }
        }
        val testRegistered = allEntries.filter { gkiSeriesOf(it.kernelSeries) in TEST_SERIES }
        add(
            "测试线 ${TEST_SERIES.joinToString(" / ")}：已登记 ${testRegistered.size} 档" +
                "（仅 beta，设置页已标「测试」）"
        )
        addAll(upstreamCatalogReport())
    }

    /**
     * ★ 上游内核清单 ↔ 我方登记档位**对账**。
     *
     * 这是 [GhostLockKernelCatalog] 的**唯一生产消费者** —— 在此之前它是个孤儿：
     * 50 条内核串躺在那里，没有任何代码读它，"文件存在"被当成了"已接通"。
     *
     * 它有真事可做：上游清单是**从上游源码树枚举**出来的，
     * 而我方 50 档偏移是**逐条抄录**进 [GhostLockKernelOffsets] 的。
     * 两条来源不同的路必须对上 —— 对不上就说明抄漏了或抄多了，
     * 而那种错**不会自己暴露**：少一档只是"某台机器选不中"，
     * 多一档则是"登记了一个上游根本不支持的内核"，两者都很隐蔽。
     */
    private fun upstreamCatalogReport(): List<String> = buildList {
        val declared = GhostLockKernelCatalog.VERSIONS
        val registered = upstreamEntries.map { it.firmware }.toSet()
        val missing = declared.filter { it !in registered }
        val extra = registered.filter { it !in declared }
        add(
            "上游清单（${GhostLockKernelCatalog.SOURCE_REPO} · ${GhostLockKernelCatalog.SOURCE_PATH}）：" +
                "声明 ${declared.size} 档，我方已登记 ${declared.size - missing.size} 档"
        )
        if (missing.isNotEmpty()) {
            add("    ⚠️ 上游有、我方未登记 ${missing.size} 档：$missing")
        }
        if (extra.isNotEmpty()) {
            add("    ⚠️ 我方登记了、上游清单里却没有 ${extra.size} 档：$extra")
        }
        add("    上游覆盖的小版本：${GhostLockKernelCatalog.MINOR_VERSIONS.joinToString("、")}")
    }

    /** 可行性能实测出来的基线占比 —— 缺基线时最该看的一个数。 */
    fun feasibilityCoverage(): String {
        // 同处修正：覆盖率的**分母**必须是注册表全部档位，而不是那 2 条手写档 ——
        // 拿 2 当分母会算出"0/2 已实测"，看起来像什么都没做，其实是 101 档里的 2 档。
        val withLayout = allEntries.count { it.feasibility?.layout?.known == true }
        val measured = allEntries.count { it.feasibility?.measuredWord != null }
        return "布局已知 $withLayout/${allEntries.size}，落点已实测 $measured/${allEntries.size}"
    }

    /**
     * 一句话概览（方案 × 大系列 各有多少档）。
     *
     * 原来它是 `entries.joinToString` —— 101 档里只列 2 档，
     * 而且真要列全了就是 101 段文字糊满一行。现在按 (方案, 大系列) 归并。
     */
    fun summary(): String =
        allEntries
            .groupBy { "${it.scheme.label} × ${gkiSeriesOf(it.kernelSeries)}" }
            .entries
            .sortedBy { it.key }
            .joinToString("；") { (k, v) -> "$k ${v.size} 档" }
}
