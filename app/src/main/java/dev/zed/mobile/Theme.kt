package dev.zed.mobile

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A deliberately quiet palette: warm neutrals carry the layout, hairlines separate content, and
 * the accent is reserved for the send action and small status marks.
 */
internal object ZedColors {
    val Background = Color(0xFFFAF9F7)
    val Surface = Color(0xFFFFFFFF)
    val Subtle = Color(0xFFF2F1EE)
    val Hairline = Color(0xFFE6E4E0)
    val Text = Color(0xFF1F1E1C)
    val Muted = Color(0xFF6B6863)
    val Faint = Color(0xFF9C9890)
    val Accent = Color(0xFFC2603E)
    val Success = Color(0xFF2E7D4F)
    val Danger = Color(0xFFB42318)
    val Warning = Color(0xFFB26A00)
    val AddedBackground = Color(0xFFEAF5EE)
    val RemovedBackground = Color(0xFFFCECEA)
}

@Composable
internal fun ZedTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = ZedColors.Text,
        onPrimary = Color.White,
        primaryContainer = ZedColors.Subtle,
        onPrimaryContainer = ZedColors.Text,
        secondary = ZedColors.Muted,
        onSecondary = Color.White,
        secondaryContainer = ZedColors.Subtle,
        onSecondaryContainer = ZedColors.Text,
        tertiary = ZedColors.Accent,
        onTertiary = Color.White,
        background = ZedColors.Background,
        onBackground = ZedColors.Text,
        surface = ZedColors.Background,
        onSurface = ZedColors.Text,
        surfaceVariant = ZedColors.Subtle,
        onSurfaceVariant = ZedColors.Muted,
        surfaceContainerLowest = ZedColors.Surface,
        surfaceContainerLow = ZedColors.Surface,
        surfaceContainer = ZedColors.Surface,
        surfaceContainerHigh = ZedColors.Surface,
        surfaceContainerHighest = ZedColors.Subtle,
        surfaceTint = Color.Transparent,
        error = ZedColors.Danger,
        onError = Color.White,
        errorContainer = ZedColors.RemovedBackground,
        onErrorContainer = ZedColors.Danger,
        outline = ZedColors.Hairline,
        outlineVariant = ZedColors.Hairline,
    )
    val base = Typography()
    val typography = base.copy(
        headlineSmall = base.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    )
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}

internal val CodeTextStyle = TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 12.5.sp,
    lineHeight = 19.sp,
    color = ZedColors.Text,
)
