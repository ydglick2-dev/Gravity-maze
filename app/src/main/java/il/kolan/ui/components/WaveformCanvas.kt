package il.kolan.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.kolan.R
import il.kolan.audio.NativeEngine
import il.kolan.ui.theme.KolanCyan
import il.kolan.ui.theme.KolanSurface
import il.kolan.ui.theme.KolanTextSecondary
import il.kolan.ui.theme.KolanViolet
import kotlinx.coroutines.android.awaitFrame

private const val HISTORY_SIZE = 160

/**
 * Rolling history of raw and processed levels.
 *
 * The audio thread never touches this. It publishes peak and RMS into atomics, and this class
 * samples them once per displayed frame from the UI thread, which is the only way to draw at
 * 60 fps without putting a lock anywhere near the audio callback.
 */
class WaveformState {
    val rawPeaks = FloatArray(HISTORY_SIZE)
    val processedPeaks = FloatArray(HISTORY_SIZE)

    /**
     * Snapshot-backed so that advancing it invalidates the Canvas. The sample arrays themselves
     * are plain floats: only one value needs to be observable for the redraw to happen, and
     * making 320 floats observable would cost far more than it buys.
     */
    var writeIndex by mutableIntStateOf(0)
        private set

    fun push(raw: Float, processed: Float) {
        rawPeaks[writeIndex] = raw
        processedPeaks[writeIndex] = processed
        writeIndex = (writeIndex + 1) % HISTORY_SIZE
    }

    fun clear() {
        rawPeaks.fill(0f)
        processedPeaks.fill(0f)
        writeIndex = 0
    }
}

/**
 * Live visualiser showing the raw and processed signals as two translucent layers.
 *
 * Seeing both at once is what makes the effect legible: with only the processed trace you
 * cannot tell how much of what you hear is you and how much is the engine.
 */
@Composable
fun WaveformCanvas(
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = remember { WaveformState() }

    LaunchedEffect(isActive) {
        if (!isActive) {
            state.clear()
            return@LaunchedEffect
        }
        while (true) {
            // Driving from the frame clock means we sample exactly once per rendered frame, no
            // faster and no slower, regardless of what the display is doing.
            awaitFrame()
            val meters = NativeEngine.readMeters()
            state.push(meters.rawPeak, meters.processedPeak)
        }
    }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp)),
        ) {
            drawRect(KolanSurface)
            drawCentreLine()

            if (isActive) {
                drawWaveLayer(
                    values = state.rawPeaks,
                    startIndex = state.writeIndex,
                    brush = Brush.verticalGradient(
                        listOf(
                            KolanTextSecondary.copy(alpha = 0.42f),
                            KolanTextSecondary.copy(alpha = 0.16f),
                        ),
                    ),
                    strokeColor = KolanTextSecondary.copy(alpha = 0.6f),
                )
                drawWaveLayer(
                    values = state.processedPeaks,
                    startIndex = state.writeIndex,
                    brush = Brush.verticalGradient(
                        listOf(KolanViolet.copy(alpha = 0.55f), KolanCyan.copy(alpha = 0.2f)),
                    ),
                    strokeColor = KolanCyan,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(KolanTextSecondary, stringResource(R.string.waveform_raw))
            LegendDot(KolanCyan, stringResource(R.string.waveform_processed))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = KolanTextSecondary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private fun DrawScope.drawCentreLine() {
    val y = size.height / 2f
    drawLine(
        color = Color.White.copy(alpha = 0.06f),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
    )
}

/**
 * Draws one layer as a mirrored envelope: the level history above the centre line and its
 * reflection below, filled and outlined.
 */
private fun DrawScope.drawWaveLayer(
    values: FloatArray,
    startIndex: Int,
    brush: Brush,
    strokeColor: Color,
) {
    val count = values.size
    if (count < 2) return

    val centreY = size.height / 2f
    val maxAmplitude = centreY * 0.9f
    val stepX = size.width / (count - 1).toFloat()

    val fill = Path()
    val outline = Path()

    for (i in 0 until count) {
        // Oldest sample on the left, newest on the right, so the trace scrolls rather than
        // jumping when the ring buffer wraps.
        val value = values[(startIndex + i) % count].coerceIn(0f, 1f)
        val x = i * stepX
        val y = centreY - value * maxAmplitude
        if (i == 0) {
            outline.moveTo(x, y)
            fill.moveTo(x, centreY)
            fill.lineTo(x, y)
        } else {
            outline.lineTo(x, y)
            fill.lineTo(x, y)
        }
    }
    for (i in count - 1 downTo 0) {
        val value = values[(startIndex + i) % count].coerceIn(0f, 1f)
        fill.lineTo(i * stepX, centreY + value * maxAmplitude)
    }
    fill.close()

    drawPath(fill, brush)
    drawPath(outline, strokeColor, style = Stroke(width = 2f))
}

/** Height the visualiser wants when it has the room. */
val WaveformPreferredHeight = 160.dp
