package com.watermark.photo.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * v1.3.4: 主题与语言设置管理
 * - 主题：跟随系统 / 浅色 / 深色，通过 AppCompatDelegate.setDefaultNightMode 应用
 * - 语言：跟随系统 / 英文 / 简体中文，通过 AppCompat 自带 setApplicationLocales 应用
 */
object SettingsPrefs {
    private const val PREF = "moto_wm_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANG = "lang_tag"

    // 主题模式值：与 AppCompatDelegate 常量一致
    const val THEME_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    const val THEME_LIGHT = AppCompatDelegate.MODE_NIGHT_NO
    const val THEME_DARK = AppCompatDelegate.MODE_NIGHT_YES

    // 语言标签：""=跟随系统
    const val LANG_SYSTEM = ""
    const val LANG_EN = "en"
    const val LANG_ZH = "zh-CN"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getTheme(context: Context): Int =
        prefs(context).getInt(KEY_THEME, THEME_SYSTEM)

    fun getLang(context: Context): String =
        prefs(context).getString(KEY_LANG, LANG_SYSTEM) ?: LANG_SYSTEM

    fun setTheme(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun setLang(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LANG, tag).apply()
        val locales = if (tag == LANG_SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** 在 Application 启动时恢复已保存的设置（避免重启后丢失） */
    fun applySaved(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getTheme(context))
        val tag = getLang(context)
        val locales = if (tag == LANG_SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
