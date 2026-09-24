package com.ting.root

import android.app.LocaleManager
import android.content.Context
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
    private const val LOG_DETAILED = "log_detailed"
    private const val PAYLOAD_SOURCE = "payload_source"
    private const val CONSUMED_INSTALL_REQUEST = "consumed_install_request"

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

    /**
     * 提权页日志是否显示详细模式。
     *
     * `true`（默认）→ 逐条显示翻译后的语义日志；
     * `false`        → 精简模式，只显示里程碑，CFI 之后固定显示「正在提升权限至 root」。
     */
    fun logDetailed(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(LOG_DETAILED, true)

    fun setLogDetailed(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(LOG_DETAILED, enabled)
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
