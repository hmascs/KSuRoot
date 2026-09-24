package com.ting.root.root

/**
 * 把「临时拿到的 root」**移交**给一个常驻的 Root 管理器。
 *
 * 为什么必须有这一步
 * ------------------
 * 本工程的提权链路（`--run-payload`）拿到的是**当前进程的 root**，进程一结束就没了。
 * 想让它变成"设备上持续可用的 root"，必须把内核里的 su 兼容层**挂到一个管理器 App 上**：
 * KernelSU / SukiSU Ultra 都提供了一个 `libksud.so`，用它的 `late-load` 子命令
 * 可以把刚打进去的 KernelSU 内核模块**late-load** 进正在运行的内核，
 * 并指定"哪个包名算管理器"。
 *
 * 两个关键点（踩过就知道）
 * ------------------------
 * 1. **`libksud.so` 要从管理器 App 自己的安装目录里找**，不能自己造一个 ——
 *    它是随管理器版本走的，版本不匹配会 load 失败。
 *    所以用 `find /data/app -name libksud.so | grep <包名> | head -n 1` 定位。
 * 2. **`--package-name` 必须和 grep 的包名一致**。用户给的那条 KernelSU 命令写的是
 *    `grep "me.weishu.kernelsu" ... --package-name me.weishu.kernel`，**少了一个 `su`** ——
 *    那是错的（`me.weishu.kernel` 不是合法包名）。本文件统一用 [RootManager.packageName]，
 *    保证两处永远一致，不再手抄。
 *
 * 权限：整条链路都要 root（`su -c`）。没有 root 时本功能**只提示、不尝试**。
 */

/** 支持移交的 Root 管理器。 */
enum class RootManager(
    /** Android 包名，同时用于"去哪儿找 libksud.so"和 `--package-name`。 */
    val packageName: String,
    /** UI 上显示的名字。 */
    val displayName: String,
    /** 管理器官网 / 下载页，带给用户去装。 */
    val homeUrl: String,
) {
    KERNELSU(
        packageName = "me.weishu.kernelsu",
        displayName = "KernelSU",
        homeUrl = "https://kernelsu.org/",
    ),
    SUKISU_ULTRA(
        packageName = "com.sukisu.ultra",
        displayName = "SukiSU Ultra",
        homeUrl = "https://github.com/SukiSU-Ultra/SukiSU-Ultra",
    ),
    ;

    /** 在设备上定位该管理器的 `libksud.so`。 */
    val findCommand: String
        get() = "find /data/app -name libksud.so | grep \"$packageName\" | head -n 1"
}

/** 一条可执行的移交命令（拆成"找路径"+"late-load"两段，便于分别报告失败原因）。 */
data class HandoffCommand(
    val manager: RootManager,
    /** 定位 libksud.so；输出为空表示管理器没装或版本不对。 */
    val locate: String,
    /** 真正的 late-load。`$KSUD` 由 [locate] 的结果替换。 */
    val lateLoad: String,
) {
    /** 合成成一条可直接交给 `su -c` 的完整命令。 */
    val shell: String
        get() = "KSUD=\$($locate); " +
            "if [ -z \"\$KSUD\" ]; then echo \"NO_KSUD\"; exit 3; fi; " +
            "echo \"KSUD=\$KSUD\"; " +
            lateLoad

    companion object {
        fun of(manager: RootManager): HandoffCommand = HandoffCommand(
            manager = manager,
            locate = manager.findCommand,
            lateLoad = "\"\$KSUD\" late-load --allow-shell --package-name ${manager.packageName}",
        )
    }
}

/** 移交结果。 */
sealed class HandoffResult {
    /** 成功。 */
    data class Success(val manager: RootManager, val ksudPath: String, val output: String) :
        HandoffResult() {
        val summary: String get() = "已移交给 ${manager.displayName}"
    }

    /** 管理器没装（找不到 libksud.so）—— 这是最常见的一种，要引导去装。 */
    data class ManagerMissing(val manager: RootManager) : HandoffResult() {
        val summary: String get() = "没找到 ${manager.displayName} 的 libksud.so"
    }

