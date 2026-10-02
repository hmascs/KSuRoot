package com.kernelpack.vivo

import com.kernelpack.boot.Lz4

/**
 * 从 `vendor_boot.img` 里**直接读出这台机器 vr.ko 用的 tag A 偏移**。
 *
 * ### 为什么需要它
 *
 * 载荷里的 `VR_TAG_A_OFF` 是**编译期烤死**的，而实测它有 `0x04` / `0x06` 两种取值 ——
 * 更麻烦的是**同一台设备**的两份 `vr.ko`（`lib/modules/vr.ko` 与
 * `lib/modules/<版本>/vr.ko`）就各一种。选错的表现是：
 * 绕过去清**错误的字节** → 标记没被抹掉 → 子进程照样被 `sys_exit` 探针杀掉，
 * 而自检报成功。这是最难查的一类失败。
 *
 * ### 怎么读
 *
 * 链路：`VNDRBOOT v4` 头 → vendor_ramdisk（LZ4-legacy，可能是**多帧拼接**）→
 * cpio `newc` → 取 `lib/modules/vr.ko` → 扫 ELF 的可执行节。
 *
 * 在 `vr.ko` 里，打标记那段长这样（三族同构）：
 *
 * ```
 * mrs  x8, sp_el0            ; x8 = current
 * ldrb w9, [x8, #6]          ; ← tag A：6 或 4
 * cbz  w9, ...
 * ldrb w9, [x8, #44]         ; tag B 恒为 0x2c
 * ```
 *
 * ### 为什么选平铺那份
 *
 * ramdisk 里通常有**两套**同名模块：`lib/modules/vr.ko`（平铺）与
 * `lib/modules/<版本>/vr.ko`。Android 的 `modprobe` 查找顺序是
 * `/lib/modules/<uname -r>/` → `/lib/modules/`，而 `<uname -r>` 是
 * `6.1.145-android14-11-maybe-dirty` 这种**完整串**，**不是** `6.1-gki` 这种短名。
 * 所以实际加载的是**平铺那份** —— 本实现优先取它，取不到才退而取带目录的那份
 * （并在 [Result.usedFlatPath] 里如实标出来是哪一种）。
 */
object VrKoProbe {

    /** 探到的结果。 */
    data class Result(
        /** 本机 vr.ko 用的 tag A 字节偏移（`0x04` 或 `0x06`）。 */
        val tagA: Long,
        /** ramdisk 里那份模块的路径，用于日志与复核。 */
        val modulePath: String,
        /** 模块字节数。 */
        val moduleSize: Int,
        /** 模块 sha256（前 16 位十六进制），用于把话说死。 */
        val moduleSha256Prefix: String,
        /** 是否取到的是平铺那份（`lib/modules/vr.ko`）。false = 退而取了带目录的版本。 */
        val usedFlatPath: Boolean,
        /** tag B 的偏移（旁证，恒为 0x2c）。 */
        val tagB: Long,
    )

    /** 认不出来时给一句人话，而不是抛栈。 */
    class ProbeException(message: String) : Exception(message)

    private const val MAGIC_VNDRBOOT = "VNDRBOOT"
    private const val FLAT_PATH = "lib/modules/vr.ko"

    /**
     * @param vendorBoot `vendor_boot.img` 的完整字节。
     * @throws ProbeException 解析不了 / 找不到 vr.ko / 找不到那段代码。
     */
    fun probe(vendorBoot: ByteArray): Result {
        val ramdisk = extractVendorRamdisk(vendorBoot)
        val entries = cpioEntries(ramdisk)
        if (entries.isEmpty()) {
            throw ProbeException("vendor_ramdisk 里没解析出任何 cpio 条目（格式可能不是 newc）。")
        }

        // 优先平铺那份；没有再退回带目录的（按字典序取第一个，保证结果稳定）。
        val flat = entries.firstOrNull { it.name == FLAT_PATH }
        val versioned = entries
            .filter { it.name.startsWith("lib/modules/") && it.name.endsWith("/vr.ko") }
            .minByOrNull { it.name }
        val chosen = flat ?: versioned
            ?: throw ProbeException(
                "这份 vendor_boot 里没有 vr.ko —— 这台机器可能不带 vr.ko（那就不需要绕过），" +
                    "或者它把模块放在别处。",
            )

        val module = ramdisk.copyOfRange(chosen.offset, chosen.offset + chosen.size)
        val found = scanTagOffsets(module)
            ?: throw ProbeException(
                "取到了 ${chosen.name}（${chosen.size} B），但里面找不到打标记那段代码 —— " +
                    "这份 vr.ko 的形态与已知的三族都不同，不敢猜。",
            )

        return Result(
            tagA = found.first,
            modulePath = chosen.name,
            moduleSize = chosen.size,
            moduleSha256Prefix = sha256Prefix(module),
            usedFlatPath = flat != null,
            tagB = found.second,
        )
    }

