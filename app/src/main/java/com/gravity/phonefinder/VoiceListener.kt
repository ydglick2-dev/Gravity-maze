package com.gravity.phonefinder

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Keeps Android's speech recognizer running in a loop and reports every phrase it
 * hears — partial results included, so the alarm fires without waiting for the
 * speaker to finish the sentence.
 *
 * The recognizer stops itself after every utterance, silence timeout or error, so
 * the loop here is what makes listening continuous.
 */
class VoiceListener(
    private val context: Context,
    private val onPhrase: (String) -> Unit,
    /** Human-readable status (heard text / errors) for on-screen testing feedback. */
    private val onDiagnostic: (String) -> Unit = {},
) {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var sessionActive = false
    private var networkBackoffMs = INITIAL_BACKOFF_MS
    private var muted = false

    /** Muting the recognizer's own start/stop beeps, which would otherwise chirp all night. */
    var suppressBeeps = true

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "no speech recognition service on this device")
            return
        }
        running = true
        networkBackoffMs = INITIAL_BACKOFF_MS
        beginSession()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        sessionActive = false
        destroyRecognizer()
        unmuteBeeps()
    }

    // --- recognition loop --------------------------------------------------

    private fun beginSession() {
        if (!running || sessionActive) return
        val engine = recognizer ?: createRecognizer() ?: run {
            scheduleRestart(networkBackoff())
            return
        }
        muteBeeps()
        sessionActive = true
        try {
            engine.startListening(recognitionIntent())
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed: ${e.message}")
            sessionActive = false
            recreateRecognizer()
            scheduleRestart(SHORT_RETRY_MS)
        }
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
    } catch (e: Exception) {
        Log.e(TAG, "cannot create recognizer", e)
        null
    }

    private fun recreateRecognizer() {
        destroyRecognizer()
        createRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Deliberately NOT forcing EXTRA_PREFER_OFFLINE: on devices without the
            // Hebrew offline pack that flag makes many recognizers return nothing
            // instead of falling back to the network model. Letting the system choose
            // uses the online model when there is internet (more accurate) and the
            // offline model when the pack is present.
        }

    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private val restartRunnable = Runnable { beginSession() }

    private fun networkBackoff(): Long {
        val delay = networkBackoffMs
        networkBackoffMs = (networkBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        return delay
    }

    // --- beep suppression --------------------------------------------------

    private fun muteBeeps() {
        if (!suppressBeeps || muted) return
        muted = true
        setMuted(true)
    }

    private fun unmuteBeeps() {
        if (!muted) return
        muted = false
        setMuted(false)
    }

    private fun setMuted(mute: Boolean) {
        val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (stream in intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)) {
            // Blocked while Do Not Disturb is on unless the app holds policy access.
            runCatching { audioManager.adjustStreamVolume(stream, direction, 0) }
        }
    }

    // --- callbacks ---------------------------------------------------------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            deliver(partialResults)
        }

        override fun onResults(results: Bundle?) {
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { !it.isNullOrBlank() }
            if (best != null) onDiagnostic("שמעתי: $best")
            deliver(results)
            sessionActive = false
            networkBackoffMs = INITIAL_BACKOFF_MS
            scheduleRestart(SHORT_RETRY_MS)
        }

        override fun onError(error: Int) {
            sessionActive = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> {
                    // Ordinary silence. Go straight back to listening.
                    networkBackoffMs = INITIAL_BACKOFF_MS
                    scheduleRestart(SHORT_RETRY_MS)
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                -> {
                    recreateRecognizer()
                    scheduleRestart(RECREATE_RETRY_MS)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.e(TAG, "microphone permission missing — stopping")
                    onDiagnostic("אין הרשאת מיקרופון")
                    stop()
                }

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                -> {
                    // The Hebrew model is missing and cannot be reached. Retry on the
                    // network model rather than dying silently.
                    Log.w(TAG, "Hebrew model unavailable (error $error)")
                    onDiagnostic("חבילת עברית חסרה — נסה עם אינטרנט או התקן זיהוי עברית")
                    scheduleRestart(networkBackoff())
                }

                else -> {
                    // Network or server trouble: back off so a broken state does not
                    // spin the CPU and drain the battery overnight.
                    Log.w(TAG, "recognizer error $error")
                    if (error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    ) {
                        onDiagnostic("שגיאת רשת בזיהוי — צריך אינטרנט או חבילת עברית אופליין")
                    }
                    scheduleRestart(networkBackoff())
                }
            }
        }
    }

    private fun deliver(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (phrase in matches) {
            if (!phrase.isNullOrBlank()) onPhrase(phrase)
        }
    }

    private companion object {
        const val TAG = "PhoneFinder/Voice"
        const val LANGUAGE = "he-IL"
        const val SHORT_RETRY_MS = 300L
        const val RECREATE_RETRY_MS = 1_000L
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
