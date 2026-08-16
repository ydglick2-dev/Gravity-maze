package il.kolan.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Fires on press and again on release or cancel.
 *
 * Compose has no press-and-hold button out of the box, and A/B comparison needs one: the whole
 * point is that the comparison lasts exactly as long as the finger is down.
 */
fun Modifier.pointerInputHold(
    onPress: () -> Unit,
    onRelease: () -> Unit,
): Modifier = this.pointerInput(onPress, onRelease) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onPress()
        // Returns null when the gesture is cancelled, e.g. by a parent scroll taking over, so
        // the released state is restored either way and the button cannot latch on.
        waitForUpOrCancellation()
        onRelease()
    }
}
