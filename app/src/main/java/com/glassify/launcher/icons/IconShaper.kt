package com.glassify.launcher.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Re-cuts an installed app's launcher icon into the iOS shape.
 *
 * This is the single highest-leverage part of the whole launcher. Android icons
 * arrive in a mix of shapes — circles, rounded squares, bare transparent
 * glyphs, full-bleed squares — and that inconsistency is most of what the eye
 * reads as "this is Android". Forcing every icon into one squircle at one size
 * with one corner radius does more for the illusion than any amount of blur.
 *
 * Note this reshapes the app's *own* artwork. It does not substitute Apple's
 * icons, which are not ours to redistribute.
 */
class IconShaper(private val sizePx: Int) {

    /**
     * @param drawable the icon as returned by `LauncherApps`
     * @return a square bitmap of [sizePx], fully opaque inside the squircle.
     */
    fun shape(drawable: Drawable): Bitmap {
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val content = when (drawable) {
            // Adaptive icons already separate art from plate, which is exactly
            // the split we want: keep the art, replace the plate with ours.
            is AdaptiveIconDrawable -> renderAdaptive(drawable)
            else -> renderLegacy(drawable)
        }

        canvas.drawBitmap(content, 0f, 0f, null)
        maskToSquircle(canvas)
        drawGloss(canvas)
        return output
    }

    /**
     * Adaptive icons reserve the outer ~18% of their canvas as bleed that the
     * system crops. We crop the same way, then re-plate: if the icon's own
     * background is transparent (common for monochrome-ish icons) we synthesise
     * a plate from the foreground's dominant colour so it never lands as a bare
     * glyph floating on the wallpaper.
     */
    private fun renderAdaptive(drawable: AdaptiveIconDrawable): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw at the oversized canvas the icon expects, then crop the bleed.
        val oversize = (sizePx * ADAPTIVE_SCALE).toInt()
        val inset = (oversize - sizePx) / 2
        val bounds = Rect(-inset, -inset, oversize - inset, oversize - inset)

        val background = drawable.background
        val foreground = drawable.foreground

        if (background != null && !background.isEffectivelyTransparent()) {
            background.bounds = bounds
            background.draw(canvas)
        } else {
            val plate = foreground?.let { dominantColor(it) } ?: DEFAULT_PLATE
            drawPlate(canvas, plate)
        }

        foreground?.let {
            it.bounds = bounds
            it.draw(canvas)
        }
        return bitmap
    }

    /**
     * Legacy icons are already the finished artwork, usually with their own
     * shape baked in and transparent padding around it. Scaling them to fill our
     * squircle would crop that shape badly, so instead they are centred at ~78%
     * on a plate tinted from the icon itself — which is what iOS does to
     * anything that is not full-bleed.
     */
    private fun renderLegacy(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawPlate(canvas, dominantColor(drawable))

        val inner = (sizePx * LEGACY_CONTENT).toInt()
        val offset = (sizePx - inner) / 2
        drawable.bounds = Rect(offset, offset, offset + inner, offset + inner)
        drawable.draw(canvas)
        return bitmap
    }

    /** A soft vertical gradient plate, lighter at the top like an iOS icon. */
    private fun drawPlate(canvas: Canvas, color: Int) {
        val top = ColorUtils.blendARGB(color, Color.WHITE, 0.16f)
        val bottom = ColorUtils.blendARGB(color, Color.BLACK, 0.10f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, sizePx.toFloat(),
                top, bottom, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
    }

    /** Clips whatever has been drawn so far to the squircle, antialiased. */
    private fun maskToSquircle(canvas: Canvas) {
        val mask = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawPath(
            squircleAndroidPath(sizePx.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK },
        )
        canvas.drawBitmap(
            mask,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            },
        )
    }

    /**
     * A single hairline of light along the top edge. Subtle to the point of
     * being invisible in isolation, but across a full grid it is what gives the
     * icons the impression of being physical tiles.
     */
    private fun drawGloss(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, sizePx * 0.012f)
            shader = LinearGradient(
                0f, 0f, 0f, sizePx * 0.5f,
                Color.argb(56, 255, 255, 255), Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(squircleAndroidPath(sizePx.toFloat()), paint)
    }

    /** The same superellipse the Compose side uses, as an android.graphics.Path. */
    private fun squircleAndroidPath(size: Float): android.graphics.Path {
        val path = android.graphics.Path()
        val half = size / 2f
        val p = 2.0 / SQUIRCLE_N
        var first = true
        val steps = 96
        for (i in 0..steps) {
            val t = i.toDouble() / steps * 2.0 * PI
            val c = cos(t)
            val s = sin(t)
            val x = half + half * sign(c) * abs(c).pow(p).toFloat()
            val y = half + half * sign(s) * abs(s).pow(p).toFloat()
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        return path
    }

    /**
     * The colour a plate should be. Palette's vibrant swatch is the right answer
     * for most branded icons; the muted swatch and then the average are the
     * fallbacks for icons with no strong colour at all.
     */
    private fun dominantColor(drawable: Drawable): Int {
        val sample = drawable.toBitmapOrNull(PALETTE_SAMPLE) ?: return DEFAULT_PLATE
        val palette = Palette.from(sample).clearFilters().generate()
        val color = palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: DEFAULT_PLATE

        // A near-white or near-black plate loses the icon against the wallpaper,
        // so pull extremes back towards something with body.
        val luminance = ColorUtils.calculateLuminance(color)
        return when {
            luminance > 0.86 -> ColorUtils.blendARGB(color, Color.BLACK, 0.12f)
            luminance < 0.04 -> ColorUtils.blendARGB(color, Color.WHITE, 0.14f)
            else -> color
        }
    }

    private companion object {
        /** Adaptive icons are drawn 1.5x and cropped, per the platform spec. */
        const val ADAPTIVE_SCALE = 1.5f

        /** Fraction of the tile a legacy icon's artwork occupies. */
        const val LEGACY_CONTENT = 0.78f

        const val SQUIRCLE_N = 5.0
        const val PALETTE_SAMPLE = 48
        const val DEFAULT_PLATE = 0xFF4A4A52.toInt()
    }
}

private fun sign(v: Double): Float = if (v < 0) -1f else 1f

/**
 * True when a drawable paints (almost) nothing — the case that decides whether
 * an adaptive icon keeps its own plate or gets a synthesised one.
 */
private fun Drawable.isEffectivelyTransparent(): Boolean {
    val bitmap = toBitmapOrNull(8) ?: return true
    var opaque = 0
    for (x in 0 until bitmap.width) {
        for (y in 0 until bitmap.height) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 24) opaque++
        }
    }
    return opaque < bitmap.width * bitmap.height / 8
}

private fun Drawable.toBitmapOrNull(size: Int): Bitmap? {
    if (this is BitmapDrawable) {
        val source = bitmap ?: return null
        if (source.width <= 0 || source.height <= 0) return null
        return source.scale(size)
    }
    val w = max(1, min(size, if (intrinsicWidth > 0) intrinsicWidth else size))
    val h = max(1, min(size, if (intrinsicHeight > 0) intrinsicHeight else size))
    return try {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val previous = Rect(bounds)
        setBounds(0, 0, w, h)
        draw(canvas)
        bounds = previous
        bitmap
    } catch (e: IllegalArgumentException) {
        null
    }
}

private fun Bitmap.scale(size: Int): Bitmap =
    Bitmap.createScaledBitmap(this, size, size, true)
