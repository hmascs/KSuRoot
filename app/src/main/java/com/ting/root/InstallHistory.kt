package com.ting.root

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class InstallRunResult {
    Running,
    Succeeded,
    Failed,
}

data class InstallHistoryEntry(
    val id: String,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val result: InstallRunResult,
    val log: String,
    val profileId: String? = null,
    val usedShizuku: Boolean = false,
)

class InstallHistoryStore(private val context: Context) {
    private val directory = File(context.filesDir, "install-history").apply { mkdirs() }

    /**
     * `AtomicFile` 缓存 —— 按 entry id 复用。
     *
     * 安装过程中同一份记录要写几十上百次，而 `AtomicFile` 的构造会去 stat
     * 目标文件与 `.bak` 文件。建造成本不大，但完全没有复用的必要，顺手省掉。
     */
    private val atomicFiles = HashMap<String, AtomicFile>()

    fun load(): List<InstallHistoryEntry> = directory
        .listFiles { file -> file.extension == "json" }
        .orEmpty()
        .mapNotNull(::decodeOrQuarantine)
        .sortedByDescending(InstallHistoryEntry::startedAtMillis)

    fun closeInterruptedRuns(): List<InstallHistoryEntry> = load().map { entry ->
        if (entry.result == InstallRunResult.Running) {
            entry.copy(
                completedAtMillis = System.currentTimeMillis(),
                result = InstallRunResult.Failed,
            ).also { save(it) }
        } else {
            entry
        }
    }

    /**
     * 清理某个记录的内存态与磁盘尾文件。安装结束、UI 已经拿到最终 entry 之后调用。
     */
    fun release(id: String) {
        synchronized(atomicFiles) { atomicFiles.remove(id) }
        runCatching {
            File(directory, "$id.json.bak").takeIf(File::exists)?.delete()
        }.onFailure { Log.w(TAG, "清理历史尾文件失败: $id", it) }
    }

    fun create(): InstallHistoryEntry = InstallHistoryEntry(
        id = UUID.randomUUID().toString(),
        startedAtMillis = System.currentTimeMillis(),
        completedAtMillis = null,
        result = InstallRunResult.Running,
        log = "",
        usedShizuku = AppPreferences.shizukuMode(context),
    ).also { save(it) }

    /**
     * 完整落盘：`fsync` + 原子替换。
     *
     * 只用于**低频且必须持久**的时刻 —— 新建记录、切换 profile、结束记录。
     * 提权过程中高频刷新的日志不要走这里（见 [saveDraft]）。
     */
    fun save(entry: InstallHistoryEntry) {
        val atomicFile = atomicFileFor(entry.id)
        val output = atomicFile.startWrite()
        try {
            output.write(encode(entry).toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    /**
     * 草稿落盘：同一套 AtomicFile 原子替换流程，但**跳过 `fd.sync()`**。
     *
     * 为什么单独留一条路径：`fd.sync()` 是一次真正的 fsync 系统调用，在 UFS/eMMC
     * 上通常 1~10ms，遇到后台刷写或闪存忙时会飙到几十毫秒。提权过程中日志是
     * 每条一行往里灌的，原来每行都 fsync 一遍 —— 这份写入发生在 `Dispatchers.IO`
     * 上，不会直接卡住 UI 线程，但它持续占着 IO 线程、和日志轮询抢文件系统带宽，
     * 而且日志本来就是**进度输出**而不是账本：进程被杀时丢掉最后几行完全可接受。
     *
     * 做法是仍然走 `startWrite`/`finishWrite` 的「写临时文件 → rename」原子替换，
     * 所以历史文件**永远不会处于半截状态**，只是不保证断电后最后几行还在。
     * 真正需要保证落盘的三个时刻（create / profile / finish）都仍然调 [save]。
     */
    fun saveDraft(entry: InstallHistoryEntry) {
        val atomicFile = atomicFileFor(entry.id)
        val output = atomicFile.startWrite()
        try {
            output.write(encode(entry).toString().toByteArray(Charsets.UTF_8))
            output.flush()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun atomicFileFor(id: String): AtomicFile = synchronized(atomicFiles) {
        atomicFiles.getOrPut(id) { AtomicFile(File(directory, "$id.json")) }
    }

    private fun encode(entry: InstallHistoryEntry) = JSONObject()
        .put("id", entry.id)
        .put("startedAtMillis", entry.startedAtMillis)
        .put("completedAtMillis", entry.completedAtMillis ?: JSONObject.NULL)
        .put("result", entry.result.name)
        .put("log", entry.log)
        .put("profileId", entry.profileId ?: JSONObject.NULL)
        .put("usedShizuku", entry.usedShizuku)

    private fun decodeOrQuarantine(file: File): InstallHistoryEntry? = try {
        decode(AtomicFile(file).openRead().use { it.readBytes() })
    } catch (_: Throwable) {
        val quarantined = File(directory, "${file.name}.corrupt")
        quarantined.delete()
        file.renameTo(quarantined)
        null
    }

    private fun decode(bytes: ByteArray): InstallHistoryEntry {
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        return InstallHistoryEntry(
            id = value.getString("id"),
            startedAtMillis = value.getLong("startedAtMillis"),
            completedAtMillis = if (value.isNull("completedAtMillis")) {
                null
            } else {
                value.getLong("completedAtMillis")
            },
            result = InstallRunResult.valueOf(value.getString("result")),
            log = value.getString("log"),
            profileId = if (value.isNull("profileId")) {
                null
            } else {
                value.getString("profileId").takeIf(String::isNotBlank)
            },
            usedShizuku = value.optBoolean("usedShizuku", false),
        )
    }

    private companion object {
        const val TAG = "InstallHistory"
    }
}
