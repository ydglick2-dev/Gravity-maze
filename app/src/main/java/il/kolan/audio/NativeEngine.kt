package il.kolan.audio

import java.nio.ByteBuffer

/**
 * Kotlin face of libkolan.
 *
 * Everything here is a thin pass-through. Parameters are written into a block of atomics that
 * the audio thread reads once per callback, which is why a slider can be dragged while audio is
 * running without a lock anywhere in the path.
 */
object NativeEngine {

    init {
        System.loadLibrary("kolan")
    }

    /** Parameter indices. These must stay in lockstep with ParamId in core/ParamBlock.h. */
    object Param {
        const val PITCH_SEMITONES = 0
        const val FORMANT_SEMITONES = 1
        const val RING_MOD_HZ = 2
        const val RING_MOD_DEPTH = 3
        const val WHISPER = 4
        const val DRIVE = 5
        const val REVERB_MIX = 6
        const val REVERB_SIZE = 7
        const val TELEPHONE = 8
        const val GATE_THRESHOLD_DB = 9
        const val COMPRESSOR_AMOUNT = 10
        const val OUTPUT_GAIN_DB = 11
        const val BYPASS = 12
    }

    /** Output routing for the live engine. Mirrors OutputRoute in engine/LiveEngine.h. */
    object Route {
        const val HEADPHONES = 0
        const val SPEAKER = 1
    }

    /** Telemetry snapshot, refreshed once per animation frame. */
    data class MeterSnapshot(
        val rawPeak: Float,
        val processedPeak: Float,
        val rawRms: Float,
        val processedRms: Float,
        val latencyMs: Float,
    )

    private val meterScratch = FloatArray(5)

    fun start(route: Int, preferredInputDeviceId: Int = 0): Boolean =
        nativeStart(route, preferredInputDeviceId)

    fun stop() = nativeStop()

    fun isRunning(): Boolean = nativeIsRunning()

    /** Silences the output without tearing the streams down, e.g. when headphones vanish. */
    fun setMuted(muted: Boolean) = nativeSetMuted(muted)

    fun setParam(id: Int, value: Float) = nativeSetParam(id, value)

    fun getParam(id: Int): Float = nativeGetParam(id)

    /** Not thread safe by design: only the UI frame loop calls this. */
    fun readMeters(): MeterSnapshot {
        nativeReadMeters(meterScratch)
        return MeterSnapshot(
            rawPeak = meterScratch[0],
            processedPeak = meterScratch[1],
            rawRms = meterScratch[2],
            processedRms = meterScratch[3],
            latencyMs = meterScratch[4],
        )
    }

    fun xrunCount(): Int = nativeXrunCount()

    /**
     * Rewrites WebRTC's capture buffer in place. Called from WebRTC's recording thread for every
     * 10 ms block, before the Opus encoder sees it.
     */
    fun processCallBuffer(
        buffer: ByteBuffer,
        byteOffset: Int,
        byteCount: Int,
        channelCount: Int,
        sampleRate: Int,
    ): Boolean = nativeProcessCallBuffer(buffer, byteOffset, byteCount, channelCount, sampleRate)

    fun resetCallProcessor() = nativeResetCallProcessor()

    /** Offline voice-message processing. Blocking; callers must be off the main thread. */
    fun processOffline(samples: FloatArray, sampleRate: Int): FloatArray =
        nativeProcessOffline(samples, sampleRate)

    private external fun nativeStart(route: Int, preferredDeviceId: Int): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetMuted(muted: Boolean)
    private external fun nativeSetParam(id: Int, value: Float)
    private external fun nativeGetParam(id: Int): Float
    private external fun nativeReadMeters(out: FloatArray)
    private external fun nativeXrunCount(): Int
    private external fun nativeProcessCallBuffer(
        buffer: ByteBuffer,
        byteOffset: Int,
        byteCount: Int,
        channelCount: Int,
        sampleRate: Int,
    ): Boolean

    private external fun nativeResetCallProcessor()
    private external fun nativeProcessOffline(samples: FloatArray, sampleRate: Int): FloatArray
}
