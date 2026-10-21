package dev.zed.mobile

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zeddesktop.mobile.R

/**
 * Quiet neutrals carry the layout and hairlines separate content; actions are black. Purple is
 * reserved for the mascot and the thinking animation, so it always means "Zeb is here or busy".
 */
internal object ZedColors {
    val Background = Color(0xFFFAFAFA)
    val Surface = Color(0xFFFFFFFF)
    val Subtle = Color(0xFFF3F3F5)
    val Hairline = Color(0xFFE8E7EC)
    val Text = Color(0xFF18171C)
    val Muted = Color(0xFF6B6A73)
    val Faint = Color(0xFF9F9EA7)
    val Accent = Color(0xFF18171C)
    val AccentPressed = Color(0xFF000000)
    val AccentSoft = Color(0xFFF3F3F5)
    val AccentText = Color(0xFF18171C)
    val Brand = Color(0xFF6E56CF)
    val BrandDark = Color(0xFF5B45B8)
    val BrandLight = Color(0xFF9E8CF0)
    val Success = Color(0xFF218358)
    val SuccessSoft = Color(0xFFE6F6EB)
    val Danger = Color(0xFFCE2C31)
    val DangerSoft = Color(0xFFFEEBEC)
    val Warning = Color(0xFFAB6400)
    val WarningSoft = Color(0xFFFFF4D5)
    val AddedBackground = Color(0xFFE9F6EE)
    val RemovedBackground = Color(0xFFFDECEC)
}

// Inter and JetBrains Mono ship as variable fonts; each weight selects its axis value.
@OptIn(ExperimentalTextApi::class)
private fun interWeight(weight: Int) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun monoWeight(weight: Int) = Font(
    R.font.jetbrains_mono,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

internal val InterFamily = FontFamily(interWeight(400), interWeight(500), interWeight(600), interWeight(700))
internal val MonoFamily = FontFamily(monoWeight(400), monoWeight(500), monoWeight(700))

@Composable
internal fun ZedTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = ZedColors.Accent,
        onPrimary = Color.White,
        primaryContainer = ZedColors.AccentSoft,
        onPrimaryContainer = ZedColors.AccentText,
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
        errorContainer = ZedColors.DangerSoft,
        onErrorContainer = ZedColors.Danger,
        outline = ZedColors.Hairline,
        outlineVariant = ZedColors.Hairline,
    )
    fun style(size: Int, line: Int, weight: Int = 400, tracking: Double = 0.0) = TextStyle(
        fontFamily = InterFamily,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = FontWeight(weight),
        letterSpacing = tracking.sp,
    )
    val typography = Typography(
        displaySmall = style(30, 36, 600, -0.6),
        headlineMedium = style(26, 32, 600, -0.5),
        headlineSmall = style(22, 28, 600, -0.4),
        titleLarge = style(18, 24, 600, -0.25),
        titleMedium = style(16, 22, 500, -0.15),
        titleSmall = style(14, 20, 600, -0.05),
        bodyLarge = style(16, 24, 400, -0.1),
        bodyMedium = style(14, 20, 400, -0.05),
        bodySmall = style(13, 18, 400),
        labelLarge = style(15, 20, 500, -0.1),
        labelMedium = style(13, 18, 500),
        labelSmall = style(11, 14, 500, 0.2),
    )
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

internal val CodeTextStyle = TextStyle(
    fontFamily = MonoFamily,
    fontSize = 12.5.sp,
    lineHeight = 19.sp,
    color = ZedColors.Text,
)
