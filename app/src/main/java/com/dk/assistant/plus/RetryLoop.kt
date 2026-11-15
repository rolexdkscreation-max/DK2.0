package com.dk.assistant.plus

/**
 * 15 नवंबर 2026 (मालिक, Part 3): "हर UI काम: करो -> स्क्रीन दोबारा पढ़ो -> जाँचो -> न हुआ तो दूसरा तरीका".
 *
 * Verify-and-retry चक्र: हर [Strategy] बारी-बारी से चलता है; हर बार के बाद [verify] (असली स्क्रीन की हालत) देखा जाता है.
 * पहला तरीका जो सच में काम करे, वहीं रुक जाता है. सब नाकाम = साफ़ रिपोर्ट (कौन-सा तरीका क्यों नहीं चला) -
 * झूठा "हो गया" कभी नहीं. UI के लिए तय क्रम: node ID क्लिक -> text/contentDescription क्लिक -> bounds पर टैप -> scroll करके फिर.
 * Pure (Android import नहीं) -> JVM test ([RetryLoopTest]). Accessibility वाला जोड़ [A11yRetry] में.
 */
object RetryLoop {

    data class Strategy(val name: String, val act: suspend () -> Boolean)

    data class Step(val strategy: String, val round: Int, val acted: Boolean, val verified: Boolean)

    data class Result(val ok: Boolean, val by: String?, val steps: List<Step>) {
        fun report(): String = if (ok) "हो गया ($by से)" else
            "नहीं हुआ - आज़माए: " + steps.joinToString(", ") { "${it.strategy}#${it.round}" + if (!it.acted) " (कर नहीं पाया)" else " (स्क्रीन पर नहीं दिखा)" }
    }

    /**
     * @param rounds पूरी strategy-सूची कितनी बार (हर round के बीच [between], जैसे थोड़ा रुकना / scroll)
     * @param settle हर action के बाद स्क्रीन के बदलने का इंतज़ार
     */
    suspend fun run(
        strategies: List<Strategy>,
        verify: suspend () -> Boolean,
        rounds: Int = 2,
        settle: suspend () -> Unit = {},
        between: suspend (round: Int) -> Unit = {},
    ): Result {
        val steps = ArrayList<Step>()
        if (verify()) return Result(true, "पहले से", steps)
        for (round in 1..rounds.coerceAtLeast(1)) {
            for (s in strategies) {
                val acted = try { s.act() } catch (_: Exception) { false }
                if (acted) settle()
                val ok = acted && (try { verify() } catch (_: Exception) { false })
                steps += Step(s.name, round, acted, ok)
                if (ok) return Result(true, s.name, steps)
            }
            if (round < rounds) between(round)
        }
        return Result(false, null, steps)
    }
}
