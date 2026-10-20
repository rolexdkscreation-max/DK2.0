package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * FREE speech-to-text: Android ka SpeechRecognizer (Google app). Hindi + English dono samajhta hai.
 * Ek hi recognizer baar-baar use hota hai (tez), aur lagatar sunne me "beep" ki awaaz dabai jaati hai.
 */
class VoiceListener(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var muted = false
    private var listening = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Aakhri error code (9 = mic permission nahi). */
    @Volatile var lastError: Int = 0
        private set

    var muteBeep = false

    private fun obtain(): SpeechRecognizer =
        recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

    private fun reset() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun mute(on: Boolean) {
        if (on == muted) return
        if (on && audio.isMusicActive) return   // gaana/video chal raha ho to awaaz mat chheduo
        val dir = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (stream in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try { audio.adjustStreamVolume(stream, dir, 0) } catch (_: Exception) {}
        }
        muted = on
    }

    fun release() {
        listening = false
        mute(false)
        reset()
    }

    /** Ek baar sunta hai aur bola hua text return karta hai (ya null). */
    suspend fun listenOnce(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val r = obtain()
            val finished = AtomicBoolean(false)

            fun finish(result: String?, err: Int = 0) {
                if (finished.compareAndSet(false, true)) {
                    lastError = err
                    listening = false
                    // khatam hone wali beep ke baad awaaz wapas
                    main.postDelayed({ if (!listening) mute(false) }, 350)
                    // in errors ke baad recognizer naya banao
                    if (err == SpeechRecognizer.ERROR_CLIENT || err == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        err == SpeechRecognizer.ERROR_SERVER || err == 11 /*SERVER_DISCONNECTED*/) reset()
                    if (cont.isActive) cont.resume(result)
                }
            }

            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    finish(text?.takeIf { it.isNotBlank() })
                }
                override fun onError(error: Int) {
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        DKLog.d("Mic error code: $error")
                    finish(null, error)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    DKState.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { DKState.user("$it…") }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // bolna band karte hi jaldi khatam karo (tez jawab)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            }
            listening = true
            if (muteBeep) mute(true)
            try {
                r.startListening(intent)
            } catch (e: Exception) {
                reset(); finish(null, SpeechRecognizer.ERROR_CLIENT)
            }
            cont.invokeOnCancellation {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { recognizer?.cancel() } catch (_: Exception) {}
                    finish(null)
                }
            }
        }
    }
}
