package com.glassify.launcher.glass

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.compositionLocalOf

/**
 * How much of the Liquid Glass effect the current device can actually render.
 *
 * The effect is built in three layers and each tier drops the most expensive one:
 *  - [FULL]   real backdrop sampling + AGSL refraction, chromatic aberration and
 *             tilt-driven specular. Needs a RuntimeShader (API 33+).
 *  - [BLUR]   backdrop is blurred with RenderEffect but the rim is painted with
 *             gradients instead of refracted. API 31-32, or a user downgrade.
 *  - [FLAT]   translucent gradients only. Used when the device reports that
 *             cross-window blur is unavailable (battery saver turns this off
 *             system-wide) or when the user picks it to save power.
 */
enum class GlassTier {
    FULL,
    BLUR,
    FLAT;

    val samplesBackdrop: Boolean get() = this == FULL
    val blursBackdrop: Boolean get() = this != FLAT

    companion object {
        /** The best tier this device can do, before any user preference is applied. */
        fun deviceMax(context: Context): GlassTier = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> FULL
            else -> BLUR
        }

        /**
         * Whether the system will honour blur *behind* our overlay windows. Android
         * turns this off in battery saver and on low-end devices, and it is a
         * runtime value — it can flip while the app is running.
         */
        fun crossWindowBlurEnabled(context: Context): Boolean {
            val wm = context.getSystemService(WindowManager::class.java) ?: return false
            return wm.isCrossWindowBlurEnabled
        }
    }
}

/** Resolved tier for the current composition: min(device capability, user preference). */
val LocalGlassTier = compositionLocalOf { GlassTier.BLUR }
