package com.glassify.launcher.launcher

import android.graphics.Rect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glassify.launcher.data.HomeItem
import com.glassify.launcher.data.LaunchableApp
import com.glassify.launcher.glass.GlassMotion
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.liquidGlass

/**
 * An open folder: a glass card floating over a darkened, scaled-back home screen.
 *
 * The zoom-in is the animation iOS is known for, and it is the reason folders
 * are worth building at all rather than treated as a plain list.
 */
@Composable
fun FolderView(
    folder: HomeItem.Folder,
    appsByKey: Map<String, LaunchableApp>,
    editMode: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val apps = folder.keys.mapNotNull { appsByKey[it] }
    var name by remember(folder.id) { mutableStateOf(folder.name) }

    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = GlassMotion.fluid(),
        label = "folder-open",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .pointerInput(folder.id) {
                detectTapGestures {
                    if (name != folder.name) onRename(name)
                    onDismiss()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .graphicsLayer {
                    scaleX = 0.92f + 0.08f * scale
                    scaleY = 0.92f + 0.08f * scale
                    alpha = scale
                }
                .liquidGlass(
                    shape = GlassShapes.panel,
                    cornerRadius = 38.dp,
                    spec = GlassSpec(blur = 34.dp, refraction = 12.dp, tintAmount = 0.20f),
                )
                // Consumes taps so tapping inside the card does not dismiss it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                enabled = editMode,
                textStyle = GlassTheme.type.title.copy(
                    color = GlassTheme.colors.onGlass,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(GlassTheme.colors.accent),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )

            apps.chunked(4).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { app ->
                        Box(Modifier.weight(1f)) {
                            AppTile(
                                label = app.label,
                                icon = app.icon,
                                showLabel = true,
                                jiggling = editMode,
                                onClick = {
                                    onLaunch(app, null)
                                    onDismiss()
                                },
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (apps.isEmpty()) Spacer(Modifier.height(40.dp))
        }
    }
}
