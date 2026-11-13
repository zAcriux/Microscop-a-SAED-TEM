/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Lang { SYSTEM, ES, EN }

private val Dark = darkColorScheme(
    primary = Color(0xFF5EC8F2), onPrimary = Color(0xFF00344A), primaryContainer = Color(0xFF0E4C69),
    secondary = Color(0xFFFFD54F), onSecondary = Color(0xFF3B2F00), tertiary = Color(0xFF8BD8A0),
    background = Color(0xFF101418), surface = Color(0xFF14191E), surfaceVariant = Color(0xFF26303A),
)
private val Light = lightColorScheme(
    primary = Color(0xFF00658A), primaryContainer = Color(0xFFC4E7FF), secondary = Color(0xFF7A5900), tertiary = Color(0xFF2F6B44),
)

@Composable
fun TemLabTheme(mode: ThemeMode, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Lightweight bilingual text helper: every UI label is written in both languages next to each other. */
class Tr(val spanish: Boolean) {
    operator fun invoke(en: String, es: String) = if (spanish) es else en
}

val LocalTr = compositionLocalOf { Tr(true) }
