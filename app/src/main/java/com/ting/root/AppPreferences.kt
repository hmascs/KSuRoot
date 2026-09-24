package com.ting.root

import android.app.LocaleManager
import android.content.Context
import com.kernelpack.policy.SeriesOverride
import android.os.LocaleList

enum class AccentColor(val storedValue: String) {
    Dynamic("dynamic"),
    Blue("blue"),
    Violet("violet"),
    Green("green"),
    Orange("orange"),
    MiuBlue("miu_blue"); // MIUI 默认蓝（#2371E1）

    companion object {
        fun fromStoredValue(value: String?): AccentColor =
            entries.firstOrNull { it.storedValue == value } ?: Dynamic
    }
}

enum class AppThemeMode(val storedValue: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromStoredValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: System
    }
}

/**
 * 载荷来源。
 *
 * 原先还有一个 `Online`（三星专用的 GitHub 在线源）—— 已整体移除：
 * 那份在线清单登记的机型全是三星 Galaxy，载荷也是为三星内核编的；
 * 随包内置库现在覆盖小米与 vivo，在线源既没有可用目标、又白白多一次网络往返。
 *
 * 枚举名与 `storedValue` 都保持不变，只有条目数变了 —— 老用户存档里的
 * `"online"` 会被 [fromStoredValue] 归一化成 [Bundled]，不会出现"来源丢失"。
 */
enum class PayloadSource(val storedValue: String) {
    Bundled("bundled"),
    Custom("custom");

    companion object {
        fun fromStoredValue(value: String?): PayloadSource =
            entries.firstOrNull { it.storedValue == value } ?: Bundled
    }
}

object AppPreferences {
    private const val PREFERENCES = "appearance"
    private const val ACCENT_COLOR = "accent_color"
    private const val THEME_MODE = "theme_mode"
    private const val ADVANCED_MODE = "advanced_mode"
    private const val SHIZUKU_MODE = "shizuku_mode"
    private const val PAYLOAD_SOURCE = "payload_source"
    private const val CONSUMED_INSTALL_REQUEST = "consumed_install_request"
    private const val KERNEL_SERIES_OVERRIDE = "kernel_series_override"
    private const val ALLOW_ABI_MISMATCH = "allow_abi_mismatch"
    private const val ALLOW_TEST_KERNEL = "allow_test_kernel"

    /**
     * 启动时的「已获取 root，是否移交到 Root 管理器」提示是否已经问过。
     *
     * 为什么要持久化：root 检测是**每次启动都跑**的，如果每次都弹，
     * 用户会被同一个框反复打断 —— 那属于噪声，而本工程的原则是"假警报比不报更糟"。
     * 所以：自动提示**只出现一次**；之后想再移交，走主页底部的「移交 root」按钮。
     */
    /**
     * 「5.x 内核支持（beta）」是否已打开。**默认关**。
     *
     * 关着的时候：识别到 5.x 内核**不采用**五系方案，构建被拦住并引导用户来开这里。
     * 为什么要有这道闸：5.x 的布局锚点只有上游 target.h 一条腿，本工程没在 5.x 上
     * 实测过 —— 默认让它参与构建，等于把未验证的偏移当可用产出交付。
     */
    fun allowTestKernel(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ALLOW_TEST_KERNEL, false)

    fun setAllowTestKernel(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ALLOW_TEST_KERNEL, enabled)
            .apply()
    }


    fun accentColor(context: Context): AccentColor = AccentColor.fromStoredValue(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ACCENT_COLOR, null),
    )

    fun setAccentColor(context: Context, color: AccentColor) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ACCENT_COLOR, color.storedValue)
            .apply()
    }

    fun themeMode(context: Context): AppThemeMode = AppThemeMode.fromStoredValue(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(THEME_MODE, null),
    )

    fun setThemeMode(context: Context, themeMode: AppThemeMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(THEME_MODE, themeMode.storedValue)
            .apply()
    }

    fun advancedMode(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ADVANCED_MODE, false)

    fun setAdvancedMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ADVANCED_MODE, enabled)
            .apply()
    }


    fun payloadSource(context: Context): PayloadSource = PayloadSource.fromStoredValue(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(PAYLOAD_SOURCE, null),
    )

    fun setPayloadSource(context: Context, source: PayloadSource) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(PAYLOAD_SOURCE, source.storedValue)
            .apply()
    }

    /**
     * 载荷构建：强制指定的内核系列。
     *
     * 与宿主侧 `--force-series` 同一套语义 —— 与 boot.img 实测不符时**拒绝构建**，
     * 只有下面这个"忽略冲突"开关被打开才放行（且会记入日志）。
     */
    fun kernelSeriesOverride(context: Context): SeriesOverride = SeriesOverride.fromWire(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KERNEL_SERIES_OVERRIDE, null),
    )

    fun setKernelSeriesOverride(context: Context, value: SeriesOverride) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KERNEL_SERIES_OVERRIDE, value.wireValue)
            .apply()
    }

    /** 高级：忽略"强制指定/基线 ABI 与实测冲突"，等价宿主侧 --i-know-what-i-am-doing。 */
    fun allowAbiMismatch(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ALLOW_ABI_MISMATCH, false)

    fun setAllowAbiMismatch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ALLOW_ABI_MISMATCH, enabled)
            .apply()
    }

    fun shizukuMode(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SHIZUKU_MODE, false)

    fun setShizukuMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SHIZUKU_MODE, enabled)
            .apply()
    }

    @Synchronized
    fun consumeInstallRequest(context: Context, requestId: String?): Boolean {
        if (requestId.isNullOrBlank()) return false
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(CONSUMED_INSTALL_REQUEST, null) == requestId) return false
        return preferences.edit()
            .putString(CONSUMED_INSTALL_REQUEST, requestId)
            .commit()
    }

    fun languageTag(context: Context): String {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) "" else locales[0].toLanguageTag()
    }

    fun setLanguage(context: Context, languageTag: String) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags(languageTag)
    }
}
