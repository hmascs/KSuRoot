package com.ting.root

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.time.Duration.Companion.milliseconds

enum class InstallPhase {
    Checking,
    Ready,
    Downloading,
    Exploiting,
    LoadingKernelSu,
    Installed,
    Failed,
}

/**
 * 「进行中」阶段的**唯一定义**。
 *
 * [InstallUiState.busy] 与安装页的 `BUSY_PHASES` 都取自这里，避免两处各写一份
 * 字面量 —— 之前那两处一旦只改一边，就会出现"页面上还在转圈但按钮已经能点"之类的
 * 状态错位。返回的是不可变集合，可以在顶层直接持有、无需每次重组重建。
 */
internal fun busyPhases(): Set<InstallPhase> = setOf(
    InstallPhase.Checking,
    InstallPhase.Downloading,
    InstallPhase.Exploiting,
    InstallPhase.LoadingKernelSu,
)

data class InstallUiState(
    val phase: InstallPhase = InstallPhase.Checking,
    val message: String = "",
    val probeOutput: String = "",
    val log: String = "",
    /**
     * 提权页日志框**实际渲染**的内容。
     *
     * 与 [log] 的区别：[log] 是载荷的原始输出（英文 + 地址 + errno，用于导出给开发者），
     * 这里是**给用户看的**——详细模式逐行翻译，简略模式只留里程碑。
     * 两者分开存，是为了让「导出原始日志」和「界面上看得懂」不再互相妥协。
     */
    val displayLog: String = "",
    /**
     * 载荷启动前那段日志（自检 / Shizuku / 载荷选定），即 [log] 的前缀。
     *
     * 之所以要把它显式存下来：CFI 之后 [log] 是**延后合并**的（见
     * `publishExploitLog` 的性能说明），收尾时必须能重新拼出完整原文，
     * 而调用点的局部变量那时已经出了作用域。
     */
    val logPrefix: String = "",
) {
    val busy: Boolean
        get() = phase in BUSY_PHASE_SET
}

/** [busyPhases] 的常量实例，供 [InstallUiState.busy] 零分配读取。 */
private val BUSY_PHASE_SET = busyPhases()

private data class CommandResult(val code: Int, val output: String)

