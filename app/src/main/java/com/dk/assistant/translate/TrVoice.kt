package com.dk.assistant.translate

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.GeminiTts
import com.dk.assistant.Quiet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 25 नवंबर 2026 (मालिक: "Gemini की आवाज़ न बने तो Gemini Live, वह भी न बने तो ऑफ़लाइन TTS - आवाज़ लेकिन रुकनी नहीं चाहिए"):
 * DK ट्रांसलेटर में अनुवाद **हमेशा बोलकर** सुनाई दे.
 *
 * क्रम (सबसे तेज़ पहले, ताकि जवाब तुरंत आए):
 *  1. Gemini Live की सीधी आवाज़ - अनुवाद के साथ ही आती है (कोई अलग इंतज़ार नहीं).
 *  2. Live ने लिखा हुआ अनुवाद दिया पर आवाज़ नहीं (या बीच में टूटा) = **Gemini TTS** (DK लाइव की keys) - छोटा इंतज़ार.
 *  3. वह भी न चले / limit / धीमा = **फ़ोन की ऑफ़लाइन आवाज़** ([TrTts]) - चुप कभी नहीं.
 */
object TrVoice {
    enum class Src { LIVE, GEMINI_TTS, PHONE }

    /** Gemini TTS का इंतज़ार इससे ज़्यादा नहीं - फिर फ़ोन की आवाज़ */
    const val GEMINI_WAIT_MS = 3500L
    /** लिखा अनुवाद आ गया, आवाज़ नहीं आई, और इतनी देर से कुछ नया नहीं = Live की आवाज़ नहीं बनेगी, आगे बढ़ो */
    const val NO_AUDIO_MS = 1300L

    /** बोलने वाले का ठहराव (ms) - इतनी चुप्पी = बात खत्म, तुरंत अनुवाद (पहले 480) */
    const val END_SILENCE_MS = 320
    /** लंबी बात में छोटे ठहराव पर ही टुकड़ा भेज दो (पहले 4.5 s) */
    const val LONG_SPEECH_MS = 3500
    const val SHORT_PAUSE_MS = 160
    /** इससे लंबी बात बिना ठहराव = टुकड़ा भेजो (पहले 9 s) */
    const val MAX_SPEECH_MS = 8000

    /** माइक: अभी बात का टुकड़ा खत्म मानें? (Live को audioStreamEnd) */
    fun turnEnds(spokeMs: Int, silentMs: Int): Boolean =
        silentMs >= END_SILENCE_MS || (spokeMs >= LONG_SPEECH_MS && silentMs >= SHORT_PAUSE_MS) || spokeMs >= MAX_SPEECH_MS

    /** Live की आवाज़ न आए तो अगला कौन (क्रम में) */
    fun fallbacks(hasKey: Boolean, online: Boolean, geminiBlocked: Boolean): List<Src> =
        (if (hasKey && online && !geminiBlocked) listOf(Src.GEMINI_TTS) else emptyList()) + Src.PHONE

    /** इस turn में दूसरे रास्ते से बोलना है? (Live की आवाज़ नहीं आई, अनुवाद है, पहले से नहीं बोला, हिंदी वाली बात फेंकी नहीं गई) */
    fun needFallback(gotAudio: Boolean, text: String, alreadySpoken: Boolean, dropped: Boolean): Boolean =
        !gotAudio && !alreadySpoken && !dropped && text.isNotBlank()

    /** भाषा code -> फ़ोन TTS का bcp (न मिले = अंग्रेज़ी) */
    fun bcp(code: String): String = TrLang.of(code)?.bcp ?: "en-IN"
}

/** [TrVoice] का असली बोलने वाला: Gemini TTS -> फ़ोन की आवाज़. एक-एक करके (क्रम न बिगड़े), अलग thread पर. */
class TrSpeak(c: Context, private val onNote: (String) -> Unit) {
    private val app = c.applicationContext
    private val tts = TrTts(app, onNote)
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-tr-voice").apply { isDaemon = true } }
    private val gem = Executors.newCachedThreadPool { r -> Thread(r, "dk-tr-gtts").apply { isDaemon = true } }
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)

    /** अभी कुछ बनना / बजना बाकी */
    fun pending(): Boolean = queued.get() > 0 || tts.pending()

    fun speak(text: String, code: String, out: TrAudio.Out, keys: List<String>, online: Boolean) {
        if (text.isBlank()) return
        queued.incrementAndGet()
        work.execute {
            try { speakNow(text, code, out, keys, online) } catch (e: Exception) { Quiet.note(e) } finally { queued.decrementAndGet() }
        }
    }

    private fun speakNow(text: String, code: String, out: TrAudio.Out, keys: List<String>, online: Boolean) {
        val blocked = keys.isNotEmpty() && GeminiTts.allBlockedUntil(keys) > System.currentTimeMillis()
        for (src in TrVoice.fallbacks(keys.isNotEmpty(), online, blocked)) {
            when (src) {
                TrVoice.Src.GEMINI_TTS -> {
                    val pcm = try {
                        gem.submit<ByteArray?> { TrTts.Wav.parse(GeminiTts.synthesizeAny(keys, text, TrEngine.VOICE)) }
                            .get(TrVoice.GEMINI_WAIT_MS, TimeUnit.MILLISECONDS)
                    } catch (e: Exception) { Quiet.note(e); DKLog.d("Translator: Gemini TTS नहीं चला (${e.javaClass.simpleName}) - फ़ोन की आवाज़"); null }
                    if (pcm != null && pcm.isNotEmpty()) { out.play(pcm); return }
                }
                TrVoice.Src.PHONE -> { if (!tts.say(text, TrVoice.bcp(code), out)) onNote("आवाज़ नहीं बन पाई - अनुवाद ऊपर लिखा है"); return }
                TrVoice.Src.LIVE -> Unit
            }
        }
    }

    fun close() {
        work.shutdownNow(); gem.shutdownNow()
        tts.close()
    }
}
