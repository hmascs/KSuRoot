package com.kernelpack.resolve

import com.kernelpack.kallsyms.ByteReader

/**
 * 内核镜像的"按地址读内存"适配层。
 *
 * kallsyms 只能给出**有符号名**的东西。像 `compat_ashmem_ioctl`（旧内核叫 `ashmem_compat_ioctl`）
 * 这类没有导出符号的地址，或者藏在 `ctl_table.data` 字段里的指针，必须直接**读镜像内容**才能拿到。
 * 这个类把"内核虚拟地址 ↔ 镜像文件偏移"的换算包起来，并在此之上提供几种惯用法。
 */
class KernelImage(
    val bytes: ByteArray,
    /** 内核镜像链接基址（KIMAGE_TEXT_BASE）。 */
    val baseAddress: Long,
) {
    private val r = ByteReader(bytes)

    /** 地址 → 镜像内偏移；不在镜像范围返回 null。 */
    fun offsetOf(address: Long): Int? {
        val off = address - baseAddress
        if (off < 0 || off >= bytes.size) return null
        return off.toInt()
    }

    fun addressOf(offset: Long): Long = baseAddress + offset

    fun contains(address: Long): Boolean = offsetOf(address) != null

    /** 读 8 字节；越界返回 null。 */
    fun readU64(address: Long): Long? {
        val o = offsetOf(address) ?: return null
        if (o + 8 > bytes.size) return null
        return r.u64(o)
    }

    /** 读 4 字节；越界返回 null。 */
    fun readU32(address: Long): Long? {
        val o = offsetOf(address) ?: return null
        if (o + 4 > bytes.size) return null
        return r.u32(o)
    }

    /** 读 C 字符串（上限 256 字节，必须是可见 ASCII）；越界/非字符串返回 null。 */
    fun readCString(address: Long, max: Int = 256): String? {
        val o = offsetOf(address) ?: return null
        val sb = StringBuilder()
        var i = o
        while (i < bytes.size && sb.length < max) {
            val b = bytes[i].toInt() and 0xff
            if (b == 0) return if (sb.isEmpty()) null else sb.toString()
            if (b < 0x20 || b > 0x7e) return null
            sb.append(b.toChar())
            i++
        }
        return null
    }

    /** 在镜像里查找 8 字节小端值的所有出现位置（返回地址）。 */
    fun findAllU64(value: Long, limit: Int = 64): List<Long> {
        val pat = ByteReader.encodeUIntN(value, 8, true)
        val out = ArrayList<Long>()
        var from = 0
        while (out.size < limit) {
            val at = r.find(pat, from)
            if (at < 0) break
            out.add(baseAddress + at)
            from = at + 1
        }
        return out
    }

    /**
     * 找一个 `struct ctl_table` 条目，其 `procname` 指向 [procName]，返回该条目 **`data` 字段的地址**。
     *
     * `struct ctl_table` 在 arm64 上的前缀布局（include/linux/sysctl.h）：
     * ```
     *   +0x00  const char *procname
     *   +0x08  void *data            <-- 返回这个字段的地址
     *   +0x10  int maxlen
     *   +0x14  umode_t mode
     * ```
     * 做法：已知 `data` 指向的目标符号地址（例如 `sysctl_bootid`），在镜像里搜这个 8 字节值，
     * 对每个命中点回看 8 字节取 `procname` 指针，确认它指向的字符串等于 [procName]。
     */
    fun findCtlTableDataField(procName: String, dataSymbolAddress: Long): Long? {
        for (candidate in findAllU64(dataSymbolAddress)) {
            val procnamePtr = readU64(candidate - 8) ?: continue
            val s = readCString(procnamePtr) ?: continue
            if (s == procName) return candidate
        }
        return null
    }

    /** 读一个 `struct file_operations` 槽位：`u64(fops + slot)`。 */
    fun readFopsSlot(fopsAddress: Long, slot: Long): Long? = readU64(fopsAddress + slot)

    /** 判断一个值是否像内核镜像地址（在 baseAddress..baseAddress+len 内）。 */
    fun looksLikeImagePointer(v: Long): Boolean = contains(v)
}
