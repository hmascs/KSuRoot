package com.kernelpack.boot

/**
 * boot 镜像**格式识别**：在解析之前先回答"这是什么东西"。
 *
 * 为什么单独做一层
 * ----------------
 * 用户手上可能是 `boot.img` / `init_boot.img` / `vendor_boot.img` / 裸 `Image`，
 * 也可能是厂商魔改过的（MTK、高通 split）。现在失败时只会说
 * 「不是有效的 boot.img / Image」—— 那句话对用户**毫无帮助**：他不知道自己给错了哪一个。
 *
 * 本层的目标不是"多解析几种格式"，而是**把认不出来的东西说清楚**：
 * ```
 *   ✅ 直接支持的        → 明确告知容器类型与版本
 *   ⚠️ 认得但内核不在这  → 点名（vendor_boot 的 kernel 在 boot/init_boot）
 *   ❓ 认不出            → 说清看了哪些特征、下一步该给什么
 * ```
 *
 * [诚实声明 —— 这一节很重要]
 * 下面标注 `[可验证]` 的判据都能从文件头直接读出来（magic、字段、字符串表）。
 * 标注 `[启发式]` 的是**统计性判断**，只能用来"提示可能是什么"，
 * **绝不能**当作事实断言 —— 把启发式结论说成确定的，正是本工程一直在防的那类错误。
 * 凡启发式命中，输出里都会带「疑似」字样。
 */
object BootFormatDetector {

    /** 识别到的容器类型。 */
    enum class Format(val label: String, val parseable: Boolean) {
        /** 标准 AOSP boot header v0~v4，内核在里面。 */
        AOSP_BOOT("AOSP boot.img", true),

        /** vendor_boot v3/v4：**内核不在这里**。 */
        VENDOR_BOOT("AOSP vendor_boot.img", false),

        /** 裸内核镜像（arm64 Image / 压缩后的 Image）。 */
        RAW_KERNEL("裸内核镜像", true),

        /** 疑似 MTK 布局。 */
        MTK_SUSPECT("疑似 MTK 布局", true),

        /** 疑似高通 split（多份 DTB）。 */
        QCOM_SPLIT("疑似高通 split boot", true),

        /** 认不出来。 */
        UNKNOWN("未知格式", false),
    }

    /** 识别结果。 */
    data class Info(
        val format: Format,
        /** 人类可读的一句话结论。可疑的会带「疑似」。 */
        val summary: String,
        /** 识别依据（逐条列出，便于用户与我们对账）。 */
        val evidence: List<String>,
        /** 建议用户下一步做什么；为空表示不需要额外动作。 */
        val advice: String? = null,
        /** header 版本（AOSP 系才有）。 */
        val headerVersion: Int? = null,
        /** 页大小（AOSP 系才有）。 */
        val pageSize: Int? = null,
    )

    private val MAGIC_BOOT = "ANDROID!".toByteArray(Charsets.US_ASCII)
    private val MAGIC_VENDOR = "VNDRBOOT".toByteArray(Charsets.US_ASCII)
    private val MAGIC_ARM64 = byteArrayOf(0x4D, 0x5A, 0x40, 0xFA.toByte()) // "MZ@" + 0xFA

    /** 各厂商的标记串 —— [可验证]：直接在文件里找 ASCII 串。 */
    private val VENDOR_MARKERS = listOf(
        "MTK" to "MediaTek",
        "mediatek" to "MediaTek",
        "MT6789" to "MediaTek",
        "MT6985" to "MediaTek",
        "QC_IMAGE_VERSION" to "Qualcomm",
        "qcom,board-id" to "Qualcomm",
        "QUALCOMM" to "Qualcomm",
    )

