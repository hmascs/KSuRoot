package com.kernelpack.offset

/**
 * pselect 栈覆盖**可行性**模型。
 *
 * 背景：GhostLock 的 UAF 利用要靠 `pselect6` 把伪造的 `rt_mutex_waiter` 字段
 * （`task` / `lock`）**铺在内核栈上**，再让被释放的 waiter 正好落在那里。
 * 「铺」得进去与否，取决于两层完全独立的东西：
 *
 *   1. **结构体布局**（waiter 有多长、`task` 落在第几个字）—— 由内核源码/配置决定；
 *   2. **调用链深度**（waiter 在第几个字上）—— 由编译器的 PGO/LTO 决定，**不是内核版本决定的**。
 *
 * 上游 `JoinChang/ghostlock-oneplus` 的结论与本模型一致：
 * > The pselect stack overlay requires the freed `rt_mutex_waiter` to land within the
 * > user-controllable `stack_fds` region. **Where it lands is determined by compiler
 * > PGO/LTO profiles, not the kernel version.**（README「Not Feasible」节）
 *
 * 因此本文件把「布局」与「落点」拆成两个字段：前者可以按内核查表，
 * 后者**必须逐机型实测**（或至少从目标内核反汇编推）。混在一起就会重演
 * 「按内核版本外推布局」那类错误。
 */

