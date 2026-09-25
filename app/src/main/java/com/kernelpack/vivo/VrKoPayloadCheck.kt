package com.kernelpack.vivo

import com.kernelpack.elf.Elf64File
import com.kernelpack.patch.AArch64

/**
 * **只读**检查：给定一份 `.so` 的字节，判断它是否**真的带上了 vivo `vr.ko` 反 root 绕过**。
 *
 * ### 为什么需要它（这一层才是这件事的正确落点）
 *
 * `vr.ko` 的抹标记是**往内核内存写**，只有载荷（C 代码）里那套
 * `pipe_phys_write_data` / `pipe_write64` 原语做得到。Kotlin 侧没有这个原语，
 * 所以"把 [VrKoBypass] 规划出来的地址接到提权流程上"是一个**做不了的假动作** ——
 * 见 [VrKoBypass] 的类注释。
 *
 * Kotlin 侧能做、且**应该**做的是另一件事：**证明这份载荷带上了绕过**。
 * 本文件就是那个证明，它只读字节、不写任何东西。
 *
 * ### 判据（两条，任一成立即判"有"）
 *
 * 1. **日志串**：`"vr detag"` —— `patch_task_vr_tag()` 成功/失败时打的
 *    `root vr detag ok=…` / `root vr detag incomplete …` 里共有的那段。
 *    它只可能由那个函数产生，且落在 `.rodata` 里，`strip` 也带不走。
 * 2. **机器码特征**：同一段函数体里同时出现
 *    - `add xN, xM, #0x2c` —— 算 `task + VR_TAG_B_OFF`（tag B 的地址）
 *    - `AND` 立即数，位掩码 == `~0x400` —— 清 `VR_SYSCALL_TP_FLAG`（先把这个 task
 *      从 `sys_exit` 慢路径上摘下来，再去动那两个会被 tamper 检查的标记字节）
 *
 * ### ⚠️ 对任务书里那条建议的实测更正
 *
 * 任务书建议"扫 `movz/movk` 里的 `0x2c` 与 `0x400`"。**实测不是这样**：
 * clang 把 `task + 0x2c` 折成 `add` 立即数，把 `& ~0x400` 折成 `AND` 逻辑立即数，
 * 两者都**不是** `movz/movk`。照着 movz 扫会**全量漏判**（连有绕过的 36 份也一个都扫不出来）。
 * 所以这里扫的是实测出来的真实形态，并为此在 [AArch64] 里补了两个解码器。
 *
 * ### 判据的实测区分度（本仓库 `jniLibs/arm64-v8a` 全量 126 份 `.so`）
 *
 * | | 份数 | 日志串 | `add #0x2c` 且 `AND ~0x400` |
 * |---|---|---|---|
 * | 有绕过 | 36 | 36 / 36 | 36 / 36 |
 * | 没有 | 89 | 0 / 89 | **0 / 89** |
 *
 * 也就是说两条判据在真实语料上**完全一致**、且零假阳性。
 * 另 1 份（`libksu_other_androidcve202643499_any_ccc0.so`，8492 B）ELF 头损坏，
 * 解析不了 → 判 [Status.UNPARSEABLE]（见下）。
 *
 * ### 为什么还要三态
 *
 * "解析不了"和"确认没有"是两件事：前者可能是文件损坏/截断，后者是判据跑完了、
 * 明确没有。把它们混成 `false` 会让闸门给出**错误的原因**。闸门对这两态都拦
 * （宁可拦住，也不要在蓝厂机型上装一份会被 `sys_exit` 探针杀掉的载荷），
 * 但文案不同。
 */
object VrKoPayloadCheck {

    /**
     * `patch_task_vr_tag()` 打的两条日志里共有的那段。
     *
     * ```
     * root vr detag ok=%d tag_a=%u->%u tag_b=%u->%u flags=%016llx->%016llx
     * root vr detag incomplete task=%016llx (continuing)
     * ```
     */
    const val DETAG_LOG_MARKER = "vr detag"

    /** 判定结果。 */
    enum class Status {
        /** 判据成立 —— 这份载荷带 `vr.ko` per-task 抹标记。 */
        PRESENT,

        /** 判据跑完了，两条都不成立 —— 这份载荷**没有**带。 */
        ABSENT,

        /** 连 ELF 都解析不了（截断 / 头损坏 / 不是 ELF），**无法确认**。 */
        UNPARSEABLE,
    }

