package com.kernelpack.policy

import com.kernelpack.offset.StackLayoutVerdict
import com.kernelpack.profile.BaselineLookup
import com.kernelpack.profile.BaselineRegistry
import com.kernelpack.profile.BaselineScheme

/**
 * 载荷构建的**硬闸门**：基线 ABI / 强制指定 与实际内核不符时拒绝打包。
 *
 * 为什么必须硬拦截（实测案例）
 * ----------------------------
 * 用户拿一台 5.10.246 的 boot.img、选了 GKI 6.6 的基线档位（pd2520-…），
 * 界面给出的是**提示**而不是拒绝，于是照常打包：
 * ```
 *   需要改写: 25 项 · 校验通过: 12 项
 * ```
 * 13 项偏移没能校验通过 —— 那些偏移是给 6.6 的 `.so` 改的，而目标内核是 5.10。
 * 这不是"可能有问题"，这是**明知不匹配还出包**。等价于让用户拿内核内存去赌。
 *
 * 因此本闸门的原则与宿主侧 `gl5x/policy.py` + `build_payload.py` 的 G2/G3 一致：
 * **默认拒绝**；只有显式打开"忽略冲突"才放行，且放行必须留痕。
 *
 * [风险] 本类只做**判定**，不做任何 IO。留痕由调用方负责（见 PayloadBuilderViewModel）。
 */
enum class LayoutFamily(val label: String, val sizeBytes: Int, val words: Int) {
    FLAT_9W("flat-9w", 72, 9),
    FLAT_10W("flat-10w", 80, 10),
    FLAT_11W("flat-11w", 88, 11),
    NESTED_6_6("nested-6.6", 112, 14),
    UNKNOWN("unknown", 0, 0),
}

/**
 * 内核支持分级（2026-09-12 定的产品口径）。
 *
 * ```
 *   主线  6.6 / 6.12           —— 「通用方案」与「vivo / iQOO 方案」都要覆盖
 *   测试  5.x（含 5.10 / 5.15）—— 仅作 beta 测试，UI 必须明确标注「测试」
 * ```
 *
 * 为什么 5.x 降到测试级：本工程对 5.x 的布局锚点**只有上游 target.h 一条腿**
 * （`yakidango-official/GhostLock-H80GT` 的 5.10.236），既没有 BTF 也没有本机反汇编；
 * 而 6.1 / 6.6 都能从真实镜像把 BTF 量出来。证据强度不在一个档次，
 * 就不该在 UI 上平起平坐 —— 这是"假警报比不报更糟"的同一条原则。
 */
enum class KernelTier(val label: String, val mainline: Boolean) {
    /** 主线：有实测锚点，正常交付。 */
    MAINLINE("主线", true),

    /** 测试：仅 beta 验证，UI 必须带「测试」标记。 */
    TEST("测试", false),
}

/** 用户在设置页选择的"内核系列"。 */
enum class SeriesOverride(
    val label: String,
    /** 设置页那一行右侧显示的短标签 —— 列表行放不下长说明。 */
    val shortLabel: String,
    val wireValue: String,
    val tier: KernelTier,
) {
    AUTO("自动（按 boot.img 实测）", "自动", "auto", KernelTier.MAINLINE),
    FORCE_5("强制 5.x（测试）", "5.x（测试）", "5", KernelTier.TEST),
    FORCE_6("强制 6.x（主线）", "6.x（主线）", "6", KernelTier.MAINLINE);

    companion object {
        fun fromWire(value: String?): SeriesOverride =
            entries.firstOrNull { it.wireValue == value } ?: AUTO
    }
}

/**
 * 一个内核 release 属于主线还是测试。
 *
 * 判据只看**主版本**：6.x 主线、其余（含 5.x）测试。
 * **不**按具体次版本细分 —— 那是 [BuildGate.familyForRelease] 的职责（布局归族）。
 * 两件事混在一起，会让"6.12 是主线、但布局归族是 nested-6.6"这种完全正常的情况
 * 看起来像自相矛盾。
 */
fun tierOfRelease(release: String): KernelTier {
    // 口径单一来源：以 BaselineRegistry.MAINLINE_SERIES 为准。
    // [2026-09-12] 主线 = 6.6 / 6.12；6.x 里的其它次版本（如 6.1）**不算主线**。
    val series = BuildGate.seriesOf(release) ?: return KernelTier.TEST
    return if (series in BaselineRegistry.MAINLINE_SERIES) KernelTier.MAINLINE else KernelTier.TEST
}

