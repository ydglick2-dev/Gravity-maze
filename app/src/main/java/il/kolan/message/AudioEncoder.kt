package il.kolan.message

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Container and codec for the exported message.
 *
 * [codecMimeType] is what MediaCodec is asked for; [fileMimeType] is what the share intent
 * advertises. They differ — "audio/mp4a-latm" describes the elementary stream, while a receiving
 * app needs to be told the file is "audio/mp4" — and mixing them up makes WhatsApp reject the
 * attachment.
 */
enum class ExportFormat(
    val extension: String,
    val codecMimeType: String,
    val fileMimeType: String,
) {
    /** AAC in MP4. Works on every supported API level and WhatsApp accepts it. */
    M4A("m4a", MediaFormat.MIMETYPE_AUDIO_AAC, "audio/mp4"),

    /** Opus in Ogg. Matches what WhatsApp itself uses, but the muxer only supports it from 29. */
    OPUS("ogg", MediaFormat.MIMETYPE_AUDIO_OPUS, "audio/ogg"),
    ;

    val isSupported: Boolean
        get() = this != OPUS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}

/**
 * Encodes processed float samples into a shareable file.
 *
 * MediaCodec is fed 16-bit PCM in chunks and drained into a MediaMuxer. The drain has to keep up
 * with the feed rather than happening at the end, otherwise the codec's input queue blocks
 * forever on a long recording.
 */
object AudioEncoder {

    private const val TAG = "KolanEncoder"
    private const val BIT_RATE = 128_000
    private const val TIMEOUT_US = 10_000L

    suspend fun encode(
        samples: FloatArray,
        sampleRate: Int,
        outputFile: File,
        format: ExportFormat,
    ): Boolean = withContext(Dispatchers.IO) {
        val effectiveFormat = if (format.isSupported) format else ExportFormat.M4A

        runCatching {
            encodeInternal(samples, sampleRate, outputFile, effectiveFormat)
        }.onFailure {
            Log.w(TAG, "encoding failed", it)
            outputFile.delete()
        }.getOrDefault(false)
    }

    private fun encodeInternal(
        samples: FloatArray,
        sampleRate: Int,
        outputFile: File,
        format: ExportFormat,
    ): Boolean {
        if (samples.isEmpty()) return false

        outputFile.parentFile?.mkdirs()

        val mediaFormat = MediaFormat.createAudioFormat(format.codecMimeType, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            if (format == ExportFormat.M4A) {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }

        val muxerFormat = when (format) {
            ExportFormat.M4A -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            ExportFormat.OPUS -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
        }

        val codec = MediaCodec.createEncoderByType(format.codecMimeType)
        codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var sampleOffset = 0
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        inputBuffer.clear()
                        inputBuffer.order(ByteOrder.nativeOrder())

                        val capacityFrames = inputBuffer.capacity() / 2
                        val remaining = samples.size - sampleOffset
                        val count = minOf(capacityFrames, remaining)

                        if (count > 0) {
                            writePcm16(inputBuffer, samples, sampleOffset, count)
                            val presentationTimeUs =
                                sampleOffset.toLong() * 1_000_000L / sampleRate
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                count * 2,
                                presentationTimeUs,
                                0,
                            )
                            sampleOffset += count
                        } else {
                            val presentationTimeUs =
                                sampleOffset.toLong() * 1_000_000L / sampleRate
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The muxer needs the codec's real output format, complete with the
                        // codec-specific data, so the track cannot be added before this fires.
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        val isConfig =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

                        if (!isConfig && bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }

                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        return outputFile.exists() && outputFile.length() > 0
    }

    private fun writePcm16(target: ByteBuffer, samples: FloatArray, offset: Int, count: Int) {
        val shorts = target.asShortBuffer()
        for (i in 0 until count) {
            val clamped = samples[offset + i].coerceIn(-1f, 1f)
            shorts.put((clamped * 32767f).toInt().toShort())
        }
        target.position(0)
        target.limit(count * 2)
    }
}
