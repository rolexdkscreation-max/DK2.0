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
 * तो DK पूरी तरह चुप था. अब सिर्फ़ बैकअप मोड में, Kokoro (नवंबर 2026 से Edge की जगह) न चले तो आख़िरी सहारा = फ़ोन की अपनी हिंदी आवाज़
 * (Android TextToSpeech, बिना इंटरनेट भी). आम (DK लाइव) मोड में यह कभी नहीं बजता.
 *
 * 20 नवंबर 2026 (मालिक: "फ़ोन के TTS की जगह यही ऑफ़लाइन वाला TTS बोले"): [say] पहले DK की ऑफ़लाइन आवाज़
 * ([com.dk.assistant.voice.st3.OfflineVoice], Supertonic 3, फ़ोन पर बनती है). वह डाउनलोड नहीं / न चले = तभी Android TTS.
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

    /** बोलो और खत्म होने तक रुको: पहले ऑफ़लाइन आवाज़, फिर Android TTS. false = दोनों नहीं चलीं */
    suspend fun say(c: Context, text: String): Boolean {
        if (text.isBlank()) return false
        if (com.dk.assistant.voice.st3.OfflineVoice.say(c, text)) return true
        return android(c, text)
    }

    /** सिर्फ़ फ़ोन की अपनी (Android) आवाज़ - ऑफ़लाइन आवाज़ डाउनलोड न हो तब का आख़िरी सहारा */
    suspend fun android(c: Context, text: String): Boolean {
        if (text.isBlank()) return false
        val ok = withTimeoutOrNull(4_000) { init(c).await() } ?: false
        if (!ok) { ready = null; return false }
        val t = tts ?: return false
        val done = CompletableDeferred<Unit>()
        cur = done
        // 16 नवंबर 2026 (मालिक): हिंदी आवाज़ रोमन शब्दों को English पढ़ती थी -> हिंग्लिश के हिंदी शब्द देवनागरी में ([HinglishText]).
        // भावना / स्टाइल बॉक्स + गति/पिच स्लाइडर -> फ़ोन की आवाज़ की गति और पिच ([WordStyle.phone]).
        try { com.dk.assistant.voice.engine.VoiceTune.load(c) } catch (e: Exception) { Quiet.note(e) }
        val (rate, pitch) = com.dk.assistant.voice.engine.WordStyle.phone(
            com.dk.assistant.voice.engine.VoiceTune.speedPct, com.dk.assistant.voice.engine.VoiceTune.pitchPct, styleText(c))
        try { t.setSpeechRate(rate); t.setPitch(pitch) } catch (e: Exception) { Quiet.note(e) }
        val spoken = com.dk.assistant.voice.engine.HinglishText.toSpeakable(text)
        val r = t.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), "dk-${System.nanoTime()}")
        if (r != TextToSpeech.SUCCESS) return false
        withTimeoutOrNull(30_000L + text.length * 120L) { done.await() }
        return true
    }

    /** बोलने का अंदाज़: Jarvis / Venom-Friday का अपना, वरना Settings का "भावना / स्टाइल" बॉक्स + अभी का मूड */
    private fun styleText(c: Context): String = try {
        if (JarvisMode.on(c)) JarvisMode.VOICE_STYLE
        else AssistantPersona.voiceStyle(c) ?: com.dk.assistant.avatar.MoodState.voiceStyle(com.dk.assistant.voice.engine.GeminiTtsEngine.style(c))
    } catch (_: Exception) { "" }

    fun stop() {
        com.dk.assistant.voice.st3.OfflineVoice.stop()
        try { tts?.stop() } catch (e: Exception) { Quiet.note(e) }
        cur?.complete(Unit)
    }
}
