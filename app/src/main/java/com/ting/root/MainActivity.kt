package com.ting.root

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ting.root.ui.glass.AppBackground
import com.ting.root.ui.glass.GlassNavBarContent
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.ting.root.ui.glass.LocalGlassBackdrop
import com.ting.root.ui.glass.glassNavBar
import com.ting.root.ui.theme.AppMotion
import com.ting.root.ui.theme.RootMyGalaxyTheme
import com.ting.root.ui.theme.staggeredEntry
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val installViewModel by viewModels<InstallViewModel>()
    private val builderViewModel by viewModels<PayloadBuilderViewModel>()
    private var resumedOnce = false
    private var accentColor by mutableStateOf(AccentColor.Dynamic)
    private var themeMode by mutableStateOf(AppThemeMode.System)
    private var advancedMode by mutableStateOf(false)
    private var shizukuMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        accentColor = AppPreferences.accentColor(this)
        themeMode = AppPreferences.themeMode(this)
        advancedMode = AppPreferences.advancedMode(this)
        shizukuMode = AppPreferences.shizukuMode(this)
        setContent {
            RootMyGalaxyTheme(accentColor = accentColor, themeMode = themeMode) {
                RootApp(
                    installViewModel = installViewModel,
                    builderViewModel = builderViewModel,
                    accentColor = accentColor,
                    themeMode = themeMode,
                    advancedMode = advancedMode,
                    shizukuMode = shizukuMode,
                    onAccentColorChanged = { color ->
                        AppPreferences.setAccentColor(this, color)
                        accentColor = color
                    },
                    onThemeModeChanged = { mode ->
                        AppPreferences.setThemeMode(this, mode)
                        themeMode = mode
                    },
                    onAdvancedModeChanged = { enabled ->
                        AppPreferences.setAdvancedMode(this, enabled)
                        advancedMode = enabled
                    },
                    onShizukuModeChanged = { enabled ->
                        AppPreferences.setShizukuMode(this, enabled)
                        shizukuMode = enabled
                    },
                    openInstaller = { profileId ->
                        val installer = Intent(this, InstallActivity::class.java)
                            .putExtra(InstallActivity.EXTRA_INSTALL_REQUEST_ID, UUID.randomUUID().toString())
                        if (profileId != null) {
                            installer.putExtra(InstallActivity.EXTRA_PROFILE_ID, profileId)
                        }
                        startActivity(installer)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce) installViewModel.refresh() else resumedOnce = true
    }
}

/** Apple 风格悬浮底栏的几何参数（脱离屏幕边缘的胶囊形玻璃条）。 */
private val FloatingBarMargin = 16.dp

/**
 * 胶囊圆角：用 **50%** 而不是固定 dp。
 * 固定 dp 只有在「圆角 ≥ 高度一半」时才是胶囊，之前 16/28dp 都小于 64dp 栏高的一半(32dp)，
 * 所以看起来是「很圆的矩形」而不是苹果 App Store 那种**两端半圆的胶囊**。
 * 百分比写法与高度解耦，永远是正确的胶囊。
 */
private val FloatingBarCornerRadius = 32.dp

/** 悬浮底栏浮在内容之上，需要为它让出的底部空间（栏高约 64dp + 上下留白 16dp×2）。 */
private val FloatingBarReservedHeight = 96.dp

/** 悬浮底栏的实际高度：64dp 栏体 + 上下各 16dp 留白 = 96dp = [FloatingBarReservedHeight]。 */
private val FloatingBarContentHeight = 64.dp

/** dock 玻璃底板的语义标签；与 [com.ting.root.ui.glass.SliderTestTag] 一起用于真机几何验证。 */
const val NavBarDockTestTag = "ksu-nav-dock"

/**
 * 按下任意 tab 时**整条底栏**的放大倍数。
 *
 * 方向是「放大」而不是常见的「按瘪」：按下时底栏与内部的滑块**一起被放大**，
 * 滑块自身再额外乘一个更大的比例（见 `GlassNavBarContent.SliderPressedScale`），
 * 于是滑块会比栏体大出一圈。两者在修饰符链上是父子关系，比例天然相乘。
 *
 * 取值要克制：底栏四周只有 16dp 留白，放大超过 1.06 就会把留白吃得差不多、
 * 看起来像贴住了屏幕边缘。
 */
private const val FloatingBarPressedScale = 1.04f

private enum class AppPage(@StringRes val label: Int, val icon: ImageVector) {
    Overview(R.string.nav_overview, Icons.Rounded.Home),
    // 扁平扳手：Material 的 Build 就是一把扁平开口扳手，与其余三个图标的
    // Rounded 线性风格同族（同描边粗细、同圆角端点）。
    Builder(R.string.nav_builder, Icons.Rounded.Build),
    History(R.string.nav_history, Icons.Rounded.History),
    Settings(R.string.nav_settings, Icons.Rounded.Settings),
}

private data class LanguageOption(@StringRes val label: Int, val tag: String)

private enum class CompatibilityWarning {
    Device,
    KernelVersion,
}

private val languageOptions = listOf(
    LanguageOption(R.string.language_system, ""),
    LanguageOption(R.string.language_korean, "ko"),
    LanguageOption(R.string.language_english, "en"),
    LanguageOption(R.string.language_japanese, "ja"),
    LanguageOption(R.string.language_chinese, "zh-CN"),
    LanguageOption(R.string.language_chinese_traditional, "zh-TW"),
    LanguageOption(R.string.language_turkish, "tr"),
    LanguageOption(R.string.language_russian, "ru"),
    LanguageOption(R.string.language_vietnamese, "vi"),
)

private const val KERNEL_SU_MANAGER_URL =
    "https://github.com/tiann/KernelSU/releases/download/v3.2.5/KernelSU_v3.2.5_32525-release.apk"
private const val KERNEL_SU_MANAGER_PACKAGE = "me.weishu.kernelsu"
private const val KERNEL_SU_HOME_URL = "https://kernelsu.org/"
private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager"
private const val SHIZUKU_MANAGER_URL = "https://github.com/thedjchi/Shizuku/releases/"

private fun isKernelSuManagerInstalled(context: Context): Boolean =
    context.packageManager.getLaunchIntentForPackage(KERNEL_SU_MANAGER_PACKAGE) != null

private fun openKernelSuManager(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(KERNEL_SU_MANAGER_PACKAGE)
    if (launch != null) {
        context.startActivity(launch)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KERNEL_SU_MANAGER_URL)))
    }
}

private fun openShizukuManager(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
    if (launch != null) {
        context.startActivity(launch)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_MANAGER_URL)))
    }
}

