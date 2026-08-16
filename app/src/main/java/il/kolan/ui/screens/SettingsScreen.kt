package il.kolan.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.kolan.R
import il.kolan.call.SignalingClient
import il.kolan.ui.components.GradientButton
import il.kolan.ui.components.InfoCard
import il.kolan.ui.theme.KolanBackground
import il.kolan.ui.theme.KolanCyan
import il.kolan.ui.theme.KolanDanger
import il.kolan.ui.theme.KolanSuccess
import il.kolan.ui.theme.KolanSurface
import il.kolan.ui.theme.KolanTextPrimary
import il.kolan.ui.theme.KolanTextSecondary

@Composable
fun SettingsScreen(
    signalingUrl: String,
    hapticsEnabled: Boolean,
    xrunCount: Int,
    onSignalingUrlChange: (String) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftUrl by remember(signalingUrl) { mutableStateOf(signalingUrl) }
    var saved by remember { mutableStateOf(false) }

    val trimmed = draftUrl.trim()
    val urlValid = trimmed.isEmpty() || SignalingClient.isValidUrl(trimmed)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KolanBackground)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.close),
                    tint = KolanTextPrimary,
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = KolanTextPrimary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_signaling),
                    style = MaterialTheme.typography.titleMedium,
                    color = KolanTextPrimary,
                )
                Text(
                    text = stringResource(R.string.settings_signaling_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KolanTextSecondary,
                )
                OutlinedTextField(
                    value = draftUrl,
                    onValueChange = {
                        draftUrl = it
                        saved = false
                    },
                    label = { Text(stringResource(R.string.settings_signaling_hint)) },
                    singleLine = true,
                    isError = !urlValid,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = KolanSurface,
                        unfocusedContainerColor = KolanSurface,
                        focusedTextColor = KolanTextPrimary,
                        unfocusedTextColor = KolanTextPrimary,
                        cursorColor = KolanCyan,
                        focusedIndicatorColor = KolanCyan,
                        unfocusedIndicatorColor = KolanTextSecondary,
                        focusedLabelColor = KolanCyan,
                        unfocusedLabelColor = KolanTextSecondary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!urlValid) {
                    Text(
                        text = stringResource(R.string.settings_signaling_invalid),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KolanDanger,
                    )
                }
                if (saved) {
                    Text(
                        text = stringResource(R.string.settings_saved),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KolanSuccess,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_haptics),
                        style = MaterialTheme.typography.titleMedium,
                        color = KolanTextPrimary,
                    )
                    Text(
                        text = stringResource(R.string.settings_haptics_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KolanTextSecondary,
                    )
                }
                Switch(
                    checked = hapticsEnabled,
                    onCheckedChange = onHapticsChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = KolanCyan,
                        checkedTrackColor = KolanCyan.copy(alpha = 0.35f),
                    ),
                )
            }

            InfoCard(
                title = stringResource(R.string.settings_engine),
                body = stringResource(R.string.settings_engine_desc),
                accent = KolanCyan,
            )

            Text(
                text = stringResource(R.string.settings_xruns, xrunCount),
                style = MaterialTheme.typography.bodyMedium,
                color = KolanTextSecondary,
            )

            Spacer(Modifier.height(8.dp))
        }

        GradientButton(
            text = stringResource(R.string.settings_save),
            enabled = urlValid,
            onClick = {
                onSignalingUrlChange(trimmed)
                saved = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        )
    }
}
