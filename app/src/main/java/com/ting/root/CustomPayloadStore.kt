package com.ting.root

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import java.io.File
import java.security.MessageDigest

/**
 * 库房里的一条自定义动态库。
 *
 * [file] 是**真正会被加载**的那一份（应用私有目录里的活动副本），
 * 它随条目一起构造出来，不靠任何全局状态 —— 早先写成"静态持有当前路径"是个隐患：
 * 多线程读、或者切换条目之后旧对象还指着新路径，都会拿到错的载荷。
 */
data class CustomPayloadInfo(
    /** 稳定标识（库房内的相对路径），用于「设为当前」。 */
    val id: String,
    /** 机型分组（库房的一级子目录）。 */
    val device: String,
    val displayName: String,
    val size: Long,
    val sha256: String,
    val importedAtMillis: Long,
    /** 用户可见的落盘路径；拿不到时为库房内的相对路径。 */
    val publicPath: String,
    /** 私有活动副本的绝对路径（构建 [file] 用）。 */
    val activePath: String,
) {
    /** 真正会被 dlopen / LD_PRELOAD 的那份文件。为什么不是 [publicPath]：见 [CustomPayloadStore]。 */
    val file: File get() = File(activePath)
}

/**
 * 用户自定义动态库的**库房**。
 *
 * ### 存放布局
 *
 * ```
 * /storage/emulated/0/Download/动态库/<机型>/<文件名>.so     ← 用户可见，可自己备份/整理
 * /data/data/com.ting.root/files/active_payload/payload.so  ← 真正被加载的那一份
 * ```
 *
 * ### 为什么必须有两份（这条不是啰嗦，是踩过的坑）
 *
 * `Download/` 下那份**不能直接拿来 dlopen**。理由有两层：
 * 1. **可读性**：分区存储下应用只能读「自己经 MediaStore 创建的」那批文件；
 *    一旦用户在文件管理器里改名/移动，或换一台机器同步过来，路径就失效。
 * 2. **SELinux**：提权助手要按路径打开它，而 `/storage` 是 FUSE
 *    （`u:object_r:fuse:s0`）—— 非 `untrusted_app` 的域**根本读不到**，
 *    这一点在 `pm install` 时被 SELinux 当场拦过（`avc: denied { read } ... tclass=file`）。
 *
 * 所以：**库房负责「让用户看得见、管得住」，私有副本负责「一定能被加载」**。
 * 选用某一份 = 把库房那份拷成私有副本。
 *
 * ### 搜寻能力的边界（如实说明，不做承诺）
 *
 * 这个库房**不能自动扫描** `Download/动态库/` 里用户自己丢进去的文件 ——
 * 分区存储下应用看不到别的应用（包括文件管理器）创建的下载文件。
 * 所以库房里的条目只有两个来源：
 * - 本应用写进去的（「载荷构建」的产物、通过「导入」拷进来的）；
 * - 用户通过系统文件选择器（SAF）**显式授权**的那一个文件。
 *
 * 想加一份新的：走「导入」，或直接在「载荷构建」里生成。
 */
object CustomPayloadStore {

    /** 库房根目录名（`Download/动态库/`）。 */
    const val LIBRARY_DIR = "动态库"

    /** 未能识别机型时的分组名。 */
    const val DEFAULT_GROUP = "未分类"

    private const val ACTIVE_DIR = "active_payload"
    private const val ACTIVE_NAME = "payload.so"
    private const val INDEX_FILE = "library/index.tsv"
    private const val MAX_PAYLOAD_BYTES = 256L * 1024 * 1024
    private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    // ────────────────────────── 读取 ──────────────────────────

    /** 当前生效的那一份；没有则返回 null。 */
    fun current(context: Context): CustomPayloadInfo? {
        val active = activeFile(context)
        if (!active.exists()) return null
        val index = readIndex(context)
        val id = activeId(context)
        val hit = index.firstOrNull { it.id == id } ?: index.firstOrNull()
        val size = active.length()
        val sha = hit?.sha256 ?: sha256Of(active) ?: return null
        return CustomPayloadInfo(
            id = hit?.id ?: ACTIVE_NAME,
            device = hit?.device ?: DEFAULT_GROUP,
            displayName = hit?.displayName ?: active.name,
            size = size,
            sha256 = sha,
            importedAtMillis = hit?.importedAtMillis ?: 0L,
            publicPath = hit?.publicPath ?: active.absolutePath,
            activePath = active.absolutePath,
        )
    }

    /**
     * 库房里的全部条目。
     *
     * ⚠️ **只有当前生效的那一条带活动副本路径**，其余的把 [CustomPayloadInfo.activePath]
     * 留空。理由：非当前条目根本没有活动副本，若也填上同一个路径，
     * 调用方拿 `entry.file` 去加载就会**静默地用错载荷**。
     * 留空之后 `File("").canRead()` 为 false，调用方的 `require` 会当场炸出来。
     * 要用某一条，先 [activate] 它，然后使用 [activate] 的返回值。
     */
    fun list(context: Context): List<CustomPayloadInfo> {
        val activeId = activeId(context)
        val activePath = activeFile(context).absolutePath
        return readIndex(context).map { entry ->
            entry.copy(activePath = if (entry.id == activeId) activePath else "")
        }
    }