    /**
     * 判定用到的原始证据。
     *
     * 全部**如实暴露**，不折叠成一个布尔：闸门拦下时要能把"为什么"讲清楚，
     * 事后复核的人也要能自己重算一遍。
     */
    data class Evidence(
        /** `.rodata` 里有没有 [DETAG_LOG_MARKER]。 */
        val detagLogMarker: Boolean,
        /** `add xN, xM, #0x6`（tag A 地址）出现次数 —— **仅作旁证**，单靠它不足以判定。 */
        val tagAAddSites: Int,
        /** `add xN, xM, #0x2c`（tag B 地址）出现次数。 */
        val tagBAddSites: Int,
        /** `AND` 64 位立即数 == `~0x400`（清 `VR_SYSCALL_TP_FLAG`）出现次数。 */
        val syscallTpFlagClearSites: Int,
        /** ELF 是否解析成功。 */
        val elfParsed: Boolean,
        /** 扫过多少个可执行节的字节（0 表示没扫到东西）。 */
        val executableBytes: Int,
    ) {
        /**
         * 机器码特征是否成立。
         *
         * **两个都要**：`add #0x2c` 单独出现是常见现象（实测 6 份**没有**绕过的载荷
         * 里也有 `add #0x2c`），而"清 `0x400` 这一位"才是 `vr.ko` 那一段的指纹。
         */
        val codeSignature: Boolean get() = tagBAddSites > 0 && syscallTpFlagClearSites > 0

        /** 两条判据是否都成立（都成立时可信度最高）。 */
        val bothSignals: Boolean get() = detagLogMarker && codeSignature
    }

    data class Result(val status: Status, val evidence: Evidence) {
        val hasBypass: Boolean get() = status == Status.PRESENT
    }

    /** 一次判定。**纯函数**：不读文件、不碰 Android。 */
    fun check(bytes: ByteArray): Result {
        val detagLog = indexOf(bytes, DETAG_LOG_MARKER.toByteArray(Charsets.US_ASCII)) >= 0

        val elf = runCatching { Elf64File(bytes) }.getOrNull()
        var tagA = 0
        var tagB = 0
        var tpFlag = 0
        var scanned = 0
        if (elf != null) {
            for (section in elf.executableSections()) {
                val from = section.offset.toInt()
                val to = section.endOffset().toInt()
                if (from < 0 || to > bytes.size || to <= from) continue
                scanned += to - from
                val sites = scan(bytes, from, to)
                tagA += sites[0]
                tagB += sites[1]
                tpFlag += sites[2]
            }
        }

        val evidence = Evidence(
            detagLogMarker = detagLog,
            tagAAddSites = tagA,
            tagBAddSites = tagB,
            syscallTpFlagClearSites = tpFlag,
            elfParsed = elf != null,
            executableBytes = scanned,
        )
        val status = when {
            detagLog || evidence.codeSignature -> Status.PRESENT
            elf == null || scanned == 0 -> Status.UNPARSEABLE
            else -> Status.ABSENT
        }
        return Result(status, evidence)
    }

    /**
     * 扫一个可执行节，返回 `[tag A 次数, tag B 次数, 清 0x400 次数]`。
     *
     * 只做**单条指令**匹配，不做数据流跟踪：这里要的是"这段代码在不在"，
     * 不是"这个常量最终进了哪个寄存器"。偏移量是编译期常量、clang 直接折进
     * `add`/`AND` 的立即数字段，所以单条匹配就够 —— 而且是**实测过的**够。
     */
    private fun scan(bytes: ByteArray, from: Int, to: Int): IntArray {
        val out = IntArray(3)
        val tpFlagMask = VrKoBypass.VR_SYSCALL_TP_FLAG.inv() // ~0x400
        var i = from
        while (i + 4 <= to) {
            val insn = (bytes[i].toInt() and 0xff) or
                ((bytes[i + 1].toInt() and 0xff) shl 8) or
                ((bytes[i + 2].toInt() and 0xff) shl 16) or
                ((bytes[i + 3].toInt() and 0xff) shl 24)
            i += 4

            val add = AArch64.decodeAddImmediate(insn)
            if (add != null && add.sf && !add.isSub) {
                when (add.imm.toLong()) {
                    VrKoBypass.VR_TAG_A_OFF -> out[0]++
                    VrKoBypass.VR_TAG_B_OFF -> out[1]++
                }
                continue
            }
            val and = AArch64.decodeLogicalImmediate(insn)
            if (and != null && and.sf && and.opc == 0 && and.bitmask == tpFlagMask) {
                out[2]++
            }
        }
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (k in needle.indices) {
                if (haystack[i + k] != needle[k]) continue@outer
            }
            return i
        }
        return -1
    }
}
