package com.dk.assistant

/**
 * नवंबर 2026 (मालिक): "काम समझ जाए तो आगे क्या करना है उसके बटन आएँ - छूते ही वही भेज दे, लिखना न पड़े.
 * कई विकल्प हों तो सब एक साथ / एक-एक करके / अपना खुद का लिखो. और काम चलते समय कुछ और लिखूँ तो
 * उसी काम का हो = साथ में जोड़ो, अलग हो = यह काम पूरा होने के बाद करो." - Coding Agent और DK कंप्यूटर दोनों.
 *
 * यहाँ सिर्फ़ नियम (कोई Android नहीं) - JVM test से पक्का.
 */
object FollowUp {
    /** SEND = छूते ही भेजो; TYPE = लिखने का डिब्बा खोलो; REROUTE = बीच में भेजी बात का रास्ता बदलो (साथ में ⇄ बाद में) */
    enum class Act { SEND, TYPE, REROUTE }

    data class Choice(val label: String, val text: String, val act: Act = Act.SEND)

    /** बीच में भेजी बात कहाँ जाए: NOW = चल रहे काम में जोड़ो, NEXT = उसके बाद अलग काम */
    enum class Route { NOW, NEXT }

    const val LABEL_MAX = 60
    val CUSTOM = Choice("✏️ अपना लिखें", "", Act.TYPE)
    const val ADDED_NOW = "🔗 यह चल रहे काम से जुड़ा लगा — इसी काम में जोड़ दिया, अगले कदम से यह भी होगा।"
    const val ADDED_NEXT = "⏭ यह अलग काम लगा — अभी वाला काम पूरा होते ही यह शुरू होगा।"
    val TO_NEXT = Choice("⏭ नहीं, इसे बाद में अलग से करो", "", Act.REROUTE)
    val TO_NOW = Choice("🔗 नहीं, इसे अभी वाले काम में जोड़ो", "", Act.REROUTE)

    // ------------------------------------------------------------------ बटन बनाना

    /** AI के दिए विकल्प: साफ़, दोहराव नहीं, बहुत छोटे/लंबे नहीं, ज़्यादा से ज़्यादा 4 */
    fun clean(list: List<String>): List<String> = list.asSequence()
        .map { it.trim().trimStart('•', '-', '*', ' ').trim().trim('"').replace(Regex("\\s+"), " ") }
        .filter { it.length in 2..300 }
        .distinctBy { it.lowercase() }
        .take(4).toList()

    fun label(t: String): String = if (t.length <= LABEL_MAX) t else t.take(LABEL_MAX - 1).trimEnd() + "…"

