package com.dk.assistant

import java.util.Locale

/**
 * 25 नवंबर 2026 (मालिक: "Custom बटन - टैप करे तो खोजकर दुनिया की कोई भी भाषा"): ISO 639 की सारी भाषाएँ
 * (Java की अपनी सूची, ~180) - अपना नाम + English नाम + code. खोज तीनों में. pure (Android import नहीं) -> JVM test.
 */
object WorldLangs {
    data class L(val code: String, val native: String, val english: String) {
        val label get() = if (native.equals(english, true) || native.isBlank()) "$english ($code)" else "$native — $english ($code)"
    }

    /** हिंदी / English / Hinglish ऊपर अलग से हैं - Custom सूची में नहीं (hi / en दोबारा नहीं) */
    val ALL: List<L> by lazy {
        Locale.getISOLanguages().asSequence()
            .filter { it != "hi" && it != "en" }
            .map { code ->
                val loc = Locale(code)
                val en = loc.getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
                val nat = loc.getDisplayLanguage(loc).replaceFirstChar { c -> c.titlecase(loc) }
                L(code, nat, en)
            }
            .filter { it.english != it.code }      // जिनका नाम Java को भी पता नहीं - छोड़ो
            .distinctBy { it.code }
            .sortedBy { it.english.lowercase() }
            .toList()
    }

    fun find(code: String): L? = ALL.firstOrNull { it.code == code }

    fun english(code: String): String = find(code)?.english ?: Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    /** खोज: अपना नाम / English नाम / code - शुरू से मिले वाले पहले */
    fun search(q: String): List<L> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return ALL
        val hit = ALL.filter { it.english.lowercase().contains(t) || it.native.lowercase().contains(t) || it.code == t }
        return hit.sortedBy { if (it.english.lowercase().startsWith(t) || it.native.lowercase().startsWith(t) || it.code == t) 0 else 1 }
    }
}
