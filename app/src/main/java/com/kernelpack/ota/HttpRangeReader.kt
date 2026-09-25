/*
 * 出处：YuKongA/ghostlock-app（Apache-2.0）
 *   app/src/main/kotlin/com/ghostlock/app/data/ota/HttpRangeReader.kt
 * 改动：package 名；新增 [probe]（原实现只返回长度，拿不到"服务器支不支持 Range"）；
 *      User-Agent 改为本应用自己的。许可证见 app/src/main/assets/GL_LICENSE_Apache2.txt
 */

package com.kernelpack.ota

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 HTTP `Range` 请求**只读远端文件的指定区间**。
 *
 * 这是「解析完整包链接」能成立的前提：完整包动辄 4–8 GiB，
 * 而真正需要的只有尾部 64 KiB（中央目录）、payload 清单，以及 `boot` 分区那几十 MiB。
 * 没有 Range 就得整包下载 —— 手机上那是几十 GB 流量和几十分钟。
 *
 * 只用 `HttpURLConnection`：不引第三方 HTTP 客户端。重定向**手工跟随**，
 * 因为厂商 CDN 与 GitHub Release 大量使用 302，而 `instanceFollowRedirects`
 * 在跨协议跳转时行为不一致。
 */
class HttpRangeReader(
    private val connectTimeoutMs: Int = 15000,
    private val readTimeoutMs: Int = 30000,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {

    /** 远端文件的基本信息。 */
    data class RemoteFile(
        val length: Long,
        /**
         * 服务器**是否真的**响应了 `Content-Range`。
         *
         * 只有它为 true，才谈得上"只下需要的块"。为 false 时本应用**直接放弃**
         * 而不是退化去下整包 —— 一次误触就是几个 GB 的流量，必须让用户显式选择。
         */
        val acceptsRanges: Boolean,
    )

    private fun openConnectionWithRedirects(
        urlStr: String, headers: Map<String, String>
    ): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < MAX_REDIRECTS) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.connect()
            val code = conn.responseCode
            if (code in 301..303 || code in 307..308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank()) {
                    currentUrl = if (location.startsWith("http://", true) ||
                        location.startsWith("https://", true)
                    ) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    redirects++
                    continue
                }
            }
            return conn
        }
        throw IOException("重定向次数过多：$urlStr")
    }

    /** 探测远端文件长度与 Range 支持情况；连不上/读不到返回 null。 */
    suspend fun probe(url: String): RemoteFile? = withContext(Dispatchers.IO) {
        try {
            val conn = openConnectionWithRedirects(url, mapOf("Range" to "bytes=0-0"))
            try {
                val contentRange = conn.getHeaderField("Content-Range")
                if (contentRange != null) {
                    val slash = contentRange.lastIndexOf('/')
                    if (slash != -1) {
                        val total = contentRange.substring(slash + 1).trim().toLongOrNull()
                        if (total != null && total > 0) {
                            return@withContext RemoteFile(total, acceptsRanges = true)
                        }
                    }
                }
                val length = conn.contentLengthLong
                if (length > 0) {
                    return@withContext RemoteFile(length, acceptsRanges = false)
                }
                null
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** 读 `[start, start+size)`；越界、非 2xx、或服务器无视 Range 时返回 null。 */
    suspend fun read(url: String, start: Long, size: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (size == 0) return@withContext ByteArray(0)
        if (size < 0 || start < 0) return@withContext null

        try {
            val end = start + size - 1
            val conn = openConnectionWithRedirects(url, mapOf("Range" to "bytes=$start-$end"))
            try {
                val code = conn.responseCode
                if (code == 200 && start > 0) {
                    // 服务器**无视了** Range，从 0 开始发。这里必须拒绝：
                    // 静默接受会把文件开头当成中间某段，得到的是一份"看起来对"的垃圾。
                    return@withContext null
                }
                if (code !in 200..299) return@withContext null
                val stream = conn.inputStream
                val buffer = ByteArray(size)
                var totalRead = 0
                while (totalRead < size) {
                    val count = stream.read(buffer, totalRead, size - totalRead)
                    if (count == -1) break
                    totalRead += count
                }
                if (totalRead == size) buffer else buffer.copyOf(totalRead)
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 把 `[start, start+size)` **流式**写进 [out]；返回实际写入字节数。
     *
     * 与 [read] 的区别：那个会把整段读进一个 `ByteArray`。boot 镜像动辄 96 MiB，
     * 为它开一个那么大的数组（还要再加一份写缓冲）在手机上很容易被 LMK 盯上。
     * 需要整份文件的场合一律走这里。
     *
     * @param onProgress 已写字节数回调（每 1 MiB 一次），用于界面进度。
     */
    suspend fun readToFile(
        url: String,
        start: Long,
        size: Long,
        out: java.io.File,
        onProgress: ((Long) -> Unit)? = null,
    ): Long = withContext(Dispatchers.IO) {
        if (size < 0 || start < 0) throw IOException("非法区间：start=$start size=$size")
        val end = start + size - 1
        val conn = openConnectionWithRedirects(
            url, mapOf("Range" to "bytes=$start-${if (size == 0L) start else end}")
        )
        try {
            val code = conn.responseCode
            if (code == 200 && start > 0) {
                throw IOException("服务器忽略了 Range 请求，无法只取需要的区间")
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var written = 0L
                    var sinceReport = 0L
                    while (written < size) {
                        val want = minOf(buffer.size.toLong(), size - written).toInt()
                        val n = input.read(buffer, 0, want)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        written += n
                        sinceReport += n
                        if (sinceReport >= PROGRESS_STEP) {
                            sinceReport = 0
                            onProgress?.invoke(written)
                        }
                    }
                    onProgress?.invoke(written)
                    if (written != size) {
                        throw IOException("区间不完整：期望 $size 字节，实际 $written")
                    }
                    written
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 6
        private const val PROGRESS_STEP = 1L shl 20
        const val DEFAULT_USER_AGENT = "KSuRoot/4.1 (Android; OTA offset extractor)"
    }
}