/** 闸门判定结果。 */
sealed class GateDecision {
    abstract val ok: Boolean

    /** 直接放行。 */
    object Proceed : GateDecision() {
        override val ok = true
    }

    /** 放行但必须给出明显警告（逃生门被使用）。 */
    data class ProceedWithWarning(val notes: List<String>) : GateDecision() {
        override val ok = true
    }

    /** 拒绝构建。UI 必须**阻断**（弹窗），不能只是把文字标红。 */
    data class Blocked(
        val title: String,
        val detail: List<String>,
        val remedy: String,
    ) : GateDecision() {
        override val ok = false
    }
}

object BuildGate {

    /**
     * 内核 release → 布局族别（**版本外推**）。
     *
     * [诚实声明] Kotlin 侧目前**无法实测布局**：本工程没有 BTF 解析器，也没有反汇编推导，
     * 所以这里只能按内核版本外推。锚点全部来自**真实镜像/真实开源 target.h**，
     * 但「外推」与「实测」在 UI 上必须区分开 —— 把外推值说得像实测值，
     * 就是在重演「盲信参考表」那类错误。
     *
     * 按 2026-09-12 的产品口径，本表覆盖**三条主线 + 一条测试线**：
     * ```
     *   主线 6.12    nested-6.6 112 字节
     *                            ← JoinChang/ghostlock-oneplus 的 6.12.23/6.12.38 设备
     *                              （OnePlus 15 / Ace 6T / Find X9 Pro）走 nested 分支，
     *                              与其 STRUCT_OFFSETS_6_12 同族
     *   主线 6.6     nested-6.6 112 字节（task@0x50 lock@0x58 wake_state@0x60 ww_ctx@0x68）
     *                            ← **本机实测** /sys/kernel/btf/vmlinux（6.6.89，140652 类型）
     *   主线 6.1     flat-11w   88 字节（task@0x30 lock@0x38 wake_state@0x40 prio@0x44
     *                              deadline@0x48 ww_ctx@0x50）
     *                            ← **本轮实测**：用户 boot.img（6.1.145）解出裸内核，
     *                              扫 BTF 读出（133758 类型）—— 见 measure_waiter.py
     *   主线 6.5 及以下 flat-11w 88 字节
     *                            ← 233laoliu/mt6985 的 VERIFICATION.md（5.15.178，
     *                              ABI XML + 真机反汇编双证）
     *   测试 5.15+   flat-11w   88 字节（同上，但只有上游一条腿）
     *   测试 5.10-   flat-10w   80 字节（task@0x30 lock@0x38 prio@0x40 deadline@0x48，
     *                              **无** wake_state / ww_ctx）
     *                            ← yakidango-official/GhostLock-H80GT 的 5.10.236 target.h
     * ```
     *
     * [勘误] 本函数早先写成「5.10 = flat-9w / 72 字节」，那是照 mainline 结构体定义推的，
     * **与真机不符**：5.10.236 的 waiter 有 deadline 字段，实际 80 字节。
     * 教训与"104 vs 112"完全一样 —— 结构体布局只能以目标内核为准。
     * 第二次勘误：6.1 一度被上游 `JoinChang` 记为 compact(80 字节)，
     * 本轮 BTF 实测是 88 字节且带 `wake_state`。**同一个 task/lock 字下标（6/7）**
     * 让两者在 pselect 铺字段这件事上等价，但整体尺寸不等价，不能混。
     * 真要精确判定必须读目标镜像的 BTF 或反汇编；本函数只用于"明显不该打包"的拦截，
     * 因此宁可归到 UNKNOWN 也不要瞎猜。
     */
    fun familyForRelease(release: String): LayoutFamily {
        val (major, minor) = versionOf(release) ?: return LayoutFamily.UNKNOWN
        return when {
            // ── 主线 6.x ───────────────────────────────────────────
            major == 6 && minor >= 6 -> LayoutFamily.NESTED_6_6   // 6.6 / 6.12 / 6.18…
            major == 6 -> LayoutFamily.FLAT_11W                   // 6.0 ~ 6.5
            // ── 测试线 5.x ─────────────────────────────────────────
            // 5.15 起与 6.1 同族；5.10 及以下少 wake_state/ww_ctx，是 80 字节
            major == 5 && minor >= 15 -> LayoutFamily.FLAT_11W
            major == 5 -> LayoutFamily.FLAT_10W
            else -> LayoutFamily.UNKNOWN
        }
    }

    /** `6.1.145-android14-11-…` → `6.1`；解析不出来返回 null。 */
    fun seriesOf(release: String): String? {
        val (major, minor) = versionOf(release) ?: return null
        return "$major.$minor"
    }

