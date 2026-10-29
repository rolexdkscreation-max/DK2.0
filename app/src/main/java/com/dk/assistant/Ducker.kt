package com.dk.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * User bole to peeche chal raha gaana / video DHEEMA (~20%), DK ka jawab khatam hone ke baad dheere-dheere WAPAS.
 *
 * Tarika: Android ka "transient may-duck" audio focus. Isme Android khud doosre apps ki awaaz
 * smooth fade (VolumeShaper ramp) ke saath ~20% par laata hai aur focus chhodne par wapas usi smooth fade se badhata hai.
 * Phone ki master volume ko haath nahi lagate - isliye DK ki apni awaaz dheemi nahi hoti aur user ki volume setting nahi badalti.
 * (Kuch apps - jaise podcast / kuch video player - dheema hone ki jagah khud thodi der ruk jaate hain; yeh us app ka niyam hai.)
 */
object Ducker {
    private val h = Handler(Looper.getMainLooper())
    @Volatile var active = false
        private set
    private var req: AudioFocusRequest? = null
    private var am: AudioManager? = null
    private var since = 0L
    private var quietCheck: (() -> Boolean)? = null

    /** Itni der se zyada kabhi dheema nahi rakhenge (bhool-chook se gaana hamesha dheema na rahe) */
    // v3.6: 90s -> 60s (owner: "DK चालू करते ही सारी आवाज़ कम" - बात रुकने पर भी देर तक धीमा रहता था)
    private const val MAX_HOLD_MS = DuckRule.MAX_HOLD_MS
    private const val AFTER_REPLY_MS = 1_200L

    private val watchdog = Runnable { if (active) { DKLog.d("Background awaaz: ${MAX_HOLD_MS / 1000}s ho gaye - wapas badha rahe"); doRelease() } }
    private val releaser = object : Runnable {
        override fun run() {
            // DK abhi bhi bol raha hai -> thoda aur ruko
            if (quietCheck?.invoke() == false) { h.postDelayed(this, 300); return }
            doRelease()
        }
    }

    /** फ़ोन पर गाना/वीडियो अभी बज रहा है */
    fun musicActive(c: Context): Boolean = try {
        (am ?: c.applicationContext.getSystemService(AudioManager::class.java).also { am = it })?.isMusicActive == true
    } catch (_: Exception) { false }

    /** User bolne laga / DK se baat shuru - peeche ki awaaz dheemi karo (pehle se dheemi ho to bas "wapas badhana" radd) */
    fun duck(c: Context, why: String) {
        h.post {
            h.removeCallbacks(releaser)
            if (!Prefs.duckMedia(c)) return@post
            if (active) return@post
            val a = am ?: c.applicationContext.getSystemService(AudioManager::class.java).also { am = it } ?: return@post
            val r = req ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener({ }, h)
                .build().also { req = it }
            val musicOn = try { a.isMusicActive } catch (_: Exception) { false }
            // v3.6: कुछ बज ही नहीं रहा = focus मत लो (फ़ोन की बाकी आवाज़ें/सूचनाएँ बेवजह धीमी न हों)
            if (!DuckRule.shouldDuck(musicOn)) return@post
            val res = try { a.requestAudioFocus(r) } catch (_: Exception) { AudioManager.AUDIOFOCUS_REQUEST_FAILED }
            active = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (active) {
                since = System.currentTimeMillis()
                h.removeCallbacks(watchdog); h.postDelayed(watchdog, MAX_HOLD_MS)
                if (musicOn) DKLog.d("Background awaaz dheemi ($why)")
            }
        }
    }

    /**
     * Baat + jawab khatam: jab [quiet] true ho (DK chup) uske ~1.2s baad awaaz dheere-dheere wapas.
     * Beech me user phir bola (duck) to yeh radd.
     */
    fun releaseWhenQuiet(quiet: () -> Boolean, afterMs: Long = AFTER_REPLY_MS) {
        h.post {
            if (!active) return@post
            quietCheck = quiet
            h.removeCallbacks(releaser)
            h.postDelayed(releaser, afterMs)
        }
    }

    /** Turant wapas (Live band, call aaya, app band) */
    fun releaseNow() { h.post { h.removeCallbacks(releaser); doRelease() } }

    private fun doRelease() {
        h.removeCallbacks(watchdog)
        if (!active) return
        active = false
        try { req?.let { am?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        DKLog.d("Background awaaz wapas (${(System.currentTimeMillis() - since) / 1000}s dheemi rahi)")
    }
}
