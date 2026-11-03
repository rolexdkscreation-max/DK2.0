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

    @Volatile private var armedAt = 0L

    fun arm(now: Long = System.currentTimeMillis()) { armedAt = now }

    /** अभी बजाएँ? (बुलाने के बाद पहली बार जुड़ा) - pure, JUnit से जाँच */
    fun due(armedAt: Long, now: Long, inCall: Boolean, enabled: Boolean): Boolean =
        enabled && !inCall && armedAt > 0L && now - armedAt in 0..ARM_MS

    fun connected(c: Context, inCall: Boolean) {
        val now = System.currentTimeMillis()
        if (!due(armedAt, now, inCall, Prefs.bool(c, "live_chime", true))) return
        armedAt = 0L
        Thread {
            try {
                // "Yes Boss" अभी बज रहा हो = उसके खत्म होने तक रुको (ज़्यादा से ज़्यादा 2.5s)
                val until = System.currentTimeMillis() + InvokeAck.ACK_DEAF_MAX_MS + 300
                while (InvokeAck.deafNow() && System.currentTimeMillis() < until) Thread.sleep(40)
                if (DKState.mode == Mode.SPEAKING) return@Thread
                val pcm = pcm()
                InvokeAck.deafUntil = System.currentTimeMillis() + durationMs() + 150
                play(pcm)
                DKLog.d("Live जुड़ा - हल्की आवाज़ बजी")
            } catch (_: Exception) {}
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

    private fun play(pcm: ShortArray) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
            Thread.sleep(durationMs() + 80L)
        } finally { try { t.stop() } catch (_: Exception) {}; t.release() }
    }
}
