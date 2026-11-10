package com.dk.assistant

/**
 * नवंबर 2026 (मालिक): "7 एजेंट में बँटा एजेंट पूरी बात नहीं जानता - मैनेजर पूरी बात याद रखे और सबको एक सारांश में समझाए".
 *
 * मैनेजर की याददाश्त (पूरी टीम की साझा कॉपी): user के अपने शब्द, समझ/spec, शर्तें, क्या नहीं छेड़ना, प्लान और कौन-सा कदम
 * हुआ, क्या पता चला (जड़, आज़माए सुधार, CI के पकड़े नाम), और टीम में अभी तक क्या-क्या हुआ.
 * हर एजेंट की AI कॉल में यही कॉपी जाती है. बहुत बड़ी हो तो मैनेजर (AI) उसे छोटे सारांश में लिखता है; वह भी न हो सके तो
 * यहाँ का पक्का (बिना AI) छोटा रूप - user के शब्द कभी नहीं कटते.
 *
 * Android import नहीं -> JVM unit test.
 */
class TeamMemory {
    var userWords = ""
    var understanding = ""
    var spec = ""
    var acceptance: List<String> = emptyList()
    var notTouch: List<String> = emptyList()
    var plan: List<String> = emptyList()
    var stepsDone = 0
    private val findings = ArrayList<String>()
    private val log = ArrayDeque<String>()

    /** पता चली बात (जड़, फ़ैसला, CI का सबूत) - दोहराव नहीं */
    fun found(t: String) {
        val s = t.trim().take(FINDING_MAX)
        if (s.isBlank() || findings.lastOrNull() == s) return
        findings.add(s)
        while (findings.size > FINDINGS_KEEP) findings.removeAt(0)
    }

    /** टीम में क्या हुआ (हर एजेंट का संदेश) - आख़िरी [LOG_KEEP] */
    fun event(agent: String, t: String) {
        val s = t.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(EVENT_MAX) ?: return
        log.addLast("$agent: $s")
        while (log.size > LOG_KEEP) log.removeFirst()
    }

    val isEmpty get() = userWords.isBlank() && plan.isEmpty() && findings.isEmpty()

    /** याददाश्त कितनी बदली - सारांश दोबारा लिखवाना है या नहीं */
    fun version(): Int = (userWords.length + understanding.length + spec.length) * 31 + plan.size * 7 + stepsDone * 13 + findings.size * 17

    /** पूरी कॉपी (कुछ नहीं कटा) */
    fun full(): String = buildString {
        append("USER'S OWN WORDS (exact - this is what the owner wants):\n").append(userWords.trim()).append('\n')
        if (understanding.isNotBlank()) append("\nWHAT THE TEAM UNDERSTOOD:\n").append(understanding.trim()).append('\n')
        if (spec.isNotBlank()) append("\nSPEC (approved):\n").append(spec.trim()).append('\n')
        if (acceptance.isNotEmpty()) append("\nDONE WHEN:\n").append(acceptance.joinToString("\n") { "- $it" }).append('\n')
        if (notTouch.isNotEmpty()) append("\nDO NOT CHANGE:\n").append(notTouch.joinToString("\n") { "- $it" }).append('\n')
        if (plan.isNotEmpty()) {
            append("\nPLAN (").append(stepsDone.coerceAtMost(plan.size)).append('/').append(plan.size).append(" done):\n")
            plan.forEachIndexed { i, s -> append(if (i < stepsDone) "[x] " else if (i == stepsDone) "[>] " else "[ ] ").append(i + 1).append(". ").append(s).append('\n') }
        }
        if (findings.isNotEmpty()) append("\nWHAT WE LEARNED SO FAR (root causes, tried fixes, compiler proof - do not repeat a failed idea):\n")
            .append(findings.joinToString("\n") { "- $it" }).append('\n')
        if (log.isNotEmpty()) append("\nTEAM LOG (latest last):\n").append(log.joinToString("\n") { "- $it" }).append('\n')
    }

