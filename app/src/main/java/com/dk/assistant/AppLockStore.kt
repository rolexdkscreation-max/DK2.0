package com.dk.assistant

import android.content.Context
import java.security.SecureRandom

/**
 * 21 नवंबर 2026: ऐप लॉक की सेटिंग्स (Prefs). PIN / पैटर्न सिर्फ़ hash में ([AppLockRules.seal]) - कहीं दिखते नहीं.
 * यही "DK सीक्रेट PIN" टच मोड की चेतावनी स्क्रीन पर भी चलता है.
 */
object AppLockStore {
    const val P_ON = "applock_on"
    const val P_PIN = "applock_pin_hash"
    const val P_PATTERN = "applock_pattern_hash"
    const val P_METHOD = "applock_method"
    const val P_APPS = "applock_apps"
    const val P_DK = "applock_dk_self"
    const val P_GRACE = "applock_grace_ms"
    const val P_FAILS = "applock_fails"
    const val P_LAST_FAIL = "applock_last_fail"

    fun on(c: Context) = Prefs.bool(c, P_ON, false)
    fun setOn(c: Context, v: Boolean) = Prefs.edit(c) { putBoolean(P_ON, v) }

    fun hasPin(c: Context) = Prefs.str(c, P_PIN, "").isNotBlank()
    fun hasPattern(c: Context) = Prefs.str(c, P_PATTERN, "").isNotBlank()

    private fun salt() = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun setPin(c: Context, pin: String) = Prefs.edit(c) { putString(P_PIN, AppLockRules.seal(pin, salt())) }
    fun setPattern(c: Context, dots: List<Int>) = Prefs.edit(c) { putString(P_PATTERN, AppLockRules.seal(AppLockRules.patternKey(dots), salt())) }
    fun clearPattern(c: Context) = Prefs.edit(c) { remove(P_PATTERN); if (method(c) == AppLockRules.Method.PATTERN) putString(P_METHOD, AppLockRules.Method.PIN.id) }

    /** PIN जाँच (गलत = गिनती बढ़े, सही = गिनती शून्य). रुकने का समय चल रहा हो तो false */
    fun checkPin(c: Context, pin: String): Boolean = tryCode(c) { AppLockRules.check(pin, Prefs.str(c, P_PIN, "")) }
    fun checkPattern(c: Context, dots: List<Int>): Boolean = tryCode(c) { AppLockRules.check(AppLockRules.patternKey(dots), Prefs.str(c, P_PATTERN, "")) }

    private fun tryCode(c: Context, ok: () -> Boolean): Boolean {
        if (waitLeft(c) > 0) return false
        val good = ok()
        if (good) success(c) else fail(c)
        return good
    }

    fun success(c: Context) = Prefs.edit(c) { putLong(P_FAILS, 0L) }
    fun fail(c: Context) = Prefs.edit(c) { putLong(P_FAILS, fails(c) + 1L); putLong(P_LAST_FAIL, System.currentTimeMillis()) }
    fun fails(c: Context): Int = Prefs.long(c, P_FAILS, 0L).toInt()

    /** अभी कितनी देर कोशिश नहीं (गलत कोशिशों के बाद) */
    fun waitLeft(c: Context): Long {
        val w = AppLockRules.waitMs(fails(c))
        if (w <= 0) return 0
        return (Prefs.long(c, P_LAST_FAIL, 0L) + w - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun method(c: Context) = AppLockRules.Method.of(Prefs.str(c, P_METHOD, AppLockRules.Method.PIN.id))
    fun setMethod(c: Context, m: AppLockRules.Method) = Prefs.edit(c) { putString(P_METHOD, m.id) }

    fun apps(c: Context): Set<String> = Prefs.str(c, P_APPS, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun setApps(c: Context, s: Set<String>) = Prefs.edit(c) { putString(P_APPS, s.sorted().joinToString(",")) }

    fun dkSelf(c: Context) = Prefs.bool(c, P_DK, true)
    fun setDkSelf(c: Context, v: Boolean) = Prefs.edit(c) { putBoolean(P_DK, v) }

    fun grace(c: Context) = Prefs.long(c, P_GRACE, AppLockRules.RELOCK_DEFAULT)
    fun setGrace(c: Context, ms: Long) = Prefs.edit(c) { putLong(P_GRACE, ms) }

    /** यह ऐप (या DK खुद) ताले में है? */
    fun locked(c: Context, pkg: String): Boolean = on(c) && hasPin(c) && (if (pkg == c.packageName) dkSelf(c) else pkg in apps(c))
}
