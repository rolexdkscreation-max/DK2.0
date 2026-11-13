package com.dk.assistant

/**
 * 13 नवंबर 2026 (मालिक): "कोडिंग एजेंट क्या कर रहा है, वह एक लाइन में उसी जगह दिखे. repo चेक कर रहा है तो 'repo चेक कर रहा है',
 * फिर PR मिला रहा है तो वही लाइन हटकर 'PR…' - जैसे Computer में दिखता रहता है."
 *
 * जड़: एजेंट हर कदम ("पढ़ रहा हूँ: …", "खोज: …", "Build: …") CoderSession में "s" लाइन से लिखता था, पर चैट में काम चलते
 * समय "s" लाइनें छिपी हैं ([ChatClean.showInChat]) और सिंगल एजेंट में ऊपर वाली पट्टी भी छिपी - तो कदम कहीं नहीं दिखते थे,
 * बुलबुले में सिर्फ़ तरंग. अब: अभी का कदम = जवाब वाले बुलबुले में तरंग के बगल की एक लाइन, हर नए कदम पर वही लाइन बदलती है.
 * शुद्ध नियम (Android नहीं) -> JVM test.
 */
object CoderNow {
    /** एक लाइन इससे लंबी नहीं (बाकी "…") */
    const val MAX = 90
    /** काम शुरू हुआ, अभी कोई कदम नहीं / एजेंट सोच रहा है */
    const val THINK = "सोच रहा हूँ…"

    /**
     * अभी का कदम: आख़िरी "u" (आपकी बात) के बाद की सबसे नई "s" लाइन. काम नहीं चल रहा = "".
     * [role] / [text] = लाइन से role और पाठ (CoderSession.Line या test की जोड़ी).
     */
    fun <T> line(items: List<T>, busy: Boolean, role: (T) -> String, text: (T) -> String): String {
        if (!busy) return ""
        for (i in items.indices.reversed()) {
            val it = items[i]
            when (role(it)) {
                "u" -> return THINK
                "s" -> return clean(text(it))
            }
        }
        return THINK
    }

    fun line(items: List<Pair<String, String>>, busy: Boolean): String = line(items, busy, { it.first }, { it.second })

    /** एक लाइन: पहली भरी लाइन, जगह एक, बहुत लंबी = कटे; सिर्फ़ "…" = सोच रहा हूँ */
    fun clean(t: String): String {
        val first = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return THINK
        val one = first.replace(Regex("\\s+"), " ")
        if (one.trim('.', '…', ' ', '-').isEmpty()) return THINK
        return if (one.length > MAX) one.take(MAX - 1).trimEnd() + "…" else one
    }

    /** बुलबुले की लाइन: असली रुकावट वाली ख़बर (दोबारा कोशिश / मॉडल बदला - [DKStatus]) पहले, नहीं तो अभी का कदम */
    fun label(status: String?, step: String): String = status?.takeIf { it.isNotBlank() } ?: step
}
