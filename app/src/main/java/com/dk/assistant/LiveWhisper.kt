package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 14 नवंबर 2026 (मालिक: "DK लाइव जो सुनता है, उसमें Whisper का एकदम सही सुनने वाला लगा दो - ताकि एकदम सही सुने और
 * उसी हिसाब से काम हो"): बात खत्म होते ही वही आवाज़ Groq के whisper-large-v3 (सबसे सही, turbo नहीं) से भी लिखवाओ.
 * Whisper के असली शब्द आए = फ़ोन के तुरंत वाले काम (ऐप खोलो, लॉक, टॉर्च…) उसी text से. न आए / देर हो = caption वाला text
 * (पहले जैसा). Groq key DK लाइव की अपनी, न हो तो AI दिमाग में कहीं भी डली Groq key ([Prefs.scopeKey] उधार).
 */
object LiveWhisper {
    const val MODEL = "whisper-large-v3"
    /** इससे ज़्यादा रुके तो caption वाला text (काम में देर न हो) */
    const val WAIT_MS = 1_400L
    /** इतनी छोटी आवाज़ (ms) पर Whisper नहीं - "हाँ" जैसे पर caption ही काफ़ी */
    const val MIN_SPEECH_MS = 500

    fun key(c: Context): String = Prefs.scopeKey(c, ApiScopes.LIVE, "groq")
    fun on(c: Context): Boolean = key(c).isNotBlank()

    /**
     * किसका text मानें (pure - JUnit). Whisper में असली शब्द हों और वह DK की अपनी आवाज़ की गूँज न हो = Whisper,
     * वरना caption.
     */
    fun pick(whisper: String?, caption: String, dkSaid: String = ""): String {
        val w = whisper?.trim()?.trim('"', '.', ' ').orEmpty()
        if (w.isBlank() || !BargeGate.realWords(w)) return caption
        if (dkSaid.isNotBlank() && BargeGate.isEcho(w, dkSaid)) return caption
        return w
    }

    /** आवाज़ के टुकड़े (16 kHz PCM) → Whisper text. key नहीं / छोटी आवाज़ / देर / गलती = null */
    suspend fun hear(c: Context, chunks: List<ByteArray>, speechMs: Int): String? {
        if (chunks.isEmpty() || speechMs < MIN_SPEECH_MS) return null
        val k = key(c).ifBlank { return null }
        val pcm = ByteArray(chunks.sumOf { it.size }).also { out -> var o = 0; chunks.forEach { System.arraycopy(it, 0, out, o, it.size); o += it.size } }
        return withTimeoutOrNull(WAIT_MS) {
            withContext(Dispatchers.IO) {
                try { Transcriber.accurate(k, GeminiTts.pcmToWav(MicBoost.normalize(pcm), GeminiLive.IN_RATE), WAIT_MS) }
                catch (e: Exception) { DKLog.d("Live Whisper: ${e.message?.take(80)}"); null }
            }
        }
    }
}
