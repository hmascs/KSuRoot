package com.kernelpack.offsets

/**
 * 把 GhostLock `offsets.json` 的符号键，映射到**我方 [com.kernelpack.resolve.SymbolCatalog] 的键**。
 *
 * ### 这一步是干什么的
 *
 * 我方主打方案是「**直接把偏移写进 .so**」—— 那条路要的是
 * [com.kernelpack.resolve.SymbolCatalog] 里那 29 个键。
 * 而 `offsets.json` 给的是它自己那套键。两者**不是同一套词汇表**，
 * 所以要让 offsets.json 驱动我方打补丁，必须先有一张对照表。
 *
 * ### ⚠️ 关键事实：两边**只部分重叠**
 *
 * 逐条核对后的实际情况：
 *
 * | | 我方（29 键） | GhostLock（17 键） |
 * |---|---|---|
 * | 取向 | ashmem / fops / pipe / configfs 路线 | slide / rt_mutex 路线 |
 * | 能直接对上 | **5 个**（见 [DIRECT]） | |
 * | 各自独有 | 24 个 | 12 个 |
 *
 * **也就是说：一份 offsets.json 只能部分驱动我方的 .so 写入，不能全部。**
 * 这不是实现缺陷，是两个项目走了不同的利用路线 —— 如实记录，
 * 不为了让映射"看起来完整"而硬凑（硬凑就是把 A 的数值当 B 用，正是铁律禁止的）。
 *
 * ### 缺的那部分怎么办
 *
 * 我方独有的键（如 `ASHMEM_*` 系列）仍须走原有路径：
 * 从 `boot.img` 提符号（[com.kernelpack.resolve.OffsetResolver]）。
 * 本映射只负责"能省一次提取的地方就省"。
 */
object SymbolKeyMapping {

    /**
     * 能**直接**对上的键：`offsets.json 键 → 我方 SymbolCatalog 键`。
     *
     * 每条都要求语义**完全一致**才算数 —— 名称像不算。
     */
    val DIRECT: Map<String, List<String>> = mapOf(
        // 一对多：同一个上游键在我方对应多个键。
        // 依据是 GhostLock 自己的 `runtime_struct_offsets.h` 就是这么用的 ——
        // 它把 `off_init_task` 同时喂给 INIT_TASK 与 SLIDE_INIT_TASK，
        // 把 `off_slide_boot_id` 同时喂给 SLIDE_RANDOM_BOOT_ID_DATA 与 SLIDE_SYSCTL_BOOTID。
        //
        // [2026-09-24 勘误] 最初写成一对一、只映射 5 个，是**过于保守**：
        // 它按"名字要像"来判断，而实际判据应该是"上游是不是把它用在了这个位置上"。
        "off_init_task" to listOf("INIT_TASK", "SLIDE_INIT_TASK"),
        "off_root_task_group" to listOf("ROOT_TASK_GROUP", "SLIDE_ROOT_TASK_GROUP"),
        "off_selinux_blob_sizes" to listOf("SELINUX_BLOB_SIZES"),
        "off_selinux_enforcing" to listOf("SELINUX_ENFORCING"),
        "off_security_hook_heads" to listOf("SECURITY_HOOK_HEADS"),
        "off_slide_nfulnl_logger" to listOf("SLIDE_NFULNL_LOGGER"),
        "off_slide_loggers_0_1" to listOf("SLIDE_LOGGERS_0_1"),
        "off_slide_boot_id" to listOf("SLIDE_RANDOM_BOOT_ID_DATA", "SLIDE_SYSCTL_BOOTID"),
        "off_slide_sysctl_bootid" to listOf("SLIDE_SYSCTL_BOOTID"),
    )

    /**
     * 我方**没有**对应键的 offsets.json 条目。
     *
     * 列出来是为了让"覆盖不全"这件事在代码里可见，而不是靠人记。
     * 将来若我方新增了对应路线的符号，从这张表里移一条到 [DIRECT] 即可。
     */
    val UNMAPPED_FROM_JSON: Set<String> = setOf(
        "off_remove_waiter",
        "off_rt_mutex_adjust_prio_chain",
        "off_rb_erase",
        "off_commit_creds",
        "off_worker_thread",
        "off_random_table",
        "off_do_mcast_group_source",
        "off_security_hook_heads_extra",
    )

    /** 我方有、而 offsets.json 不提供的键（只能走 boot.img 提取）。 */
    val JSON_CANNOT_SUPPLY: Set<String> = setOf(
        // ── ashmem / fops 路线（8）── GhostLock 不走这条路，只能从 boot.img 读
        "ASHMEM_MISC_FOPS", "ASHMEM_FOPS", "ASHMEM_IOCTL", "ASHMEM_COMPAT_IOCTL",
        "ASHMEM_MMAP", "ASHMEM_OPEN", "ASHMEM_RELEASE", "ASHMEM_SHOW_FDINFO",
        // ── configfs / splice（4）──
        "CONFIGFS_READ_ITER", "CONFIGFS_BIN_WRITE_ITER", "COPY_SPLICE_READ", "NOOP_LLSEEK",
        // ── slab / pipe（2）──
        "KMALLOC_CACHES", "ANON_PIPE_BUF_OPS",
        // ── 可选键（1）──
        "SLIDE_NFULNL_LOG_PACKET",
    )

    /** 一次映射的结果。 */
    data class Mapped(
        /** 可直接喂给我方打补丁流程的键值（已换成我方键名）。 */
        val symbols: Map<String, Long>,
        /** 这次 offsets.json 里出现的、我方用不上的键 —— 如实保留，便于诊断。 */
        val unusedFromJson: List<String>,
    )

    /**
     * 把一份 offsets.json 里的符号映射成我方键名。
     *
     * **只搬运，不换算**：两边都是"内核镜像内的字节偏移"，量纲一致，直接搬。
     * 不做任何加减、不按版本外推。
     */
    fun mapSymbols(doc: OffsetsDocument): Mapped {
        val out = LinkedHashMap<String, Long>()
        val unused = mutableListOf<String>()
        for ((jsonKey, value) in doc.symbols) {
            val ours = DIRECT[jsonKey]
            if (ours.isNullOrEmpty()) {
                unused += jsonKey
            } else {
                // 一对多：同一个值写到多个我方键上（与上游用法一致）
                for (k in ours) out[k] = value
            }
        }
        return Mapped(out, unused.sorted())
    }

    /** 这份 offsets.json 对我方 .so 写入的**覆盖率**（能供上多少 / 我方一共要多少）。 */
    fun coverage(doc: OffsetsDocument): Double {
        val total = DIRECT.values.sumOf { it.size } + JSON_CANNOT_SUPPLY.size
        if (total == 0) return 0.0
        return mapSymbols(doc).symbols.size.toDouble() / total.toDouble()
    }

    /** 我方全部必需键里，offsets.json 能供上几个（用于回答"还差多少要提"）。 */
    fun coverableCount(): Int = DIRECT.values.sumOf { it.size }
}
