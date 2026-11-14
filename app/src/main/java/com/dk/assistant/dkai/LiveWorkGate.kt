package com.dk.assistant.dkai

import com.dk.assistant.agent.core.ConfirmGate

/**
 * 14 नवंबर 2026 (मालिक: "कोई भी काम… बिना इजाज़त के ना करेगा… एक बार कन्फर्मेशन के बाद ही… ऐप खोलो जैसे काम तुरंत"):
 * DK लाइव के बड़े काम (कहानी / ऐप / वेबसाइट / गेम / कोड / DK कंप्यूटर / इमेज / सोशल रिव्यू) पहले मालिक की अपनी "हाँ".
 * "हाँ" की जाँच ऐप करता है ([ConfirmGate.accept]) - AI के कहने पर नहीं. छोटे काम (ऐप खोलो, सर्च, समय) बिना पूछे.
 * pure - JVM test से जाँचा.
 */
object LiveWorkGate {
    data class Pending(val tool: String, val what: String, val askedAt: Long)

    sealed class Gate {
        object Run : Gate()
        /** पहले पूछो (नई pending) */
        object Ask : Gate()
        /** पूछा था, पर मालिक की साफ़ "हाँ" नहीं सुनी */
        object NotHeard : Gate()
    }

    const val WINDOW_MS = 2 * 60_000L

    /** हाँ / ना जैसा छोटा जवाब (पहले से चल रहे काम का जवाब - नया काम नहीं) */
    fun shortAnswer(t: String): Boolean {
        // सिर्फ़ जवाब वाले शब्द ("हाँ कर दो", "नहीं रुको", "ok") - "login page बनाओ" जैसा छोटा नया काम जवाब नहीं गिना जाता
        val w = t.lowercase().replace(Regex("[^\\p{L}\\p{M}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        return w.isNotEmpty() && w.size <= 5 && w.all { it in ANSWER_WORDS }
    }

    val ANSWER_WORDS = setOf(
        "हाँ", "हां", "हा", "जी", "हाँजी", "ठीक", "है", "कर", "करो", "दो", "चलो", "बिल्कुल", "बिलकुल", "शुरू", "आगे", "बढ़ो", "भाई",
        "नहीं", "नही", "ना", "मत", "रद्द", "रुको", "रहने", "छोड़ो", "बंद",
        "yes", "yeah", "yep", "ok", "okay", "haan", "han", "ha", "ji", "sure", "go", "ahead", "do", "it", "start", "theek", "hai", "kar", "karo", "chalo",
        "no", "nahi", "nahin", "na", "cancel", "stop", "dont", "don't", "wait"
    )

    /** यह tool call बड़ा काम है? [action] = app_studio / social_insights का action, [text] = काम का विवरण */
    fun needs(tool: String, action: String, text: String): Boolean = when (tool) {
        "story_task", "build_website", "make_game", "image_task" -> true
        "computer_task", "coding_task" -> !shortAnswer(text)
        "app_studio" -> action.lowercase() in setOf("build", "change")
        "social_insights" -> action.lowercase() !in setOf("stop", "ask", "facts", "numbers")
        else -> false
    }

    fun check(tool: String, pending: Pending?, userAt: Long, userText: String, now: Long): Gate {
        if (pending == null || pending.tool != tool || now - pending.askedAt > WINDOW_MS) return Gate.Ask
        return if (ConfirmGate.accept(pending.askedAt, userAt, userText)) Gate.Run else Gate.NotHeard
    }

    fun askNote(what: String): String =
        "CONFIRM_FIRST (the owner wants every big job confirmed): NOTHING has started yet. In ONE short sentence in the user's language tell him exactly " +
            "what you understood (\"${what.take(160)}\") and ask हाँ या ना. Only after he himself clearly says yes, call this same tool again with the same request. " +
            "If he says no or changes it, do not start. The app checks his own words."

    const val NOT_HEARD = "NOT_STARTED: the app did not hear the user himself clearly say yes after your question, so nothing was started. " +
        "Ask him again in one short line (हाँ या ना) and wait. Never say it has started."
}
