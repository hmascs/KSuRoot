package com.ting.root

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.File
import java.security.MessageDigest

data class CustomPayloadInfo(
    val file: File,
    val displayName: String,
    val size: Long,
    val sha256: String,
    val importedAtMillis: Long,
)

/**
 * Stores a user-imported dynamic library (.so) that replaces the downloaded
 * exploit payload. Imported libraries are treated as self-contained payloads:
 * they are executed directly by the CVE-2026-43499 helper via --run-payload.
 */
object CustomPayloadStore {
    private const val DIRECTORY = "custom_payload"
    private const val PAYLOAD_NAME = "payload.so"
    private const val META_NAME = "payload.meta"
    private const val MAX_PAYLOAD_BYTES = 256L * 1024 * 1024
    private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    fun current(context: Context): CustomPayloadInfo? {
        val payload = payloadFile(context)
        val meta = metaFile(context)
        if (!payload.exists() || !meta.exists()) return null
        val fields = meta.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap()
        val size = fields["size"]?.toLongOrNull() ?: return null
        val sha256 = fields["sha256"] ?: return null
        val displayName = fields["name"] ?: payload.name
        val importedAt = fields["importedAt"]?.toLongOrNull() ?: 0L
        if (!payload.exists() || payload.length() != size) return null
        return CustomPayloadInfo(payload, displayName, size, sha256, importedAt)
    }

    fun import(context: Context, uri: Uri, displayName: String): CustomPayloadInfo {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val temporary = File(directory, "$PAYLOAD_NAME.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val magic = ByteArray(4)
        var magicFilled = 0
        context.contentResolver.openInputStream(uri).use { input ->
            require(input != null) { context.getString(R.string.custom_import_failed) }
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (magicFilled < 4) {
                        val take = minOf(4 - magicFilled, count)
                        System.arraycopy(buffer, 0, magic, magicFilled, take)
                        magicFilled += take
                    }
                    total += count
                    require(total <= MAX_PAYLOAD_BYTES) {
                        context.getString(R.string.custom_import_failed)
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        require(total > 0) { context.getString(R.string.custom_import_invalid) }
        require(magicFilled == 4 && magic.contentEquals(ELF_MAGIC)) {
            context.getString(R.string.custom_import_invalid)
        }
        val payload = payloadFile(context)
        if (payload.exists()) payload.delete()
        require(temporary.renameTo(payload)) { context.getString(R.string.custom_import_failed) }
        Os.chmod(payload.absolutePath, 0b100100100)
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val importedAt = System.currentTimeMillis()
        val resolvedName = displayName.ifBlank { payload.name }
        metaFile(context).writeText(
            buildString {
                append("name=").append(resolvedName.replace('\n', ' ').replace('\r', ' ')).append('\n')
                append("size=").append(total).append('\n')
                append("sha256=").append(sha256).append('\n')
                append("importedAt=").append(importedAt).append('\n')
            },
            Charsets.UTF_8,
        )
        return CustomPayloadInfo(payload, resolvedName, total, sha256, importedAt)
    }

    /**
     * 把一份**已经拿在手里**的动态库字节写进「自定义载荷」。
     *
     * 与 [import] 的区别只有来源：那个从 SAF 的 Uri 流式拷贝，这个直接用内存里的字节
     * （「载荷构建」页每次构建出的补丁库就在内存里，没必要先落盘中转一次）。
     * 校验与元数据写法与 [import] 完全一致，所以后续流程无法区分两者。
     */
    fun save(context: Context, bytes: ByteArray, displayName: String): CustomPayloadInfo {
        require(bytes.size > ELF_MAGIC.size &&
            bytes[0] == ELF_MAGIC[0] && bytes[1] == ELF_MAGIC[1] &&
            bytes[2] == ELF_MAGIC[2] && bytes[3] == ELF_MAGIC[3]
        ) { context.getString(R.string.custom_import_invalid) }
        require(bytes.size.toLong() <= MAX_PAYLOAD_BYTES) {
            context.getString(R.string.custom_import_failed)
        }
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val temporary = File(directory, "$PAYLOAD_NAME.part")
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        val payload = payloadFile(context)
        if (payload.exists()) payload.delete()
        require(temporary.renameTo(payload)) { context.getString(R.string.custom_import_failed) }
        Os.chmod(payload.absolutePath, 0b100100100)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val importedAt = System.currentTimeMillis()
        val resolvedName = displayName.ifBlank { payload.name }
        metaFile(context).writeText(
            buildString {
                append("name=").append(resolvedName.replace('\n', ' ').replace('\r', ' ')).append('\n')
                append("size=").append(bytes.size).append('\n')
                append("sha256=").append(sha256).append('\n')
                append("importedAt=").append(importedAt).append('\n')
            },
            Charsets.UTF_8,
        )
        return CustomPayloadInfo(payload, resolvedName, bytes.size.toLong(), sha256, importedAt)
    }

    fun clear(context: Context) {
        payloadFile(context).delete()
        metaFile(context).delete()
        File(context.filesDir, "$DIRECTORY/$PAYLOAD_NAME.part").delete()
    }

    private fun payloadFile(context: Context) = File(context.filesDir, "$DIRECTORY/$PAYLOAD_NAME")

    private fun metaFile(context: Context) = File(context.filesDir, "$DIRECTORY/$META_NAME")
}
