package com.ting.root.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.ting.root.AccentColor
import com.ting.root.AppThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * MIUI / HyperOS 主色板。
 *
 * 这三个值是 miuix 自身 `lightColorScheme()` 的默认 primary 系（`primary = 0xFF3482FF`），
 * 所以「MIUI 蓝」不需要靠 Monet 重新生成，直接用 miuix 内置配色即可拿到最原生的观感；
 * 这里保留常量仅用于交互态（按压 / 高亮）与强调色种子，避免在组件里散落魔法色值。
 */
private const val MIUI_BLUE_PRIMARY = 0xFF3482FF
private const val MIUI_BLUE_PRESSED = 0xFF2E6BE6
private const val MIUI_BLUE_HIGHLIGHT = 0xFF5B9DFF

/** 按压态 / 高亮态：用于按钮、列表项与玻璃边缘的交互反馈。 */
object MiuixAccent {
    val pressed = Color(MIUI_BLUE_PRESSED)
    val highlight = Color(MIUI_BLUE_HIGHLIGHT)
}

/**
 * KSU 风格的圆角规格：卡片 24dp、胶囊 32dp。
 * 参考图实测卡片与容器都是大圆角，且不用阴影，靠圆角+底色差分层。
 */
private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Light),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
)

private fun accentSeed(context: Context, accentColor: AccentColor): Color = when (accentColor) {
    AccentColor.Dynamic -> Color(context.getColor(android.R.color.system_accent1_500))
    AccentColor.Blue -> Color(0xFF415F91)
    AccentColor.Violet -> Color(0xFF6750A4)
    AccentColor.Green -> Color(0xFF356A35)
    AccentColor.Orange -> Color(0xFF8B4F23)
    // MIUI 默认蓝：与 miuix 内置 primary 完全一致
    AccentColor.MiuBlue -> Color(MIUI_BLUE_PRIMARY)
}

/**
 * 把 miuix 的 [Colors] 映射成 Material3 [ColorScheme]。
 *
 * 之所以要映射而不是各配一套：项目中仍有三处必须用 Material3 的组件
 * （`AlertDialog` / `ModalBottomSheet` / `OutlinedTextField`），
 * 让它们与 miuix 组件共用同一套语义色，才能避免「对话框是 Material 紫、页面是 MIUI 蓝」的割裂。
 */
private fun Colors.toMaterialScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiaryContainer,
        onTertiary = onTertiaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariantSummary,
        surfaceTint = primary,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = dividerLine,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}

@Composable
fun RootMyGalaxyTheme(
    accentColor: AccentColor,
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 跟随「系统 / 浅色 / 深色」三态，MIUI 行为。
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        AppThemeMode.System -> systemDarkTheme
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }

    val keyColor = accentSeed(context, accentColor)
    val controller = remember(accentColor, themeMode, darkTheme) {
        ThemeController(
            // MiuBlue 用 miuix 内置配色（primary 即 #3482FF），其余强调色走 Monet；
            // 深浅三态与之正交。
            colorSchemeMode = when {
                accentColor == AccentColor.MiuBlue -> when (themeMode) {
                    AppThemeMode.System -> ColorSchemeMode.System
                    AppThemeMode.Light -> ColorSchemeMode.Light
                    AppThemeMode.Dark -> ColorSchemeMode.Dark
                }
                else -> when (themeMode) {
                    AppThemeMode.System -> ColorSchemeMode.MonetSystem
                    AppThemeMode.Light -> ColorSchemeMode.MonetLight
                    AppThemeMode.Dark -> ColorSchemeMode.MonetDark
                }
            },
            keyColor = if (accentColor == AccentColor.Dynamic || accentColor == AccentColor.MiuBlue) {
                null
            } else {
                keyColor
            },
            // Neutral：surface/background 保持中性，不被强调色染色。
            // 主题色的作用范围收敛到 primary 系（按钮、开关、选中态），
            // 这样「调主题色 = 改按钮颜色」，背景始终是干净的中性色。
            paletteStyle = ThemePaletteStyle.Neutral,
            isDark = darkTheme,
        )
    }

    SideEffect {
        val window = (context as Activity).window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MiuixTheme(controller = controller) {
        val miuixScheme = MiuixTheme.colorScheme
        // 注意：这里刻意不用 remember —— miuix 会复用同一个 Colors 实例并原地更新其状态属性，
        // 实例身份始终不变，把它当 key 永远不会失效，会导致「切换强调色后 Material3 配色不刷新」。
        // 直接每次重组重算（只是一次色值搬运，成本可忽略），保证两套主题始终一致。
        val materialColors = miuixScheme.toMaterialScheme(darkTheme)
        MaterialExpressiveTheme(
            colorScheme = materialColors,
            typography = AppTypography,
            shapes = AppShapes,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
