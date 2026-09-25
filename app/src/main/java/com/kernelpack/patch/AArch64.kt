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

    // ─────────────────────────────── 立即数解码（只读特征扫描用）

    /** 一条 `ADD/SUB (immediate)` 的解码结果。 */
    data class AddImmediate(
        /** true = 64 位（X）形式。 */
        val sf: Boolean,
        /** 立即数是否左移 12 位。 */
        val shift12: Boolean,
        /** **已经算上移位**的立即数。 */
        val imm: Int,
        val rn: Int,
        val rd: Int,
        val isSub: Boolean,
    )

    /**
     * 解码 `ADD/SUB (immediate)`；不是这类指令返回 null。
     *
     * ### 为什么需要它
     *
     * `task + VR_TAG_B_OFF` 这种"基址 + 小偏移"clang 会折成 `add xN, xM, #0x2c`，
     * **不是** movz/movk —— 只扫 movz/movk 会把这类点全部漏掉（实测：6.1 族基线里
     * `add #0x2c` 有 3 处，`movz #0x2c` 一处都没有）。
     *
     * 编码：`sf op S 100010 sh imm12 Rn Rd`（bit 28..23 = 100010，bit 30 = S）。
     */
    @JvmStatic
    fun decodeAddImmediate(insn: Int): AddImmediate? {
        if (((insn ushr 23) and 0x3f) != 0b100010) return null
        if (((insn ushr 29) and 0x1) != 0) return null // bit29 必须为 0（bit30 是 S）
        val sf = ((insn ushr 31) and 1) == 1
        val shift12 = ((insn ushr 22) and 1) == 1
        val raw = (insn ushr 10) and 0xfff
        return AddImmediate(
            sf = sf,
            shift12 = shift12,
            imm = if (shift12) raw shl 12 else raw,
            rn = (insn ushr 5) and 0x1f,
            rd = insn and 0x1f,
            isSub = ((insn ushr 30) and 1) == 1,
        )
    }

    /** 一条逻辑立即数指令（`AND/ORR/EOR/ANDS` 立即数形式）的解码结果。 */
    data class LogicalImmediate(
        /** 0 = AND，1 = ORR，2 = EOR，3 = ANDS。 */
        val opc: Int,
        val sf: Boolean,
        /**
         * 解码出来的位掩码，即指令里那个"立即数"本体。
         *
         * `and x2, x0, #0xfffffffffffffbff`（清掉 `0x400` 这一位）在这里是
         * [bitmask] = `0xfffffffffffffbff`。AArch64 **没有** `BIC (immediate)`，
         * 所以"清某一位"一定长成 `AND` + 取反后的掩码。
         */
        val bitmask: Long,
        val rn: Int,
        val rd: Int,
    )

    /**
     * 解码 `AND/ORR/EOR/ANDS (immediate)`；不是这类指令、或掩码编码非法时返回 null。
     *
     * 编码：`sf opc 100100 N immr imms Rn Rd`（bit 28..23 = 100100）。
     */
    @JvmStatic
    fun decodeLogicalImmediate(insn: Int): LogicalImmediate? {
        if (((insn ushr 23) and 0x3f) != 0b100100) return null
        val sf = ((insn ushr 31) and 1) == 1
        val width = if (sf) 64 else 32
        val mask = decodeBitMasks(
            n = (insn ushr 22) and 1,
            immr = (insn ushr 16) and 0x3f,
            imms = (insn ushr 10) and 0x3f,
            width = width,
        ) ?: return null
        return LogicalImmediate(
            opc = (insn ushr 29) and 0x3,
            sf = sf,
            bitmask = mask,
            rn = (insn ushr 5) and 0x1f,
            rd = insn and 0x1f,
        )
    }

    /**
     * AArch64 `DecodeBitMasks(N, imms, immr)`：把三个字段还原成那个重复位模式。
     *
     * 位模式**不是**任意 64 位数 —— 必须是"2^len 位的元素循环重复"。所以
     * `0xfffffffffffffbff`（除 bit 10 外全 1）看着不像合法掩码，实际是
     * `len = 6` 时元素 `0b111`（3 位）右旋 58 位的重复结果。手算极易出错，
     * 必须照伪码实现 —— 本实现已用 `llvm-objdump` 实际打出的
     * `and x2, x0, #0xfffffffffffffbff` / `#0xfffffffffffff7ff` 两条指令逐条比对过。
     *
     * @return 位模式；字段非法（`len < 1` 或元素宽度超过寄存器）时返回 null。
     */
    @JvmStatic
    fun decodeBitMasks(n: Int, immr: Int, imms: Int, width: Int): Long? {
        val bits = (n shl 6) or (imms.inv() and 0x3f)
        if (bits == 0) return null
        val len = 31 - Integer.numberOfLeadingZeros(bits)
        if (len < 1) return null
        val esize = 1 shl len
        if (esize > width) return null
        val s = imms and (esize - 1)
        val r = (immr and (esize - 1)) % esize
        // ⚠️ JVM 的 `shl` 把移位数按低 6 位取模：`1L shl 64` == 1L，不是 2^64。
        //    所以 esize = 64 时必须显式写 -1L，否则 full 变成 0，掩码被整个 and 成 0。
        val full = if (esize == 64) -1L else (1L shl esize) - 1
        var element = if (s == esize - 1) full else (1L shl (s + 1)) - 1
        if (r != 0) element = ((element ushr r) or (element shl (esize - r))) and full
        var mask = 0L
        var i = 0
        while (i < width) {
            mask = mask or (element shl i)
            i += esize
        }
        return if (width == 64) mask else mask and 0xffffffffL
    }
}
