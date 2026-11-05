package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "8 मिनट पे अपने-आप नया कनेक्शन बने, और पुरानी बातचीत याद रखे"):
 * सामान्य बदलाव (8-9 मिनट, goAway) server के session-resumption handle से होता है = याद वही.
 * पर जब handle नहीं भेजा जाता - session दो बार अटका (नई बातचीत), handle पुराना/खत्म, या ऐप दोबारा खुला -
 * तब नया Gemini सत्र बिल्कुल कोरा था: अभी-अभी की बात भूल जाता था. अब ऐसे नए सत्र के system prompt में
 * पिछली कुछ बातें (फ़ोन में पहले से रखी DK चैट) जोड़ी जाती हैं, ताकि बात वहीं से आगे चले.
 *
 * Pure logic - Android import नहीं -> JVM test ([LiveCarryTest]).
 */
object LiveCarry {
    data class Turn(val role: String, val text: String, val time: Long)

    /** इससे पुरानी बात "अभी की बातचीत" नहीं */
    const val WINDOW_MS = 30 * 60_000L
    const val MAX_TURNS = 10
    const val MAX_TURN_CHARS = 240
    const val MAX_CHARS = 1_800

    /** नए (बिना handle) सत्र के prompt के लिए पिछली बातचीत; कुछ न हो तो "" */
    fun note(turns: List<Turn>, now: Long): String {
        val recent = turns.filter { it.text.isNotBlank() && now - it.time in 0..WINDOW_MS && (it.role == "u" || it.role == "d") }
            .takeLast(MAX_TURNS)
        if (recent.isEmpty()) return ""
        val lines = ArrayDeque<String>()
        var total = 0
        for (t in recent.asReversed()) {
            val txt = t.text.replace(Regex("\\s+"), " ").trim().trimEnd('…').let { if (it.length > MAX_TURN_CHARS) it.take(MAX_TURN_CHARS) + "…" else it }
            val line = (if (t.role == "u") "User: " else "DK: ") + txt
            if (total + line.length > MAX_CHARS) break
            lines.addFirst(line); total += line.length + 1
        }
        if (lines.isEmpty()) return ""
        return "\n\nRECENT CONVERSATION (the voice connection was renewed - this is the SAME conversation, continue it naturally; " +
            "do NOT greet again, do NOT repeat old answers, do NOT redo old tasks unless the user asks again):\n" + lines.joinToString("\n")
    }
}
