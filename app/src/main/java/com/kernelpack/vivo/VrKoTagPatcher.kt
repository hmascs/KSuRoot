package com.kernelpack.vivo

/**
 * 把载荷里 `VR_TAG_A_OFF` 的**烤死值改成识别出来的值**。
 *
 * ### 为什么必须改，而不是"检查一下"
 *
 * 载荷里 tag A 的偏移是编译期常量，patch 之前是改不了的 —— 但**打补丁这件事我们本来就做**
 * （[com.kernelpack.patch.SharedObjectPatcher] 一直在改符号字面量）。这里改的是**另一类**立即数：
 * 不在 `movz/movk` 里，而在 `add` 里。
 *
 * ### 载荷里它长什么样（实测）
 *
 * `patch_task_vr_tag()` 编出来是这样的（`libbaseline_6_1.so` 为例）：
 *
 * ```
 * add  x1, x23, #0x4        ; ← tag A：6.1 是 4，6.12 是 6
 * add  x1, x23, #0x2c       ; ← tag B：恒 0x2c
 * ```
 *
 * 两个都走**同一个基址寄存器**（`x23` = task）、**同一个目标寄存器**（`x1`）。
 * 这给了我们一个**精确且独特**的定位方式：
 *
 * 1. 先找 `add x1, R, #0x2c` —— tag B 的值是三族实测恒定的，锚点很硬；
 * 2. 再看**同一个 R** 上的 `add x1, R, #imm`，`imm ∈ {4, 6}` —— 那些就是 tag A；
 * 3. 只改这些，别的 `add #4` 一概不碰。
 *
 * ### 为什么不直接全局替换 `add #4`
 *
 * 因为 `add #4` 在普通代码里满地都是。实测这两份载荷里，
 * **只有绑在 task 寄存器上的那几处**才是 tag 访问（6.1 有 5 处、6.12 有 3 处），
 * 换错任何一处都会改坏不相干的计算。所以锚点必须绑在寄存器上。
 */
object VrKoTagPatcher {

    /** tag B 的偏移，三族实测恒定；用它当锚点。 */
    const val TAG_B_OFF = 0x2cL

    /** 已知的 tag A 取值。 */
    val KNOWN_TAG_A = listOf(0x04L, 0x06L)

    data class Report(
        val oldTagA: Long,
        val newTagA: Long,
        /** 真正改掉的指令处数。 */
        val patchedSites: Int,
        /** 锚点（`add ..., #0x2c`）找到几处。 */
        val anchorSites: Int,
    ) {
        val changed: Boolean get() = oldTagA != newTagA
        val ok: Boolean get() = !changed || patchedSites > 0
    }

    class PatchException(message: String) : Exception(message)

    /**
     * 就地改写 [bytes]。
     *
     * @param wanted 识别出来的目标 tag A（例如从 `vendor_boot` 里读到的 `0x04`）。
     * @throws PatchException 锚点找不到、或发现了无法解释的第三种取值 —— 都**不猜**。
     */
    fun patch(bytes: ByteArray, wanted: Long): Report {
        require(wanted in KNOWN_TAG_A) { "tag A 只见过 ${KNOWN_TAG_A.map { "0x%02x".format(it) }}，收到 $wanted" }

        val sections = executableSections(bytes)
        if (sections.isEmpty()) throw PatchException("这份载荷里没有可执行节，不像是能用的 .so。")

        // ① 先扫一遍：找出锚点基址寄存器，以及候选 tag A 的取值
        val taskRegs = HashSet<Int>()
        val foundTagA = HashSet<Long>()
        var anchors = 0

        for ((from, to) in sections) {
            var i = from
            while (i + 4 <= to) {
                val insn = readU32(bytes, i)
                val add = decodeAddImm(insn)
                // 锚点认「目标寄存器 ≠ 基址寄存器」的那种（实测是 `add x1, x23, #0x2c`）。
                // `add x23, x23, #0x2c` 这种原地加不是 tag 访问，不能当锚点 ——
                // 但**也不能用 continue 跳过**：continue 会跳过循环末尾的 i += 4，
                // 直接变成死循环（这版初稿就是这么写的，被自检抓出来了）。
                if (add != null && add.imm == TAG_B_OFF && add.rn != add.rd) {
                    anchors++
                    taskRegs.add(add.rn)
                }
                i += 4
            }
        }
        if (anchors == 0) {
            throw PatchException(
                "这份载荷里找不到 tag B 的锚点（`add ... , #0x2c`）—— " +
                    "它可能不是带 vr.ko 抹标记的那一版，或者编译形态变了。",
            )
        }

        // ② 候选必须**紧挨着锚点**。
        //
        // 这一条是被实测逼出来的：初稿只要求"同一基址寄存器 + imm ∈ {4,6}"，
        // 结果 `libbaseline_6_12.so` 上同时扫出 `#4` 和 `#6` —— 因为 `x22` 这个寄存器
        // 在别处也被用来算过 `task+4`（那是**别的**字段，不是 tag A）。
        // 改成只在锚点附近找之后，取值就唯一了。
        //
        // 窗口取 ±64 字节（16 条指令）：实测 tag A 与 tag B 的访问相距 8 条指令以内，
        // 留一倍余量；再远就可能是无关代码。
        val window = 64
        for ((from, to) in sections) {
            var i = from
            while (i + 4 <= to) {
                val anchor = decodeAddImm(readU32(bytes, i))
                if (anchor != null && anchor.imm == TAG_B_OFF && anchor.rn != anchor.rd) {
                    var j = maxOf(from, i - window)
                    val jEnd = minOf(to, i + window)
                    while (j + 4 <= jEnd) {
                        val add = decodeAddImm(readU32(bytes, j))
                        if (add != null && add.rn == anchor.rn && add.imm in KNOWN_TAG_A) {
                            foundTagA.add(add.imm)
                        }
                        j += 4
                    }
                }
                i += 4
            }
        }
        if (foundTagA.isEmpty()) {
            throw PatchException(
                "找到了 tag B 锚点，但同一基址寄存器上没有任何 tag A 候选 —— 不敢改。",
            )
        }
        if (foundTagA.size > 1) {
            throw PatchException(
                "同一基址寄存器上出现了多种 tag A 取值（${foundTagA.map { "0x%02x".format(it) }}）—— " +
                    "这说明载荷本身就不自洽，不敢挑一个改。",
            )
        }

        val old = foundTagA.first()
        if (old == wanted) return Report(old, wanted, patchedSites = 0, anchorSites = anchors)

        // ② 改写：只动「同一基址寄存器 + 目标寄存器固定为 x1」的那些
        var patched = 0
        for ((from, to) in sections) {
            var i = from
            while (i + 4 <= to) {
                val anchor = decodeAddImm(readU32(bytes, i))
                if (anchor != null && anchor.imm == TAG_B_OFF && anchor.rn != anchor.rd) {
                    var j = maxOf(from, i - window)
                    val jEnd = minOf(to, i + window)
                    while (j + 4 <= jEnd) {
                        val add = decodeAddImm(readU32(bytes, j))
                        if (add != null && add.rn == anchor.rn && add.imm == old) {
                            writeU32(bytes, j, encodeAddImm(add.sf, add.rd, add.rn, wanted.toInt()))
                            patched++
                        }
                        j += 4
                    }
                }
                i += 4
            }
        }
        if (patched == 0) throw PatchException("定位到了 tag A = 0x%02x，但改写时一处都没落上。".format(old))
        return Report(old, wanted, patched, anchors)
    }

