package com.ting.root.root

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * root 检测与命令执行。
 *
 * 设计约束（决定了这里的取舍）
 * --------------------------
 * 1. **所有命令都通过 `su -c` 走**，不自己找 `libksud.so` 去 exec ——
 *    `su -c` 是各家管理器（KernelSU / SukiSU / Magisk）共通且唯一有保证的入口。
 * 2. **`su` 可能会弹授权框、也可能永远不返回**，所以每一步都必须带超时。
 *    没有超时的 `su` 调用会把界面线程（或协程）永久挂住 —— 这是本类所有
 *    `waitFor(timeout)` 的原因，不是为了好看。
 * 3. **不吞输出**：stdout/stderr 合并后原样往上传。移交失败时用户唯一能自救的东西
 *    就是那几行原文，我们不能替它"总结"掉。
 *
 * [可测性] 路径（`su` 候选位置）与执行器都可以注入，这样单测不需要真的 root。
 */
class RootShell(
    private val exec: (List<String>, Long) -> ShellOutcome = ::execWithTimeout,
    /** `su` 的候选路径，按顺序探测。有的设备只有 `/system/bin/su`，有的只有 `su`。 */
    private val suCandidates: List<String> = DEFAULT_SU_CANDIDATES,
) {

    /** 执行结果。 */
    data class ShellOutcome(val exitCode: Int, val output: String, val timedOut: Boolean = false)

    /**
     * 当前是否有 root。
     *
     * 判据是**实际跑一次 `id` 并看 uid**，不是去查文件是否存在 ——
     * "存在 su 二进制"和"这个 App 真的拿到了 root"是两件事
     * （用户可能在管理器里拒绝过本应用）。
     */
    fun hasRoot(): Boolean = hasRoot(exec, suCandidates)

    /** 带逐条过程的检测（界面诊断用）。 */
    fun probe(): Companion.Probe = probe(exec, suCandidates)

    /** 跑一条 root 命令。没有 root 时直接返回 [HandoffResult.NoRoot] 语义的失败。 */
    fun runAsRoot(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): ShellOutcome {
        val tried = ArrayList<String>()
        for (su in suCandidates) {
            if (!suExists(su)) {
                tried.add("$su(不存在)")
                continue
            }
            val outcome = exec(listOf(su, "-c", command), timeoutMillis)
            // 超时/被拒绝时不继续试下一个 su —— 那通常意味着管理器在等用户点授权，
            // 换条路径再弹一次只会更乱。
            return outcome
        }
        return ShellOutcome(
            exitCode = 127,
            output = "找不到可用的 su —— 逐个试过：${tried.joinToString("、")}",
        )
    }

    /** 在给定的候选与执行器上做检测 —— 抽出来是为了单测能喂假实现。 */
    companion object {
        /**
         * `su` 的候选路径，**绝对路径优先**。
         *
         * [勘误 —— 真机实测踩到的坑] 第一版是
         * `listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su")`，
         * 结果在本机（vivo/iQOO + SukiSU Ultra）上**检测不到 root**：
         * 这台机器的可用 `su` 在 **`/product/bin/su`**（KernelSU 的 648 字节静态 shim，
         * 内部 exec `/data/adb/ksud`），而它在 `/system/bin`、`/sbin` 下都不存在。
         * 于是候选全落空 → 界面显示「未检测到 root」，而设备明明有 root。
         *
         * 教训：**"su" 这个名字在哪，是厂商/管理器决定的，不能只赌 /system/bin。**
         * `/product/bin` 与 `/debug_ramdisk` 是近年内核 root 方案常见的落点。
         *
         * [补充] 本工程载荷提权后的落点是 `/apex/com.android.virt/bin/su`（用户实测确认），
         * 已置于首位。
         */
        val DEFAULT_SU_CANDIDATES = listOf(
            // 本工程的载荷提权后把 su 落在这里（用户实测确认）。
            // 放在**第一位**：刚跑完载荷的设备，root 就在这个位置，
            // 先探它命中率最高，也最贴合"跑完载荷 → 移交"这条主流程。
            "/apex/com.android.virt/bin/su",
            "/product/bin/su",     // KernelSU / SukiSU 常见（本机实测就在这）
            "/debug_ramdisk/su",   // Magisk 的 ramdisk 落点
            "/system/bin/su",      // 传统
            "/system/xbin/su",     // 老设备
            "/sbin/su",            // 老设备
            "su",                  // 兜底：交给 PATH（Environment 里的 PATH 含 /product/bin）
        )

        const val DEFAULT_TIMEOUT_MILLIS = 20_000L

        /** `id` 输出里出现 `uid=0` 才算 root。 */
        fun outputIndicatesRoot(output: String): Boolean =
            output.contains("uid=0") || output.contains("uid=0(root)")

        /**
         * 逐个候选试，**并把每个候选的结果记下来**。
         *
         * 为什么要记录：真机上一旦检测失败，用户看到的只有"未检测到 root"，
         * 而我们要判断是"路径不对"还是"被管理器拒绝了授权"还是"超时"，
         * 只能靠这些逐条结果。没有它就只能靠猜。
         */
        fun probe(
            exec: (List<String>, Long) -> ShellOutcome,
            suCandidates: List<String>,
            suExists: (String) -> Boolean = { PathLookup.exists(it) },
        ): Probe {
            val tried = ArrayList<CandidateResult>()
            for (su in suCandidates) {
                if (!suExists(su)) {
                    tried.add(CandidateResult(su, false, "路径不存在或不可执行", -1))
                    continue
                }
                val r = exec(listOf(su, "-c", "id"), 8_000L)
                val ok = !r.timedOut && outputIndicatesRoot(r.output)
                tried.add(
                    CandidateResult(
                        path = su,
                        launched = true,
                        detail = when {
                            r.timedOut -> "执行超时（可能管理器弹了授权框但你没点）"
                            ok -> "拿到 uid=0"
                            else -> "退出码 ${r.exitCode}：${r.output.trim().take(120).ifBlank { "(无输出)" }}"
                        },
                        exitCode = r.exitCode,
                    ),
                )
                if (ok) return Probe(true, tried)
            }
            return Probe(false, tried)
        }

        fun hasRoot(
            exec: (List<String>, Long) -> ShellOutcome,
            suCandidates: List<String>,
            suExists: (String) -> Boolean = { PathLookup.exists(it) },
        ): Boolean = probe(exec, suCandidates, suExists).ok

        /** 单个候选的探测结果。 */
        data class CandidateResult(
            val path: String,
            val launched: Boolean,
            val detail: String,
            val exitCode: Int,
        )

        /** 整体探测结果 + 逐条过程。 */
        data class Probe(val ok: Boolean, val tried: List<CandidateResult>) {
            /** 给用户看的一句话诊断（检测失败时特别有用）。 */
            fun describe(): String =
                tried.joinToString("；") { "${it.path}→${if (it.launched) it.detail else "不存在"}" }
        }

        /**
         * `su` 是否可用。
         *
         * [勘误] 第一版对裸名 `"su"` 直接无条件返回 true，等于**假装它在**。
         * 结果 App 会去 exec 一个不存在的路径，拿到 IOException，
         * 而报出来的理由是"找不到可用的 su"，掩盖了真正的问题。
         * 现在裸名也真的去 PATH 里找。
         */
        fun suExists(su: String): Boolean = PathLookup.exists(su)

        /** 真实实现：起进程、合并 stderr、带超时强杀。 */
        fun execWithTimeout(argv: List<String>, timeoutMillis: Long): ShellOutcome {
            return try {
                val pb = ProcessBuilder(argv).redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    ShellOutcome(-1, output, timedOut = true)
                } else {
                    ShellOutcome(process.exitValue(), output)
                }
            } catch (t: Throwable) {
                // 找不到 su 时 ProcessBuilder 会抛 IOException —— 这是**正常**的探测结果，
                // 不是崩溃，所以转成退出码而不是往上抛。
                ShellOutcome(127, "${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun suExists(su: String): Boolean = Companion.suExists(su)
}

/**
 * `su` 的 PATH 查找。
 *
 * 单独抽出来是为了**可测**：单测不能真的去读 /product/bin。
 * 真机上 `Environment.getPath()` 给的是 App 自己的 PATH，
 * 而 ProcessBuilder 起进程时用的正是它 —— 所以这里查的就是那份。
 */
object PathLookup {
    /** 可注入的 PATH；null = 用运行环境的真实 PATH。 */
    var searchPath: String? = null

    fun exists(name: String): Boolean {
        if (name.contains('/')) return File(name).canExecute()
        val path = searchPath ?: System.getenv("PATH") ?: "/sbin:/system/sbin:/system/bin:/system/xbin"
        return path.split(':').any { dir ->
            dir.isNotBlank() && File(dir, name).canExecute()
        }
    }
}

/** 把一次移交的完整流程串起来（检测 → 定位 → late-load → 归类结果）。 */
class RootHandoffRunner(private val shell: RootShell = RootShell()) {

    /**
     * 上一次 root 检测的逐条过程 —— 界面在"没检测到"时把它摊给用户看。
     *
     * 为什么要有：真机上检测失败时，用户只看到"未检测到 root"是**没法自救**的。
     * 有了这个，他能看出是路径不对（`/system/bin/su 不存在`）还是管理器没授权
     * （`执行超时` 或 `Denied`），据此去管理器里改设置。
     */
    var lastProbe: RootShell.Companion.Probe? = null
        private set

    /**
     * 检测 root。
     *
     * @param probe 注入用；默认走真实 [RootShell]。
     */
    fun detectRoot(probe: () -> Boolean = { shell.hasRoot() }): Boolean = runCatching { probe() }
        .getOrDefault(false)

    /** 带诊断的检测：同时记录逐条过程。 */
    fun detectRootVerbose(): Boolean {
        val p = runCatching { shell.probe() }.getOrNull()
        lastProbe = p
        return p?.ok == true
    }

    /**
     * 执行移交。
     *
     * 流程与判据：
     * ```
     *   ① 没有 root            → NoRoot（不尝试执行，避免弹两次授权框）
     *   ② 跑合成命令
     *   ③ 输出 NO_KSUD / exit 3 → ManagerMissing（引导去装）
     *   ④ exit 0 且无明显错误串 → Success
     *   ⑤ 其余                  → Failed（原样带出输出）
     * ```
     */
    fun handoff(manager: RootManager, hasRoot: Boolean = detectRoot()): HandoffResult {
        if (!hasRoot) return HandoffResult.NoRoot
        val cmd = HandoffCommand.of(manager)
        val outcome = shell.runAsRoot(cmd.shell)
        val output = outcome.output
        if (HandoffOutput.managerMissing(output, outcome.exitCode)) {
            return HandoffResult.ManagerMissing(manager)
        }
        if (HandoffOutput.looksSuccessful(output, outcome.exitCode)) {
            return HandoffResult.Success(
                manager = manager,
                ksudPath = HandoffOutput.ksudPath(output) ?: "(未打印路径)",
                output = output,
            )
        }
        return HandoffResult.Failed(manager, outcome.exitCode, output)
    }
}
