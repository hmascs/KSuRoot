package com.kernelpack.patch

/**
 * 常量materialization扫描器：在一段机器码里找出"哪几条指令合起来把某个常量写进了寄存器"。
 *
 * 核心是**按寄存器的数据流跟踪**（而不是连续指令窗口匹配），因为 clang -O2 会把
 * 不同寄存器的 movz/movk 链交错排放。模型：
 *
 *  - 维护 32 个通用寄存器的"当前已知常量"与"构造它的指令序列"；
 *  - 遇到 `movz`/`movn` → 该寄存器获得新值，链重置为这一条；
 *  - 遇到 `movk` → 若该寄存器已有已知值，则替换对应 16 位半字，链追加这一条；
 *  - 遇到任何**写该寄存器**的其它指令 → 该寄存器状态失效；
 *  - 遇到分支/调用/系统调用/无法识别的编码 → 清空全部寄存器状态（保守，宁可少找不可找错）。
 */
class ConstantScanner(private val text: ByteArray) {

    /** 一个常量构造点。 */
    class Site(
        /** 构成该常量的指令下标（相对 [text] 的 4 字节字下标），按执行顺序。 */
        val chain: IntArray,
        /** 承载常量的寄存器号。 */
        val register: Int,
        /** 64 位（X）还是 32 位（W）形式。 */
        val sf: Boolean,
        /** 链首指令的 opc：2=MOVZ，0=MOVN。 */
        val firstOpc: Int,
        /** 链中各指令的 16 位半字位置。 */
        val hwSlots: IntArray,
    ) {
        /** 该构造点覆盖的最后一条指令下标。 */
        val endIndex: Int get() = chain[chain.size - 1]
        /** 涉及的全部 16 位半字位置。 */
        val coveredHw: Set<Int> get() = hwSlots.toSet()
    }

    private val count = text.size / 4

    private fun insn(i: Int): Int =
        (text[i * 4].toInt() and 0xff) or
            ((text[i * 4 + 1].toInt() and 0xff) shl 8) or
            ((text[i * 4 + 2].toInt() and 0xff) shl 16) or
            ((text[i * 4 + 3].toInt() and 0xff) shl 24)

    /**
     * 扫描一次，收集**所有**目标常量的构造点。
     *
     * @param targets 关心的常量值（Long 位模式）
     * @return 常量值 → 构造点列表
     */
    fun scan(targets: Set<Long>): Map<Long, List<Site>> {
        val out = HashMap<Long, MutableList<Site>>(targets.size * 2)
        val value = LongArray(32)
        val chain = arrayOfNulls<IntArray>(32)
        val sfOf = BooleanArray(32)
        val firstOpcOf = IntArray(32)
        val hwOf = arrayOfNulls<IntArray>(32)
        val known = BooleanArray(32)

        for (k in 0 until count) {
            val insn = insn(k)
            val mv = AArch64.decodeMoveWide(insn)

            if (mv != null) {
                val rd = mv.rd
                when (mv.opc) {
                    2 -> { // MOVZ：整体清零后写入一个半字
                        value[rd] = if (mv.sf) (mv.imm16.toLong() shl (mv.hw * 16))
                        else ((mv.imm16.toLong() shl (mv.hw * 16)) and 0xffffffffL)
                        chain[rd] = intArrayOf(k)
                        hwOf[rd] = intArrayOf(mv.hw)
                        sfOf[rd] = mv.sf
                        firstOpcOf[rd] = 2
                        known[rd] = true
                    }
                    0 -> { // MOVN：reg = ~(imm16 << (hw*16))
                        // 即：目标半字 = ~imm16，其余位全 1（低于该半字的位也是 1）。
                        val shift = mv.hw * 16
                        val lower = if (shift == 0) 0L else (1L shl shift) - 1L
                        val upper = if (shift + 16 >= 64) 0L else (-1L shl (shift + 16))
                        var v = ((mv.imm16.toLong().inv() and 0xffff) shl shift) or lower or upper
                        if (!mv.sf) v = v and 0xffffffffL
                        value[rd] = v
                        chain[rd] = intArrayOf(k)
                        hwOf[rd] = intArrayOf(mv.hw)
                        sfOf[rd] = mv.sf
                        firstOpcOf[rd] = 0
                        known[rd] = true
                    }
                    else -> { // MOVK：只有寄存器已有已知值时才成立
                        if (!known[rd]) continue
                        val mask = 0xffffL shl (mv.hw * 16)
                        var v = (value[rd] and mask.inv()) or (mv.imm16.toLong() shl (mv.hw * 16))
                        if (!mv.sf) v = v and 0xffffffffL
                        value[rd] = v
                        chain[rd] = chain[rd]!! + k
                        hwOf[rd] = hwOf[rd]!! + mv.hw
                    }
                }

                if (known[rd]) {
                    val v = value[rd]
                    if (targets.contains(v)) {
                        out.getOrPut(v) { ArrayList(2) }.add(
                            Site(
                                chain = chain[rd]!!.copyOf(),
                                register = rd,
                                sf = sfOf[rd],
                                firstOpc = firstOpcOf[rd],
                                hwSlots = hwOf[rd]!!.copyOf(),
                            )
                        )
                    }
                }
                continue
            }

            val written = AArch64.writtenRegisters(insn)
            if (written == null) {
                for (r in 0 until 32) known[r] = false
            } else {
                for (r in written) known[r] = false
            }
        }
        return out
    }

    /** 按旧值扫描一次，仅用于校验剩余量。 */
    fun countOccurrences(value: Long): Int = scan(setOf(value))[value]?.size ?: 0

    companion object {
        /**
         * 用 [newValue] 重写一个构造点，保留原有指令条数、寄存器、半字布局。
         *
         * 规则：链首的 MOVZ/MOVN 决定"未被覆盖的半字"的隐含值（MOVZ→0，MOVN→0xffff）；
         * 若新值在某个**链上没有对应指令**的半字上位与隐含值不符，则这条链无法表达新值，
         * 返回 null（调用方会把它计入失败并如实上报，而不是猜着改）。
         */
        @JvmStatic
        fun rewrite(site: Site, newValue: Long): IntArray? {
            val maxChunk = if (site.sf) 3 else 1
            val implicit = if (site.firstOpc == 0) 0xffffL else 0L

            // 逐半字求值：链上有的用链，链上没有的必须等于隐含值
            val byHw = HashMap<Int, Long>(site.hwSlots.size)
            for (i in site.hwSlots.indices) {
                val c = site.hwSlots[i]
                byHw[c] = (newValue ushr (16 * c)) and 0xffff
            }
            for (c in 0..maxChunk) {
                if (!byHw.containsKey(c)) {
                    val actual = (newValue ushr (16 * c)) and 0xffff
                    if (actual != implicit) return null
                }
            }

            val out = IntArray(site.chain.size)
            for (i in site.chain.indices) {
                val hw = site.hwSlots[i]
                val chunk = byHw[hw] ?: implicit
                out[i] = if (i == 0) {
                    when (site.firstOpc) {
                        0 -> AArch64.encodeMoveWide(0, site.sf, hw, (chunk.inv() and 0xffff).toInt(), site.register)
                        else -> AArch64.encodeMovz(site.sf, hw, chunk.toInt() and 0xffff, site.register)
                    }
                } else {
                    AArch64.encodeMovk(site.sf, hw, chunk.toInt() and 0xffff, site.register)
                }
            }
            return out
        }
    }
}
