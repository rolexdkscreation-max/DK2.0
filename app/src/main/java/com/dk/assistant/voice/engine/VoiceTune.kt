package com.dk.assistant.voice.engine

import android.content.Context
import android.media.AudioTrack
import android.media.PlaybackParams

/**
 * v3.80: आवाज़ की गति (speed) और पिच - एक ही जगह, जो भी आवाज़ इंजन चालू हो (DK लाइव की सीधी आवाज़,
 * Gemini TTS, Edge) उसी पर. बजाने वाले AudioTrack पर लगती है -> बदलते ही तुरंत, चलती बातचीत में भी.
 *
 * प्रतिशत में: 100 = सामान्य. गति 70-130, पिच 80-120 (इससे बाहर आवाज़ बिगड़ती है).
 */
object VoiceTune {
    const val MIN_SPEED = 70
    const val MAX_SPEED = 130
    const val MIN_PITCH = 80
    const val MAX_PITCH = 120
    const val NORMAL = 100

    @Volatile var speedPct = NORMAL
        private set
    @Volatile var pitchPct = NORMAL
        private set
    /** बदला तो गिनती - हर AudioTrack अपना लगाया version याद रखता है */
    @Volatile var version = 0
        private set
    @Volatile private var loaded = false

    fun clampSpeed(p: Int) = p.coerceIn(MIN_SPEED, MAX_SPEED)
    fun clampPitch(p: Int) = p.coerceIn(MIN_PITCH, MAX_PITCH)

    fun load(c: Context) {
        if (loaded) return
        val sp = VoiceEngines.sp(c)
        speedPct = clampSpeed(sp.getInt("tune_speed", NORMAL)); pitchPct = clampPitch(sp.getInt("tune_pitch", NORMAL))
        loaded = true; version++
    }

    fun setSpeed(c: Context, pct: Int) {
        val v = clampSpeed(pct); if (loaded && v == speedPct) return
        VoiceEngines.sp(c).edit().putInt("tune_speed", v).apply(); speedPct = v; loaded = true; version++
    }

    fun setPitch(c: Context, pct: Int) {
        val v = clampPitch(pct); if (loaded && v == pitchPct) return
        VoiceEngines.sp(c).edit().putInt("tune_pitch", v).apply(); pitchPct = v; loaded = true; version++
    }

    fun reset(c: Context) { setSpeed(c, NORMAL); setPitch(c, NORMAL) }

    fun speed(): Float = speedPct / 100f
    fun pitch(): Float = pitchPct / 100f

    /** PCM के इतने bytes बजने में असली समय (गति के साथ) */
    fun playMs(bytes: Int, rate: Int, speedPct: Int = this.speedPct): Long =
        if (rate <= 0) 0L else bytes.toLong() * 1000L * 100L / (rate.toLong() * 2L * speedPct.coerceAtLeast(1))

    /** "गति 100% • पिच 100%" */
    fun label(speed: Int = speedPct, pitch: Int = pitchPct): String = "गति $speed% • पिच $pitch%"

    /**
     * इस track पर अभी की गति/पिच लगाओ (बदली हो तभी). लौटाता है: लगा version (track के साथ रखो).
     * कुछ फ़ोन पर PlaybackParams न चले -> वैसे ही बजता रहे (टूटे नहीं).
     */
    fun apply(t: AudioTrack, appliedVer: Int): Int {
        val v = version
        if (v == appliedVer) return v
        try {
            t.playbackParams = PlaybackParams().allowDefaults().setSpeed(speed()).setPitch(pitch())
        } catch (_: Exception) {}
        return v
    }
}
