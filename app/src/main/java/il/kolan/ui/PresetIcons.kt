package il.kolan.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector

/** Maps a preset's stored icon key to a vector. Unknown keys fall back to a generic voice icon. */
fun presetIcon(key: String): ImageVector = when (key) {
    "deep_male" -> Icons.Filled.RecordVoiceOver
    "female" -> Icons.Filled.Face
    "child" -> Icons.Filled.ChildCare
    "robot" -> Icons.Filled.SmartToy
    "demon" -> Icons.Filled.LocalFireDepartment
    "alien" -> Icons.Filled.Podcasts
    "old_phone" -> Icons.Filled.PhoneInTalk
    "whisper" -> Icons.Filled.GraphicEq
    "custom" -> Icons.Filled.Bolt
    else -> Icons.Filled.GraphicEq
}

/** Icon keys offered when saving a custom preset. */
val customIconKeys = listOf(
    "custom",
    "deep_male",
    "female",
    "child",
    "robot",
    "demon",
    "alien",
    "old_phone",
    "whisper",
)
