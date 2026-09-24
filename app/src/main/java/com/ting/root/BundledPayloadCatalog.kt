package com.ting.root

import android.content.Context
import androidx.annotation.StringRes
import java.io.File
import java.security.MessageDigest

/**
 * 随包内置的厂商载荷库清单（Xiaomi / vivo 各机型 × 各内核版本）。
 *
 * ### 为什么要有这张表
 *
 * `jniLibs/arm64-v8a/` 下的 `libksu_*.so` 是**逐构建编译**的产物：同一台 iQOO 13，
 * 内核 6.6.30 / 6.6.57 / 6.6.89 各有一份，同一版本的两次 OTA（不同 commit）也各有一份。
 * 载荷靠**编译期常量**寻址内核符号，拿错一份就是常量对不上、提权直接失败。
 * 所以"哪台设备该用哪一份"必须是一张**显式登记的表**，而不是运行时猜。
 *
 * ### 三级匹配
 *
 * 1. [MatchTier.Exact] —— 机型 + 内核版本 + 内核 commit 全部对上，直接命中；
 * 2. [MatchTier.SameKernel] —— 机型对上，内核版本对得上但 commit 不同（厂商微调 OTA），
 *    可能可用，界面上会标出来让用户自己判断；
 * 3. [MatchTier.SimilarDevice] —— 机型不认识，但**同厂商 + 同内核大版本**下有登记过的
 *    载荷。同 SoC 家族、同 GKI 版本的机型之间符号偏移高度重合，于是"可能可用"。
 *    这一级永远只是**建议**，绝不自动选中。
 *
 * ### 6.6.89 的特例
 *
 * iQOO neo10 Pro+、iQOO 13、iQOO 11（Neo11）这三款的 6.6.89 构建，上游用的就是
 * 随包那份 `libbs.so`，提权文件里对应目录的 `preload.so` 反而**是重打包过的另一份**。
 * 因此这三条在 [OVERRIDES] 里被显式指向 `libbs.so`，见 [resolve] 的第 0 步。
 */
object BundledPayloadCatalog {

    /** 内置库所在目录（安装后的 `nativeLibraryDir`）。 */
    private const val BUNDLED_DIR_LIBBS = "libbs.so"

    /**
     * 机型串的分词分隔符：**非字母数字、且非 `+` / `-`** 的字符。
     *
     * `iQOO Neo10 Pro+` → `[iqoo, neo10, pro+]`，
     * `vivo X200S`     → `[vivo, x200s]`，
     * `V2405A`         → `[v2405a]`。
     *
     * 两个刻意的设计：
     * - **字母数字连续段整体保留**，所以 `x200s` 不会被切成 `x200`+`s` ——
     *   "vivo x200" 与 "vivo x200s" 天然是两个不同的词，不会互相误命中；
     * - **`+` 与 `-` 归入词内**，所以 `pro+` / `turbo+` 完整保留 ——
     *   否则 "Neo10 Pro+" 会被削成 "Neo10 Pro"、"Z10 Turbo+" 会匹配不上自己的关键词。
     */
    private val MODEL_SEPARATOR = Regex("[^a-z0-9+\\-]+")

    /**
     * 6.6.89 走随包 `libbs.so` 的三款机型。
     *
     * 键是**分词后的机型关键词**（小写），值是可命中的内核版本集合。
     *
     * ⚠️ 这里不能用正则，必须用分词匹配 —— "Neo10 Pro" 与 "Neo10 Pro+" 只差一个加号，
     * 而 `+` 在正则里是量词、不是字面量，写成模式必然误伤其中一个。改成分词：
     * 先把机型串按非字母数字切开，再要求关键词的**每一个词都出现**且
     * 加号要求**必须真的带 +**。这样 "Neo10 Pro" 与 "Neo10 Pro+" 天然互斥。
     */
    private val OVERRIDE_TO_BUNDLED_LIB: Map<String, Set<String>> = mapOf(
        // iQOO Neo10 Pro+（注意：不带 + 的 Neo10 Pro 不在其列）
        "iqoo neo10 pro+" to setOf("6.6.89"),
        // iQOO Neo11 与 iQOO 11 是同一份构建
        "iqoo neo11" to setOf("6.6.89"),
        "iqoo 11" to setOf("6.6.89"),
        // iQOO 13
        "iqoo 13" to setOf("6.6.89"),
    )

    /** 匹配等级。数值越大越可信。 */
    enum class MatchTier {
        /** 机型 / 内核版本 / 内核 commit 全中。 */
        Exact,

        /** 机型与内核版本中，但内核 commit 不同（OTA 微调）。 */
        SameKernel,

        /** 同厂商 + 同内核大版本下的兄弟机型 —— 可能可用，仅供参考。 */
        SimilarDevice,
    }

    /** 一次解析的结果。 */
    data class Resolution(
        val entry: BundledPayload,
        val tier: MatchTier,
        /** 命中用的实际文件（可能是 staged 副本，也可能是 nativeLibraryDir 原件）。 */
        val file: File,
    ) {
        /** 是否需要向用户提示"可能可用"。 */
        val provisional: Boolean get() = tier != MatchTier.Exact
    }

