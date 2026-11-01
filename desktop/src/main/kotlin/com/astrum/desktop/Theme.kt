package com.astrum.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyvt.files.core.storage.FileCategory

val Accents = listOf(Color(0xFF7548F8), Color(0xFF2F6BFF), Color(0xFF00A3A3), Color(0xFFE5487A), Color(0xFFE8B95A), Color(0xFF2E9E5B))
val Gold = Color(0xFFE8B95A)

fun FileCategory.color(): Color = when (this) {
    FileCategory.IMAGE -> Color(0xFF4C8DFF); FileCategory.VIDEO -> Color(0xFFB45CFF); FileCategory.AUDIO -> Color(0xFFFF8A3D)
    FileCategory.DOCUMENT -> Color(0xFF2EB67D); FileCategory.ARCHIVE -> Color(0xFFE9B10E); FileCategory.APK -> Color(0xFF00B3C7)
    FileCategory.OTHER -> Color(0xFF8A94A6)
}

private fun light(a: Color) = lightColorScheme(
    primary = a, onPrimary = Color.White, primaryContainer = Color(0xFFE9E1FF), onPrimaryContainer = Color(0xFF22105A),
    secondary = Color(0xFF4F5D78), secondaryContainer = Color(0xFFECE9F5), tertiary = Color(0xFFB8862F),
    background = Color(0xFFF8F9FC), onBackground = Color(0xFF12111A), surface = Color(0xFFF8F9FC), onSurface = Color(0xFF12111A),
    surfaceVariant = Color(0xFFEFEDF7), onSurfaceVariant = Color(0xFF5E5A70), surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF2F0F9), surfaceContainerHigh = Color(0xFFECE9F5), surfaceContainerHighest = Color(0xFFE4E0F0),
    outline = Color(0xFFD6D2E3), outlineVariant = Color(0xFFE8E5F1), error = Color(0xFFD93A3A),
)

private fun dark(a: Color) = darkColorScheme(
    primary = Color(a.red * 0.7f + 0.3f, a.green * 0.7f + 0.3f, a.blue * 0.7f + 0.3f), onPrimary = Color(0xFF12082E),
    primaryContainer = Color(0xFF42217D), onPrimaryContainer = Color(0xFFE9E1FF), secondary = Color(0xFFC4B7E8), secondaryContainer = Color(0xFF2A2440),
    tertiary = Gold, background = Color(0xFF101018), onBackground = Color(0xFFECEAF4), surface = Color(0xFF101018), onSurface = Color(0xFFECEAF4),
    surfaceVariant = Color(0xFF1E1C2A), onSurfaceVariant = Color(0xFFA29DB8), surfaceContainerLowest = Color(0xFF0B0B11), surfaceContainerLow = Color(0xFF17161F),
    surfaceContainer = Color(0xFF1C1A26), surfaceContainerHigh = Color(0xFF24212F), surfaceContainerHighest = Color(0xFF2D2A3A),
    outline = Color(0xFF3A3650), outlineVariant = Color(0xFF292637), error = Color(0xFFFF7A7A),
)

private val type = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp), bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp), labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun AstrumTheme(dark: Boolean, accent: Int, content: @Composable () -> Unit) {
    val a = Accents.getOrElse(accent) { Accents[0] }
    MaterialTheme(colorScheme = if (dark) dark(a) else light(a), typography = type,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)), content = content)
}