    /**
     * पक्का छोटा रूप (AI के बिना): user के शब्द पूरे, बाकी हर हिस्सा अपने हिस्से की जगह तक.
     * हमेशा [max] से छोटा (user के शब्द ही बहुत लंबे हों तो वे भी कटते हैं, आख़िर में).
     */
    fun short(max: Int): String {
        val f = full()
        if (f.length <= max) return f
        val words = userWords.trim()
        val room = (max - minOf(words.length, max / 2) - 200).coerceAtLeast(0)
        fun cut(s: String, n: Int) = if (s.length <= n) s else s.take(maxOf(0, n - 1)) + "…"
        val out = buildString {
            append("USER'S OWN WORDS (exact):\n").append(cut(words, max / 2)).append('\n')
            if (understanding.isNotBlank()) append("\nUNDERSTOOD: ").append(cut(understanding.trim(), room / 5)).append('\n')
            if (spec.isNotBlank()) append("\nSPEC: ").append(cut(spec.trim(), room / 5)).append('\n')
            if (acceptance.isNotEmpty()) append("\nDONE WHEN: ").append(cut(acceptance.joinToString("; "), room / 10)).append('\n')
            if (notTouch.isNotEmpty()) append("\nDO NOT CHANGE: ").append(cut(notTouch.joinToString("; "), room / 10)).append('\n')
            if (plan.isNotEmpty()) append("\nPLAN ").append(stepsDone.coerceAtMost(plan.size)).append('/').append(plan.size).append(" done; now: ")
                .append(cut(plan.getOrNull(stepsDone) ?: "build/tests", room / 10)).append('\n')
            if (findings.isNotEmpty()) append("\nLEARNED (newest first): ").append(cut(findings.takeLast(6).reversed().joinToString(" | "), room / 5)).append('\n')
            if (log.isNotEmpty()) append("\nLATEST (newest first): ").append(cut(log.takeLast(6).reversed().joinToString(" | "), room / 10)).append('\n')
        }
        return cut(out, max)
    }

    /** मैनेजर (AI) से सारांश लिखवाने का संदेश */
    fun summaryAsk(max: Int): String = "You are the MANAGER. This is the team's full shared memory for the current task. Rewrite it as ONE compact " +
        "brief (max $max characters) that every team member can read to understand EVERYTHING important in detail: keep the user's own words " +
        "(exact if they fit), the goal, what was understood, the spec/acceptance, what must not change, which plan step we are on, every " +
        "root cause / failed idea / compiler-proven wrong name (so nobody repeats it), and the latest state. No new ideas, no greetings. " +
        "Plain text only.\n\n----- FULL MEMORY -----\n" + full()

    /** AI का सारांश ठीक है? (खाली नहीं, सीमा में, user के शब्दों का कुछ हिस्सा बचा) - नहीं तो [short] */
    fun accept(summary: String?, max: Int): String {
        val s = summary?.trim().orEmpty()
        if (s.length < 40 || s.length > max) return short(max)
        val key = userWords.trim().take(30)
        return if (key.isNotBlank() && !s.contains(key.take(12))) "USER'S OWN WORDS (exact):\n" + userWords.trim().take(max / 3) + "\n\n" + s.take(max - max / 3 - 40)
        else s
    }

    companion object {
        const val FINDINGS_KEEP = 30
        const val FINDING_MAX = 400
        const val LOG_KEEP = 25
        const val EVENT_MAX = 180
        /** हर एजेंट को कितनी बड़ी कॉपी (अक्षर) - मॉडल जितना पढ़ सकता है उसका छोटा हिस्सा, फिर भी पूरी बात */
        fun maxFor(budget: Int): Int = (budget / 25).coerceIn(4_000, 20_000)
        const val HEADER = "MANAGER'S TEAM BRIEF (shared memory of the WHOLE task - read it fully before you do your part; your part must fit this):\n"
    }
}
