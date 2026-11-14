package com.dk.assistant

import android.content.Context
import android.media.AudioManager

/**
 * 14 नवंबर 2026 (मालिक): "माइक दबाने पर बार-बार टू-टू करके आवाज़ नहीं आनी चाहिए"।
 * बोलकर लिखते समय (सिर्फ़ जब आपने माइक दबाया) फ़ोन की बीप वाली धाराएँ चुप, माइक रुकते ही वापस वैसी ही.
 * गाना/वीडियो चल रहा हो तो उसकी आवाज़ कभी नहीं छेड़ते.
 */
object QuietMic {
    private var count = 0
    private val muted = mutableListOf<Int>()
    private val h by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var pending: Runnable? = null

    /** कौन-सी धाराएँ चुप करनी हैं (गाना चल रहा हो तो MUSIC नहीं) - JVM test के लिए */
    fun streams(musicPlaying: Boolean): List<Int> =
        if (musicPlaying) listOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        else listOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)

    @Synchronized fun on(c: Context) {
        count++
        if (count > 1) return
        // अभी-अभी रुका था और वापस आवाज़ लौटने वाली थी -> वही चुप्पी जारी रखो
        pending?.let { h.removeCallbacks(it); pending = null; if (muted.isNotEmpty()) return }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val playing = try { am.isMusicActive } catch (_: Exception) { true }
        for (s in streams(playing)) {
            try {
                if (am.isStreamMute(s)) continue
                am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                muted.add(s)
            } catch (_: Exception) {}
        }
    }

    @Synchronized fun off(c: Context) {
        if (count == 0) return
        count--
        if (count > 0) return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // बीप की आख़िरी आवाज़ निकल जाने दो, फिर वापस
        val r = Runnable {
            synchronized(this) {
                if (count == 0) {
                    for (s in muted) try { am?.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
                    muted.clear()
                }
                pending = null
            }
        }
        pending = r
        h.postDelayed(r, 400)
    }
}
