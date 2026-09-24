package com.kernelpack

import com.kernelpack.boot.BootImageParser
import com.kernelpack.boot.KernelDecompressor
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
import com.kernelpack.profile.BaselineRegistry
import com.kernelpack.profile.BaselineScheme
import com.kernelpack.resolve.KernelImage
import com.kernelpack.resolve.OffsetResolver
import com.kernelpack.export.OffsetsJson
import com.kernelpack.export.TargetHeaderWriter
import com.kernelpack.policy.BuildGate
import com.kernelpack.policy.GateDecision
import com.kernelpack.policy.SeriesOverride

/** 打包请求。 */
class PackRequest(
    /** 用户给的 boot.img（或裸 Image / Image.gz）。 */
    val bootImage: ByteArray,
    /** 基础动态库（链接 2 里那个 preload.so / libbs.so）。null = 只解析偏移，不打包。 */
    val baseLibrary: ByteArray? = null,
    /** 指定基线；null = 自动识别。 */
    val baseline: BaselineProfile? = null,
    /** 压缩内核解压钩子；null = 不解压。 */
    val decompressor: BootImageParser.Decompressor? = KernelDecompressor.default,
    /** 是否连数据段里的 4 字节偏移也一起改（默认关，4 字节容易误伤）。 */
    val patchDataLiterals32: Boolean = false,
    /** 设置页选择的"强制指定内核系列"。默认按 boot.img 实测走。 */
    val seriesOverride: SeriesOverride = SeriesOverride.AUTO,
    /** 用户是否显式打开"忽略冲突"（等价宿主侧 --i-know-what-i-am-doing）。 */
    val allowAbiMismatch: Boolean = false,
    /**
     * 当前方案（通用 / vivo），供闸门查 [com.kernelpack.profile.BaselineRegistry] 用。
     *
     * 默认 [BaselineScheme.UNKNOWN]：**未知时闸门会跳过注册表建议** ——
     * 方案都不知道就去报"基线不匹配"，报出来的多半是假警报。
     */
    val scheme: BaselineScheme = BaselineScheme.UNKNOWN,
    /**
     * 「5.x 内核支持（beta）」。**默认 false** —— 关着时闸门拒绝 5.x 构建。
     * 默认值取 false 是刻意的：漏传参数时得到的是安全行为（拒绝未验证的 5.x）。
     */
    val allowTestKernel: Boolean = false,
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
    /** 硬闸门结论。非 Proceed 时 [packedLibrary] 一定为 null —— 拒绝构建时不出包。 */
    val gate: GateDecision = GateDecision.Proceed,
) {
    /** 是否被闸门拒绝（UI 据此弹阻断式对话框）。 */
    val blocked: Boolean get() = gate is GateDecision.Blocked
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
        decompressor: BootImageParser.Decompressor? = KernelDecompressor.default,
        log: (String) -> Unit = {},
    ): KernelImageAnalysis {
        val parsed = BootImageParser.parse(bootImage, decompressor)
        log("[+] 容器: ${parsed.info.container} 版本=${parsed.info.headerVersion ?: "-"} " +
            "page=${parsed.info.pageSize ?: "-"} kernel_off=0x${java.lang.Long.toHexString(parsed.info.kernelOffset.toLong())}")
        log("[+] 内核 Image: ${parsed.image.size} 字节" + if (parsed.info.decompressed) "（已解压）" else "")
        // 解析失败的具体原因必须上屏：笼统的"找不到 Linux version"会把用户引向错误方向
        parsed.diagnosis?.let { log("[!] 解析诊断: $it") }

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

        // [2026-09-25 修 · 与 PayloadBuilderViewModel 同一处错]
        // 旧写法只查 [BaselineProfiles.detect]，而那张表只有 PD2520(6.6) / IONSTACK_P10(6.6)
        // 两条 —— 拿 6.1 或 6.12 的基线库进来，detect() 会**兜底返回 PD2520**，
        // 于是硬闸门报「基线 ABI 档位是 GKI 6.6 而 boot.img 是 6.1」把用户挡在门外。
        //
        // 现在只认**能自证身份**的匹配：sha256 命中，或 .so 里带着已登记档位的
        // BUILD_VARIANT_LABEL 字符串（都在 [BaselineRegistry.findByBytes] 里）。
        //
        // 为什么**不再**保留"兜底取第一档"：那等于拿 6.6 的旧值去打 6.1 的库。
        // 即便硬闸门这一次能拦住，兜底本身就是在制造"看起来有基线"的假象 ——
        // 本工程两次勘误都是这个形态。认不出就如实报缺，由下面给出明确警告并停止打包。
        val baseline = request.baseline
            ?: request.baseLibrary?.let { BaselineRegistry.findByBytes(it)?.profile }

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

        val header = TargetHeaderWriter.write(profile)
        val json = OffsetsJson.write(profile)

        // ===== 硬闸门（P1）=====
        // 原来这里只是 warnings.add(...) 一句提示。但"提示"挡不住用户继续点构建 ——
        // 实测出现过「需要改写 25 项 · 校验通过 12 项」照样出包的情况：那是拿内核内存去赌。
        // 现在改为：不匹配就**拒绝打包**，除非用户在设置里显式打开"忽略冲突"。
        val gate = BuildGate.evaluate(
            kernelRelease = analysis.versionNumber,
            baselineAbiSeries = baseline?.abi?.kernelSeries,
            override = request.seriesOverride,
            allowMismatch = request.allowAbiMismatch,
            // 任务1：把方案与档位 id 一起带进去，闸门才能查四元组注册表
            scheme = request.scheme,
            profileId = baseline?.id,
            allowTestKernel = request.allowTestKernel,
        )
        when (gate) {
            is GateDecision.Blocked -> {
                log("[X] ${gate.title}")
                gate.detail.forEach { log("    $it") }
                log("    → ${gate.remedy}")
                return PackResult(
                    analysis, profile, null, null, baseline, header, json, warnings, gate,
                )
            }
            is GateDecision.ProceedWithWarning -> gate.notes.forEach { warnings.add(it) }
            GateDecision.Proceed -> Unit
        }

        if (request.baseLibrary == null) {
            return PackResult(analysis, profile, null, null, baseline, header, json, warnings)
        }

        if (baseline == null) {
            warnings.add(
                "这份基础 .so 对应不到任何已登记基线（sha256 与机型标签都没命中）—— " +
                    "无法确定它里面的旧值，本次不打包。"
            )
            warnings.add(
                "若这是 6.1 / 6.12 的族基线（libbaseline_6_1.so / libbaseline_6_12.so）：" +
                    "它们**没有登记进偏移表**（编译期符号值未逐项核实，见 BaselineRegistry.BASELINE_6_1 的说明），" +
                    "必须由调用方显式传入对应档位（PackRequest.baseline），本函数**不会**替你猜一个系列。"
            )
            return PackResult(analysis, profile, null, null, null, header, json, warnings)
        }

        // ── 完整性闸门（★ 安全关键）──────────────────────────────────
        //
        // 打补丁只能改写**基线 .so 里本来就有的字面量**。因此两边必须**双向对齐**，
        // 缺任何一边都会让产出的 .so 里留下一批"为别的内核烤死的旧值"，
        // 而那是**静默**的：既不报错、也不 manifest 成失败，装机后才炸。
        //
        // 这条闸门的由来：上游 50 档每档只带 9 个符号，而我方必需键有 25 个。
        // 原先的写法是 `baseline.symbolOffsets[key] ?: continue` —— 直接跳过，
        // 于是"看起来支持、实际写错内存"。这正是本工程一直在防的假象，必须硬拦。
        val align = SymbolAlignment.check(
            baseKeys = baseline.symbolOffsets.keys,
            resolvedKeys = offsets.filterValues { it.resolved }.keys,
        )
        if (!align.aligned) {
            align.blockMessage(baseline.id, baseline.kernelVersion).forEach { log(it) }
            warnings.add("[X] 符号对不齐，已停止打包（详见日志）")
            return PackResult(analysis, profile, null, null, baseline, header, json, warnings)
        }

        // 组装 patch 规格（两边已对齐，无需再跳过任何键）
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
