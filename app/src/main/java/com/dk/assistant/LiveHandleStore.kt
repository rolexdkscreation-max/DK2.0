package com.dk.assistant

/**
 * 18 नवंबर 2026 (ऑडिट G4, मालिक: "1 घंटा+ बिना रुके बातचीत, याद न टूटे"):
 *
 * Gemini Live का session-resumption handle पहले सिर्फ़ RAM में था - Android service को मार दे / ऐप update / crash =
 * नई बातचीत, याद सिर्फ़ आख़िरी 10 बातें ([LiveCarry]). Google handle को session खत्म होने के बाद 2 घंटे तक मानता है
 * (https://ai.google.dev/gemini-api/docs/live-session) -> अब handle + मॉडल + key (hash) + समय फ़ोन में save.
 * दोबारा शुरू होने पर [MAX_AGE_MS] से कम पुराना, उसी key और उसी (अब भी मौजूद) मॉडल का हो = वहीं से बातचीत.
 * handle मिटाया गया (`handle = null`, जैसे अटका / ज़हरीला) = save वाला भी मिटा - पुराना ज़हरीला handle वापस नहीं आता.
 *
 * Pure logic - Android import नहीं -> JVM test ([LiveHandleStoreTest]).
 */
object LiveHandleStore {
    const val PREF = "live_handle_v1"
    /** Google: 2 घंटे. हम 90 मिनट (घड़ी का फ़र्क / server की देरी के लिए गुंजाइश) */
    const val MAX_AGE_MS = 90 * 60_000L
    private const val SEP = '\u0001'

    data class Saved(val handle: String, val model: String, val keyHash: Int, val at: Long)

    fun encode(s: Saved): String = listOf(s.at.toString(), s.keyHash.toString(), s.model, s.handle).joinToString(SEP.toString())

    fun decode(raw: String?): Saved? {
        if (raw.isNullOrBlank()) return null
        val p = raw.split(SEP)
        if (p.size != 4) return null
        val at = p[0].toLongOrNull() ?: return null
        val kh = p[1].toIntOrNull() ?: return null
        if (p[2].isBlank() || p[3].isBlank()) return null
        return Saved(p[3], p[2], kh, at)
    }

    /** दोबारा शुरू होने पर इसी से बातचीत जारी करें? */
    fun usable(s: Saved?, now: Long, keyHash: Int, models: List<String>): Boolean =
        s != null && now - s.at in 0 until MAX_AGE_MS && s.keyHash == keyHash && s.model in models
}
