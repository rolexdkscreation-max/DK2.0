package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** FREE speech-to-text: Android ka SpeechRecognizer (Google app). Hindi + English dono samajhta hai. */
class VoiceListener(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Ek baar sunta hai aur bola hua text return karta hai (ya null). */
    suspend fun listenOnce(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val finished = AtomicBoolean(false)

            fun finish(result: String?) {
                if (finished.compareAndSet(false, true)) {
                    main.post { try { recognizer.destroy() } catch (_: Exception) {} }
                    if (cont.isActive) cont.resume(result)
                }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    finish(text)
                }
                override fun onError(error: Int) {
                    DKLog.d("Mic error code: $error")
                    finish(null)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            recognizer.startListening(intent)
            cont.invokeOnCancellation { finish(null) }
        }
    }
}
