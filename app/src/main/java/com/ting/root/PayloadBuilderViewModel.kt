package com.ting.root

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kernelpack.KernelPack
import com.kernelpack.PackRequest
import com.kernelpack.PackResult
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
    /** 打包时使用的基线 id（kernelpack 的 BaselineProfiles）。 */
    val baselineId: String,
    /** 产物文件名前缀。 */
    val filePrefix: String,
    /**
     * 这份载荷的**来源版本标签**。
     * 它同时会显示在界面上 —— 用户最关心的一件事就是"我打的到底是哪一份库"，
     * 所以不藏在代码里，直接跟文件名、大小、sha256 一起摊开给人看。
     */
    val versionLabel: String,
) {
    Universal(
        library = "libionstack.so",
        baselineId = "IONSTACK-P10-CP2A.260605.012",
        filePrefix = "payload-universal",
        versionLabel = "IonStack · blazer-CP2A.260605.012",
    ),
    VivoVrKo(
        library = "libbs.so",
        baselineId = "PD2520-BP2A.250605.031.A3",
        filePrefix = "payload-vivo",
        // 就是 boxiaolanya2008 仓库 release v1.3.0 里的 preload.so（176544 字节）
        versionLabel = "release v1.3.0",
    ),
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
                val baseLibrary = withContext(Dispatchers.IO) { readBaseLibrary(app, scheme) }
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
                    baseLibraryName = scheme.library,
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
                            // 显式指定基线：内置两份载荷的 sha256 都在基线表里，
                            // 但显式传入更不容易被"兜底选第一个"的旧逻辑带偏。
                            baseline = BaselineProfiles.byId(scheme.baselineId),
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
                        error = app.getString(R.string.builder_no_output),
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

    private fun readBaseLibrary(context: Context, scheme: PayloadScheme): ByteArray {
        val file = File(context.applicationInfo.nativeLibraryDir, scheme.library)
        require(file.exists()) { context.getString(R.string.error_bundled_missing) }
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