    /** 识别入口。`data` 是完整文件内容。 */
    fun detect(data: ByteArray): Info {
        if (data.size < 16) {
            return Info(
                format = Format.UNKNOWN,
                summary = "文件太小（${data.size} 字节），不像任何 boot 镜像",
                evidence = listOf("长度 < 16 字节，连 magic 都放不下"),
                advice = "请确认选择的是 boot.img / init_boot.img / Image，而不是别的小文件。",
            )
        }

        // ── ① magic 判定 [可验证] ────────────────────────────────────
        if (startsWith(data, 0, MAGIC_VENDOR)) {
            return vendorBootInfo(data)
        }
        if (startsWith(data, 0, MAGIC_BOOT)) {
            return androidBootInfo(data)
        }

        // ── ② 裸内核 / 压缩镜像 [可验证] ─────────────────────────────
        if (startsWith(data, 0, MAGIC_ARM64)) {
            return Info(
                format = Format.RAW_KERNEL,
                summary = "裸 arm64 内核镜像（Image）",
                evidence = listOf("开头是 arm64 Image 魔数 4D 5A 40 FA"),
                advice = null,
            )
        }
        val compression = KernelDecompressor.detect(data, 0)
        if (compression != KernelDecompressor.Format.NONE) {
            return Info(
                format = Format.RAW_KERNEL,
                summary = "压缩过的内核镜像（${compression.name}）",
                evidence = listOf("开头匹配 ${compression.name} 的容器魔数"),
                advice = null,
            )
        }

        // ── ③ 厂商特征 [启发式 —— 一律带「疑似」] ─────────────────────
        val evidence = ArrayList<String>()
        val vendors = ArrayList<String>()
        // 只在头部 1MB 内找标记：整个文件扫一遍在 100MB 镜像上太慢，而且标记本来就在头部
        val head = data.copyOfRange(0, minOf(data.size, 1 shl 20))
        val headText = String(head, Charsets.ISO_8859_1)
        for ((marker, vendor) in VENDOR_MARKERS) {
            if (headText.contains(marker)) {
                vendors.add(vendor)
                evidence.add("头部 1MB 内出现「$marker」→ $vendor 特征")
            }
        }

        if (vendors.contains("MediaTek")) {
            return Info(
                format = Format.MTK_SUSPECT,
                summary = "疑似 MTK 布局（头部有 MediaTek 标记）",
                evidence = evidence,
                advice = "MTK 机型的 kernel 有时不在标准偏移上（厂商会在 boot header 后加私有头）。" +
                    "本工程目前按标准 AOSP 偏移解析 —— 若解析出的内核版本与设备不符，" +
                    "请把 boot.img 与 /proc/version 一起反馈。",
            )
        }

        // 高通 split 的特征：头部能看到多份 DTB / dtb 相关串
        val dtbHits = listOf("dtb", "DTB", "qcom,msm-id", "qcom,pmic-id").count { headText.contains(it) }
        if (vendors.contains("Qualcomm") || dtbHits >= 2) {
            evidence.add("头部出现 $dtbHits 处 dtb / qcom 相关串 → 高通 split 特征")
            return Info(
                format = Format.QCOM_SPLIT,
                summary = "疑似高通 split boot（内核可能在 boot/init_boot，vendor_boot 里是 dtb）",
                evidence = evidence,
                advice = "高通的 vendor_boot 里通常只有 vendor ramdisk 与 dtb，内核在 boot.img。" +
                    "请优先尝试 boot.img；若手上只有 vendor_boot，本工具无法从中取内核。",
            )
        }

        // ── ④ 认不出 ────────────────────────────────────────────────
        return Info(
            format = Format.UNKNOWN,
            summary = "认不出这是什么镜像",
            evidence = evidence + listOf(
                "开头 8 字节：${data.copyOfRange(0, 8).joinToString(" ") { "%02X".format(it) }}",
                "既不是 ANDROID! / VNDRBOOT，也不是 arm64 Image 或已知压缩格式",
                "头部 1MB 内没有命中任何已知厂商标记",
            ),
            advice = "请确认这是从固件包里解出来的 boot.img / init_boot.img，" +
                "或解压后的内核 Image。部分厂商（如 vivo OriginOS 4.0+）会把 ramdisk " +
                "做成嵌套加密分段，那种镜像本工具无法解析。",
        )
    }

    /** 标准 AOSP boot：读出 header 版本 / 页大小，并做厂商特征启发。 */
    private fun androidBootInfo(data: ByteArray): Info {
        val headerVersion = u32(data, 0x28).toInt()
        val evidence = ArrayList<String>()
        evidence.add("开头是 AOSP boot magic「ANDROID!」")
        evidence.add("header_version = $headerVersion")

        // v0..v2 有 page_size 字段（v3+ 固定 4096）
        val pageSize = if (headerVersion in 0..2) u32(data, 0x24).toInt().takeIf { it > 0 } else 4096
        pageSize?.let { evidence.add("page_size = $it（v3+ 固定 4096）") }

        val fmt = when {
            headerVersion in 0..4 -> Format.AOSP_BOOT
            else -> Format.UNKNOWN
        }
        if (fmt == Format.UNKNOWN) {
            return Info(
                format = Format.UNKNOWN,
                summary = "看起来是 AOSP boot，但 header_version = $headerVersion 超出已知范围（0~4）",
                evidence = evidence,
                advice = "这个版本比本工具认识的更新，请把镜像反馈给我们补支持。",
                headerVersion = headerVersion,
                pageSize = pageSize,
            )
        }

        // v0..v2 的「second stage」字段：高通常把 dtb 放这，MTK 有厂商用法
        if (headerVersion in 0..2) {
            val secondSize = u32(data, 0x10).toInt()
            if (secondSize > 0) {
                evidence.add("second_size = $secondSize（>0 说明带 second stage，常见于高通/MTK 魔改）")
            }
        }
        return Info(
            format = Format.AOSP_BOOT,
            summary = "标准 AOSP boot.img（header v$headerVersion）",
            evidence = evidence,
            advice = null,
            headerVersion = headerVersion,
            pageSize = pageSize,
        )
    }

    /** vendor_boot v3/v4：内核不在这里，必须说清楚并指路。 */
    private fun vendorBootInfo(data: ByteArray): Info {
        val headerVersion = u32(data, 0x08).toInt()
        val evidence = ArrayList<String>()
        evidence.add("开头是 AOSP vendor_boot magic「VNDRBOOT」")
        evidence.add("vendor header_version = $headerVersion")
        val dtbSize = u32(data, 0x18).toInt()
        if (dtbSize > 0) evidence.add("dtb_size = $dtbSize（vendor_boot 里放的是 dtb，不是内核）")
        return Info(
            format = Format.VENDOR_BOOT,
            summary = "vendor_boot.img —— **内核不在这个文件里**",
            evidence = evidence,
            advice = "GKI 设备的内核在 boot.img（或某些机型的 init_boot.img）。" +
                "请改用 boot.img；vendor_boot 只提供 vendor ramdisk 与 dtb。",
            headerVersion = headerVersion,
        )
    }

    private fun startsWith(data: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (offset + magic.size > data.size) return false
        for (i in magic.indices) if (data[offset + i] != magic[i]) return false
        return true
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}
