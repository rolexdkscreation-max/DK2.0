package com.dk.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * FREE TTS: Android ka apna TextToSpeech (Google Speech Services ki neural awaaz).
 * Koi paisa nahi, koi API key nahi. Sabse natural Hindi voice khud choose karta hai.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            DKLog.d("TTS start nahi hua (status=$status)")
            ready.complete(false)
            return
        }
        val hindi = Locale("hi", "IN")
        tts.language = hindi
        pickBestVoice(hindi)?.let {
            tts.voice = it
            DKLog.d("Voice: ${it.name} (quality=${it.quality}, network=${it.isNetworkConnectionRequired})")
        }
        tts.setSpeechRate(1.05f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
        })
        ready.complete(true)
    }

    /** Hindi voices me se sabse high quality wali (installed) voice. */
    private fun pickBestVoice(locale: Locale): Voice? {
        val voices = try { tts.voices } catch (e: Exception) { null } ?: return null
        return voices
            .filter { it.locale.language == locale.language }
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenByDescending { it.isNetworkConnectionRequired } // online voice zyada natural hoti hai
            )
            .firstOrNull()
    }

    /** Bolta hai aur bolna khatam hone tak wait karta hai. */
    suspend fun speak(text: String) {
        if (text.isBlank() || !ready.await()) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        withTimeoutOrNull(60_000) { done.await() }
    }

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
