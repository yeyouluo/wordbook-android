package com.example.wordbook.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 发音小喇叭的固定蓝色 */
val SpeakBlue = Color(0xFF1976D2)

/** 深色模式自动跟随系统 */
@Composable
fun appColorScheme() =
    if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