    // ───────────────────────── vendor_boot ─────────────────────────

    /** VNDRBOOT v3/v4：vendor_ramdisk 在 `align(header_size, page_size)` 处，长度取自头。 */
    private fun extractVendorRamdisk(img: ByteArray): ByteArray {
        if (img.size < 2124) throw ProbeException("文件太小，不像 vendor_boot.img。")
        val magic = String(img, 0, 8, Charsets.US_ASCII)
        if (magic != MAGIC_VNDRBOOT) {
            throw ProbeException(
                "这不是 vendor_boot.img（头 8 字节是 \"$magic\"，应为 \"VNDRBOOT\"）。" +
                    "注意：boot.img 和 vendor_boot.img 是**两个不同的文件**，别弄混。",
            )
        }
        val headerVersion = u32(img, 8)
        if (headerVersion != 3L && headerVersion != 4L) {
            throw ProbeException("vendor_boot 头版本是 $headerVersion，本工具只认 v3 / v4。")
        }
        val pageSize = u32(img, 12).toInt()
        if (pageSize <= 0 || pageSize > 65536) throw ProbeException("vendor_boot 的 page_size 不合理：$pageSize")
        val ramdiskSize = u32(img, 24).toInt()
        if (ramdiskSize <= 0) {
            throw ProbeException("vendor_boot 头的 vendor_ramdisk_size 是 $ramdiskSize —— 这个包里没有 vendor_ramdisk。")
        }
        val headerSize = u32(img, 2096).toInt().takeIf { it > 0 } ?: pageSize
        val start = alignUp(headerSize, pageSize)
        if (start + ramdiskSize > img.size) {
            throw ProbeException(
                "vendor_ramdisk 声明 $ramdiskSize 字节、起点 $start，但文件只有 ${img.size} 字节 —— " +
                    "这份文件被截断了（常见于「只取了前 N MB」的镜像）。",
            )
        }
        val raw = img.copyOfRange(start, start + ramdiskSize)
        if (!Lz4.isLegacy(raw, 0)) return raw
        // Android 的 vendor_ramdisk 就是 lz4-legacy。**上限要够大**：实测一份 40 MB 的
        // ramdisk 能解出 132 MB（多帧拼接、每帧独立可解），默认上限会被顶到。
        return Lz4.decompressLegacy(raw, 0, raw.size, maxOutput = RAMDISK_MAX_OUTPUT)
            ?: throw ProbeException(
                "vendor_ramdisk 是 LZ4-legacy，但解不开（可能被截断，或超过 " +
                    "${RAMDISK_MAX_OUTPUT / 1024 / 1024} MB 的上限）。",
            )
    }

    /** 解压后的 vendor_ramdisk 上限。实测 40 MB → 132 MB，留一倍余量。 */
    private const val RAMDISK_MAX_OUTPUT = 512 * 1024 * 1024

    private fun alignUp(value: Int, alignment: Int): Int =
        ((value + alignment - 1) / alignment) * alignment

    // ───────────────────────── cpio newc ─────────────────────────

    private data class Entry(val name: String, val offset: Int, val size: Int)

