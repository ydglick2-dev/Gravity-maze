package com.glassify.launcher.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hosts a Compose tree in a window that is not an Activity.
 *
 * Compose refuses to run without a lifecycle, a saved-state registry and a
 * ViewModel store, all of which an Activity normally provides. An overlay window
 * has no Activity, so this supplies the three of them and tears them down again
 * — without that, the composition leaks every time a panel closes.
 */
class OverlayHost(
    private val context: Context,
    private val windowManager: WindowManager,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var view: ComposeView? = null

    val isShowing: Boolean get() = view != null

    fun show(params: WindowManager.LayoutParams, content: @Composable () -> Unit) {
        if (view != null) return

        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayHost)
            setViewTreeViewModelStoreOwner(this@OverlayHost)
            setViewTreeSavedStateRegistryOwner(this@OverlayHost)
            setContent(content)
        }

        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            // The overlay permission can be revoked while we are running.
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            return
        }

        view = composeView
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun update(params: WindowManager.LayoutParams) {
        val current = view ?: return
        try {
            windowManager.updateViewLayout(current, params)
        } catch (e: Exception) {
            // The view was already detached.
        }
    }

    fun dismiss() {
        val current = view ?: return
        view = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        try {
            windowManager.removeView(current)
        } catch (e: Exception) {
            // Already removed.
        }
        store.clear()
    }
}

/**
 * Window parameters for the overlays.
 *
 * `FLAG_LAYOUT_NO_LIMITS` is what lets the Island sit over the status bar and
 * line up with the camera cutout; without it the window is pushed below the
 * system bar and the illusion collapses.
 */
object OverlayWindows {

    /**
     * The Island. Now that the pill is glass rather than opaque black, its
     * window asks for blur-behind like every other pane — without it the glass
     * would sit over sharp pixels and read as a smudge.
     */
    fun island(topOffsetPx: Int, blurRadiusPx: Int): WindowManager.LayoutParams =
        blurredPanel(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            blurRadiusPx,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = topOffsetPx
        }

    /**
     * The dock: full width at the bottom, sized to its content.
     *
     * `FLAG_BLUR_BEHIND` is the whole effect here. We cannot read the pixels of
     * the launcher underneath, but the compositor can blur them for us, which is
     * what makes this glass rather than a tinted strip.
     */
    fun dock(bottomOffsetPx: Int, blurRadiusPx: Int): WindowManager.LayoutParams =
        blurredPanel(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            blurRadiusPx,
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            y = bottomOffsetPx
        }

    /** The clock panel: sized to its content, near the top. */
    fun clock(topOffsetPx: Int, blurRadiusPx: Int): WindowManager.LayoutParams =
        blurredPanel(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            blurRadiusPx,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = topOffsetPx
        }

    /**
     * A window that floats over other apps with the system blurring behind it.
     *
     * Not focusable, so it never takes the keyboard or the back gesture from the
     * app underneath, but still touchable so its own contents work.
     */
    private fun blurredPanel(width: Int, height: Int, blurRadiusPx: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = blurRadiusPx
            }
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    /** The invisible strip that catches a downward swipe from the top-left. */
    fun trigger(heightPx: Int, widthPx: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    /**
     * The Notification Centre panel: full screen, focusable so the back gesture
     * closes it, and asking the system to blur everything behind it.
     *
     * That blur is the overlay counterpart of the launcher's sampled backdrop.
     * We cannot read another app's pixels, but the compositor can blur them for
     * us, which gets to the same place without a screen recording.
     */
    fun panel(blurRadiusPx: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.35f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = blurRadiusPx
            }
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
}

/** Convenience for hosts that only need to know whether a view is attached. */
val View.isAttached: Boolean get() = isAttachedToWindow
