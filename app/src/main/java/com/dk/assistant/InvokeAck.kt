package com.dk.assistant

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.dk.assistant.voice.HeyDk

/**
 * मालिक (02 नवंबर 2026): "power बटन / Hey DK से DK खुले तो Siri जैसा तुरंत साफ़ जवाब, और Live उसी पल जुड़े".
 *
 * power बटन दबाते ही (Live पीछे से जुड़ना शुरू हो चुका होता है - invokedNow) फ़ोन में पहले से बना छोटा
 * "जी सर / हाँ बोलिए" (Hey DK वाले ack, Gemini TTS की आवाज़, बिना इंटरनेट) उसी पल बजता है. बना न हो = छोटी
 * "टिंग" (ToneGenerator) + पीछे से ack बना लेते हैं ताकि अगली बार आवाज़ आए.
 * ack बजते समय माइक की आवाज़ Gemini को नहीं जाती ([deafUntil]) - वरना Gemini DK की अपनी आवाज़ को सवाल समझे.
 * ack खत्म होते ही जो बोलो वह सीधे Live को (जुड़ने तक की बात GeminiLive.pending में रुकी रहती है).
 */
object InvokeAck {
    /** दो बार जल्दी-जल्दी बुलाने (onShow + VoiceActivity) पर दो बार "जी सर" नहीं */
    const val GAP_MS = 4_000L
    /** ack ज़्यादा से ज़्यादा इतना माइक बंद रखे (अटक जाए तो भी माइक खुल जाए) */
    const val ACK_DEAF_MAX_MS = 2_500L
    /** टिंग के समय */
    const val TONE_MS = 160
    const val TONE_DEAF_MS = 260L
    /** ack के बाद गूँज खत्म होने तक */
    const val AFTER_ACK_MS = 150L

    @Volatile var deafUntil = 0L
    /**
     * नवंबर 2026: DK बुलाया - ack (Yes Boss / टिंग) अभी शुरू होने वाला है (IO thread पर). तब तक [LiveChime] रुके -
     * वरना पुराना connection तुरंत जुड़कर chime पहले बजा देता और ack उसके ऊपर बजकर उसे दबा देता.
     */
    @Volatile private var expectUntil = 0L
    const val EXPECT_MS = 1_500L
    fun expect(now: Long = System.currentTimeMillis()) { expectUntil = now + EXPECT_MS }
    fun expectNow(now: Long = System.currentTimeMillis()): Boolean = now < expectUntil
    @Volatile private var lastAt = 0L

    /** ack बजाएँ? कॉल चल रही हो / DK खुद बोल रहा हो (बीच में रोकने को दबाया) / अभी-अभी बजा हो = नहीं */
    fun wanted(mode: Mode, sinceLastMs: Long, inCall: Boolean): Boolean =
        !inCall && mode != Mode.SPEAKING && sinceLastMs >= GAP_MS

    /** अभी माइक की आवाज़ Live को न भेजें? */
    fun deafNow(now: Long = System.currentTimeMillis()): Boolean = now < deafUntil

    /** IO thread पर बुलाओ (ack बजने तक रुकता है ~1 s). @return क्या बजा: "ack" / "tone" / "" */
    fun play(c: Context, inCall: Boolean): String = try { play0(c, inCall) } finally { expectUntil = 0L }

    private fun play0(c: Context, inCall: Boolean): String {
        val now = System.currentTimeMillis()
        if (!wanted(DKState.mode, now - lastAt, inCall)) return ""
        lastAt = now
        deafUntil = now + ACK_DEAF_MAX_MS
        val ack = HeyDk.nextAck(c)
        if (HeyDk.hasAck(c, ack)) {
            DKState.reply(ack)
            val ok = try { HeyDk.playAck(c, ack) } catch (_: Exception) { false }
            deafUntil = System.currentTimeMillis() + AFTER_ACK_MS
            if (ok) return "ack"
        }
        // ack फ़ोन में नहीं बना (पहली बार / आवाज़ बदली) = तुरंत टिंग, ack अगली बार के लिए पीछे से बनाओ
        deafUntil = System.currentTimeMillis() + TONE_DEAF_MS
        tone()
        Thread { try { HeyDk.prepareAcks(c) } catch (_: Exception) {} }.start()
        // Live की अपनी आवाज़ वाला "Yes Boss" - Live बंद होते ही बनेगा (अभी Live जुड़ रहा है, उससे टकराव नहीं)
        com.dk.assistant.voice.LiveAckMaker.ensureAsync(c, "power बटन (टिंग बजी)")
        return "tone"
    }

    private fun tone() {
        try {
            val t = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            t.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS)
            Thread { try { Thread.sleep(TONE_MS + 120L) } catch (_: Exception) {}; t.release() }.start()
        } catch (_: Exception) {}
    }
}

/**
 * नवंबर 2026 (मालिक): "ऐप के अंदर ओर्ब पर टैप करूँ तो फिर से 'जी बॉस' बोलता है और 'जुड़ रहा है' आता है".
 * नियम: अभिवादन ("जी बॉस" / टिंग) + जुड़ने की आवाज़ सिर्फ़ power बटन / assistant से बुलाने पर.
 * ऐप के अंदर: Live पहले से जुड़ा = कुछ नहीं करना; न जुड़ा हो = चुपचाप जोड़ो, हल्की आवाज़ बस एक बार.
 * Pure logic - JVM test ([InvokeEntryTest]).
 */
object InvokeEntry {
    /** ऐप के अंदर: Live चालू और जुड़ा / जुड़ रहा = जैसा है वैसा रहने दो (दोबारा न जोड़ो) */
    fun keepAsIs(liveOn: Boolean, ready: Boolean, running: Boolean): Boolean = liveOn && (ready || running)

    /** ऐप के अंदर जुड़ने की हल्की आवाज़ - पहले कभी नहीं बजी तो ही */
    fun chimeInApp(alreadyChimed: Boolean): Boolean = !alreadyChimed
}
