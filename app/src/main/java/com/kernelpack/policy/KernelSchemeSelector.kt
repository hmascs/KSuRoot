package com.kernelpack.policy

import com.kernelpack.profile.BaselineRegistry

/**
 * **构建前的内核方案选择** —— 先看内核版本，再决定用哪套方案，最后才动手打包。
 *
 * 为什么要在闸门之外**再加**这一层
 * --------------------------------
 * [BuildGate] 是在 `KernelPack.pack()` **内部**跑的：它已经读完 boot.img、
 * 解析出内核、算完基线对比，才有机会说"不行"。也就是说，被拦下时前面那些工作
 * 已经白做了 —— 用户也经历了一段"看起来在构建"的过程才等到拒绝。
 *
 * 本类的定位是**前置**判定：调用方在真正开跑之前（甚至只拿到 release 串时）
 * 就能问一句"这个内核该用哪套方案？能不能建？"，把拒绝提前到**零成本**的位置。
 *
 * 三层判定（顺序即优先级）
 * ```
 *   ① 版本解析不出来        → Blocked（认不出内核就不该动内核内存）
 *   ② 6.x（主线）           → Selected(主方案)
 *   ③ 5.x 且开关关着        → Blocked（引导去设置里开「5.x 内核支持（beta）」）
 *      5.x 且开关开着        → Selected(测试方案) + 强制风险提示
 * ```
 *
 * [与 BuildGate 的关系] 两层**都必须保留**，不是重复：
 * `BuildGate` 是最后一道硬闸（无论谁调用 `pack()` 都拦得住，包括将来别的入口）；
 * 本类是体验层的提前量。**宁可拦两次，也不要有一层因为调用路径不同而被绕过。**
 */
object KernelSchemeSelector {

    /** 判定结果。 */
    sealed class Decision {
        abstract val ok: Boolean

        /**
         * 可以采用方案了。
         *
         * @param series     解析出的内核系列，如 `6.6`。
         * @param useTestScheme 是否走**测试**（5.x）方案。
         * @param notes      必须显示给用户的提示（测试线风险等）。空表示没有特别要说的。
         */
        data class Selected(
            val series: String,
            val major: Int,
            val useTestScheme: Boolean,
            val notes: List<String>,
        ) : Decision() {
            override val ok = true
            /** 该用哪条线：主线还是测试。 */
            val tier: KernelTier get() = if (useTestScheme) KernelTier.TEST else KernelTier.MAINLINE
        }

        /** 拒绝构建，必须给出可操作的补救方式。 */
        data class Blocked(
            val title: String,
            val detail: List<String>,
            val remedy: String,
        ) : Decision() {
            override val ok = false
        }
    }

    /** 主线系列 —— **单一来源**，直接引用注册表，避免两处清单漂移。 */
    val MAINLINE_SERIES: List<String> get() = BaselineRegistry.MAINLINE_SERIES

    /** 测试系列（需开「5.x 内核支持（beta）」）。 */
    val TEST_SERIES: List<String> get() = BaselineRegistry.TEST_SERIES

