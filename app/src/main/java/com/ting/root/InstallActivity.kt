package com.ting.root

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.ting.root.ui.glass.AppBackground
import com.ting.root.ui.theme.AppMotion
import com.ting.root.ui.theme.RootMyGalaxyTheme
import kotlinx.coroutines.delay

class InstallActivity : ComponentActivity() {
    private val installViewModel by viewModels<InstallViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val bundledLibrary = intent.getStringExtra(EXTRA_BUNDLED_LIBRARY)
        val startInstall = savedInstanceState == null && AppPreferences.consumeInstallRequest(
            this,
            intent.getStringExtra(EXTRA_INSTALL_REQUEST_ID),
        )
        intent.removeExtra(EXTRA_INSTALL_REQUEST_ID)
        intent.removeExtra(EXTRA_BUNDLED_LIBRARY)
        setContent {
            RootMyGalaxyTheme(
                accentColor = AppPreferences.accentColor(this),
                themeMode = AppPreferences.themeMode(this),
            ) {
                val installState by installViewModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = installState.busy) {}
                LaunchedEffect(startInstall, bundledLibrary) {
                    if (startInstall) installViewModel.install(bundledLibrary)
                }
                InstallScreen(
                    installState = installState,
                    onRetry = { installViewModel.install(bundledLibrary) },
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        const val EXTRA_INSTALL_REQUEST_ID = "install_request_id"

        /** 手动指定的内置载荷文件名（`jniLibs` 里的名字）；不传则按设备自动匹配。 */
        const val EXTRA_BUNDLED_LIBRARY = "bundled_library"
    }
}

// -------------------------------------------------------------------------
// 排版尺度
//
// 提权页原来混用 10/12/14/16/18/20/28dp 七种间距、38/44/21/24dp 四种图标尺寸，
// 卡片之间与卡片内部的呼吸感对不上。这里收敛成一套尺度，界面上每个间距
// 都能对应到下面某个常量，不再出现"看着差不多但数值不一样"的随手值。
// -------------------------------------------------------------------------

/** 页面左右留白。 */
private val SPACING_PAGE = 20.dp

/** 区块（卡片）之间的竖向间距。 */
private val SPACING_SECTION = 12.dp

/** 卡片内边距。 */
private val SPACING_CARD = 20.dp

/** 卡片内部的元素间距。 */
private val SPACING_ITEM = 16.dp

/** 紧邻的两行文字之间的间距。 */
private val SPACING_TIGHT = 2.dp

/** 按钮之间的间距。 */
private val SPACING_CONTROL = 12.dp

/** 状态卡主图标的尺寸。 */
private val ICON_STATUS = 44.dp

/**
 * 「进行中」阶段集合。
 *
 * 语义与 `InstallUiState.busy` 完全一致 —— 这里曾经是两处独立的 `setOf(...)` 字面量，
 * 改动阶段定义时很容易只改一边。现在两处都指向同一份顶层常量，
 * 并且顺带把"每次重组都新建一个 Set"的分配去掉了。
 */
private val BUSY_PHASES: Set<InstallPhase> = busyPhases()

/**
 * 安装页竖向上 1/4 / 下 3/4 的分割点。
 *
 * 需求是「上面四分之一显示正在运行的 exploit，下面四分之三留日志」。
 * 用权重而不是固定高度：小屏上状态区不会被压扁，大屏上日志区自动变高。
 */
private const val STATUS_WEIGHT = 1f
private const val LOG_WEIGHT = 3f

@Composable
private fun InstallScreen(
    installState: InstallUiState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val logScrollState = rememberScrollState()
    // 自动滚到底的触发键：用**渲染内容**而不是行数。
    // 之前用行数当 key，简略模式下去重会把新行合并掉、行数不变，滚动就停住；
    // 用内容做 key 则任何一次可见变化都会滚，逻辑与"用户看到新东西"完全一致。
    // 载荷每秒刷几十行也没关系——key 只是字符串比较，不触发重组以外的开销。
    // 滚动触发键：译文内容 + 原始日志长度。
    // 只盯译文的话，切到「原文」模式后译文不再变化，滚动就停住不跟了。
    val scrollKey = installState.displayLog + "|" + installState.log.length
    LaunchedEffect(scrollKey) {
        if (scrollKey.length <= 1) return@LaunchedEffect
        logScrollState.scrollTo(logScrollState.maxValue)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 与主界面同一套背景语义色（miuix `surface`），进入安装页视觉不断层。
        AppBackground()
        Scaffold(containerColor = Color.Transparent) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = SPACING_PAGE),
            verticalArrangement = Arrangement.spacedBy(SPACING_SECTION),
        ) {
            // ---- 上 1/4：当前 exploit 在做什么 ----
            // 标题行压到一行小字，把高度让给状态卡 —— 提权时用户真正要看的
            // 是「跑到哪一步了」，不是应用名。
            Text(
                text = if (installState.busy) {
                    stringResource(R.string.install_keep_open)
                } else {
                    installState.message
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            // 状态卡与日志卡只依赖各自的数据：把 installState 拆开传进去，
            // 日志更新时状态卡**不重组**。
            InstallerStatusCard(
                phase = installState.phase,
                message = installState.message,
                modifier = Modifier.weight(STATUS_WEIGHT),
            )
            InstallerLog(
                rawLog = installState.log,
                modifier = Modifier.weight(LOG_WEIGHT),
                scrollState = logScrollState,
            )

            // 底部操作区用弹簧展开/收起替代硬性 if：提权结束时按钮
            // 从内容下方柔和长出来，失败/完成切换不再"啪"地一下弹出。
            AnimatedVisibility(
                visible = !installState.busy,
                enter = fadeIn(AppMotion.snappy()) + expandVertically(AppMotion.gentle()),
                exit = shrinkVertically(AppMotion.snappy()) + fadeOut(AppMotion.snappy()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = SPACING_PAGE),
                    horizontalArrangement = Arrangement.spacedBy(SPACING_CONTROL),
                ) {
                    if (installState.phase == InstallPhase.Failed) {
                        Button(
                            onClick = onClose,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_close))
                        }
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(stringResource(R.string.action_retry))
                        }
                    } else if (installState.phase == InstallPhase.Installed) {
                        Button(
                            onClick = onClose,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * 状态卡。
 *
 * 结构，从上到下依次回答「在干什么 / 干到哪 / 还差多少」：
 * 1. `message` —— 阶段级文案（来自 ViewModel）
 * 3. 进度条   —— 阶段近似（里程碑随翻译引擎一并移除）
 */
@Composable
private fun InstallerStatusCard(
    phase: InstallPhase,
    message: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    // 状态色全部取 miuix 语义色对（container / on-container 成对使用，
    // 对比度由色板保证）。失败=红、其余=蓝，与安装页的「进行中」语义一致。
    val failed = phase == InstallPhase.Failed
    val busy = phase in BUSY_PHASES
    val containerColor = if (failed) scheme.errorContainer else scheme.tertiaryContainer
    val contentColor = if (failed) scheme.onErrorContainer else scheme.onTertiaryContainer
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SPACING_CARD),
            verticalArrangement = Arrangement.spacedBy(SPACING_ITEM),
        ) {
            Row(
                // 图标跟着文字顶端对齐，详情换行时不会把图标推到中线以下
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SPACING_ITEM),
            ) {
                val iconKind = when {
                    busy -> 0
                    phase == InstallPhase.Installed -> 1
                    else -> 2
                }
                // AnimatedContent 的 key 从 phase 换成了「图标种类」。
                // phase 在 Exploiting 期间只有一个取值，但 busy 在
                // Checking/Downloading/Exploiting/LoadingKernelSu 之间切时值不变 ——
                // 用 phase 当 key 会让同一套图标重新播一遍交叉淡入，纯属浪费。
                // transitionSpec：旧态缩小淡出、新态弹性放大入场，一次可感知的过冲。
                AnimatedContent(
                    targetState = iconKind,
                    transitionSpec = {
                        (fadeIn(AppMotion.snappy()) + scaleIn(
                            initialScale = 0.6f,
                            animationSpec = AppMotion.bouncy(),
                        )).togetherWith(
                            fadeOut(AppMotion.snappy()) + scaleOut(
                                targetScale = 0.6f,
                                animationSpec = AppMotion.snappy(),
                            ),
                        )
                    },
                    label = "install-status-icon",
                ) { kind ->
                    when (kind) {
                        0 -> InfiniteProgressIndicator(
                            size = ICON_STATUS,
                            color = contentColor,
                            strokeWidth = 3.dp,
                            orbitingDotSize = 3.dp,
                        )
                        1 -> Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(ICON_STATUS),
                        )
                        else -> Icon(
                            Icons.Rounded.Error,
                            contentDescription = null,
                            modifier = Modifier.size(ICON_STATUS),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SPACING_TIGHT),
                ) {
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.title2,
                    )
                    // [2026-09-24] 翻译引擎已移除 → 状态区统一显示阶段详情。
                    val detail = installPhaseDetail(phase)
                    Text(
                        text = detail,
                        style = MiuixTheme.textStyles.body2,
                        color = contentColor.copy(alpha = 0.78f),
                    )
                }
            }
            // 里程碑跳变时进度条用弹簧平滑追赶，而不是瞬移 ——
            // CFI 通过那一刻 14/16 → 16/16 的"冲线感"就来自这里。
            val animatedProgress by animateFloatAsState(
                targetValue = installProgress(phase),
                animationSpec = AppMotion.snappy(),
                label = "install-progress",
            )
            LinearProgressIndicator(
                progress = animatedProgress,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = contentColor,
                    disabledForegroundColor = contentColor,
                    backgroundColor = contentColor.copy(alpha = 0.2f),
                ),
            )
        }
    }
}