    /** 没有 root 权限。 */
    data object NoRoot : HandoffResult() {
        val summary: String get() = "当前没有 root 权限"
    }

    /** 命令跑了但失败。 */
    data class Failed(val manager: RootManager, val exitCode: Int, val output: String) : HandoffResult() {
        val summary: String get() = "${manager.displayName} 移交失败（退出码 $exitCode）"
    }
}

/**
 * 命令输出的解析 —— **纯函数**，与进程执行分开，好单测。
 *
 * 约定的输出格式（见 [HandoffCommand.shell]）：
 * ```
 *   KSUD=/data/app/.../libksud.so     ← 找到了
 *   NO_KSUD                            ← 没找到（exit 3）
 *   <late-load 的输出>                 ← 其余
 * ```
 */
object HandoffOutput {

    const val NO_KSUD = "NO_KSUD"

    /** 从输出里取出 `KSUD=<路径>` 那一行。 */
    fun ksudPath(output: String): String? =
        output.lineSequence()
            .firstOrNull { it.startsWith("KSUD=") }
            ?.removePrefix("KSUD=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 输出是否表明"管理器没装"。 */
    fun managerMissing(output: String, exitCode: Int): Boolean =
        output.contains(NO_KSUD) || exitCode == 3

    /**
     * late-load 是否成功。
     *
     * [诚实声明] 这里**只按退出码与已知关键字判断**，不做"看起来像成功"的猜测。
     * KernelSU / SukiSU 的 late-load 没有稳定的机器可读输出协议，
     * 所以本项目采取的策略是：**退出码 0 且没有明显错误串**才算成功，
     * 并在 UI 上把原始输出原样给用户看 —— 让人自己核对，比我们猜更可靠。
     */
    fun looksSuccessful(output: String, exitCode: Int): Boolean {
        if (exitCode != 0) return false
        if (managerMissing(output, exitCode)) return false
        val lower = output.lowercase()
        // [勘误] 第一版只列了 error/failed/denied/not found/invalid/cannot，
        // 结果 `Operation not permitted`（EPERM 的标准说法、也是 late-load 最常见的
        // 失败形态：SELinux 或内核不允许）会**漏过**，被报成"成功"。
        // 把一个权限失败报成成功，比不报更糟 —— 单测里那条用例把它抓了出来。
        val badWords = listOf(
            "error", "failed", "failure", "denied", "not found", "invalid", "cannot",
            "not permitted", "permission", "unable", "refused", "mismatch",
        )
        return badWords.none { lower.contains(it) }
    }

    /** 把 [HandoffResult] 变成用户能读的一段话（含命令原文，便于自己复核）。 */
    fun describe(result: HandoffResult): List<String> = when (result) {
        is HandoffResult.Success -> listOf(
            "已移交给 ${result.manager.displayName}。",
            "ksud：${result.ksudPath}",
            "输出：${result.output.trim().ifBlank { "(无)" }}",
            "请打开 ${result.manager.displayName} 确认是否已识别到 root。",
        )
        is HandoffResult.ManagerMissing -> listOf(
            "在 /data/app 下没找到 ${result.manager.displayName} 的 libksud.so" +
                "（包名 ${result.manager.packageName}）。",
            "说明该管理器**没装**，或者装的是别的版本/别的包名。",
            "请先安装 ${result.manager.displayName}：${result.manager.homeUrl}",
            "装好后再回到这里点一次「移交 root」。",
        )
        HandoffResult.NoRoot -> listOf(
            "当前没有 root 权限，无法移交。",
            "请先在本工具的构建页跑一次载荷，拿到 root 之后再来。",
        )
        is HandoffResult.Failed -> listOf(
            "${result.manager.displayName} 移交失败（退出码 ${result.exitCode}）。",
            "输出：${result.output.trim().ifBlank { "(无)" }}",
            "常见原因：管理器版本与其内核模块不匹配、内核不支持 late-load、" +
                "或该管理器并非本次注入的 KernelSU 分支。",
        )
    }
}
