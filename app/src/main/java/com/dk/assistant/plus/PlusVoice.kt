package com.dk.assistant.plus

import android.content.Context
import android.speech.tts.TextToSpeech
import com.dk.assistant.DKLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * 15 नवंबर 2026: छोटी घोषणाएँ (चार्जर लगा, SOS भेजा, ड्राइविंग मोड ...) फ़ोन की अपनी (Native/Android) TTS से -
 * कोई API key / internet नहीं चाहिए, DK लाइव की चलती बातचीत से अलग. पहली बार में TTS तैयार न हो तो बात कतार में.
 *
 * 20 नवंबर 2026 (मालिक: "चार्जिंग / Wi-Fi वाला ट्रिगर अभी फ़ोन का TTS बोलता है - वहाँ भी यही ऑफ़लाइन वाला बोले"):
 * पहले DK की ऑफ़लाइन आवाज़ ([com.dk.assistant.voice.st3.OfflineVoice]); वह डाउनलोड नहीं / न चले = पहले जैसा फ़ोन का TTS.
 * घोषणाएँ कतार में, एक के बाद एक ([say] तुरंत लौटता है).
 */
object PlusVoice {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    private val order = kotlinx.coroutines.sync.Mutex()
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayList<String>()

    fun say(c: Context, text: String) {
        if (text.isBlank()) return
        val app = c.applicationContext
        if (!com.dk.assistant.voice.st3.OfflineVoice.ready(app)) { phone(app, text); return }
        scope.launch {
            val ok = order.withLock { com.dk.assistant.voice.st3.OfflineVoice.say(app, text) }
            if (!ok) android.os.Handler(android.os.Looper.getMainLooper()).post { phone(app, text) }
        }
    }

    /** फ़ोन की अपनी (Android) आवाज़ - पहले जैसा */
    private fun phone(c: Context, text: String) {
        // 16 नवंबर 2026: हिंदी आवाज़ में रोमन हिंग्लिश शब्द टूटे नहीं
        @Suppress("NAME_SHADOWING") val text = com.dk.assistant.voice.engine.HinglishText.toSpeakable(text)
        val t = tts
        if (t != null && ready) { t.speak(text, TextToSpeech.QUEUE_ADD, null, "dk_plus_${System.nanoTime()}"); return }
        synchronized(pending) { pending += text }
        if (t == null) {
            tts = TextToSpeech(c.applicationContext) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (!ready) { DKLog.d("PlusVoice: TTS शुरू नहीं हुआ ($st)"); return@TextToSpeech }
                tts?.language = Locale("hi", "IN")
                val list = synchronized(pending) { pending.toList().also { pending.clear() } }
                list.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "dk_plus_${System.nanoTime()}") }
            }
        }
    }
}
