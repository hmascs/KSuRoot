package com.ting.root

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast

/**
 * 日志的两个动作：**一键复制**与**保存到指定目录**。
 *
 * 放在公共文件里，是因为三个地方都要用：运行记录详情、安装页的实时日志，
 * 以及以后任何展示日志的地方 —— 复制/导出的行为必须一致（包括失败时的提示）。
 *
 * 复制用系统 `ClipboardManager` 而不是 Compose 的 `LocalClipboardManager`：
 * 前者在 Android 13+ 会自带系统级"已复制"气泡、且不依赖当前 Composition 存活；
 * 这里仍然自己再 Toast 一次，是为了在某些 ROM 没有系统气泡时也有反馈。
 */
internal fun Context.copyLogToClipboard(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java)
    val copied = runCatching {
        clipboard?.setPrimaryClip(ClipData.newPlainText("KSuRoot log", text))
    }.isSuccess
    Toast.makeText(
        this,
        getString(if (copied) R.string.log_copied else R.string.log_copy_failed),
        Toast.LENGTH_SHORT,
    ).show()
}

/** 把日志写进用户选定的 Uri（ACTION_CREATE_DOCUMENT 的结果）。 */
internal fun Context.writeLogToUri(uri: Uri, text: String): Boolean {
    val ok = runCatching {
        contentResolver.openOutputStream(uri)?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        } != null
    }.getOrDefault(false)
    Toast.makeText(
        this,
        getString(if (ok) R.string.log_saved else R.string.log_save_failed),
        Toast.LENGTH_SHORT,
    ).show()
    return ok
}

/** 日志默认文件名：`<前缀>-<yyyyMMdd-HHmmss>.log`。 */
internal fun logFileName(prefix: String, timestamp: Long = System.currentTimeMillis()): String =
    prefix + "-" +
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date(timestamp)) +
        ".log"