    // ───────────────────────── 指令层 ─────────────────────────

    private data class AddImm(val sf: Boolean, val rd: Int, val rn: Int, val imm: Long)

    /**
     * `ADD (immediate)` —— `sf | 0 0 100010 | sh | imm12 | Rn | Rd`。
     *
     * 只认 `sh = 0`（不左移 12 位）的形式：tag 偏移都是小数值，带移位的那种不可能是它。
     */
    private fun decodeAddImm(insn: Int): AddImm? {
        if ((insn and 0x7F000000) != 0x11000000) return null
        val sf = ((insn ushr 31) and 1) == 1
        if (((insn ushr 22) and 1) != 0) return null     // sh 必须为 0
        val imm = ((insn ushr 10) and 0xFFF).toLong()
        return AddImm(sf, insn and 0x1F, (insn ushr 5) and 0x1F, imm)
    }

    private fun encodeAddImm(sf: Boolean, rd: Int, rn: Int, imm: Int): Int =
        (if (sf) 0x91000000.toInt() else 0x11000000) or (imm shl 10) or (rn shl 5) or rd

    // ───────────────────────── ELF ─────────────────────────

    private fun executableSections(elf: ByteArray): List<Pair<Int, Int>> {
        if (elf.size < 64) return emptyList()
        if (u32(elf, 0) != 0x464C457FL) return emptyList()
        val shoff = u64(elf, 0x28)
        val shentsize = u16(elf, 0x3A)
        val shnum = u16(elf, 0x3C)
        if (shoff <= 0 || shentsize < 64 || shnum <= 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until shnum) {
            val o = (shoff + i.toLong() * shentsize).toInt()
            if (o + 64 > elf.size) break
            val type = u32(elf, o + 4)
            val flags = u64(elf, o + 8)
            val off = u64(elf, o + 0x18)
            val size = u64(elf, o + 0x20)
            if (type == 1L && (flags and 0x4L) != 0L && size > 0) {
                val from = off.toInt(); val to = (off + size).toInt()
                if (from in 0 until elf.size && to <= elf.size && to > from) out.add(from to to)
            }
        }
        return out
    }

    private fun readU32(d: ByteArray, at: Int): Int =
        (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8) or
            ((d[at + 2].toInt() and 0xFF) shl 16) or ((d[at + 3].toInt() and 0xFF) shl 24)

    private fun writeU32(d: ByteArray, at: Int, v: Int) {
        d[at] = (v and 0xFF).toByte()
        d[at + 1] = ((v ushr 8) and 0xFF).toByte()
        d[at + 2] = ((v ushr 16) and 0xFF).toByte()
        d[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun u16(d: ByteArray, at: Int): Int =
        (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(d: ByteArray, at: Int): Long = readU32(d, at).toLong() and 0xFFFFFFFFL

    private fun u64(d: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (d[at + k].toLong() and 0xFF)
        return v
    }
}
