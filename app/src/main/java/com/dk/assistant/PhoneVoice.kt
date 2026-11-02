package com.dk.assistant

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * मालिक (नवंबर 2026, log 11:09): बैकअप मोड में Edge TTS भी नहीं जुड़ा (फ़ोन से Microsoft के server तक रास्ता अटका)
 * तो DK पूरी तरह चुप था. अब सिर्फ़ बैकअप मोड में, Piper (नवंबर 2026 से Edge की जगह) न चले तो आख़िरी सहारा = फ़ोन की अपनी हिंदी आवाज़
 * (Android TextToSpeech, बिना इंटरनेट भी). आम (DK लाइव) मोड में यह कभी नहीं बजता.
 */
object PhoneVoice {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready: CompletableDeferred<Boolean>? = null
    @Volatile private var cur: CompletableDeferred<Unit>? = null

    private fun init(c: Context): CompletableDeferred<Boolean> {
        ready?.let { return it }
        val d = CompletableDeferred<Boolean>()
        ready = d
        try {
            var t: TextToSpeech? = null
            t = TextToSpeech(c.applicationContext) { st ->
                val ok = st == TextToSpeech.SUCCESS && t?.let { e ->
                    val r = e.setLanguage(Locale("hi", "IN"))
                    r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                } == true
                if (!ok) DKLog.d("फ़ोन की आवाज़: हिंदी आवाज़ फ़ोन में नहीं मिली (status=$st)")
                d.complete(ok)
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { cur?.complete(Unit) }
                @Deprecated("old API") override fun onError(id: String?) { cur?.complete(Unit) }
                override fun onError(id: String?, code: Int) { cur?.complete(Unit) }
                override fun onStop(id: String?, interrupted: Boolean) { cur?.complete(Unit) }
            })
            tts = t
        } catch (e: Exception) { DKLog.d("फ़ोन की आवाज़ शुरू नहीं हुई: ${e.message}"); d.complete(false) }
        return d
    }

    /** बोलो और खत्म होने तक रुको. false = फ़ोन की आवाज़ भी नहीं चली */
    suspend fun say(c: Context, text: String): Boolean {
        if (text.isBlank()) return false
        val ok = withTimeoutOrNull(4_000) { init(c).await() } ?: false
        if (!ok) { ready = null; return false }
        val t = tts ?: return false
        val done = CompletableDeferred<Unit>()
        cur = done
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "dk-${System.nanoTime()}")
        if (r != TextToSpeech.SUCCESS) return false
        withTimeoutOrNull(30_000L + text.length * 120L) { done.await() }
        return true
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
        cur?.complete(Unit)
    }
}
