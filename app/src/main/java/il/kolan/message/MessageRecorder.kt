package il.kolan.message

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Captures raw mono PCM for the voice-message path.
 *
 * This uses AudioRecord rather than the Oboe engine on purpose: nothing here is real time. The
 * recording is processed afterwards by the phase vocoder, which produces cleaner pitch shifting
 * than the live chain precisely because it is free to use a 2048-sample window.
 */
class MessageRecorder {

    @Volatile
    private var stopRequested = false

    /** Peak level of the most recent chunk, for the recording meter. */
    @Volatile
    var currentLevel: Float = 0f
        private set

    fun requestStop() {
        stopRequested = true
    }

    /**
     * Records until [requestStop] is called, the coroutine is cancelled, or [maxSeconds] elapses.
     * Returns the captured samples as floats in [-1, 1].
     *
     * Requires RECORD_AUDIO; callers check that before getting here, so the annotation just
     * silences the lint that cannot see across that boundary.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(maxSeconds: Int = MAX_SECONDS): FloatArray = withContext(Dispatchers.IO) {
        stopRequested = false
        currentLevel = 0f

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) return@withContext FloatArray(0)

        // A generous buffer: this is not latency sensitive, and a large one survives the
        // scheduler stalls that a phone under load produces without dropping samples.
        val bufferSize = minBuffer * 4

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext FloatArray(0)
        }

        val maxSamples = maxSeconds * SAMPLE_RATE
        val collected = ArrayList<FloatArray>()
        var total = 0

        try {
            recorder.startRecording()
            val chunk = FloatArray(CHUNK_SAMPLES)

            while (currentCoroutineContext().isActive && !stopRequested && total < maxSamples) {
                val read = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) break

                var peak = 0f
                for (i in 0 until read) {
                    val magnitude = kotlin.math.abs(chunk[i])
                    if (magnitude > peak) peak = magnitude
                }
                currentLevel = peak

                collected.add(chunk.copyOf(read))
                total += read
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            currentLevel = 0f
        }

        val result = FloatArray(minOf(total, maxSamples))
        var offset = 0
        for (part in collected) {
            val count = minOf(part.size, result.size - offset)
            if (count <= 0) break
            part.copyInto(result, offset, 0, count)
            offset += count
        }
        result
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val MAX_SECONDS = 120
        private const val CHUNK_SAMPLES = 4800  // 100 ms
    }
}
