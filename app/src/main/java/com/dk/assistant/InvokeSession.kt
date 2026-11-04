package com.dk.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * नवंबर 2026 (मालिक): power बटन से DK = Google Assistant जैसी एक बार की बातचीत.
 * पहले power बटन Live को हमेशा के लिए चालू कर देता था - फिर DK बंद दिखने पर भी हर बात सुनकर जवाब देता था.
 * अब: बुलाया -> "Yes Boss" -> बात -> [IDLE_MS] चुप = Live बंद, माइक वापस सिर्फ़ "Hey DK / OK DK / Hello DK" सुनता है.
 * सिर्फ़ तब जब "हमेशा सुनो (wake word)" चालू है (वरना पहले जैसा). Live का बटन खुद दबाया = हमेशा वाला Live, पहले जैसा.
 */
object InvokeSession {
    /** इतनी देर कोई बात नहीं = बातचीत खत्म (Hey DK वाले जैसा 30s) */
    const val IDLE_MS = 30_000L

    fun useSession(fromPowerButton: Boolean, settingOn: Boolean, wakeMode: Boolean): Boolean =
        fromPowerButton && settingOn && wakeMode
}

/**
 * नवंबर 2026 (मालिक): "Gemini Live जुड़ते ही एक अलग-सी, हल्की आवाज़ आए - पता चल जाए कि जुड़ गया".
 * DK बुलाया (power बटन / Hey DK) [arm] -> Live का setup पूरा [connected] = "Yes Boss" खत्म होने के बाद दो छोटे, धीमे सुर (ऊपर जाते).
 * बीच बातचीत में चुपचाप दोबारा जुड़ने पर नहीं बजता (सिर्फ़ बुलाने के [ARM_MS] अंदर, एक बार).
 * बजते समय माइक की आवाज़ Gemini को नहीं जाती (InvokeAck.deafUntil) - वरना Gemini इसे बोली समझे.
 */
object LiveChime {
    /** बुलाने के बाद इतनी देर तक का जुड़ना ही "जुड़ गया" माना जाए */
    const val ARM_MS = 25_000L
    const val RATE = 24_000
    /** दो सुर: G5 -> C6 (हल्का, ऊपर जाता - "तैयार") */
    val NOTES = listOf(784.0 to 85, 1046.5 to 130)
    const val GAP_MS = 25
    const val VOLUME = 0.22

    /**
     * नवंबर 2026 (मालिक: "power बटन से खोलूँ तो कभी-कभी जुड़ने की आवाज़ नहीं आती"). तीन जड़ें:
     * 1) usage SONIFICATION था = फ़ोन silent / vibrate / DND हो तो Android उसे चुप कर देता है ("Yes Boss" MEDIA पर
     *    बजता था, इसलिए वह आता था, यह नहीं). अब [USAGE] = MEDIA - जो आवाज़ "Yes Boss" की, वही इसकी.
     * 2) पुराना (parked) connection तुरंत जुड़ जाए तो यह "Yes Boss" शुरू होने से पहले ही बज जाता था और ack उसके
     *    ऊपर बजकर उसे दबा देता था. अब [InvokeAck.expectNow] - ack आने वाला है तो उसके खत्म होने तक रुको.
     * 3) Bluetooth / ठंडा speaker शुरू के ~100 ms खा जाता है (0.24 s की आवाज़ लगभग पूरी गायब). अब [LEAD_MS] चुप्पी पहले.
     */
    val USAGE = AudioAttributes.USAGE_MEDIA
    /** बजाने से पहले इतनी चुप्पी (Bluetooth / speaker जागे, सुर कटे नहीं) */
    const val LEAD_MS = 120
    /** दो बार बुलाने पर (session + activity) दो बार न बजे */
    const val REPEAT_GAP_MS = 4_000L

    @Volatile private var armedAt = 0L
    @Volatile private var playedAt = 0L

    fun arm(now: Long = System.currentTimeMillis()) { armedAt = now }

    /** अभी बजाएँ? (बुलाने के बाद पहली बार जुड़ा) - pure, JUnit से जाँच */
    fun due(armedAt: Long, now: Long, inCall: Boolean, enabled: Boolean): Boolean =
        enabled && !inCall && armedAt > 0L && now - armedAt in 0..ARM_MS

    fun connected(c: Context, inCall: Boolean) {
        val now = System.currentTimeMillis()
        if (!due(armedAt, now, inCall, Prefs.bool(c, "live_chime", true))) return
        if (now - playedAt < REPEAT_GAP_MS) return
        armedAt = 0L; playedAt = now
        Thread {
            try {
                // "Yes Boss" अभी बज रहा हो / बजने वाला हो = उसके खत्म होने तक रुको (ज़्यादा से ज़्यादा ~3s)
                val until = System.currentTimeMillis() + InvokeAck.ACK_DEAF_MAX_MS + InvokeAck.EXPECT_MS
                while ((InvokeAck.deafNow() || InvokeAck.expectNow()) && System.currentTimeMillis() < until) Thread.sleep(40)
                if (DKState.mode == Mode.SPEAKING) { DKLog.d("Live जुड़ा - DK बोल रहा था, जुड़ने की आवाज़ नहीं बजाई"); return@Thread }
                val pcm = pcm()
                InvokeAck.deafUntil = System.currentTimeMillis() + LEAD_MS + durationMs() + 150
                play(pcm)
                DKLog.d("Live जुड़ा - हल्की आवाज़ बजी")
            } catch (e: Exception) { DKLog.d("Live जुड़ने की आवाज़ नहीं बजी: ${e.message}") }
        }.start()
    }

    fun durationMs(): Int = NOTES.sumOf { it.second } + GAP_MS * (NOTES.size - 1)

    /** 16-bit mono PCM - हर सुर में धीरे शुरू, धीरे खत्म (क्लिक नहीं) */
    fun pcm(): ShortArray {
        val out = ArrayList<Short>()
        NOTES.forEachIndexed { i, (hz, ms) ->
            val n = RATE * ms / 1000
            val fade = (RATE * 0.012).toInt()
            for (k in 0 until n) {
                val env = minOf(1.0, k / fade.toDouble(), (n - k) / (fade * 4.0))
                // मुख्य सुर + हल्का ऊपरी सुर = घंटी जैसा नरम
                val v = (sin(2 * PI * hz * k / RATE) * 0.85 + sin(4 * PI * hz * k / RATE) * 0.15) * env * VOLUME
                out.add((v * Short.MAX_VALUE).toInt().toShort())
            }
            if (i < NOTES.size - 1) repeat(RATE * GAP_MS / 1000) { out.add(0) }
        }
        return out.toShortArray()
    }

    /** [LEAD_MS] चुप्पी + सुर (pure, JUnit) */
    fun withLead(p: ShortArray): ShortArray = ShortArray(RATE * LEAD_MS / 1000 + p.size).also { System.arraycopy(p, 0, it, RATE * LEAD_MS / 1000, p.size) }

    private fun play(pcm0: ShortArray) {
        val pcm = withLead(pcm0)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(USAGE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            val n = t.write(pcm, 0, pcm.size)
            if (n < pcm.size) throw IllegalStateException("AudioTrack ने सिर्फ़ $n/${pcm.size} लिया")
            t.play()
            Thread.sleep(LEAD_MS + durationMs() + 80L)
        } finally { try { t.stop() } catch (_: Exception) {}; t.release() }
    }
}
