package com.dk.assistant

import java.util.concurrent.ConcurrentHashMap

/**
 * v3.5+: DK लाइव ने "हाँ, कहानी लिख रही हूँ / कोडिंग टीम को दे दिया / हो गया" बोला पर असल में एजेंट का tool
 * (story_task / coding_task / computer_task / build_website) बुलाया ही नहीं - एजेंट की स्क्रीन पर कुछ नहीं दिखता था.
 * अब: बारी ख़त्म होते ही जाँच - यूज़र ने किसी एजेंट को काम कहा + DK ने हाँ कहा + tool नहीं बुलाया = फ़ोन खुद वही
 * tool चलाता है (एजेंट की स्क्रीन खुलती है, काम वहीं दिखता है) और Gemini को असली नतीजा बताता है.
 *
 * Android import नहीं -> JVM test.
 */
object LiveAgentRoute {
    enum class Agent(val tool: String, val title: String) {
        STORY("story_task", "स्टोरी राइटर"),
        WEBSITE("build_website", "वेबसाइट फ़ोर्ज"),
        COMPUTER("computer_task", "DK कंप्यूटर"),
        CODING("coding_task", "कोडिंग एजेंट"),
    }

    val TOOLS: Set<String> = Agent.values().map { it.tool }.toSet()

    fun byTool(name: String): Agent? = Agent.values().firstOrNull { it.tool == name }

    private val STORY_N = listOf("कहानी", "कहानियां", "कहानियाँ", "स्टोरी", "story", "kahani", "kahaani", "کہانی", "اسٹوری", "سٹوری")
    private val STORY_V = listOf("लिख", "बना", "तैयार", "शुरू", "likh", "bana", "taiyar", "shuru", "write", "make", "start", "لکھ", "بنا", "تیار", "شروع")
    private val WEB_N = listOf("वेबसाइट", "वेब साइट", "वेब पेज", "वेबपेज", "लैंडिंग पेज", "website", "web site", "webpage", "web page", "landing page", "ویب سائٹ", "ویب پیج")
    private val WEB_V = listOf("बना", "तैयार", "bana", "taiyar", "build", "make", "create", "بنا", "تیار")
    private val COMP_N = listOf("डीके कंप्यूटर", "डीके कम्प्यूटर", "dk कंप्यूटर", "dk computer", "कंप्यूटर एजेंट", "कम्प्यूटर एजेंट", "computer agent",
        "कंप्यूटर से", "कंप्यूटर को", "कम्प्यूटर से", "कम्प्यूटर को", "computer se", "computer ko", "ڈی کے کمپیوٹر", "کمپیوٹر سے", "کمپیوٹر کو")
    private val CODE_N = listOf("कोडिंग", "कोड ", "रिपो", "रिपॉजिटरी", "रिपोजिटरी", "गिटहब", "पुल रिक्वेस्ट", "coding", "code ", "repo", "github",
        "pull request", "کوڈنگ", "کوڈ ", "ریپو")
    /** काम सौंपने वाले शब्द ("क्या करता है" जैसे सवाल नहीं) */
    private val DO_V = listOf("करो", "कर दो", "करवा", "करवाओ", "बनाओ", "बना दो", "लिखो", "लिख दो", "ठीक करो", "ठीक कर", "जोड़ो", "जोड़ दो", "जोडो",
        "बदलो", "बदल दो", "दे दो", "दो ", "दे दिया", "दे रही", "दे रहा", "दे दी", "सौंप", "शुरू", "भेजो", "चलाओ", "निकालो", "ढूंढो", "ढूँढो", "रिसर्च",
        "karo", "kar do", "banao", "bana do", "likho", "jodo", "jod do", "badlo", "de do", "bhejo", "chalao", "shuru",
        "fix", "add ", "make", "build", "create", "write", "start", "research",
        "کرو", "کر دو", "بناؤ", "لکھو", "ٹھیک کرو", "جوڑو", "بدلو", "دے دو", "شروع", "بھیجو")

    private fun norm(t: String) = " " + t.lowercase().replace(Regex("[\\s,.!?।؟]+"), " ").trim() + " "
    private fun any(t: String, l: List<String>) = l.any { t.contains(it) }

