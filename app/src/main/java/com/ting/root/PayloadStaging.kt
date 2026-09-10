package com.ting.root

import android.content.Context
import android.system.Os
import java.io.File

/**
 * 让「原生库目录里的文件」真正可用 —— 载荷要可读，执行助手要可执行。
 *
 * ### 为什么需要这一层（实测踩到的坑）
 * 内置载荷与执行助手都住在 `applicationInfo.nativeLibraryDir`
 * （本机是 `/data/app/~~xxx/com.ting.root-yyy/lib/arm64/`）。
 * 这个目录里的文件是**安装器以 system 身份**提取的：
 *
 * ```
 * -rwxr-xr-x system system 162328 libbs.so
 * -rwxr-xr-x system system  26024 libcve43499root.so
 * ```
 *
 * 属主是 `system` 而不是应用自己 —— 所以应用对它执行 `Os.chmod` 会直接抛
 * `ErrnoException: chmod failed: EACCES (Permission denied)`。
 * 旧代码在这里没有兜任何异常，于是整条安装链在打印完
 * 「使用内置动态库：libbs.so」之后立刻断掉：
 *
 * ```
 * [-] chmod failed: EACCES (Permission denied)
 * [*] 安装失败
 * ```
 *
 * 而它本来就 `-rwxr-xr-x`：**那次 chmod 是多余的**（载荷只用 LD_PRELOAD 映射，
 * 不需要额外权限）。自定义载荷之所以一直没事，是因为那份文件是应用自己
 * 写进私有目录的，属主是应用自己。
 *
 * ### 因此这里的策略是「先看够不够，再动手」
 * 1. 已经满足（可读 / 可执行）→ **原样返回，一次 chmod 都不做**（常见路径，零风险）；
 * 2. 不满足 → 先试就地 chmod（有些 ROM/分区允许）；
 * 3. 还不满足 → 复制到应用私有目录（一定能写）再 chmod，并缓存复用
 *    （同名同长度直接跳过复制，重复安装不会反复写盘）；
 * 4. 全都失败 → 返回原文件，把判断留给调用方（它们本来就有 require 兜底）。
 */
internal object PayloadStaging {

    /** 0444：只读。给载荷用。 */
    private const val MODE_READABLE = 0b100100100

    /** 0755：可执行。给执行助手用。 */
    private const val MODE_EXECUTABLE = 0b111101101

    /** 把 [source] 变成「应用读得到」的文件；已经是就直接返回它。 */
    fun ensureReadable(context: Context, source: File): File =
        ensure(context, source, MODE_READABLE) { it.canRead() }

    /** 把 [source] 变成「应用执行得了」的文件；已经是就直接返回它。 */
    fun ensureExecutable(context: Context, source: File): File =
        ensure(context, source, MODE_EXECUTABLE) { it.canExecute() }

    private fun ensure(
        context: Context,
        source: File,
        mode: Int,
        satisfied: (File) -> Boolean,
    ): File {
        if (satisfied(source)) return source
        if (runCatching { Os.chmod(source.absolutePath, mode) }.isSuccess && satisfied(source)) {
            return source
        }
        val directory = File(context.filesDir, STAGED_DIR).apply { mkdirs() }
        val staged = File(directory, source.name)
        runCatching {
            if (!staged.exists() || staged.length() != source.length()) {
                source.inputStream().use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                }
            }
            Os.chmod(staged.absolutePath, mode)
        }
        return if (satisfied(staged)) staged else source
    }

    private const val STAGED_DIR = "staged"
}
