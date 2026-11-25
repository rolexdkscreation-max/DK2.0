package com.dk.assistant

import android.content.Context

/**
 * 21 नवंबर 2026 (मालिक): "बहुत सारे फ़ोन में स्लाइड थोड़ा सा ऊपर करने पर PIN डालने का हिस्सा आ जाता है, बहुत सारे में
 * थोड़ा और ऊपर करना पड़ता है - Settings में चुन सकें कि DK कितना ऊपर स्लाइड करे."
 * Settings → फ़ोन लॉक / अनलॉक. स्लाइड नीचे से ([START]) ऊपर की ओर, स्क्रीन की ऊँचाई के [pct]% तक.
 * डिफ़ॉल्ट [DEF] = पहले जैसा (85% से 25% तक = 60%). "और ऊपर" चालू = PIN न आए तो अगली कोशिश [STEP_UP]% ज़्यादा ऊपर.
 */
object UnlockSwipe {
    const val PREF_PCT = "unlock_swipe_pct"
    const val PREF_MORE = "unlock_swipe_more"
    const val MIN = 20
    const val MAX = 80
    const val DEF = 60
    const val STEP = 5
    const val STEP_UP = 10
    /** स्लाइड कहाँ से शुरू (स्क्रीन की ऊँचाई का हिस्सा) */
    const val START = 0.85f
    /** ऊपर इससे आगे नहीं (status bar / notification खिंचने से बचो) */
    const val TOP = 0.05f

    fun pct(c: Context): Int = clamp(Prefs.long(c, PREF_PCT, DEF.toLong()).toInt())
    fun setPct(c: Context, p: Int) = Prefs.edit(c) { putLong(PREF_PCT, clamp(p).toLong()) }
    fun more(c: Context): Boolean = Prefs.bool(c, PREF_MORE, false)
    fun setMore(c: Context, on: Boolean) = Prefs.edit(c) { putBoolean(PREF_MORE, on) }

    /** 5 के गुणक में, [MIN]..[MAX]. pure */
    fun clamp(p: Int): Int = ((p.coerceIn(MIN, MAX) + STEP / 2) / STEP * STEP).coerceIn(MIN, MAX)

    /** इस कोशिश ([try0] = 0, 1, 2 ...) में कितना ऊपर. pure */
    fun pctFor(base: Int, more: Boolean, try0: Int): Int =
        if (more) minOf(clamp(base) + try0 * STEP_UP, MAX) else clamp(base)

    /** (शुरू y, ख़त्म y) pixels में. pure */
    fun ys(height: Int, pct: Int): Pair<Float, Float> {
        val end = maxOf(START - pct / 100f, TOP)
        return height * START to height * end
    }

    fun label(p: Int): String = when {
        p <= 40 -> "$p% — थोड़ा ऊपर (कई फ़ोन में इतने से PIN आ जाता है)"
        p >= 70 -> "$p% — बहुत ऊपर (जिन फ़ोन में पूरा खींचना पड़ता है)"
        else -> "$p% — बीच में" + if (p == DEF) " (डिफ़ॉल्ट, पहले जैसा)" else ""
    }
}

/**
 * 24 नवंबर 2026 (मालिक): swipe से PIN वाला हिस्सा न आए = फ़ोन लॉक करके फिर जगाना, फिर swipe ([PhoneUnlocker.enter]).
 * Pure (JUnit). GLOBAL_ACTION_LOCK_SCREEN Android 9 (API 28) से.
 */
object UnlockRetry {
    const val MAX_RELOCKS = 1
    const val RELOCK_WAIT_MS = 600L
    const val SWIPES_AFTER_RELOCK = 2

    fun relock(done: Int, sdk: Int): Boolean = sdk >= 28 && done < MAX_RELOCKS
}
