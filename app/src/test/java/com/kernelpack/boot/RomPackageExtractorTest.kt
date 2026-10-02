package com.kernelpack.boot

import com.kernelpack.ota.XzDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * [RomPackageExtractor] 的测试：**只用真文件**，不造假的字节数组来"测通过"。
 *
 * 为什么必须这样
 * --------------
 * 这个类要解决的是**真实厂商包**的解析。真实包里有太多"想当然会踩空"的东西：
 *   · payload.bin 存在 zip 里的方式（stored / deflate）；
 *   · 操作类型（实测手上的 OPPO/一加完整包 37 个分区**全部**是 REPLACE_ZSTD）；
 *   · fastboot 包里镜像在 `images/` 子目录下，且 `super.img` 有几个 GB；
 *   · tar 的长路径要走 GNU `L` 条目或 ustar 的 prefix 字段。
 * 用 `byteArrayOf(1,2,3)` 是测不出这些的 —— 那种测试只会给人"已经验证过"的错觉。
 *
 * 样本从哪来（**找不到就 SKIP 并说明原因，绝不假装测过**）
 * ------------------------------------------------------
 *   1. 真机存储上的真实 OTA zip（几 GB，`*-update-full*.zip`）：扫 `Download` 目录找；
 *   2. 真机存储上的真实 boot.img / init_boot.img：`03-内核镜像/roms` 下；
 *   3. 真机存储上的真实小 zip（AnyKernel3 那种）；
 *   4. 本机的真实 `.tar.gz`（Ubuntu rootfs 包，用来测"包内没有 boot.img"的报错）；
 *   5. 用 `tar` / `python3` **独立实现**现场造包（tar.gz、payload.bin zip），
 *      内容仍是真实 boot.img 的字节，最后逐字节对账 —— 造包工具与解析代码
 *      不是同一个实现，才谈得上"独立"。
 *
 * 一条硬规矩：**不许把失败写成成功**。任何"样本不在 / 工具不在"都用 assumeTrue 跳过，
 * 并在消息里写清楚跳过的原因。
 */
class RomPackageExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ───────────────────────────── 样本发现 ─────────────────────────────

    /**
     * 真机存储在本机有两个等价视图：容器里是 `/mnt/sdcard`（chroot-distro 绑进来的），
     * 应用/宿主侧是 `/storage/emulated/0`。两份都列上，谁先命中用谁（同一个文件，
     * 不会重复扫）。
     */
    private val storageRoots = listOf("/mnt/sdcard", "/storage/emulated/0", "/sdcard")

    private fun findFile(roots: List<File>, maxDepth: Int, accept: (File) -> Boolean): File? {
        var visited = 0
        for (root in roots) {
            if (!root.isDirectory) continue
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty()) {
                val (dir, depth) = queue.removeFirst()
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    if (++visited > 40000) return null // 别为了找样本把测试卡死
                    if (child.isDirectory) {
                        if (depth < maxDepth) queue.add(child to depth + 1)
                    } else if (accept(child)) {
                        return child
                    }
                }
            }
        }
        return null
    }

    /**
     * 本机的样本目录名带中文（`ksuroot项目/03-内核镜像/roms`）。
     *
     * 而 JVM 拼路径用的是 `sun.jnu.encoding`：容器里 `LANG` 为空时它是
     * **ANSI_X3.4-1968（纯 ASCII）**，`new File("…/ksuroot项目/…")` 直接 exists=false，
     * 目录列举出来是 `ksuroot??????`。此时样本"找不到"不是样本的问题，
     * 而是环境的问题 —— 所以下面 [sampleHint] 会把话说明白，
     * 免得报告里出现一个看不出原因的 SKIPPED（这个工程吃过这种亏）。
     */
    private val cjkPathUsable: Boolean =
        System.getProperty("sun.jnu.encoding").orEmpty().contains("UTF", ignoreCase = true)

    private fun sampleHint(): String = if (cjkPathUsable) {
        ""
    } else {
        "（本机 JVM 的 sun.jnu.encoding=${System.getProperty("sun.jnu.encoding")}，" +
            "中文路径列不出来 —— 用 LANG=C.UTF-8 重跑，或用 KSU_TEST_BOOT_IMG 直接指定镜像）"
    }

    /** 取样本：拿不到就 assumeTrue 跳过（消息里必须写清为什么），拿得到就返回非空 File。 */
    private fun requireSample(file: File?, what: String): File {
        assumeTrue("找不到$what，跳过", file != null)
        return file!!
    }

    /** 取外部工具（tar / python3）：本机没有就跳过 —— 造包必须用独立实现。 */
    private fun requireTool(path: String?, what: String): String {
        assumeTrue("本机没有 $what，无法用独立实现造包/解压，跳过", path != null)
        return path!!
    }

    private fun underStorage(vararg sub: String): List<File> =
        storageRoots.map { File(it, sub.joinToString("/")) }

    /** 真实 OTA zip：几 GB 的 `*-update-full*.zip`。 */
    private val realOtaZip: File? by lazy {
        val env = System.getenv("KSU_TEST_OTA_ZIP")
        if (!env.isNullOrBlank() && File(env).isFile) {
            File(env)
        } else {
            findFile(underStorage("Download") + underStorage("下载"), 4) { file ->
                file.name.endsWith(".zip", ignoreCase = true) && file.length() > (512L shl 20)
            }
        }
    }

    /** 真实的小 zip（AnyKernel3 之类），用来测"没有 payload.bin 也没有 boot.img"的报错。 */
    private val realSmallZip: File? by lazy {
        findFile(underStorage("ksuroot项目"), 6) { file ->
            file.name.endsWith(".zip", ignoreCase = true) &&
                file.length() in (1L shl 10)..(64L shl 20)
        }
    }

    /** 真实镜像：优先最小的那份（测试要快），但必须是完整镜像（≥ 8 MiB）。 */
    private val realBootImage: File? by lazy {
        val env = System.getenv("KSU_TEST_BOOT_IMG")
        if (!env.isNullOrBlank() && File(env).isFile) {
            File(env)
        } else {
            val candidates = ArrayList<File>()
            for (root in underStorage("ksuroot项目", "03-内核镜像", "roms")) {
                findFileAll(root, 4, candidates) { file ->
                    file.name == "boot.img" && file.length() >= (8L shl 20)
                }
            }
            candidates.minByOrNull { it.length() }
        }
    }

    private fun findFileAll(
        root: File,
        maxDepth: Int,
        out: MutableList<File>,
        accept: (File) -> Boolean,
    ) {
        if (!root.isDirectory) return
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            for (child in dir.listFiles() ?: continue) {
                if (++visited > 40000) return
                if (child.isDirectory) {
                    if (depth < maxDepth) queue.add(child to depth + 1)
                } else if (accept(child)) {
                    out.add(child)
                }
            }
        }
    }

    /** 真实 tar.gz（本机的 Ubuntu rootfs 包）：用来测"gzip+tar 里没有 boot.img"的报错。 */
    private val realTarGz: File? by lazy {
        val env = System.getenv("KSU_TEST_TGZ")
        if (!env.isNullOrBlank() && File(env).isFile) {
            File(env)
        } else {
            val homes = listOf(
                "/data/data/com.dsharnessmobile.shell/files/home/tmp",
                System.getProperty("user.home") + "/tmp",
            ).map(::File)
            findFile(homes, 3) { file ->
                (file.name.endsWith(".tar.gz") || file.name.endsWith(".tgz")) && file.length() > (1L shl 20)
            }
        }
    }

    // ───────────────────────────── 已知样本的期望值 ─────────────────────────────

    /**
     * 这份包的期望值**不是**由本 Kotlin 代码算出来的，而是用
     * `03-内核镜像/roms/payload_pull.py` 的本地版（Python + zstd CLI，与本实现完全
     * 无关的一条路径）在同一份包上跑出来的。两边一致 = 移植没有语义偏差。
     *
     * 只对**已知文件名**生效：用户以后换一份包，不至于因为哈希对不上而误报失败
     * （那种情况下退化为结构性断言 + BootFormatDetector 交叉识别）。
     */
    private val knownOtaExpectations = mapOf(
        "PD2444_A_15.0.22.2.W10.V000L1-update-full(1).zip" to mapOf(
            "boot" to Expect(100663296L, "6fe4c0252c9be7c6034f17129f1027d8be67b626a51bcda360557b0e17a7a667"),
            "vendor_boot" to Expect(100663296L, "c3b2620f97ea7cc0c388e6f88293ee5ab764bbd5619eb6fcc5a0ae1f87de9ddc"),
        ),
    )

    private data class Expect(val size: Long, val sha256: String?)

    // ───────────────────────────── 用例：格式识别 ─────────────────────────────

    @Test
    fun `detect 认得出真实的完整 OTA zip`() {
        val zip = requireSample(realOtaZip, "真实 OTA zip（Download 下没有 >512 MiB 的 zip）")
        println("[样本] 真实 OTA zip = ${zip.absolutePath} (${zip.length()} 字节)")
        assertEquals(RomPackageExtractor.Kind.OTA_ZIP, RomPackageExtractor.detect(zip))
    }

    @Test
    fun `detect 对真实 boot_img 与普通文本返回 UNKNOWN`() {
        val img = requireSample(realBootImage, "真实 boot.img" + sampleHint())
        assertEquals(RomPackageExtractor.Kind.UNKNOWN, RomPackageExtractor.detect(img!!))

        // 用一份真实的文本文件（不是造出来的字节数组）
        val text = File(tmp.root, "note.txt").apply { writeText("这不是一个刷机包。\n") }
        assertEquals(RomPackageExtractor.Kind.UNKNOWN, RomPackageExtractor.detect(text))
        assertEquals(RomPackageExtractor.Kind.UNKNOWN, RomPackageExtractor.detect(File(tmp.root, "不存在.zip")))
    }

    // ───────────────────────────── 用例：形态 A（payload.bin） ─────────────────────────────

    @Test
    fun `真实 OTA 包 分区清单里能看到 boot 与 vendor_boot`() {
        val zip = requireSample(realOtaZip, "真实 OTA zip")
        val names = RomPackageExtractor.listPartitions(zip!!)
        println("[样本] ${zip.name} 里共 ${names.size} 个分区：${names.sorted().joinToString(", ")}")
        assertTrue("分区清单里应当有 boot，实际：$names", "boot" in names)
        assertTrue("分区清单里应当有 vendor_boot，实际：$names", "vendor_boot" in names)
        assertTrue("完整包的分区数不该这么少：${names.size}", names.size >= 10)
    }

    @Test
    fun `真实 OTA 包 取出 boot 与参考实现 payload_pull_py 的 sha256 一致`() {
        val zip = requireSample(realOtaZip, "真实 OTA zip")
        val expected = knownOtaExpectations[zip.name]?.get("boot")
        if (expected == null) {
            println("[警告] ${zip.name} 不在已知样本表里，只做结构性断言")
        }

        val result = RomPackageExtractor.extract(zip, setOf("boot"))
        val boot = result["boot"]
        assertNotNull("没有取到 boot（返回的键：${result.keys}）", boot)

        println("[结果] boot = ${boot!!.size} 字节, sha256=${sha256(boot)}, head=${hex(boot, 8)}")
        if (expected != null) {
            assertEquals("boot 长度与参考实现不一致", expected.size, boot.size.toLong())
            assertEquals("boot 内容 sha256 与参考实现不一致", expected.sha256, sha256(boot))
        }
        assertTrue("boot 必须是 4096 的整数倍（payload 按块组织），实际 ${boot.size}", boot.size % 4096 == 0)
        // 与工程里**已有的**容器识别器交叉验证：解出来的必须真是一份 boot 镜像。
        val info = BootFormatDetector.detect(boot)
        assertEquals(
            "解出来的 boot 不是 AOSP boot.img（识别结果：${info.summary} / ${info.evidence}）",
            BootFormatDetector.Format.AOSP_BOOT, info.format,
        )
    }

    @Test
    fun `真实 OTA 包 取出 vendor_boot 与参考实现一致`() {
        val zip = requireSample(realOtaZip, "真实 OTA zip")
        val expected = knownOtaExpectations[zip.name]?.get("vendor_boot")

        val result = RomPackageExtractor.extract(zip, setOf("vendor_boot"))
        val vendorBoot = result["vendor_boot"]
        assertNotNull("没有取到 vendor_boot（返回的键：${result.keys}）", vendorBoot)

        println("[结果] vendor_boot = ${vendorBoot!!.size} 字节, sha256=${sha256(vendorBoot)}")
        if (expected != null) {
            assertEquals("vendor_boot 长度与参考实现不一致", expected.size, vendorBoot.size.toLong())
            assertEquals("vendor_boot 内容 sha256 与参考实现不一致", expected.sha256, sha256(vendorBoot))
        }
        val info = BootFormatDetector.detect(vendorBoot)
        assertEquals(
            "解出来的 vendor_boot 不是 VNDRBOOT（识别结果：${info.summary}）",
            BootFormatDetector.Format.VENDOR_BOOT, info.format,
        )
    }

    @Test
    fun `真实 OTA 包 一次取两个分区都拿得到`() {
        val zip = requireSample(realOtaZip, "真实 OTA zip")
        val result = RomPackageExtractor.extract(zip!!, setOf("boot", "vendor_boot"))
        assertNotNull("boot 没取到", result["boot"])
        assertNotNull("vendor_boot 没取到", result["vendor_boot"])
        assertEquals("不该多出别的键：${result.keys}", 2, result.size)
    }

    @Test
    fun `真实 OTA 包 要不存在的分区时 报错必须列出包里真实分区`() {
        val zip = requireSample(realOtaZip, "真实 OTA zip")
        try {
            RomPackageExtractor.extract(zip!!, setOf("my_missing_partition"))
            fail("包里没有这个分区，却既没抛异常也没说明 —— 这正是本工程最反感的静默失败")
        } catch (e: RomPackageExtractor.ExtractException) {
            val message = e.message.orEmpty()
            println("[报错文案] $message")
            assertTrue("报错要说清是 payload 清单里没有：$message", message.contains("payload.bin"))
            assertTrue("报错要列出包里真实存在的分区（boot）：$message", message.contains("boot"))
            assertTrue("报错要列出包里真实存在的分区（vendor_boot）：$message", message.contains("vendor_boot"))
        }
    }

    // ───────────────────────────── 用例：真 zip / 真 tgz 的错误路径 ─────────────────────────────

    @Test
    fun `真实小 zip 里没有 payload_bin 也没有 boot_img 时报错要列出包内条目`() {
        val zip = requireSample(realSmallZip, "真实的小 zip 样本" + sampleHint())
        // 用 JDK 自己的 ZipFile 读出真实条目名，作为对账依据（独立实现）
        val entries = ZipFile(zip).use { zf -> zf.entries().toList().map { it.name } }
        assumeTrue("样本 zip 是空的，跳过", entries.isNotEmpty())
        println("[样本] ${zip.name}，${entries.size} 个条目：${entries.take(5)}")

        try {
            RomPackageExtractor.extract(zip, setOf("boot"))
            fail("这个 zip 里没有 boot，却既没抛异常也没说明")
        } catch (e: RomPackageExtractor.ExtractException) {
            val message = e.message.orEmpty()
            println("[报错文案] ${message.take(400)}")
            assertTrue("报错要说清这是个 ZIP：$message", message.contains("ZIP"))
            assertTrue("报错要列出包内条目：$message", message.contains("包内条目"))
            assertTrue(
                "报错里至少要出现一个**真实存在**的条目名（来自 JDK ZipFile 的独立读取）",
                entries.any { message.contains(it) },
            )
        }
    }

    @Test
    fun `真实 tar_gz 里没有 boot_img 时报错要说明是 fastboot 包并列出条目`() {
        val tgz = requireSample(realTarGz, "真实 .tar.gz 样本")
        println("[样本] 真实 tar.gz = ${tgz.absolutePath} (${tgz.length()} 字节)")
        assertEquals(RomPackageExtractor.Kind.FASTBOOT_TGZ, RomPackageExtractor.detect(tgz))

        try {
            RomPackageExtractor.extract(tgz, setOf("boot"))
            fail("这个 tar.gz 里没有 boot.img，却既没抛异常也没说明")
        } catch (e: RomPackageExtractor.ExtractException) {
            val message = e.message.orEmpty()
            println("[报错文案] ${message.take(400)}")
            assertTrue("报错要说清这是 gzip + tar：$message", message.contains("gzip + tar"))
            assertTrue("报错要列出包内条目：$message", message.contains("包内条目"))
            assertTrue("报错要说明想要的是什么：$message", message.contains("boot.img"))
        }
    }

    // ───────────────────────────── 用例：形态 B（tar.gz），用独立实现现场造包 ─────────────────────────────

    @Test
    fun `tar_gz 通道 用 tar 命令造的 fastboot 包能逐字节取出真实 boot_img`() {
        val tar = requireTool(
            which("tar", listOf("/usr/bin/tar", "/bin/tar", "/data/data/com.dsharnessmobile.shell/files/usr/bin/tar")),
            "tar 命令",
        )
        val src = requireSample(realBootImage, "真实 boot.img" + sampleHint())
        println("[样本] 真实 boot.img = ${src!!.absolutePath} (${src.length()} 字节)")

        val root = tmp.newFolder("fastboot-src")
        val images = File(root, "images").apply { mkdirs() }
        // ① 目标**前面**先放一个几十 MiB 的填充条目（模拟 super.img）：
        //    证明解析是流式跳过的，而不是"把整个包读进内存"。
        val filler = File(images, "super.img")
        filler.outputStream().use { out ->
            val chunk = ByteArray(1 shl 20)
            repeat(32) { out.write(chunk) }
        }
        // ② 再来一个超长路径（>100 字节）：GNU tar 会写成 `L` 类型条目，
        //    这正是真实 ROM 里会遇到的形态，漏了它就会把条目名读成乱码。
        val longDir = File(images, "a".repeat(92)).apply { mkdirs() }
        File(longDir, "filler.img").writeBytes(ByteArray(64 * 1024))
        // ③ 目标本体：真实镜像的字节
        File(images, "boot.img").writeBytes(src.readBytes())
        File(root, "flash-all.sh").writeText("#!/bin/sh\nfastboot flash boot images/boot.img\n")

        val tgz = File(tmp.root, "fastboot-sample.tgz")
        val (code, output) = run(
            listOf(tar, "-czf", tgz.absolutePath, "-C", root.absolutePath, "images", "flash-all.sh")
        )
        assertEquals("tar 造包失败：$output", 0, code)
        println("[样本] 造出的 fastboot 包 = ${tgz.length()} 字节（tar 实现：$tar）")

        assertEquals(RomPackageExtractor.Kind.FASTBOOT_TGZ, RomPackageExtractor.detect(tgz))

        val found = RomPackageExtractor.find(tgz, setOf("boot"))
        assertEquals("应当只取到 boot 一份", 1, found.size)
        assertEquals("Found.name 应当是包内条目名", "images/boot.img", found[0].name)
        assertEquals("取出的字节数应当与真实镜像一致", src.length(), found[0].bytes.size.toLong())
        assertEquals(
            "取出的内容与真实镜像不逐字节一致 —— tar/gzip 通道有语义偏差",
            sha256(src.readBytes()), sha256(found[0].bytes),
        )
        println("[结果] 从 tar.gz 取出的 boot.img sha256=${sha256(found[0].bytes)}（与源文件一致）")
    }

    @Test
    fun `未压缩 tar 通道 同样能取出真实 boot_img`() {
        val tar = requireTool(
            which("tar", listOf("/usr/bin/tar", "/bin/tar", "/data/data/com.dsharnessmobile.shell/files/usr/bin/tar")),
            "tar 命令",
        )
        val src = requireSample(realBootImage, "真实 boot.img" + sampleHint())

        val root = tmp.newFolder("plain-tar-src")
        val images = File(root, "images").apply { mkdirs() }
        File(images, "boot.img").writeBytes(src.readBytes())
        val plainTar = File(tmp.root, "fastboot-sample.tar")
        val (code, output) = run(listOf(tar, "-cf", plainTar.absolutePath, "-C", root.absolutePath, "images"))
        assertEquals("tar 造包失败：$output", 0, code)

        // 未压缩 tar 靠偏移 257 的 "ustar" 认出来（厂商偶尔直接发 .tar）
        assertEquals(RomPackageExtractor.Kind.FASTBOOT_TGZ, RomPackageExtractor.detect(plainTar))
        val bytes = RomPackageExtractor.extract(plainTar, setOf("boot"))["boot"]
        assertNotNull("没有取到 boot", bytes)
        assertEquals("取出的字节数应当与真实镜像一致", src.length(), bytes!!.size.toLong())
        assertEquals("内容不一致", sha256(src.readBytes()), sha256(bytes))
    }

    // ───────────────────────────── 用例：payload.bin（含 deflate 存放），python 独立造包 ─────────────────────────────

    /** python 造包的结果：期望值由 python 侧独立算出，写进 expect.txt。 */
    private class PayloadFixture(val dir: File) {
        val stored: File get() = File(dir, "fixture_stored.zip")
        val deflated: File get() = File(dir, "fixture_deflate.zip")
        val expectations: Map<String, Expect> by lazy {
            File(dir, "expect.txt").readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val parts = line.split('\t')
                    parts[0] to Expect(parts[1].toLong(), parts[2])
                }
        }

        val opsUsed: String by lazy {
            File(dir, "expect.txt").readLines().firstOrNull { it.startsWith("#ops") }.orEmpty()
        }
    }

    private fun buildPayloadFixture(): PayloadFixture? {
        val python = requireTool(
            which(
                "python3",
                listOf("/usr/bin/python3", "/usr/local/bin/python3",
                    "/data/data/com.dsharnessmobile.shell/files/usr/bin/python3"),
            ),
            "python3",
        )
        val src = requireSample(realBootImage, "真实 boot.img（造 payload 需要真实镜像字节）" + sampleHint())

        val dir = tmp.newFolder("payload-fixture")
        val script = File(tmp.root, "make_payload_fixture.py").apply { writeText(PAYLOAD_FIXTURE_PY) }
        val (code, output) = run(listOf(python, script.absolutePath, dir.absolutePath, src.absolutePath))
        println("[造包] exit=$code\n$output")
        if (code == 3) {
            assumeTrue("造包工具缺少依赖，跳过：$output", false)
        }
        assertEquals("python 造 payload.bin 失败：$output", 0, code)
        return PayloadFixture(dir)
    }

    @Test
    fun `payload 以 stored 存放在 zip 里时能还原 REPLACE ZERO XZ ZSTD 各类操作`() {
        val fixture = buildPayloadFixture() ?: return
        verifyPayloadFixture(fixture, fixture.stored, expectedZipMethod = 0)
    }

    @Test
    fun `payload 被 deflate 压缩存放在 zip 里时也能还原`() {
        val fixture = buildPayloadFixture() ?: return
        verifyPayloadFixture(fixture, fixture.deflated, expectedZipMethod = 8)
    }

    private fun verifyPayloadFixture(fixture: PayloadFixture, zip: File, expectedZipMethod: Int) {
        // 先确认这份 fixture 真的走了想测的那条路（否则测试等于什么都没测）
        val method = ZipFile(zip).use { it.getEntry("payload.bin").method }
        assertEquals("fixture 里 payload.bin 的存放方式不对", expectedZipMethod, method)
        println("[样本] ${zip.name}（payload.bin method=$method）${fixture.opsUsed}")

        assertEquals(RomPackageExtractor.Kind.OTA_ZIP, RomPackageExtractor.detect(zip))
        val names = RomPackageExtractor.listPartitions(zip)
        assertTrue("清单里应当有 boot：$names", "boot" in names)
        assertTrue("清单里应当有 init_boot：$names", "init_boot" in names)

        val expected = fixture.expectations["boot"]
        assertNotNull("expect.txt 里没有 boot 的期望值", expected)
        val result = RomPackageExtractor.extract(zip, setOf("boot"))
        val boot = result["boot"]
        assertNotNull("没有取到 boot（返回的键：${result.keys}）", boot)
        assertEquals("boot 长度与 python 侧独立算出的不一致", expected!!.size, boot!!.size.toLong())
        assertEquals("boot 内容与 python 侧独立算出的不一致", expected.sha256, sha256(boot))

        // 一次取两份也要对
        val both = RomPackageExtractor.extract(zip, setOf("boot", "init_boot"))
        assertEquals("应当取到两份", 2, both.size)
        val initExpected = fixture.expectations["init_boot"]!!
        assertEquals(initExpected.size, both["init_boot"]!!.size.toLong())
        assertEquals(initExpected.sha256, sha256(both["init_boot"]!!))

        // 不存在的分区：必须抛，且把包里真实分区列出来
        try {
            RomPackageExtractor.extract(zip, setOf("nope"))
            fail("包里没有 nope 分区，却既没抛异常也没说明")
        } catch (e: RomPackageExtractor.ExtractException) {
            val message = e.message.orEmpty()
            assertTrue("报错要列出包内真实分区：$message", message.contains("boot") && message.contains("init_boot"))
        }
    }

    @Test
    fun `fixture 里的 XZ 数据能被工程内置的 XzDecoder 直接解开`() {
        val fixture = buildPayloadFixture() ?: return
        val blob = File(fixture.dir, "xz_op.bin")
        val raw = File(fixture.dir, "xz_op.raw")
        assumeTrue("fixture 没带 XZ 数据，跳过", blob.isFile && raw.isFile)
        val expected = raw.readBytes()
        val decoded = XzDecoder.decode(blob.readBytes(), maxOutput = expected.size)
        assertArrayEquals(
            "内置 XZ 解码器解出的内容与 python 侧不一致（若这里失败，说明提取时走的是 xz CLI 兜底路径）",
            expected, decoded,
        )
    }

    // ───────────────────────────── 小工具 ─────────────────────────────

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun hex(data: ByteArray, count: Int): String =
        data.take(count).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun which(name: String, known: List<String>): String? {
        for (dir in System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        val prefix = System.getenv("PREFIX").orEmpty()
        if (prefix.isNotBlank()) {
            val candidate = File(prefix, "bin/$name")
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        for (path in known) {
            val candidate = File(path)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }

    /** 跑外部命令，返回 (退出码, stdout+stderr)。用独立实现造包时靠它。 */
    private fun run(command: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        return code to output
    }

    companion object {
        /**
         * 用 python 造一份**符合 update_engine 规范**的 payload.bin，并连同 zip 一起写出。
         *
         * 为什么用 python 而不是在 Kotlin 里造：造包的人必须是**另一套实现**，
         * 否则"写包"和"读包"共用同一个错误假设，测试就白做了。
         * 造包内容仍然是**真实 boot.img 的字节**，期望值也在 python 侧独立算出。
         */
        private val PAYLOAD_FIXTURE_PY = """
import bz2, hashlib, lzma, os, shutil, struct, subprocess, sys, zipfile

BLOCK = 4096
MIB = 1024 * 1024
SKIP_EXIT = 3


def which(name, extra):
    found = shutil.which(name)
    if found:
        return found
    for path in extra:
        if os.path.exists(path):
            return path
    return None


def varint(value):
    out = bytearray()
    while True:
        part = value & 0x7F
        value >>= 7
        if value:
            out.append(part | 0x80)
        else:
            out.append(part)
            return bytes(out)


def tag(field, wire):
    return varint((field << 3) | wire)


def length_delimited(field, data):
    return tag(field, 2) + varint(len(data)) + data


def varint_field(field, value):
    return tag(field, 0) + varint(value)


def extent(start_block, num_blocks):
    return varint_field(1, start_block) + varint_field(2, num_blocks)


def main():
    outdir, src = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=True)
    raw = open(src, 'rb').read()
    if len(raw) < 8 * MIB:
        print('SKIP: 源镜像不足 8 MiB')
        return SKIP_EXIT

    zstd = which('zstd', ['/data/data/com.dsharnessmobile.shell/files/usr/bin/zstd', '/usr/bin/zstd'])
    bzip2 = which('bzip2', ['/usr/bin/bzip2', '/data/data/com.dsharnessmobile.shell/files/usr/bin/bzip2'])
    if not zstd:
        print('SKIP: 找不到 zstd，无法造 REPLACE_ZSTD 数据')
        return SKIP_EXIT

    def zstd_compress(data):
        done = subprocess.run([zstd, '-q', '-c', '-3'], input=data, capture_output=True)
        if done.returncode != 0:
            raise RuntimeError('zstd 压缩失败: ' + done.stderr.decode('utf-8', 'replace'))
        return done.stdout

    # 每个操作是五元组：(类型, 压缩后的数据, 明文, 起始块, 块数)
    # 明文单独留着，是为了让期望值由**另一条路径**（直接拼明文）算出来，
    # 而不是复用"压过的数据"——后者会让造包与解包共用同一个假设。
    # boot 分区：REPLACE + ZERO + REPLACE_XZ + REPLACE_ZSTD (+ REPLACE_BZ)
    boot = [
        (0, raw[0:2 * MIB], raw[0:2 * MIB], 0, 512),                    # REPLACE 2 MiB
        (6, None, None, 512, 256),                                      # ZERO 1 MiB
        (8, lzma.compress(raw[2 * MIB:3 * MIB], format=lzma.FORMAT_XZ),
            raw[2 * MIB:3 * MIB], 768, 256),                            # REPLACE_XZ 1 MiB
        (14, zstd_compress(raw[3 * MIB:6 * MIB]), raw[3 * MIB:6 * MIB], 1024, 768),
    ]
    ops_used = '0,6,8,14'
    if bzip2:
        boot.append((1, bz2.compress(raw[6 * MIB:6 * MIB + 512 * 1024]),
                     raw[6 * MIB:6 * MIB + 512 * 1024], 1792, 128))
        ops_used = ops_used + ',1'

    # 第二个分区：证明"要哪个取哪个"，而不是把整个 payload 当一份数据
    init_boot = [(0, raw[7 * MIB:8 * MIB], raw[7 * MIB:8 * MIB], 0, 256)]

    blobs = []
    # 单元素列表当可变容器：嵌套函数里 "global" 指的是**模块**作用域，改不到 main 的局部变量
    # （这个坑真踩过一次，fixture 直接 NameError）。
    cursor = [0]

    def offsets_for(ops):
        # 必须**自己往前推**：data_offset 是相对数据段起点的偏移，
        # 早先这里直接取 cursor[0] 没推进，结果所有操作都指向 0 号偏移
        # （被"独立实现读一遍"的交叉校验抓出来了）。
        out = []
        position = cursor[0]
        for (optype, blob, plain, start_block, num_blocks) in ops:
            if blob is None or len(blob) == 0:
                out.append((0, 0))
            else:
                out.append((position, len(blob)))
                position += len(blob)
        return out

    def append_blobs(ops):
        for (optype, blob, plain, start_block, num_blocks) in ops:
            if blob is not None and len(blob) > 0:
                blobs.append(blob)
                cursor[0] += len(blob)

    boot_offsets = offsets_for(boot)
    append_blobs(boot)
    init_offsets = offsets_for(init_boot)
    append_blobs(init_boot)

    def partition_update(name, ops, offsets):
        body = length_delimited(1, name.encode('utf-8'))
        for index, (optype, blob, plain, start_block, num_blocks) in enumerate(ops):
            data_offset, data_length = offsets[index]
            op = varint_field(1, optype) + varint_field(2, data_offset) + varint_field(3, data_length)
            op = op + length_delimited(6, extent(start_block, num_blocks))
            body = body + length_delimited(8, op)
        return length_delimited(13, body)

    manifest = varint_field(3, BLOCK)
    manifest = manifest + partition_update('boot', boot, boot_offsets)
    manifest = manifest + partition_update('init_boot', init_boot, init_offsets)

    payload = b'CrAU' + struct.pack('>Q', 2) + struct.pack('>Q', len(manifest)) + struct.pack('>I', 0)
    payload = payload + manifest + b''.join(blobs)

    def build_image(ops):
        # 期望镜像用**明文**拼（压缩块是给解析器解的，不是期望值本身）
        last_block = 0
        for (optype, blob, plain, start_block, num_blocks) in ops:
            last_block = max(last_block, start_block + num_blocks)
        image = bytearray(last_block * BLOCK)
        for (optype, blob, plain, start_block, num_blocks) in ops:
            if optype in (6, 7):
                continue
            size = num_blocks * BLOCK
            piece = plain[:size]
            if len(piece) != size:
                raise RuntimeError('明文长度与目标区间不一致')
            image[start_block * BLOCK:start_block * BLOCK + size] = piece
        return bytes(image)

    boot_image = build_image(boot)
    init_image = build_image(init_boot)

    with zipfile.ZipFile(os.path.join(outdir, 'fixture_stored.zip'), 'w', zipfile.ZIP_STORED) as zf:
        zf.writestr('payload.bin', payload)
        zf.writestr('META-INF/com/android/metadata', 'post-build=fixture\n')
    with zipfile.ZipFile(os.path.join(outdir, 'fixture_deflate.zip'), 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.writestr('payload.bin', payload)
        zf.writestr('META-INF/com/android/metadata', 'post-build=fixture\n')

    # 给"内置 XzDecoder 能不能解 python 造的 XZ"单独留一份料
    open(os.path.join(outdir, 'xz_op.bin'), 'wb').write(boot[2][1])
    open(os.path.join(outdir, 'xz_op.raw'), 'wb').write(raw[2 * MIB:3 * MIB])

    with open(os.path.join(outdir, 'expect.txt'), 'w') as fh:
        fh.write('boot\t%d\t%s\n' % (len(boot_image), hashlib.sha256(boot_image).hexdigest()))
        fh.write('init_boot\t%d\t%s\n' % (len(init_image), hashlib.sha256(init_image).hexdigest()))
        fh.write('#ops boot=%s bzip2=%s\n' % (ops_used, 'yes' if bzip2 else 'no'))

    print('payload=%d 字节 manifest=%d boot=%d init_boot=%d ops=%s bzip2=%s'
          % (len(payload), len(manifest), len(boot_image), len(init_image), ops_used, 'yes' if bzip2 else 'no'))
    return 0


sys.exit(main())
"""
    }
}
