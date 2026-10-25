package com.dk.assistant

import android.content.Context
import com.dk.assistant.voice.engine.VoiceEngines

/**
 * v3.71: ऐप खुलते ही चुने हुए आवाज़ इंजन का कनेक्शन तैयार (Gemini TTS = Worker का /ping, TLS + HTTP/2 खुला रहे;
 * Edge = WebSocket खुला). इंजन अपना OkHttp client/connection pool दोबारा इस्तेमाल करते हैं, इसलिए पहले वाक्य पर
 * हाथ मिलाने (DNS + TLS) की देरी नहीं लगती. 20 s में एक बार से ज़्यादा नहीं (बैटरी/डेटा).
 */
object VoiceWarm {
    @Volatile private var lastAt = 0L

    fun shouldWarm(now: Long, last: Long, gapMs: Long = 20_000): Boolean = last == 0L || now - last >= gapMs

    fun onAppOpen(c: Context) {
        val now = System.currentTimeMillis()
        if (!shouldWarm(now, lastAt)) return
        lastAt = now
        val app = c.applicationContext
        Thread({
            try {
                val e = VoiceEngines.active(app) ?: return@Thread
                if (e.notReady(app) == null) e.warm(app)
                // बँटवारे में इंजन न चले तो जो सहारा बजेगा, वह भी तैयार
                VoiceEngines.fallback()?.takeIf { it.id != e.id && it.notReady(app) == null }?.warm(app)
            } catch (_: Exception) {}
        }, "dk-warm-open").apply { isDaemon = true; start() }
    }
}
