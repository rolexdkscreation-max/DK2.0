package com.dk.assistant

/**
 * v3.87: काम का मोड और बातचीत का मोड (मालिक के नियम) - DK का स्वभाव, आवाज़ और बोलने का अंदाज़ वही रहता है;
 * यह सिर्फ़ तय करता है कि काम के समय कितना बोले और बातचीत के समय कैसे।
 * Android के बिना (JVM test)।
 */
object WorkChatMode {

    enum class Mode { WORK, CHAT, MIXED }

    private val FEELING = Regex(
        "(मूड|उदास|दुखी|परेशान|थक|बोर|अकेला|अकेलापन|टेंशन|खुश|ख़ुश|गुस्सा|ग़ुस्सा|डर|रोना|नींद नहीं|यार|sad|tired|bored|lonely|upset|happy|stress)",
        RegexOption.IGNORE_CASE
    )

    /** "कर दो / लगाओ / खोलो / भेजो" = काम; दोनों हों ("यार आज मूड ख़राब है, एक अलार्म लगा दो") = MIXED */
    fun classify(text: String): Mode {
        val t = text.trim()
        if (t.isBlank()) return Mode.CHAT
        if (TaskQueue.isStop(t)) return Mode.WORK
        val task = TaskQueue.split(t).any { it.domain != "other" } && TaskQueue.isCommand(t)
        val commandOnly = TaskQueue.isCommand(t) && !Regex("(\\?|क्या\\s|क्यों|कैसे|कैसा|कैसी|कब\\s|कौन)").containsMatchIn(t)
        val work = task || (commandOnly && TaskQueue.split(t).isNotEmpty() && t.split(Regex("\\s+")).size <= 8)
        if (!work) return Mode.CHAT
        // काम से पहले मन की बात भी कही हो
        val chatPart = t.split(Regex("[,।!?]|\\sऔर\\s")).any { p -> FEELING.containsMatchIn(p) && TaskQueue.domainOf(p) == null && !TaskQueue.isCommand(p) }
        return if (chatPart) Mode.MIXED else Mode.WORK
    }

    /** मॉडल के लिए नियम - [live] = DK Live (वहाँ कई काम फ़ोन की कतार चलाती है) */
    fun block(live: Boolean): String = buildString {
        append("\n\nWORK MODE vs TALK MODE (owner's rules). These do NOT change your personality, voice or speaking style - only how much you say and when:")
        append("\n- On EVERY user turn first decide: is he getting a phone task done (WORK) or just talking (TALK)? Command words like कर दो, लगाओ, खोलो, भेजो, चलाओ, बंद करो, call, send, open mean WORK.")
        append("\n- WORK: talk only about the task. No jokes, small talk, stories, long intro or extra advice. Before: ONLY one very short line like \"ठीक है, कर रही हूँ।\" (in your own gender form), then the tool at once - nothing else before the work. ")
        append("When it is done: the result at once, e.g. \"हो गया।\" Do not start how-are-you talk, stories or questions in the middle of a task - ask only what the task truly needs.")
        if (live) {
            append("\n- SEVERAL TASKS in one go (e.g. \"अलार्म लगाओ, फिर राहुल को व्हाट्सऐप करो, फिर गैलरी से फ़ोटो डिलीट करो, फिर यूट्यूब पर गाना सर्च करो, फिर मैप में नेविगेशन शुरू करो\" - 5 or more): ")
            append("first listen to the WHOLE sentence, then call phone_task ONCE with his full request in his exact words. The phone itself splits it into separate tasks, runs them one by one in the spoken order, ")
            append("brings the phone to the right screen between tasks, skips a stuck one and goes on. Do NOT call separate tools for those tasks and do not say anything is done until the SYSTEM NOTE with the summary comes; ")
            append("then speak that summary in one short line (e.g. \"अलार्म लग गया, मैसेज भेज दिया, फ़ोटो डिलीट हो गई, गाना चल रहा है, नेविगेशन शुरू है।\").")
            append("\n- If he says something new while tasks are running, call phone_task with only the new thing - it goes to the END of the list. If he says \"रुको\" / \"बंद करो\" / stop while tasks are running, call phone_task with request \"रुको\" at once - everything stops immediately.")
        } else {
            append("\n- SEVERAL TASKS in one go: listen to the whole sentence, do them one by one in the spoken order; if one gets stuck, skip it and go on; at the end give ONE short summary of what was done and what was not (and why).")
        }
        append("\n- RISKY tasks (deleting photos/files, sending a message to someone): if the name, the photo or the message is not clear, ask ONE short question before sending/deleting. For all other tasks never ask again and again.")
        append("\n- TALK: talk like a real, close person - relaxed, open and warm. React like a human: laugh, show worry, ask back, add your own thought. Never wrap up a conversation in one robotic line; keep it going.")
        append("\n- SWITCHING: the moment he asks for a task, switch to WORK. Go back to TALK only after all tasks are done and he talks to you. ")
        append("Both in one sentence (\"यार आज मूड ख़राब है, एक अलार्म लगा दो\"): do the task FIRST, then a little sympathy in a few words and start the conversation.")
        append("\n- If a word sounds wrong, understand the real meaning from context. His word comes first: do what he says, never argue. Always masculine forms for him. Start your reply immediately, no delay.")
    }
}
