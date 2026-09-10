package com.kernelpack.model

/** 单个内核符号。 */
data class KernelSymbol(
    /** 内核虚拟地址（运行时 KASLR 前的链接地址，Long 位模式）。 */
    val address: Long,
    /** nm 风格类型字符：'T'/'t' 代码、'D'/'d'/'B'/'b'/'R'/'r' 数据、'A'/'U' 等。 */
    val type: Char,
    /** 符号名（已去掉类型字符前缀）。 */
    val name: String,
    /** 是否为全局符号。 */
    val isGlobal: Boolean,
) {
    /** 是否代码段符号。 */
    val isText: Boolean get() = type == 'T' || type == 't' || type == 'W' || type == 'w'

    /** 是否数据段符号（含 bss/rodata）。 */
    val isData: Boolean get() = type in "DdBbRrGgSsVv"

    override fun toString(): String =
        java.lang.Long.toHexString(address).padStart(16, '0') + " " + type + " " + name
}

/** kallsyms 各子表在**内核镜像内**的文件偏移。 */
data class KallsymsLayout(
    val tokenTable: Long,
    val tokenIndex: Long,
    val tokenIndexEnd: Long,
    val markers: Long,
    val names: Long,
    val numSyms: Long,
    val addresses: Long,
    /** 地址表元素宽度（2/4/8），由 markers 探测得到。 */
    val offsetTableElementSize: Int,
    /** 是否为 base-relative 偏移表（CONFIG_KALLSYMS_BASE_RELATIVE）。 */
    val hasBaseRelative: Boolean,
    /** 是否启用 ABSOLUTE_PERCPU 语义。 */
    val hasAbsolutePercpu: Boolean,
    /** base-relative 时的相对基址。 */
    val relativeBaseAddress: Long?,
) {
    override fun toString(): String = buildString {
        append("token_table=").append(java.lang.Long.toHexString(tokenTable))
        append(" token_index=").append(java.lang.Long.toHexString(tokenIndex))
        append(" markers=").append(java.lang.Long.toHexString(markers))
        append(" names=").append(java.lang.Long.toHexString(names))
        append(" num_syms=").append(java.lang.Long.toHexString(numSyms))
        append(" addresses=").append(java.lang.Long.toHexString(addresses))
        append(" elem=").append(offsetTableElementSize)
        append(" base_relative=").append(hasBaseRelative)
    }
}

/** boot.img 解析结果。 */
data class BootImageInfo(
    /** 输入的容器类型。 */
    val container: Container,
    /** boot 头版本（v0..v4），原始 Image 时为 null。 */
    val headerVersion: Int?,
    /** boot 头 size 字段。 */
    val headerSize: Long?,
    /** 页大小。 */
    val pageSize: Int?,
    /** 内核 Image 在原始文件中的起始偏移。 */
    val kernelOffset: Int,
    /** 头里声明的 kernel_size。 */
    val declaredKernelSize: Long,
    /** 真正交给解析器的镜像长度（可能是 image_size，也可能是解压后的长度）。 */
    val imageLength: Int,
    /** 是否做过解压（Image.gz 等）。 */
    val decompressed: Boolean,
    /** 是否识别为 arm64 Image（`ARMd` magic）。 */
    val arm64Image: Boolean,
) {
    enum class Container { ANDROID_BOOT, RAW_IMAGE, VENDOR_BOOT, UNKNOWN }
}

/** 一次完整的内核符号提取结果。 */
data class KernelImageAnalysis(
    val boot: BootImageInfo,
    /** `Linux version ...` 整行。 */
    val versionString: String,
    /** 版本号，形如 `6.6.89`。 */
    val versionNumber: String,
    /** 架构名，如 `aarch64`。 */
    val architecture: String,
    val is64Bits: Boolean,
    val isBigEndian: Boolean,
    /** 内核链接基址（KIMAGE_TEXT_BASE）。 */
    val baseAddress: Long,
    val layout: KallsymsLayout,
    val symbols: List<KernelSymbol>,
    /** 提取过程中的日志行。 */
    val log: List<String>,
) {
    /** 按名字索引（同名取地址最小者，符合 kallsyms 顺序）。 */
    val byName: Map<String, KernelSymbol> by lazy {
        val m = HashMap<String, KernelSymbol>(symbols.size * 2)
        for (s in symbols) {
            val prev = m[s.name]
            if (prev == null || java.lang.Long.compareUnsigned(s.address, prev.address) < 0) {
                m[s.name] = s
            }
        }
        m
    }

    /** 同名全部符号。 */
    val allByName: Map<String, List<KernelSymbol>> by lazy {
        symbols.groupBy { it.name }
    }

    /** 符号地址转成相对基址的偏移。 */
    fun offsetOf(symbol: KernelSymbol): Long = symbol.address - baseAddress
}