/** `rt_mutex_waiter` 在目标内核里的实际形态。**必须实测，不可按版本外推。** */
enum class WaiterLayout(
    val label: String,
    /** `RT_WAITER_TASK_WORD_REL`：`task` 字段在 waiter 内的字下标。 */
    val taskWordRel: Int,
    /** `RT_WAITER_LOCK_WORD_REL`：`lock` 字段在 waiter 内的字下标。 */
    val lockWordRel: Int,
    val sizeBytes: Int,
) {
    /**
     * 5.10 形态：`task@0x30 lock@0x38 prio@0x40 deadline@0x48`，**无** wake_state / ww_ctx。
     * 80 字节。出处：`yakidango-official/GhostLock-H80GT` 的
     * `annap-AGT-AN00_9.0.0.230/target.h`（5.10.236，荣耀 80 GT）。
     */
    COMPACT_5_10("compact-5.10", taskWordRel = 6, lockWordRel = 7, sizeBytes = 80),

    /**
     * 6.1 形态（**本工程实测**）：`task@0x30 lock@0x38 wake_state@0x40 prio@0x44
     * deadline@0x48 ww_ctx@0x50`，88 字节。
     *
     * 出处：用户提供的真机 `boot.img`（6.1.145-android14-11-maybe-dirty）解出的裸内核，
     * 从中扫出 BTF 段读出的 `struct rt_mutex_waiter` 完整布局（133758 个类型）。
     *
     * [勘误] 上游 `JoinChang/ghostlock-oneplus` 把 6.1 一概记为 `Compact (10 words)`、
     * 尺寸 ≤ 0x60。**实测不成立**：本机 6.1.145 是 88 字节且**有** `wake_state`，
     * 与它的 `compact` 分支（只写 0x30/0x38/0x40/0x48，不写 wake_state）不是同一形态。
     * 逐字段看，6.1 的 `task`/`lock` 位置与 5.10 **恰好相同**（都是字 6/7），
     * 这正是两者都能被它的 pselect 表铺中的原因；但整体尺寸不同，混用会写错字段。
     */
    FLAT_6_1("flat-6.1", taskWordRel = 6, lockWordRel = 7, sizeBytes = 88),

    /**
     * 6.6 形态（**本机实测**）：`tree`（含 prio/deadline）在前，`pi_tree` 在后，
     * `task@0x50 lock@0x58 wake_state@0x60 ww_ctx@0x68`，112 字节。
     *
     * 出处：本机运行内核 `/sys/kernel/btf/vmlinux`（6.6.89-android15-8，
     * 140652 个类型）。与 `kallsyms`/`disasm` 的既有结论一致（pi_blocked_on 等值也相符）。
     */
    NESTED_6_6("nested-6.6", taskWordRel = 10, lockWordRel = 11, sizeBytes = 112),

    /** 没能实测出来。**不允许退化成"大概和 6.6 一样"** —— 那正是本工程勘误两次的错误。 */
    UNKNOWN("unknown", taskWordRel = -1, lockWordRel = -1, sizeBytes = 0),
    ;

    /** 只有实测出来的形态才允许参与可行性判定。 */
    val known: Boolean get() = this != UNKNOWN

    /**
     * 该布局下 `waiter_word` 的**上限**（超出即铺不进去）。
     *
     * [证据等级：**上游文档值**，非本工程推导]
     * 取值直接采用 `JoinChang/ghostlock-oneplus` README「Stack Layout Feasibility」原文：
     * ```
     *   For 6.12 nested waiter (14 words): max feasible waiter word = 3
     *   For 5.10/6.1 compact waiter (10 words): max feasible waiter word = 7
     * ```
     * （6.6 与 6.12 同为 nested，共用 3。）
     *
     * **为什么不自己推**：可控区的几何是确定的 —— 3 个 fd_set × `PSELECT_ROUTE_NFDS=320` 位
     * ÷ 64 = 每集 5 字，合计可控全局字区间 `[0, 14]`。但把「上限」从这 15 个字导出来时，
     * 本工程试过的三种自然边界**都对不上上游的 3 / 7**：
     * ```
     *   公式          nested   compact
     *   14 - taskRel     4        8
     *   14 - lockRel     1        7
     *   15 - sizeWords   1        5
     *   上游文档值        3        7   ← 本工程采用
     * ```
     * 上游没写它的推导过程。既然推不出来，就**照抄文档值并标明是文档值**，
     * 而不是编一个"看起来能自洽"的公式塞进去 —— 那正是本工程两次布局勘误的同一种错。
     *
     * [风险] 这是全工程唯一一处**没有实测支撑**的数值。真要用在边界机型上
     * （落点恰好等于上限），必须先在目标机上实测落点再放行。
     * [待验证] 见 `OffsetProvenance`/单测：一旦有人量出边界机型，应把结论回填到此处。
     */
    val maxFeasibleWord: Int
        get() = when (this) {
            COMPACT_5_10, FLAT_6_1 -> 7
            NESTED_6_6 -> 3
            UNKNOWN -> -1
        }

    companion object {
        /**
         * 只知道 `task`/`lock` 字下标时的定型 —— **有歧义就返回 [UNKNOWN]，绝不猜**。
         *
         * 5.10 与 6.1 的字下标完全相同（6/7），所以这一对输入本身就**不足以定型**。
         * 与其默默返回"先登记的那个"，不如老实说不确定，让调用方去补 `sizeBytes`
         * 或 `wake_state`。理由与全工程一致：**给不出就报缺失，不要给一个可能写坏内核的答案。**
         */
        fun of(taskWordRel: Int, lockWordRel: Int): WaiterLayout =
            fromWordRels(taskWordRel, lockWordRel).singleOrNull() ?: UNKNOWN

        /**
         * 候选集（可能多个）—— 需要自己看清歧义时用这个。
         *
         * ```
         *   fromWordRels(10, 11) → [NESTED_6_6]                 唯一，可采信
         *   fromWordRels( 6,  7) → [COMPACT_5_10, FLAT_6_1]     有歧义，须补信息
         * ```
         */
        fun fromWordRels(taskWordRel: Int, lockWordRel: Int): List<WaiterLayout> =
            entries.filter { it.known && it.taskWordRel == taskWordRel && it.lockWordRel == lockWordRel }

        /**
         * 按字下标 + 字节数定型：**唯一确定**的入口。
         *
         * BTF 与反汇编都能同时给出这两个量，所以正常路径都应该走这里。
         */
        fun of(taskWordRel: Int, lockWordRel: Int, sizeBytes: Int): WaiterLayout =
            fromWordRels(taskWordRel, lockWordRel)
                .firstOrNull { it.sizeBytes == sizeBytes } ?: UNKNOWN

        /**
         * 按字下标 + 有无 `wake_state` 字段定型。
         *
         * 什么时候用它：只从反汇编里看出"`task` 后面 8 字节是 `lock`，再往后有没有第三个指针"
         * 这类信息、拿不到结构体总大小时。5.10 没有 `wake_state`，6.1 有。
         */
        fun of(taskWordRel: Int, lockWordRel: Int, hasWakeState: Boolean): WaiterLayout =
            fromWordRels(taskWordRel, lockWordRel)
                .firstOrNull { (it.sizeBytes > COMPACT_5_10.sizeBytes) == hasWakeState } ?: UNKNOWN
    }
}