    /**
     * 载荷来源：来自提权文件里逐构建编译的那一份，还是随包原有的 all-in-one 库。
     * 界面上要能一眼看出差别，所以单独建模。
     */
    enum class Origin { VendorBuild, AllInOne }

    /** 清单里的一条载荷。 */
    data class BundledPayload(
        /** `jniLibs` 里的文件名。 */
        val library: String,
        /** 厂商：`xiaomi` / `vivo`。 */
        val vendor: String,
        /** 机型标签（展示用）。 */
        val displayName: String,
        /**
         * 匹配机型用的关键词组：**每一组是一个空格分隔的关键词串，任一组合命中即可**。
         * `null` 表示这份载荷**不按机型匹配**（例如小米那几份通用 SoC 家族包，
         * 需要用户手动选）。
         */
        val model: List<String>?,
        /** 内核版本，如 `6.6.89`；`null` 表示不限。 */
        val kernelVersion: String?,
        /** 内核 `g<commit>` 里的 commit 前缀，用于同版本内再区分 OTA。 */
        val kernelCommits: Set<String> = emptySet(),
        /** 载荷 sha256（前 16 位展示，全量比对）。 */
        val sha256: String,
        val size: Long,
        /** 提权文件里的原始相对路径，方便溯源。 */
        val sourcePath: String,
        /** 提权命令行（来自随附的 `提权命令.md`）。 */
        val command: String = "",
        val origin: Origin = Origin.VendorBuild,
        /**
         * 备注的资源 id（0 表示无备注），例如「六大机型共用」。
         *
         * 用资源 id 而不是字符串：这条备注会**直接渲染到机型清单界面**，
         * 硬编码中文会让非中文用户在列表里看到一段中文。
         * 机型名（[displayName]）不翻译 —— 那是型号，属于专有名词。
         */
        @StringRes val noteRes: Int = 0,
        /**
         * **内核版本未知** —— 只允许用户手动选择，**绝不参与自动匹配**。
         *
         * 这一条是硬安全约束，不是提示。存在的原因：并入的那批载荷多数被 strip 过，
         * 二进制里读不到内核串，机型只能从来源仓库路径得知。若把这种条目的
         * [kernelVersion] 留成 `null` 了事，会被 [resolve] 的第 1/2 步当成
         * 「**不限内核**」而自动选中 —— 而本文件开头就写了：载荷靠**编译期常量**
         * 寻址内核符号，拿错一份就是常量对不上、提权直接失败。
         *
         * 所以「机型已知但内核未知」必须与「机型无关所以不限内核」区分开：
         * 前者是本字段为真，后者是 [model] 为 `null`。
         */
        val kernelUnknown: Boolean = false,
        /**
         * 厂商构建号（如 `CP2A.260705.006` / `S942U1UES4AZG3`），可为空。
         *
         * 仅用于展示：同一机型有多份、且内核版本都读不出来时，
         * 用户只能靠构建号区分是哪一次 OTA 的载荷。
         */
        val buildId: String = "",
        /**
         * 变体的**可读区分名**（如 `64 位 PoC`），空串表示没有。
         *
         * 由来：有些载荷的区分点既不是内核版本也不是构建号，而是文件名本身
         * （`libpoc64.so` / `libpoc32.so` 就是同一次 PoC 的 64 位与 32 位两份）。
         * 不单独给一个字段的话，界面只能退回去显示内部库名
         * （`other_androidcve202643499_any_cc14`），那对用户毫无意义。
         */
        val shortName: String = "",
    )

    /**
     * 全部登记载荷。
     *
     * 顺序即优先级：同一机型 + 同一内核版本有多份时，**排在前面的先命中**。
     * 这也是把 [kernelCommits] 非空的条目放在前面的原因 —— commit 精确定位的那份
     * 肯定比"同版本任意 commit"的那份更可信。
     */
    private val LIBBS = BundledPayload(
        library = BUNDLED_DIR_LIBBS,
        vendor = "vivo",
        displayName = "内置 all-in-one 库（libbs.so）",
        model = null,
        kernelVersion = null,
        sha256 = "8c3410cbc7dce25df3274c0e35d293cb38d7ad2384af7ecc549a3400c50695d9",
        size = 176544,
        sourcePath = "jniLibs/libbs.so",
        command = "LD_PRELOAD=<payload> /system/bin/id",
        origin = Origin.AllInOne,
        noteRes = R.string.payload_note_libbs,
    )

