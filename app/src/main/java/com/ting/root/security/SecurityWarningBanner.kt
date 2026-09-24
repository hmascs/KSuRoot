package com.ting.root.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 顶部安全警告横幅。
 *
 * 产品要求：
 *   · 校验不通过时在**软件顶部**弹出警告
 *   · 文案必须原样使用 [SecurityIntegrity.WARNING_TEXT]
 *   · **允许继续使用软件**（横幅可关闭，不做阻断）
 *   · 每次启动重新检测并弹出（因此本组件不持久化"已忽略"状态）
 *
 * 用法（在 MainActivity 的 setContent 里，最外层 Box 的顶部）：
 * ```
 * val report by rememberSecurityIntegrityState(File(filesDir, "hashes.json"))
 * Box(Modifier.fillMaxSize()) {
 *     AppContent()
 *     SecurityWarningBanner(report, Modifier.align(Alignment.TopCenter))
 * }
 * ```
 */
@Composable
fun SecurityWarningBanner(
    report: SecurityIntegrity.Report?,
    modifier: Modifier = Modifier,
) {
    val failure = report?.takeIf { !it.ok } ?: return
    var dismissed by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    if (dismissed) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠️", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                // 原文案，逐字使用
                Text(
                    text = SecurityIntegrity.WARNING_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = failure.summary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { showDetail = true }) { Text("查看详情") }
                TextButton(onClick = { dismissed = true }) { Text("继续使用") }
            }
        }
    }

    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("完整性校验详情") },
            text = {
                Column {
                    Text(SecurityIntegrity.WARNING_TEXT, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    failure.issues.take(8).forEach { issue ->
                        Text(
                            text = "· ${issue.path}（${issue.role}）\n  ${issue.reason}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (failure.issues.size > 8) {
                        Text("…共 ${failure.issues.size} 项，完整记录见日志",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("日志：${failure.logPath}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showDetail = false }) { Text("知道了") } },
        )
    }
}

/**
 * 启动时执行一次完整性校验（IO 线程），返回可观察结果。
 *
 * 每次进程启动都会重新跑 —— 这是产品要求，不要加"记住本次已忽略"的持久化。
 */
@Composable
fun rememberSecurityIntegrityState(
    manifestAsset: String = "hashes.json",
    root: File,
): State<SecurityIntegrity.Report?> {
    val context = androidx.compose.ui.platform.LocalContext.current
    return produceState<SecurityIntegrity.Report?>(initialValue = null, manifestAsset, root) {
        value = withContext(Dispatchers.IO) {
            // 清单随 APK 发布（assets），运行时落到 filesDir 再校验。
            // 每次都重新落盘：升级后清单内容也可能变化。
            val manifest = File(context.filesDir, manifestAsset)
            val ok = runCatching {
                context.assets.open(manifestAsset).use { input ->
                    manifest.outputStream().use { input.copyTo(it) }
                }
                true
            }.getOrDefault(false)

            if (!ok || !manifest.isFile) {
                // 没有清单**不等于**被篡改（可能这版没带清单）。不弹窗，但留日志，
                // 避免"静默通过"的安全错觉。
                android.util.Log.i("KSuRoot/Integrity", "未找到完整性清单 $manifestAsset，跳过校验")
                null
            } else {
                SecurityIntegrity.verify(context, manifest, root)
            }
        }
    }
}

// --------------------------------------------------------------------------
// 设置页：强制指定内核系列（与宿主侧 --force-series 同一套语义）
// --------------------------------------------------------------------------

/** 内核系列策略。Auto = 按 boot.img / release 串实测结果走。 */
enum class KernelSeriesOption(val label: String, val wireValue: String) {
    Auto("自动（按实测）", "auto"),
    Force5("强制 5.x", "5"),
    Force6("强制 6.x", "6"),
}

/**
 * 强制指定与实测冲突时的判定 —— **与 Python 侧 G2 闸门逐条对应**。
 *
 * 默认拒绝（与"绝不盲信"一致）；只有打开"忽略冲突"（等价于
 * --i-know-what-i-am-doing）才放行，且**必须留痕**。
 */
data class SeriesConflict(
    val conflicted: Boolean,
    val message: String,
    val remedy: String,
)

fun evaluateSeriesConflict(
    forced: KernelSeriesOption,
    detectedMajor: Int,
    ignoreConflict: Boolean,
): SeriesConflict {
    if (forced == KernelSeriesOption.Auto || detectedMajor <= 0) {
        return SeriesConflict(false, "", "")
    }
    val forcedMajor = forced.wireValue.toInt()
    if (forcedMajor == detectedMajor) return SeriesConflict(false, "", "")
    return if (ignoreConflict) {
        SeriesConflict(
            conflicted = false,
            message = "强制指定 ${forcedMajor}.x 与实测 ${detectedMajor}.x 冲突 —— 已按“忽略冲突”放行",
            remedy = "本次构建的偏移很可能与设备不匹配，该决定已写入构建日志（escape_hatch_used=true）",
        )
    } else {
        SeriesConflict(
            conflicted = true,
            message = "强制指定 ${forcedMajor}.x，但实测是 ${detectedMajor}.x",
            remedy = "已拒绝构建。确需越过请在高级设置中打开“忽略冲突”",
        )
    }
}

/** 设置页的一行：强制指定内核系列。 */
@Composable
fun KernelSeriesSettingRow(
    selected: KernelSeriesOption,
    ignoreConflict: Boolean,
    onSelect: (KernelSeriesOption) -> Unit,
    onIgnoreConflictChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("载荷构建：内核系列", style = MaterialTheme.typography.titleSmall)
        Text(
            "决定用 5.x 还是 6.x 的布局规则构建载荷。默认按 boot.img 实测结果自动选择。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        KernelSeriesOption.entries.forEach { option ->
            TextButton(onClick = { onSelect(option) }) {
                Text(if (option == selected) "✓ ${option.label}" else option.label)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = ignoreConflict, onCheckedChange = onIgnoreConflictChange)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("忽略冲突（高级）", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "等价于宿主侧的 --i-know-what-i-am-doing：强制指定与实测冲突时仍然构建。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "打开后每次越过硬拦截都会记入日志，便于事后追溯。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
