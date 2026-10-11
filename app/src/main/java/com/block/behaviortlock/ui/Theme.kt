package com.block.behaviortlock.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Theme.kt — one place for colours and typography.
//
// The palette is fixed rather than taken from the user's wallpaper (Material 3
// dynamic colour). Two reasons:
//
//   1. Android paints the window background from themes.xml before Compose
//      starts, and that value cannot be derived from a wallpaper. With dynamic
//      colour the app visibly jumps colour on launch.
//   2. The app has to look the same on every phone it is demonstrated on.
//
// If you do want dynamic colour back, set DYNAMIC_COLOR = true and change
// app_window_background in res/values/colors.xml and res/values-night/colors.xml
// to the closest match of your wallpaper.
// ---------------------------------------------------------------------------

private const val DYNAMIC_COLOR = false

private val BrandBlue = Color(0xFF38BDF8)
private val BrandBlueDark = Color(0xFF0C4A6E)
private val BrandAmber = Color(0xFFFBBF24)

private val LightColors = lightColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EEFB),
    onPrimaryContainer = BrandBlueDark,
    secondary = BrandBlue,
    tertiary = BrandAmber,
    background = Color(0xFFF8FAFC),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF10344A),
    onPrimaryContainer = Color(0xFF9BDCFB),
    secondary = Color(0xFF7DD3FC),
    tertiary = BrandAmber,
    background = Color(0xFF0B1220),
    surface = Color(0xFF131C2B)
)

// Every style pins the sans-serif family. Without this some OEM skins (MIUI
// among them) substitute a serif face and the typography stops looking designed.
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif),
    bodyLarge = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.SansSerif),
    bodyMedium = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.SansSerif),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
)

@Composable
fun BlockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        DYNAMIC_COLOR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

/** Shared animation durations so motion feels consistent across the app. */
object Motion {
    const val FAST = 180
    const val NORMAL = 260
    const val SLOW = 400
}