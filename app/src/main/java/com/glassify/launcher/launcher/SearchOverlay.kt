package com.glassify.launcher.launcher

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glassify.launcher.R
import com.glassify.launcher.data.LaunchableApp
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.liquidGlass

/**
 * Spotlight: a glass field over a dimmed home screen, filtering apps as you type.
 *
 * Matching is prefix-first, then substring. That ordering matters more than it
 * looks — typing "ma" should offer Maps before Gmail, and a plain `contains`
 * filter puts them in whatever order the app list happened to be in.
 */
@Composable
fun SearchOverlay(
    apps: List<LaunchableApp>,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    val results = remember(query, apps) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            emptyList()
        } else {
            val prefix = apps.filter { it.label.startsWith(trimmed, ignoreCase = true) }
            val rest = apps.filter {
                !it.label.startsWith(trimmed, ignoreCase = true) &&
                    it.label.contains(trimmed, ignoreCase = true)
            }
            (prefix + rest).take(30)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .imePadding(),
        ) {
            Spacer(Modifier.height(64.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(
                        shape = GlassShapes.pill,
                        cornerRadius = 999.dp,
                        spec = GlassSpec(blur = 24.dp, refraction = 8.dp, tintAmount = 0.22f),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SearchGlyph()
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_hint),
                            style = GlassTheme.type.body,
                            color = GlassTheme.colors.onGlassTertiary,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = GlassTheme.type.body.copy(color = GlassTheme.colors.onGlass),
                        cursorBrush = SolidColor(GlassTheme.colors.accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = {
                                results.firstOrNull()?.let { onLaunch(it, null) }
                                    ?: searchWeb(context, query)
                                onDismiss()
                            },
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            LazyColumn(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(results, key = { it.key }) { app ->
                    ResultRow(app) {
                        onLaunch(app, null)
                        onDismiss()
                    }
                }
                if (query.isNotBlank()) {
                    item {
                        WebSearchRow(query) {
                            searchWeb(context, query)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(app: LaunchableApp, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(GlassShapes.card)
            .pointerInput(app.key) { detectTapGestures { onClick() } }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).clip(GlassShapes.icon)) {
            app.icon?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = app.label,
            style = GlassTheme.type.body,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WebSearchRow(query: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(GlassShapes.card)
            .pointerInput(query) { detectTapGestures { onClick() } }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { SearchGlyph() }
        Text(
            text = stringResource(R.string.search_web, query),
            style = GlassTheme.type.callout,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A magnifier, drawn rather than shipped as an asset. */
@Composable
private fun SearchGlyph() {
    androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
        val stroke = 1.9f.dp.toPx()
        val radius = size.minDimension * 0.34f
        val center = androidx.compose.ui.geometry.Offset(radius + stroke, radius + stroke)
        drawCircle(
            color = Color.White.copy(alpha = 0.75f),
            radius = radius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.75f),
            start = androidx.compose.ui.geometry.Offset(
                center.x + radius * 0.72f, center.y + radius * 0.72f,
            ),
            end = androidx.compose.ui.geometry.Offset(size.width - stroke, size.height - stroke),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

private fun searchWeb(context: android.content.Context, query: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // No browser installed; nothing sensible to fall back to.
    }
}
