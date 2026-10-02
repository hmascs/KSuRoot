package com.ting.root

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kernelpack.boot.RomPackageExtractor
import com.kernelpack.KernelPack
import com.kernelpack.PackRequest
import com.kernelpack.PackResult
import com.kernelpack.policy.GateDecision
import com.kernelpack.boot.BootImageParser
import com.kernelpack.boot.KernelDecompressor
import com.kernelpack.kallsyms.KallsymsFinder
import com.kernelpack.kallsyms.KallsymsOptions
import com.kernelpack.ota.OtaPayloadExtractor
import com.kernelpack.policy.KernelSchemeSelector
import com.kernelpack.profile.BaselineScheme
import com.kernelpack.profile.BaselineRegistry
import com.kernelpack.profile.BaselineProfiles
import com.kernelpack.vivo.VrKoBypass
import com.kernelpack.vivo.VrKoGateDecision
import com.kernelpack.vivo.VrKoProbe
import com.kernelpack.vivo.VrKoTagPatcher
import com.kernelpack.vivo.VrKoPayloadGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /**
     * 这个方案**是否必须提供 `vendor_boot.img`**。
     *
     * ### 为什么只有蓝厂方案要
     *
     * 蓝厂方案的核心是多做一层 **vr.ko 反 root 绕过**：`vr.ko` 会给来自 app 的 task
     * 打两个标记字节，该 task 一旦持有 `euid 0`，`sys_exit` 探针就把它杀掉。
     * 要抹掉那两个字节，就得知道**它们在哪** —— 而那个偏移是**编译期烤进载荷**的。
     *
     * 问题在于：实测它有 `0x04` / `0x06` 两种取值，**同一台设备的两份 `vr.ko` 就各一种**。
     * 选错的表现是"清错了字节、标记没抹掉、而自检报成功" —— 提权看着成功，子进程随即被杀，
     * 从现象上根本看不出来是这个原因。
     *
     * 那台机器**实际加载的那份 `vr.ko`** 就在 `vendor_boot.img` 的 vendor_ramdisk 里。
     * 读它 = 把这个值定死，而不是赌。所以蓝厂方案要这份镜像。
     *
     * ### 通用方案为什么不要
     *
     * 通用方案**不做 vr.ko 绕过**（`VrKoPayloadGate` 在非蓝厂方案下直接短路，
     * 连 ELF 都不解析），也就没有这个偏移要定 —— 给它要 `vendor_boot` 纯属多余。
     */
    val needsVendorBoot: Boolean,
) {
    /** 通用方案：Pixel / GKI 线，不含厂商绕过。 */
    Universal(
        library = "libionstack.so",
        filePrefix = "payload-universal",
        versionLabel = "IonStack · blazer-CP2A.260605.012",
        baselineScheme = BaselineScheme.UNIVERSAL,
        needsVendorBoot = false,
    ),
    /** vivo / iQOO 方案：多一条 vr.ko 反 root 绕过。 */
    VivoVrKo(
        library = "libbs.so",
        filePrefix = "payload-vivo",
        // 就是 boxiaolanya2008 仓库 release v1.3.0 里的 preload.so（176544 字节）
        versionLabel = "release v1.3.0",
        baselineScheme = BaselineScheme.VIVO,
        needsVendorBoot = true,
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

    /**
     * 蓝厂方案没给 `vendor_boot.img`。
     *
     * 为什么这算"阻断"而不是"警告"：那个标记偏移只有 `0x04` / `0x06` 两种，
     * 赌一个就是 50% 概率产出**看起来正常、实际抹不掉标记**的载荷 ——
     * 而它的失败现象（提权成功、子进程随即被杀）从日志上完全看不出原因。
     * 与其给一份可能是错的，不如当场说清楚要什么。
     */
    VENDOR_BOOT_REQUIRED,

    /** 给了 `vendor_boot.img`，但里面读不出这台机器的 vr.ko（格式/内容不符）。 */
    VENDOR_BOOT_UNREADABLE,

    /** 读到了本机 tag A，但没能把它改写到载荷里（锚点找不到 / 载荷不自洽）。 */
    VR_KO_TAG_PATCH_FAILED,

    /** 该 (方案, 内核系列) 组合还没有登记偏移产物（如 6.12 暂无数据）。 */
    BASELINE_NOT_REGISTERED,

    /** 基线 ABI / 强制指定与实测内核冲突。 */
    ABI_CONFLICT,

    /** 认不出内核版本。 */
    UNKNOWN_KERNEL,

    /**
     * 蓝厂方案下，这份载荷**没有** `vr.ko` 反 root 绕过。
     *
     * 这是**唯一**一类"载荷本身不合格"的阻断：别的几类都是"这台机器的数据不够 /
     * 设置没开"，换一份载荷就好的是这一类。所以在 UI 上必须给"换一份"的指引，
     * 而不是像 [BASELINE_NOT_REGISTERED] 那样只能说"知道了"。
     */
    VIVO_VR_KO_MISSING,

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
        title.contains("vr.ko") || title.contains("vr ko") ->
            BuildBlockKind.VIVO_VR_KO_MISSING
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
    /**
     * 已选 vendor_boot.img 的显示名与大小。
     *
     * **只有蓝厂方案要它** —— 通用方案不做 vr.ko 绕过，也就没有那个偏移要定。
     * 为什么不在这里解释"为什么要"：那段说明放在界面上（用户看得见的地方），
     * 而不是埋在数据类里。见 `builder_vendor_boot_why` 那条文案。
     */
    val vendorBootName: String = "",
    val vendorBootSize: Long = 0,
    /** 从 vendor_boot 里探到的本机 tag A（`0x04` / `0x06`）；没探到就是 null。 */
    val probedTagA: Long? = null,
    /** 探针结论一句话（日志与界面共用）。 */
    val probeSummary: String = "",
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
 * 「解析完整包链接」的可观察状态。
 *
 * 与 [PayloadBuildState] 分开：这两件事**可以同时发生**（构建在跑的时候
 * 用户完全可以再去解一个链接），共用一个状态机会互相踩。
 */
data class OtaLinkState(
    val running: Boolean = false,
    /** 用户填的链接；对话框重开时回填。 */
    val url: String = "",
    /** 解析过程的日志（最新的在最后）。 */
    val log: List<String> = emptyList(),
    /** 最近一次失败的说明。 */
    val error: String? = null,
    /** 最近一次成功解出的镜像：显示名与大小。 */
    val resultName: String = "",
    val resultSize: Long = 0,
)

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
    private var vendorBootUri: Uri? = null
    private var vendorBootFile: File? = null

    /**
     * 输入也可以是一个**本地文件**而不是 `content://` URI ——
     * 「解析完整包链接」解出来的镜像就在应用私有目录里，没有对应的 content URI。
     *
     * 两者**互斥**：谁最后被设置，谁就是本次输入。留着一个指向旧输入的字段
     * 迟早会出现"界面上写着 A、实际构建用的是 B"。
     */
    private var bootFile: File? = null

    private val mutableOtaState = MutableStateFlow(OtaLinkState())
    val otaState: StateFlow<OtaLinkState> = mutableOtaState.asStateFlow()
    private var otaJob: Job? = null

    fun bootImageName(): String = mutableState.value.sourceName

    fun rememberBootImage(uri: Uri, name: String, size: Long) {
        bootUri = uri
        bootFile = null
        mutableState.value = mutableState.value.copy(
            sourceName = name,
            sourceSize = size,
            error = null,
        )
    }

    /** 记住一个**本地文件**作为输入（「解析完整包链接」的产物走这条路）。 */
    fun rememberBootImageFile(file: File, name: String, size: Long) {
        bootFile = file
        bootUri = null
        mutableState.value = mutableState.value.copy(
            sourceName = name,
            sourceSize = size,
            error = null,
        )
    }

    /**
     * 记住用户选的 `vendor_boot.img`（**蓝厂方案专用**）。
     *
     * 载荷里 vr.ko 的 tag A 偏移是**编译期烤死**的，而实测它有 `0x04` / `0x06` 两种取值 ——
     * 同一台设备的两份 `vr.ko` 就各一种。选错就是"清错字节、标记没抹掉、而自检报成功"
     * 这种最难查的失败。`vendor_boot` 里装着这台机器**实际加载的那份 vr.ko**（平铺的
     * `lib/modules/vr.ko`），读它就能把值定死，再由 [com.kernelpack.vivo.VrKoTagPatcher] 改写载荷。
     */
    fun rememberVendorBootImage(uri: Uri, name: String, size: Long) {
        vendorBootUri = uri
        vendorBootFile = null
        mutableState.value = mutableState.value.copy(
            vendorBootName = name,
            vendorBootSize = size,
            error = null,
        )
    }

    /** 同 [rememberVendorBootImage]，但输入已经是本地文件（整包导入的产物走这条）。 */
    fun rememberVendorBootFile(file: File, name: String, size: Long) {
        vendorBootFile = file
        vendorBootUri = null
        mutableState.value = mutableState.value.copy(
            vendorBootName = name,
            vendorBootSize = size,
            error = null,
        )
    }

    // ────────────────────────── 导入完整刷机包 ──────────────────────────

    /**
     * 用户选了一个**完整刷机包**，直接从里面取出 boot / vendor_boot。
     *
     * ### 为什么必须先拷到应用缓存
     *
     * 文件选择器给的是 `content://` uri，而 [RomPackageExtractor] 的接口收 `File`
     * （它内部用 `RandomAccessFile` 随机读，ZIP64 中央目录在包尾，非随机访问做不了）。
     * Android 上 **SAF 的 uri 没办法变成 File** —— 所以只能整体拷一份。
     *
     * ⚠️ **这个代价是真的**：完整包动辄 4–8 GiB，拷一份就要那么多缓存空间、也要时间。
     * 所以：
     *  - 拷之前先查可用空间，不够就**当场拒绝**并说清楚差多少，不做半截拷贝；
     *  - 拷完立刻抽分区，抽完**马上删掉缓存**（失败路径也删）；
     *  - 日志里如实写出"正在拷贝 N GiB"以及为什么。
     *
     * 如果哪天 [RomPackageExtractor] 支持了"给一个可随机读的流"，
     * 这一整段拷贝就可以去掉 —— 那才是这个功能该有的样子。
     */
    fun importRomPackage(uri: Uri, name: String, size: Long) {
        if (mutableState.value.phase.busy) return
        mutableState.value = mutableState.value.copy(
            phase = PayloadBuildPhase.Reading,
            log = emptyList(),
            error = null,
            blockedKind = null,
        )
        viewModelScope.launch {
            val lines = ArrayList<String>(64)
            fun say(line: String) {
                lines.add(line)
                mutableState.value = mutableState.value.copy(log = lines.toList())
            }
            val cache = File(getApplication<Application>().cacheDir, "rompack")
            var staged: File? = null
            try {
                cache.mkdirs()
                staged = File(cache, "package.tmp")

                // ① 先查空间，不够就别开始 —— 拷到一半失败比不拷更让人困惑。
                if (size > 0) {
                    val usable = cache.usableSpace
                    if (usable < size + (64L * 1024 * 1024)) {
                        error(
                            "缓存空间不够：这个包 %.1f GiB，缓存只剩 %.1f GiB。"
                                .format(size / 1073741824.0, usable / 1073741824.0) +
                                "完整包要先整份拷进缓存才能随机读（ZIP 的中央目录在包尾）。",
                        )
                    }
                }

                say("[*] 正在把 $name 拷进缓存（%.1f GiB）—— 完整包要整份拷进来才能随机读".format(size / 1073741824.0))
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "读不到选中的文件" }
                        staged.outputStream().use { output ->
                            input.copyTo(output, 1 shl 20)
                            output.fd.sync()
                        }
                    }
                }
                say("[+] 拷完：${staged.length() / 1048576} MiB")

                // ② 取分区。要**两个都对账** —— extract 的语义是"取不到的键不出现"，
                // 所以不能拿"拿到了一个"当成"两个都拿到了"。
                val found = withContext(Dispatchers.IO) {
                    RomPackageExtractor.extract(staged, setOf("boot", "vendor_boot"))
                }
                val boot = found["boot"]
                val vendorBoot = found["vendor_boot"]
                if (boot == null) {
                    val have = withContext(Dispatchers.IO) {
                        runCatching { RomPackageExtractor.listPartitions(staged) }.getOrDefault(emptyList())
                    }
                    error("这个包里没有 boot 分区。包里的分区有：${have.take(30).joinToString(", ")}")
                }
                say("[+] boot：${boot.size / 1048576} MiB")
                withContext(Dispatchers.IO) { rememberBootImageBytes(boot, "boot.img") }

                if (vendorBoot != null) {
                    say("[+] vendor_boot：${vendorBoot.size / 1048576} MiB")
                    withContext(Dispatchers.IO) { rememberVendorBootBytes(vendorBoot, "vendor_boot.img") }
                } else {
                    say("[i] 这个包里没有 vendor_boot —— 通用方案不需要它；蓝厂方案要另选一份。")
                }
                say("[i] 从完整包里取好了，不用你自己解压。")
            } catch (e: Throwable) {
                lines.add("[X] ${e.message ?: e::class.java.simpleName}")
                mutableState.value = mutableState.value.copy(
                    log = lines.toList(),
                    phase = PayloadBuildPhase.Failed,
                    error = e.message ?: e::class.java.simpleName,
                )
            } finally {
                // ③ 不管成没成，缓存立刻删 —— 它可能有几个 GiB。
                runCatching { staged?.delete() }
            }
        }
    }

    /** 把内存里的镜像字节落成一个临时文件，再交给既有的"本地文件输入"路径。 */
    private fun rememberBootImageBytes(bytes: ByteArray, name: String) {
        val f = writeTempImage(bytes, "rom_boot.img")
        rememberBootImageFile(f, name, bytes.size.toLong())
    }

    private fun rememberVendorBootBytes(bytes: ByteArray, name: String) {
        val f = writeTempImage(bytes, "rom_vendor_boot.img")
        rememberVendorBootFile(f, name, bytes.size.toLong())
    }

    private fun writeTempImage(bytes: ByteArray, fileName: String): File {
        val dir = File(getApplication<Application>().filesDir, "builder_input")
        dir.mkdirs()
        val f = File(dir, fileName)
        f.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        return f
    }

    // ────────────────────────── 解析完整包链接 ──────────────────────────

    /** 用户改了链接输入框 / 关掉错误提示时调用。 */
    fun clearOtaError() {
        if (mutableOtaState.value.error != null) {
            mutableOtaState.value = mutableOtaState.value.copy(error = null)
        }
    }

    fun cancelOtaParse() {
        otaJob?.cancel()
        otaJob = null
        mutableOtaState.value = mutableOtaState.value.copy(running = false)
    }

    /**
     * 解析一个**完整包链接**，把里面的 boot 镜像解出来并设为构建输入。
     *
     * 只接受 `http` / `https`。整个过程靠 HTTP `Range` 请求**只取需要的那几块**
     * —— 完整包 4–8 GiB，全下下来在手机上是不现实的。
     *
     * 成功之后**不在这里预校验**镜像是否可用：那是 [build] 的职责，
     * 它已经有完整的格式识别与内核版本判定。在这里再判一次要么是重复劳动，
     * 要么是拿半个数组做判断 —— 后者会给出**错的**结论。
     */
    fun parseOtaLink(rawUrl: String) {
        val app = getApplication<Application>()
        val url = rawUrl.trim()
        if (otaJob?.isActive == true) return
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            mutableOtaState.value = OtaLinkState(
                url = url, error = app.getString(R.string.builder_ota_bad_url),
            )
            return
        }
        otaJob = viewModelScope.launch {
            mutableOtaState.value = OtaLinkState(running = true, url = url)
            val lines = ArrayList<String>(64)
            var pending = 0
            fun publish(line: String) {
                lines.add(line)
                pending++
                if (pending >= 3) {
                    pending = 0
                    mutableOtaState.value =
                        mutableOtaState.value.copy(log = lines.takeLast(MAX_LOG_LINES))
                }
            }
            try {
                val dir = otaWorkDir(app)
                withContext(Dispatchers.IO) { purgeStaleOtaFiles(dir, bootFile) }
                publish(app.getString(R.string.builder_ota_started))
                val result = OtaPayloadExtractor.extractPartitions(url, dir) { publish(it) }
                val file = result.bootFile
                val size = file.length()
                rememberBootImageFile(file, file.name, size)
                mutableOtaState.value = OtaLinkState(
                    url = url,
                    log = lines.takeLast(MAX_LOG_LINES),
                    resultName = file.name,
                    resultSize = size,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableOtaState.value = mutableOtaState.value.copy(
                    running = false,
                    log = lines.takeLast(MAX_LOG_LINES),
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    private fun otaWorkDir(app: Application): File = File(app.cacheDir, OTA_CACHE_DIR)

    /**
     * 清掉上一次解析留下的镜像。
     *
     * boot 镜像动辄 96 MiB，留在 `cacheDir` 里会一直堆到系统来清 ——
     * 而系统清理是不打招呼的，用户下次点构建只会看到"文件不见了"。
     * 所以这里**主动**只留一份，并且跳过当前正在用的那一份。
     */
    private fun purgeStaleOtaFiles(dir: File, keep: File?) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.name.startsWith(OTA_FILE_PREFIX)) continue
            if (keep != null && f.absolutePath == keep.absolutePath) continue
            f.delete()
        }
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
        val file = bootFile
        val app = getApplication<Application>()
        if (uri == null && file == null) {
            mutableState.value = mutableState.value.copy(
                phase = PayloadBuildPhase.Failed,
                error = app.getString(R.string.builder_no_input),
            )
            return
        }
        if (mutableState.value.phase.busy) return

        // ⚠️ 这里**必须用 copy，不能构造新的 PayloadBuildState**。
        //
        // 踩过的坑：原来写的是 `PayloadBuildState(phase=…, sourceName=…, sourceSize=…)` ——
        // 只传了 3 个参数，其余全部取**默认值**，于是 `vendorBootName` 被打回 `""`。
        // 而下面那道「蓝厂方案要 vendor_boot」的闸门正是拿 `vendorBootName` 判的，
        // 结果**恒为真** → 蓝厂方案 100% 被自己的闸门拦死，用户选完 vendor_boot
        // 再点构建还是被拦（死循环）。本轮新写的 VrKoProbe / VrKoTagPatcher
        // 因此**在生产路径上一行都跑不到**。
        //
        // 所以：保留输入（sourceName/Size、vendorBootName/Size），
        // 只清上一轮的产物、日志与阻断结论。
        mutableState.value = mutableState.value.copy(
            phase = PayloadBuildPhase.Reading,
            log = emptyList(),
            summary = "",
            notices = emptyList(),
            blockedKind = null,
            error = null,
            probedTagA = null,
            probeSummary = "",
            outputName = "",
            outputSize = 0,
            outputSha256 = "",
            headerName = "",
            savedPath = "",
            appliedAsPayload = false,
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
                    when {
                        // 「解析完整包链接」的产物在应用私有目录里，直接读文件。
                        // 不走 contentResolver：`file://` 在部分 ROM 上会被直接拒掉，
                        // 而那个失败信息对用户毫无意义。
                        file != null -> {
                            if (!file.isFile) error(app.getString(R.string.builder_input_gone))
                            file.readBytes()
                        }

                        uri != null -> app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error(app.getString(R.string.builder_read_failed))

                        else -> error(app.getString(R.string.builder_no_input))
                    }
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
                    // 区分两种"没有"，因为它们要的下一步动作完全不同：
                    //  · 随包**有**这一族的基线库 → 缺的是「登记」（符号值要逐项核实），不是库；
                    //  · 连库都没有 → 缺的是产物本身，得先拿到为该内核编译的 .so 或 target.h。
                    // 原来两种情况共用一句"还没有登记偏移产物"，用户会去翻仓库找 .so，
                    // 而其实 .so 就在包里。
                    val familyLib = BaselineRegistry.BaselineLibraries
                        .familyOf(selected.release)
                        ?.let { BaselineRegistry.BaselineLibraries.forFamily(it) }
                        .orEmpty()
                    if (familyLib.isNotEmpty()) {
                        publish("    注意：随包**有** ${selected.series} 族的基线库 $familyLib，")
                        publish("    但它**没有登记编译期符号值** —— 缺的是登记，不是库。")
                        publish("    符号旧值必须从该 .so 里逐项核实后才能登记；拿没核实过的值去改字节，")
                        publish("    产物会**静默**保留旧值（既不报错也不 manifest 成失败），装机后才炸。")
                        publish("    所以这里如实报缺，不替你猜一组数值。")
                    } else {
                        publish("    主线需要覆盖 6.6 与 6.12；缺的那一档要拿到**为该内核编译的载荷 .so**")
                        publish("    或上游对应机型的 target.h 才能登记，不能用别的内核的数值凑。")
                    }
                    mutableState.value = mutableState.value.copy(
                        phase = PayloadBuildPhase.Failed,
                        error = msg,
                        log = lines.takeLast(MAX_LOG_LINES),
                        blockedKind = BuildBlockKind.BASELINE_NOT_REGISTERED,
                    )
                    return@launch
                }

                var baseLibrary = withContext(Dispatchers.IO) {
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

                // ── ★ 库与档位必须同源 ──────────────────────────────────────
                //
                // 「选哪份库」是按**结构体族**走的，「选哪一档」是按**三级路由**（内核串）走的。
                // 两条路独立，于是会选岔：X100 Pro 6.1.145 上路由命中了上游档
                // `up-6-1-145-…`（旧值来自 GhostLock 的 offsets.h），而库是自编的
                // `libbaseline_6_1.so`（旧值来自 baseline-6.1-tokay/target.h）。
                // 两套旧值毫无交集 → 26 项**一处都没匹配上**，产物等于原样拷贝。
                //
                // 所以这里以**库**为准：自编族基线必须配它自己的那一档。
                // 上游 `up-*` 档只适用于上游那份 .so。
                val loadedLibraryName = lastBaseLibraryName ?: scheme.library
                val ownProfileId = BaselineRegistry.BaselineLibraries
                    .profileIdForLibrary(loadedLibraryName)
                val effectiveProfileId = if (ownProfileId != null && ownProfileId != profileId) {
                    publish("[i] 基础库 $loadedLibraryName 是**自编族基线**，旧值就烤在它里面。")
                    publish("    档位从 $profileId 改用与它同源的 $ownProfileId ——")
                    publish("    否则档位里的旧值在这份 .so 里一个都找不到，产物会等于原样拷贝。")
                    ownProfileId
                } else {
                    profileId
                }

                mutableState.value = mutableState.value.copy(
                    scheme = scheme,
                    baseLibraryName = lastBaseLibraryName ?: scheme.library,
                    baseLibrarySize = baseLibrary.size.toLong(),
                    baseLibrarySha256 = baseSha,
                )
                // ── 蓝厂方案专属闸门：这份载荷到底带没带 vr.ko 抹标记 ──
                //
                // 为什么必须在**构建阶段**拦，而不是装上去再说：抹标记是往内核内存写，
                // 只有载荷 C 侧那套 pipe_phys_write_data / pipe_write64 原语做得到，
                // Kotlin 侧没有这个原语（见 com.kernelpack.vivo.VrKoBypass 的类注释）。
                // 所以"这份载荷带没带绕过"是**装之前唯一查得了的事** —— 不查，
                // 在带 vr.ko 的机器上就会得到"提权成功、子进程随即被 sys_exit 探针杀掉"。
                //
                // 通用方案**不触发**这条检查：VrKoPayloadGate 在 scheme != VIVO 时
                // 直接短路返回 NotApplicable，连 ELF 都不解析。
                val vrDecision = withContext(Dispatchers.Default) {
                    VrKoPayloadGate.decide(
                        scheme = scheme.baselineScheme,
                        libraryName = lastBaseLibraryName ?: scheme.library,
                        bytes = baseLibrary,
                    )
                }
                if (vrDecision is VrKoGateDecision.Blocked) {
                    publish("[X] ${vrDecision.title}")
                    vrDecision.detail.forEach { publish("    $it") }
                    publish("    ${vrDecision.remedy}")
                    mutableState.value = mutableState.value.copy(
                        phase = PayloadBuildPhase.Failed,
                        error = (listOf(vrDecision.title) + vrDecision.detail + vrDecision.remedy)
                            .joinToString("\n"),
                        log = lines.takeLast(MAX_LOG_LINES),
                        blockedKind = BuildBlockKind.VIVO_VR_KO_MISSING,
                    )
                    return@launch
                }
                if (vrDecision is VrKoGateDecision.Pass) {
                    publish(app.getString(R.string.builder_vr_ko_ok))
                }

                // ── 蓝厂方案：读 vendor_boot 里的 vr.ko，把载荷的 tag A **改成本机的值** ──
//
// 到这一步为止，"载荷带没带绕过"已经查过了；这一步查的是另一半：
// **它要抹的那个字节，在这台机器上是不是对的**。
// 详细理由见 PayloadScheme.needsVendorBoot 的注释，这里只说流程。
if (scheme.needsVendorBoot) {
                    // 判据用**真正的输入句柄**，不是展示用的名字。
                    // `vendorBootName` 是给人看的（可能是「还没选」这种占位），
                    // 拿它当控制变量早晚会出事 —— 这次就出过一次（见上面的状态重置注释）。
                    if (vendorBootUri == null && vendorBootFile == null) {
                        val msg = "蓝厂方案需要一份 vendor_boot.img（用来确定本机 vr.ko 的标记偏移）。\n" +
                            "没有它就只能赌「0x04 / 0x06」里的一个 —— 赌错的表现是提权看着成功、" +
                            "子进程随即被杀，而日志里一切正常。所以这里不猜，停止打包。"
                        publish("[X] $msg")
                        mutableState.value = mutableState.value.copy(
                            phase = PayloadBuildPhase.Failed,
                            error = msg,
                            blockedKind = BuildBlockKind.VENDOR_BOOT_REQUIRED,
                        )
                        return@launch
                    }

                    val probe = withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = vendorBootFile?.readBytes()
                                ?: vendorBootUri?.let { app.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } }
                                ?: error("读不到 vendor_boot.img 的内容")
                            VrKoProbe.probe(bytes)
                        }
                    }
                    if (probe.isFailure) {
                        val why = probe.exceptionOrNull()?.message ?: "未知原因"
                        val msg = "认不出这台机器的 vr.ko 标记偏移：$why"
                        publish("[X] $msg")
                        mutableState.value = mutableState.value.copy(
                            phase = PayloadBuildPhase.Failed,
                            error = msg,
                            blockedKind = BuildBlockKind.VENDOR_BOOT_UNREADABLE,
                        )
                        return@launch
                    }
                    val detected = probe.getOrThrow()
                    publish(
                        "[i] 从 vendor_boot 读到本机 vr.ko：${detected.modulePath}（${detected.moduleSize} B，" +
                            "sha256 ${detected.moduleSha256Prefix}…）",
                    )
                    publish(
                        "[i] 本机 tag A = 0x%02x，tag B = 0x%02x%s".format(
                            detected.tagA, detected.tagB,
                            if (detected.usedFlatPath) "" else "（注意：取的是带版本的目录那份，不是平铺的 lib/modules/vr.ko）",
                        ),
                    )

                    // 载荷里那个值可能是另一种 —— 直接改，而不是"对不上就停下"。
                    val patch = runCatching { VrKoTagPatcher.patch(baseLibrary, detected.tagA) }
                    if (patch.isFailure) {
                        val msg = "识别到了本机 tag A = 0x%02x，但没能改到载荷里：%s".format(
                            detected.tagA, patch.exceptionOrNull()?.message ?: "未知原因",
                        )
                        publish("[X] $msg")
                        mutableState.value = mutableState.value.copy(
                            phase = PayloadBuildPhase.Failed,
                            error = msg,
                            blockedKind = BuildBlockKind.VR_KO_TAG_PATCH_FAILED,
                        )
                        return@launch
                    }
                    val report = patch.getOrThrow()
                    if (report.changed) {
                        publish(
                            "[i] 载荷里的 tag A 由 0x%02x 改为 0x%02x（%d 处，锚点 %d 处）".format(
                                report.oldTagA, report.newTagA, report.patchedSites, report.anchorSites,
                            ),
                        )
                    } else {
                        publish("[i] 载荷里的 tag A 已经是 0x%02x，无需改写（锚点 %d 处）".format(report.oldTagA, report.anchorSites))
                    }
                    mutableState.value = mutableState.value.copy(
                        probedTagA = detected.tagA,
                        probeSummary = "本机 vr.ko tag A = 0x%02x（%s）".format(
                            detected.tagA,
                            if (report.changed) "已改写载荷 %d 处".format(report.patchedSites) else "载荷本来就对",
                        ),
                    )
                }

                // ── 提示（**不是**闸门）：这台机器像是蓝厂的，却选了通用方案 ──
                //
                // 方向与上面的闸门相反：这里只有**正面证据**才算数。
                // 读不到 /proc/modules 不能当成"有 vr.ko" —— SELinux 下非蓝厂机器
                // 一样读不到，那样每台机器都会看到这条提示，提示就成了噪音。
                val vrHintIdentity = withContext(Dispatchers.Default) {
                    listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DEVICE, Build.PRODUCT)
                        .joinToString(" ")
                        .lowercase()
                }
                val modulesText = withContext(Dispatchers.IO) {
                    runCatching { File("/proc/modules").readText() }.getOrNull()
                }
                if (VrKoBypass.shouldSuggestVivoScheme(modulesText, vrHintIdentity, scheme.baselineScheme)) {
                    publish(app.getString(R.string.builder_vr_ko_hint))
                }

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
                            baseline = BaselineRegistry.byId(effectiveProfileId)?.profile,
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
        // ⚠️ `outputLibrary` 是**内存里**的产物。进程被回收 / ViewModel 重建之后它是 null，
        // 而旧代码这里是 `?: return` —— 点了「用作载荷」**什么都不会发生**：
        // 没有 Toast、没有报错、按钮也不变。用户看到的就是"点了没用"。
        // 这正是「点击提权时用不上导入的动态库」最容易走到的一条路。
        // 现在改成明确报错，并给出下一步（重新构建，或直接导入文件）。
        val bytes = outputLibrary
        if (bytes == null) {
            mutableState.value = mutableState.value.copy(
                error = getApplication<Application>()
                    .getString(R.string.builder_output_lost),
            )
            return
        }
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

        /** 「解析完整包链接」的缓存子目录与产物前缀。 */
        private const val OTA_CACHE_DIR = "ota"
        private const val OTA_FILE_PREFIX = "ksuroot_ota_"

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
