package il.kolan.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import il.kolan.ui.theme.KolanAccentGradient
import il.kolan.ui.theme.KolanAccentGradientSoft
import il.kolan.ui.theme.KolanCyan
import il.kolan.ui.theme.KolanSurface
import il.kolan.ui.theme.KolanSurfaceElevated
import il.kolan.ui.theme.KolanTextPrimary
import il.kolan.ui.theme.KolanTextSecondary
import il.kolan.ui.theme.KolanViolet

/** Large preset tile with an icon, a name and a press-scale animation. */
@Composable
fun PresetCard(
    name: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    isCustom: Boolean,
    customBadge: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "presetCardScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) KolanCyan else Color(0xFF2E2E3E),
        label = "presetCardBorder",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .then(
                if (selected) Modifier.background(KolanAccentGradientSoft)
                else Modifier.background(KolanSurface),
            )
            .border(BorderStroke(if (selected) 2.dp else 1.dp, borderColor), RoundedCornerShape(22.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(KolanAccentGradient)
                    else Modifier.background(KolanSurfaceElevated),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else KolanViolet,
                modifier = Modifier.size(24.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = KolanTextPrimary,
            )
            if (isCustom) {
                Text(
                    text = customBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = KolanCyan,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(KolanCyan.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = KolanTextSecondary,
        )
    }
}

/**
 * Finger-sized slider with the value shown alongside its label.
 *
 * The track is taller than the Material default because these are dragged one-handed while
 * listening, often without looking directly at the control.
 */
@Composable
fun BigSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    steps: Int = 0,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = KolanTextPrimary)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = KolanCyan)
        }

        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = KolanTextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = KolanCyan,
                activeTrackColor = KolanViolet,
                inactiveTrackColor = KolanSurfaceElevated,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        )
    }
}

/** Pill showing the measured round-trip latency. */
@Composable
fun LatencyBadge(
    latencyMs: Float,
    label: String,
    /** Format string with a single %d placeholder, already resolved from resources. */
    template: String,
    modifier: Modifier = Modifier,
) {
    val rounded = latencyMs.toInt().coerceAtLeast(0)
    // Under 40 ms feels immediate, under 70 ms is usable, beyond that the delay is distracting.
    val colour = when {
        rounded <= 40 -> KolanCyan
        rounded <= 70 -> Color(0xFFFBBF24)
        else -> Color(0xFFF87171)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(KolanSurface)
            .border(1.dp, colour.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "$label $rounded" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(colour),
        )
        Text(
            text = template.format(rounded),
            style = MaterialTheme.typography.labelMedium,
            color = colour,
        )
    }
}

/** Full-width primary action painted with the accent gradient. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "gradientButtonScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (enabled) Modifier.background(KolanAccentGradient)
                else Modifier.background(KolanSurfaceElevated)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) Color.White else KolanTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) Color.White else KolanTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Secondary action: outlined, no fill. */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tint: Color = KolanTextPrimary,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
        }
    }
}

/** Card used for explanatory blocks and warnings. */
@Composable
fun InfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accent: Color = KolanViolet,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.09f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = KolanTextPrimary)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = KolanTextSecondary)
        }
    }
}
