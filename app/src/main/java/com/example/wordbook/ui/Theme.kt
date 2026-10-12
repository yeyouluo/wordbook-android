package com.example.wordbook.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** 发音小喇叭的固定蓝色 */
val SpeakBlue = Color(0xFF1976D2)

/**
 * 主题模式（P3）：system / light / dark，设置页可手动覆盖，立即生效并持久化。
 * 用可观察状态承载，切换时无需重建 Activity。
 */
object ThemePrefs {

    const val K_MODE = "theme_mode"
    private const val PREFS = "settings"

    var mode by mutableStateOf("system")
        private set

    fun load(context: Context) {
        mode = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_MODE, "system") ?: "system"
    }

    fun set(context: Context, value: String) {
        val safe = if (value in listOf("system", "light", "dark")) value else "system"
        mode = safe
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_MODE, safe).apply()
    }
}

/** 深色模式：默认跟随系统，设置页可强制浅色/深色 */
@Composable
fun appColorScheme() = when (ThemePrefs.mode) {
    "light" -> lightColorScheme()
    "dark" -> darkColorScheme()
    else -> if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
}