    /** बात में किस एजेंट को काम कहा गया? (null = किसी को नहीं / सिर्फ़ पूछा) */
    fun detect(text: String): Agent? {
        val t = norm(text)
        if (t.isBlank()) return null
        return when {
            any(t, STORY_N) && any(t, STORY_V) -> Agent.STORY
            any(t, WEB_N) && any(t, WEB_V) -> Agent.WEBSITE
            any(t, COMP_N) && any(t, DO_V) -> Agent.COMPUTER
            any(t, CODE_N) && any(t, DO_V) -> Agent.CODING
            else -> null
        }
    }

    /** पक्का दावा: शुरू / कर रही / दे दिया / हो गया - इसके साथ "और कुछ?" जैसा सवाल भी हो तो भी दावा है */
    private val CLAIM = listOf("शुरू", "लिख रही", "लिख रहा", "लिखती हूँ", "लिखता हूँ", "बना रही", "बना रहा", "बनाती हूँ", "कर रही", "कर रहा",
        "करती हूँ", "करता हूँ", "दे दिया", "दे दी", "दे रही", "दे रहा", "सौंप", "भेज दिया", "हो गया", "हो गई", "कर दिया", "कर दी", "लिख दी",
        "बना दी", "चालू कर", "started", "starting", "done", "on it", "shuru", "ho gaya", "kar diya", "kar rahi", "likh rahi", "bana rahi",
        "شروع", "کر رہی", "کر رہا", "کر دیا", "ہو گیا", "دے دیا", "لکھ رہی", "بنا رہی")
    /** हल्की हाँ: हाँ / ठीक है / ज़रूर - इसके साथ DK कुछ पूछ रहा हो (किस विषय पर?) तो खुद शुरू नहीं */
    private val YES = listOf("हाँ", "हां", "ठीक है", " जी ", "ज़रूर", "जरूर", "बिल्कुल", "अच्छा", " okay ", " ok ", " sure ", " haan ", "theek",
        "ہاں", "ٹھیک", "ضرور")
    private val NO = listOf("नहीं कर सकती", "नहीं कर सकता", "नहीं हो सकता", "नहीं हो पाया", "बंद है", " off ", "माफ़", "माफ", "sorry",
        "can't", "cannot", "نہیں کر سکتی", "بند ہے")
    /** DK इजाज़त माँग रहा है ("शुरू करूँ?", "लिख दूँ?", "चाहेंगे?") - अभी हाँ नहीं हुई */
    private val PERMIT = listOf("करूँ", "करूं", "दूँ", "दूं", "लिखूँ", "लिखूं", "बनाऊँ", "बनाऊं", "चाहेंगे", "चाहेंगी", "चाहोगे", "चाहिए क्या",
        "shall i", "should i", "do you want", "karun", "karu ", "کروں", "دوں", "چاہیں گے")
    /** DK सवाल पूछ रहा है (किस विषय पर? कौन-सा?) */
    private val ASK = listOf("?", "؟", "किस ", "कौन सा", "कौन-सा", "कौनसा", "कौन सी", "क्या आप", "बताइए कि", "बताओ कि", "which", "what kind", "کس ", "کون سا")

    /** DK ने हाँ / शुरू / हो गया कहा (मना नहीं; हल्की हाँ के साथ सवाल नहीं) */
    fun agreed(reply: String): Boolean {
        val r = norm(reply)
        if (r.isBlank() || any(r, NO) || any(r, PERMIT)) return false
        if (any(r, CLAIM)) return true
        return any(r, YES) && ASK.none { reply.contains(it) || r.contains(it) }
    }

    /**
     * जाँच: यूज़र ने [user] बोला, DK ने [said] कहा. किस एजेंट का tool फ़ोन को खुद चलाना है? null = कुछ नहीं.
     * [toolRecentlyCalled] = इस बात पर एजेंट का tool पहले ही चल चुका.
     */
    fun missed(user: String, said: String, toolRecentlyCalled: Boolean): Agent? {
        if (toolRecentlyCalled || user.trim().length < 3 || !agreed(said)) return null
        return detect(user) ?: detect(said)
    }

