package com.kernelpack.elf

/**
 * 极简 ELF64 小端解析器 —— 只做打包器需要的事：
 *  - 找 `.text`（要扫指令）和可分配的只读/数据段（要找字面量指针）；
 *  - 读/写任意文件偏移的 u32/u64。
 *
 * 不做重定位、不做动态链接，**不改动任何文件布局**：所有 patch 都是原地覆盖，
 * 因此 ELF 头、段表、重定位表、`.relr.dyn` 全部保持原样，产出的 .so 可以直接
 * `LD_PRELOAD` 使用，不会因为布局变化被 linker 拒绝。
 */
class Elf64File(val bytes: ByteArray) {

    class Section(
        val name: String,
        val type: Long,
        val flags: Long,
        val addr: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val info: Int,
        val addralign: Long,
        val entsize: Long,
    ) {
        val isExecutable: Boolean get() = (flags and SHT_EXECINSTR) != 0L
        val isAlloc: Boolean get() = (flags and SHF_ALLOC) != 0L
        val isWritable: Boolean get() = (flags and SHF_WRITE) != 0L
        val isNoBits: Boolean get() = type == SHT_NOBITS

        /** 节内容对应的字节区间（NOBITS 节没有内容）。 */
        val hasBytes: Boolean get() = !isNoBits && offset < Int.MAX_VALUE && size > 0

        fun endOffset(): Long = offset + size
    }

    val sections: List<Section>

    init {
        require(bytes.size >= 64) { "文件过小，不是 ELF" }
        require(bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) { "不是 ELF 文件（magic 不匹配）" }
        require(bytes[4].toInt() == 2) { "不是 ELF64（EI_CLASS=${bytes[4]}）" }
        require(bytes[5].toInt() == 1) { "只支持小端 ELF（EI_DATA=${bytes[5]}）" }

        val shoff = u64(0x28)
        val shentsize = u16(0x3a)
        val shnum = u16(0x3c)
        val shstrndx = u16(0x3e)

        // 第一遍：读节表原始项
        data class Raw(val nameOff: Long, val type: Long, val flags: Long, val addr: Long,
                       val offset: Long, val size: Long, val link: Int, val info: Int,
                       val align: Long, val entsize: Long)

        val raws = ArrayList<Raw>(if (shnum > 0) shnum else 0)
        for (i in 0 until (if (shoff > 0) shnum else 0)) {
            val base = (shoff + i.toLong() * shentsize).toInt()
            if (base + 64 > bytes.size) break
            raws.add(
                Raw(
                    nameOff = u32(base + 0x00),
                    type = u32(base + 0x04),
                    flags = u64(base + 0x08),
                    addr = u64(base + 0x10),
                    offset = u64(base + 0x18),
                    size = u64(base + 0x20),
                    link = u32(base + 0x28).toInt(),
                    info = u32(base + 0x2c).toInt(),
                    align = u64(base + 0x30),
                    entsize = u64(base + 0x38),
                )
            )
        }

        // 第二遍：用 .shstrtab 解名字
        val strSec = raws.getOrNull(shstrndx)
        val strs = if (strSec != null && strSec.offset + strSec.size <= bytes.size) {
            bytes.copyOfRange(strSec.offset.toInt(), (strSec.offset + strSec.size).toInt())
        } else ByteArray(0)

        sections = raws.map { r ->
            Section(
                name = cstr(strs, r.nameOff.toInt()),
                type = r.type, flags = r.flags, addr = r.addr, offset = r.offset, size = r.size,
                link = r.link, info = r.info, addralign = r.align, entsize = r.entsize,
            )
        }
    }

    fun section(name: String): Section? = sections.firstOrNull { it.name == name }

    /** 可执行且可分配的节（通常是 `.text` 和 `.plt`）。 */
    fun executableSections(): List<Section> =
        sections.filter { it.isExecutable && it.isAlloc && it.hasBytes && it.size > 0 }

    /** 可分配、非可执行、有内容的节（`.rodata` / `.data.rel.ro` / `.data` …）。 */
    fun allocDataSections(): List<Section> =
        sections.filter { it.isAlloc && !it.isExecutable && it.hasBytes && it.size > 0 }

    // ---------------------------------------------------------------- 读写

    fun u16(off: Int): Int = (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8)

    fun u32(off: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (bytes[off + i].toLong() and 0xff)
        return v
    }

    fun u64(off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[off + i].toLong() and 0xff)
        return v
    }

    fun putU32(off: Int, value: Long) {
        for (i in 0 until 4) bytes[off + i] = ((value ushr (8 * i)) and 0xff).toByte()
    }

    fun putU64(off: Int, value: Long) {
        for (i in 0 until 8) bytes[off + i] = ((value ushr (8 * i)) and 0xff).toByte()
    }

    fun readU64(off: Int): Long = u64(off)

    /** 在指定区间里找 8 字节小端值的所有出现（返回文件偏移）。 */
    fun findAllU64(value: Long, from: Int, to: Int): List<Int> {
        val pat = ByteArray(8) { ((value ushr (8 * it)) and 0xff).toByte() }
        val out = ArrayList<Int>()
        val end = minOf(to, bytes.size)
        var i = from
        outer@ while (i + 8 <= end) {
            for (k in 0 until 8) if (bytes[i + k] != pat[k]) { i++; continue@outer }
            out.add(i)
            i++
        }
        return out
    }

    private fun cstr(table: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= table.size) return ""
        var e = offset
        while (e < table.size && table[e] != 0.toByte()) e++
        return String(table, offset, e - offset, Charsets.UTF_8)
    }

    companion object {
        const val SHT_NOBITS = 8L
        const val SHF_WRITE = 0x1L
        const val SHF_ALLOC = 0x2L
        const val SHT_EXECINSTR = 0x4L
    }
}
