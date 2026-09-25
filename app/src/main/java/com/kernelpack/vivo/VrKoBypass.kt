package com.kernelpack.vivo

import com.kernelpack.profile.BaselineScheme

/**
 * 蓝厂（vivo/iQOO）`vr.ko` 反 root 的**判定逻辑库**。
 *
 * ### 这个文件为什么只剩判定
 *
 * 它原本还有一套 `plan()` / `VrKoWrite` / `VrKoPlan`，规划"要往哪几个内核地址写什么"。
 * 那套东西**已经被删除**，原因是它做的事**没有任何人能执行**：
 *
 * > Kotlin 侧写不了内核内存。它没有那个原语。
 * > 真正的内核写发生在**载荷（C 代码）里**，走的是
 * > `pipe_phys_write_data` / `pipe_write64`。
 *
 * 把 `plan()` 接到提权流程上，只会得到一个"看起来做了事、其实什么都没发生"的假动作 ——
 * 比没有更糟，因为后来的人会以为这一步已经接好了。正确的分工是：
 *
 * | 层 | 干什么 |
 * |---|---|
 * | 载荷（C，`root.c` 的 `patch_task_vr_tag`） | **真的**抹标记：先清 `VR_SYSCALL_TP_FLAG`，再逐字节清 tag A / tag B，最后回读自证 |
 * | 本文件（Kotlin） | **判定**：vr.ko 在不在、要不要提示用户改选蓝厂方案 |
 * | [VrKoPayloadCheck] / [VrKoPayloadGate]（Kotlin） | **证明**：这份载荷到底带没带抹标记，不带就在构建阶段拦住 |
 *
 * ### 归属：**仅蓝厂方案**
 *
 * 抹标记只挂在 [BaselineScheme.VIVO] 上，通用方案不带、也不检查
 * （见 [VrKoPayloadGate]）。曾经试过把它做成"按运行时检测生效的横切能力"
 * （探到 vr.ko 就挂，与方案无关）—— 那是错的：`vr.ko` 是 vivo 的私有反 root 模块，
 * 把它横切到所有方案等于让别的厂商也走一条没验证过的路径。
 *
 * ### 机制（源码级，不是字符串反推）
 *
 * `vr.ko` 会在 `fork`/`clone` 时给每个来自 app 的 task 打两个标记字节，
 * 并在 `thread_info.flags` 里置 `VR_SYSCALL_TP_FLAG(0x400)`；之后该 task 一旦持有
 * `euid 0`，**`sys_exit` tracepoint 探针就会把它杀掉**。
 * 所以提权后、在跑提权校验（读子进程 `getuid()`）**之前**，必须把标记抹掉。
 *
 * ### ⚠️ 两条仍然成立的注意事项
 *
 * 1. **tag A 与 tag B 必须同时清零** —— `vr` 把"一个清一个没清"本身当作 tamper 证据，
 *    单独清一个**会被杀**。
 * 2. **`/proc/modules` 读不到就假设已加载**（[needsBypass] 传 `null`）。
 *    SELinux enforcing 下 `untrusted_app` 读不到它是常态；把它"优化"成读不到就跳过，
 *    会变成**漏抹标记 → 子进程被杀**。
 *
 * ### 来源与可信度
 *
 * 机制来自上游**源码** `YuKongA/ghostlock-app` 的 `src/core/main.c`，以及
 * `boxiaolanya2008/CVE-2026-43499-Neo11Plus` 的
 * `exploit/src/targets/PD2520-BP2A.250605.031.A3/root.c`（C 侧的正确实现）。
 * **没有任何真机验证** —— 以上全部是源码阅读 + 二进制审计的结论。
 */
object VrKoBypass {

    /** tag A 的字节偏移。上游硬编码，**不在 `offsets.json` 里**，patch 改不了。 */
    const val VR_TAG_A_OFF: Long = 0x06

    /** tag B 的字节偏移。同上，未对齐到 8。 */
    const val VR_TAG_B_OFF: Long = 0x2c

    /** syscall tracepoint 标志位；抹标记时先把它从 `thread_info.flags` 里清掉。 */
    const val VR_SYSCALL_TP_FLAG: Long = 0x400L

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

    /** 蓝厂的身份串。机型名 / 厂商名里出现任意一个即认为"像是蓝厂机器"。 */
    private val VIVO_IDENTITY_MARKERS = listOf("vivo", "iqoo")

    /**
     * 身份串是否像蓝厂机器（vivo / iQOO）。
     *
     * 只用于**提示**，不参与任何安全判定 —— 认错最多是多说一句，不会少做一步。
     */
    fun looksLikeVivoDevice(identityText: String?): Boolean =
        identityText != null &&
            VIVO_IDENTITY_MARKERS.any { identityText.contains(it, ignoreCase = true) }

    /**
     * 要不要在界面上提示"这台机器有 `vr.ko`，建议改选蓝厂方案"。
     *
     * ### 方向与 [needsBypass] **正好相反**
     *
     * [needsBypass] 是**安全判定**：读不到 `/proc/modules` 时必须保守当作"已加载"，
     * 否则会漏抹标记。
     *
     * 这里是**提示**：读不到 `/proc/modules` 时**不能**当作"有 vr.ko" ——
     * 那会让每一台非蓝厂机器（SELinux 下同样读不到）都看到这条提示，
     * 提示就变成了噪音，用户会连真该看的那一次一起忽略掉。
     * 所以只有**正面证据**才算数：`/proc/modules` 里确实有 `vr` 行，
     * 或机型 / 厂商身份串本身就是蓝厂。
     *
     * 已经选了蓝厂方案还提示"建议改选蓝厂方案"同样是噪音，所以那种情况返回 false。
     *
     * @param modulesText `/proc/modules` 的内容；`null` = 读不到。
     * @param deviceIdentityText 机型身份串（厂商 / 型号 / device / product 拼起来，小写）。
     */
    fun shouldSuggestVivoScheme(
        modulesText: String?,
        deviceIdentityText: String?,
        currentScheme: BaselineScheme,
    ): Boolean {
        if (currentScheme == BaselineScheme.VIVO) return false
        val vrModuleSeen = modulesText != null &&
            modulesText.lineSequence().any(::looksLikeVrModule)
        return vrModuleSeen || looksLikeVivoDevice(deviceIdentityText)
    }
}
