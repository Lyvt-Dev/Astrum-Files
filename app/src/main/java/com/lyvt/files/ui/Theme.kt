package com.lyvt.files.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyvt.files.core.settings.AppSettings
import com.lyvt.files.core.settings.ThemeMode

/** Selectable accents. Index 0 is Astrum Cosmic Purple. */
val StarlightGold = Color(0xFFE8B95A)
val DeepViolet = Color(0xFF42217D)
val Accents = listOf(
    Color(0xFF7548F8), Color(0xFF2F6BFF), Color(0xFF00A3A3), Color(0xFFE5487A), Color(0xFFE8B95A), Color(0xFF2E9E5B),
)

/** Category palette used across charts and file icons, tuned for both themes. */
object CategoryColors {
    val image = Color(0xFF4C8DFF); val video = Color(0xFFB45CFF); val audio = Color(0xFFFF8A3D)
    val document = Color(0xFF2EB67D); val archive = Color(0xFFE9B10E); val apk = Color(0xFF00B3C7); val other = Color(0xFF8A94A6)
}

data class LyvtExtras(val gradientStart: Color, val gradientEnd: Color, val subtle: Color, val reduceMotion: Boolean)
val LocalLyvt = staticCompositionLocalOf { LyvtExtras(Color(0xFFE8F0FF), Color(0xFFF7F9FC), Color(0xFF8A94A6), false) }

private fun light(accent: Color) = lightColorScheme(
    primary = accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E1FF), onPrimaryContainer = Color(0xFF22105A),
    secondary = Color(0xFF4F5D78), secondaryContainer = Color(0xFFE6ECF7), onSecondaryContainer = Color(0xFF1B2537),
    tertiary = Color(0xFFB8862F),
    background = Color(0xFFF8F9FC), onBackground = Color(0xFF0E1320),
    surface = Color(0xFFF8F9FC), onSurface = Color(0xFF12111A),
    surfaceVariant = Color(0xFFEFEDF7), onSurfaceVariant = Color(0xFF5E5A70),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF2F0F9), surfaceContainerHigh = Color(0xFFECE9F5), surfaceContainerHighest = Color(0xFFE4E0F0),
    outline = Color(0xFFD6D2E3), outlineVariant = Color(0xFFE8E5F1),
    error = Color(0xFFD93A3A),
)

private fun dark(accent: Color) = darkColorScheme(
    primary = Color(
        red = (accent.red * 0.7f + 0.3f), green = (accent.green * 0.7f + 0.3f), blue = (accent.blue * 0.7f + 0.3f)
    ), onPrimary = Color(0xFF071634),
    primaryContainer = Color(0xFF42217D), onPrimaryContainer = Color(0xFFE9E1FF),
    secondary = Color(0xFFC4B7E8), secondaryContainer = Color(0xFF2A2440), onSecondaryContainer = Color(0xFFE6DFF8),
    tertiary = Color(0xFFE8B95A),
    background = Color(0xFF101018), onBackground = Color(0xFFECEAF4),
    surface = Color(0xFF101018), onSurface = Color(0xFFECEAF4),
    surfaceVariant = Color(0xFF1E1C2A), onSurfaceVariant = Color(0xFFA29DB8),
    surfaceContainerLowest = Color(0xFF0B0B11), surfaceContainerLow = Color(0xFF17161F),
    surfaceContainer = Color(0xFF1C1A26), surfaceContainerHigh = Color(0xFF24212F), surfaceContainerHighest = Color(0xFF2D2A3A),
    outline = Color(0xFF3A3650), outlineVariant = Color(0xFF292637),
    error = Color(0xFFFF7A7A),
)

private val Sans = FontFamily.SansSerif

private val LyvtTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.3.sp),
)

private val LyvtShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun LyvtTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val accent = Accents.getOrElse(settings.accent) { Accents[0] }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> dark(accent)
        else -> light(accent)
    }
    val extras = if (dark) LyvtExtras(Color(0xFF1E1538), Color(0xFF101018), Color(0xFF6E6A85), settings.reduceMotion)
    else LyvtExtras(Color(0xFFEDE6FF), Color(0xFFF8F9FC), Color(0xFF8A86A0), settings.reduceMotion)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalLyvt provides extras,
        LocalDensity provides Density(density.density, density.fontScale * settings.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = LyvtTypography, shapes = LyvtShapes, content = content)
    }
}
