package com.dk.assistant

/**
 * Kaam poora hone par chhoti report: kaam ke dauraan kya dikkat aayi (bug / error / limit) aur kaise theek hui.
 * CoderAi (model/API switch), CoderTeam (reviewer/build) yahan jodte hain; finish par [text] report banata hai.
 */
object CoderReport {
    data class Item(val problem: String, val fix: String)
    private val items = mutableListOf<Item>()

    /** Naya kaam shuru - purani report saaf */
    fun begin() = synchronized(items) { items.clear() }

    fun add(problem: String, fix: String) = synchronized(items) {
        val p = problem.trim().take(220); val f = fix.trim().take(220)
        if (p.isEmpty()) return@synchronized
        // ek hi dikkat baar-baar (har kadam par wahi 429) - ek baar hi likho
        if (items.none { it.problem == p && it.fix == f }) items.add(Item(p, f))
        while (items.size > 12) items.removeAt(0)
    }

    fun items(): List<Item> = synchronized(items) { items.toList() }

    /** Report ka text. [error] = kaam aakhir me kis wajah se ruka (null = poora hua) */
    fun text(error: String? = null): String = format(items(), error)

    fun format(list: List<Item>, error: String?): String = buildString {
        append("📋 रिपोर्ट\n")
        if (list.isEmpty() && error == null) { append("काम में कोई दिक्कत या एरर नहीं आया।"); return@buildString }
        list.forEach { append("• दिक्कत: ").append(it.problem).append("\n  हल: ").append(it.fix).append('\n') }
        if (error != null) append("• दिक्कत: ").append(error.lineSequence().first().take(220))
            .append("\n  हल: ठीक नहीं हो पाया — ").append(advice(error))
    }.trimEnd()

    /** Ruke hue kaam ke liye seedha sujhav */
    fun advice(error: String): String = when {
        error.contains("429") || error.contains("limit", true) || error.contains("कोटा") ->
            "सारी keys की free limit खत्म है। थोड़ी देर बाद फिर भेजें, या Settings में दूसरी API (Groq / OpenRouter) की key डालें।"
        error.contains("401") || error.contains("403") -> "Settings में API key / GitHub token जाँचें।"
        error.contains("10 मिनट") -> "काम को छोटे हिस्सों में बाँटकर भेजें।"
        else -> "ऊपर की गलती देखकर फिर भेजें।"
    }
}