class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = PayloadRepository(application)
    private val historyStore = InstallHistoryStore(application)
    private val mutableState = MutableStateFlow(InstallUiState())
    private val mutableHistory = MutableStateFlow(historyStore.closeInterruptedRuns())
    private var discoveryJob: Job? = null
    private var installJob: Job? = null
    private var activeHistoryEntry: InstallHistoryEntry? = null

    /**
     * 提权日志的**增量读取器**。
     *
     * 旧实现在轮询里对 `exploit.log` 调 `readText()`：每 250ms 把整个文件重新读一遍，
     * 而载荷输出是**只追加**的 —— 已读过的字节被反复读了几十上百次，轮询间隔越短浪费越大。
     * 换成一个带偏移量的读取器后，没有新输出的 tick 只做一次 `length()` stat，
     * 从 O(文件大小) 降到 O(新增字节)。
     */
    private var logReader: IncrementalLogReader? = null

    /**
     * 历史记录的**脏标记**。
     *
     * `appendLog` 以前每写一行就同步走一次 `AtomicFile` + `fd.sync()`；载荷输出快时
     * 一秒能灌十几行，等于一秒十几次 fsync。现在行文本立即进 `mutableState`（UI 照旧实时），
     * 历史落盘改成后台低频协程按 [HISTORY_SAVE_DEBOUNCE] 合并写一次，并额外用
     * [HISTORY_SAVE_MIN_INTERVAL_MILLIS] 给写入速率封顶 —— 哪怕日志持续不断刷，
     * 最多也就是每 500ms 一次磁盘写，而不是每行一次。
     */
    @Volatile
    private var historyDirty = false
    private var historyFlusher: Job? = null

    /**
     * 当前这次安装的日志行缓冲。
     *
     * 同时充当两件事：UI 上展示的文本（`joinToString` 后进 `mutableState`），
     * 以及历史记录要落盘的快照。以前是把整段日志当字符串不断 `+` 拼接、
     * 每次追加都重新分配一个新 String（日志越长越慢），现在按行存，
     * 追加是 O(1)，只在发布时才拼一次。
     */
    private val installLogLines = ArrayList<String>(256)

    /**
     * 日志框**实际渲染**的语义化行缓冲（与 [installLogLines] 一一对应但内容不同）。
     *
     * [installLogLines] 是载荷原始输出，用于导出与排查；这里是翻译/收敛后给用户看的。
     * 分开维护的原因：用户既需要「界面看得懂」，也需要「导出的是原始日志」，
     * 用同一份缓冲就无法同时满足——之前界面上刷的是英文原始串，正是这个原因。
     */

    /**
     * `stripAnsi(rawLog)` 的缓存。载荷日志是累积增长的，每次追加几十字节就
     * 把整段重跑一遍正则会随日志长度退化成 O(n²)；这里按"源长度"做键，
     * 长度没变就直接复用上一次的结果。
     */
    private var strippedCache = ""
    private var strippedSourceLength = -1

    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    val history: StateFlow<List<InstallHistoryEntry>> = mutableHistory.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (installJob?.isActive == true) return
        mutableHistory.value = historyStore.load()
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            val probe = NativeProbe.run()
            if (detectInstalled()) {
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Installed,
                    message = app.getString(R.string.status_ksu_active),
                    probeOutput = probe,
                    log = probe,
                )
                return@launch
            }
            val localLabel = activeLocalPayloadLabel()
            if (localLabel != null) {
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Ready,
                    message = app.getString(R.string.status_not_installed),
                    probeOutput = probe,
                    log = "$probe\n${app.getString(R.string.log_local_ready, localLabel)}",
                )
                return@launch
            }
            try {
                // 全部改成本地解析：不再有 GitHub 往返，这一步是纯内存 + 一次文件 stat。
                val snapshot = DeviceSnapshot.current()
                val resolution = repository.bundledTarget(snapshot, allowSimilar = false)
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Ready,
                    message = app.getString(R.string.status_not_installed),
                    probeOutput = probe,
                    log = "$probe\n${app.getString(R.string.log_profile, resolution.entry.library)}",
                )
            } catch (error: Throwable) {
                // 设备没有精确匹配的内置库时不再直接判"不支持" —— 可能是同厂商的兄弟机型，
                // 标成"可能可用"让用户自己决定要不要试。
                //
                // 注意 snapshot 只取一次：`DeviceSnapshot.current()` 会读 /proc（version、
                // cmdline、sysfs）并做正则解析，是这份代码里最贵的一次"本地调用"。旧写法
                // 在 catch 分支里又调了两次，纯属重复劳动。
                val snapshot = runCatching { DeviceSnapshot.current() }.getOrNull()
                val similar = snapshot?.let { current ->
                    runCatching { repository.bundledTarget(current, allowSimilar = true) }.getOrNull()
                }
                if (similar != null && snapshot != null) {
                    mutableState.value = InstallUiState(
                        phase = InstallPhase.Ready,
                        message = app.getString(R.string.status_not_installed),
                        probeOutput = probe,
                        log = buildString {
                            append(probe)
                            append('\n')
                            append(app.getString(R.string.log_profile, similar.entry.library))
                            append('\n')
                            append(
                                app.getString(
                                    R.string.log_match_similar_device,
                                    similar.entry.displayName,
                                    snapshot.model,
                                ),
                            )
                        },
                    )
                } else {
                    mutableState.value = InstallUiState(
                        phase = InstallPhase.Failed,
                        message = app.getString(R.string.status_support_failed),
                        probeOutput = probe,
                        log = "$probe\n[-] ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /**
     * 开始一次安装。
     *
     * @param bundledLibrary 指定要用的内置库文件名（从载荷列表里手动点选时传入）。
     *        `null` 表示按设备自动匹配 —— 这是常态，也是唯一会走 [BundledPayloadCatalog]
     *        三级匹配的入口。
     *
     * 只有这一个参数。「载荷来源」(`Bundled` / `Custom`) 由 [AppPreferences.payloadSource]
     * 在安装线程里现读 —— 多传一个 `profileId` 进来会出现「参数说 Custom、偏好说 Bundled」
     * 这种自相矛盾的状态，而偏好的那份是**真的会被执行**的那份。
     */
    fun install(bundledLibrary: String? = null) {
        if (installJob?.isActive == true || mutableState.value.phase == InstallPhase.Installed) return
        discoveryJob?.cancel()
        installJob = viewModelScope.launch(Dispatchers.IO) {
            // 一次安装的日志缓冲从零开始：它同时也是历史记录的镜像，
            // 直接 append 成行列表，避免"字符串拼接 + 每次刷新都整段扫一遍"。
            installLogLines.clear()
            // 上一次安装的摘要 / 里程碑 / CFI 标记必须一起清掉，否则新的一轮会从
            // 上一轮的进度条中段起步。
            digestedLength = 0
            // `strippedCache` 系列也必须清 —— 它们上一轮缓存的是**上一次**的载荷输出。
            // 若不清：新一轮首跳时 `rawLog.length` 恰好等于上一轮的 `strippedSourceLength`
            // （都是 0 就是最典型的一种）就会命中缓存分支，直接把上一轮的日志当作
            // 本轮内容返回，digest 也对着一份陈旧文本跑。
            strippedSourceLength = -1
            strippedCache = ""
            logPrefixCache = ""
            p0ScanLength = 0
            mutableState.value = InstallUiState(
                phase = InstallPhase.Checking,
                probeOutput = mutableState.value.probeOutput,
            )
            startHistory()
            try {
                if (shizukuEnabled()) {
                    appendLog(app.getString(R.string.log_shizuku_prepare))
                    if (!ShizukuController.isRunning() && !ShizukuController.pingUntilRunning()) {
                        error(app.getString(R.string.error_shizuku_unavailable))
                    }
                    if (!ShizukuController.isGranted() && !ShizukuController.requestPermission()) {
                        error(app.getString(R.string.error_shizuku_permission))
                    }
                    appendLog(app.getString(R.string.log_shizuku_permission))
                }
                val customInfo = CustomPayloadStore.current(app)
                // 自定义载荷被删掉了却还停在 Custom 来源：退回内置，别让安装卡在"文件不存在"。
                var source = AppPreferences.payloadSource(app)
                if (source == PayloadSource.Custom && customInfo == null) {
                    appendLog(app.getString(R.string.log_custom_missing_fallback))
                    source = PayloadSource.Bundled
                }
                setPhase(InstallPhase.Checking, app.getString(R.string.status_local_preparing))

                val payloads = when (source) {
                    PayloadSource.Bundled -> {
                        val snapshot = DeviceSnapshot.current()
                        val resolution = if (bundledLibrary != null) {
                            repository.selectBundled(bundledLibrary)
                                ?: error(app.getString(R.string.error_bundled_missing))
                        } else {
                            repository.bundledTarget(snapshot)
                        }
                        val entry = resolution.entry
                        appendLog(app.getString(R.string.log_profile, entry.library))
                        // 手动指定的那一份如果**不是**本机自动匹配会选的那一份，明确写出来。
                        // 否则用户点了 A、日志里只有一行库名，出问题时根本判断不了
                        // 到底是他选错了还是程序拿错了。
                        //
                        // 只在手动指定时才反查一次自动匹配结果：这是用户可能"选错机型"的
                        // 唯一路径，值得多读一次快照；走自动匹配时再算一遍纯属重复劳动。
                        if (bundledLibrary != null) {
                            val autoPicked = repository.bundledTarget(snapshot).entry.library
                            if (entry.library != autoPicked) {
                                appendLog(
                                    app.getString(
                                        R.string.log_manual_payload,
                                        entry.library,
                                        snapshot.model,
                                    ),
                                )
                            }
                        }
                        updateHistoryProfile(entry.library)
                        appendLog(app.getString(R.string.log_bundled_payload, entry.displayName))
                        // 命中等级不是精确匹配时讲清楚原因 —— 这三款机型是「可能可用」，
                        // 用户有权知道自己在赌什么，而不是被默默塞一份不匹配的库。
                        when (resolution.tier) {
                            BundledPayloadCatalog.MatchTier.SameKernel -> appendLog(
                                app.getString(
                                    R.string.log_match_same_kernel,
                                    snapshot.kernelRelease,
                                ),
                            )
                            BundledPayloadCatalog.MatchTier.SimilarDevice -> appendLog(
                                app.getString(
                                    R.string.log_match_similar_device,
                                    entry.displayName,
                                    snapshot.model,
                                ),
                            )
                            BundledPayloadCatalog.MatchTier.Exact -> Unit
                        }
                        repository.bundledPayloads(resolution)
                    }
                    PayloadSource.Custom -> {
                        val info = customInfo ?: error(app.getString(R.string.custom_import_failed))
                        val profile = repository.customTarget(DeviceSnapshot.current())
                        appendLog(app.getString(R.string.log_profile, profile.profileId))
                        updateHistoryProfile(profile.profileId)
                        appendLog(app.getString(R.string.log_custom_payload, info.displayName))
                        repository.customPayloads(profile, info)
                    }
                }

                // 载荷已定，先把家族确定下来 —— 三套利用链的日志格式完全不同，
                // 必须用对应的映射表，否则出现的是另一种机型的误译。

                setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))
                executeExploit(payloads.exploit)

                if (payloads.kernelSu != null) {
                    setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_ksu_loading))
                    installKernelSu(payloads)
                }

                setPhase(InstallPhase.Installed, app.getString(R.string.status_ksu_active))
                appendLog(app.getString(R.string.log_install_complete))
                finishHistory(InstallRunResult.Succeeded)
            } catch (error: Throwable) {
                // 失败退出同样要补全原始日志：提权失败时用户**最需要**的就是完整原文，
                // 而失败往往正好发生在 CFI 之后的高频段。
                forceLogRebuild()
                appendLog("[-] ${error.message ?: error.javaClass.simpleName}")
                setPhase(InstallPhase.Failed, app.getString(R.string.status_install_failed))
                finishHistory(InstallRunResult.Failed)
            }
        }
    }

    private suspend fun executeExploit(payload: File) {
        val shizuku = shizukuEnabled()
        val logFile = if (shizuku) File(SHIZUKU_LOG_PATH) else File(app.filesDir, "exploit.log")
        if (shizuku) {
            ShizukuController.exec(arrayOf("rm", "-f", SHIZUKU_LOG_PATH)).waitFor()
        } else {
            logFile.delete()
        }
        val helper = helperFile()
        if (!shizuku) {
            require(helper.canExecute()) { app.getString(R.string.error_helper_unavailable) }
        }
        val logPrefix = mutableState.value.log
        val bootToken = currentBootToken()
        val cachedOffset = cachedP0Offset(bootToken)
        val process = if (shizuku) {
            val stagedPayload = shizukuStage(payload, SHIZUKU_PAYLOAD_PATH, "755")
            ShizukuController.exec(
                arrayOf("/system/bin/sh", "-c", "true"),
                shizukuEnvironment(cachedOffset, stagedPayload.absolutePath, helper.absolutePath),
            )
        } else {
            val processBuilder = ProcessBuilder(
                helper.absolutePath,
                "--run-payload",
                payload.absolutePath,
                helper.absolutePath,
                logFile.absolutePath,
            ).redirectErrorStream(true)
            processBuilder.environment().apply {
                put("EXPLOIT_ATTEMPTS", EXPLOIT_ATTEMPTS)
                put("P0_ATTEMPT_TIMEOUT_SEC", "45")
                put("EXPLOIT_ATTEMPT_TIMEOUT_SEC", "120")
                cachedOffset?.let { put(P0_OFFSET_ENV, it) }
            }
            processBuilder.start()
        }

        if (!shizuku) {
            // 先把读取器挂上再进轮询：载荷从启动到第一次输出之间有一段 KASLR 尝试的静默期，
            // 旧实现在这段时间里每 250ms 把（可能已经很大的）日志整段重读一遍。
            logReader = IncrementalLogReader(logFile)
        }
        val captured = StringBuilder()
        val readLog: () -> String = if (shizuku) {
            { drainProcessOutput(process, captured) }
        } else {
            { logReader?.readNew() ?: logFile.readTextIfPresent() }
        }

        // 已发布出去的日志长度。用它判断"这次 tick 是否有新内容"，
        // 而不是把整段日志和上一版做 `String !=` 比较（字符串全量比较 + 每次都捕获快照）。
        var publishedLength = 0
        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var polls = 0
            while (process.isAlive) {
                val rawLog = readLog()
                if (rawLog.length != publishedLength) {
                    cacheP0Offset(bootToken, rawLog)
                    publishExploitLog(logPrefix, rawLog)
                    publishedLength = rawLog.length
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                if (++polls >= POLLS_PER_WATCHDOG_CHECK) {
                    polls = 0
                    val now = SystemClock.elapsedRealtime()
                    require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                        app.getString(R.string.error_exploit_stalled)
                    }
                    require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                        app.getString(R.string.error_exploit_timeout)
                    }
                }
                delay(if (shizuku) SHIZUKU_LOG_POLL_INTERVAL else LOG_POLL_INTERVAL)
            }

            val exitCode = process.waitFor()
            val rawLog = readLog()
            cacheP0Offset(bootToken, rawLog)
            publishExploitLog(logPrefix, rawLog)
            // 载荷已退出，不会再有高频输出 —— 把累积的原始日志一次性补全。
            // 少了这一步，`log` 会停在 CFI 之前的某个中间态：用户点「导出原始日志」
            // 拿到的是一份半截的、恰好缺了最关键那段的日志。
            forceLogRebuild()
            val earlyOutput = readProcessOutput(process, shizuku).trim()
            require(exitCode == 0) {
                app.getString(
                    R.string.error_payload_exit,
                    exitCode,
                    earlyOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "",
                )
            }
            require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) {
                app.getString(R.string.error_success_marker)
            }
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
            logReader?.close()
            logReader = null
        }
        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private fun drainProcessOutput(process: Process, buffer: StringBuilder): String {
        return try {
            drainStream(process.inputStream, buffer)
            drainStream(process.errorStream, buffer)
            buffer.toString()
        } catch (_: Throwable) {
            buffer.toString()
        }
    }

    private fun drainStream(stream: InputStream, buffer: StringBuilder) {
        val data = ByteArray(4096)
        while (stream.available() > 0) {
            val count = stream.read(data)
            if (count <= 0) break
            buffer.append(String(data, 0, count, Charsets.UTF_8))
        }
    }

    /**
     * 发布载荷日志。
     *
     * @param rawLog 载荷到目前为止的**全部**输出（增量读取器给的是累积串）。
     *
     * 这里做了三件事：
     * 1. `stripAnsi` 的结果按 `rawLog.length` 缓存 —— 载荷每次追加几十字节，
     *    旧实现却把整段（已经涨到几十 KB 的）日志重新跑一遍正则。长度没变
     *    就说明内容没变，直接复用。
     * 2. 全量替换用不着 —— 前缀（启动前的日志）是固定的，后缀是 rawLog，
     *    拼接即得，省掉 `listOf(...).filter{...}.joinToString` 的中间集合。
     * 3. **只有原始日志变了才重建 `log` 字符串**。`log` 是几百 KB 的原始输出，
     *    而界面只渲染 `displayLog`（译文）。CFI 之后载荷毫秒级刷屏，
     *    原来每个轮询周期（250ms）都要把整段重新 `joinToString` 一遍并
     *    往 Compose 状态里塞一个新对象 —— UI 侧每 250ms 收到一次几百 KB 的
     *    "新值"，等于自己给自己制造了一次全量 diff。现在 `log` 只在真正
     *    有新增内容时重建，且**不在 CFI 之后的高频段重建**。
     */
    private fun publishExploitLog(prefix: String, rawLog: String) {
        logPrefixCache = prefix
        val cleaned = if (rawLog.length == strippedSourceLength) {
            strippedCache
        } else {
            stripAnsi(rawLog).also {
                strippedCache = it
                strippedSourceLength = rawLog.length
            }
        }
        if (cleaned.length <= digestedLength) return
        digestedLength = cleaned.length
        // [2026-09-24] 翻译引擎已整体移除：界面日志 = 原始日志（去掉 ANSI）。
        // 因此只要有新增内容就必须重建 —— 这正是"用户看到新东西"的定义。
        val combined = when {
            prefix.isBlank() -> cleaned
            cleaned.isBlank() -> prefix
            else -> "$prefix\n$cleaned"
        }
        mutableState.value = mutableState.value.copy(
            log = combined,
            logPrefix = prefix,
            displayLog = combined,
        )
        markHistoryDirty()
    }

    /**
     * 把累积的原始日志一次性同步进 [InstallUiState.log]。
     *
     * 收尾时调用：CFI 之后为了让 UI 不为几百 KB 的字符串反复做全量更新，
     * `publishExploitLog` 是有意跳过重建的。真正需要完整原文时（安装结束、
     * 「导出原始日志」）必须在这里补上，否则导出的会是 CFI 前后的半截日志。
     */
    private fun forceLogRebuild() {
        val previous = mutableState.value
        val combined = synchronized(logRebuildLock) {
            // 用 `logPrefixCache` 而不是 `previous.logPrefix`：后者会被 publishExploitLog
            // 一起写，两者平时一致；但收尾可能发生在第一次 publish **之前**（载荷秒退），
            // 那时只有 `logPrefixCache` 是准的。
            val cleaned = strippedCache
            when {
                logPrefixCache.isBlank() -> cleaned
                cleaned.isBlank() -> logPrefixCache
                else -> "$logPrefixCache\n$cleaned"
            }
        }
        if (combined.isNotBlank() && combined != previous.log) {
            mutableState.value = previous.copy(log = combined)
            markHistoryDirty()
        }
    }

    /** [publishExploitLog] 已消化到 `stripAnsi` 后文本的哪个位置。 */
    private var digestedLength = 0

    /**
     * [cacheP0Offset] 已扫描到原始日志的哪个位置。
     *
     * 旧实现对全量日志跑 `P0_OFFSET_PATTERN.findAll`，日志每涨一点就从头
     * 再扫一遍，随日志增长退化成 O(n²)。这里改成只扫上次之后的新增尾部
     * （带一段重叠窗口防匹配跨写入边界被截断）。
     */
    private var p0ScanLength = 0

    /**
     * 载荷启动前就已存在的那段日志（自检、Shizuku、载荷选定 …）。
     *
     * 单独存一份，是为了让 [forceLogRebuild] 不必依赖调用点的局部变量 ——
     * 收尾时那个变量已经出了作用域。
     */
    private var logPrefixCache = ""

    /** [strippedCache] / [logPrefixCache] 的写入串行化（轮询协程与收尾协程都会碰）。 */
    private val logRebuildLock = Any()


    private fun installKernelSu(payloads: VerifiedPayloads) {
        val kernelSu = requireNotNull(payloads.kernelSu)
        if (shizukuEnabled()) {
            shizukuStage(kernelSu, SHIZUKU_KSUD_PATH, "755")
            shizukuStage(kernelSu, SHIZUKU_KSUD_STAGE_PATH, "755")
            appendLog(app.getString(R.string.log_ksu_staged))
        } else {
            val source = shellQuote(kernelSu.absolutePath)
            val stageCommand =
                "/system/bin/cp $source /data/local/tmp/ksud-s25u-kdp && " +
                    "/system/bin/cp $source /data/local/tmp/.ksud-stage && " +
                    "/system/bin/chmod 755 /data/local/tmp/ksud-s25u-kdp /data/local/tmp/.ksud-stage"
            val stage = runHelper("-c", stageCommand)
            require(stage.code == 0) { app.getString(R.string.error_ksu_stage, stage.output) }
            appendLog(app.getString(R.string.log_ksu_staged))
        }

        val lateLoad = runHelper("--late-load")
        require(lateLoad.code == 0) {
            app.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output)
        }
        if (lateLoad.output.isNotBlank()) appendLog(lateLoad.output)
        storeInstallReceipt()
        appendLog(app.getString(R.string.log_ksu_control_verified))
    }

    private fun activeLocalPayloadLabel(): String? =
        when (AppPreferences.payloadSource(app)) {
            PayloadSource.Bundled -> app.getString(R.string.payload_source_bundled)
            PayloadSource.Custom -> CustomPayloadStore.current(app)?.displayName
        }

    private fun detectInstalled(): Boolean {
        if (NativeProbe.isKernelSuActive()) return true
        val bootToken = currentBootToken() ?: return false
        val receipt = app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE)
        return receipt.getString(RECEIPT_BOOT_TOKEN, null) == bootToken &&
            receipt.getBoolean(RECEIPT_VERIFIED, false)
    }

    private fun storeInstallReceipt() {
        val bootToken = currentBootToken() ?: error(app.getString(R.string.error_boot_id))
        val stored = app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE)
            .edit()
            .putString(RECEIPT_BOOT_TOKEN, bootToken)
            .putBoolean(RECEIPT_VERIFIED, true)
            .commit()
        require(stored) { app.getString(R.string.error_receipt) }
    }

    private fun currentBootToken(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun cachedP0Offset(bootToken: String?): String? {
        if (bootToken == null) return null
        val stored = app.getSharedPreferences(P0_CACHE, Application.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) != bootToken) return null
        return stored.getString(P0_CACHE_OFFSET, null)
    }

    private fun cacheP0Offset(bootToken: String?, log: String) {
        if (bootToken == null) return
        // 只扫新增尾部（带重叠窗口）。日志文件只在末尾追加，同一 boot 会话里
        // slide 值固定，所以窗口内取到的匹配与全量扫描语义等价。
        val scanFrom = if (p0ScanLength in 1..log.length) {
            p0ScanLength - P0_SCAN_OVERLAP.coerceAtMost(p0ScanLength)
        } else {
            0
        }
        p0ScanLength = log.length
        if (scanFrom >= log.length) return
        val match = P0_OFFSET_PATTERN.findAll(log, scanFrom).lastOrNull() ?: return
        val offset = match.groupValues[1].toLongOrNull(16) ?: return
        if (offset !in 0..P0_OFFSET_MAX || offset and P0_OFFSET_MASK != 0L) return
        val value = "0x${offset.toString(16)}"
        val stored = app.getSharedPreferences(P0_CACHE, Application.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) == bootToken &&
            stored.getString(P0_CACHE_OFFSET, null) == value
        ) return
        stored.edit()
            .putString(P0_CACHE_BOOT_TOKEN, bootToken)
            .putString(P0_CACHE_OFFSET, value)
            .apply()
    }

    private fun helperFile(): File =
        if (shizukuEnabled()) {
            shizukuStage(nativeHelperFile(), SHIZUKU_HELPER_PATH, "755")
        } else {
            // 同样来自 /data/app/.../lib：属主是 system，chmod 会 EACCES。
            // 它本来就是 0755，所以这里通常原样返回；万一某些 ROM 给成 0644，
            // PayloadStaging 会复制到私有目录再补上可执行位。
            PayloadStaging.ensureExecutable(app, nativeHelperFile())
        }

    private fun nativeHelperFile() = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private fun shizukuEnabled(): Boolean = AppPreferences.shizukuMode(app)

    private fun shizukuStage(source: File, target: String, mode: String): File {
        val staged = File(target)
        if (staged.exists() && staged.length() == source.length()) return staged
        try {
            ShizukuController.writeFile(target, mode, source.inputStream())
        } catch (error: Throwable) {
            throw IllegalStateException(
                app.getString(R.string.error_shizuku_stage, target, error.message.orEmpty()),
                error,
            )
        }
        return staged
    }

    private fun shizukuEnvironment(
        cachedOffset: String?,
        payloadPath: String,
        helperPath: String,
    ): Array<String> = buildList {
        add("EXPLOIT_ATTEMPTS=$EXPLOIT_ATTEMPTS")
        add("P0_ATTEMPT_TIMEOUT_SEC=45")
        add("EXPLOIT_ATTEMPT_TIMEOUT_SEC=120")
        add("CVE43499_ROOT_HELPER=$helperPath")
        add("LD_PRELOAD=$payloadPath")
        cachedOffset?.let { add("$P0_OFFSET_ENV=$it") }
    }.toTypedArray()

    private fun readProcessOutput(process: Process, shizuku: Boolean): String {
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = if (shizuku) process.errorStream.bufferedReader().use { it.readText() } else ""
        return stdout + stderr
    }

    private fun runHelper(vararg arguments: String): CommandResult {
        val helper = helperFile()
        val process = if (shizukuEnabled()) {
            ShizukuController.exec(arrayOf(helper.absolutePath) + arguments)
        } else {
            ProcessBuilder(listOf(helper.absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
        }
        val output = readProcessOutput(process, shizukuEnabled())
        return CommandResult(process.waitFor(), stripAnsi(output.trim()))
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun setPhase(phase: InstallPhase, message: String) {
        mutableState.value = mutableState.value.copy(phase = phase, message = message)
        appendLog("[*] $message")
    }

    /**
     * 追加一行日志。
     *
     * 行文本**立刻**进 `mutableState`（UI 实时性不变），历史落盘只打脏标记。
     * 缓冲区有上限：提权日志刷得快，历史记录不需要无限增长 —— 超出后丢最旧的，
     * 保留尾部 [MAX_LOG_LINES] 行，同时也能让字符串保持小而快。
     *
     * [InstallUiState.displayLog] 与 `log` 现在是**同一份**（翻译引擎已移除）。
     */
    private fun appendLog(line: String) {
        val cleanLine = stripAnsi(line).trim()
        if (cleanLine.isBlank()) return
        installLogLines.add(cleanLine)
        if (installLogLines.size > MAX_LOG_LINES) {
            installLogLines.subList(0, installLogLines.size - LOG_TRIM_TARGET).clear()
        }
        // [2026-09-24] 翻译引擎已整体移除：界面日志与原始日志同一份。
        val joined = installLogLines.joinToString("\n")
        mutableState.value = mutableState.value.copy(
            log = joined,
            displayLog = joined,
        )
        markHistoryDirty()
    }

    /**
     * 让界面把**非提权链路**的事件也写进同一份运行日志（目前是「移交 root」的结果）。
     *
     * 为什么值得开这个口子：移交失败时**唯一能自救的东西就是那几行原始输出**，
     * 而用户会导出运行日志。如果这一步的结果只活在对话框里，关掉就没了，
     * 出问题时我们手里什么都没有。日志是这条链路的证据留痕。
     *
     * 之所以不把 [appendLog] 直接改成 public：那个函数带 `stripAnsi` + 历史同步等
     * 内部约定，外部调用方未必知道该守什么规矩。这里只暴露"写一行日志"这一件事。
     */
    fun logExternalEvent(line: String) = appendLog(line)

    private fun startHistory() {
        val entry = historyStore.create()
        activeHistoryEntry = entry
        publishHistory(entry)
        startHistoryFlusher()
    }

    /**
     * 历史记录的合并写协程。
     *
     * 代替原来"每行日志一次 `AtomicFile` + `fd.sync()`"的做法：每次醒来把当前
     * [mutableState] 里的日志快照落一次盘，两次写之间至少隔 [HISTORY_SAVE_MIN_INTERVAL_MILLIS]，
     * 且只在确实脏了的时候写。姿态改成 `saveDraft`（原子替换但不 fsync），
     * 真正需要掉电安全的时刻仍然走完整 `save`。
     */
    private fun startHistoryFlusher() {
        historyFlusher?.cancel()
        historyFlusher = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HISTORY_SAVE_DEBOUNCE)
                if (!historyDirty || activeHistoryEntry == null) continue
                historyDirty = false
                flushHistoryDraft()
                // 日志一直在涨时按最小间隔限速，避免"每 500ms 写一次"退化成持续写。
                if (historyDirty) {
                    delay(HISTORY_SAVE_MIN_INTERVAL_MILLIS)
                }
            }
        }
    }

    private fun markHistoryDirty() {
        historyDirty = true
    }

    /**
     * 把当前日志草稿写入磁盘。按 entry id 复用同一个 `AtomicFile`，
     * 仍然是「写临时文件 → rename」的原子替换，只是不 fsync。
     */
    private suspend fun flushHistoryDraft() {
        val entry = activeHistoryEntry ?: return
        val log = mutableState.value.log
        val updated = entry.copy(log = log)
        activeHistoryEntry = updated
        if (log.isNotEmpty()) historyStore.saveDraft(updated)
    }

    /**
     * 停止合并写并做一次**完整**落盘，确保 UI 拿到的是最终版本、磁盘也是最终版本。
     */
    private suspend fun stopHistoryFlusher() {
        historyFlusher?.cancel()
        historyFlusher = null
        if (historyDirty) {
            historyDirty = false
            flushHistoryDraft()
        }
    }

    private fun updateHistoryProfile(profileId: String) {
        val entry = activeHistoryEntry ?: return
        val updated = entry.copy(profileId = profileId)
        activeHistoryEntry = updated
        historyStore.save(updated)
        publishHistory(updated)
    }

    private suspend fun finishHistory(result: InstallRunResult) {
        stopHistoryFlusher()
        val entry = activeHistoryEntry ?: return
        val completed = entry.copy(
            completedAtMillis = System.currentTimeMillis(),
            result = result,
            log = mutableState.value.log,
        )
        activeHistoryEntry = null
        historyDirty = false
        historyStore.save(completed)
        publishHistory(completed)
        // 记录已经完整落盘，把内存态与磁盘尾文件一起收掉。
        historyStore.release(completed.id)
    }

    private fun publishHistory(entry: InstallHistoryEntry) {
        mutableHistory.value = (mutableHistory.value.filterNot { it.id == entry.id } + entry)
            .sortedByDescending(InstallHistoryEntry::startedAtMillis)
    }

    private fun File.readTextIfPresent(): String = if (exists()) readText() else ""

    /**
     * `exploit.log` 的增量读取器。
     *
     * 载荷以只追加方式写这个文件，所以"看有没有新输出"本质上是一次 `length()`；
     * 只有长度真的变了才去读那一段增量。旧实现对每次轮询都 `readText()` 整个文件，
     * 在 250ms 的轮询节奏下，同一批字节会被重复读取多次，日志越大越浪费。
     *
     * 几个不要删掉的处理：
     * - 句柄在进程启动前后可能还没建立，`RandomAccessFile` 构造失败要能自己重试；
     * - 新读到的字节可能切在多字节 UTF-8 字符中间，最后一个不完整字符必须留在缓冲里
     *   等下一个 tick 补齐，否则日志里会出现替换字符；
     * - 读取按行切分，最后一行没有换行符说明还没写完，也不能急着输出。
     *
     * 注意这个类只在单线程（安装协程）里使用，[close] 也由同一线程调用。
     */
    private class IncrementalLogReader(private val file: File) {
        private var raf: RandomAccessFile? = null
        private var offset = 0L
        private val output = StringBuilder()
        private val carry = StringBuilder()

        /**
         * 上一轮读取留下的**不完整 UTF-8 字节**（0~3 字节）。
         *
         * 这里是必须单独处理的一层，不能只靠字符缓冲：`String(bytes, UTF_8)` 遇到
         * 被切断的多字节字符时会**当场**替换成 U+FFFD —— 等我们看到字符串时原文已经
         * 丢了，再往后拼也补不回来。所以要把尾部那段不完整字节先"寄存"到下一轮，
         * 和后续字节拼成完整序列再解码。中文日志被切断的概率不低，这个不处理就会
         * 在日志里稳定出现"乱码方块"。
         */
        private val pending = ByteArray(4)
        private var pendingCount = 0

        fun readNew(): String {
            val length = file.length()
            val handle = ensureHandle() ?: return output.toString()
            if (length <= offset) return output.toString()
            val chunk = ByteArray(((length - offset).coerceAtMost(MAX_CHUNK_BYTES)).toInt())
            val read = handle.read(chunk)
            if (read <= 0) return output.toString()
            offset += read
            decode(chunk, read)
            return output.toString()
        }

        private fun decode(chunk: ByteArray, count: Int) {
            // 先把上一轮的残留字节拼到最前面，凑成一个完整的字节序列再解码。
            val bytes: ByteArray
            if (pendingCount == 0) {
                bytes = chunk
            } else {
                bytes = ByteArray(pendingCount + count)
                System.arraycopy(pending, 0, bytes, 0, pendingCount)
                System.arraycopy(chunk, 0, bytes, pendingCount, count)
            }
            var end = bytes.size

            // 从尾部回溯，找到最后一个完整 UTF-8 序列的结束位置，剩下的寄到下一轮。
            val completeEnd = trailingCompleteEnd(bytes, 0, end)
            if (completeEnd < end) {
                pendingCount = (end - completeEnd).coerceAtMost(pending.size)
                System.arraycopy(bytes, completeEnd, pending, 0, pendingCount)
                end = completeEnd
            } else {
                pendingCount = 0
            }
            if (end <= 0) return
            append(String(bytes, 0, end, Charsets.UTF_8))
        }

        /**
         * 返回"从 start 到 end 之间可以安全解码"的结束下标。
         * 等于 [end] 表示没有残缺序列。
         */
        private fun trailingCompleteEnd(bytes: ByteArray, start: Int, end: Int): Int {
            var i = end - 1
            var steps = 0
            while (i >= start && steps < UTF8_MAX_SEQUENCE_BYTES) {
                val b = bytes[i].toInt() and 0xFF
                if (b and 0xC0 == 0x80) {
                    i--
                    steps++
                    continue
                }
                val expected = utf8SequenceLength(b)
                if (expected == 0) return end
                return if (end - i < expected) i else end
            }
            return end
        }

        private fun append(text: String) {
            carry.append(text)
            val lastNewline = carry.lastIndexOf('\n')
            if (lastNewline >= 0) {
                output.append(carry, 0, lastNewline + 1)
                carry.delete(0, lastNewline + 1)
            } else if (carry.length > MAX_CARRY_CHARS) {
                // 极端情况：载荷长时间不换行（比如进度条原地刷新）。别把内存撑爆，
                // 直接当作完整行吐出去。
                output.append(carry)
                carry.setLength(0)
            }
        }

        private fun ensureHandle(): RandomAccessFile? {
            raf?.let { return it }
            if (!file.exists()) return null
            return try {
                RandomAccessFile(file, "r").also {
                    it.seek(offset)
                    raf = it
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun close() {
            try {
                raf?.close()
            } catch (_: Throwable) {
                // 关闭失败无关紧要：进程退出时由内核回收描述符。
            }
            raf = null
        }
    }

    companion object {
        private const val EXPLOIT_ATTEMPTS = "24"
        /**
         * 日志「多久没有新输出」就判定卡死并放弃（杀掉载荷进程）。
         *
         * 90s → **180s**：这条链在真机上有几段天然的长静默 ——
         * KASLR 泄露要等 pselect 路由（[=] 每次尝试 8s 量级、预算 24 次），
         * 之后的堆喷 / pipe 布局又是长时间不打印的纯计算。90s 会在这些安静段
         * 被误判成卡死而主动 kill，日志上看起来就是"莫名退出"。
         *
         * 注意这是**应用侧的看门狗**，与载荷自己的两个内部超时不是一回事：
         * `P0_ATTEMPT_TIMEOUT_SEC=45` / `EXPLOIT_ATTEMPT_TIMEOUT_SEC=120`
         * 是编译进载荷里、由它自己控制的单次尝试上限（见下方 environment()）。
         * 总时长上限仍由 [EXPLOIT_TOTAL_MILLIS]（15 分钟）兜底。
         */
        private const val EXPLOIT_STALL_MILLIS = 180_000L
        private const val EXPLOIT_TOTAL_MILLIS = 900_000L
        private const val INSTALL_RECEIPT = "install_receipt"
        private const val RECEIPT_BOOT_TOKEN = "kernel_boot_id"
        private const val RECEIPT_VERIFIED = "verified"
        private const val P0_CACHE = "p0_cache"
        private const val P0_CACHE_BOOT_TOKEN = "kernel_boot_id"
        private const val P0_CACHE_OFFSET = "offset"
        private const val P0_OFFSET_ENV = "SLIDE_P0_OFFSET"
        private const val P0_OFFSET_MAX = 0x1f0000L
        private const val P0_OFFSET_MASK = 0xffffL
        private const val SHIZUKU_LOG_PATH = "/data/local/tmp/ksu-exploit.log"
        private const val SHIZUKU_HELPER_PATH = "/data/local/tmp/ksu-helper"
        private const val SHIZUKU_PAYLOAD_PATH = "/data/local/tmp/ksu-payload"
        private const val SHIZUKU_KSUD_PATH = "/data/local/tmp/ksud-s25u-kdp"
        private const val SHIZUKU_KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"
        private val LOG_POLL_INTERVAL = 120.milliseconds
        private val SHIZUKU_LOG_POLL_INTERVAL = 300.milliseconds

        /**
         * 每 N 次轮询才走一遍看门狗判定。
         *
         * 旧实现在**每次**轮询里都调两次 `SystemClock.elapsedRealtime()` 并比较两个
         * 时间差 —— 轮询节奏下这些调用占比很小，但既然循环体本身已经足够轻，
         * 就没必要每 tick 都读一次时钟。按约 240ms（本地）/600ms（Shizuku）检查一次，
         * 相对 180s 的静默阈值完全够用。
         */
        private const val POLLS_PER_WATCHDOG_CHECK = 2

        /** 主日志缓冲保留的最大行数，超出后一次性裁到 [LOG_TRIM_TARGET] 行。 */
        private const val MAX_LOG_LINES = 1_200

        /**
         * CFI 分界之后的固定文案。
         *
         * 这一段载荷日志刷新极快，逐行呈现只会让人眼花；用户关心的是
         * 「已经过了最难的一关，正在收尾」，所以统一收敛成一句话。
         * 用常量而不是 `R.string` —— 这段逻辑不在 Composable 里，
         * 拿不到 `stringResource`，而这段文案又不随语言变化（root 是通用词）。
         */
        private const val LOG_TRIM_TARGET = 900

        /** 历史记录的合并写节流。 */
        private val HISTORY_SAVE_DEBOUNCE = 500.milliseconds
        private const val HISTORY_SAVE_MIN_INTERVAL_MILLIS = 500L

        /** 单次增量读取的上限、行残留缓冲上限，以及 UTF-8 序列最长字节数。 */
        private const val MAX_CHUNK_BYTES = 256 * 1024L
        private const val MAX_CARRY_CHARS = 64 * 1024
        private const val UTF8_MAX_SEQUENCE_BYTES = 4

        /** 由 UTF-8 首字节推断该字符占几个字节；非首字节返回 0。 */
        private fun utf8SequenceLength(firstByte: Int): Int = when {
            firstByte and 0x80 == 0 -> 1
            firstByte and 0xE0 == 0xC0 -> 2
            firstByte and 0xF0 == 0xE0 -> 3
            firstByte and 0xF8 == 0xF0 -> 4
            else -> 0
        }

        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val P0_OFFSET_PATTERN = Regex(
            "slide-kaslr-ok[^\\n]*slide=([0-9a-fA-F]{16})",
        )

        /**
         * P0 增量扫描的重叠窗口。单条 slide 匹配最长约百字符，512 足以
         * 覆盖"匹配恰好跨两次写入边界"的极端情况。
         */
        private const val P0_SCAN_OVERLAP = 512

        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