    private fun versionOf(release: String): Pair<Int, Int>? {
        val m = Regex("^(\\d+)\\.(\\d+)").find(release.trim()) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /**
     * 执行判定。
     *
     * [P1 → 任务1] 本闸门现在**同时接入 `BaselineRegistry`**，因此它不只知道"内核系列对不对"，
     * 还知道"这台机器的四元组在注册表里有没有对应基线"。两级信息分工如下：
     * ```
     *   Registry 二级判定 → 进 notes（建议 / 覆盖面提示）  ← 本函数新增，**不阻断**
     *   Gate     一级判定 → 进 conflicts → Blocked/Proceed  ← 原有语义，保持不变
     * ```
     * 为什么注册表这一级**不做成阻断**：注册表覆盖面本来就窄（当前只有 6.6 两条），
     * 把"没有登记"当硬错误会让 5.x 支持彻底没法用；而"基线 ABI 与实测内核不符"
     * 是**确定性**的错误，那才必须拦住。两者混为一谈就是把噪声当警报 ——
     * 假警报比不报更糟（本工程的既有原则）。
     *
     * @param kernelRelease    boot.img 实测内核 release
     * @param baselineAbiSeries 基线档位声明的 GKI 系列（如 "6.6"）；没有基线时传 null
     * @param override         设置页的"强制指定内核系列"
     * @param allowMismatch    用户是否显式打开"忽略冲突"（等价 --i-know-what-i-am-doing）
     * @param scheme           当前方案（通用 / vivo）；未知时传 [BaselineScheme.UNKNOWN]，
     *                         此时跳过注册表建议，避免对着未知方案报假冲突
     * @param profileId        用户实际选中的基线档位 id；给了才能核对"选的这条登记了吗"
     */
    fun evaluate(
        kernelRelease: String,
        baselineAbiSeries: String?,
        override: SeriesOverride,
        allowMismatch: Boolean,
        scheme: BaselineScheme = BaselineScheme.UNKNOWN,
        profileId: String? = null,
        /**
         * 「5.x 内核支持（beta）」开关。**默认 false** —— 关着时识别到 5.x 直接拒绝构建。
         *
         * 默认值取 false 是刻意的：新增调用方忘了传这个参数时，得到的是**安全**行为
         * （拒绝未验证的 5.x），而不是悄悄放行。默认值就该往安全那侧倒。
         */
        allowTestKernel: Boolean = false,
    ): GateDecision {
        val series = seriesOf(kernelRelease)
            ?: return GateDecision.Blocked(
                title = "无法识别内核版本",
                detail = listOf("boot.img 的 release 串是「$kernelRelease」，解析不出主.次版本号。"),
                remedy = "请确认这个 boot.img 确实来自目标设备。",
            )
        val major = series.substringBefore('.').toIntOrNull() ?: 0
        if (major !in 5..6) {
            return GateDecision.Blocked(
                title = "不支持的内核主版本 $major",
                detail = listOf(
                    "实测内核：$kernelRelease",
                    "本工具只处理 6.x（主线）与 5.x（测试）：4.x / 7.x 的 rt_mutex 与 task_struct 布局",
                    "与这两条线都不同，不存在“回退到默认值”这种选项。",
                ),
                remedy = "该设备暂不支持；请勿强行构建。",
            )
        }

        // 主线判定（口径单一来源：BaselineRegistry.MAINLINE_SERIES = 6.6 / 6.12）
        val mainline = series in BaselineRegistry.MAINLINE_SERIES

        val conflicts = ArrayList<String>()
        val notes = ArrayList<String>()

        // ── 「5.x 内核支持（beta）」闸（2026-09-12 口径）──────────────────
        // 开关**关着**时：识别到 5.x 就**不采用**五系方案，直接拦住并引导去开开关。
        //
        // 为什么是"拦住"而不是"悄悄按 6.x 方案继续"：
        //   ① 悄悄继续 = 拿 6.x 的布局规则去改 5.x 的内核内存，那是**明知不匹配还出包**，
        //      正是 P1 闸门当初要消灭的那种行为；
        //   ② 静默降级还会让用户以为"5.x 已经支持了"，而事实是我们根本没量过 5.x。
        // 所以这里选**最响**的做法：明确拒绝 + 告诉用户去哪儿开。
        if (override != SeriesOverride.AUTO) {
            val forced = override.wireValue.toIntOrNull() ?: 0
            if (forced != major) {
                conflicts.add("强制指定 ${forced}.x，但 boot.img 实测是 $series（$kernelRelease）")
            }
        }

        // ── 支持分级提示：开关已开、确实要走测试线时，出包前再提醒一次 ──
        if (!mainline && major == 5) {
            notes.add(
                "[测试内核] $series 不在主线支持范围内（主线为 6.6 / 6.12）。" +
                    "你已手动开启「5.x 内核支持（beta）」。5.x 的布局锚点只有上游 target.h 一条腿，"
            )
            notes.add(
                "    本工程没有该内核的 BTF 或反汇编实测 —— 偏移是否与这台机器一致**未经本工程验证**，" +
                    "请只用于 beta 验证，不要在主力机上当作可用产出。"
            )
        }

        if (baselineAbiSeries != null && baselineAbiSeries != series) {
            val baselineFamily = familyForRelease(baselineAbiSeries + ".0")
            val kernelFamily = familyForRelease(kernelRelease)
            conflicts.add(
                "基线 ABI 档位是 GKI $baselineAbiSeries（布局 ${baselineFamily.label}，" +
                    "${baselineFamily.sizeBytes} 字节），而 boot.img 是 $series（布局 ${kernelFamily.label}，" +
                    "${kernelFamily.sizeBytes} 字节）"
            )
            conflicts.add(
                "（布局族别按内核版本外推：5.10=flat-10w/80 字节、5.15~6.5=flat-11w/88 字节、" +
                    "6.6+=nested-6.6/112 字节。锚点来自真机实测与开源 target.h，" +
                    "但当前镜像的实际布局要看它的 BTF 或反汇编才能确认）"
            )
            if (baselineFamily != kernelFamily) {
                conflicts.add(
                    "两边的 rt_mutex_waiter 布局不同 → 基础 .so 里的偏移常量与目标内核不匹配，" +
                        "改写后的载荷会打到错误的结构体字段上"
                )
            }
        }

        // ===== 任务1：接入 BaselineRegistry（四元组感知）=====
        // [修] 原来这里直接 `if (conflicts.isEmpty()) return Proceed`，会把上面收集到的
        // notes **整批丢掉** —— 于是"闸门给出建议"这条路根本走不通（建议永远显示不出来）。
        // 现在 notes 非空时走 ProceedWithWarning 把它带出去。
        consultRegistry(
            kernelRelease = kernelRelease,
            series = series,
            scheme = scheme,
            profileId = profileId,
            notes = notes,
        )

        // ── ① 冲突优先，且**可以被"忽略冲突"放行**（这是它的设计用途）──
        if (conflicts.isNotEmpty()) {
            return if (allowMismatch) {
                notes.add("[风险] 已按“忽略冲突”放行，本次构建的偏移很可能与设备不匹配")
                notes.addAll(conflicts)
                GateDecision.ProceedWithWarning(notes)
            } else {
                GateDecision.Blocked(
                    title = "基线 ABI 冲突，已拒绝构建",
                    detail = conflicts + listOf("这不是“可能有问题”，而是明知不匹配还出包 —— 改为直接拒绝。"),
                    remedy = "请换用与目标内核匹配的基线 / 基础 .so；确需强行出包，请在设置里打开“忽略冲突”（会记入日志）。",
                )
            }
        }

        // ── ② 非主线内核的硬拦 ────────────────────────────────────────
        // 放在冲突之后，是因为这两条**不可**被"忽略冲突"放行 —— 它们不是"数值可能不合"，
        // 而是"这条线根本没支持/没数据"。两者性质不同，不能共用一个逃生门。
        //
        // [勘误] 这里原来只判 `tierOfRelease(..) == TEST && !allowTestKernel`，而 tier 把
        // "所有非主线"都算 TEST —— 于是 6.1 会撞进**为 5.x 写的那段文案**，弹出
        // 「识别到 5.x 内核…」这种与事实不符的提示。测试把它抓了出来。现在按主版本分开说。
        if (!mainline && major == 5 && !allowTestKernel) {
            return GateDecision.Blocked(
                title = "识别到 5.x 内核，但「5.x 内核支持（beta）」未开启",
                detail = listOf(
                    "实测内核：$kernelRelease（$series，属 5.x 测试线）",
                    "当前设置里「5.x 内核支持（beta）」是**关闭**的，因此不会采用五系内核方案。",
                    "5.x 的布局锚点目前只有上游 target.h 一条腿 —— 本工程没有该内核的",
                    "BTF / 反汇编实测，偏移是否与这台机器一致**未经本工程验证**，",
                    "所以默认不参与构建。",
                ),
                remedy = "确需为 5.x 构建：到「设置」里打开「5.x 内核支持（beta）」（在「关于」上面），再回来构建。",
            )
        }
        if (!mainline && major == 6) {
            return GateDecision.Blocked(
                title = "不支持的 6.x 内核版本 $series",
                detail = listOf(
                    "实测内核：$kernelRelease",
                    "本工程只支持 6.x 里的 ${BaselineRegistry.MAINLINE_SERIES.joinToString(" / ")}（主线）。",
                    "$series 的 rt_mutex_waiter 布局族与主线不同，且没有为它登记过偏移产物 ——",
                    "硬按主线偏移构建会打到错误的结构体字段上。",
                ),
                remedy = "该 6.x 次版本暂不支持；请勿强行构建。",
            )
        }

        return if (notes.isEmpty()) GateDecision.Proceed
        else GateDecision.ProceedWithWarning(notes)
    }

    /**
     * 任务1：把注册表的四元组结论翻译成给用户看的 notes。**只提示，不阻断。**
     *
     * 四件事，按优先级：
     *   1. 用户选的档位**没有登记**在注册表里 → 明确说"未经登记"，并给出登记了什么；
     *   2. 注册表里有**更贴合的档位**（同四元组） → 建议换；
     *   3. 注册表里**完全没有**这个组合 → 如实上报"缺基线"，并附获取途径；
     *   4. 基线偏移组不是 VERIFIED（占位符/交叉参考） → 点名拖累项，这是最该拦的一类。
     *
     * 第 4 条特别重要：`Meowkis/ghostlock-samsung-research` 的 `5.15.h` 标着 5.15 却是占位符，
     * 若被当基线收录，用户会拿一组假数字去改内核内存。
     */
    private fun consultRegistry(
        kernelRelease: String,
        series: String,
        scheme: BaselineScheme,
        profileId: String?,
        notes: MutableList<String>,
    ) {
        // 方案未知时不查 —— 查了也只会得到"别的方案"的结论，那是假警报。
        if (scheme == BaselineScheme.UNKNOWN) return

        val branch = BaselineRegistry.gkiBranchOf(kernelRelease)
        val existing = profileId?.let { BaselineRegistry.byId(it) }

        when (val r = BaselineRegistry.lookup(kernelRelease, scheme, branch)) {
            is BaselineLookup.Found -> {
                val hit = r.entry
                if (existing == null) {
                    notes.add("[基线建议] 注册表里有匹配的档位：${hit.quad()}（${hit.source}）")
                } else if (existing.profile.id != hit.profile.id) {
                    notes.add(
                        "[基线建议] 当前选的是 ${existing.profile.id}，但按四元组" +
                            "（机型/固件/内核系列/GKI分支）更贴合的是 ${hit.profile.id}" +
                            "（${hit.quad()}）—— 同内核系列下不同固件的偏移仍可能不同，建议核对。"
                    )
                }
                if (r.degraded) {
                    notes.add(
                        "[基线提示] 这次是**降级匹配**（没有完全对上四元组），" +
                            "构建前请再核对机型代号与固件版本。"
                    )
                }
                // 偏移可信度：这是唯一会让人觉得"该拦"的一条，但仍在闸门语义之外单独表达
                if (!hit.buildable) {
                    notes.add(
                        "[基线风险] 该基线的偏移组状态是「${hit.offsets.status.label}」，" +
                            "拖累项：" + hit.offsets.weakest().joinToString("、") { "${it.key}（${it.tier.label}）" } +
                            " —— 这一档**不应**用于构建。"
                    )
                }
                hit.feasibility?.let { f ->
                    notes.add("[pselect 可行性] ${f.summary()}")
                    if (!f.verdict.feasible &&
                        f.verdict != StackLayoutVerdict.UNKNOWN
                    ) {
                        notes.add(
                            "[风险] ${f.verdict.label}：该机型上 pselect 栈覆盖这条路" +
                                "（按已核实的实测数据）走不通。"
                        )
                    }
                }
            }

            is BaselineLookup.Missing -> {
                notes.add("[基线缺失] ${r.notes.joinToString(" ")}")
                r.howTo.forEach { notes.add("    $it") }
                if (existing != null) {
                    notes.add(
                        "[基线缺失] 当前选的 ${existing.profile.id}（GKI ${existing.profile.abi.kernelSeries}）" +
                            "没有登记在 $series 这一档下 —— 它的偏移不是为这个内核编的。"
                    )
                }
            }
        }
    }
}
