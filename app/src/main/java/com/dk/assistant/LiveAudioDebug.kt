package com.dk.assistant

import android.util.Log

/**
 * नवंबर 2026: Live आवाज़ की जाँच - `adb logcat -s DK_AUDIO_DEBUG`.
 * हर chunk पर log नहीं (Live में सेकंड में ~25 chunk) - हर बारी का पहला, फिर हर 50वाँ; गलतियाँ हमेशा (पर एक ही बात सेकंड में एक बार).
 */
object LiveAudioDebug {
    private const val TAG = LiveAudioGuard.TAG
    @Volatile private var chunks = 0L
    @Volatile private var lastWarn = ""
    @Volatile private var lastWarnAt = 0L

    fun d(msg: String) { try { Log.d(TAG, msg) } catch (_: Throwable) {} }

    fun w(msg: String) {
        val now = System.currentTimeMillis()
        if (msg == lastWarn && now - lastWarnAt < 1_000) return
        lastWarn = msg; lastWarnAt = now
        try { Log.w(TAG, msg) } catch (_: Throwable) {}
    }

    /** server से आवाज़ का टुकड़ा आया */
    fun chunk(bytes: Int, rate: Int, queued: Int) {
        val n = ++chunks
        if (rate != GeminiLive.OUT_RATE) w("chunk rate $rate Hz पर player $OUT_HZ Hz - आवाज़ तेज़/धीमी लगेगी")
        if (n == 1L || n % 50 == 0L || queued > 100) d("chunk #$n: $bytes bytes (${bytes * 1000 / (rate * 2).coerceAtLeast(1)} ms) • कतार $queued")
    }

    private val OUT_HZ get() = GeminiLive.OUT_RATE
}
