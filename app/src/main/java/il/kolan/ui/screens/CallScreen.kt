package il.kolan.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.kolan.R
import il.kolan.call.CallState
import il.kolan.call.SignalingClient
import il.kolan.message.ShareSheet
import il.kolan.ui.components.GradientButton
import il.kolan.ui.components.InfoCard
import il.kolan.ui.components.OutlineButton
import il.kolan.ui.theme.KolanBackground
import il.kolan.ui.theme.KolanCyan
import il.kolan.ui.theme.KolanDanger
import il.kolan.ui.theme.KolanSuccess
import il.kolan.ui.theme.KolanSurface
import il.kolan.ui.theme.KolanTextPrimary
import il.kolan.ui.theme.KolanTextSecondary
import il.kolan.ui.theme.KolanWarning

/**
 * Internal calls: the only mode where another person hears the processed voice.
 *
 * The connection is peer to peer; the signaling server exists purely to introduce the two
 * phones and cannot hear anything. Without one configured, the screen says so rather than
 * failing silently at connect time.
 */
@Composable
fun CallScreen(
    signalingUrl: String,
    state: CallState,
    roomCode: String,
    errorReason: String?,
    micMuted: Boolean,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartCall: (String) -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var enteredCode by remember { mutableStateOf("") }
    val serverConfigured = SignalingClient.isValidUrl(signalingUrl)
    val inCall = state != CallState.IDLE && state != CallState.ENDED && state != CallState.FAILED

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
            IconButton(onClick = { if (inCall) onHangUp() else onBack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.close),
                    tint = KolanTextPrimary,
                )
            }
            Text(
                text = stringResource(R.string.call_title),
                style = MaterialTheme.typography.headlineSmall,
                color = KolanTextPrimary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!serverConfigured) {
                InfoCard(
                    title = stringResource(R.string.call_no_server_title),
                    body = stringResource(R.string.call_no_server_body),
                    accent = KolanWarning,
                )
                OutlineButton(
                    text = stringResource(R.string.call_open_settings),
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                InfoCard(
                    title = stringResource(R.string.call_title),
                    body = stringResource(R.string.call_explain),
                    accent = KolanCyan,
                )
            }

            if (inCall) {
                CallStatusPanel(state = state, roomCode = roomCode)
            } else if (serverConfigured) {
                Text(
                    text = stringResource(R.string.call_room_code),
                    style = MaterialTheme.typography.titleMedium,
                    color = KolanTextPrimary,
                )
                OutlinedTextField(
                    value = enteredCode,
                    onValueChange = { input ->
                        enteredCode = input.filter { it.isDigit() }.take(6)
                    },
                    label = { Text(stringResource(R.string.call_room_code_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            errorReason?.let { reason ->
                Text(
                    text = when (reason) {
                        "room-full" -> stringResource(R.string.call_room_full)
                        else -> stringResource(R.string.call_failed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = KolanDanger,
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier.padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (inCall) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineButton(
                        text = if (micMuted) stringResource(R.string.call_unmute)
                        else stringResource(R.string.call_mute),
                        icon = if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        onClick = onToggleMute,
                        tint = if (micMuted) KolanWarning else KolanCyan,
                        modifier = Modifier.weight(1f),
                    )
                    OutlineButton(
                        text = stringResource(R.string.call_share_code),
                        icon = Icons.Filled.Share,
                        onClick = {
                            ShareSheet.shareText(
                                context,
                                context.getString(R.string.call_invite_text, roomCode),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                GradientButton(
                    text = stringResource(R.string.call_hangup),
                    icon = Icons.Filled.CallEnd,
                    onClick = onHangUp,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (serverConfigured) {
                GradientButton(
                    text = stringResource(R.string.call_create),
                    onClick = {
                        if (!hasMicPermission) {
                            onRequestMicPermission()
                        } else {
                            onStartCall(SignalingClient.generateRoomCode())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlineButton(
                    text = stringResource(R.string.call_join),
                    enabled = SignalingClient.isValidRoomCode(enteredCode),
                    onClick = {
                        if (!hasMicPermission) {
                            onRequestMicPermission()
                        } else {
                            onStartCall(enteredCode)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CallStatusPanel(state: CallState, roomCode: String) {
    val statusText = when (state) {
        CallState.CONNECTING -> stringResource(R.string.call_connecting)
        CallState.WAITING_FOR_PEER -> stringResource(R.string.call_waiting)
        CallState.CONNECTED -> stringResource(R.string.call_connected)
        CallState.RECONNECTING -> stringResource(R.string.call_reconnecting)
        CallState.ENDED -> stringResource(R.string.call_ended)
        CallState.FAILED -> stringResource(R.string.call_failed)
        CallState.IDLE -> ""
    }
    val statusColour = when (state) {
        CallState.CONNECTED -> KolanSuccess
        CallState.FAILED -> KolanDanger
        CallState.RECONNECTING -> KolanWarning
        else -> KolanCyan
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(KolanSurface)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColour),
            )
            Text(statusText, style = MaterialTheme.typography.titleMedium, color = statusColour)
        }

        Text(
            text = stringResource(R.string.call_your_code),
            style = MaterialTheme.typography.bodyMedium,
            color = KolanTextSecondary,
        )

        Text(
            // Wide letter spacing so a six-digit code can be read aloud without losing your place.
            text = roomCode.toCharArray().joinToString(" "),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp),
            color = KolanTextPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KolanSurface,
    unfocusedContainerColor = KolanSurface,
    focusedTextColor = KolanTextPrimary,
    unfocusedTextColor = KolanTextPrimary,
    cursorColor = KolanCyan,
    focusedIndicatorColor = KolanCyan,
    unfocusedIndicatorColor = KolanTextSecondary,
    focusedLabelColor = KolanCyan,
    unfocusedLabelColor = KolanTextSecondary,
)
