package il.kolan.call

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Messages exchanged with the signaling server. Mirrors the protocol in server/server.js. */
sealed interface SignalingMessage {
    data class Joined(val roomCode: String, val isInitiator: Boolean) : SignalingMessage
    data class PeerJoined(val roomCode: String) : SignalingMessage
    data class Offer(val sdp: String) : SignalingMessage
    data class Answer(val sdp: String) : SignalingMessage
    data class IceCandidate(val sdp: String, val sdpMid: String, val sdpMLineIndex: Int) :
        SignalingMessage

    data object PeerLeft : SignalingMessage
    data class Error(val reason: String) : SignalingMessage
    data object Disconnected : SignalingMessage
}

/**
 * WebSocket client for call setup.
 *
 * The server only relays: it never sees or stores audio. Its whole job is to let two phones
 * that both know a six-digit code exchange the SDP and ICE candidates they need in order to
 * connect to each other directly.
 */
class SignalingClient(private val serverUrl: String) {

    private val client = OkHttpClient.Builder()
        // The server sends a ping every 30 s; a shorter interval here keeps NAT bindings alive
        // on mobile networks that drop idle connections aggressively.
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _messages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 32)
    val messages: SharedFlow<SignalingMessage> = _messages.asSharedFlow()

    fun connect(roomCode: String) {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(JSONObject().put("type", "join").put("room", roomCode))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text)?.let { _messages.tryEmit(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "signaling failure", t)
                _messages.tryEmit(SignalingMessage.Disconnected)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _messages.tryEmit(SignalingMessage.Disconnected)
            }
        })
    }

    fun sendOffer(sdp: String) {
        send(JSONObject().put("type", "offer").put("sdp", sdp))
    }

    fun sendAnswer(sdp: String) {
        send(JSONObject().put("type", "answer").put("sdp", sdp))
    }

    fun sendIceCandidate(sdp: String, sdpMid: String?, sdpMLineIndex: Int) {
        send(
            JSONObject()
                .put("type", "ice")
                .put("candidate", sdp)
                .put("sdpMid", sdpMid ?: "")
                .put("sdpMLineIndex", sdpMLineIndex),
        )
    }

    fun close() {
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    private fun send(payload: JSONObject) {
        webSocket?.send(payload.toString())
    }

    private fun parse(text: String): SignalingMessage? = runCatching {
        val json = JSONObject(text)
        when (json.getString("type")) {
            "joined" -> SignalingMessage.Joined(
                roomCode = json.optString("room"),
                isInitiator = json.optBoolean("initiator", false),
            )

            "peer-joined" -> SignalingMessage.PeerJoined(json.optString("room"))
            "offer" -> SignalingMessage.Offer(json.getString("sdp"))
            "answer" -> SignalingMessage.Answer(json.getString("sdp"))
            "ice" -> SignalingMessage.IceCandidate(
                sdp = json.getString("candidate"),
                sdpMid = json.optString("sdpMid"),
                sdpMLineIndex = json.optInt("sdpMLineIndex"),
            )

            "peer-left" -> SignalingMessage.PeerLeft
            "error" -> SignalingMessage.Error(json.optString("reason"))
            else -> null
        }
    }.onFailure { Log.w(TAG, "unparseable signaling message", it) }.getOrNull()

    companion object {
        private const val TAG = "KolanSignaling"

        /** Room codes are six digits: long enough to avoid collisions, short enough to read out. */
        fun generateRoomCode(): String = (100000..999999).random().toString()

        fun isValidUrl(url: String): Boolean =
            url.startsWith("ws://") || url.startsWith("wss://")

        fun isValidRoomCode(code: String): Boolean =
            code.length == 6 && code.all { it.isDigit() }
    }
}
