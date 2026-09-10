package com.kernelpack.patch

/**
 * AArch64 指令层的**最小必要**实现：只覆盖"把 64 位/32 位常量塞进寄存器"这件事。
 *
 * 为什么需要它：`preload.so` 里所有内核偏移都是 `#define` 出来的**编译期常量**，
 * clang -O2 会把它们折成立即数，用 `movz/movk/movn` 序列写进寄存器。
 * 想在设备上换一份偏移，就必须精确地找到这些序列并重写 —— 这正是本文件做的事。
 *
 * 一个重要事实（实测）：clang 会把**不同寄存器**的 movz/movk 链**交错**排放来隐藏延迟，
 * 例如
 * ```
 *   movz w16, #0xe280          ; INIT_TASK 低 16 位
 *   cmp  w31, #0
 *   movz w15, #0x5600          ; ROOT_TASK_GROUP 低 16 位
 *   movk w16, #0x210, lsl #16  ; INIT_TASK 高 16 位 -> 0x210e280
 *   movk w15, #0x230, lsl #16  ; ROOT_TASK_GROUP   -> 0x2305600
 * ```
 * 所以**不能**用"连续几条指令"去匹配，必须按寄存器做数据流跟踪。
 */
object AArch64 {

    const val REG_SP = 31

    /** 一条 `movz/movk/movn` 的解码结果。 */
    data class MoveWide(
        /** 0 = MOVN，2 = MOVZ，3 = MOVK。 */
        val opc: Int,
        /** 16 位半字位置 0..3。 */
        val hw: Int,
        val imm16: Int,
        val rd: Int,
        /** true = 64 位形式（X 寄存器）。 */
        val sf: Boolean,
    )

    /** 解码 `movz/movk/movn`，不是这类指令返回 null。 */
    @JvmStatic
    fun decodeMoveWide(insn: Int): MoveWide? {
        if (((insn ushr 23) and 0x3f) != 0b100101) return null
        val opc = (insn ushr 29) and 0x3
        if (opc == 1) return null // 未分配
        val sf = ((insn ushr 31) and 1) == 1
        val hw = (insn ushr 21) and 0x3
        if (!sf && hw > 1) return null
        return MoveWide(opc, hw, (insn ushr 5) and 0xffff, insn and 0x1f, sf)
    }

    /** 编码 `movz/movk/movn`。 */
    @JvmStatic
    fun encodeMoveWide(opc: Int, sf: Boolean, hw: Int, imm16: Int, rd: Int): Int =
        ((if (sf) 1 else 0) shl 31) or
            (opc shl 29) or
            (0b100101 shl 23) or
            (hw shl 21) or
            ((imm16 and 0xffff) shl 5) or
            (rd and 0x1f)

    @JvmStatic
    fun encodeMovz(sf: Boolean, hw: Int, imm16: Int, rd: Int) = encodeMoveWide(2, sf, hw, imm16, rd)

    @JvmStatic
    fun encodeMovk(sf: Boolean, hw: Int, imm16: Int, rd: Int) = encodeMoveWide(3, sf, hw, imm16, rd)

    // -------------------------------------------------------- 寄存器写分析

    /** 指令是否改变控制流（分支/调用/返回/系统调用）。 */
    @JvmStatic
    fun isControlFlow(insn: Int): Boolean = when ((insn ushr 25) and 0xf) {
        0b1010, 0b1011 -> true
        else -> false
    }

    /**
     * 该指令写入的通用寄存器集合；返回 `null` 表示"无法判定，按最坏情况清空全部"。
     *
     * 判据是 AArch64 顶层指令分组（bit 28..25）：
     * ```
     *  op0  bits28..25   含义
     *  000  x000/x001    保留（SME 等）
     *  001  x010/x011    保留
     *  010  x100/x101    载入/存储（非 scaled/register offset）
     *  011  x110/x111    载入/存储（unsigned imm） / SIMD&FP
     *  100  x000/x001    数据处理（立即数）—— 含 movz/movk/movn/adr/add/sub
     *  101  x010/x011    分支/异常/系统
     *  110  x100/x101    载入/存储 / 数据处理（寄存器）
     *  111  x110/x111    载入/存储 / SIMD&FP
     * ```
     * 保守策略：**宁可多清空**（最多漏掉一些可 patch 点，由校验阶段兜底），
     * 也**绝不漏清**（漏清会把不该改的指令改掉，直接搞坏 .so）。
     */
    @JvmStatic
    fun writtenRegisters(insn: Int): IntArray? {
        val g = (insn ushr 25) and 0xf
        when (g) {
            0b0000, 0b0001, 0b0010, 0b0011 -> return null // 保留编码：清空全部
            0b0111, 0b1111 -> return EMPTY              // SIMD&FP：不写通用寄存器
            0b0101, 0b1101 -> {                          // 数据处理（寄存器）
                val rd = insn and 0x1f
                return if (rd == REG_SP) EMPTY else intArrayOf(rd)
            }
            0b1000, 0b1001 -> {                          // 数据处理（立即数）
                val rd = insn and 0x1f
                return if (rd == REG_SP) EMPTY else intArrayOf(rd)
            }
            0b1010, 0b1011 -> {                          // 分支/异常/系统
                // MRS <sysreg>, Xt 会把系统寄存器读进通用寄存器
                if (((insn ushr 20) and 0xfff) == 0xD53) {
                    val rt = insn and 0x1f
                    return if (rt == REG_SP) EMPTY else intArrayOf(rt)
                }
                return null // 分支/调用/SVC/屏障：一律清空全部
            }
            0b0100, 0b0110, 0b1100, 0b1110 -> {          // 载入/存储
                val out = ArrayList<Int>(3)
                val load = ((insn ushr 22) and 1) == 1
                val rt = insn and 0x1f
                val rt2 = (insn ushr 10) and 0x1f
                val rs = (insn ushr 16) and 0x1f
                if (load) {
                    if (rt != REG_SP) out.add(rt)
                    if (g == 0b1100 && rt2 != REG_SP) out.add(rt2) // LDP/LDPSW 写两个寄存器
                } else if (g == 0b1110 && rt == REG_SP && rs != REG_SP) {
                    out.add(rs)                                    // STXR 把状态写到 Rs
                }
                return out.toIntArray()
            }
        }
        return null
    }

    private val EMPTY = IntArray(0)
}
