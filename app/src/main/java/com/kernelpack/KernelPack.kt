package com.kernelpack

import com.kernelpack.boot.BootImageParser
import com.kernelpack.kallsyms.KallsymsFinder
import com.kernelpack.kallsyms.KallsymsOptions
import com.kernelpack.model.KernelImageAnalysis
import com.kernelpack.model.OffsetEntry
import com.kernelpack.model.ResolveSource
import com.kernelpack.model.TargetProfile
import com.kernelpack.patch.PatchReport
import com.kernelpack.patch.PatchSpec
import com.kernelpack.patch.SharedObjectPatcher
import com.kernelpack.patch.SpecKind
import com.kernelpack.profile.BaselineProfile
import com.kernelpack.profile.BaselineProfiles
import com.kernelpack.resolve.KernelImage
import com.kernelpack.resolve.OffsetResolver
import com.kernelpack.export.OffsetsJson
import com.kernelpack.export.TargetHeaderWriter

/** 打包请求。 */
class PackRequest(
    /** 用户给的 boot.img（或裸 Image / Image.gz）。 */
    val bootImage: ByteArray,
    /** 基础动态库（链接 2 里那个 preload.so / libbs.so）。null = 只解析偏移，不打包。 */
    val baseLibrary: ByteArray? = null,
    /** 指定基线；null = 自动识别。 */
    val baseline: BaselineProfile? = null,
    /** 压缩内核解压钩子；null = 不解压。 */
    val decompressor: BootImageParser.Decompressor? = BootImageParser.gzipDecompressor,
    /** 是否连数据段里的 4 字节偏移也一起改（默认关，4 字节容易误伤）。 */
    val patchDataLiterals32: Boolean = false,
    val log: (String) -> Unit = {},
)

/** 打包结果。 */
class PackResult(
    val analysis: KernelImageAnalysis,
    val profile: TargetProfile,
    /** 打过补丁的 .so 字节；未提供基础库时为 null。 */
    val packedLibrary: ByteArray?,
    val patchReport: PatchReport?,
    val baseline: BaselineProfile?,
    val targetHeader: String,
    val offsetsJson: String,
    val warnings: List<String>,
) {
    val log: List<String> get() = analysis.log

    /** 一句话结论，方便直接丢给界面。 */
    fun summary(): String = buildString {
        appendLine("内核: ${analysis.versionNumber} ${analysis.architecture} 基址 ${Hex.u64(analysis.baseAddress)}")
        appendLine("符号: ${analysis.symbols.size} 个")
        appendLine("偏移: 解析出 ${profile.offsets.count { it.value.resolved }}/${profile.offsets.size} 项")
        if (profile.unresolved.isNotEmpty()) appendLine("未解析: ${profile.unresolved.joinToString(", ")}")
        patchReport?.let { appendLine(it.summary()) }
        if (warnings.isNotEmpty()) {
            appendLine("提示:")
            for (w in warnings) appendLine("  ! $w")
        }
    }
}

/**
 * 对外唯一入口：**boot.img → 偏移 → 动态库**。
 *
 * 典型用法（Android 里就是一次后台线程调用）：
 * ```kotlin
 * val result = KernelPack.pack(
 *     PackRequest(
 *         bootImage = File("/sdcard/boot.img").readBytes(),
 *         baseLibrary = assets.open("libbs.so").readBytes(),
 *     )
 * )
 * File("/sdcard/libbs_patched.so").writeBytes(result.packedLibrary!!)
 * ```
 *
 * 本类不做任何 IO、不依赖 Android、不开线程 —— 纯函数式，
 * 方便嵌进任何工程（也可直接放到 CLI / 服务端复用）。
 */
object KernelPack {

    /** 只解析 boot.img，得到内核符号表与基址。 */
    @JvmStatic
    @JvmOverloads
    fun analyze(
        bootImage: ByteArray,
        decompressor: BootImageParser.Decompressor? = BootImageParser.gzipDecompressor,
        log: (String) -> Unit = {},
    ): KernelImageAnalysis {
        val parsed = BootImageParser.parse(bootImage, decompressor)
        log("[+] 容器: ${parsed.info.container} 版本=${parsed.info.headerVersion ?: "-"} " +
            "page=${parsed.info.pageSize ?: "-"} kernel_off=0x${java.lang.Long.toHexString(parsed.info.kernelOffset.toLong())}")
        log("[+] 内核 Image: ${parsed.image.size} 字节" + if (parsed.info.decompressed) "（已解压）" else "")

        val finder = KallsymsFinder(parsed.image, KallsymsOptions(log = log))
        val r = finder.run()

        return KernelImageAnalysis(
            boot = parsed.info,
            versionString = r.versionString,
            versionNumber = r.versionNumber,
            architecture = r.architecture,
            is64Bits = r.is64Bits,
            isBigEndian = r.isBigEndian,
            baseAddress = r.baseAddress,
            layout = r.layout,
            symbols = r.symbols,
            log = r.log,
        )
    }

    /** 解析偏移（不打包）。 */
    @JvmStatic
    fun resolveOffsets(
        analysis: KernelImageAnalysis,
        kernelImageBytes: ByteArray,
    ): Map<String, OffsetEntry> {
        val image = KernelImage(kernelImageBytes, analysis.baseAddress)
        return OffsetResolver(analysis, image).resolve()
    }

