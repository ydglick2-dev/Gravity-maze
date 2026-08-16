package il.kolan.audio

import android.content.Context
import il.kolan.data.PresetParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The four ways Kolan can be running. */
enum class EngineMode {
    IDLE,

    /** Microphone to headphones, for rehearsing a preset. */
    LIVE_MONITOR,

    /** WebRTC call between two Kolan users. The only path where someone else hears the change. */
    CALL,

    /** Record, process offline, export, share. */
    VOICE_MESSAGE,

    /** Experimental: play into a call already running on speakerphone. */
    LOUDSPEAKER,
}

/** Why the engine refused to start, so the UI can say something specific. */
enum class EngineFailure {
    NONE,
    NO_HEADPHONES,
    AUDIO_UNAVAILABLE,
}

/**
 * Owns the live audio engine's lifecycle and the rules around it.
 *
 * The rule that matters: LIVE MONITOR will not open the output without headphones, and if
 * headphones are unplugged mid-session the output is muted immediately rather than at the next
 * user interaction. LOUDSPEAKER deliberately opts out of that rule, because playing into the
 * room is the entire point of it.
 */
class EngineController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val audioRoute = AudioRoute(context)

    private val _mode = MutableStateFlow(EngineMode.IDLE)
    val mode: StateFlow<EngineMode> = _mode.asStateFlow()

    private val _failure = MutableStateFlow(EngineFailure.NONE)
    val failure: StateFlow<EngineFailure> = _failure.asStateFlow()

    val routeState: StateFlow<RouteState> = audioRoute.observe()
        .stateIn(scope, SharingStarted.Eagerly, audioRoute.currentState())

    private var currentParams: PresetParams = PresetParams()

    init {
        scope.launch(Dispatchers.Default) {
            routeState.collect { state ->
                // Unplugging headphones during LIVE MONITOR turns the phone into a feedback loop
                // within one buffer, so this cannot wait for the user to notice.
                if (_mode.value == EngineMode.LIVE_MONITOR) {
                    NativeEngine.setMuted(!state.headphonesConnected)
                    _failure.value =
                        if (state.headphonesConnected) EngineFailure.NONE
                        else EngineFailure.NO_HEADPHONES
                }
            }
        }
    }

    fun applyParams(params: PresetParams) {
        currentParams = params
        params.applyToEngine()
    }

    fun setBypassed(bypassed: Boolean) {
        NativeEngine.setParam(NativeEngine.Param.BYPASS, if (bypassed) 1f else 0f)
    }

    fun isBypassed(): Boolean = NativeEngine.getParam(NativeEngine.Param.BYPASS) > 0.5f

    /**
     * Starts the live engine in the requested mode.
     *
     * Returns false and sets [failure] when it cannot: no headphones for LIVE MONITOR, or the
     * platform refusing to open a low-latency stream.
     */
    fun start(mode: EngineMode): Boolean {
        if (mode == EngineMode.LIVE_MONITOR && !routeState.value.headphonesConnected) {
            _failure.value = EngineFailure.NO_HEADPHONES
            return false
        }

        val route = when (mode) {
            EngineMode.LOUDSPEAKER -> NativeEngine.Route.SPEAKER
            else -> NativeEngine.Route.HEADPHONES
        }
        audioRoute.setSpeakerphoneOn(mode == EngineMode.LOUDSPEAKER)

        val started = NativeEngine.start(route, audioRoute.preferredInputDeviceId())
        if (!started) {
            _failure.value = EngineFailure.AUDIO_UNAVAILABLE
            audioRoute.setSpeakerphoneOn(false)
            return false
        }

        currentParams.applyToEngine()
        NativeEngine.setMuted(false)
        _failure.value = EngineFailure.NONE
        _mode.value = mode
        return true
    }

    /**
     * Stops the engine, leaving [failure] alone.
     *
     * A failed start is followed immediately by a stop to tidy up, so clearing the reason here
     * would erase it before the UI ever saw it and the user would get silence with no
     * explanation. [dismissFailure] is the only thing that clears it.
     */
    fun stop() {
        NativeEngine.stop()
        audioRoute.setSpeakerphoneOn(false)
        _mode.value = EngineMode.IDLE
    }

    fun dismissFailure() {
        _failure.value = EngineFailure.NONE
    }

    /** Marks the engine as being driven by WebRTC rather than by Oboe. */
    fun enterCallMode() {
        NativeEngine.stop()
        NativeEngine.resetCallProcessor()
        currentParams.applyToEngine()
        _mode.value = EngineMode.CALL
    }

    fun exitCallMode() {
        NativeEngine.resetCallProcessor()
        if (_mode.value == EngineMode.CALL) _mode.value = EngineMode.IDLE
    }

    fun readMeters(): NativeEngine.MeterSnapshot = NativeEngine.readMeters()
}
