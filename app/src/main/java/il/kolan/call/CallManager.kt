package il.kolan.call

import android.content.Context
import android.util.Log
import il.kolan.audio.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/** What the call UI needs to know. */
enum class CallState {
    IDLE,
    CONNECTING,
    WAITING_FOR_PEER,
    CONNECTED,
    RECONNECTING,
    ENDED,
    FAILED,
}

/**
 * Runs a WebRTC call with the processed voice substituted for the raw microphone.
 *
 * The substitution happens in [JavaAudioDeviceModule.AudioBufferCallback]. WebRTC hands us its
 * capture buffer — a direct ByteBuffer of 16-bit PCM — before the Opus encoder touches it, and we
 * rewrite it in place through the native engine. Everything downstream, encoder included, sees
 * only the processed signal, which is why the far end hears the changed voice and not the real
 * one.
 *
 * This is the only route on stock Android where that substitution is possible at all: it works
 * because the capture stream belongs to this app. There is no equivalent hook for WhatsApp's
 * microphone, and no permission that creates one.
 */
class CallManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CallState.IDLE)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _roomCode = MutableStateFlow("")
    val roomCode: StateFlow<String> = _roomCode.asStateFlow()

    private val _errorReason = MutableStateFlow<String?>(null)
    val errorReason: StateFlow<String?> = _errorReason.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var signaling: SignalingClient? = null
    private var eglBase: EglBase? = null

    private var isInitiator = false
    private var micMuted = false

    /** Candidates that arrive before the remote description is set have to wait for it. */
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    fun start(serverUrl: String, roomCode: String) {
        if (_state.value != CallState.IDLE && _state.value != CallState.ENDED &&
            _state.value != CallState.FAILED
        ) {
            return
        }

        _errorReason.value = null
        _roomCode.value = roomCode
        _state.value = CallState.CONNECTING
        remoteDescriptionSet = false
        pendingCandidates.clear()

        initialiseFactory()
        createPeerConnection()

        val client = SignalingClient(serverUrl)
        signaling = client
        scope.launch { client.messages.collect(::handleSignalingMessage) }
        client.connect(roomCode)
    }

    private fun initialiseFactory() {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )

        val module = JavaAudioDeviceModule.builder(context)
            .setSampleRate(SAMPLE_RATE)
            // Hardware AEC and NS run before we ever see the buffer and are tuned for an
            // unmodified human voice. They fight the pitch shift and smear the result, so both
            // are off and the chain's own gate and compressor do that work instead.
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioBufferCallback { buffer, _, channelCount, sampleRate, bytesRead,
                                      captureTimeNs ->
                processCaptureBuffer(buffer, channelCount, sampleRate, bytesRead)
                // The return value is the capture timestamp WebRTC should attribute to this
                // block. Processing happens in place and adds no delay of its own here, so the
                // original timestamp remains the correct one.
                captureTimeNs
            }
            .createAudioDeviceModule()
        audioDeviceModule = module

        val egl = EglBase.create()
        eglBase = egl

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /**
     * Rewrites one captured block in place.
     *
     * Runs on WebRTC's recording thread every 10 ms. Muting is handled here by zeroing the
     * buffer rather than by disabling the track, so the engine keeps running and unmuting is
     * immediate instead of arriving a block or two later.
     */
    private fun processCaptureBuffer(
        buffer: ByteBuffer,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
    ) {
        if (micMuted) {
            val position = buffer.position()
            for (i in position until position + bytesRead) {
                if (i < buffer.capacity()) buffer.put(i, 0)
            }
            return
        }

        if (!buffer.isDirect) {
            // Without a direct buffer there is no address to hand to native code. Leaving the
            // audio untouched is the only safe response, and it means the call still works —
            // just without the voice change.
            Log.w(TAG, "capture buffer is not direct; passing audio through unprocessed")
            return
        }

        NativeEngine.processCallBuffer(
            buffer = buffer,
            byteOffset = buffer.position(),
            byteCount = bytesRead,
            channelCount = channelCount,
            sampleRate = sampleRate,
        )
    }

    private fun createPeerConnection() {
        val currentFactory = factory ?: return

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = currentFactory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    signaling?.sendIceCandidate(
                        candidate.sdp,
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                    )
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    _state.value = when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> CallState.CONNECTED
                        PeerConnection.PeerConnectionState.DISCONNECTED -> CallState.RECONNECTING
                        PeerConnection.PeerConnectionState.FAILED -> CallState.FAILED
                        PeerConnection.PeerConnectionState.CLOSED -> CallState.ENDED
                        else -> _state.value
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) =
                    Unit
            },
        )

        val constraints = MediaConstraints().apply {
            // The same reasoning as the device module: platform voice processing would undo the
            // effect we just spent a chain building.
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        }

        val source = currentFactory.createAudioSource(constraints)
        audioSource = source
        val track = currentFactory.createAudioTrack(AUDIO_TRACK_ID, source)
        localTrack = track
        peerConnection?.addTrack(track, listOf(STREAM_ID))
    }

    private fun handleSignalingMessage(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.Joined -> {
                isInitiator = message.isInitiator
                _state.value =
                    if (isInitiator) CallState.WAITING_FOR_PEER else CallState.CONNECTING
            }

            is SignalingMessage.PeerJoined -> {
                // Whoever created the room makes the offer, so both sides never offer at once.
                if (isInitiator) createOffer()
            }

            is SignalingMessage.Offer -> {
                setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, message.sdp))
                createAnswer()
            }

            is SignalingMessage.Answer -> {
                setRemoteDescription(
                    SessionDescription(SessionDescription.Type.ANSWER, message.sdp),
                )
            }

            is SignalingMessage.IceCandidate -> {
                val candidate =
                    IceCandidate(message.sdpMid, message.sdpMLineIndex, message.sdp)
                if (remoteDescriptionSet) {
                    peerConnection?.addIceCandidate(candidate)
                } else {
                    pendingCandidates.add(candidate)
                }
            }

            SignalingMessage.PeerLeft -> {
                _state.value = CallState.ENDED
            }

            is SignalingMessage.Error -> {
                _errorReason.value = message.reason
                _state.value = CallState.FAILED
            }

            SignalingMessage.Disconnected -> {
                if (_state.value == CallState.CONNECTED) {
                    // The media path is peer to peer, so losing the signaling socket does not by
                    // itself end an established call.
                    Log.i(TAG, "signaling closed while connected; media continues")
                } else {
                    _state.value = CallState.ENDED
                }
            }
        }
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(
            simpleSdpObserver("createOffer") { description ->
                peerConnection?.setLocalDescription(
                    simpleSdpObserver("setLocalDescription") {},
                    description,
                )
                signaling?.sendOffer(description.description)
            },
            constraints,
        )
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createAnswer(
            simpleSdpObserver("createAnswer") { description ->
                peerConnection?.setLocalDescription(
                    simpleSdpObserver("setLocalDescription") {},
                    description,
                )
                signaling?.sendAnswer(description.description)
            },
            constraints,
        )
    }

    private fun setRemoteDescription(description: SessionDescription) {
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) = Unit
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    // Candidates buffered while the description was outstanding can now be
                    // applied; WebRTC rejects them before this point.
                    pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingCandidates.clear()
                }

                override fun onCreateFailure(error: String?) = Unit
                override fun onSetFailure(error: String?) {
                    Log.w(TAG, "setRemoteDescription failed: $error")
                    _state.value = CallState.FAILED
                }
            },
            description,
        )
    }

    fun setMicMuted(muted: Boolean) {
        micMuted = muted
    }

    fun isMicMuted(): Boolean = micMuted

    fun hangUp() {
        signaling?.close()
        signaling = null

        peerConnection?.close()
        peerConnection = null

        localTrack?.dispose()
        localTrack = null
        audioSource?.dispose()
        audioSource = null

        pendingCandidates.clear()
        remoteDescriptionSet = false
        NativeEngine.resetCallProcessor()

        _state.value = CallState.ENDED
        _roomCode.value = ""
    }

    /** Tears down the factory as well. Called when the call screen is left for good. */
    fun release() {
        hangUp()
        factory?.dispose()
        factory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        eglBase?.release()
        eglBase = null
        _state.value = CallState.IDLE
    }

    private fun simpleSdpObserver(
        tag: String,
        onSuccess: (SessionDescription) -> Unit,
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            sdp?.let { scope.launch(Dispatchers.Main) { onSuccess(it) } }
        }

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "$tag failed: $error")
            _state.value = CallState.FAILED
        }

        override fun onSetFailure(error: String?) {
            Log.w(TAG, "$tag set failed: $error")
        }
    }

    companion object {
        private const val TAG = "KolanCall"
        private const val SAMPLE_RATE = 48000
        private const val AUDIO_TRACK_ID = "kolan_audio"
        private const val STREAM_ID = "kolan_stream"
    }
}
