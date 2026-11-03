package com.dk.assistant

import android.content.Context
import android.content.SharedPreferences

/**
 * आवाज़ से लॉक / अनलॉक की settings + फ़ोन का PIN / पैटर्न / पासवर्ड।
 *
 * PIN हमेशा Keystore के ताले में (AES-GCM, चाबी फ़ोन से बाहर नहीं निकलती)। ताला न चले तो PIN **सेव ही नहीं होता**
 * (बाकी keys की तरह सादा नहीं रखा जाता)। PIN कभी log, AI या स्क्रीन पर नहीं जाता।
 */
object PhoneLockStore {
    const val K_LOCK_ON = "plock_lock_on"
    const val K_UNLOCK_ON = "plock_unlock_on"
    const val K_NAMES = "plock_names"
    const val K_LOCK_WORDS = "plock_lock_words"
    const val K_UNLOCK_WORDS = "plock_unlock_words"
    const val K_KIND = "plock_kind"
    const val K_SECRET = "plock_secret"      // SecretNames में भी (राज़)
    const val K_FAILS = "plock_fails"

    /** लगातार इतनी बार गलत = DK PIN डालना रोक दे (फ़ोन लंबे समय के लिए न अटके) - नया PIN सेव करने पर फिर चालू */
    const val MAX_FAILS = 2

    /** tests में नकली ताला */
    @Volatile internal var codec: SecretCodec = KeystoreCodec

    private fun raw(c: Context): SharedPreferences = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)

    fun lockOn(c: Context) = raw(c).getBoolean(K_LOCK_ON, false)
    fun unlockOn(c: Context) = raw(c).getBoolean(K_UNLOCK_ON, false)
    fun names(c: Context) = raw(c).getString(K_NAMES, null) ?: PhoneLockVoice.DEFAULT_NAMES
    fun lockWords(c: Context) = raw(c).getString(K_LOCK_WORDS, null)?.takeIf { it.isNotBlank() } ?: PhoneLockVoice.DEFAULT_LOCK
    fun unlockWords(c: Context) = raw(c).getString(K_UNLOCK_WORDS, null)?.takeIf { it.isNotBlank() } ?: PhoneLockVoice.DEFAULT_UNLOCK
    fun kind(c: Context): PhoneLockVoice.Kind? = PhoneLockVoice.Kind.of(raw(c).getString(K_KIND, null))
    fun hasSecret(c: Context) = kind(c) != null && SecretNames.isSealed(raw(c).getString(K_SECRET, null))
    fun fails(c: Context) = raw(c).getInt(K_FAILS, 0)

    fun saveSettings(c: Context, lockOn: Boolean, unlockOn: Boolean, names: String, lockWords: String, unlockWords: String) {
        raw(c).edit().putBoolean(K_LOCK_ON, lockOn).putBoolean(K_UNLOCK_ON, unlockOn && hasSecret(c))
            .putString(K_NAMES, names.trim()).putString(K_LOCK_WORDS, lockWords.trim()).putString(K_UNLOCK_WORDS, unlockWords.trim())
            .commit()
        reload(c)
    }

    /** PIN / पैटर्न / पासवर्ड ताले में। false = गलत रूप या ताला नहीं चला (तब कुछ सेव नहीं) */
    fun saveSecret(c: Context, kind: PhoneLockVoice.Kind, secret: String): Boolean {
        if (!PhoneLockVoice.valid(kind, secret)) return false
        val sealed = codec.seal(secret)?.takeIf { SecretNames.isSealed(it) } ?: return false
        if (codec.open(sealed) != secret) return false          // खोलकर पक्का - बाद में गलत PIN न डले
        return raw(c).edit().putString(K_KIND, kind.id).putString(K_SECRET, sealed).putInt(K_FAILS, 0).commit()
    }

    /** सिर्फ़ PIN डालते समय - कहीं और नहीं */
    internal fun secret(c: Context): String? =
        raw(c).getString(K_SECRET, null)?.takeIf { SecretNames.isSealed(it) }?.let { codec.open(it) }

    fun clearSecret(c: Context) {
        raw(c).edit().remove(K_SECRET).remove(K_KIND).remove(K_FAILS).putBoolean(K_UNLOCK_ON, false).commit()
        reload(c)
    }

    fun failed(c: Context) { raw(c).edit().putInt(K_FAILS, fails(c) + 1).commit() }
    /**
     * v3.151: मालिक ने खुद फ़ोन खोला (Android का अपना गलत-PIN गिनती भी 0) - DK को फिर एक मौका।
     * पहले दो बार न खुलने पर DK हमेशा के लिए रुक जाता था (जब तक PIN दोबारा सेव न हो), भले गलती
     * PIN की नहीं, बटन छूटने की थी। एक ही मौका - फिर गलत हुआ तो फिर रुकेगा (Android का ताला कभी नहीं अटकेगा)।
     */
    fun userUnlocked(c: Context) { val f = fails(c); if (f >= MAX_FAILS) raw(c).edit().putInt(K_FAILS, MAX_FAILS - 1).commit() }
    fun worked(c: Context) { if (fails(c) != 0) raw(c).edit().putInt(K_FAILS, 0).commit() }
    /** DK अभी PIN डाल सकता है? */
    fun canEnter(c: Context) = unlockOn(c) && hasSecret(c) && fails(c) < MAX_FAILS

    /** settings → [PhoneLockVoice.cfg] (बुलाने वाले नाम में DK के अपने नाम भी) */
    fun reload(c: Context) {
        val names = (PhoneLockVoice.list(names(c)) + Prefs.wakeWords(c).map { PhoneLockVoice.norm(it) }).filter { it.isNotEmpty() }.distinct()
        PhoneLockVoice.cfg = PhoneLockVoice.Cfg(
            lockOn = lockOn(c), unlockOn = unlockOn(c) && hasSecret(c), names = names,
            lock = PhoneLockVoice.list(lockWords(c)), unlock = PhoneLockVoice.list(unlockWords(c)),
        )
    }
}