    val ALL: List<BundledPayload> = listOf(
        // ——— iQOO Neo10 Pro+ ———————————————————————————————
        // 6.6.89 特例：走随包 libbs.so（见 OVERRIDE_TO_BUNDLED_LIB）
        BundledPayload(
            library = "libksu_vivo_neon10pp_6657_a.so",
            vendor = "vivo",
            displayName = "iQOO Neo10 Pro+",
            model = listOf("iqoo neo10 pro+"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("a7861f9f1a53"),
            sha256 = "c22d23d363b7158c4bde38449aef7670a69e70b9c0d131c51236d8cd3bf1309f",
            size = 147104,
            sourcePath = "vivo/iQOO neo10 Pro+/6.6.57-…-ga7861f9f1a53-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_neon10pp_6657_b.so",
            vendor = "vivo",
            displayName = "iQOO Neo10 Pro+",
            model = listOf("iqoo neo10 pro+"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("da7e4eafe109"),
            sha256 = "088d8c3c518a73cac30f0bd0b845bfbade8b180fab2b3b30ea63c5f35c9c1f64",
            size = 147104,
            sourcePath = "vivo/iQOO neo10 Pro+/6.6.57-…-gda7e4eafe109-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_neon10pp_6689_a.so",
            vendor = "vivo",
            displayName = "iQOO Neo10 Pro+",
            model = listOf("iqoo neo10 pro+"),
            kernelVersion = "6.6.89",
            kernelCommits = setOf("1f71897ac249"),
            sha256 = "7b9ff7e820c3b741d46b4c2c1c8c11e0a1654cc3f07388dd44956d8076cc1eb6",
            size = 147104,
            sourcePath = "vivo/iQOO neo10 Pro+/6.6.89-…-g1f71897ac249-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
            noteRes = R.string.payload_note_rollback_6689,
        ),
        // ——— iQOO 13 ——————————————————————————————————————
        BundledPayload(
            library = "libksu_vivo_iqoo13_6630_b.so",
            vendor = "vivo",
            displayName = "iQOO 13",
            model = listOf("iqoo 13"),
            kernelVersion = "6.6.30",
            kernelCommits = setOf("ddea8124c6fe"),
            sha256 = "3874b28e1fff36f9adf219291a6a4303ae42a1624246bfcf4ccd11868409a644",
            size = 147104,
            sourcePath = "vivo/IQOO 13/6.6.30-…-gddea8124c6fe-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_iqoo13_6630_a.so",
            vendor = "vivo",
            displayName = "iQOO 13",
            model = listOf("iqoo 13"),
            kernelVersion = "6.6.30",
            kernelCommits = setOf("24ffe62b0a0e"),
            sha256 = "f58eca3124ab0a34c1e14607041bd87959c5cb7f412670b0de413a13965d1dd3",
            size = 147104,
            sourcePath = "vivo/IQOO 13/6.6.30-…-g24ffe62b0a0e-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_iqoo13_6657_a.so",
            vendor = "vivo",
            displayName = "iQOO 13",
            model = listOf("iqoo 13"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("da7e4eafe109"),
            sha256 = "aa6d442f737bfc77b1305d235696ad964244154d1ea0c6aed8a6152ad2df6ea6",
            size = 147104,
            sourcePath = "vivo/IQOO 13/6.6.57-…-gda7e4eafe109-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_iqoo13_6689_a.so",
            vendor = "vivo",
            displayName = "iQOO 13",
            model = listOf("iqoo 13"),
            kernelVersion = "6.6.89",
            kernelCommits = setOf("1f71897ac249"),
            sha256 = "135080801e86f9e117f1bdcd946715ed93350feee5cef33b9da362787e0ba825",
            size = 147104,
            sourcePath = "vivo/IQOO 13/6.6.89-…-g1f71897ac249-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
            noteRes = R.string.payload_note_rollback_6689,
        ),
        // ——— iQOO Neo11（同一条目兼作 iQOO 11 的别名） ——————————
        BundledPayload(
            library = "libksu_vivo_neon11_6689_a.so",
            vendor = "vivo",
            displayName = "iQOO Neo11",
            model = listOf("iqoo neo11", "iqoo 11"),
            kernelVersion = "6.6.89",
            kernelCommits = setOf("1f71897ac249"),
            sha256 = "4deb01e21d1c8bdd3d4991f738ec6f16590ec30d2a724f487736da5540eee027",
            size = 147104,
            sourcePath = "vivo/IQOO Neo11/6.6.89-…-g1f71897ac249-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
            noteRes = R.string.payload_note_rollback_6689,
        ),
        // ——— iQOO 13 / Neo11 / X200U 三合一 ————————————————
        BundledPayload(
            library = "libksu_vivo_iqoo13neo11x200u_6689_a.so",
            vendor = "vivo",
            displayName = "iQOO 13 / iQOO Neo11 / vivo X200 Ultra",
            model = null,
            kernelVersion = "6.6.89",
            kernelCommits = setOf("1f71897ac249"),
            sha256 = "9033f24d366d78e2ea6a19d8e38a2c8c086dc174afd2c14af1d1efaa1db566de",
            size = 157784,
            sourcePath = "vivo/IQOO13、iQOOneo11，X200U/6.6.89-…/iQOO13 neo11.so",
            command = "LD_PRELOAD=<payload> <su>",
            noteRes = R.string.payload_note_share_three,
        ),
        // ——— iQOO Neo10 Pro ———————————————————————————————
        BundledPayload(
            library = "libksu_vivo_neon10p_6630_a.so",
            vendor = "vivo",
            displayName = "iQOO Neo10 Pro",
            model = listOf("iqoo neo10 pro"),
            kernelVersion = "6.6.30",
            kernelCommits = setOf("24ffe62b0a0e"),
            sha256 = "89dc451f03ad08fa5b2173b62e350d3c4612a32e19ffefb805c86d1b24809e93",
            size = 147048,
            sourcePath = "vivo/IQOO Neo10 Pro/6.6.30-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_neon10p_6657_a.so",
            vendor = "vivo",
            displayName = "iQOO Neo10 Pro",
            model = listOf("iqoo neo10 pro"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("e9c3d7352454"),
            sha256 = "5b57e8924e88fd464d2ec2e154fa735315467664aefa21305e8755f8710cb8ab",
            size = 147048,
            sourcePath = "vivo/IQOO Neo10 Pro/6.6.57-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_neon10p_6689_a.so",
            vendor = "vivo",
            displayName = "iQOO Neo10 Pro",
            model = listOf("iqoo neo10 pro"),
            kernelVersion = "6.6.89",
            kernelCommits = setOf("b57af212129c"),
            sha256 = "9feb0d6fddeb4307c69b3939619232c3049ea21e5a145605f19a6768e01c9a17",
            size = 147048,
            sourcePath = "vivo/IQOO Neo10 Pro/6.6.89-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        // ——— iQOO Z10 Turbo+ ———————————————————————————————
        BundledPayload(
            library = "libksu_vivo_z10tp_6657_a.so",
            vendor = "vivo",
            // [2026-09-24 合并] 这一份二进制同时是 vivo X200 / X200 Pro mini / X200 Pro /
            // X200S / iQOO Neo10 Pro / iQOO Z10 Turbo+ 六款机型共用的那份
            // （原 `libksu_vivo_sixmodels_a.so` 与它 sha256 完全相同，已作为重复文件删除）。
            // 显示名**列出全部机型**，不再只写第一款；匹配关键词仍只保留有实证的那一款 ——
            // 其余五款没有"该内核版本下确实可用"的证据，不冒充已验证。
            displayName = "iQOO Z10 Turbo+ / vivo X200 / X200 Pro mini / X200 Pro / X200S / iQOO Neo10 Pro",
            model = listOf("iqoo z10 turbo+"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("e9c3d7352454"),
            sha256 = "c0474820eb0bef03fb2a086b1b4dec99c8015f0f03a85eaf399ef52decb563e5",
            size = 156824,
            sourcePath = "vivo/iQOO Z10 Turbo+/6.6.57-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
            noteRes = R.string.payload_note_share_sixmodels,
        ),
        BundledPayload(
            library = "libksu_vivo_z10tp_6689_a.so",
            vendor = "vivo",
            displayName = "iQOO Z10 Turbo+",
            model = listOf("iqoo z10 turbo+"),
            kernelVersion = "6.6.89",
            kernelCommits = setOf("b57af212129c"),
            sha256 = "c0cf336d657e6102e176ee8ffcb823b020492312f7e657e9f4d57d166edbb6ac",
            size = 157672,
            sourcePath = "vivo/iQOO Z10 Turbo+/6.6.89-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        // ——— vivo X200 ————————————————————————————————————
        BundledPayload(
            library = "libksu_vivo_x200_6630_d.so",
            vendor = "vivo",
            displayName = "vivo X200",
            model = listOf("vivo x200"),
            kernelVersion = "6.6.30",
            kernelCommits = setOf("df2db89bad22"),
            sha256 = "8007714786c75909d836e10e708a62095288122b183e29239baeefca1dc4b42c",
            size = 147048,
            sourcePath = "vivo/VIVO X200/6.6.30-…-gdf2db89bad22-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200_6630_c.so",
            vendor = "vivo",
            displayName = "vivo X200",
            model = listOf("vivo x200"),
            kernelVersion = "6.6.30",
            kernelCommits = setOf("4063ff378d59"),
            sha256 = "928fdc5fa53db89e6b5698b3ad2bd1582db9c57d229a89387935ce464579a650",
            size = 147048,
            sourcePath = "vivo/VIVO X200/6.6.30-…-g4063ff378d59-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200_6630_b.so",
            vendor = "vivo",
            displayName = "vivo X200",
            model = listOf("vivo x200"),
            kernelVersion = "6.6.30",
            kernelCommits = setOf("3c00ef68de5b"),
            sha256 = "27da87c0f9714ec67403e836a961d90722528e48787e6d606919d024c5f1cdb0",
            size = 147048,
            sourcePath = "vivo/VIVO X200/6.6.30-…-g3c00ef68de5b-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200_6630_a.so",
            vendor = "vivo",
            displayName = "vivo X200",
            model = listOf("vivo x200"),
            kernelVersion = "6.6.30",
            kernelCommits = setOf("24ffe62b0a0e"),
            sha256 = "0ba832d0c5e13d2ae2cdf71ee6607b5762684391cdbd90b2e74e7534e9a5d4ea",
            size = 147048,
            sourcePath = "vivo/VIVO X200/6.6.30-…-g24ffe62b0a0e-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200_6657_c.so",
            vendor = "vivo",
            displayName = "vivo X200",
            model = listOf("vivo x200"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("e9c3d7352454"),
            sha256 = "91c3080e6cf329896e639321add256749cd6be7d23cf36a7434fd7fdb1f10d53",
            size = 147048,
            sourcePath = "vivo/VIVO X200/6.6.57-…-ge9c3d7352454-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200_6657_b.so",
            vendor = "vivo",
            displayName = "vivo X200",
            model = listOf("vivo x200"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("5552931da54f"),
            sha256 = "ba5b2da4532c52896f6a943ac73cc72d5fbfcb0a19d3c4ae53ca0c0939be3e24",
            size = 147048,
            sourcePath = "vivo/VIVO X200/6.6.57-…-g5552931da54f-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200_6657_a.so",
            vendor = "vivo",
            displayName = "vivo X200",
            model = listOf("vivo x200"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("0d2377693855"),
            sha256 = "1701f91770c81cd8b7bc555b48c442e2e0d9ba4f73d4009e639b0431df5a2488",
            size = 147048,
            sourcePath = "vivo/VIVO X200/6.6.57-…-g0d2377693855-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200_6689_b.so",
            vendor = "vivo",
            displayName = "vivo X200",
            model = listOf("vivo x200"),
            kernelVersion = "6.6.89",
            kernelCommits = setOf("b57af212129c"),
            sha256 = "a97b62da740c3af0cce103a11d51ef90be602ec23fc9f721fe834777a1c0041e",
            size = 147048,
            sourcePath = "vivo/VIVO X200/6.6.89-…-gb57af212129c-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200_6689_a.so",
            vendor = "vivo",
            displayName = "vivo X200",
            model = listOf("vivo x200"),
            kernelVersion = "6.6.89",
            kernelCommits = setOf("177325e1c8c7"),
            sha256 = "e1bd0dbd1dd2ef65f7ac0c27d12b6dc9668982ea2e17f2d76dcd1c5f2f5545e6",
            size = 147048,
            sourcePath = "vivo/VIVO X200/6.6.89-…-g177325e1c8c7-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        // ——— vivo X200S ————————————————————————————————————
        BundledPayload(
            library = "libksu_vivo_x200s_6630_a.so",
            vendor = "vivo",
            displayName = "vivo X200S",
            model = listOf("vivo x200s"),
            kernelVersion = "6.6.30",
            kernelCommits = setOf("24ffe62b0a0e"),
            sha256 = "787389ba729c099c04a9cdf2eac835d1ccaebbab7169da6b6edd1ca481cd6256",
            size = 147112,
            sourcePath = "vivo/VIVO X200S/6.6.30-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200s_6657_c.so",
            vendor = "vivo",
            displayName = "vivo X200S",
            model = listOf("vivo x200s"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("e9c3d7352454"),
            sha256 = "d304c6615a4c1fb79dab7bec7c0cfdee6c06672f5cf000f8f75ee7aa39f1d0ad",
            size = 147112,
            sourcePath = "vivo/VIVO X200S/6.6.57-…-ge9c3d7352454-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200s_6657_b.so",
            vendor = "vivo",
            displayName = "vivo X200S",
            model = listOf("vivo x200s"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("5552931da54f"),
            sha256 = "dc2fa3d821ecaf083926fce4589130c4f5ec92438c43af85d923ca496640e24a",
            size = 147048,
            sourcePath = "vivo/VIVO X200S/6.6.57-…-g5552931da54f-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200s_6657_a.so",
            vendor = "vivo",
            displayName = "vivo X200S",
            model = listOf("vivo x200s"),
            kernelVersion = "6.6.57",
            kernelCommits = setOf("0d2377693855"),
            sha256 = "459afb5d5930d7d8d7c31a450c1c0c03a2e83f578f7581a68ee29750d6e5e254",
            size = 147048,
            sourcePath = "vivo/VIVO X200S/6.6.57-…-g0d2377693855-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200s_6689_b.so",
            vendor = "vivo",
            displayName = "vivo X200S",
            model = listOf("vivo x200s"),
            kernelVersion = "6.6.89",
            kernelCommits = setOf("b57af212129c"),
            sha256 = "ab1bcce345dfa1b04131bcf00ca0d58f2729afd43de3e8d643c42e58010227c1",
            size = 147112,
            sourcePath = "vivo/VIVO X200S/6.6.89-…-gb57af212129c-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_x200s_6689_a.so",
            vendor = "vivo",
            displayName = "vivo X200S",
            model = listOf("vivo x200s"),
            kernelVersion = "6.6.89",
            kernelCommits = setOf("177325e1c8c7"),
            sha256 = "02442049ad1ddc9ae2797a965533bbf9503ce186f8092f6ac6c8af980940d960",
            size = 147048,
            sourcePath = "vivo/VIVO X200S/6.6.89-…-g177325e1c8c7-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        // ——— vivo Pad5 Pro ————————————————————————————————
        BundledPayload(
            library = "libksu_vivo_pad5pro_66127_a.so",
            vendor = "vivo",
            displayName = "vivo Pad5 Pro",
            model = listOf("vivo pad5 pro"),
            kernelVersion = "6.6.127",
            kernelCommits = setOf("24559e55e8d4"),
            sha256 = "7c7c91ab74ebab865ceb698b0526118ef9f864b3f0e3a88ff259230d021fcd1a",
            size = 147048,
            sourcePath = "vivo/VIVO Pad5 Pro/6.6.127-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        BundledPayload(
            library = "libksu_vivo_pad5pro_6630_a.so",
            vendor = "vivo",
            displayName = "vivo Pad5 Pro",
            model = listOf("vivo pad5 pro"),
            kernelVersion = "6.6.30",
            kernelCommits = setOf("24ffe62b0a0e"),
            sha256 = "b36a91392f89370dd77e77e22bac51a49d8632e693b6f57e0f78619a1937828b",
            size = 147048,
            sourcePath = "vivo/VIVO Pad5 Pro/6.6.30-…/preload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
        ),
        // ——— iQOO 12 (A15) ————————————————————————————————
        BundledPayload(
            library = "libksu_vivo_iqoo12_a15.so",
            vendor = "vivo",
            displayName = "iQOO 12（Android 15）",
            model = listOf("iqoo 12"),
            kernelVersion = null,
            sha256 = "6b4e788cdcb0b8c4c8607589d6f820a0b32657687dc4386127177af1f734255c",
            size = 154240,
            sourcePath = "vivo/iqoo12 A15/iQOO12.so",
            command = "LD_PRELOAD=<payload> sh",
        ),
        // ——— Xiaomi：通用 SoC 家族包（不按机型自动匹配） ————
        BundledPayload(
            library = "libksu_mi_sm8550_a.so",
            vendor = "xiaomi",
            displayName = "Redmi K90 / Civi 5 Pro / Pad 8 / Turbo 4 Pro（骁龙 8 Gen 3 家族）",
            model = null,
            kernelVersion = null,
            sha256 = "576deb3385c38dc283fccb2734e80a87334c4c784520768ede487c002f7d2ad8",
            size = 85608,
            sourcePath = "Xiaomi/Redmi K90.so 等 4 份同二进制",
            origin = Origin.VendorBuild,
            noteRes = R.string.payload_note_dedup_four,
        ),
        BundledPayload(
            library = "libksu_mi_sm8650_a.so",
            vendor = "xiaomi",
            displayName = "Redmi K80 Pro / Xiaomi 15 Pro / Xiaomi 15 Ultra（骁龙 8 至尊版家族）",
            model = null,
            kernelVersion = null,
            sha256 = "e9090eccf96dd2890fcd2bafcfdc48178b9569fe1fdcf795c90b4290edba5c12",
            size = 85608,
            sourcePath = "Xiaomi/Redmi K80 Pro.so 等 3 份同二进制",
            noteRes = R.string.payload_note_dedup_three,
        ),
        BundledPayload(
            library = "libksu_mi_sm8750_a.so",
            vendor = "xiaomi",
            displayName = "Xiaomi 15 / Xiaomi Pad 8 Pro（骁龙 8 Elite 家族）",
            model = null,
            kernelVersion = null,
            sha256 = "9950c6a6483361e68ea550950354e032953406517119a06b3a1b8a5a527045da",
            size = 85624,
            sourcePath = "Xiaomi/Xiaomi 15.so + Xiaomi Pad 8 Pro.so（同一二进制）",
            noteRes = R.string.payload_note_dedup_two,
        ),
        BundledPayload(
            library = "libksu_mi_k70u_os3.so",
            vendor = "xiaomi",
            displayName = "Redmi K70 Ultra（OS3）",
            model = null,
            kernelVersion = null,
            sha256 = "afd47c758d5623d0c8af69120159cee49f2e70097899b1e36a3f9b3dc8314633",
            size = 164976,
            sourcePath = "Xiaomi/k70u_os3",
            command = "/data/local/tmp/preload.so --no-proxy --force",
            origin = Origin.VendorBuild,
            noteRes = R.string.payload_note_pie_exec,
        ),
        BundledPayload(
            library = "libksu_mi_8g2_6m.so",
            vendor = "xiaomi",
            displayName = "小米 13 系列等 6 款（骁龙 8 Gen 2）",
            model = null,
            kernelVersion = null,
            sha256 = "55a406e6d0fefb339d18915e06dd86a289b923094e8b19172245b27333e90552",
            size = 1889584,
            sourcePath = "Xiaomi/小米8g2/小米13系列等6款.so",
            command = "/data/local/tmp/preload.so --no-proxy --force",
            noteRes = R.string.payload_note_exec_8g2,
        ),
        BundledPayload(
            library = "libksu_mi_mt6895_43499.so",
            vendor = "xiaomi",
            displayName = "小米天玑 8000～8250（MT6895）",
            model = null,
            kernelVersion = null,
            sha256 = "71563f169b0454b93db517a6d0ceeb96da1ed9eaa131e5ccb5313d24866a7083",
            size = 4982680,
            sourcePath = "Xiaomi/小米天玑8000～8250/mi_mt6895_43499",
            command = "/data/local/tmp/mi_mt6895_43499 --target <内核版本的后三位>",
            noteRes = R.string.payload_note_exec_mt6895,
        ),
        // ——— 三星 W26（用户提供，2026-09-24）——————————————————
        // 载荷由用户提供（`libw26payload.so`）。二进制里**没有内核串也没有构建指纹**，
        // 所以 `kernelVersion` 留空并置 `kernelUnknown = true` —— 只允许手动选用，
        // 绝不参与自动匹配（拿错一份就是编译期常量对不上、提权直接失败）。
        // 已核对确为 CVE-2026-43499 载荷：pselect ×28、KernelSnitch ×21、futex-PI 原语 ×2。
        BundledPayload(
            library = "libksu_samsung_w26_any_2671.so",
            vendor = "samsung",
            displayName = "三星 W26（Galaxy Z Fold7 中国版）",
            model = listOf("w26", "sm-f9660"),
            kernelVersion = null,
            sha256 = "2671d27d26aaaf462c7d36b76d8acb6e70983e04e9d8fdd4a49df85d0ef0ec0c",
            size = 93576,
            sourcePath = "用户提供：libw26payload.so",
            command = "LD_PRELOAD=<payload> /system/bin/id",
            kernelUnknown = true,
        ),
    ) + BundledPayloadImported.ALL

    private val byLibrary: Map<String, BundledPayload> = ALL.associateBy { it.library }

    /** 按库文件名取回登记项。 */
    fun byLibrary(library: String): BundledPayload? = byLibrary[library]

    /** 需要用户手动选择的载荷（`model == null`，无法按设备自动匹配）。 */
    val manualOnly: List<BundledPayload> get() = ALL.filter { it.model == null }

    /** 能按设备自动匹配的载荷。 */
    val autoMatchable: List<BundledPayload> get() = ALL.filter { it.model != null }

    /** 某个厂商下登记过的全部内核版本（相似机型回退时用）。 */
    private val vendorKernels: Map<String, Set<String>> = ALL
        .filter { it.model != null && it.kernelVersion != null }
        .groupBy({ it.vendor }, { it.kernelVersion!! })
        .mapValues { (_, versions) -> versions.toSet() }

    /**
     * 为设备解析应使用的内置载荷。
     *
     * @return 命中结果；设备不在内置清单里时返回 `null`（调用方应引导用户走自定义载荷，
     *         或到载荷列表里手动挑一份"可能可用"的）。
     */
    fun resolve(
        context: Context,
        snapshot: DeviceSnapshot,
        /** 允许回落到"可能可用"的相似机型。关掉时只有 [MatchTier.Exact] 会返回。 */
        allowSimilar: Boolean = true,
    ): Resolution? {
        // [2026-09-24] haystack 从"只有 Build.MODEL"改成**全部身份串**。
        // 原因：vivo/iQOO 的 Build.MODEL 是型号代码（V2463A），而目录里登记的是
        // 市场名（iqoo 13）—— 只赌 MODEL 必然匹配不上。identityText 里含
        // model/device/product/board/manufacturer/brand + 市场名属性，
        // 名字落在哪个字段都能命中。这是纯召回提升，不需要新增数据。
        val model = snapshot.identityText.ifBlank { snapshot.model }.lowercase()
        val kernel = snapshot.kernelVersion
        val commit = snapshot.kernelCommit

        // 第 0 步：6.6.89 特例 —— 这三款用随包 libbs.so，不用提权文件里那份。
        if (overrideToBundledLib(model, kernel)) {
            bundledLibFile(context)?.let { return Resolution(LIBBS, MatchTier.Exact, it) }
        }

        // 目录里的 displayName（如 "iQOO 13"）本身也是很好的关键词组，
        // 额外带上它 —— 有些条目 model 只写了代号而 displayName 才是全名。
        val candidates = ALL.filter { entry ->
            val groups = (entry.model ?: emptyList()) + listOf(entry.displayName)
            groups.any { matchesModel(listOf(it), model) }
        }
        if (candidates.isEmpty()) {
            return similarDeviceFallback(context, snapshot).takeIf { allowSimilar }
        }

        // 第 1 步：机型 + 内核版本 + commit 全中。
        // [2026-09] 新增 `!kernelUnknown` 闸门：内核版本读不出来的条目**不得**
        // 落进自动匹配，否则它会被当成"不限内核"而命中任意内核。
        candidates.firstOrNull {
            !it.kernelUnknown && (
                it.kernelVersion == null ||
                    (it.kernelVersion == kernel && it.kernelCommits.isNotEmpty() && commit in it.kernelCommits)
                )
        }?.let { entry ->
            fileFor(context, entry)?.let { return Resolution(entry, MatchTier.Exact, it) }
        }

        // 第 2 步：机型 + 内核版本中，commit 不在登记表里（厂商小版本 OTA）。
        candidates.firstOrNull { !it.kernelUnknown && (it.kernelVersion == null || it.kernelVersion == kernel) }
            ?.let { entry ->
                fileFor(context, entry)?.let { return Resolution(entry, MatchTier.SameKernel, it) }
            }

        // 第 3 步：机型中但内核版本不在登记表 —— 同机型的其它 OTA，可能可用。
        // 这一步本来就只作建议，所以内核未知的条目可以在这里出现；
        // 但它们**排在有内核信息的条目后面**，避免"未知"顶掉"已知"。
        candidates.sortedBy { it.kernelUnknown }
            .firstOrNull()
            ?.let { entry ->
                fileFor(context, entry)?.let { return Resolution(entry, MatchTier.SimilarDevice, it) }
            }

        return similarDeviceFallback(context, snapshot).takeIf { allowSimilar }
    }

    /**
     * 相似机型回落：设备本身没有登记项时，看**同厂商 + 同内核大版本**下有没有登记过的载荷。
     *
     * 判定依据是这份载荷的编译特征：同厂商同 GKI 版本的内核，`rt_mutex` / `pipe_buffer`
     * 那条链上的符号偏移往往只差很少几项，载荷里的常量改写能覆盖掉。所以标成"可能可用"，
     * 但**绝不自动选中** —— 让用户自己决定要不要试。
     */
    private fun similarDeviceFallback(context: Context, snapshot: DeviceSnapshot): Resolution? {
        val vendor = snapshot.manufacturer.lowercase().let { raw ->
            when {
                raw.contains("vivo") || raw.contains("iqoo") -> "vivo"
                raw.contains("xiaomi") || raw.contains("redmi") || raw.contains("poco") -> "xiaomi"
                else -> return null
            }
        }
        if (snapshot.kernelMajorMinor !in vendorKernels[vendor].orEmpty()) return null
        // 同厂商同内核版本下，优先拿数据量最小的那份 —— 它是"覆盖最广"的共同构建，
        // 失败面最小；反正这一级本来就只作建议。
        val entry = ALL
            .filter { it.vendor == vendor && it.kernelVersion == snapshot.kernelVersion }
            .minByOrNull { it.size }
            ?: return null
        return fileFor(context, entry)?.let { Resolution(entry, MatchTier.SimilarDevice, it) }
    }

    /** 命中的文件是否真的存在于本次安装里（防止清单与实际打包不一致）。 */
    fun fileFor(context: Context, entry: BundledPayload): File? {
        val file = File(context.applicationInfo.nativeLibraryDir, entry.library)
        return file.takeIf { it.exists() }
    }

    private fun bundledLibFile(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir, BUNDLED_DIR_LIBBS).takeIf { it.exists() }

    private fun overrideToBundledLib(model: String, kernel: String): Boolean =
        OVERRIDE_TO_BUNDLED_LIB.any { (pattern, versions) ->
            kernel in versions && matchesModel(listOf(pattern), model)
        }

    /**
     * 机型关键词匹配。
     *
     * 关键词是空格分隔的一串词（如 `iqoo neo10 pro+`）。匹配规则：
     * 把机型串也按非字母数字切开，要求关键词的**每一个词都精确出现**（全词匹配）。
     *
     * 为什么不用正则：`+` 在正则里是量词。`iqoo neo10 pro+` 会变成"pro 重复一次以上"，
     * 于是 "Neo10 Pro" 也被算命中 —— 恰恰是我们想区分的两款机器。全词切分天然免疫。
     */
    internal fun matchesModel(keywordGroups: List<String>, model: String): Boolean {
        // [勘误 2026-09-24] 必须**两侧都小写、都用同一个分隔符切**。
        // 原来只切 `group.split(' ')` 且不转小写，而 MODEL_SEPARATOR 的字符类是
        // `[^a-z0-9+\-]`（**只认小写**）—— 于是 `displayName = "iQOO 13"` 里的
        // "iQOO" 永远匹配不上小写 haystack，displayName 那条路是**死代码**。
        val haystack = model.lowercase().split(MODEL_SEPARATOR).filter(String::isNotEmpty)
        return keywordGroups.any { group ->
            val words = group.lowercase().split(MODEL_SEPARATOR).filter(String::isNotEmpty)
            words.isNotEmpty() && words.all { it in haystack }
        }
    }

    /** 校验文件内容是否与清单登记的一致（sha256）。仅用于自检与"可能可用"提示。 */
    fun verify(entry: BundledPayload, file: File): Boolean = runCatching {
        if (!file.exists() || file.length() != entry.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256
    }.getOrDefault(false)
}
