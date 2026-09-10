package com.kernelpack

/**
 * 十六进制格式化工具。
 *
 * 约定：本库中所有 64 位量（内核虚拟地址、ELF 地址等）都以 [Long] 的 **位模式** 存放，
 * 不使用有符号语义。`0xffffffc080000000` 这类值在 Long 里是负数，这是正常的，
 * 格式化时必须走这里的方法（基于 `java.lang.Long.toHexString`，按位输出，不做符号处理）。
 */
object Hex {

    /** 16 位定宽十六进制，带 `0x` 前缀：`0xffffffc08210e280`。 */
    @JvmStatic
    fun u64(value: Long): String = "0x" + java.lang.Long.toHexString(value).padStart(16, '0')

    /** 不定宽十六进制，带 `0x` 前缀，用于偏移这类小值：`0xc81de4`。 */
    @JvmStatic
    fun u(value: Long): String = "0x" + java.lang.Long.toHexString(value)

    /** 不定宽十六进制，带 `0x` 前缀（Int 版）。 */
    @JvmStatic
    fun u(value: Int): String = "0x" + Integer.toHexString(value)

    /** 指定宽度的十六进制，不带前缀。 */
    @JvmStatic
    fun fixed(value: Long, width: Int): String =
        java.lang.Long.toHexString(value).padStart(width, '0').takeLast(width)

    /** 解析 `0x...` / 十进制字符串为 64 位位模式。 */
    @JvmStatic
    fun parse(text: String): Long {
        val t = text.trim()
            .removeSuffix("ULL").removeSuffix("ull").removeSuffix("LL").removeSuffix("ll")
            .removeSuffix("U").removeSuffix("u").removeSuffix("L").removeSuffix("l")
        return when {
            t.startsWith("0x", ignoreCase = true) ->
                java.lang.Long.parseUnsignedLong(t.substring(2), 16)
            t.startsWith("-") ->
                -java.lang.Long.parseUnsignedLong(t.substring(1), 16)
            else -> t.toLong()
        }
    }
}

/** `0xffffffc08210e280` */
fun Long.hexU64(): String = Hex.u64(this)

/** `0xc81de4` */
fun Long.hexU(): String = Hex.u(this)

/** 无符号比较：<0 小于、0 相等、>0 大于。 */
fun Long.ucmp(other: Long): Int = java.lang.Long.compareUnsigned(this, other)