    /**
     * AI के विकल्प → बटन. [tasks] = ये आगे के काम हैं (एक साथ भी हो सकते हैं) → "सब एक साथ" / "एक-एक करके" भी.
     * सवाल के जवाब (एक ही चुनना है) में tasks = false.
     */
    fun fromAi(list: List<String>, tasks: Boolean): List<Choice> {
        val l = clean(list)
        val out = l.map { Choice(label(it), it) }.toMutableList()
        if (tasks && l.size >= 2) {
            out += Choice("⚡ सब एक साथ करो", "ये सब एक साथ करो:\n" + l.joinToString("\n") { "• $it" })
            out += Choice("🪜 एक-एक करके करो", "ये एक-एक करके, इसी क्रम में करो:\n" + l.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"))
        }
        return out
    }

    /** DK कंप्यूटर ने सवाल पूछा: उसके सुझाए जवाब + अपना लिखें */
    fun forQuestion(options: List<String>): List<Choice> = fromAi(options, tasks = false) + CUSTOM

    /** DK कंप्यूटर का काम पूरा: आगे के सुझाए काम (न हों तो कोई बटन नहीं) */
    fun forDone(next: List<String>): List<Choice> = fromAi(next, tasks = true).let { if (it.isEmpty()) it else it + CUSTOM }

    /** कोडिंग टीम ने काम समझकर बताया: सवाल हों तो जवाब के बटन, वरना हाँ / सुझाए बदलाव / अपना बदलाव / रद्द */
    fun forUnderstanding(questions: List<String>, options: List<String>): List<Choice> =
        if (questions.isNotEmpty()) fromAi(options, tasks = false) + Choice("✏️ अपना जवाब लिखें", "", Act.TYPE)
        else listOf(Choice("✅ हाँ, सही समझा — शुरू करो", "हाँ")) +
            fromAi(options, tasks = false).map { it.copy(label = label("➕ " + it.text), text = "हाँ, पर यह भी: " + it.text) } +
            listOf(Choice("✏️ कुछ बदलना है", "", Act.TYPE), Choice("❌ रद्द करो", "रद्द"))

    const val MERGE_YES = "हाँ, PR merge कर दो"
    const val MERGE_NO = "अभी नहीं, PR merge मत करो"
    const val CONTINUE = "आगे करो"

    /**
     * कोडिंग स्क्रीन (काम नहीं चल रहा): अभी किस बात का इंतज़ार है उसके बटन.
     * [pending] = समझ \"हाँ\" के इंतज़ार में; [prOpen] = PR तैयार; [lastRole] = आख़िरी जवाब (d / e); [canResume] = रुका काम बचा है
     */
    fun coder(pending: SevenPlus.Understanding?, prOpen: Boolean, lastRole: String?, canResume: Boolean): List<Choice> = when {
        pending != null && pending.answer.isBlank() -> forUnderstanding(pending.questions, pending.choices)
        prOpen && lastRole == "d" -> listOf(Choice("✅ $MERGE_YES", MERGE_YES), Choice("⏸ $MERGE_NO", MERGE_NO), CUSTOM)
        lastRole == "e" && canResume -> listOf(Choice("▶ वहीं से आगे करो", CONTINUE), CUSTOM)
        else -> emptyList()
    }

    // ------------------------------------------------------------------ काम चलते समय नई बात

    private val LATER = Regex(
        "(बाद\\s*में|इसके\\s*बाद|उसके\\s*बाद|इसके\\s*पूरा|पूरा\\s*होने\\s*(के\\s*बाद|पर)|अगला\\s*काम|दूसरा\\s*काम|नया\\s*काम|अलग\\s*से|अलग\\s*काम|" +
            "after\\s+(this|that|it)|\\blater\\b|next\\s+task|new\\s+task|separate|\\bbaad\\s*m|iske\\s*baad|uske\\s*baad|alag\\s*se|naya\\s*kaam)",
        RegexOption.IGNORE_CASE)
    private val SAME = Regex(
        "(इसी\\s*में|इसमें|इस\\s*में|उसमें|उसी\\s*में|इसके\\s*साथ|उसके\\s*साथ|साथ\\s*में|साथ\\s*ही|साथ-साथ|यह\\s*भी|ये\\s*भी|वो\\s*भी|वह\\s*भी|" +
            "(^|\\s)भी(\\s|$)|(^|\\s)bhi(\\s|$)|एक\\s*और\\s*बात|की\\s*जगह|इसी\\s*काम|same\\s+task|\\balso\\b|\\btoo\\b|as\\s+well|instead|\\bisme\\b|\\bisi\\s*me|\\bsath\\s*me|\\bsaath\\s*me|ye\\s*bhi|yeh\\s*bhi|wo\\s*bhi)",
        RegexOption.IGNORE_CASE)

    /** आम शब्द - इनका मेल \"एक ही काम\" का सबूत नहीं */
    private val STOP = setOf(
        "करो", "करें", "करना", "करके", "कीजिए", "दीजिए", "देना", "बनाओ", "बनाना", "जोड़ो", "हटाओ", "बदलो", "ठीक", "चाहिए", "होना", "होगा", "होती", "होता",
        "में", "का", "की", "के", "को", "से", "और", "भी", "यह", "ये", "वो", "वह", "इस", "उस", "एक", "कुछ", "सब", "सारे", "अभी", "फिर", "मेरे", "मेरा", "मेरी",
        "मुझे", "तुम", "आप", "हैं", "है", "था", "थी", "वाला", "वाली", "वाले", "बात", "काम", "ऐप", "चीज़", "जैसे", "ऐसे", "वैसा", "ऐसा", "जब", "तब", "अगर",
        "please", "plz", "the", "and", "for", "with", "this", "that", "make", "add", "remove", "change", "fix", "can", "you", "want", "need", "app",
        "karo", "kar", "kardo", "bana", "banao", "aur", "bhi", "mein", "mujhe", "kaam", "abhi", "wala", "wali", "hai", "chahiye")

    fun words(s: String): Set<String> = s.lowercase()
        .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
        .filter { it.length >= 3 && it !in STOP }
        .toSet()

    /**
     * चल रहे काम [current] के बीच user ने [msg] भेजा: साफ़ \"बाद में\" = NEXT; साफ़ \"इसी में / ये भी\" = NOW;
     * वरना एक भी ख़ास शब्द मिलता है (उसी चीज़ की बात) = NOW, नहीं = NEXT (अलग काम - चल रहा काम न बिगड़े).
     */
    fun route(current: String?, msg: String): Route {
        if (current.isNullOrBlank() || msg.isBlank()) return Route.NEXT
        if (LATER.containsMatchIn(msg)) return Route.NEXT
        if (SAME.containsMatchIn(msg)) return Route.NOW
        return if (words(current).intersect(words(msg)).isNotEmpty()) Route.NOW else Route.NEXT
    }

    /** AI को: user ने काम के बीच यह जोड़ा */
    fun forAi(added: List<String>): String =
        "USER ADDED THIS WHILE YOU WERE WORKING (it belongs to the SAME task - include it now, keep everything already done, do not drop any earlier part):\n" +
            added.joinToString("\n") { "- $it" }

    /** काम खत्म हो गया पर जोड़ी बात इस्तेमाल नहीं हुई = अगला काम बनाकर कतार में (छूटे नहीं) */
    fun leftover(task: String?, added: String): String =
        "पिछले काम" + (task?.lineSequence()?.firstOrNull()?.take(80)?.let { " (\"$it\")" } ?: "") + " में यह भी करना था: $added"

    /** बीच में भेजी बात - वह किस रास्ते गई (बटन से पलटा जा सके) */
    data class Routed(val id: Long, val text: String, val route: Route)

    /** काम के बीच भेजी गई बातें (thread-safe): काम का अगला कदम इन्हें उठाता है */
    class Inbox {
        private val items = ArrayList<Pair<Long, String>>()
        private var next = 1L

        @Synchronized fun add(t: String): Long { val id = next++; items.add(id to t); return id }
        @Synchronized fun drain(): List<String> = items.map { it.second }.also { items.clear() }
        /** अभी तक उठाई नहीं गई तो निकालो (रास्ता बदलना) - उठ चुकी = null */
        @Synchronized fun take(id: Long): String? { val i = items.indexOfFirst { it.first == id }; return if (i < 0) null else items.removeAt(i).second }
        @Synchronized fun has(id: Long): Boolean = items.any { it.first == id }
        @get:Synchronized val size: Int get() = items.size
        @Synchronized fun clear() = items.clear()
    }
}
