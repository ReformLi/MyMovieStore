package com.hpu.mymoviestore.presentation.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题管理：浅色 / 深色模式切换与持久化。
 *
 * - 持久化：SharedPreferences("app_settings") 的 "theme_mode"
 * - 应用模式：[AppCompatDelegate.setDefaultNightMode]，Activity 会自动重建并以新配色渲染
 * - 默认深色（App 原生即为深色主题，首次启动保持原观感）
 */
object ThemeManager {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    const val MODE_LIGHT = 0
    const val MODE_DARK = 1

    fun isLightMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME_MODE, MODE_DARK) == MODE_LIGHT
    }

    /** 启动时应用持久化的主题（需在任何 Activity 创建前调用，如 Application.onCreate） */
    fun applySaved(context: Context) {
        val mode = if (isLightMode(context)) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** 切换主题并持久化；返回切换后是否为浅色模式 */
    fun toggle(context: Context): Boolean {
        val newLight = !isLightMode(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_THEME_MODE, if (newLight) MODE_LIGHT else MODE_DARK).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (newLight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
        return newLight
    }
}
