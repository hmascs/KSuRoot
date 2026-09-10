package com.ting.root

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 往系统「下载」目录里放文件。
 *
 * 为什么不用 `File("/storage/emulated/0/Download/...")`：那是分区存储之前的写法，
 * 现在普通应用（没有 MANAGE_EXTERNAL_STORAGE）直接写那个路径会被拒。
 * `MediaStore.Downloads` 是系统给的正路 —— **插入自己创建的文件不需要任何权限**
 * （API 29+，本工程 minSdk 33），落盘后就是用户能在文件管理器里看到的
 * `/storage/emulated/0/Download/<name>`。
 */
object DownloadStore {

    /**
     * 写入下载目录。返回可读的落盘路径（例如 `/storage/emulated/0/Download/xxx.so`），
     * 失败时抛异常（调用处自行兜底）。
     */
    fun save(context: Context, displayName: String, bytes: ByteArray): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // 明确标记为「下载」，避免被当成媒体文件索引
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri: Uri = resolver.insert(collection, values)
            ?: error(context.getString(R.string.builder_save_download_failed))
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error(context.getString(R.string.builder_save_download_failed))
        } finally {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return absolutePathOf(context, uri) ?: displayName
    }

    /** 把 content:// 换成用户看得懂的绝对路径；拿不到就退回文件名。 */
    private fun absolutePathOf(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val relative = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            queryDisplayName(context, uri),
        )
        return relative.absolutePath
    }

    private fun queryDisplayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "download"
}
