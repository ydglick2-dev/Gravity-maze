package il.kolan.ui.screens

import android.media.AudioAttributes
import android.media.MediaPlayer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.kolan.R
import il.kolan.audio.NativeEngine
import il.kolan.data.PresetParams
import il.kolan.message.AudioEncoder
import il.kolan.message.ExportFormat
import il.kolan.message.MessageRecorder
import il.kolan.message.ShareOutcome
import il.kolan.message.ShareSheet
import il.kolan.ui.components.GradientButton
import il.kolan.ui.components.InfoCard
import il.kolan.ui.components.OutlineButton
import il.kolan.ui.theme.KolanAccentGradient
import il.kolan.ui.theme.KolanBackground
import il.kolan.ui.theme.KolanCyan
import il.kolan.ui.theme.KolanDanger
import il.kolan.ui.theme.KolanSurface
import il.kolan.ui.theme.KolanTextPrimary
import il.kolan.ui.theme.KolanTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class MessageStage {
    IDLE,
    RECORDING,
    PROCESSING,
    READY,
}

/**
 * Record, process offline, share.
 *
 * The processing here goes through the phase vocoder rather than the live WSOLA chain. Nothing
 * is waiting on the result, so the engine can afford a 2048-sample window and the noticeably
 * cleaner pitch shift that comes with it.
 */
@Composable
fun VoiceMessageScreen(
    params: PresetParams,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(MessageStage.IDLE) }
    var level by remember { mutableFloatStateOf(0f) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    var exportedFile by remember { mutableStateOf<File?>(null) }
    var exportFormat by remember { mutableStateOf(ExportFormat.M4A) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    val recorder = remember { MessageRecorder() }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }

    val tooShortMessage = stringResource(R.string.message_too_short)
    val exportFailedMessage = stringResource(R.string.message_export_failed)
    val noWhatsAppMessage = stringResource(R.string.message_no_whatsapp)

    DisposableEffect(Unit) {
        onDispose {
            recorder.requestStop()
            recordingJob?.cancel()
            player.value?.release()
            player.value = null
        }
    }

    LaunchedEffect(stage) {
        if (stage != MessageStage.RECORDING) {
            level = 0f
            return@LaunchedEffect
        }
        elapsedSeconds = 0
        val startedAt = System.currentTimeMillis()
        while (stage == MessageStage.RECORDING) {
            level = recorder.currentLevel
            elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
            delay(50)
        }
    }

    fun startRecording() {
        if (!hasMicPermission) {
            onRequestMicPermission()
            return
        }
        statusMessage = null
        exportedFile?.delete()
        exportedFile = null
        stage = MessageStage.RECORDING

        recordingJob = scope.launch {
            val samples = recorder.record()
            if (samples.size < MessageRecorder.SAMPLE_RATE / 4) {
                statusMessage = tooShortMessage
                stage = MessageStage.IDLE
                return@launch
            }

            stage = MessageStage.PROCESSING

            // Both the DSP and the encode are heavy enough to stutter the animation if they run
            // anywhere near the main thread.
            val processed = withContext(Dispatchers.Default) {
                NativeEngine.processOffline(samples, MessageRecorder.SAMPLE_RATE)
            }

            val format = if (ExportFormat.OPUS.isSupported) ExportFormat.OPUS else ExportFormat.M4A
            val target = File(
                File(context.cacheDir, "messages"),
                "kolan_${System.currentTimeMillis()}.${format.extension}",
            )

            val ok = AudioEncoder.encode(
                samples = processed,
                sampleRate = MessageRecorder.SAMPLE_RATE,
                outputFile = target,
                format = format,
            )

            if (ok) {
                exportFormat = format
                exportedFile = target
                stage = MessageStage.READY
            } else {
                statusMessage = exportFailedMessage
                stage = MessageStage.IDLE
            }
        }
    }

    fun stopRecording() {
        recorder.requestStop()
    }

    fun togglePlayback() {
        val file = exportedFile ?: return
        val existing = player.value
        if (existing != null && existing.isPlaying) {
            existing.pause()
            isPlaying = false
            return
        }
        if (existing != null) {
            existing.start()
            isPlaying = true
            return
        }

        val created = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                isPlaying = false
                it.seekTo(0)
            }
            prepare()
            start()
        }
        player.value = created
        isPlaying = true
    }

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
                text = stringResource(R.string.message_title),
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
            InfoCard(
                title = stringResource(R.string.message_title),
                body = stringResource(R.string.message_explain),
                accent = KolanCyan,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (stage) {
                    MessageStage.PROCESSING -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = KolanCyan)
                        Text(
                            stringResource(R.string.message_processing),
                            color = KolanTextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    else -> RecordButton(
                        recording = stage == MessageStage.RECORDING,
                        level = level,
                        onClick = {
                            if (stage == MessageStage.RECORDING) stopRecording() else startRecording()
                        },
                    )
                }
            }

            if (stage == MessageStage.RECORDING) {
                Text(
                    text = formatDuration(elapsedSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                    color = KolanCyan,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KolanDanger,
                )
            }

            if (stage == MessageStage.READY) {
                Text(
                    text = "${stringResource(R.string.message_format)}: ${exportFormat.extension}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KolanTextSecondary,
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        if (stage == MessageStage.READY) {
            Column(
                modifier = Modifier.padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GradientButton(
                    text = stringResource(R.string.message_share_whatsapp),
                    icon = Icons.Filled.Send,
                    onClick = {
                        val file = exportedFile ?: return@GradientButton
                        when (
                            ShareSheet.share(context, file, exportFormat.fileMimeType)
                        ) {
                            ShareOutcome.SHARED_TO_CHOOSER ->
                                statusMessage = noWhatsAppMessage

                            ShareOutcome.FAILED -> statusMessage = exportFailedMessage
                            ShareOutcome.SHARED_TO_WHATSAPP -> statusMessage = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineButton(
                        text = if (isPlaying) stringResource(R.string.message_pause)
                        else stringResource(R.string.message_play),
                        icon = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        onClick = { togglePlayback() },
                        modifier = Modifier.weight(1f),
                    )
                    OutlineButton(
                        text = stringResource(R.string.message_rerecord),
                        icon = Icons.Filled.Refresh,
                        onClick = {
                            player.value?.release()
                            player.value = null
                            isPlaying = false
                            startRecording()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // Keep the engine's parameters in step so the offline pass uses exactly what the user
    // last heard in LIVE MONITOR.
    LaunchedEffect(params) { params.applyToEngine() }
}

/**
 * Record button whose halo tracks the input level.
 *
 * The level ring is not decoration: it is the only feedback that the microphone is actually
 * picking something up before the user has anything to play back.
 */
@Composable
private fun RecordButton(recording: Boolean, level: Float, onClick: () -> Unit) {
    val haloScale = 1f + level.coerceIn(0f, 1f) * 0.55f

    Box(contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier
                    .size(150.dp)
                    .scale(haloScale)
                    .clip(CircleShape)
                    .background(KolanCyan.copy(alpha = 0.12f)),
            )
        }
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .then(
                    if (recording) {
                        Modifier
                            .background(KolanSurface)
                            .background(KolanDanger.copy(alpha = 0.18f))
                    } else {
                        Modifier.background(KolanAccentGradient)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(120.dp)) {
                Icon(
                    imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (recording) {
                        stringResource(R.string.message_stop)
                    } else {
                        stringResource(R.string.message_record)
                    },
                    tint = if (recording) KolanDanger else Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return "%d:%02d".format(minutes, remainder)
}
