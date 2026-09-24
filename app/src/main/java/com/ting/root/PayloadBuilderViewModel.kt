package com.ting.root

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kernelpack.KernelPack
import com.kernelpack.PackRequest
import com.kernelpack.PackResult
import com.kernelpack.policy.GateDecision
import com.kernelpack.boot.BootImageParser
import com.kernelpack.boot.KernelDecompressor
import com.kernelpack.kallsyms.KallsymsFinder
import com.kernelpack.kallsyms.KallsymsOptions
import com.kernelpack.policy.KernelSchemeSelector
import com.kernelpack.profile.BaselineScheme
import com.kernelpack.profile.BaselineRegistry
import com.kernelpack.profile.BaselineProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 构建方案：决定**拿哪一份基础动态库**去打补丁、以及按哪份基线去改写常量。
 *
 * 两份载荷是同一漏洞（CVE-2026-43499）的两个分支，差别在厂商适配：
 * - [Universal]：IonStack 上游的通用分支（Pixel/GKI），不含任何厂商绕过，
 *   靠 `rt_mutex` + `pipe_buffer` 通用链工作 —— 大部分 GKI 6.6 设备可用；
 * - [VivoVrKo]：vivo/iQOO 专用分支（`libbs.so` v1.0.0），额外做 `vr.ko` 反 root 绕过。
 *
 * 两者都靠 kernelpack 把编译期常量改写成用户 boot.img 解析出来的偏移，
 * 所以**换机型不需要重新编译**，只要基线认得出来。
 */
enum class PayloadScheme(
    /** jniLibs 里的文件名。 */
    val library: String,
    /** 产物文件名前缀。 */
    val filePrefix: String,
    /**
     * 这份载荷的**来源版本标签**。
     * 它同时会显示在界面上 —— 用户最关心的一件事就是"我打的到底是哪一份库"，
     * 所以不藏在代码里，直接跟文件名、大小、sha256 一起摊开给人看。
     */
    val versionLabel: String,
    /**
     * 这个方案在 [com.kernelpack.profile.BaselineRegistry] 里对应的方案维度。
     *
     * 为什么要显式映射：闸门要靠它去查「这台机器的四元组有没有对应基线」；
     * 不传的话闸门只能按 UNKNOWN 处理并跳过注册表建议（那就白做了）。
     */
    val baselineScheme: BaselineScheme,
) {
    /** 通用方案：Pixel / GKI 线，不含厂商绕过。 */
    Universal(
        library = "libionstack.so",
        filePrefix = "payload-universal",
        versionLabel = "IonStack · blazer-CP2A.260605.012",
        baselineScheme = BaselineScheme.UNIVERSAL,
    ),
    /** vivo / iQOO 方案：多一条 vr.ko 反 root 绕过。 */
    VivoVrKo(
        library = "libbs.so",
        filePrefix = "payload-vivo",
        // 就是 boxiaolanya2008 仓库 release v1.3.0 里的 preload.so（176544 字节）
        versionLabel = "release v1.3.0",
        baselineScheme = BaselineScheme.VIVO,
    ),
}

/**
 * 构建被**硬拦**的原因类别。
 *
 * 为什么要分类别而不是只给一段文字：P1 定的规矩是「阻断必须是弹窗、不能只是把字标红」，
 * 而不同原因的**补救动作完全不同** ——
 * ```
 *   TEST_KERNEL_DISABLED → 一键去开「5.x 内核支持（beta）」
 *   ABI_CONFLICT         → 去换基线 / boot.img
 *   OTHER                → 看日志
 * ```
 * 只传文字的话，UI 只能把一大段原文塞进弹窗，用户读完还是不知道点哪儿。
 */
enum class BuildBlockKind {
    /** 识别到 5.x 内核，但「5.x 内核支持（beta）」没开。 */
    TEST_KERNEL_DISABLED,

    /** 该 (方案, 内核系列) 组合还没有登记偏移产物（如 6.12 暂无数据）。 */
    BASELINE_NOT_REGISTERED,

    /** 基线 ABI / 强制指定与实测内核冲突。 */
    ABI_CONFLICT,

