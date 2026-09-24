package com.kernelpack.vivo

/**
 * 蓝厂（vivo/iQOO）`vr.ko` 反 root 的 **per-task 标记清除**逻辑。
 *
 * ### 这是什么
 *
 * `vr.ko` 会在 `fork`/`clone` 时给每个来自 app 的 task 打标记；该 task 之后
 * 一旦持有 `euid 0`，**`sys_exit` tracepoint 探针会把它杀掉**。
 * 所以提权后、在跑提权校验（读子进程 `getuid()`）**之前**，必须把标记抹掉。
 *
 * ### 归属：**仅蓝厂方案**
 *
 * 这一步**只挂在 `BaselineScheme.VIVO` 上**，通用方案不带。
 * 曾经试过把它做成"按运行时检测生效的横切能力"（探到 vr.ko 就挂，
 * 与方案无关）—— 那是错的：`vr.ko` 是 vivo 的私有反 root 模块，
 * 把它横切到所有方案等于让别的厂商也走一条没验证过的路径。
 * 厂商私有字段不可跨厂商照搬，这是本工程既有的一条注意事项。
 *
 * ### 来源与可信度
 *
 * 全部来自上游**源码** `YuKongA/ghostlock-app` 的 `src/core/main.c`
 * （不是从二进制字符串反推的）。本文件是那份实现的**纯逻辑投影**：
 * 只算"要写哪些地址、写什么值、什么条件下跳过"，**不碰内核内存**。
 *
 * ### ⚠️ 一条上游作者自己留的未决警告
 *
 * tagB 那笔写会**整字清零 `0x28-0x2f`**（因为利用原语是 64 位粒度，
 * 而 tag B 在 `+0x2c` 未对齐）。上游注释原文要求：
 * *"请在你的 6.1.145 内核上实测确认清 `0x28-0x2f` 是安全的；
 * 若不安全，把 tagB 那次写入注释掉。"*
 *
 * 因此 [VrKoPlan.includeTagB] 默认**保留**（与上游一致），但把它做成**显式开关**，
 * 便于实测后一键关掉，而不是让人去改代码。
 */
object VrKoBypass {

    /** tag A 的字节偏移。上游硬编码，**不在 offsets.json 里**。 */
    const val VR_TAG_A_OFF: Long = 0x06

    /** tag B 的字节偏移。同上，未对齐到 8。 */
    const val VR_TAG_B_OFF: Long = 0x2c

    /** syscall tracepoint 标志位；清 `thread_info.flags` 时一并清掉。 */
    const val VR_SYSCALL_TP_FLAG: Long = 0x400L

    /** 上游 `do_one_write` 的 `mode` 实参 —— pselect 写入模式。 */
    const val WRITE_MODE: Int = 1

    /** 上游 `do_one_write` 的 `leaf` 实参 —— 用 `fake_right == 0` 那条臂。 */
    const val WRITE_LEAF: Int = 1

    /**
     * `/proc/modules` 里 `vr` 模块的**匹配规则**（上游原文）：
     * 行首匹配 `vr`，且第 3 个字符是空格或下划线 —— 避免误命中 `vrm`、`vradio` 之类。
     */
    fun looksLikeVrModule(line: String): Boolean =
        line.length > 2 && line.startsWith("vr", ignoreCase = true) &&
            (line[2] == ' ' || line[2] == '_')

    /**
     * 判定是否需要抹标记。
     *
     * @param modulesText `/proc/modules` 的内容；`null` 表示**读不到**
     *   （SELinux enforcing 下 untrusted_app 读不到是常态）。
     *   读不到时按上游策略**保守假设已加载** —— 宁可多写一笔，也不要漏抹而被杀。
     */
    fun needsBypass(modulesText: String?): Boolean {
        if (modulesText == null) return true
        return modulesText.lineSequence().any(::looksLikeVrModule)
    }

    /**
     * 规划要写哪几笔。返回**地址与用途**，调用方负责真正的写入。
     *
     * @param childTask 子进程 `task_struct` 的内核地址。
     * @param threadInfoFlagsOff `TASK_THREAD_INFO_FLAGS_OFF`，**必须取自
     *   `offsets.json` 的 `struct_fields`**，不可写死 —— 上游源码里它同时用于
     *   VR 抹标记与 W3 seccomp 绕过，写错会连带把 seccomp 绕过搞坏。
     * @param includeTagB 是否包含 tagB 那笔（见类注释里的未决警告）。
     */
    fun plan(
        childTask: Long,
        threadInfoFlagsOff: Long,
        includeTagB: Boolean = true,
    ): VrKoPlan {
        // 第 1 笔：清 thread_info.flags 这个 64 位字。
        // 它一次覆盖两件事：tag A（+0x06）与 VR_SYSCALL_TP_FLAG（0x400）——
        // 后者一清，该 task 立刻从 sys_exit 慢路径上摘下来。
        val writes = mutableListOf(
            VrKoWrite(
                address = childTask + threadInfoFlagsOff,
                label = "VR: flags+tagA",
                reason = "覆盖 tag A(+0x06) 并清 VR_SYSCALL_TP_FLAG(0x400)",
            ),
        )
        // 第 2 笔：tag B 在 +0x2c，未对齐；向下取 8 字节边界后整字清零。
        // 这笔**依赖第 1 笔成功**（上游源码里是 `if (vr_ok)` 串联的）。
        if (includeTagB) {
            writes += VrKoWrite(
                address = (childTask + VR_TAG_B_OFF) and 7L.inv(),
                label = "VR: tagB",
                reason = "tag B 在 +0x2c 未对齐，向下对齐到 +0x28 后整字清零",
                dependsOnPrevious = true,
            )
        }
        return VrKoPlan(writes)
    }
}

/** 一笔待执行的内核写入。 */
data class VrKoWrite(
    val address: Long,
    val label: String,
    val reason: String,
    /** 为真时必须等前一笔成功才执行（上游源码里的串联关系）。 */
    val dependsOnPrevious: Boolean = false,
)

/** 一次 VR.ko 绕过要执行的全部写入。 */
data class VrKoPlan(val writes: List<VrKoWrite>) {
    val isEmpty: Boolean get() = writes.isEmpty()

    /**
     * 每笔写入都带 `mode`/`leaf` —— 上游 `do_one_write(target, desc, mode, leaf)` 的后两个参数
     * 不是值或长度，而是 **pselect 写入模式**与**用哪条臂**。这里固定为上游 VR 路径所用的取值。
     */
    val mode: Int get() = VrKoBypass.WRITE_MODE
    val leaf: Int get() = VrKoBypass.WRITE_LEAF
}