@Composable
private fun RootApp(
    installViewModel: InstallViewModel,
    builderViewModel: PayloadBuilderViewModel,
    accentColor: AccentColor,
    themeMode: AppThemeMode,
    advancedMode: Boolean,
    shizukuMode: Boolean,
    onAccentColorChanged: (AccentColor) -> Unit,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onAdvancedModeChanged: (Boolean) -> Unit,
    onShizukuModeChanged: (Boolean) -> Unit,
    openInstaller: (String?) -> Unit,
) {
    val context = LocalContext.current
    val installState by installViewModel.state.collectAsStateWithLifecycle()
    val history by installViewModel.history.collectAsStateWithLifecycle()
    val targetCatalog by installViewModel.targetCatalog.collectAsStateWithLifecycle()
    val buildState by builderViewModel.state.collectAsStateWithLifecycle()
    var selectedPage by remember { mutableStateOf(AppPage.Overview) }
    var showInstallConfirmation by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf<TargetProfile?>(null) }
    var compatibilityWarning by remember { mutableStateOf<CompatibilityWarning?>(null) }
    // 内核低于 6.6：按安装前先提示"免 ADB 这条路走不通"。
    var kernelTooOld by remember { mutableStateOf(false) }
    var payloadSource by remember { mutableStateOf(AppPreferences.payloadSource(context)) }
    var customPayload by remember { mutableStateOf(CustomPayloadStore.current(context)) }
    val device = remember { DeviceSnapshot.current() }

    // backdrop 的采集源（底栏与滑块的模糊 + 折射用）。
    // 采集层里画了什么，玻璃就只可能糊到什么 —— 所以「背景 + 页面内容」都必须
    // 落在这一层里面，见下方采集层的注释。
    val glassBackdrop = rememberLayerBackdrop()

    // 点按底栏任意 tab → 整条 dock 缩放。状态必须放在这一层：
    // 按压是在 GlassNavBarContent 内部（每个 tab 一个 InteractionSource）采集的，
    // 而要缩放的是这里的**外层玻璃容器**，所以由内容层回调上报、这里执行。
    // 注意缩放在 graphicsLayer 上做（纯绘制），命中区仍是原来的整条栏。
    var bottomBarPressed by remember { mutableStateOf(false) }
    val bottomBarScale by animateFloatAsState(
        targetValue = if (bottomBarPressed) FloatingBarPressedScale else 1f,
        animationSpec = AppMotion.press(),
        label = "bottomBarPressScale",
    )

    // 点「开始构建」先弹方案选择（从下往上），选完才真正开跑。
    var showSchemeSheet by remember { mutableStateOf(false) }
    if (showSchemeSheet) {
        PayloadSchemeSheet(
            onDismiss = { showSchemeSheet = false },
            onPick = { scheme ->
                showSchemeSheet = false
                builderViewModel.build(scheme)
            },
        )
    }

    // ── 载荷构建：选 boot.img ──
    // 只读、不持久化权限：boot.img 是一次性输入，解析完就不再需要访问它了。
    val bootImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, size) = queryOpenable(context, uri)
        builderViewModel.rememberBootImage(uri, name, size.coerceAtLeast(0L))
    }

    // ── 载荷构建：导出产出的 .so / target.h ──
    var pendingExport by remember { mutableStateOf<BuilderExport?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val export = pendingExport
        pendingExport = null
        if (result.resultCode != android.app.Activity.RESULT_OK || export == null) {
            return@rememberLauncherForActivityResult
        }
        val payload: ByteArray? = when (export) {
            BuilderExport.Library -> builderViewModel.outputBytes()
            BuilderExport.Header -> builderViewModel.outputHeaderText().toByteArray()
        }
        if (payload == null) return@rememberLauncherForActivityResult
        val wrote = result.data?.data?.let { uri ->
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload) } != null
            }.getOrDefault(false)
        } ?: false
        Toast.makeText(
            context,
            context.getString(if (wrote) R.string.builder_export_done else R.string.builder_export_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun exportArtifact(export: BuilderExport) {
        pendingExport = export
        val name = when (export) {
            BuilderExport.Library -> buildState.outputName.ifBlank { "libbs-patched.so" }
            BuilderExport.Header -> "target.generated.h"
        }
        val mime = when (export) {
            BuilderExport.Library -> "application/octet-stream"
            BuilderExport.Header -> "text/plain"
        }
        exportLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, name)
            },
        )
    }

    val importPayloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Throwable) {
        }
        try {
            val info = CustomPayloadStore.import(context, uri, queryDisplayName(context, uri))
            AppPreferences.setPayloadSource(context, PayloadSource.Custom)
            payloadSource = PayloadSource.Custom
            customPayload = info
            installViewModel.refresh()
            Toast.makeText(
                context,
                context.getString(R.string.custom_import_success, info.displayName),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (error: Throwable) {
            Toast.makeText(
                context,
                error.message ?: context.getString(R.string.custom_import_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    if (kernelTooOld) {
        KernelTooOldDialog(
            kernelVersion = device.kernelVersion,
            onDismiss = { kernelTooOld = false },
            onContinue = {
                kernelTooOld = false
                selectedProfile = null
                if (advancedMode && payloadSource == PayloadSource.Online) {
                    showTargetPicker = true
                    installViewModel.loadTargetCatalog()
                } else {
                    showInstallConfirmation = true
                }
            },
        )
    }

    if (showTargetPicker) {
        TargetSelectionSheet(
            device = device,
            catalog = targetCatalog,
            onDismiss = { showTargetPicker = false },
            onRetry = installViewModel::loadTargetCatalog,
            onNext = { profile ->
                selectedProfile = profile
                showTargetPicker = false
                compatibilityWarning = when {
                    !profile.matchesDevice(device) -> CompatibilityWarning.Device
                    !profile.matchesKernelVersion(device) -> CompatibilityWarning.KernelVersion
                    else -> null
                }
                if (compatibilityWarning == null) showInstallConfirmation = true
            },
        )
    }

    compatibilityWarning?.let { warning ->
        val profile = selectedProfile ?: return@let
        AlertDialog(
            onDismissRequest = {
                compatibilityWarning = null
                showTargetPicker = true
            },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = {
                DialogDimAmount(0.24f)
                Text(
                    stringResource(when (warning) {
                        CompatibilityWarning.Device -> R.string.device_mismatch_title
                        CompatibilityWarning.KernelVersion -> R.string.kernel_version_mismatch_title
                    }),
                )
            },
            text = {
                Text(
                    when (warning) {
                        CompatibilityWarning.Device -> stringResource(
                            R.string.device_mismatch_body,
                            device.model,
                            profile.supportedModels,
                        )
                        CompatibilityWarning.KernelVersion -> stringResource(
                            R.string.kernel_version_mismatch_body,
                            device.kernelVersion,
                            profile.supportedKernelVersions,
                        )
                    },
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        compatibilityWarning = when (warning) {
                            CompatibilityWarning.Device -> if (!profile.matchesKernelVersion(device)) {
                                CompatibilityWarning.KernelVersion
                            } else {
                                null
                            }
                            CompatibilityWarning.KernelVersion -> null
                        }
                        if (compatibilityWarning == null) {
                            showInstallConfirmation = true
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        compatibilityWarning = null
                        showTargetPicker = true
                    },
                ) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }

    if (showInstallConfirmation) {
        AlertDialog(
            onDismissRequest = { showInstallConfirmation = false },
            icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
            title = {
                DialogDimAmount(0.24f)
                Text(stringResource(R.string.install_confirm_title))
            },
            text = {
                Text(
                    when {
                        selectedProfile != null -> stringResource(R.string.install_confirm_body)
                        else -> when (payloadSource) {
                            PayloadSource.Bundled -> stringResource(R.string.install_confirm_body_bundled)
                            PayloadSource.Custom -> stringResource(
                                R.string.install_confirm_body_custom,
                                customPayload?.displayName.orEmpty(),
                            )
                            PayloadSource.Online -> stringResource(R.string.install_confirm_body)
                        }
                    },
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    showInstallConfirmation = false
                    openInstaller(selectedProfile?.profileId)
                    selectedProfile = null
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalGlassBackdrop provides glassBackdrop,
        ) {
            Scaffold(
                // 背景由采集层里的 AppBackground 画（它同时也是玻璃的采集源），
                // 所以 Scaffold 自己不能再铺一层不透明底，否则会把采集层盖住。
                containerColor = Color.Transparent,
                topBar = {
                    // 通栏顶栏：贴屏幕边缘、不做悬浮留白，
                    // 系统栏留白由 Scaffold 自己处理（defaultWindowInsetsPadding 默认 true）。
                    SmallTopAppBar(
                        title = stringResource(selectedPage.label),
                        // 顶栏：**不透明**，且取 miuix 语义色 `surfaceContainer`
                        // （浅色纯白 / 深色 #242424）—— 与卡片同色，页面是 `surface`。
                        // 之前用 glassSurface(tintAlpha=0.97) 实测仍是 (220~240) 的灰 ——
                        // 只要有一点透明度，下方滚动的深色文字就会渗上来变成灰蒙蒙。
                        // 毛玻璃在浅色背景下本来就看不出效果，所以这里直接铺不透明色，
                        // 把「玻璃感」全部交给底栏（那里有 backdrop 的折射，效果才明显）。
                        modifier = Modifier.background(MiuixTheme.colorScheme.surfaceContainer),
                        color = Color.Transparent,
                    )
                },
                // 顶栏与底栏都已移出 Scaffold 的槽位，改为内容区内的悬浮层
            ) { padding ->
                // 完整传下去：状态栏 / 顶栏 / 左右系统栏 / 底部手势条的高度都在里面。
                // 页面内部不再用 Modifier.padding() 收窄视口（那会让内容无法从玻璃底栏下滚过），
                // 而是把这些值并入 LazyColumn 的 contentPadding —— 列表仍铺满全屏，
                // 内容本身收在系统栏内侧，既不遮挡、又保留液态玻璃效果。
                val layoutDirection = LocalLayoutDirection.current
                // 悬浮底栏浮在内容之上（不在 Scaffold 的 bottomBar 槽位里），
                // 所以必须手动把它的高度让出来，否则最后一张卡片会被压住。
                val contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding() + FloatingBarReservedHeight,
                )

                Box(modifier = Modifier.fillMaxSize()) {
                // ── 采集层：**背景 + 全部页面内容**，这就是玻璃唯一能糊到的东西 ──
                //
                // 两个都不能少：
                // ① 只有内容、没有背景 → 页面空白处录下来的是**透明**，糊透明还是透明，
                //    栏下那片就等于「没被处理过的原始背景」，玻璃看起来什么都没干；
                // ② 只有背景、没有内容 → 糊一个纯色得到的还是同一个纯色。
                //    （模糊是低通滤波：背景的高频为零，糊前糊后是同一片平坦。）
                //
                // 顶栏 / 底栏都在这个 Box **之外**，所以不会录到自己（自引用）。
                Box(modifier = Modifier.fillMaxSize().layerBackdrop(glassBackdrop)) {
                AppBackground()
                AnimatedContent(targetState = selectedPage, label = "page") { page ->
                    when (page) {
                        AppPage.Overview -> OverviewPage(
                            padding = contentPadding,
                            device = device,
                            installState = installState,
                            payloadSource = payloadSource,
                            customPayload = customPayload,
                            onPayloadSourceChanged = { source ->
                                AppPreferences.setPayloadSource(context, source)
                                payloadSource = source
                                installViewModel.refresh()
                            },
                            onImportPayload = {
                                importPayloadLauncher.launch(arrayOf("*/*"))
                            },
                            onRemovePayload = {
                                CustomPayloadStore.clear(context)
                                customPayload = null
                                if (payloadSource == PayloadSource.Custom) {
                                    AppPreferences.setPayloadSource(context, PayloadSource.Online)
                                    payloadSource = PayloadSource.Online
                                }
                                installViewModel.refresh()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.custom_removed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onInstall = {
                                selectedProfile = null
                                // ① 内核 < 6.6 且还没开 Shizuku：先提示"免 ADB 走不通"。
                                //    已经开了 Shizuku 的机器不必再拦（那正是提示里让做的事）。
                                if (!device.supportsGhostLockWithoutAdb && !shizukuMode) {
                                    kernelTooOld = true
                                } else if (advancedMode && payloadSource == PayloadSource.Online) {
                                    // ② 只有「官方在线源」才谈得上挑 profile —— 它要从在线清单里
                                    //    按机型/内核选一个下载。内置动态库与自定义动态库都是**本地**
                                    //    载荷，没有可选项；之前只要开了高级模式就会弹出在线清单，
                                    //    用内置库时也被"定位到官方在线源"，就是这个判断少了来源这一维。
                                    showTargetPicker = true
                                    installViewModel.loadTargetCatalog()
                                } else {
                                    showInstallConfirmation = true
                                }
                            },
                        )
                        AppPage.Builder -> PayloadBuilderPage(
                            padding = contentPadding,
                            state = buildState,
                            onPickBootImage = { bootImageLauncher.launch(arrayOf("*/*")) },
                            onBuild = { showSchemeSheet = true },
                            onApplyAsPayload = {
                                builderViewModel.saveAsPayload { info ->
                                    payloadSource = PayloadSource.Custom
                                    customPayload = info
                                    installViewModel.refresh()
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.builder_applied, info.displayName),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onExportLibrary = { exportArtifact(BuilderExport.Library) },
                            onExportHeader = { exportArtifact(BuilderExport.Header) },
                        )
                        AppPage.History -> HistoryPage(contentPadding, history)
                        AppPage.Settings -> SettingsPage(
                            padding = contentPadding,
                            accentColor = accentColor,
                            themeMode = themeMode,
                            advancedMode = advancedMode,
                            shizukuMode = shizukuMode,
                            onAccentColorChanged = onAccentColorChanged,
                            onThemeModeChanged = onThemeModeChanged,
                            onAdvancedModeChanged = onAdvancedModeChanged,
                            onShizukuModeChanged = onShizukuModeChanged,
                        )
                    }
                }
                }
            }
            
                    // ── Apple 风格悬浮玻璃底栏 ──
                    // 脱离屏幕边缘：四周留白 + 胶囊圆角 + 玻璃材质，浮在内容之上。
                    // 悬浮栏容器：只吃系统栏 inset（padding），保证两条玻璃栏落在安全区内。
                    // 这一层没有 pointerInput，不会拦截下方内容的触摸。
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        Box(
                            modifier = Modifier
                                // ← 上一轮替换代码时把这两行删掉了，导致底栏跑到内容区左上角
                                .align(Alignment.BottomCenter)
                                // 让下层 testTag 出现在无障碍树里（Appium / uiautomator 用）。
                                // 注：本机 Compose 1.11.2 + dump 实测仍未给 resource-id，
                                // 几何验证以 KSU-GEO 日志为准（见 GlassNavBarContent.GEO_PROBE）。
                                .semantics { testTagsAsResourceId = true }
                                .padding(
                                    start = FloatingBarMargin,
                                    end = FloatingBarMargin,
                                    bottom = FloatingBarMargin,
                                )
                                .fillMaxWidth()
                                .height(FloatingBarContentHeight)
                                // 按下缩放作用在**整条 dock**上。它是这一层的父修饰符，
                                // 所以内部的滑块会连同一起被放大；滑块自己再乘一个更大的比例，
                                // 于是「底栏和滑块一起放大、滑块比例更大」是天然成立的。
                                .graphicsLayer {
                                    scaleX = bottomBarScale
                                    scaleY = bottomBarScale
                                }
                        ) {
                            // ── ① 玻璃底板：**只有玻璃、不含任何子内容** ──
                            // 库的 DrawBackdropNode.draw() 顺序是
                            //   onDrawBehind → drawBackdropLayer(玻璃，带形状裁剪) → onDrawSurface → drawContent()
                            // 子内容是在玻璃那一层的裁剪里画的。所以玻璃必须挂在一个空 Box 上，
                            // 内容层做它的**兄弟**；否则滑块放大后超出栏体的部分会被这里裁掉
                            // （表现为「超出底栏显示的部分不显示」）。
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .testTag(NavBarDockTestTag)
                                    .glassNavBar(
                                        backdrop = glassBackdrop,
                                        shape = RoundedCornerShape(50), // 50% = 真正的胶囊（两端半圆）
                                    ),
                            )
                            // ── ② 内容层：与玻璃是兄弟，画在玻璃之上、且不在它的裁剪里 ──
                            GlassNavBarContent(
                                items = AppPage.entries.map { it.icon to stringResource(it.label) },
                                selectedIndex = AppPage.entries.indexOf(selectedPage),
                                onSelect = { selectedPage = AppPage.entries[it] },
                                modifier = Modifier.matchParentSize(),
                                backdrop = glassBackdrop,
                                contentHeight = FloatingBarContentHeight,
                                onBarPressedChange = { bottomBarPressed = it },
                            )
                        }
                    }

                }
}
    }
}

@Composable
private fun DialogDimAmount(amount: Float) {
    val window = (LocalView.current.parent as DialogWindowProvider).window
    SideEffect { window.setDimAmount(amount) }
}

@Composable
private fun OverviewPage(
    padding: PaddingValues,
    device: DeviceSnapshot,
    installState: InstallUiState,
    payloadSource: PayloadSource,
    customPayload: CustomPayloadInfo?,
    onPayloadSourceChanged: (PayloadSource) -> Unit,
    onImportPayload: () -> Unit,
    onRemovePayload: () -> Unit,
    onInstall: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        // 列表铺满全屏（内容可从玻璃底栏下滚过），但内容收在系统栏 + 顶/底栏内侧
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp + padding.calculateStartPadding(layoutDirection),
            top = 20.dp + padding.calculateTopPadding(),
            end = 20.dp + padding.calculateEndPadding(layoutDirection),
            bottom = 20.dp + padding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            // 错峰入场：每项延迟 40ms，弹簧驱动，营造自上而下的「铺开」观感
            Box(modifier = Modifier.staggeredEntry(0)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_app_logo),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(
                            R.string.version_format,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                }
            }
        }
        item { Box(Modifier.staggeredEntry(1)) { InstallStatusCard(installState, onInstall) } }
        item { Box(Modifier.staggeredEntry(2)) { ActivationHintCard() } }
        item {
            Box(modifier = Modifier.staggeredEntry(3)) {
                CustomPayloadCard(
                    payloadSource = payloadSource,
                    customPayload = customPayload,
                    enabled = !installState.busy,
                    onSourceChanged = onPayloadSourceChanged,
                    onImport = onImportPayload,
                    onRemove = onRemovePayload,
                )
            }
        }
        item { Box(Modifier.staggeredEntry(4)) { DeviceCard(device) } }
        item { Box(Modifier.staggeredEntry(5)) { HowItWorksCard() } }
    }
}

@Composable
private fun CustomPayloadCard(
    payloadSource: PayloadSource,
    customPayload: CustomPayloadInfo?,
    enabled: Boolean,
    onSourceChanged: (PayloadSource) -> Unit,
    onImport: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var detailSheet by remember { mutableStateOf<PayloadSource?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Rounded.BuildCircle,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        stringResource(R.string.custom_payload_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.custom_payload_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            PayloadSourceChoice(
                title = stringResource(R.string.payload_source_online),
                detail = stringResource(R.string.payload_source_online_detail),
                selected = payloadSource == PayloadSource.Online,
                enabled = enabled,
                onSelect = { onSourceChanged(PayloadSource.Online) },
                onViewDetail = { detailSheet = PayloadSource.Online },
            )
            PayloadSourceChoice(
                title = stringResource(R.string.payload_source_bundled),
                detail = stringResource(R.string.payload_source_bundled_detail),
                selected = payloadSource == PayloadSource.Bundled,
                enabled = enabled,
                onSelect = { onSourceChanged(PayloadSource.Bundled) },
                onViewDetail = { detailSheet = PayloadSource.Bundled },
            )
            PayloadSourceChoice(
                title = stringResource(R.string.payload_source_custom),
                detail = customPayload?.let {
                    stringResource(
                        R.string.custom_meta_format,
                        it.displayName,
                        android.text.format.Formatter.formatFileSize(context, it.size),
                    )
                } ?: stringResource(R.string.custom_none),
                selected = payloadSource == PayloadSource.Custom,
                enabled = enabled && customPayload != null,
                onSelect = { onSourceChanged(PayloadSource.Custom) },
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onImport, enabled = enabled) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.custom_import_action))
                }
                if (customPayload != null) {
                    OutlinedButton(onClick = onRemove, enabled = enabled) {
                        Text(stringResource(R.string.custom_remove_action))
                    }
                }
            }
            if (customPayload != null) {
                Text(
                    stringResource(R.string.custom_sha_format, customPayload.sha256.take(16) + "…"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    when (detailSheet) {
        PayloadSource.Online -> PayloadSourceDetailSheet(
            title = stringResource(R.string.payload_detail_online_title),
            note = stringResource(R.string.payload_detail_online_note),
            entries = onlineSupportedDevices,
            kernelBadges = true,
            chipBadge = null,
            onDismiss = { detailSheet = null },
        )
        PayloadSource.Bundled -> PayloadSourceDetailSheet(
            title = stringResource(R.string.payload_detail_bundled_title),
            note = stringResource(R.string.payload_detail_bundled_note),
            entries = bundledSupportedDevices,
            kernelBadges = false,
            chipBadge = stringResource(R.string.chip_snapdragon_8_elite),
            onDismiss = { detailSheet = null },
        )
        else -> {}
    }
}

@Composable
private fun PayloadSourceChoice(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onViewDetail: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onViewDetail != null) {
            IconButton(onClick = onViewDetail) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(R.string.payload_detail_action),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private data class SupportedDeviceEntry(
    val name: String,
    val detail: String,
    val badge: String,
)

private val onlineSupportedDevices = listOf(
    SupportedDeviceEntry(
        "Galaxy S25",
        "SM-S9310 · S931B · S931N · S931Q · S931U · S931U1 · S931W · S931Z · SC-51F · SCG31",
        "6.6.98",
    ),
    SupportedDeviceEntry(
        "Galaxy S25+",
        "SM-S9360 · S936B · S936N · S936U · S936U1 · S936W",
        "6.6.98",
    ),
    SupportedDeviceEntry(
        "Galaxy S25 Edge",
        "SM-S9370 · S937B · S937N · S937U · S937U1 · S937W",
        "6.6.98",
    ),
    SupportedDeviceEntry(
        "Galaxy S25 Ultra",
        "SM-S9380 · S938B · S938N · S938Q · S938U · S938U1 · S938W · S938Z · SC-52F · SCG32",
        "6.6.98",
    ),
    SupportedDeviceEntry("Galaxy Z Fold 7", "SM-F966U · SM-F966U1", "6.6.98"),
    SupportedDeviceEntry("Galaxy S24 Ultra", "SM-S928U", "6.1.145"),
    SupportedDeviceEntry("Galaxy S24+", "SM-S926B", "6.1.157"),
    SupportedDeviceEntry("Galaxy S24", "SM-S921B · SM-S921N", "6.1.157"),
    SupportedDeviceEntry(
        "Galaxy S23 Ultra",
        "SM-S9180 · S918B · S918N · S918Q · S918U · S918U1 · S918W",
        "5.15.189",
    ),
    SupportedDeviceEntry(
        "Galaxy A56 5G",
        "SM-A5660 · A566B · A566E · A566S · A566U1 · A566W",
        "6.6.102",
    ),
    SupportedDeviceEntry("Galaxy A36 5G", "SM-A366W", "6.6.46"),
)

private val bundledSupportedDevices = listOf(
    SupportedDeviceEntry("iQOO Neo 10 Pro+", "", ""),
    SupportedDeviceEntry("iQOO Neo 11", "", ""),
    SupportedDeviceEntry("iQOO 13", "", ""),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayloadSourceDetailSheet(
    title: String,
    note: String,
    entries: List<SupportedDeviceEntry>,
    kernelBadges: Boolean,
    chipBadge: String?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.name }) { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                if (entry.detail.isNotEmpty()) {
                                    Text(
                                        entry.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            val badge = when {
                                kernelBadges -> stringResource(R.string.payload_detail_kernel_format, entry.badge)
                                chipBadge != null -> chipBadge
                                else -> null
                            }
                            if (badge != null) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Text(
                                        badge,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment.orEmpty()
} catch (_: Throwable) {
    uri.lastPathSegment.orEmpty()
}

/**
 * 读取 SAF 文档的显示名与字节数（大小拿不到时返回 -1）。
 *
 * 与 [queryDisplayName] 分开：那个只服务于「导入动态库」，这里还要把大小显示在
 * 构建页上 —— 用户看到 "boot.img · 96.0 MB" 才知道自己选对了固件包里的那一个。
 */
private fun queryOpenable(context: Context, uri: Uri): Pair<String, Long> = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
            (name ?: uri.lastPathSegment.orEmpty()) to size
        } else {
            uri.lastPathSegment.orEmpty() to -1L
        }
    } ?: (uri.lastPathSegment.orEmpty() to -1L)
} catch (_: Throwable) {
    uri.lastPathSegment.orEmpty() to -1L
}

@Composable
private fun HowItWorksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.how_it_works), style = MaterialTheme.typography.titleMedium)
            installerSteps.forEach { step ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(36.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(step.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(step.title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(step.detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallStatusCard(installState: InstallUiState, onInstall: () -> Unit) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val uriHandler = LocalUriHandler.current
    val managerInstalled = remember(installState) { isKernelSuManagerInstalled(context) }
    Card(
        onClick = {
            when {
                installState.busy -> Unit
                installState.phase == InstallPhase.Installed -> {
                    if (managerInstalled) {
                        openKernelSuManager(context)
                    } else {
                        uriHandler.openUri(KERNEL_SU_MANAGER_URL)
                    }
                }
                else -> onInstall()
            }
        },
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        // MIUI 原生按压反馈（整块下沉），替代自绘的缩放动画
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                installState.busy -> LoadingIndicator(
                    modifier = Modifier.size(44.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                installState.phase == InstallPhase.Installed -> Icon(
                    Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(44.dp),
                )
                installState.phase == InstallPhase.Failed -> Icon(
                    Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(44.dp),
                )
                else -> Icon(
                    Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(44.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                if (installState.phase == InstallPhase.Installed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_kernelsu),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.status_ksu_active),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    Text(
                        text = when (installState.phase) {
                            InstallPhase.Ready -> stringResource(R.string.status_not_installed)
                            else -> installState.message
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    text = when (installState.phase) {
                        InstallPhase.Installed -> stringResource(
                            if (managerInstalled) {
                                R.string.install_tap_open_manager
                            } else {
                                R.string.install_tap_manager
                            },
                        )
                        InstallPhase.Failed -> stringResource(R.string.install_tap_retry)
                        else -> stringResource(R.string.install_tap_start)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ActivationHintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.activation_hint_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.activation_hint_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InfoRow(Icons.Rounded.Memory, stringResource(R.string.device), "${device.manufacturer} ${device.model} (${device.device})")
            InfoRow(Icons.Rounded.Code, stringResource(R.string.firmware), device.buildId)
            InfoRow(Icons.Rounded.Info, stringResource(R.string.system), "Android ${device.androidRelease} (API ${device.sdk})")
            InfoRow(Icons.Rounded.Security, stringResource(R.string.system_abi), "${device.abi} (${device.pageSize / 1024}K)")
            // 内核版本单独一行，并直接给出「免 ADB 提权能不能用」的判定 ——
            // 这比只显示一个版本号有用得多：用户在按安装之前就该知道路走哪条。
            InfoRow(
                Icons.Rounded.Memory,
                stringResource(R.string.device_kernel),
                device.kernelVersion + " · " + stringResource(
                    if (device.supportsGhostLockWithoutAdb) {
                        R.string.kernel_support_ok
                    } else {
                        R.string.kernel_support_need_shizuku
                    },
                ),
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryPage(
    padding: PaddingValues,
    history: List<InstallHistoryEntry>,
) {
    var selectedHistoryId by remember { mutableStateOf<String?>(null) }
    val selectedEntry = history.firstOrNull { it.id == selectedHistoryId }
    BackHandler(enabled = selectedEntry != null) { selectedHistoryId = null }

    AnimatedContent(
        targetState = selectedEntry,
        contentKey = { it?.id ?: "history-list" },
        label = "history-detail",
    ) { entry ->
        if (entry == null) {
            HistoryList(
                padding = padding,
                history = history,
                onEntryClick = { selectedHistoryId = it.id },
            )
        } else {
            HistoryDetail(
                padding = padding,
                entry = entry,
                onBack = { selectedHistoryId = null },
            )
        }
    }
}

@Composable
private fun HistoryList(
    padding: PaddingValues,
    history: List<InstallHistoryEntry>,
    onEntryClick: (InstallHistoryEntry) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        // 列表铺满全屏（内容可从玻璃底栏下滚过），但内容收在系统栏 + 顶/底栏内侧
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp + padding.calculateStartPadding(layoutDirection),
            top = 20.dp + padding.calculateTopPadding(),
            end = 20.dp + padding.calculateEndPadding(layoutDirection),
            bottom = 20.dp + padding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(top = 20.dp, bottom = 14.dp),
            )
        }
        if (history.isEmpty()) {
            item { EmptyHistoryCard() }
        } else {
            itemsIndexed(history, key = { _, entry -> entry.id }) { index, entry ->
                Box(Modifier.staggeredEntry(index)) {
                    HistoryEntryCard(entry, onClick = { onEntryClick(entry) })
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(32.dp))
            Column {
                Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.history_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(entry: InstallHistoryEntry, onClick: () -> Unit) {
    val accent = historyResultAccent(entry.result)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // MIUI 原生按压反馈（整块下沉），替代自绘的缩放动画
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Icon(
                historyResultIcon(entry.result),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = accent,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(historyResultLabel(entry.result), style = MaterialTheme.typography.titleMedium)
                Text(
                    formatHistoryTime(entry.startedAtMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
    }
}

/**
 * 运行结果 → miuix 语义色。
 *
 * 结果只体现在**图标**上，卡片底色统一交给 miuix `Card` 的默认色
 * （`surfaceContainer`）。整块染色的卡片在一屏历史里会非常吵；
 * MIUI 的做法正是「白卡片 + 彩色图标/徽标」。
 *
 * 顺带修掉一个老 bug：上一版 Failed 的副标题用的是 `onError`（白色），
 * 而卡片本身是白的 —— 那行字在浅色模式里根本看不见。
 */
@Composable
private fun historyResultAccent(result: InstallRunResult): Color = when (result) {
    InstallRunResult.Running -> MaterialTheme.colorScheme.primary
    // **不再区分成功 / 失败**：这条链在蓝厂（vivo/iQOO）机器上会被厂商的反 root 拦下，
    // 判定"失败"既不准也没意义 —— 运行记录只负责**记录过程**。
    // 是否真的装上了，看首页的 KernelSU 状态就够了（那是实测出来的，不是推断的）。
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun HistoryDetail(
    padding: PaddingValues,
    entry: InstallHistoryEntry,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let { uri -> saveRunLog(context, uri, entry) }
    }
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        // 列表铺满全屏（内容可从玻璃底栏下滚过），但内容收在系统栏 + 顶/底栏内侧
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp + padding.calculateStartPadding(layoutDirection),
            top = 20.dp + padding.calculateTopPadding(),
            end = 20.dp + padding.calculateEndPadding(layoutDirection),
            bottom = 20.dp + padding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    stringResource(R.string.history_detail_title),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f),
                )
                // 保存到**指定目录**（系统文件选择器，用户可以挑任意位置）
                IconButton(onClick = {
                    exportLogLauncher.launch(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, runLogFileName(entry))
                        },
                    )
                }) {
                    Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.export_log))
                }
                // 一键复制到剪贴板（发群、贴 issue 都只用这一下）
                IconButton(onClick = {
                    context.copyLogToClipboard(
                        entry.log.ifBlank { context.getString(R.string.history_log_empty) },
                    )
                }) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.copy_log),
                    )
                }
            }
        }
        item { HistoryResultCard(entry) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                    ) {
                Text(
                    text = entry.log.ifBlank { stringResource(R.string.history_log_empty) },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun HistoryResultCard(entry: InstallHistoryEntry) {
    val accent = historyResultAccent(entry.result)
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                historyResultIcon(entry.result),
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                tint = accent,
            )
            Column {
                Text(historyResultLabel(entry.result), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.history_started, formatHistoryTime(entry.startedAtMillis)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.completedAtMillis?.let { completedAt ->
                    Text(
                        stringResource(R.string.history_completed, formatHistoryTime(completedAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.profileId?.let { profileId ->
                    Text(
                        stringResource(R.string.history_payload, profileId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(
                        if (entry.usedShizuku) {
                            R.string.history_shizuku_used
                        } else {
                            R.string.history_shizuku_not_used
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun historyResultLabel(result: InstallRunResult): String = stringResource(
    when (result) {
        InstallRunResult.Running -> R.string.history_running
        else -> R.string.history_recorded
    },
)

private fun historyResultIcon(result: InstallRunResult): ImageVector = when (result) {
    InstallRunResult.Running -> Icons.Rounded.Schedule
    // 一律用中性的"记录"图标：红色叉号会让人以为这次一定没用，
    // 而实际上蓝厂机器上"跑完了但没拿到 root"和"真的跑挂了"根本区分不开。
    else -> Icons.Rounded.History
}

@Composable
private fun formatHistoryTime(timestamp: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(timestamp, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale)
            .format(Date(timestamp))
    }
}

private fun runLogFileName(entry: InstallHistoryEntry): String =
    "KSuRoot-" +
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(entry.startedAtMillis)) +
        ".log"

private fun saveRunLog(context: Context, uri: Uri, entry: InstallHistoryEntry) {
    val content = entry.log.ifBlank { context.getString(R.string.history_log_empty) }
    val saved = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("open failed")
        true
    }.getOrDefault(false)
    Toast.makeText(
        context,
        if (saved) {
            context.getString(R.string.export_log_saved)
        } else {
            context.getString(R.string.export_log_failed)
        },
        Toast.LENGTH_LONG,
    ).show()
}

@Composable
private fun SettingsPage(
    padding: PaddingValues,
    accentColor: AccentColor,
    themeMode: AppThemeMode,
    advancedMode: Boolean,
    shizukuMode: Boolean,
    onAccentColorChanged: (AccentColor) -> Unit,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onAdvancedModeChanged: (Boolean) -> Unit,
    onShizukuModeChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showShizukuMissingDialog by remember { mutableStateOf(false) }
    var languageMenuTop by remember { mutableStateOf(32.dp) }
    var colorMenuTop by remember { mutableStateOf(32.dp) }
    val density = LocalDensity.current
    val currentLanguageTag = AppPreferences.languageTag(context)

    if (showShizukuMissingDialog) {
        AlertDialog(
            onDismissRequest = { showShizukuMissingDialog = false },
            icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
            title = {
                DialogDimAmount(0.24f)
                Text(stringResource(R.string.shizuku_not_running_title))
            },
            text = { Text(stringResource(R.string.shizuku_not_running_body)) },
            confirmButton = {
                FilledTonalButton(onClick = {
                    showShizukuMissingDialog = false
                    openShizukuManager(context)
                }) {
                    Text(stringResource(R.string.action_download_shizuku))
                }
            },
            dismissButton = {
                TextButton(onClick = { showShizukuMissingDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showLanguageDialog) {
        SideChoiceMenu(
            choices = languageOptions.map { stringResource(it.label) },
            selectedIndex = languageOptions.indexOfFirst {
                it.tag.isEmpty() && currentLanguageTag.isEmpty() ||
                    it.tag.isNotEmpty() && currentLanguageTag.startsWith(it.tag.substringBefore('-'))
            }.coerceAtLeast(0),
            topOffset = languageMenuTop,
            onSelected = { index ->
                showLanguageDialog = false
                AppPreferences.setLanguage(context, languageOptions[index].tag)
            },
            onDismiss = { showLanguageDialog = false },
        )
    }

    if (showColorDialog) {
        val colors = AccentColor.entries
        SideChoiceMenu(
            choices = colors.map { accentLabel(it) },
            selectedIndex = colors.indexOf(accentColor),
            topOffset = colorMenuTop,
            onSelected = { index ->
                showColorDialog = false
                onAccentColorChanged(colors[index])
            },
            onDismiss = { showColorDialog = false },
        )
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        // 列表铺满全屏（内容可从玻璃底栏下滚过），但内容收在系统栏 + 顶/底栏内侧
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp + padding.calculateStartPadding(layoutDirection),
            top = 20.dp + padding.calculateTopPadding(),
            end = 20.dp + padding.calculateEndPadding(layoutDirection),
            bottom = 20.dp + padding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 18.dp)) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge)
                Text(
                    stringResource(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionLabel(stringResource(R.string.appearance)) }
        item {
            ThemeModeSelector(themeMode, onThemeModeChanged)
        }
        item {
            // MIUI 分组列表：一个 24dp 圆角容器承载若干扁平行，行内自带 Chevron。
            // 用 miuix-preference 取代原先手写的 SettingsCard / SettingsSwitchCard：
            // 行高、按压反馈、箭头、Switch 位置全部交给 miuix 统一，不再各写一套。
            Card(
                modifier = Modifier.fillMaxWidth(),
                    ) {
                ArrowPreference(
                    title = stringResource(R.string.material_color),
                    summary = stringResource(R.string.material_color_description),
                    startAction = { PreferenceIcon(Icons.Rounded.Palette) },
                    endActions = { PreferenceValue(accentLabel(accentColor)) },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        colorMenuTop = with(density) { coordinates.positionInWindow().y.toDp() }
                    },
                    onClick = { showColorDialog = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.language),
                    summary = stringResource(R.string.language_description),
                    startAction = { PreferenceIcon(Icons.Rounded.Language) },
                    endActions = { PreferenceValue(languageLabel(currentLanguageTag)) },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        languageMenuTop = with(density) { coordinates.positionInWindow().y.toDp() }
                    },
                    onClick = { showLanguageDialog = true },
                )
                // SwitchPreference 内部即 onClick = { onCheckedChange(!checked) } + role = Role.Switch，
                // 因此「整行点击也能切换」的原行为被完整保留，无需额外补 onClick。
                SwitchPreference(
                    checked = shizukuMode,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            onShizukuModeChanged(false)
                        } else {
                            scope.launch {
                                ShizukuController.pingUntilRunning()
                                if (ShizukuController.isRunning()) {
                                    onShizukuModeChanged(true)
                                    if (!ShizukuController.isGranted()) {
                                        ShizukuController.requestPermission()
                                    }
                                } else {
                                    showShizukuMissingDialog = true
                                }
                            }
                        }
                    },
                    title = stringResource(R.string.shizuku_mode),
                    summary = stringResource(R.string.shizuku_mode_description),
                    startAction = { PreferenceIcon(Icons.Rounded.VerifiedUser) },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.advanced)) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                    ) {
                SwitchPreference(
                    checked = advancedMode,
                    onCheckedChange = onAdvancedModeChanged,
                    title = stringResource(R.string.advanced_mode),
                    summary = stringResource(R.string.advanced_mode_description),
                    startAction = { PreferenceIcon(Icons.Rounded.Memory) },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.about)) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                    ) {
                ArrowPreference(
                    title = stringResource(R.string.about),
                    summary = stringResource(R.string.about_description),
                    startAction = { PreferenceIcon(Icons.Rounded.Info) },
                    onClick = { showAboutDialog = true },
                )
            }
        }
    }
}

/** 「载荷构建」页可导出的两种产物。 */
private enum class BuilderExport { Library, Header }

@StringRes
private fun schemeTitle(scheme: PayloadScheme): Int = when (scheme) {
    PayloadScheme.Universal -> R.string.builder_scheme_universal
    PayloadScheme.VivoVrKo -> R.string.builder_scheme_vivo
}

@StringRes
private fun schemeDetail(scheme: PayloadScheme): Int = when (scheme) {
    PayloadScheme.Universal -> R.string.builder_scheme_universal_detail
    PayloadScheme.VivoVrKo -> R.string.builder_scheme_vivo_detail
}

/**
 * 「选用哪种方案」的选择弹窗：从下往上弹出（ModalBottomSheet），风格与
 * 目标选择弹窗一致 —— 两个方案各占一张 miuix 卡片，通用方案标「推荐」。
 *
 * 为什么必须让用户选：两份载荷的**适用面**不同。通用方案在大多数 GKI 6.6 设备上
 * 都能跑，但没有 vivo 的 `vr.ko` 反 root 绕过；vivo/iQOO 上少了那条绕过会被
 * 厂商的反 root 拦下来，所以那两个品牌必须选第二项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayloadSchemeSheet(
    onDismiss: () -> Unit,
    onPick: (PayloadScheme) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.builder_scheme_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.builder_scheme_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PayloadScheme.entries.forEachIndexed { index, scheme ->
                val recommended = scheme == PayloadScheme.Universal
                Card(
                    onClick = { onPick(scheme) },
                    modifier = Modifier.fillMaxWidth(),
                    pressFeedbackType = PressFeedbackType.Sink,
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Build,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = if (recommended) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "${index + 1}. " + stringResource(schemeTitle(scheme)),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (recommended) {
                                    Text(
                                        text = stringResource(R.string.builder_scheme_recommended),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                text = stringResource(schemeDetail(scheme)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 载荷构建页：**boot.img → 内核偏移 → 打补丁的动态库**。
 *
 * 页面本身只负责交互与呈现，算法全在 `com.kernelpack`（纯 Kotlin，无 Android 依赖）。
 * 三条设计原则与其余页面保持一致：
 * 1. 卡片用 miuix `Card` 默认样式（页面 `surface`、卡片 `surfaceContainer`），不写死颜色；
 * 2. 内容用 `LazyColumn` 铺满全屏，靠 `contentPadding` 收进系统栏与悬浮底栏内侧
 *    （这样构建日志可以一直滚到玻璃底栏下面去，视觉上是同一套语言）；
 * 3. 长耗时操作只给「阶段 + 实时日志」，不给假进度条 —— 解析过程本来就没有百分比。
 *
 * 关于内存：构建期间峰值几十 MB（boot.img + 内核镜像 + 十万个符号），
 * manifest 里已开 `largeHeap`；`onBuild` 返回后 ViewModel 只留下摘要与产物字节。
 */
@Composable
private fun PayloadBuilderPage(
    padding: PaddingValues,
    state: PayloadBuildState,
    onPickBootImage: () -> Unit,
    onBuild: () -> Unit,
    onApplyAsPayload: () -> Unit,
    onExportLibrary: () -> Unit,
    onExportHeader: () -> Unit,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val logScroll = rememberScrollState()
    // 日志增长时自动贴底：构建过程中永远看得到最新一行。
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp + padding.calculateStartPadding(layoutDirection),
            top = 20.dp + padding.calculateTopPadding(),
            end = 20.dp + padding.calculateEndPadding(layoutDirection),
            bottom = 20.dp + padding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Build,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.builder_heading),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                }
                Text(
                    text = stringResource(R.string.builder_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item { SectionLabel(stringResource(R.string.builder_section_input)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.builder_boot_image),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (state.sourceName.isBlank()) {
                                    stringResource(R.string.builder_boot_none)
                                } else if (state.sourceSize > 0) {
                                    state.sourceName + " · " +
                                        android.text.format.Formatter.formatFileSize(context, state.sourceSize)
                                } else {
                                    state.sourceName
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.builder_boot_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 一上一下：先选文件、再开始构建。两步是**有先后**的，
                    // 并排放会让人以为可以随便点其中一个。
                    FilledTonalButton(
                        onClick = onPickBootImage,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().height(BuilderButtonHeight),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.builder_pick_boot))
                    }
                    Button(
                        onClick = onBuild,
                        enabled = !state.busy && state.sourceName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(BuilderButtonHeight),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Build,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.builder_run))
                    }
                }
            }
        }

        if (state.busy) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.builder_running),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = state.log.lastOrNull().orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (state.log.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.builder_log_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.log.takeLast(LOG_TAIL_LINES).joinToString("\n"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(logScroll),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        state.error?.let { message ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Error,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        state.scheme?.let { scheme ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Build,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(schemeTitle(scheme)),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = scheme.versionLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (state.baseLibraryName.isNotBlank()) {
                                Text(
                                    text = stringResource(
                                        R.string.builder_base_library,
                                        state.baseLibraryName,
                                        android.text.format.Formatter.formatFileSize(
                                            context,
                                            state.baseLibrarySize,
                                        ),
                                    ) + " · sha256 " + state.baseLibrarySha256.take(16) + "…",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.summary.isNotBlank()) {
            item { SectionLabel(stringResource(R.string.builder_section_result)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.summary.trim(),
                        modifier = Modifier.padding(18.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }

        if (state.notices.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.builder_notices),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        state.notices.forEach { notice ->
                            Text(
                                text = "· $notice",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (state.outputSize > 0) {
            item { SectionLabel(stringResource(R.string.builder_section_output)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.outputName,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.builder_output_size,
                                        android.text.format.Formatter.formatFileSize(context, state.outputSize),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.builder_output_sha,
                                        state.outputSha256.take(32) + "…",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // 主操作：用主题色（Button 默认就是 colorScheme.primary）并占满整行。
                        // 之前它被 `!state.appliedAsPayload` 关掉了 —— 但构建成功后
                        // 产物**已经自动**写进自定义载荷，于是这个按钮永远是灰的，
                        // 看起来像"坏了"。它本来就该是个可重复点的动作（重新写一遍即可）。
                        Button(
                            onClick = onApplyAsPayload,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().height(BuilderButtonHeight),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.builder_set_as_payload),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        if (state.appliedAsPayload) {
                            Text(
                                text = stringResource(R.string.builder_applied_state),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = onExportLibrary,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.builder_export_so))
                            }
                            OutlinedButton(
                                onClick = onExportHeader,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.builder_export_header))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 载荷构建页里主按钮的高度：48dp＝MIUI 惯用的「可点行」高度，比默认 Button 更高、更醒目。 */
private val BuilderButtonHeight = 48.dp

/** 日志卡片里最多显示多少行（内存里保留 [PayloadBuilderViewModel] 的 400 行）。 */
private const val LOG_TAIL_LINES = 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSelectionSheet(
    device: DeviceSnapshot,
    catalog: TargetCatalogUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onNext: (TargetProfile) -> Unit,
) {
    var showOnlyMyDevice by remember { mutableStateOf(true) }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    val visibleProfiles = remember(catalog.profiles, showOnlyMyDevice, device) {
        if (showOnlyMyDevice) {
            catalog.profiles.filter { it.matches(device) }
        } else {
            catalog.profiles
        }
    }
    val selectedProfile = catalog.profiles.firstOrNull { it.profileId == selectedProfileId }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.select_device_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.select_device_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = showOnlyMyDevice,
                        role = Role.Checkbox,
                        onValueChange = { enabled ->
                            showOnlyMyDevice = enabled
                            if (enabled && selectedProfile?.matches(device) == false) {
                                selectedProfileId = null
                            }
                        },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Checkbox(
                    state = if (showOnlyMyDevice) ToggleableState.On else ToggleableState.Off,
                    onClick = null,
                )
                Text(stringResource(R.string.show_my_device_only), style = MaterialTheme.typography.titleMedium)
            }

            when {
                catalog.loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator(color = MaterialTheme.colorScheme.onSurface)
                }
                catalog.error != null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(catalog.error, color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                visibleProfiles.isEmpty() -> Text(
                    stringResource(R.string.no_matching_devices),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleProfiles, key = TargetProfile::profileId) { profile ->
                        val selected = selectedProfileId == profile.profileId
                        val matchingModel = profile.models.firstOrNull {
                            it.equals(device.model, ignoreCase = true)
                        }
                        val modelLabel = matchingModel ?: profile.models.take(3).joinToString().let {
                            if (profile.models.size > 3) "$it +${profile.models.size - 3}" else it
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { selectedProfileId = profile.profileId },
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        profile.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        modelLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { selectedProfile?.let(onNext) },
                    enabled = selectedProfile != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_next))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun PreferenceIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(26.dp),
        tint = MiuixTheme.colorScheme.primary,
    )
}

@Composable
private fun PreferenceValue(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.labelLarge,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ThemeModeSelector(
    themeMode: AppThemeMode,
    onThemeModeChanged: (AppThemeMode) -> Unit,
) {
    val themeModes = AppThemeMode.entries
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        themeModes.forEachIndexed { index, mode ->
            ToggleButton(
                checked = themeMode == mode,
                onCheckedChange = { onThemeModeChanged(mode) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    themeModes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(
                    imageVector = when (mode) {
                        AppThemeMode.System -> Icons.Rounded.BrightnessAuto
                        AppThemeMode.Light -> Icons.Rounded.LightMode
                        AppThemeMode.Dark -> Icons.Rounded.DarkMode
                    },
                    contentDescription = null,
                )
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(themeModeLabel(mode), maxLines = 1)
            }
        }
    }
}

/**
 * 内核低于 6.6 时按「安装 KernelSU」弹的提示框。
 *
 * 为什么要拦一下：GhostLock（CVE-2026-43499）打的是 `rt_mutex` + `pipe_buffer` 那条链，
 * **6.6 及以上**内核才有（6.6 与 6.12 两系都验证过）。低于 6.6 的机器上免 ADB 那条路
 * 根本走不通，硬跑只会浪费时间（甚至在错误偏移上翻车），必须改走 Shizuku / ADB。
 *
 * 确认按钮带 3 秒倒计时：这类"会把设备带进高危路径"的提示，点开即确认最容易变成
 * 肌肉记忆，强制等 3 秒是为了让人真的把上面那两行读完。
 */
@Composable
private fun KernelTooOldDialog(
    kernelVersion: String,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(CountdownSeconds) }
    LaunchedEffect(kernelVersion) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = {
            DialogDimAmount(0.24f)
            Text(stringResource(R.string.kernel_gate_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.kernel_gate_body, kernelVersion))
                Text(
                    text = stringResource(R.string.kernel_gate_shizuku_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onContinue,
                enabled = remaining == 0,
            ) {
                Text(
                    if (remaining > 0) {
                        stringResource(R.string.kernel_gate_wait, remaining)
                    } else {
                        stringResource(R.string.kernel_gate_continue)
                    },
                )
            }
        },
        dismissButton = {
            // 取消用主题色的实心按钮：倒计时期间**只有它**能点，
            // 视觉上必须一眼看出"现在能按的是这个"。
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 内核提示框确认按钮的倒计时秒数。 */
private const val CountdownSeconds = 3

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            DialogDimAmount(0.24f)
            Text(stringResource(R.string.about_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.about_body))
                Text(
                    stringResource(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Surface(
                    onClick = { uriHandler.openUri(KERNEL_SU_HOME_URL) },
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_kernelsu), contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.kernelsu_card_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.kernelsu_card_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Rounded.Link, contentDescription = stringResource(R.string.open_github))
                    }
                }
                Surface(
                    onClick = { uriHandler.openUri(KSUROOT_URL) },
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_github), contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.ksuroot_card_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.ksuroot_card_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Rounded.Link, contentDescription = stringResource(R.string.open_github))
                    }
                }
                Surface(
                    onClick = { uriHandler.openUri(ROOT_MY_GALAXY_URL) },
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_github), contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.github_card_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.github_card_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Rounded.Link, contentDescription = stringResource(R.string.open_github))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun SideChoiceMenu(
    choices: List<String>,
    selectedIndex: Int,
    topOffset: Dp,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun closeMenu(afterAnimation: () -> Unit) {
        if (closing) return
        closing = true
        visible = false
        coroutineScope.launch {
            delay(MENU_EXIT_WAIT_MILLIS)
            afterAnimation()
        }
    }

    LaunchedEffect(Unit) {
        visible = true
    }

    Popup(
        onDismissRequest = { closeMenu(onDismiss) },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val estimatedHeight = 16.dp + 56.dp * choices.size
            val constrainedTop = minOf(
                topOffset,
                maxHeight - estimatedHeight - 24.dp,
            ).coerceAtLeast(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { closeMenu(onDismiss) },
                    ),
            )
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = constrainedTop, end = 18.dp),
                enter = scaleIn(
                    animationSpec = keyframes {
                        durationMillis = 200
                        1.025f at 95
                        0.995f at 155
                    },
                    initialScale = 0.94f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ),
                exit = scaleOut(
                    animationSpec = tween(durationMillis = MENU_EXIT_ANIMATION_MILLIS),
                    targetScale = 0.86f,
                    transformOrigin = TransformOrigin(1f, 0.5f),
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = 160,
                        delayMillis = 20,
                    ),
                ),
            ) {
                Surface(
                    modifier = Modifier
                        .width(196.dp)
                        .heightIn(max = 620.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 0.dp,
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(choices) { index, choice ->
                            val selected = index == selectedIndex
                            Surface(
                                onClick = {
                                    closeMenu { onSelected(index) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = if (selected) {
                                    MaterialTheme.shapes.extraLarge
                                } else {
                                    MaterialTheme.shapes.medium
                                },
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                    Text(
                                        text = choice,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MENU_EXIT_ANIMATION_MILLIS = 180
private const val MENU_EXIT_WAIT_MILLIS = 200L
private const val ROOT_MY_GALAXY_URL = "https://github.com/BuSung-dev/Root-My-Galaxy"

/** 本软件自己的仓库。 */
private const val KSUROOT_URL = "https://github.com/hmascs/KSuRoot"

@Composable
private fun languageLabel(tag: String): String = when {
    tag.startsWith("ko") -> stringResource(R.string.language_korean)
    tag.startsWith("en") -> stringResource(R.string.language_english)
    tag.startsWith("ja") -> stringResource(R.string.language_japanese)
    tag.startsWith("zh-TW") -> stringResource(R.string.language_chinese_traditional)
    tag.startsWith("zh") -> stringResource(R.string.language_chinese)
    tag.startsWith("tr") -> stringResource(R.string.language_turkish)
    tag.startsWith("ru") -> stringResource(R.string.language_russian)
    tag.startsWith("vi") -> stringResource(R.string.language_vietnamese)
    else -> stringResource(R.string.language_system)
}

@Composable
private fun accentLabel(color: AccentColor): String = when (color) {
    AccentColor.Dynamic -> stringResource(R.string.color_dynamic)
    AccentColor.Blue -> stringResource(R.string.color_blue)
    AccentColor.Violet -> stringResource(R.string.color_violet)
    AccentColor.Green -> stringResource(R.string.color_green)
    AccentColor.Orange -> stringResource(R.string.color_orange)
    AccentColor.MiuBlue -> stringResource(R.string.color_miu_blue)
}

@Composable
private fun themeModeLabel(themeMode: AppThemeMode): String = when (themeMode) {
    AppThemeMode.System -> stringResource(R.string.theme_system)
    AppThemeMode.Light -> stringResource(R.string.theme_light)
    AppThemeMode.Dark -> stringResource(R.string.theme_dark)
}
