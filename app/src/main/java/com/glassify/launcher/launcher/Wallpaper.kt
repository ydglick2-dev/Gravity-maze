package com.glassify.launcher.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The home screen background.
 *
 * The launcher paints the wallpaper itself instead of letting the system show it
 * behind a transparent window. That is not a stylistic choice: a system
 * wallpaper lives behind our surface, where nothing we draw can sample it, and
 * without sampled pixels the glass has nothing to refract. Owning the wallpaper
 * is what makes the effect possible at all.
 */
@Composable
fun Wallpaper(
    presetIndex: Int,
    customPath: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var custom by remember(customPath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(customPath) {
        custom = customPath?.let { loadWallpaper(it) }
    }

    val bitmap = custom
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.fillMaxSize().background(WallpaperPresets.brush(presetIndex)))
    }
}

private suspend fun loadWallpaper(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val file = File(path)
    if (!file.exists()) return@withContext null
    // Decode at a bounded size: a 100-megapixel photo blurred every frame is a
    // reliable way to make the whole launcher stutter.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val sample = maxOf(1, minOf(bounds.outWidth / MAX_EDGE, bounds.outHeight / MAX_EDGE))
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}

private const val MAX_EDGE = 1600

/**
 * Copies a picked image into our own storage.
 *
 * Photo-picker URIs are scoped grants that do not reliably survive a reboot, and
 * a launcher that loses its wallpaper every time the phone restarts is worse
 * than one that costs a couple of megabytes of cache.
 */
suspend fun importWallpaper(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    val target = File(context.filesDir, "wallpaper.jpg")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext null
        target.absolutePath
    } catch (e: Exception) {
        null
    }
}

/**
 * Original wallpapers, generated rather than shipped.
 *
 * Each is a few overlapping radial gradients — the deep, soft colour fields iOS
 * ships with, which happen to also be the ideal backdrop for glass: broad tonal
 * gradients make refraction at a panel's rim obvious, where a busy photograph
 * hides it.
 */
object WallpaperPresets {

    val names = listOf("Midnight", "Ember", "Tide", "Orchid", "Graphite")

    fun brush(index: Int): Brush = when (index.coerceIn(0, names.lastIndex)) {
        0 -> mesh(
            Color(0xFF0B1026), Color(0xFF1B2A63), Color(0xFF3A1D6E), Color(0xFF060814),
        )
        1 -> mesh(
            Color(0xFF2B0A12), Color(0xFF7A1F2B), Color(0xFFC7522A), Color(0xFF16060A),
        )
        2 -> mesh(
            Color(0xFF04212B), Color(0xFF0E5A63), Color(0xFF1D8F9E), Color(0xFF021016),
        )
        3 -> mesh(
            Color(0xFF1A0A2B), Color(0xFF5B1E7A), Color(0xFFB84A8F), Color(0xFF0C0518),
        )
        else -> mesh(
            Color(0xFF14161A), Color(0xFF2A2E36), Color(0xFF41464F), Color(0xFF0A0B0D),
        )
    }

    /**
     * Two off-centre radial pools over a diagonal base. Layering the highlights
     * away from the centre keeps the brightest area out from behind the dock,
     * where it would wash the glass out.
     */
    private fun mesh(base: Color, mid: Color, highlight: Color, deep: Color): Brush =
        Brush.linearGradient(
            0f to deep,
            0.35f to base,
            0.62f to mid,
            0.85f to highlight.copy(alpha = 0.85f),
            1f to deep,
            start = Offset(0f, 0f),
            end = Offset(900f, 2200f),
        )
}
