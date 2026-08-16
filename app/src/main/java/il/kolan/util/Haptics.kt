package il.kolan.util

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * Light haptic feedback for preset changes.
 *
 * Deliberately subtle: the user is usually wearing headphones and concentrating on what they
 * hear, so the tap should confirm the change without competing with it.
 */
class Haptics(
    private val enabled: () -> Boolean,
    private val perform: (HapticFeedbackType) -> Unit,
) {
    fun presetChanged() {
        if (enabled()) perform(HapticFeedbackType.LongPress)
    }

    fun tick() {
        if (enabled()) perform(HapticFeedbackType.TextHandleMove)
    }
}

@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val feedback = LocalHapticFeedback.current
    val view = LocalView.current
    return remember(enabled, feedback, view) {
        Haptics(
            enabled = { enabled && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || view.isHapticFeedbackEnabled) },
            perform = { feedback.performHapticFeedback(it) },
        )
    }
}