    /** 认不出内核版本。 */
    UNKNOWN_KERNEL,

    /** 其它（IO、载荷读不到等）。 */
    OTHER,
}

/**
 * 把闸门给的标题归类，供 UI 决定弹哪个阻断框。
 *
 * [为什么是顶层函数而不是 ViewModel 的成员] 它**不依赖任何实例状态** ——
 * 纯粹是"标题 → 类别"的映射。放顶层有两个好处：① 其它包的单测能直接验它
 * （成员函数要求测试同包）；② 顺带说明它没有副作用，不会有人误以为它改了状态。
 *
 * 做法是**看标题里的关键短语**而不是另起一套错误码：闸门的 Blocked 是数据类，
 * 改它的构造会影响既有调用方；而标题本身是给人看的、也稳定。
 * 归类失败一律落 [BuildBlockKind.OTHER] —— 宁可弹一个通用框，
 * 也不要瞎猜类别导致把用户引到错误的设置项上。
 */
internal fun classifyBlock(title: String): BuildBlockKind = when {
        title.contains("5.x") && (title.contains("未开启") || title.contains("支持")) ->
            BuildBlockKind.TEST_KERNEL_DISABLED
        title.contains("ABI") || title.contains("冲突") || title.contains("强制指定") ->
            BuildBlockKind.ABI_CONFLICT
        title.contains("认不出") || title.contains("无法识别") || title.contains("内核版本") ->
            BuildBlockKind.UNKNOWN_KERNEL
        else -> BuildBlockKind.OTHER
    }



/** 构建阶段。UI 只需要知道「在忙什么」与「忙完没有」。 */
enum class PayloadBuildPhase {
    Idle,
    Reading,
    Packing,
    Done,
    Failed,
    ;

    val busy: Boolean get() = this == Reading || this == Packing
}

/**
 * 载荷构建的可观察状态。
 *
 * 注意这里**不含**产物的字节：一次构建要用到几十 MB（boot.img + 内核镜像 + 10 万个符号），
 * 产物 .so 也有一百多 KB。把字节放进 StateFlow 会让每次状态刷新都携带它，
 * 也会让快照系统白白比较一遍大数组。字节留在 ViewModel 的私有字段里，
 * 通过 [PayloadBuilderViewModel.outputBytes] / [PayloadBuilderViewModel.saveAsPayload] 取。
 */
data class PayloadBuildState(
    val phase: PayloadBuildPhase = PayloadBuildPhase.Idle,
    /** 本次（或上次）构建用的方案。 */
    val scheme: PayloadScheme? = null,
    /** 已选 boot.img 的显示名与大小。 */
    val sourceName: String = "",
    val sourceSize: Long = 0,
    /** 构建过程的日志（最新的在最后）。 */
    val log: List<String> = emptyList(),
    /** 被硬拦时的原因类别；非 null 时 UI 应弹阻断框（而不是只标红）。 */
    val blockedKind: BuildBlockKind? = null,
    /** 结果摘要（多行）。 */
    val summary: String = "",
    /** 结果里的「提示」行（单独拿出来上色）。 */
    val notices: List<String> = emptyList(),
    /** 产物 .so 的名字 / 大小 / sha256。 */
    val outputName: String = "",
    val outputSize: Long = 0,
    val outputSha256: String = "",
    /** target.h 的导出名。 */
    val headerName: String = "",
    /** 本次用的基础库（文件名 / 大小 / sha256）—— 用来让用户核对"打的是哪一份库"。 */
    val baseLibraryName: String = "",
    val baseLibrarySize: Long = 0,
    val baseLibrarySha256: String = "",
    /** 产物落到系统下载目录后的可读路径（拿不到就为空）。 */
    val savedPath: String = "",
    /** 是否已经写入「自定义载荷」（构建成功时是自动写入的）。 */
    val appliedAsPayload: Boolean = false,
    val error: String? = null,
) {
    /** 正在构建：期间禁止重复触发、禁止改输入。 */
    val busy: Boolean get() = phase.busy
}

