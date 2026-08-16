package com.glassify.launcher.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The interactive widgets, as glass.
 *
 * These replace Material's Switch and filled buttons everywhere in the app. Two
 * reasons beyond looks. First, a single opaque green toggle in the middle of a
 * glass panel breaks the material story more than any other element on screen —
 * controls are what the eye lands on. Second, the design is about to be
 * calibrated against a reference file, and that only works if every control
 * reads its numbers from one place instead of five ad-hoc composables.
 *
 * Everything here draws with [liquidGlass], so the controls inherit the same
 * Fresnel rim, specular, grain and fallback ladder as the panels — including
 * degrading safely if the shader is ever rejected.
 */

/**
 * An iOS-proportioned toggle on a glass track.
 *
 * The on-state is a translucent tinted glass rather than an opaque fill: the
 * track keeps refracting what is behind it, and the state is carried by the
 * tint and the thumb position together.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    val trackTint by animateColorAsState(
        targetValue = if (checked) colors.positive else colors.glassTint,
        animationSpec = GlassMotion.snappy(),
        label = "switch-tint",
    )

    // The thumb travels in layout terms, not raw offset, so RTL comes free.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val travel by animateDpAsState(
        targetValue = when {
            checked != rtl -> TRACK_WIDTH - THUMB_SIZE - TRACK_PADDING * 2
            else -> 0.dp
        },
        animationSpec = GlassMotion.gooey(),
        label = "switch-thumb",
    )

    Box(
        modifier
            .size(width = TRACK_WIDTH, height = TRACK_HEIGHT)
            .liquidGlass(
                shape = GlassShapes.pill,
                cornerRadius = TRACK_HEIGHT / 2,
                spec = GlassSpec(
                    thickness = 8.dp,
                    specular = 0.5f,
                    surfaceAlpha = if (checked) 0.42f else 0.14f,
                    elevation = 0.dp,
                    tint = trackTint,
                ),
            )
            .pointerInput(checked) { detectTapGestures { onChange(!checked) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = TRACK_PADDING + travel)
                .size(THUMB_SIZE)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** A pill action button in translucent tinted glass — never an opaque fill. */
@Composable
fun GlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = GlassTheme.colors.accent,
    compact: Boolean = true,
) {
    Box(
        modifier
            .liquidGlass(
                shape = GlassShapes.pill,
                cornerRadius = 999.dp,
                spec = GlassSpec(
                    thickness = 10.dp,
                    specular = 0.55f,
                    // Enough body to read as an action, still translucent
                    // enough to stay in the material.
                    surfaceAlpha = 0.38f,
                    elevation = 4.dp,
                    tint = tint,
                ),
            )
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(
                horizontal = if (compact) 16.dp else 24.dp,
                vertical = if (compact) 8.dp else 13.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (compact) GlassTheme.type.callout else GlassTheme.type.headline,
            color = Color.White,
        )
    }
}

/** A +/− stepper button as a small glass disc. */
@Composable
fun GlassStepperButton(glyph: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(30.dp)
            .liquidGlass(
                shape = GlassShapes.pill,
                cornerRadius = 15.dp,
                spec = GlassSpec(
                    thickness = 7.dp,
                    specular = 0.5f,
                    surfaceAlpha = 0.20f,
                    elevation = 0.dp,
                ),
            )
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = GlassTheme.type.headline, color = Color.White)
    }
}

private val TRACK_WIDTH = 52.dp
private val TRACK_HEIGHT = 32.dp
private val THUMB_SIZE = 26.dp
private val TRACK_PADDING = 3.dp
