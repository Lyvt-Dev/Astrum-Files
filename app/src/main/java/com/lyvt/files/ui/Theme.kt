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

/** Selectable accents. Index 0 is the signature Lyvt ice blue. */
val Accents = listOf(
    Color(0xFF2F6BFF), Color(0xFF00A3A3), Color(0xFF7A5CFF), Color(0xFFE5487A), Color(0xFFF08A24), Color(0xFF2E9E5B),
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
    primaryContainer = Color(0xFFDDE7FF), onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF4F5D78), secondaryContainer = Color(0xFFE6ECF7), onSecondaryContainer = Color(0xFF1B2537),
    tertiary = Color(0xFF00A3A3),
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF0E1320),
    surface = Color(0xFFF7F9FC), onSurface = Color(0xFF0E1320),
    surfaceVariant = Color(0xFFEDF1F8), onSurfaceVariant = Color(0xFF5A6478),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1F4FA), surfaceContainerHigh = Color(0xFFEAEFF7), surfaceContainerHighest = Color(0xFFE3E9F3),
    outline = Color(0xFFD3DAE6), outlineVariant = Color(0xFFE6EBF3),
    error = Color(0xFFD93A3A),
)

private fun dark(accent: Color) = darkColorScheme(
    primary = Color(
        red = (accent.red * 0.7f + 0.3f), green = (accent.green * 0.7f + 0.3f), blue = (accent.blue * 0.7f + 0.3f)
    ), onPrimary = Color(0xFF071634),
    primaryContainer = Color(0xFF1C2D52), onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = Color(0xFFAFBBD3), secondaryContainer = Color(0xFF263042), onSecondaryContainer = Color(0xFFDCE3F1),
    tertiary = Color(0xFF5ED6D6),
    background = Color(0xFF0F1217), onBackground = Color(0xFFE5E9F0),
    surface = Color(0xFF0F1217), onSurface = Color(0xFFE5E9F0),
    surfaceVariant = Color(0xFF1D232D), onSurfaceVariant = Color(0xFF9AA5B8),
    surfaceContainerLowest = Color(0xFF0B0D11), surfaceContainerLow = Color(0xFF161A21),
    surfaceContainer = Color(0xFF1A1F27), surfaceContainerHigh = Color(0xFF212733), surfaceContainerHighest = Color(0xFF2A313E),
    outline = Color(0xFF353D4B), outlineVariant = Color(0xFF262C37),
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
    val extras = if (dark) LyvtExtras(Color(0xFF16233D), Color(0xFF0F1217), Color(0xFF6E7A90), settings.reduceMotion)
    else LyvtExtras(Color(0xFFE3ECFF), Color(0xFFF7F9FC), Color(0xFF8A94A6), settings.reduceMotion)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalLyvt provides extras,
        LocalDensity provides Density(density.density, density.fontScale * settings.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = LyvtTypography, shapes = LyvtShapes, content = content)
    }
}