    private val MIN = Regex("(\\d{1,2})\\s*(मिनट|मिनिट|minute|min|منٹ)")
    private val WORD_MIN = mapOf("दस" to 10, "पंद्रह" to 15, "पन्द्रह" to 15, "बीस" to 20, "पच्चीस" to 25, "तीस" to 30,
        "das" to 10, "pandrah" to 15, "bees" to 20, "pachchis" to 25, "tees" to 30, "دس" to 10, "پندرہ" to 15, "بیس" to 20, "پچیس" to 25, "تیس" to 30)

    /** "20 मिनट की कहानी" / "बीस मिनट" -> 20; न बताया = 0 */
    fun minutes(text: String): Int {
        val t = text.lowercase()
        MIN.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        for ((w, m) in WORD_MIN) if (Regex("(^|\\s)$w\\s*(मिनट|मिनिट|minute|min|منٹ)").containsMatchIn(t)) return m
        return 0
    }

    /** tool के args (नाम -> मान) - यूज़र के अपने शब्दों में पूरा काम */
    fun args(a: Agent, user: String): Map<String, Any> {
        val u = user.trim().trimEnd('…').trim()
        return when (a) {
            Agent.STORY -> mapOf("topic" to u, "minutes" to minutes(u))
            Agent.WEBSITE -> mapOf("prompt" to u)
            Agent.COMPUTER, Agent.CODING -> mapOf("task" to u)
        }
    }

    /** tool के नतीजे से: काम सच में एजेंट को गया (शुरू / कतार में)? बंद / मना / और जानकारी चाहिए = नहीं */
    fun started(result: String): Boolean {
        val r = result.trim()
        if (r.isEmpty()) return false
        val bad = listOf("Could not", "is OFF", "Ask the user", "forge_not_started", "story_busy", "unknown function", "Tool failed", "Permission ")
        return bad.none { r.startsWith(it) || r.contains(it) }
    }

    /** Gemini को सच: फ़ोन ने खुद काम सौंपा, यह नतीजा है - वही बोलो, tool दोबारा नहीं */
    fun note(a: Agent, result: String): String =
        "SYSTEM NOTE: you told the user you would give this work to the ${a.title} but you did NOT call ${a.tool}. " +
            "The phone has now done it itself. Real result: \"${result.take(500)}\". " +
            "Tell the user in ONE short line what really happened (started / queued / could not start, and that it is visible on the ${a.title} screen). " +
            "Do NOT call ${a.tool} again for this request. Next time, always call the tool in the same turn before saying it has started."
}

/**
 * v3.5+: DK लाइव से एजेंट को दिया आख़िरी काम - एजेंट की स्क्रीन पर "🎤 DK लाइव से मिला काम" दिखाने के लिए.
 * Android import नहीं -> JVM test.
 */
object AgentHandoff {
    data class Job(val task: String, val at: Long, val result: String)

    private val jobs = ConcurrentHashMap<LiveAgentRoute.Agent, Job>()
    const val SHOW_MS = 6 * 60 * 60_000L

    fun mark(a: LiveAgentRoute.Agent, task: String, result: String, now: Long = System.currentTimeMillis()) {
        jobs[a] = Job(task.trim().take(300), now, result)
    }

    fun recent(a: LiveAgentRoute.Agent, now: Long = System.currentTimeMillis()): Job? =
        jobs[a]?.takeIf { now - it.at in 0..SHOW_MS }

    fun clear() = jobs.clear()
    fun clear(a: LiveAgentRoute.Agent) { jobs.remove(a) }

    /** "2 मिनट पहले" */
    fun ago(at: Long, now: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "अभी"
            s < 3600 -> "${s / 60} मिनट पहले"
            else -> "${s / 3600} घंटे पहले"
        }
    }

    /** स्क्रीन की पट्टी का text */
    fun banner(a: LiveAgentRoute.Agent, now: Long = System.currentTimeMillis()): String? {
        val j = recent(a, now) ?: return null
        val task = j.task.ifBlank { "(विषय एजेंट खुद चुनेगा)" }
        return "🎤 DK लाइव से मिला काम • ${ago(j.at, now)}\n$task"
    }
}
