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
 * 机制来自三处，可信度**不一样**，分开说：
 * - **厂商固件里 `vr.ko` 的真实反汇编** —— 三族各一台真机的 `lib/modules/vr.ko`，
 *   逐条对照（见 [VR_TAG_A_OFF] 的表）。**这一部分是实测的**；
 * - 上游**源码** `YuKongA/ghostlock-app` 的 `src/core/main.c`，以及
 *   `boxiaolanya2008/CVE-2026-43499-Neo11Plus` 的
 *   `exploit/src/targets/PD2520-BP2A.250605.031.A3/root.c`（C 侧的正确实现）；
 * - **然而"抹标记"这一步没有在任何真机上跑过。** 载荷里那段 `patch_task_vr_tag()`
 *   只做过编译期核对与二进制审计（`vr detag` 计数、`add #0x2c` + `AND ~0x400` 特征）。
 *   也就是说：**写什么值是实测的，写下去会发生什么还没实测。**
 */
object VrKoBypass {

    /**
     * tag A 的字节偏移 —— **按内核族不同**。上游硬编码，**不在 `offsets.json` 里**，patch 改不了。
     *
     * ## 三族已逐台实测（2026-10）
     *
     * 这两个偏移是 `vr.ko` 往 `task_struct` 里打的**标记字节**位置。它们被
     * **编译期烤进基线 `.so`**（上游没把它们放进 `offsets.json`，打补丁阶段改不了），
     * 所以"哪一族用哪一组偏移"是一个**必须逐族确认**的前提，而不是可以顺带忽略的细节。
     *
     * 下表来自**三台真机厂商固件**里 `lib/modules/vr.ko` 的反汇编对比
     * （样本、哈希与逐条指令对照见 `03-内核镜像/vrko-三族实测对比.md`）：
     *
     * | 内核族 | tag A | tag B | 样本 |
     * |---|---|---|---|
     * | **6.1** | **`0x04`** | `0x2c` | vivo X100 Pro (PD2324) · 6.1.145 |
     * | **6.6** | **`0x06`** | `0x2c` | 本机 PD2463 · 6.6.89 |
     * | **6.12** | **`0x06`** | `0x2c` | iQOO 15 (PD2505) · 6.12.58 |
     *
     * 三族的打标记函数**严格同构**（同一副反编译骨架），差异只在这些立即数上。
     * 所以这不是"按版本号外推"，是逐台读出来的。
     *
     * 要点：
     * - **只有 6.1 是 `0x04`**，6.6 与 6.12 都是 `0x06` —— 6.1 与 6.6 只差半代，
     *   tag A 就挪了 2 个字节。这里**不能用一个常量糊三族**。
     * - **tag B 三族一致**，都是 `0x2c`。
     * - `VR_SYSCALL_TP_FLAG (0x400)` 三族一致，都用 `stset` 置位。
     * - 6.12 的次级 task 字段检查用 `tbz #2`，6.1 / 6.6 用 `tbz #1` —— 与 tag A 无关，
     *   但说明"三族同构"是同构在**结构**上，不是同构在每一个立即数上。
     *
     * ## 早先的判断（留档）
     *
     * 在拿到真机固件之前，这里写的是「跨内核版本**未经证实**，当前状态是**假定相同、
     * 且从未在多版本上验证**」，依据是当时语料里 4 份 target.h 清一色 `0x06 / 0x2c`，
     * 且全语料没有一条"在两个不同内核版本上读过标记字节并比对"的记录。
     *
     * **那个判断在当时是对的**（反面证据也列全了：tag B 落在 `+0x2c` 已超出
     * `thread_info`，落在 vivo 追加字段区，稳定性没有论证）。
     * 固件到手后按同一条办法 —— 每族读一次 —— 把前提证伪了一半：**6.1 果然不同**。
     * 于是改成逐族常量。这条留档是为了记住：**"假定相同"当时是被明确标出来的，
     * 而不是被当成事实用过去。**
     */
    const val VR_TAG_A_OFF: Long = 0x06

    /** 6.1 族的 tag A 偏移（实测 X100 Pro 6.1.145）。**与 6.6 / 6.12 不同**，见 [VR_TAG_A_OFF]。 */
    const val VR_TAG_A_OFF_6_1: Long = 0x04

    /** tag B 的字节偏移。三族实测一致（`0x2c`）；未对齐到 8 —— 写的时候要向下对齐到 `0x28`。 */
    const val VR_TAG_B_OFF: Long = 0x2c

    /** 已知的 tag A 偏移全集（三族实测值）。用于扫描旁证时不漏族。 */
    val VR_TAG_A_OFFS: List<Long> = listOf(VR_TAG_A_OFF_6_1, VR_TAG_A_OFF)

    /**
     * 按内核**大系列**取 tag A 偏移。
     *
     * 只比较 `major.minor` 两段：传进来的既可能是大系列（`"6.1"`），
     * 也可能是完整版本（`"6.1.145"`）—— 两者都要认。
     *
     * @return `6.1` → `0x04`；`6.6` / `6.12` → `0x06`；**认不出返回 `null`，不猜**。
     */
    fun tagAOffForSeries(series: String?): Long? {
        val s = series?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (s.split('.').take(2).joinToString(".")) {
            "6.1" -> VR_TAG_A_OFF_6_1
            "6.6", "6.12" -> VR_TAG_A_OFF
            else -> null
        }
    }

    /**
     * 从内核 release 串取 tag A 偏移，如 `6.1.145-android14-11-g…` → `0x04`。
     *
     * 取 `-` 之前那一整段（`6.1.145`）再压成 `6.1`。
     * **不是**取第一个点分段 —— 那样会得到 `6.1.145`，一个族都认不出。
     *
     * @return 认不出族时返回 `null`（**不回落成一个默认值** —— 回落就等于猜）。
     */
    fun tagAOffForRelease(release: String?): Long? =
        tagAOffForSeries(release?.substringBefore('-'))

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