/**
 * 「载荷构建」页面的逻辑：**boot.img → 内核偏移 → 打补丁的动态库**。
 *
 * 真正的算法全部来自 `com.kernelpack`（纯 Kotlin：不依赖 Android、不开线程、
 * 不碰文件系统）—— 本类只负责三件事：读文件、切线程、把内存管好。
 *
 * ### 为什么要专门管内存
 * 一次构建的峰值大致是：boot.img 本体（几十 MB）+ 解压后的内核镜像（几十 MB）
 * + 10 万个符号对象（连同按名索引 ≈ 30 MB）。设备普通堆上限 256 MB
 * （`dalvik.vm.heapgrowthlimit`；manifest 里已开 largeHeap 提到 512 MB），
 * 所以 [build] 结束后会**立刻丢掉**所有大对象，只留下摘要字符串与产物字节。
 */
class PayloadBuilderViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow(PayloadBuildState())
    val state: StateFlow<PayloadBuildState> = mutableState.asStateFlow()

    /** 产物字节（构建成功后才非 null）。 */
    private var outputLibrary: ByteArray? = null
    private var outputHeader: String = ""
    private var outputOffsetsJson: String = ""

    /** 记住用户选的 boot.img，配置变化后不必重选。 */
    private var bootUri: Uri? = null

    fun bootImageName(): String = mutableState.value.sourceName

    fun rememberBootImage(uri: Uri, name: String, size: Long) {
        bootUri = uri
        mutableState.value = mutableState.value.copy(
            sourceName = name,
            sourceSize = size,
            error = null,
        )
    }

    fun outputBytes(): ByteArray? = outputLibrary

    fun outputHeaderText(): String = outputHeader

    fun outputOffsetsText(): String = outputOffsetsJson

    /**
     * 跑一次完整构建。整个过程都在后台线程：
     * 读文件走 IO，解析与打补丁走 Default（纯 CPU，几十秒量级）。
     */
    fun build(scheme: PayloadScheme) {
        val uri = bootUri
        val app = getApplication<Application>()
        if (uri == null) {
            mutableState.value = mutableState.value.copy(
                phase = PayloadBuildPhase.Failed,
                error = app.getString(R.string.builder_no_input),
            )
            return
        }
        if (mutableState.value.phase.busy) return

        mutableState.value = PayloadBuildState(
            phase = PayloadBuildPhase.Reading,
            sourceName = mutableState.value.sourceName,
            sourceSize = mutableState.value.sourceSize,
        )
        outputLibrary = null
        outputHeader = ""
        outputOffsetsJson = ""

        viewModelScope.launch {
            val lines = ArrayList<String>(256)
            var pending = 0
            // 日志回调是在解析线程里同步调用的，逐行刷新会让 UI 每秒重组上百次；
            // 攒够几行再推一次，观感上仍然是「实时滚动」。
            fun publish(line: String) {
                lines.add(line)
                pending++
                if (pending >= 6) {
                    pending = 0
                    mutableState.value = mutableState.value.copy(log = lines.takeLast(MAX_LOG_LINES))
                }
            }

            try {
                publish(app.getString(R.string.builder_phase_reading, mutableState.value.sourceName))
                val bootBytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error(app.getString(R.string.builder_read_failed))
                }
                // ── 构建前：先检查内核版本，再决定用哪套方案 ──────────────
                // 这一步刻意放在**打补丁之前**：认不出内核版本、或者 5.x 支持没开时，
                // 应该**零成本**就停下，而不是等把 boot.img 解完、补丁算完才说不行。
                // （KernelPack 内部还有一道同源硬闸兜底 —— 宁可拦两次，也不能有入口绕过。）
                val kernelRelease = withContext(Dispatchers.Default) {
                    val parsed = BootImageParser.parse(bootBytes, KernelDecompressor.default)
                    parsed.diagnosis?.let { publish("[!] 解析诊断: $it") }
                    KallsymsFinder(parsed.image, KallsymsOptions()).run().versionNumber
                }
                publish(app.getString(R.string.builder_detected_kernel, kernelRelease))
                val decision = KernelSchemeSelector.select(
                    kernelRelease = kernelRelease,
                    allowTestKernel = AppPreferences.allowTestKernel(app),
                    // 「强制指定」也要在**这里**就参与判定：不一致时零成本停下，
                    // 而不是等 boot.img 解完、补丁算完才被内部闸门拒绝。
                    override = AppPreferences.kernelSeriesOverride(app),
                )
                publish("[*] " + KernelSchemeSelector.describe(decision))
                if (decision is KernelSchemeSelector.Decision.Blocked) {
                    // 前置判定拒绝：不进打包流程，直接把原因给用户
                    publish("[X] " + decision.title)
                    decision.detail.forEach { publish("    $it") }
                    publish("    → " + decision.remedy)
                    mutableState.value = mutableState.value.copy(
                        phase = PayloadBuildPhase.Failed,
                        error = decision.title + " " + decision.remedy,
                        log = lines.takeLast(MAX_LOG_LINES),
                        blockedKind = classifyBlock(decision.title),
                    )
                    return@launch
                }
                val selected = decision as KernelSchemeSelector.Decision.Selected
                selected.notes.forEach { publish("    $it") }

                // ── 按 (方案, 内核系列) 路由到具体载荷档位 ──
                // 这是"两个方案 × 两个主线系列"的唯一路由点。取不到就是**真的没有**
                // 该组合的偏移产物 —— 必须在这里停下并说清楚，绝不能回退到别的系列。
                // 三级路由：**先按完整内核串**，再小版本，最后退回大系列。
                // 不传 release 的话，按小版本登记的上游 50 档永远选不中。
                val profileId = BaselineRegistry.profileIdFor(
                    scheme.baselineScheme,
                    selected.series,
                    selected.release,
                )
                if (profileId == null) {
                    val msg = "还没有为「${scheme.baselineScheme.label} × 内核 ${selected.series}」" +
                        "登记偏移产物，本次不打包（不会用别的系列顶替）"
                    publish("[X] $msg")
                    publish("    主线需要覆盖 6.6 与 6.12；缺的那一档要拿到**为该内核编译的载荷 .so**")
                    publish("    或上游对应机型的 target.h 才能登记，不能用别的内核的数值凑。")
                    mutableState.value = mutableState.value.copy(
                        phase = PayloadBuildPhase.Failed,
                        error = msg,
                        log = lines.takeLast(MAX_LOG_LINES),
                        blockedKind = BuildBlockKind.BASELINE_NOT_REGISTERED,
                    )
                    return@launch
                }

                val baseLibrary = withContext(Dispatchers.IO) {
                    readBaseLibrary(app, scheme, selected.release)
                }
                publish(
                    app.getString(
                        R.string.builder_loaded,
                        bootBytes.size / 1024,
                        baseLibrary.size / 1024,
                    ),
                )
                val baseSha = withContext(Dispatchers.Default) { sha256Hex(baseLibrary) }
                mutableState.value = mutableState.value.copy(
                    scheme = scheme,
                    baseLibraryName = lastBaseLibraryName ?: scheme.library,
                    baseLibrarySize = baseLibrary.size.toLong(),
                    baseLibrarySha256 = baseSha,
                )
                mutableState.value = mutableState.value.copy(phase = PayloadBuildPhase.Packing)

                // 峰值内存就出现在下面这一行：解析 + 打补丁都在里面完成。
                val result = withContext(Dispatchers.Default) {
                    KernelPack.pack(
                        PackRequest(
                            bootImage = bootBytes,
                            baseLibrary = baseLibrary,
                            // 显式指定基线。
                            //
                            // [2026-09-25 修] 原来查的是 [BaselineProfiles.byId] ——
                            // 那个表**只有 2 份手写档**（PD2520 / IONSTACK_P10，都是 6.6）。
                            // 上游那 50 档的 profile 是在 [BaselineRegistry] 里**就地构造**的，
                            // 没登记进去，于是 byId 查不到 → 上层兜底回落到 PD2520（6.6）
                            // → 拿 6.6 的 ABI 去对 6.1 的 boot.img → **误报"基线 ABI 冲突"**，
                            // 用户明明有 6.1 基线却被告知不匹配。
                            //
                            // [BaselineRegistry.byId] 搜的是 allEntries（手写 + 上游 + 蓝厂派生），
                            // 与三级路由用的是同一张表 —— 路由指向哪一档，这里就取到哪一档。
                            baseline = BaselineRegistry.byId(profileId)?.profile,
                            // 设置页的"强制指定内核系列"与"忽略冲突"：默认 AUTO + 不放行，
                            // 也就是**默认按实测走、冲突即拒绝**。
                            seriesOverride = AppPreferences.kernelSeriesOverride(app),
                            allowAbiMismatch = AppPreferences.allowAbiMismatch(app),
                            // 「5.x 内核支持（beta）」：关着时闸门会拒绝 5.x 构建并引导去开。
                            allowTestKernel = AppPreferences.allowTestKernel(app),
                            // 方案维度也要传：闸门靠它查四元组注册表
                            scheme = scheme.baselineScheme,
                            log = { publish(it) },
                        ),
                    )
                }

                val packed = result.packedLibrary
                val summary = buildSummary(app, result)
                val notices = result.warnings
                val header = result.targetHeader
                val offsetsJson = result.offsetsJson
                result.patchReport?.let { report ->
                    publish(
                        app.getString(
                            R.string.builder_patch_done,
                            report.outcomes.count { it.changed },
                            report.outcomes.sumOf { it.sitesPatched + it.dataLiteralsPatched },
                        ),
                    )
                }

                if (packed == null) {
                    outputHeader = header
                    outputOffsetsJson = offsetsJson
                    mutableState.value = mutableState.value.copy(
                        phase = PayloadBuildPhase.Failed,
                        log = lines.takeLast(MAX_LOG_LINES),
                        summary = summary,
                        notices = notices,
                        // 闸门拒绝时把**具体原因**上屏：gate.blocked 与"没产物"是两回事，
                        // 混成一句"没有产物"会让用户根本不知道该改什么。
                        error = (result.gate as? GateDecision.Blocked)?.let { blocked ->
                            blocked.title + "\n" + blocked.detail.joinToString("\n") { "· $it" } +
                                "\n\n" + blocked.remedy
                        } ?: app.getString(R.string.builder_no_output),
                        // 内部闸门拦下时同样要给出类别，UI 才能弹对应的阻断框
                        blockedKind = (result.gate as? GateDecision.Blocked)
                            ?.let { classifyBlock(it.title) },
                    )
                    return@launch
                }

                val sha256 = withContext(Dispatchers.Default) { sha256Hex(packed) }
                outputLibrary = packed
                outputHeader = header
                outputOffsetsJson = offsetsJson
                publish(app.getString(R.string.builder_output_ready, packed.size / 1024))

                // 自动落盘到系统下载目录（MediaStore，不需要任何存储权限），
                // 并把它设为「自定义动态库」——用户点完「开始构建」就不该再手动搬文件。
                val fileName = outputFileName()
                val saved = withContext(Dispatchers.IO) {
                    runCatching { DownloadStore.save(app, fileName, packed) }.getOrNull()
                }
                if (saved != null) {
                    publish(app.getString(R.string.builder_saved_download, saved))
                } else {
                    publish(app.getString(R.string.builder_save_download_failed))
                }
                val applied = withContext(Dispatchers.IO) {
                    runCatching {
                        CustomPayloadStore.save(app, packed, fileName).also {
                            AppPreferences.setPayloadSource(app, PayloadSource.Custom)
                        }
                    }.getOrNull()
                }
                if (applied != null) {
                    publish(app.getString(R.string.builder_applied, applied.displayName))
                }
                mutableState.value = mutableState.value.copy(
                    phase = PayloadBuildPhase.Done,
                    log = lines.takeLast(MAX_LOG_LINES),
                    summary = summary,
                    notices = notices,
                    outputName = fileName,
                    outputSize = packed.size.toLong(),
                    outputSha256 = sha256,
                    savedPath = saved.orEmpty(),
                    appliedAsPayload = applied != null,
                )
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(
                    phase = PayloadBuildPhase.Failed,
                    log = lines.takeLast(MAX_LOG_LINES),
                    error = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    /**
     * 把产物写进「自定义载荷」（并切成当前载荷源）。
     * 成功后回调 [onApplied]，失败则把错误写进 state。
     */
    fun saveAsPayload(onApplied: (CustomPayloadInfo) -> Unit) {
        val bytes = outputLibrary ?: return
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    CustomPayloadStore.save(
                        getApplication(),
                        bytes,
                        mutableState.value.outputName.ifBlank { "libbs-patched.so" },
                    )
                }
                AppPreferences.setPayloadSource(getApplication(), PayloadSource.Custom)
                mutableState.value = mutableState.value.copy(appliedAsPayload = true, error = null)
                onApplied(info)
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(
                    error = error.message
                        ?: getApplication<Application>().getString(R.string.custom_import_failed),
                )
            }
        }
    }

    private fun buildSummary(app: Application, result: PackResult): String = buildString {
        val analysis = result.analysis
        appendLine(
            app.getString(R.string.builder_kernel, analysis.versionNumber, analysis.architecture),
        )
        appendLine(
            app.getString(R.string.builder_base, "0x" + java.lang.Long.toHexString(analysis.baseAddress)),
        )
        appendLine(app.getString(R.string.builder_symbols, analysis.symbols.size))
        appendLine(
            app.getString(
                R.string.builder_offsets,
                result.profile.offsets.count { it.value.resolved },
                result.profile.offsets.size,
            ),
        )
        result.baseline?.let { appendLine(app.getString(R.string.builder_baseline, it.variantLabel)) }
        appendLine(app.getString(R.string.builder_variant, result.profile.variantLabel))
        result.patchReport?.let { report ->
            // 这里用 changed / ok 而不是「站点数」：同一台机器上跑出来的旧值本来就等于新值，
            // 那时 .so 一个字节都不用改（产物与原件逐字节相同），
            // 只有换了别的机型/内核才真的会改写。分开报才不会让人以为"它改了什么"。
            val rewritten = report.outcomes.count { it.changed }
            appendLine(
                app.getString(
                    R.string.builder_patch_stats,
                    rewritten,
                    report.outcomes.count { it.ok },
                ),
            )
            if (rewritten == 0) appendLine(app.getString(R.string.builder_patch_noop))
            if (report.untouchedKeys.isNotEmpty()) {
                appendLine(app.getString(R.string.builder_patch_absent, report.untouchedKeys.size))
            }
        }
    }

    /**
     * 清掉"被拦下"的状态（用户关掉阻断框时调用）。
     *
     * 只清 [PayloadBuildState.blockedKind] 与 error，**不动日志** ——
     * 用户可能正要看日志里那几行原始原因，把日志一起抹掉等于把证据删了。
     */
    fun clearBlock() {
        mutableState.value = mutableState.value.copy(blockedKind = null, error = null)
    }

    /** 上一次实际读到的基线库名（可能因结构体族而与 scheme.library 不同）。 */
    private var lastBaseLibraryName: String? = null

    private fun readBaseLibrary(
        context: Context,
        scheme: PayloadScheme,
        kernelRelease: String? = null,
    ): ByteArray {
        // 按**结构体族**选库：6.1/6.12 用自编基线，6.6 用方案原有的那份。
        // 不做这一步的话，6.1/6.12 会拿到 6.6 族的 .so —— 结构体偏移是错的，
        // 而那是编译期烤死的、patch 改不回来。
        val libName = com.kernelpack.profile.BaselineRegistry.BaselineLibraries
            .resolve(scheme.library, kernelRelease)
        val file = File(context.applicationInfo.nativeLibraryDir, libName)
        require(file.exists()) { context.getString(R.string.error_bundled_missing) }
        lastBaseLibraryName = libName
        return file.readBytes()
    }

    private fun outputFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val prefix = mutableState.value.scheme?.filePrefix ?: "payload"
        return "$prefix-$stamp.so"
    }

    companion object {
        private const val MAX_LOG_LINES = 400

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val builder = StringBuilder(digest.size * 2)
            for (byte in digest) {
                builder.append(Character.forDigit((byte.toInt() shr 4) and 0xf, 16))
                builder.append(Character.forDigit(byte.toInt() and 0xf, 16))
            }
            return builder.toString()
        }
    }
}