    /**
     * 走 cpio `newc`（magic `070701`）。格式：110 字节 ASCII-hex 头 + 名字（NUL 结尾）+ 4 字节对齐 + 数据 + 4 字节对齐。
     */
    private fun cpioEntries(d: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        var i = 0
        while (i + 110 <= d.size) {
            if (d[i] != 0x30.toByte() || d[i + 1] != 0x37.toByte() ||
                d[i + 2] != 0x30.toByte() || d[i + 3] != 0x37.toByte() ||
                d[i + 4] != 0x30.toByte() || d[i + 5] != 0x31.toByte()
            ) break
            val filesize = hexAt(d, i + 6 + 6 * 8)
            val namesize = hexAt(d, i + 6 + 11 * 8)
            if (filesize < 0 || namesize <= 0 || namesize > 4096) break
            val nameStart = i + 110
            if (nameStart + namesize > d.size) break
            val name = String(d, nameStart, namesize - 1, Charsets.UTF_8)
            var next = nameStart + namesize
            next = (next + 3) and 3.inv()
            if (name == "TRAILER!!!") break
            val dataOff = next
            next += filesize
            next = (next + 3) and 3.inv()
            if (dataOff + filesize > d.size) break
            out.add(Entry(name, dataOff, filesize))
            i = next
        }
        return out
    }

    private fun hexAt(d: ByteArray, at: Int): Int {
        if (at + 8 > d.size) return -1
        var v = 0L
        for (k in 0 until 8) {
            val c = d[at + k].toInt()
            val digit = when (c) {
                in 0x30..0x39 -> c - 0x30
                in 0x61..0x66 -> c - 0x61 + 10
                in 0x41..0x46 -> c - 0x41 + 10
                else -> return -1
            }
            v = (v shl 4) or digit.toLong()
        }
        return v.toInt()
    }

    // ───────────────────────── vr.ko 扫描 ─────────────────────────

    /** 扫出 (tag A, tag B)。认不出返回 null。 */
    private fun scanTagOffsets(ko: ByteArray): Pair<Long, Long>? {
        val sections = executableSections(ko)
        for ((from, to) in sections) {
            var i = from
            while (i + 4 <= to) {
                val insn = readU32(ko, i)
                // mrs xN, sp_el0 —— 这段代码一定从"取当前任务"开始
                if ((insn and 0xFFFFFFE0.toInt()) == 0xD5384100.toInt()) {
                    val taskReg = insn and 0x1F
                    // 往后看 8 条，找同一个基址寄存器上的 ldrb/strb 立即数
                    var j = i + 4
                    var tagA = -1L
                    var tagB = -1L
                    var k = 0
                    while (k < 8 && j + 4 <= to) {
                        val x = readU32(ko, j)
                        val imm = byteAccessImm(x, taskReg)
                        if (imm >= 0) {
                            if (tagA < 0) tagA = imm.toLong()
                            else if (tagB < 0 && imm.toLong() != tagA) tagB = imm.toLong()
                        }
                        j += 4
                        k++
                    }
                    if (tagA > 0 && tagB > 0) return tagA to tagB
                }
                i += 4
            }
        }
        return null
    }

    /**
     * `ldrb/strb Wt, [Xn, #imm]`（无符号偏移形式）当基址是 [wantReg] 时返回 imm，否则 -1。
     *
     * 编码：`size=00 | 111001 | opc(2) | imm12 | Rn | Rt`，byte 尺寸不缩放，imm12 就是字节偏移。
     */
    private fun byteAccessImm(insn: Int, wantReg: Int): Int {
        val op = insn and 0xFFC00000.toInt()
        if (op != 0x39000000 && op != 0x39400000.toInt()) return -1
        val rn = (insn ushr 5) and 0x1F
        if (rn != wantReg) return -1
        val imm = (insn ushr 10) and 0xFFF
        return if (imm in 1..255) imm else -1
    }

    /** ELF64 的可执行节（PROGBITS + SHF_EXECINSTR）。 */
    private fun executableSections(elf: ByteArray): List<Pair<Int, Int>> {
        if (elf.size < 64) return emptyList()
        if (u32(elf, 0) != 0x464C457FL) return emptyList()   // 0x7F 'E' 'L' 'F'
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
                val from = off.toInt()
                val to = (off + size).toInt()
                if (from in 0 until elf.size && to <= elf.size && to > from) out.add(from to to)
            }
        }
        return out
    }

    private fun sha256Prefix(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun readU32(d: ByteArray, at: Int): Int =
        (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8) or
            ((d[at + 2].toInt() and 0xFF) shl 16) or ((d[at + 3].toInt() and 0xFF) shl 24)

    private fun u16(d: ByteArray, at: Int): Int =
        (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(d: ByteArray, at: Int): Long = readU32(d, at).toLong() and 0xFFFFFFFFL

    private fun u64(d: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (d[at + k].toLong() and 0xFF)
        return v
    }
}
