package com.kernelpack.model

/** 一个偏移是怎么来的 —— 决定可信度，也决定出问题时从哪查。 */
enum class ResolveSource {
    /** 直接在 kallsyms 里按符号名查到。 */
    KALLSYMS,

    /** 由 kallsyms 符号经常量加减推导（例如 `nfulnl_logger - 0xb0`）。 */
    KALLSYMS_DERIVED,

    /** 从内核镜像里**读指针**得到（例如读 `ashmem_fops+0x50` 得到 compat_ioctl）。 */
    IMAGE_READ,

    /** 镜像里按结构特征扫描得到（例如按 `procname == "boot_id"` 找 ctl_table）。 */
    IMAGE_SCAN,

    /** 没解析出来，沿用了基线 .so 里的旧值。 */
    BASELINE,

    /** 完全拿不到。 */
    UNAVAILABLE,
}

/** 单个偏移的解析结果。 */
data class OffsetEntry(
    /** 稳定键，形如 `ASHMEM_OPEN`。 */
    val key: String,
    /** 相对内核镜像基址（KIMAGE_TEXT_BASE）的偏移；null = 未解析。 */
    val offset: Long?,
    /** 内核虚拟地址（基址 + 偏移）；null = 未解析。 */
    val address: Long?,
    val source: ResolveSource,
    /** 人类可读的来历说明，用于日志/排错。 */
    val detail: String,
) {
    val resolved: Boolean get() = offset != null
}

/** 一份完整的目标画像：能直接喂给打包器，也能导出成 C 头文件。 */
data class TargetProfile(
    /** 机型/版本标签，如 `pd2520-bp2a.250605.031.a3`。 */
    val variantLabel: String,
    /** 内核版本号，如 `6.6.89`。 */
    val versionNumber: String,
    /** 架构，如 `aarch64`。 */
    val architecture: String,
    /** 内核镜像链接基址。 */
    val imageBase: Long,
    /** 内核内存布局常量（P0_PAGE_OFFSET / DIRECT_MAP_BASE / ... ），按 ABI 档位带出。 */
    val memoryLayout: Map<String, Long>,
    /** 符号类偏移（本次从 boot.img 解析出来的部分）。 */
    val offsets: Map<String, OffsetEntry>,
    /** 结构体字段偏移（ABI 级，来自基线档位，不随 boot.img 变）。 */
    val structOffsets: Map<String, Long>,
    /** 未解析的键，便于上层提示用户。 */
    val unresolved: List<String>,
) {
    fun offset(key: String): Long? = offsets[key]?.offset

    fun requireOffset(key: String): Long =
        offsets[key]?.offset ?: error("偏移未解析: $key")
}
