package com.dk.assistant

import org.json.JSONObject

/**
 * DK कंप्यूटर की योजना + प्रगति + आख़िरी जाँच (Android-free - JUnit से जाँचा जाता है).
 *
 * काम शुरू होने से पहले AI योजना बनाता है (समझा, कदम, क्या मिलेगा) - user को checklist दिखती है,
 * हर टूल-कदम पर "step":N से प्रगति आगे बढ़ती है (ऊपर की animation पट्टी में "कदम 3/6"),
 * और "done" से पहले जाँचकर्ता देखता है कि काम सच में पूरा है या नहीं.
 */
object ComputerPlan {
    const val MAX_STEPS = 12
    /** जाँच में कमी मिले तो ज़्यादा से ज़्यादा इतनी बार और काम */
    const val VERIFY_ROUNDS = 2

    data class Plan(
        val understood: String,
        val steps: List<String>,
        val deliverable: String = "",
        val current: Int = 1,
        val done: Set<Int> = emptySet(),
    ) {
        val total get() = steps.size
        val doneCount get() = done.size
        val finished get() = steps.isNotEmpty() && done.size >= steps.size
        /** 0..1 - animation पट्टी के लिए */
        val fraction: Float get() = if (steps.isEmpty()) 0f else (done.size.toFloat() / steps.size).coerceIn(0f, 1f)
        fun currentText(): String = steps.getOrNull(current - 1).orEmpty()
    }

    private fun obj(text: String): JSONObject? {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { JSONObject(t) } catch (_: Exception) {
            val i = t.indexOf('{'); val j = t.lastIndexOf('}')
            if (i < 0 || j <= i) null else try { JSONObject(t.substring(i, j + 1)) } catch (_: Exception) { null }
        }
    }

    private fun list(o: JSONObject?, k: String): List<String> {
        val a = o?.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() && it != "null" }
    }

    /** AI की योजना (JSON) → Plan; कदम न हों = null (तब बिना योजना के पुराना तरीका) */
    fun parse(text: String): Plan? {
        val o = obj(text) ?: return null
        val steps = list(o, "steps").take(MAX_STEPS)
        if (steps.isEmpty()) return null
        return Plan(o.optString("understood").trim(), steps, o.optString("deliverable").trim())
    }

    /** AI ने योजना बदली (update_plan) - जो कदम हो चुके वे गिनती में बने रहते हैं */
    fun revise(p: Plan, steps: List<String>): Plan {
        val s = steps.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_STEPS)
        if (s.isEmpty()) return p
        val done = p.done.filter { it <= s.size }.toSet()
        return p.copy(steps = s, done = done, current = (done.maxOrNull()?.plus(1) ?: 1).coerceAtMost(s.size))
    }

    /** AI अब कदम [step] पर है: उससे पहले के सब पूरे */
    fun advance(p: Plan, step: Int?): Plan {
        if (step == null || step < 1 || p.steps.isEmpty()) return p
        val s = step.coerceAtMost(p.steps.size)
        val d = p.done + (1 until s)
        return if (s == p.current && d == p.done) p else p.copy(current = s, done = d)
    }

    fun finishAll(p: Plan): Plan = p.copy(done = (1..p.steps.size).toSet(), current = p.steps.size)

    /** user के लिए: समझ + कदमों की checklist */
    fun checklist(p: Plan, title: Boolean = true): String = buildString {
        if (title) {
            append("📋 योजना")
            if (p.understood.isNotBlank()) append("\n✍ मैंने समझा: ").append(p.understood)
            append('\n')
        }
        p.steps.forEachIndexed { i, s ->
            val n = i + 1
            append(when { n in p.done -> "✓ "; n == p.current -> "▶ "; else -> "○ " }).append(n).append(". ").append(s).append('\n')
        }
        if (title && p.deliverable.isNotBlank()) append("🎁 आपको मिलेगा: ").append(p.deliverable)
    }.trimEnd()

    /** ऊपर की animation पट्टी की लाइन */
    fun header(p: Plan?, last: String): String {
        val l = last.trim().lineSequence().firstOrNull().orEmpty().take(90)
        if (p == null || p.steps.isEmpty()) return l.ifBlank { "सोच रहा हूँ…" }
        val now = p.currentText().take(70)
        return "कदम ${p.current.coerceAtMost(p.total)}/${p.total}: $now" + if (l.isNotBlank() && l != now) "\n$l" else ""
    }

    /** AI को योजना याद दिलाना (हर कदम पर "step" बताने को) */
    fun forAi(p: Plan): String = "YOUR PLAN (follow it step by step; add \"step\":N to every JSON = the plan step you are working on now; " +
        "use update_plan if the plan must change):\n" + p.steps.mapIndexed { i, s -> "${i + 1}. $s" + if (i + 1 in p.done) " (done)" else "" }.joinToString("\n") +
        if (p.deliverable.isNotBlank()) "\nDELIVERABLE: ${p.deliverable}" else ""

    // ---------------------------------------------------------------- आख़िरी जाँच
    data class Verdict(val ok: Boolean, val missing: List<String>)

    /** जाँचकर्ता का JSON; न पढ़ पाए = पास (काम न अटके) */
    fun verdict(text: String): Verdict {
        val o = obj(text) ?: return Verdict(true, emptyList())
        val miss = list(o, "missing").take(6)
        val ok = if (o.has("ok")) o.optBoolean("ok", true) else miss.isEmpty()
        return Verdict(ok || miss.isEmpty(), if (ok) emptyList() else miss)
    }

    /** जाँच की ज़रूरत? (सीधी बातचीत / सवाल-पूछना पर नहीं) */
    fun needsVerify(p: Plan?, toolSteps: Int, round: Int): Boolean =
        round < VERIFY_ROUNDS && (toolSteps >= 1 || (p?.total ?: 0) >= 2)

    /** टूल नतीजा कितना बड़ा AI तक जाए (मॉडल की क्षमता के हिसाब से) */
    fun resultCap(budget: Int): Int = (budget / 25).coerceIn(7_000, 40_000)

    /** बातचीत कितनी बड़ी होने पर पुराने नतीजे छोटे हों */
    fun compactAt(budget: Int): Int = (budget * 7 / 10).coerceAtLeast(70_000)

    /** पुराना नतीजा छोटा करते समय कितना रहे */
    fun keepOld(budget: Int): Int = (budget / 200).coerceIn(700, 3_000)
}