    /** 完整流程：boot.img → 偏移 → 打包动态库 + 导出。 */
    @JvmStatic
    fun pack(request: PackRequest): PackResult {
        val warnings = ArrayList<String>()
        val log = request.log

        val parsed = BootImageParser.parse(request.bootImage, request.decompressor)
        val finder = KallsymsFinder(parsed.image, KallsymsOptions(log = log))
        val r = finder.run()

        val analysis = KernelImageAnalysis(
            boot = parsed.info,
            versionString = r.versionString,
            versionNumber = r.versionNumber,
            architecture = r.architecture,
            is64Bits = r.is64Bits,
            isBigEndian = r.isBigEndian,
            baseAddress = r.baseAddress,
            layout = r.layout,
            symbols = r.symbols,
            log = r.log,
        )

        if (analysis.symbols.size < 1000) {
            warnings.add("只解析出 ${analysis.symbols.size} 个符号，明显偏少；请确认 boot.img 与本机内核匹配")
        }

        // 基址自检：kallsyms 推出的基址应当等于 `_text`
        val textSym = analysis.byName["_text"] ?: analysis.byName["_stext"]
        if (textSym != null && textSym.address != analysis.baseAddress) {
            warnings.add(
                "推导基址 ${Hex.u64(analysis.baseAddress)} 与符号 _text=${Hex.u64(textSym.address)} 不一致，" +
                    "偏移可能整体偏移，请核对"
            )
        }

        val image = KernelImage(parsed.image, analysis.baseAddress)
        val resolver = OffsetResolver(analysis, image)
        val offsets = resolver.resolve()
        for (f in resolver.failures()) log("[!] $f")

        val baseline = request.baseline
            ?: request.baseLibrary?.let { BaselineProfiles.detect(it) }

        val profile = TargetProfile(
            variantLabel = buildVariantLabel(analysis, baseline),
            versionNumber = analysis.versionNumber,
            architecture = analysis.architecture,
            imageBase = analysis.baseAddress,
            memoryLayout = baseline?.abi?.memoryLayout ?: emptyMap(),
            offsets = LinkedHashMap(offsets),
            structOffsets = baseline?.abi?.structOffsets ?: emptyMap(),
            unresolved = offsets.filterValues { !it.resolved }.keys.toList(),
        )

        // ABI 档位 vs 实际内核版本
        val seriesOfKernel = analysis.versionNumber.split('.').take(2).joinToString(".")
        baseline?.abi?.let { abi ->
            if (abi.kernelSeries != seriesOfKernel) {
                warnings.add(
                    "基线 ABI 档位是 GKI ${abi.kernelSeries}，而 boot.img 内核是 $seriesOfKernel；" +
                        "结构体字段偏移可能不适用，基础 .so 需要换用对应 GKI 版本编译的那一份"
                )
            }
        }

        val header = TargetHeaderWriter.write(profile)
        val json = OffsetsJson.write(profile)

        if (request.baseLibrary == null) {
            return PackResult(analysis, profile, null, null, baseline, header, json, warnings)
        }

        if (baseline == null) {
            warnings.add("没有可用的基线档位，无法确定基础 .so 里的旧值，本次不打包")
            return PackResult(analysis, profile, null, null, null, header, json, warnings)
        }

        // 组装 patch 规格
        val specs = ArrayList<PatchSpec>()
        specs.add(PatchSpec("KIMAGE_TEXT_BASE", baseline.imageBase, analysis.baseAddress, SpecKind.BASE))
        for ((key, entry) in offsets) {
            val old = baseline.symbolOffsets[key] ?: continue
            if (!entry.resolved) continue
            specs.add(PatchSpec(key, old, entry.offset!!, SpecKind.IMAGE_OFFSET))
        }

        val working = request.baseLibrary.copyOf()
        val patcher = SharedObjectPatcher(working, log)
        patcher.patchDataLiterals32 = request.patchDataLiterals32
        val report = patcher.patch(specs)

        if (report.hasFailures) {
            warnings.add("有偏移未能完全替换（详见报告），产出的 .so 可能不适用于本机内核")
        }
        if (report.baseChanged) {
            warnings.add(
                "内核链接基址从 ${Hex.u64(report.oldBase)} 变为 ${Hex.u64(report.newBase)}；" +
                    "除偏移外已一并改写基址常量，仍建议在真机上先小范围验证"
            )
        }
        for ((key, entry) in offsets) {
            if (entry.resolved && entry.source == ResolveSource.KALLSYMS_DERIVED) {
                // 推导值只在日志里提示，不算问题
                log("[i] $key 由推导得到: ${entry.detail} -> ${Hex.u(entry.offset!!)}")
            }
        }

        return PackResult(analysis, profile, working, report, baseline, header, json, warnings)
    }

    /**
     * 生成画像标签。基础 .so 的机型标签 + 实际内核版本，
     * 既能看出"从哪台机器的基线改过来的"，也能看出"改成哪台机器的了"。
     */
    private fun buildVariantLabel(analysis: KernelImageAnalysis, baseline: BaselineProfile?): String {
        val base = baseline?.variantLabel ?: "generic"
        return "$base@${analysis.versionNumber}"
    }
}