@Composable
private fun InstallerLog(
    rawLog: String,
    modifier: Modifier,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 注意：stringResource 是 @Composable，不能在 onClick 里调 —— 先取到外面。
    val preparingText = stringResource(R.string.install_preparing)
    // 导出给的是**原始**日志（英文 + 地址 + errno），不是界面上的译文 ——
    // 贴给别人排查问题时，原始输出才有用；译文只负责让当前用户看懂。
    val exportText = rawLog.ifBlank { preparingText }
    var pendingLog by remember { mutableStateOf("") }
    // [2026-09-24] 翻译引擎整体移除 → 日志框只显示**原始日志**，不再有切换。
    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        context.writeLogToUri(uri, pendingLog)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SPACING_CARD),
            verticalArrangement = Arrangement.spacedBy(SPACING_ITEM),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.install_live_progress),
                    style = MiuixTheme.textStyles.title2,
                    modifier = Modifier.weight(1f),
                )
                // 一键复制：出问题时直接把**原始**日志贴到群里/issue 里
                IconButton(onClick = { context.copyLogToClipboard(exportText) }) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.copy_log),
                    )
                }
                // 保存到指定目录（系统文件选择器，可挑任意位置）
                IconButton(onClick = {
                    pendingLog = exportText
                    saveLogLauncher.launch(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, logFileName("ksu-install"))
                        },
                    )
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Save,
                        contentDescription = stringResource(R.string.save_log),
                    )
                }
            }
            Text(
                text = exportText,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
                // 译文是中文句子，用等宽字体反而难读；只有原始日志才需要等宽对齐。
                // 因此这里不再固定 Monospace，交给主题的正文字体。
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun installPhaseDetail(phase: InstallPhase): String = stringResource(
    when (phase) {
        InstallPhase.Checking -> R.string.phase_checking
        InstallPhase.Ready -> R.string.phase_ready
        InstallPhase.Downloading -> R.string.phase_downloading
        InstallPhase.Exploiting -> R.string.phase_exploiting
        InstallPhase.LoadingKernelSu -> R.string.phase_loading_ksu
        InstallPhase.Installed -> R.string.phase_installed
        InstallPhase.Failed -> R.string.phase_failed
    },
)

private fun installProgress(phase: InstallPhase): Float {
    // [2026-09-24] 里程碑（家族 A 的 16 级阶梯）随翻译引擎一起移除，
    // 进度条退回**阶段近似值**：Exploiting 一律 0.6。
    // 代价是"卡在 CFI 重试"不再能从进度条看出来，但日志框是原文，
    // 重试次数在状态卡的 message 里仍有。
    return when (phase) {
        InstallPhase.Checking -> 0.1f
        InstallPhase.Ready -> 0f
        InstallPhase.Downloading -> 0.3f
        InstallPhase.Exploiting -> 0.6f
        InstallPhase.LoadingKernelSu -> 0.85f
        InstallPhase.Installed -> 1f
        InstallPhase.Failed -> 0f
    }
}
