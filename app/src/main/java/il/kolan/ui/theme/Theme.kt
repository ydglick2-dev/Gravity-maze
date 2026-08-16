package il.kolan.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// The palette: near-black ground with a violet-to-cyan neon accent.
val KolanBackground = Color(0xFF0A0A0F)
val KolanSurface = Color(0xFF13131C)
val KolanSurfaceElevated = Color(0xFF1B1B27)
val KolanViolet = Color(0xFF8B5CF6)
val KolanCyan = Color(0xFF22D3EE)
val KolanTextPrimary = Color(0xFFF4F4F8)
val KolanTextSecondary = Color(0xFF9C9CB0)
val KolanDanger = Color(0xFFF87171)
val KolanWarning = Color(0xFFFBBF24)
val KolanSuccess = Color(0xFF34D399)

/** The signature gradient. Angled rather than horizontal so it survives RTL mirroring. */
val KolanAccentGradient = Brush.linearGradient(
    colors = listOf(KolanViolet, KolanCyan),
    start = Offset(0f, 0f),
    end = Offset(600f, 600f),
)

val KolanAccentGradientSoft = Brush.linearGradient(
    colors = listOf(KolanViolet.copy(alpha = 0.22f), KolanCyan.copy(alpha = 0.22f)),
    start = Offset(0f, 0f),
    end = Offset(600f, 600f),
)

private val KolanColorScheme = darkColorScheme(
    primary = KolanViolet,
    onPrimary = Color.White,
    secondary = KolanCyan,
    onSecondary = Color(0xFF04121A),
    background = KolanBackground,
    onBackground = KolanTextPrimary,
    surface = KolanSurface,
    onSurface = KolanTextPrimary,
    surfaceVariant = KolanSurfaceElevated,
    onSurfaceVariant = KolanTextSecondary,
    error = KolanDanger,
    outline = Color(0xFF2E2E3E),
)

private val KolanTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/**
 * Wraps the app in the dark palette and forces right-to-left layout.
 *
 * The direction is pinned rather than inherited: the entire interface is Hebrew, so it should
 * read right-to-left even on a device set to English.
 */
@Composable
fun KolanTheme(content: @Composable () -> Unit) {
    val view = LocalContext.current
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()

    val activity = view as? Activity
    if (activity != null) {
        SideEffect {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars = false
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = KolanColorScheme,
            typography = KolanTypography,
            content = content,
        )
    }
}