/** 可行性判定。 */
enum class StackLayoutVerdict(val label: String, val feasible: Boolean) {
    /** `task`/`lock` 落在可控字区间内 → 可以铺。 */
    FEASIBLE("可行", true),

    /**
     * 调用链被 PGO/LTO 优化掉了：编译器把 `do_futex` **内联**进 `__arm64_sys_futex`，
     * 甚至直接内联 `futex_wait_requeue_pi`，于是 waiter 落点被推到可控区之外。
     *
     * 实证（上游 README 的 Not Feasible 表 + knowlily 的荣耀 BVL-AN16）：
     * ```
     *   OPPO Find X9 Ultra   6.12.58   PGO 内联 do_futex → word=14
     *   OnePlus 12           6.1.141   PGO 内联 do_futex → word=13/19
     *   CPH2763 (OPPO)       6.1.115   PGO 内联         → word=24
     *   荣耀 BVL-AN16        6.1.128   内联 do_futex    → PSELECT_WAITER_WORD_SHIFT=11
     * ```
     * 注意荣耀那条：**同一厂商的 6.6 已修好**（AAK-AN00 6.6.89 shift=0），
     * 所以这是"厂商在某个版本上做什么"，不是"哪个内核版本不行"。
     */
    PGO_BLOCKED("被 PGO 内联阻断", false),

    /**
     * 架构层面不兼容：调用链本身与标准 GKI 不同构，无法用改参数绕过。
     * 荣耀 BVL-AN16 即属此类（`__arm64_sys_futex` 的 switch 直接 `bl futex_wait_requeue_pi`，
     * **不经** `do_futex`，futex 调用链从三层变两层）。
     */
    ARCH_INCOMPATIBLE("架构不兼容", false),

    /** 没实测过落点 —— 与 `PGO_BLOCKED` 必须分开：**不知道**不等于**不行**。 */
    UNKNOWN("未实测", false),
}

/**
 * 一台设备的 pselect 栈覆盖可行性结论。
 *
 * @param measuredWord    实测到的 `waiter_word`（waiter 的 `task` 字段相对 fd_set 起点的字下标）。
 *                        **没有实测就填 null**，不要按内核版本填一个"应该是 2"。
 * @param measuredOn      实测出处（哪台机器/哪个镜像/哪份反汇编）。
 * @param wordOverride    已知落点但未在本工程复现时的占位（同样必须带出处）。
 */
data class PselectFeasibility(
    val layout: WaiterLayout,
    val measuredWord: Int?,
    val measuredOn: String,
    val wordOverride: Int? = null,
) {
    /** 用于判定的落点：优先实测值。 */
    val word: Int? get() = measuredWord ?: wordOverride

    val verdict: StackLayoutVerdict
        get() {
            if (!layout.known || word == null) return StackLayoutVerdict.UNKNOWN
            val w = word!!
            // 负落点：waiter 在 fd_set 之下，fd_set 根本写不到它 —— 无从铺起。
            // 实证：iQOO Neo 10 CN 6.1.84 word=-11、OPPO PKW110 5.15.180 word=-29。
            if (w < 0) return StackLayoutVerdict.PGO_BLOCKED
            return if (w <= layout.maxFeasibleWord) {
                StackLayoutVerdict.FEASIBLE
            } else {
                StackLayoutVerdict.PGO_BLOCKED
            }
        }

    /** 一句话结论，直接可以放 UI。 */
    fun summary(): String {
        val w = word?.toString() ?: "未实测"
        val basis = if (measuredWord != null) "实测" else if (wordOverride != null) "已知落点" else "—"
        return "${layout.label}（${layout.sizeBytes} 字节）· waiter_word=$w（$basis：$measuredOn）" +
            " · 上限 ${layout.maxFeasibleWord} · ${verdict.label}"
    }

    companion object {
        /** 已知布局但没实测落点 —— 如实标记为"未实测"，而不是猜一个数。 */
        fun unmeasured(layout: WaiterLayout, why: String): PselectFeasibility =
            PselectFeasibility(layout, measuredWord = null, measuredOn = why)
    }
}
