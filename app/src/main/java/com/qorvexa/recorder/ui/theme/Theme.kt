package com.qorvexa.recorder.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.qorvexa.recorder.core.model.ThemeMode

object QxColors {
    val Graphite = Color(0xFF0B0B12)
    val Midnight = Color(0xFF05050A)
    val Surface = Color(0xFF14121E)
    val SurfaceHigh = Color(0xFF1C1A2A)
    val Outline = Color(0xFF2A2740)
    val Purple = Color(0xFF8B3DFF)
    val PurpleSoft = Color(0xFFB48BFF)
    val Cyan = Color(0xFF22E4FF)
    val Blue = Color(0xFF3D7BFF)
    val Red = Color(0xFFFF2D46)
    val Green = Color(0xFF2EE59D)
    val Amber = Color(0xFFFFC23D)
    val Text = Color(0xFFFFFFFF)
    val TextSoft = Color(0xFFB8B5C9)
    val TextDim = Color(0xFF7D7A90)
}

/** Brand colors that don't map onto Material roles. */
@Immutable
data class QxExtra(
    val cyan: Color,
    val red: Color,
    val green: Color,
    val amber: Color,
    val card: Color,
    val cardBorder: Color,
    val textSoft: Color,
    val isDark: Boolean,
)

val LocalQx = staticCompositionLocalOf {
    QxExtra(QxColors.Cyan, QxColors.Red, QxColors.Green, QxColors.Amber, QxColors.Surface, QxColors.Outline, QxColors.TextSoft, true)
}

private val Dark = darkColorScheme(
    primary = QxColors.Purple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A1758),
    onPrimaryContainer = QxColors.PurpleSoft,
    secondary = QxColors.Cyan,
    onSecondary = Color.Black,
    tertiary = QxColors.Blue,
    background = QxColors.Graphite,
    onBackground = QxColors.Text,
    surface = QxColors.Graphite,
    onSurface = QxColors.Text,
    surfaceVariant = QxColors.SurfaceHigh,
    onSurfaceVariant = QxColors.TextSoft,
    surfaceContainer = QxColors.Surface,
    surfaceContainerHigh = QxColors.SurfaceHigh,
    surfaceContainerLow = QxColors.Midnight,
    outline = QxColors.Outline,
    outlineVariant = QxColors.Outline,
    error = QxColors.Red,
)

private val Light = lightColorScheme(
    primary = Color(0xFF6A1FE6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF25005A),
    secondary = Color(0xFF0091A8),
    tertiary = Color(0xFF2357D9),
    background = Color(0xFFF6F5FB),
    onBackground = Color(0xFF14121E),
    surface = Color(0xFFF6F5FB),
    onSurface = Color(0xFF14121E),
    surfaceVariant = Color(0xFFE9E6F3),
    onSurfaceVariant = Color(0xFF4A465C),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0EEF7),
    outline = Color(0xFFD5D1E3),
    outlineVariant = Color(0xFFD5D1E3),
    error = Color(0xFFD7193A),
)

private val Type = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val BrandTitle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 4.sp)
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)

@Composable
fun QorvexaTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val extra = if (dark) {
        QxExtra(QxColors.Cyan, QxColors.Red, QxColors.Green, QxColors.Amber, QxColors.Surface, QxColors.Outline, QxColors.TextSoft, true)
    } else {
        QxExtra(Color(0xFF0091A8), Color(0xFFD7193A), Color(0xFF0E9F6E), Color(0xFFB7791F), Color.White, Color(0xFFE2DEEE), Color(0xFF5B5770), false)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalQx provides extra) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
    }
}