    /** 当前生效条目的 id。 */
    fun activeId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE_ID, null)

    // ────────────────────────── 写入 ──────────────────────────

    /** 从 SAF 的 Uri 流式导入，并**立刻设为当前**。 */
    fun import(context: Context, uri: Uri, displayName: String): CustomPayloadInfo {
        val (bytes, sha) = readAll(context, uri)
        require(bytes.size > ELF_MAGIC.size && bytes.copyOfRange(0, 4).contentEquals(ELF_MAGIC)) {
            context.getString(R.string.custom_import_invalid)
        }
        val name = sanitize(displayName.ifBlank { "payload.so" })
        return install(context, bytes, sha, name)
    }

    /** 把一份**已经拿在手里**的字节写进库房（「载荷构建」的产物走这条）。 */
    fun save(context: Context, bytes: ByteArray, displayName: String): CustomPayloadInfo {
        require(bytes.size > ELF_MAGIC.size &&
            bytes[0] == ELF_MAGIC[0] && bytes[1] == ELF_MAGIC[1] &&
            bytes[2] == ELF_MAGIC[2] && bytes[3] == ELF_MAGIC[3]
        ) { context.getString(R.string.custom_import_invalid) }
        require(bytes.size.toLong() <= MAX_PAYLOAD_BYTES) {
            context.getString(R.string.custom_import_failed)
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return install(context, bytes, sha, sanitize(displayName.ifBlank { "payload.so" }))
    }

    /** 把库房里已有的某一条设为当前（拷贝成私有副本）。 */
    fun activate(context: Context, id: String): CustomPayloadInfo? {
        val entry = readIndex(context).firstOrNull { it.id == id } ?: return null
        val bytes = readLibraryBytes(context, entry) ?: return null
        writeActive(context, bytes)
        markActive(context, entry.id)
        return entry.copy(sha256 = entry.sha256.ifBlank { sha256Of(bytes) ?: "" })
    }

    /**
     * 从库房删掉一条。
     *
     * 若删的正是**当前生效**的那一条，这里必须把下一条**真正激活**（把字节拷成活动副本），
     * 或者彻底清空 —— 不能只改一个 id 就完事。
     *
     * 旧写法是 `activeFile.delete(); markActive(entries.first()?.id)`：
     * id 指向了下一条，可活动副本已被删掉，于是 `current()` 返回 null，
     * 而库里那条仍显示「当前」—— 界面说正在用它，实际根本没有可加载的文件。
     * 这正是本工程一直在防的那种自相矛盾状态。
     */
    fun remove(context: Context, id: String) {
        val entries = readIndex(context).filterNot { it.id == id }
        writeIndex(context, entries)
        purgeLibraryEntry(context, id)
        if (activeId(context) != id) return

        val next = entries.firstOrNull()
        val bytes = next?.let { readLibraryBytes(context, it) }
        if (next != null && bytes != null) {
            writeActive(context, bytes)
            markActive(context, next.id)
        } else {
            // 库房空了（或下一条读不出来）：彻底清空，别留一个指向空气的 id。
            activeFile(context).delete()
            markActive(context, null)
        }
    }

    /**
     * 清空**当前生效**的那一份（库房里的文件保留）。
     *
     * 只清 active 不动库房：用户点「移除」的语义是"这次别用它"，
     * 而不是"把我辛苦构建出来的产物删掉"。
     */
    fun clear(context: Context) {
        activeFile(context).delete()
        // ⚠️ 这里以前是 `markActive(entries.firstOrNull()?.id)` —— 和 remove() 修掉的那个
        // 老 bug **逐字一样**：活动副本删了，id 却还指着第一条。后果是 current() 返回 null，
        // 而 list() 仍然给那一条填上**非空** activePath（违背它自己的文档不变量），
        // 界面上继续打「当前生效」徽标 —— 说正在用它，其实根本没有可加载的文件。
        // remove() 只在"删掉当前那一条"时才会走到那段逻辑，clear() 是另一条路径，所以漏了。
        markActive(context, null)
    }

    // ────────────────────────── 内部 ──────────────────────────

    private fun install(
        context: Context,
        bytes: ByteArray,
        sha: String,
        requestedName: String,
    ): CustomPayloadInfo {
        require(bytes.size.toLong() <= MAX_PAYLOAD_BYTES) {
            context.getString(R.string.custom_import_failed)
        }
        val device = deviceGroup()
        // 同名同内容视为同一条：重复导入不产生第二份。
        val entries = readIndex(context).toMutableList()
        val id = "$device/$requestedName"
        val existing = entries.indexOfFirst { it.id == id }
        val name = if (existing >= 0 && entries[existing].sha256 != sha) {
            // 同名但内容不同：加哈希后缀，两份都留着，不悄悄覆盖。
            requestedName.substringBeforeLast('.') + "-" + sha.take(8) + ".so"
        } else {
            requestedName
        }
        val finalId = "$device/$name"
        val publicPath = writeLibrary(context, device, name, bytes) ?: finalId
        writeActive(context, bytes)
        val info = CustomPayloadInfo(
            id = finalId,
            device = device,
            displayName = name,
            size = bytes.size.toLong(),
            sha256 = sha,
            importedAtMillis = System.currentTimeMillis(),
            publicPath = publicPath,
            activePath = activeFile(context).absolutePath,
        )
        entries.removeAll { it.id == finalId }
        entries.add(0, info)
        writeIndex(context, entries)
        markActive(context, finalId)
        return info
    }

    /** 把字节写进 `Download/动态库/<机型>/<名字>`（MediaStore，无权限要求）。 */
    private fun writeLibrary(context: Context, device: String, name: String, bytes: ByteArray): String? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + LIBRARY_DIR + "/" + device,
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return try {
            val uri = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            rememberUri(context, "$device/$name", uri.toString())
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$LIBRARY_DIR/$device/$name",
            ).absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun readLibraryBytes(context: Context, entry: CustomPayloadInfo): ByteArray? {
        val uri = uriFor(context, entry.id)
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        }
        return null
    }

    private fun purgeLibraryEntry(context: Context, id: String) {
        val uri = uriFor(context, id) ?: return
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
        forgetUri(context, id)
    }

    /** 私有活动副本 —— 这一份才是被 dlopen / LD_PRELOAD 的那个路径。 */
    private fun activeFile(context: Context): File =
        File(context.filesDir, "$ACTIVE_DIR/$ACTIVE_NAME")

    private fun writeActive(context: Context, bytes: ByteArray) {
        val dir = File(context.filesDir, ACTIVE_DIR).apply { mkdirs() }
        val temporary = File(dir, "$ACTIVE_NAME.part")
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        val target = File(dir, ACTIVE_NAME)
        if (target.exists()) target.delete()
        require(temporary.renameTo(target)) { context.getString(R.string.custom_import_failed) }
        Os.chmod(target.absolutePath, 0b100100100)
    }

    private fun readAll(context: Context, uri: Uri): Pair<ByteArray, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            require(input != null) { context.getString(R.string.custom_import_failed) }
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_PAYLOAD_BYTES) { context.getString(R.string.custom_import_failed) }
                digest.update(buffer, 0, count)
                sink.write(buffer, 0, count)
            }
        }
        require(total > 0) { context.getString(R.string.custom_import_invalid) }
        return sink.toByteArray() to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Of(file: File): String? = runCatching { sha256Of(file.readBytes()) }.getOrNull()

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sanitize(name: String): String {
        val cleaned = name.replace('\n', ' ').replace('\r', ' ')
            .replace('/', '_').replace('\\', '_').trim()
        if (cleaned.isEmpty()) return "payload.so"
        return if (cleaned.endsWith(".so", ignoreCase = true)) cleaned else "$cleaned.so"
    }

    /** 机型分组名：用设备型号，去掉文件名不友好的字符。 */
    private fun deviceGroup(): String {
        val raw = (Build.MODEL ?: "").ifBlank { Build.DEVICE ?: "" }
        val cleaned = raw.replace('/', '_').replace('\\', '_').replace('\n', ' ').trim()
        return cleaned.ifBlank { DEFAULT_GROUP }
    }

    // ---- 索引（TSV，落在私有目录；库房那份是给用户看的，这份是给程序看的）----

    private fun indexFile(context: Context) = File(context.filesDir, INDEX_FILE)

    private fun readIndex(context: Context): List<CustomPayloadInfo> {
        val file = indexFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 6) return@mapNotNull null
                CustomPayloadInfo(
                    id = f[0],
                    device = f[1],
                    displayName = f[2],
                    size = f[3].toLongOrNull() ?: return@mapNotNull null,
                    sha256 = f[4],
                    importedAtMillis = f[5].toLongOrNull() ?: 0L,
                    publicPath = f.getOrNull(6) ?: f[0],
                    // 索引里不存绝对路径（私有目录路径随安装变化），
                    // 读出来时现算 —— 这也是"静态持有"那个隐患的另一半。
                    activePath = activeFile(context).absolutePath,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, entries: List<CustomPayloadInfo>) {
        val file = indexFile(context)
        file.parentFile?.mkdirs()
        file.writeText(
            entries.joinToString("\n") { e ->
                listOf(
                    e.id, e.device, e.displayName, e.size.toString(),
                    e.sha256, e.importedAtMillis.toString(), e.publicPath,
                ).joinToString("\t")
            },
            Charsets.UTF_8,
        )
    }

    private fun markActive(context: Context, id: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ACTIVE_ID, id).apply()
    }

    private fun uriFor(context: Context, id: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("uri:$id", null)

    private fun rememberUri(context: Context, id: String, uri: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("uri:$id", uri).apply()
    }

    private fun forgetUri(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("uri:$id").apply()
    }

    private const val PREFS = "payload_library"
    private const val ACTIVE_ID = "active_id"
}