    /**
     * 先检查内核版本，再选方案。
     *
     * @param kernelRelease boot.img 里读出的 release 串（如 `6.6.89-android15-8-g...`）
     * @param allowTestKernel 设置里「5.x 内核支持（beta）」开关。**默认 false**
     *        —— 漏传时得到的是"拒绝未验证的 5.x"，安全的那一侧。
     * @param override 设置里「内核系列」的强制指定。**[勘误] 第一版没有这个参数**，
     *        于是"强制 6.x"配一台 5.x 机器会在这里被判 Selected、一路跑到
     *        `KernelPack` 内部的 [BuildGate] 才被拦下 —— 也就是说 boot.img 白解了一遍、
     *        日志刷了一屏才说不行。现在在这里就拦，代价为零。
     */
    fun select(
        kernelRelease: String,
        allowTestKernel: Boolean = false,
        override: SeriesOverride = SeriesOverride.AUTO,
    ): Decision {
        val series = BuildGate.seriesOf(kernelRelease)
            ?: return Decision.Blocked(
                title = "认不出这个内核版本，已停止构建",
                detail = listOf(
                    "boot.img 里的 release 串是「$kernelRelease」，解析不出主.次版本号。",
                    "选方案完全依赖内核版本 —— 认不出来就无法决定用哪套布局规则，",
                    "继续下去只会把偏移改到错误的位置上。",
                ),
                remedy = "请确认这个 boot.img 确实来自目标设备（有些厂商会改 release 串）。",
            )

        val major = series.substringBefore('.').toIntOrNull() ?: 0

        // ── 强制指定 vs 实测：**在这里就拦**，不要等到打包内部 ──
        // 放在最前面（拿到 major 之后立刻判）：无论目标是主线、测试还是不支持，
        // "强制的那套与实测对不上"都是最优先要说的错误。
        if (override != SeriesOverride.AUTO) {
            val forced = override.wireValue.toIntOrNull() ?: 0
            if (forced != major) {
                return Decision.Blocked(
                    title = "强制指定与实测内核不一致，已停止构建",
                    detail = listOf(
                        "设置里强制指定 ${forced}.x，但 boot.img 实测是 $series（$kernelRelease）。",
                        "两边的 rt_mutex / task_struct 布局规则不同，硬按强制的那套改偏移会打到错误字段上。",
                    ),
                    remedy = "到「设置 → 内核系列」改成「自动（按 boot.img 实测）」，或换成与该内核匹配的 boot.img。",
                )
            }
        }

        // ── ① 主线：6.6 / 6.12 ──
        if (series in MAINLINE_SERIES) {
            return Decision.Selected(
                series = series,
                major = major,
                useTestScheme = false,
                notes = listOf("主线内核 $series，采用主线方案。"),
            )
        }

        // ── ② 6.x 但不在主线（如 6.1）：明确拒绝，**不**落进 5.x 的 beta 开关 ──
        // 为什么单独拦：6.1 是 6.x，容易被误以为"和 6.6 差不多"；但它的
        // rt_mutex_waiter 是 flat 形态（88 字节，task@0x30），与 6.6/6.12 的 nested
        // （112 字节，task@0x50）**不是一套**。放它过去 = 拿 6.6 的偏移打 6.1 的内核内存。
        if (major == 6) {
            return Decision.Blocked(
                title = "不支持的 6.x 内核版本 $series",
                detail = listOf(
                    "实测内核：$kernelRelease",
                    "本工程只支持 6.x 里的 ${MAINLINE_SERIES.joinToString(" / ")}（主线）。",
                    "$series 不在支持范围内：它的 rt_mutex_waiter 布局族与主线不同，",
                    "没有为该系列登记过偏移产物 —— 硬按主线偏移构建会打到错误的结构体字段上。",
                ),
                remedy = "该 6.x 次版本暂不支持；请在「设置 → 内核系列」保持「自动」，并勿强行构建。",
            )
        }

        // ── ③ 其余主版本（4.x / 7.x 等）──
        if (major !in 5..6) {
            return Decision.Blocked(
                title = "不支持的内核主版本 $major",
                detail = listOf(
                    "实测内核：$kernelRelease",
                    "本工具只处理 6.x（主线 ${MAINLINE_SERIES.joinToString(" / ")}）",
                    "与 5.x（测试 ${TEST_SERIES.joinToString(" / ")}）。",
                    "4.x / 7.x 的 rt_mutex 与 task_struct 布局与两者都不同，不存在“回退默认值”。",
                ),
                remedy = "该设备暂不支持；请勿强行构建。",
            )
        }

        // ── 5.x：默认**不采用**五系方案 ──
        if (!allowTestKernel) {
            return Decision.Blocked(
                title = "识别到 5.x 内核，但 5.x 支持未开启",
                detail = listOf(
                    "实测内核：$kernelRelease（$series，属 5.x 测试线）",
                    "当前「5.x 内核支持（beta）」是**关闭**的，因此不采用五系方案。",
                    "5.x 的布局锚点只有上游 target.h 一条腿，本工程没有该内核的 BTF / 反汇编实测，",
                    "偏移是否与这台机器一致**未经本工程验证**，所以默认不参与构建。",
                ),
                remedy = "确需为 5.x 构建：到「设置」打开「5.x 内核支持（beta）」（在「关于」上面），再回来构建。",
            )
        }

        return Decision.Selected(
            series = series,
            major = major,
            useTestScheme = true,
            notes = listOf(
                "[测试内核] $series 属测试线，你已手动开启「5.x 内核支持（beta）」。",
                "本工程未在 5.x 上实测过偏移，请只用于 beta 验证。",
            ),
        )
    }

    /** 构建前给用户看的一句话摘要（UI 可直接用）。 */
    fun describe(decision: Decision): String = when (decision) {
        is Decision.Selected ->
            "内核 ${decision.series}（${decision.tier.label}）→ 采用" +
                if (decision.useTestScheme) "测试方案" else "主线方案"
        is Decision.Blocked -> "${decision.title}：${decision.remedy}"
    }
}
