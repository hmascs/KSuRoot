package com.ting.root.security

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 核心代码与偏移量文件的完整性校验（App 侧）。
 *
 * 产品要求（逐条对应实现）：
 *   ① 对生成的关键代码与偏移量文件做哈希校验
 *   ② 校验不通过 → 顶部弹窗警告，但**允许继续使用软件**
 *   ③ 弹窗文案必须原样使用 [WARNING_TEXT]
 *   ④ 每次启动都重新检测并弹出；失败详情写入日志
 *
 * [风险] 本模块只负责"如实报告"，绝不代替调用方拦截。
 *        清单自身可被替换 —— 真正的防篡改需要把清单签名密钥编译进 APK，
 *        或在可信环境下登记（详见 Python 侧 gl5x/integrity/verify.py 的说明）。
 */
object SecurityIntegrity {

    /** 弹窗文案原文。**必须逐字使用**，不要改写、拼接或翻译。 */
    const val WARNING_TEXT = "哈希校验未通过，可能含有恶意代码，请不要在生产环境中使用"

    private const val TAG = "KSuRoot/Integrity"
    private const val LOG_NAME = "integrity.log"
    private const val CHUNK = 1 shl 20

    /** 单个文件的校验结果。 */
    data class FileIssue(
        val path: String,
        val role: String,
        val reason: String,
        val expected: String,
        val actual: String,
    )

    /** 一次校验的完整结果。UI 只需要看 [ok] 与 [issues]。 */
    data class Report(
        val ok: Boolean,
        val checked: Int,
        val issues: List<FileIssue>,
        val manifestName: String,
        val logPath: String,
    ) {
        val summary: String
            get() = if (ok) "完整性校验通过（$checked 个文件）"
            else "完整性校验未通过：${issues.size}/$checked 个文件异常"
    }

    /**
     * 校验清单。
     *
     * @param manifestFile hashes.json（由宿主侧 build_payload.py 生成）
     * @param root         清单里路径的根目录，默认取清单所在目录
     *
     * 任何异常都被收敛成"校验不通过"的报告，**不会抛出** —— 按产品要求，
     * 校验失败只警告、不阻断。
     */
    fun verify(context: Context, manifestFile: File, root: File = manifestFile.parentFile!!): Report {
        val issues = mutableListOf<FileIssue>()
        var checked = 0
        var name = ""
        try {
            val manifest = JSONObject(manifestFile.readText())
            name = manifest.optString("name", "")
            val algo = manifest.optString("algo", "sha256")
            val files = manifest.optJSONArray("files")
            if (files == null) {
                issues += FileIssue(manifestFile.name, "manifest", "清单里没有 files 数组", "", "")
            } else {
                for (i in 0 until files.length()) {
                    val entry = files.getJSONObject(i)
                    val rel = entry.optString("path")
                    val role = entry.optString("role", "unknown")
                    val expect = entry.optString("sha256")
                    val expectSize = entry.optLong("size", -1L)
                    checked++

                    val target = File(root, rel)
                    if (!target.isFile) {
                        issues += FileIssue(rel, role, "文件不存在", expect, "")
                        continue
                    }
                    val actualSize = target.length()
                    val actual = sha256(target, algo)
                    when {
                        expect.isNotEmpty() && expect != actual ->
                            issues += FileIssue(rel, role, "内容哈希不符（文件被修改）", expect, actual)
                        expectSize >= 0 && expectSize != actualSize ->
                            issues += FileIssue(rel, role, "文件长度不符（$actualSize != $expectSize）", expect, actual)
                        expect.isEmpty() ->
                            issues += FileIssue(rel, role, "清单里没有登记哈希（登记时文件缺失）", expect, actual)
                    }
                }
            }
        } catch (t: Throwable) {
            // 清单损坏 = 校验体系不可信，按失败处理
            issues += FileIssue(manifestFile.name, "manifest", "清单无法解析：${t.message}", "", "")
            Log.e(TAG, "清单解析失败", t)
        }

        val report = Report(issues.isEmpty(), checked, issues, name,
            logPath = File(context.filesDir, LOG_NAME).absolutePath)
        if (!report.ok) writeLog(context, report)
        return report
    }

    /** 流式 SHA-256，避免把几十 MB 的镜像整个读进内存。 */
    private fun sha256(file: File, algo: String): String = try {
        val digest = MessageDigest.getInstance(if (algo == "sha256") "SHA-256" else algo)
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        Log.e(TAG, "哈希计算失败: ${file.name}", t)
        ""
    }

    /**
     * 把失败详情写入日志文件（JSON Lines，追加）。
     *
     * 只记失败、不记成功：每次启动都会校验，成功也写会把日志刷爆。
     * 同时用 Log.w 打一条带 [WARN] 前缀的系统日志，便于 adb logcat 抓取。
     */
    private fun writeLog(context: Context, report: Report) {
        val detail = report.issues.joinToString("; ") {
            "${it.path}(${it.role}): ${it.reason} 期望=${it.expected.take(16)} 实际=${it.actual.take(16)}"
        }
        Log.w(TAG, "[WARN] ${WARNING_TEXT} | ${report.summary} | $detail")
        try {
            val record = JSONObject().apply {
                put("ts", System.currentTimeMillis() / 1000)
                put("event", "integrity_failed")
                put("manifest", report.manifestName)
                put("checked", report.checked)
                put("failed", report.issues.size)
                put("warning_text", WARNING_TEXT)
                put("issues", org.json.JSONArray().apply {
                    report.issues.forEach { iss ->
                        put(JSONObject().apply {
                            put("path", iss.path); put("role", iss.role); put("reason", iss.reason)
                            put("expected", iss.expected); put("actual", iss.actual)
                        })
                    }
                })
            }
            File(context.filesDir, LOG_NAME).appendText(record.toString() + "\n")
        } catch (t: Throwable) {
            Log.e(TAG, "日志写入失败", t)
        }
    }
}
