package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "काम पूरा होकर PR खुला और merge पूछा, तब मैंने क्या-क्या कहा था, क्या ठीक हुआ, वह कैसे काम करेगा -
 * कुछ समझाकर नहीं बताया; सिर्फ़ '✅ काम हो गया / API / PR / आगे' दिखा").
 *
 * जड़: [SevenTeam] के finish() में पूरी रिपोर्ट + समझाने वाले (Explainer) का असली-diff वाला समझाना बनता था, पर
 * [SevenRunner] चैट में सिर्फ़ `out.short` ([ShortReport.text], 4 लाइन) दिखाता था - समझाना सिर्फ़ डिबग लॉग / एजेंट
 * पट्टी में जाता था. अब merge से पहले यह पूरा ब्योरा चैट में: आपने क्या कहा था -> क्या-क्या बदला -> कैसे काम करेगा ->
 * क्या नहीं हुआ / ध्यान रखें -> जाँच -> PR -> "merge कर दूँ?".
 * Pure Kotlin (JUnit).
 */
object MergeBrief {
    const val ASK = "❓ क्या मैं merge कर दूँ? \"हाँ, merge करो\" बोलें तो merge होकर नई APK बनेगी। कुछ बदलवाना हो तो बताइए - आपकी हाँ के बिना merge नहीं होगा।"

    /**
     * @param short [ShortReport.text] (हुआ/नहीं, API, PR) - सबसे ऊपर वैसा ही
     * @param task मालिक ने क्या कहा था
     * @param changes बदली फ़ाइल -> क्या/क्यों
     * @param explain समझाने वाले का असली diff वाला समझाना (AI; न बना तो खाली)
     * @param told बिना-AI पक्का नतीजा ([Seven.resultExplain]) - explain न हो तो यही
     */
    fun text(short: String, task: String, changes: List<Pair<String, String>>, explain: String, told: String, testOk: Boolean?,
             prUrl: String, github: Boolean, checks: String = "", backup: String = ""): String = buildString {
        val asks = testOk == true && github && prUrl.isNotBlank()
        // नीचे पूरा "merge कर दूँ?" आएगा - ऊपर वाली छोटी "आगे:" लाइन दोबारा नहीं
        append(short.trim().lines().filterNot { asks && it.startsWith("आगे:") }.joinToString("\n"))
        // सिर्फ़ जब बताने लायक कुछ हो (बदलाव / समझाना); "कुछ नहीं बदला" वाला छोटा जवाब वैसा ही
        if (changes.isEmpty() && explain.isBlank() && testOk != true) return@buildString
        append("\n\n━━━━━━━━━━ पूरा ब्योरा ━━━━━━━━━━")
        val t = task.trim()
        if (t.isNotEmpty()) append("\n\n📝 आपने क्या कहा था:\n").append(clip(t, 700))
        append("\n\n🔧 क्या-क्या किया (").append(changes.size).append(" फ़ाइल):")
        if (changes.isEmpty()) append("\n• कोई फ़ाइल नहीं बदली")
        changes.take(25).forEach { (f, w) -> append("\n• ").append(f).append(if (w.isNotBlank()) " — " + clip(w, 220) else "") }
        if (changes.size > 25) append("\n• …और ${changes.size - 25} फ़ाइल (PR में सब)")
        val how = explain.trim().ifBlank { told.trim() }
        if (how.isNotEmpty()) append("\n\n⚙️ यह कैसे काम करेगा (आसान भाषा में):\n").append(clip(how, 5_000))
        append("\n\n🧪 जाँच: ").append(when (testOk) {
            true -> checks.ifBlank { "build + सारे unit tests पास" }
            false -> "पास नहीं हुई — इसलिए merge नहीं पूछ रहा"
            null -> "अभी पूरी नहीं हुई"
        })
        if (testOk == true) append("\n🚫 अभी क्या नहीं हुआ: merge और APK release — वह सिर्फ़ आपकी हाँ से")
        if (backup.isNotBlank()) append("\n🛟 backup: ").append(backup)
        if (github && prUrl.isNotBlank()) append("\n🔗 PR: ").append(prUrl)
        if (asks) append("\n\n").append(ASK)
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max).trimEnd() + "…"
}
