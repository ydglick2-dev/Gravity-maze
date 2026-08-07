@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.glassify.launcher.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassify.launcher.R

/**
 * Type.
 *
 * Apple's SF Pro is licensed for use on Apple platforms only and cannot ship
 * inside an APK, so this uses Inter — a grotesque cut close enough to SF that
 * the difference does not register at UI sizes — with Heebo carrying Hebrew,
 * which Inter does not cover. Both are SIL Open Font License.
 *
 * The families are declared as one fallback chain per weight so a mixed
 * Hebrew/Latin string (a notification title, an app name) renders in a
 * consistent weight instead of falling back to the system font mid-line.
 */
val GlassFontFamily: FontFamily = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.inter_variable, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.heebo_variable, weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.heebo_variable, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.heebo_variable, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.heebo_variable, weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.heebo_variable, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/**
 * The iOS type ramp. Sizes and the negative tracking are what make text read as
 * iOS — Material's defaults are a step larger and tracked positively.
 */
private fun glassText(weight: FontWeight, size: TextUnit, tracking: TextUnit) = TextStyle(
    fontFamily = GlassFontFamily,
    fontWeight = weight,
    fontSize = size,
    letterSpacing = tracking,
)

@Immutable
class GlassTypography {
    val largeTitle = glassText(FontWeight.Bold, 34.sp, (-0.4).sp)
    val title = glassText(FontWeight.SemiBold, 22.sp, (-0.3).sp)
    val headline = glassText(FontWeight.SemiBold, 17.sp, (-0.2).sp)
    val body = glassText(FontWeight.Normal, 17.sp, (-0.2).sp)
    val callout = glassText(FontWeight.Normal, 15.sp, (-0.1).sp)
    val footnote = glassText(FontWeight.Normal, 13.sp, 0.sp)
    val caption = glassText(FontWeight.Medium, 11.sp, 0.1.sp)

    /** Home screen icon labels: tiny, tight and shadowed against the wallpaper. */
    val iconLabel = glassText(FontWeight.Medium, 11.5.sp, 0.sp)

    /** The oversized clock on the notification panel header. */
    val clock = glassText(FontWeight.Light, 56.sp, (-1.5).sp)
}

@Immutable
class GlassColors(val dark: Boolean) {
    /** Text on top of wallpaper or glass. */
    val onGlass = if (dark) Color.White else Color(0xFF0B0B0F)
    val onGlassSecondary = onGlass.copy(alpha = 0.62f)
    val onGlassTertiary = onGlass.copy(alpha = 0.38f)

    /** The tint mixed into sampled backdrop pixels. */
    val glassTint = if (dark) Color(0xFF10131A) else Color(0xFFF2F4F8)

    /** Opaque-ish surfaces that are not glass: folder plates, search field fill. */
    val fill = if (dark) Color(0x33FFFFFF) else Color(0x2E1B1C22)
    val separator = if (dark) Color(0x1FFFFFFF) else Color(0x1A000000)

    /** iOS system accents, used for switches, badges and the Island's live dot. */
    val accent = Color(0xFF0A84FF)
    val destructive = Color(0xFFFF453A)
    val positive = Color(0xFF30D158)
    val warning = Color(0xFFFF9F0A)
}

/**
 * Motion.
 *
 * iOS animates with springs, not curves, and the difference is most of why its
 * UI feels "alive". Three springs cover almost everything here: [snappy] for
 * taps and toggles, [fluid] for panels and page changes, and [gooey] for the
 * Dynamic Island morphing between shapes, where the overshoot is the point.
 */
object GlassMotion {
    fun <T> snappy() = spring<T>(dampingRatio = 0.86f, stiffness = 480f)
    fun <T> fluid() = spring<T>(dampingRatio = 0.92f, stiffness = 260f)
    fun <T> gooey() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 320f)
}

/** Corner radii, kept in one place so panels stay in the same family as icons. */
object GlassShapes {
    val icon = SquircleFractionShape(fraction = 0.2237f)
    val card = SquircleShape(radius = 22.dp)
    val panel = SquircleShape(radius = 34.dp)
    val pill = SquircleShape(radius = 999.dp)
    val dock = SquircleShape(radius = 32.dp)
}

val LocalGlassColors = staticCompositionLocalOf { GlassColors(dark = true) }
val LocalGlassTypography = staticCompositionLocalOf { GlassTypography() }

object GlassTheme {
    val colors: GlassColors
        @Composable @ReadOnlyComposable get() = LocalGlassColors.current
    val type: GlassTypography
        @Composable @ReadOnlyComposable get() = LocalGlassTypography.current
}

@Composable
fun GlassTheme(
    dark: Boolean = isSystemInDarkTheme(),
    tier: GlassTier? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resolvedTier = tier ?: GlassTier.deviceMax(context)
    val blurAvailable = GlassTier.crossWindowBlurEnabled(context)
    CompositionLocalProvider(
        LocalGlassColors provides GlassColors(dark),
        LocalGlassTypography provides GlassTypography(),
        LocalGlassTier provides resolvedTier,
        LocalBlurAvailable provides blurAvailable,
        content = content,
    )
}
