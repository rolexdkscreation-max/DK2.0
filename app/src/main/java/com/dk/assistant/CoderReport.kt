package com.dk.assistant

/**
 * Kaam poora hone par chhoti report: kaam ke dauraan kya dikkat aayi (bug / error / limit) aur kaise theek hui.
 * CoderAi (model/API switch), CoderTeam (reviewer/build) yahan jodte hain; finish par [text] report banata hai.
 */
object CoderReport {
    data class Item(val problem: String, val fix: String)
    private val items = mutableListOf<Item>()
    /** काम शुरू करने से पहले बताई योजना ("पहले … पढ़ रहा हूँ, फिर …") */
    @Volatile private var planText = ""

    /** Naya kaam shuru - purani report saaf */
    fun begin() = synchronized(items) { items.clear(); planText = "" }

    fun plan(text: String) { planText = text.trim().take(300) }
    fun plan(): String = planText

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

    /**
     * आखिरी छोटी रिपोर्ट, तीन हिस्से: क्या हुआ / क्या नहीं हुआ / आगे आपको क्या करना है.
     * ok=false पर [text] (गलती) सिर्फ़ यहीं एक बार; उसमें "आगे:" हो तो वह तीसरे हिस्से में जाता है.
     */
    fun final(ok: Boolean, text: String, list: List<Item> = items(), next: String? = null, plan: String = plan()): String = buildString {
        append("📋 रिपोर्ट\n")
        if (plan.isNotBlank()) append("🧭 योजना: ").append(plan).append('\n')
        append("✔ क्या हुआ:\n")
        if (ok) append("• काम पूरा हुआ\n")
        if (list.isEmpty() && ok) append("• कोई दिक्कत या एरर नहीं आया\n")
        list.forEach { append("• दिक्कत: ").append(it.problem).append(" → हल: ").append(it.fix).append('\n') }
        if (!ok && list.isEmpty()) append("• कुछ खास नहीं — काम बीच में रुका\n")
        val body = text.substringBefore("आगे:").trim()
        val ahead = next ?: text.substringAfter("आगे:", "").trim().takeIf { it.isNotBlank() }
        append("✘ क्या नहीं हुआ:\n")
        if (ok) append("• कुछ बाकी नहीं\n") else append(body.lines().filter { it.isNotBlank() }.take(8).joinToString("\n") { "• " + it.trim().take(300) }).append('\n')
        append("➜ आगे आपको क्या करना है:\n• ")
        append(ahead ?: if (ok) (if (text.contains("PR #")) "PR देखकर Merge करें (ऊपर लिखा है)" else "कुछ नहीं") else advice(text))
    }.trimEnd()

    /** Ruke hue kaam ke liye seedha sujhav */
    fun advice(error: String): String = when {
        error.contains("Developer settings") -> "ऊपर बताए तरीके से GitHub token की इजाज़त बदलें, फिर काम दोबारा भेजें।"
        error.contains("Push पक्का नहीं") -> "थोड़ी देर बाद \"फिर से push\" करें; GitHub पर branch देखें।"
        error.contains("429") || error.contains("limit", true) || error.contains("कोटा") ->
            "सारी keys की free limit खत्म है। थोड़ी देर बाद फिर भेजें, या Settings में दूसरी API (Groq / OpenRouter) की key डालें।"
        error.contains("401") || error.contains("403") -> "Settings में API key / GitHub token जाँचें।"
        error.contains("10 मिनट") -> "काम को छोटे हिस्सों में बाँटकर भेजें।"
        else -> "ऊपर की गलती देखकर फिर भेजें।"
    }
}
