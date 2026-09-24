package com.kernelpack.offsets

/**
 * GhostLock / IonStack 生态的 `offsets.json` 互通层。
 *
 * ### 为什么单独一个模块
 *
 * 这份格式不是我们发明的 —— 它是 NebuSec IonStack / GhostLock 的**运行时偏移载体**：
 * 一份通用载荷 + 每个机型一份 `offsets.json`，换机型只要换 json。
 * 我方主线是「编译期把偏移烤进 `.so`」，与它并存需要一层**明确的边界**，
 * 所以这个包里只做三件事：**读、写、合并**，不碰任何偏移算法。
 *
 * ### 格式来源（已核对，不是猜的）
 *
 * 字段表来自两处**对死验证**的证据：
 * 1. `GhostLock.apk` 的 `classes.dex` 字符串表（它 `org.json` 解析用的键）；
 * 2. `pyyyc/honor-6.12.38-43499-research` 的 `config/offsets.json`（BTF 实测样本）。
 * 两者 **31/31 键完全命中** —— 同一格式确凿。
 *
 * ### 模块化设计（为将来更新）
 *
 * - **字段表集中在 [OffsetsSchema]**：将来 GhostLock 加字段，只改那一处；
 * - **[OffsetsDocument.unknown] 保留所有不认识的键**：往返（读进来再写出去）
 *   **不会丢数据**，即使对方格式先行扩展，我们这边也不会静默截断；
 * - 不引第三方 JSON 依赖（本包的 [MiniJson] 自带解析），
 *   这样它能塞进任何 Android 工程，也便于单独测试。
 */
object OffsetsSchema {

    /** 顶层标量字段（字符串原样保留，不做数值解释 —— 有些是 64 位地址，有些是标志位）。 */
    val SCALARS: List<String> = listOf(
        "release",
        "kernel_sha256",
        "kernel_phys_load",
        "mm_struct_sz",
        "pselect_waiter_shift",
        "compact_waiter",
    )

    /** 内核符号地址组。22 键为完整集（DEX 里只出现 9 个，荣耀样本是全的）。 */
    val SYMBOL_KEYS: List<String> = listOf(
        "off_init_task",
        "off_init_cred",
        "off_root_task_group",
        "off_selinux_enforcing",
        "off_selinux_blob_sizes",
        "off_security_hook_heads",
        "off_slide_nfulnl_logger",
        "off_slide_loggers_0_1",
        "off_slide_boot_id",
        "off_slide_sysctl_bootid",
        "off_remove_waiter",
        "off_rt_mutex_adjust_prio_chain",
        "off_rb_erase",
        "off_commit_creds",
        "off_worker_thread",
        "off_random_table",
        "off_do_mcast_group_source",
    )

    /** 结构体字段偏移组。 */
    val STRUCT_KEYS: List<String> = listOf(
        "rt_mutex_waiter_size",
        "rt_mutex_waiter_task",
        "rt_mutex_waiter_lock",
        "rt_mutex_waiter_prio",
        "struct_mm_struct",
        "task_tasks",
        "task_comm",
        "task_cred",
        "task_real_cred",
        "task_pid",
        "task_tgid",
        "task_prio",
        "task_normal_prio",
        "task_sched_task_group",
        "task_atomic_flags",
        "task_seccomp",
        "task_pi_lock",
        "task_pi_waiters",
        "task_pi_top_task",
        "task_pi_blocked_on",
    )

    /**
     * 两个「布局判别」字段 —— 我方 [com.kernelpack.offset.PselectFeasibility] 依赖它们：
     * `compact_waiter` 对应 `WaiterLayout.COMPACT_5_10`，`pselect_waiter_shift` 是 fd_set 字偏移。
     * 单独点出来是因为它们是**跨模块契约**，改名会同时打断两个模块。
     */
    const val KEY_COMPACT_WAITER = "compact_waiter"
    const val KEY_PSELECT_WAITER_SHIFT = "pselect_waiter_shift"
    const val KEY_RELEASE = "release"
    const val KEY_KERNEL_SHA256 = "kernel_sha256"
}

/** 一份 offsets.json 的内存表示。 */
data class OffsetsDocument(
    /** 顶层标量，**原样字符串保留**（地址/标志位混在一起，不做数值解释）。 */
    val scalars: Map<String, String> = emptyMap(),
    val symbols: Map<String, Long> = emptyMap(),
    val structFields: Map<String, Long> = emptyMap(),
    /**
     * 本模块**不认识**的键，原样保留。
     *
     * 这是模块化的关键：对方格式扩展后，我们的往返读写**不会把新字段吃掉**。
     * 早期版本若不保留未知键，用户"导入再导出"一次就会静默丢数据 —— 那是最难查的一类 bug。
     */
    val unknown: Map<String, MiniJson> = emptyMap(),
) {
    val release: String? get() = scalars[OffsetsSchema.KEY_RELEASE]
    val kernelSha256: String? get() = scalars[OffsetsSchema.KEY_KERNEL_SHA256]
    val compactWaiter: Boolean get() = scalars[OffsetsSchema.KEY_COMPACT_WAITER]?.trim() == "1"
    val pselectWaiterShift: Long? get() = scalars[OffsetsSchema.KEY_PSELECT_WAITER_SHIFT]?.toLongOrNull()

    /** 缺失的符号键（用于「这份 json 够不够用」的自检）。 */
    fun missingSymbols(): List<String> = OffsetsSchema.SYMBOL_KEYS.filterNot { symbols.containsKey(it) }

    /** 缺失的结构体键。 */
    fun missingStructFields(): List<String> = OffsetsSchema.STRUCT_KEYS.filterNot { structFields.containsKey(it) }
}

/**
 * 极简 JSON —— 只为 offsets.json 服务，**不追求通用**。
 *
 * 之所以自己写而不引依赖：这个模块要能在单元测试里裸跑（不需要 Android 运行时），
 * 且格式本身极扁（两层 map + 标量），引一个 JSON 库进来不划算。
 */
sealed interface MiniJson {
    data class Str(val value: String) : MiniJson
    data class Num(val raw: String) : MiniJson
    data class Bool(val value: Boolean) : MiniJson
    data object Null : MiniJson
    data class Arr(val items: List<MiniJson>) : MiniJson
    data class Obj(val fields: Map<String, MiniJson>) : MiniJson
}
