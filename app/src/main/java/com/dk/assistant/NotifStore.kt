package com.dk.assistant

/**
 * v3.41: DK लाइव चलते समय आए notification याद (Android import नहीं -> JVM unit test).
 *
 * DKNotifListener (Android) हर नया notification यहाँ डालता है. Gemini पूछे ("क्या notification आया?")
 * तो [describe] से बताता है; "इसको रिप्लाई कर दो" पर [findForReply] सही notification चुनता है.
 * सिर्फ़ memory में (फ़ोन बंद/ऐप बंद = साफ़), सबसे नए [MAX] तक.
 */
object NotifStore {
    const val MAX = 30

    data class Item(
        val key: String,        // Android notification key (उसी से reply action मिलता है)
        val pkg: String,
        val app: String,        // "WhatsApp"
        val title: String,      // भेजने वाला / विषय: "Rahul"
        val text: String,       // संदेश
        val at: Long,
        val canReply: Boolean,
        var replied: Boolean = false,
    )

    private val items = ArrayList<Item>()

    @Synchronized fun add(i: Item) {
        if (i.title.isBlank() && i.text.isBlank()) return
        // वही notification दोबारा (update / नया संदेश उसी chat में) -> पुराना हटाकर सबसे ऊपर
        items.removeAll { it.key == i.key }
        items.add(0, i)
        while (items.size > MAX) items.removeAt(items.size - 1)
    }

    @Synchronized fun remove(key: String) { items.removeAll { it.key == key } }
    @Synchronized fun clear() = items.clear()
    @Synchronized fun all(): List<Item> = items.toList()
    @Synchronized fun get(key: String): Item? = items.firstOrNull { it.key == key }
    @Synchronized fun markReplied(key: String) { items.firstOrNull { it.key == key }?.replied = true }

    /** [since] के बाद के (0 = सब), [app] दिया तो सिर्फ़ उस ऐप/भेजने वाले के */
    @Synchronized fun recent(since: Long = 0L, app: String = ""): List<Item> {
        val q = norm(app)
        return items.filter { it.at >= since && (q.isBlank() || norm(it.app).contains(q) || norm(it.title).contains(q) || norm(it.pkg).contains(q)) }
    }

    /**
     * किसे reply? [to] = नाम या ऐप ("Rahul", "WhatsApp"); खाली = सबसे नया जिसका reply हो सकता है ("इसको").
     * पहले नाम पूरा मिले, फिर हिस्सा मिले, फिर ऐप.
     */
    @Synchronized fun findForReply(to: String): Item? {
        val can = items.filter { it.canReply }
        val q = norm(to)
        if (q.isBlank() || q in GENERIC) return can.firstOrNull()
        return can.firstOrNull { norm(it.title) == q }
            ?: can.firstOrNull { norm(it.title).contains(q) || (norm(it.title).isNotBlank() && q.contains(norm(it.title))) }
            ?: can.firstOrNull { norm(it.app).contains(q) || q.contains(norm(it.app)) }
    }

    /** "इसको / उसको / last" जैसे शब्द = सबसे नया */
    private val GENERIC = setOf("इसको", "इसे", "उसको", "उसे", "इस", "उस", "last", "latest", "this", "it", "them", "him", "her", "aakhri", "isko", "usko")

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), "").trim()

    /** Gemini के लिए (बोलने लायक) - सबसे नए पहले */
    fun describe(list: List<Item>, now: Long, max: Int = 8): String {
        if (list.isEmpty()) return "No new notifications."
        return buildString {
            append("${list.size} notification(s), newest first:\n")
            list.take(max).forEachIndexed { n, i ->
                val ago = ((now - i.at) / 60_000).let { if (it <= 0) "just now" else "$it min ago" }
                append("${n + 1}. [${i.app}] ")
                if (i.title.isNotBlank()) append("${i.title}: ")
                append(i.text.take(200))
                append(" ($ago")
                if (i.canReply) append(", can reply")
                if (i.replied) append(", already replied")
                append(")\n")
            }
            if (list.size > max) append("…and ${list.size - max} more.")
        }.trim()
    }

    /** इसे notification मत गिनो: अपना ऐप, चलते रहने वाले (music/download), खाली */
    fun skip(pkg: String, ownPkg: String, ongoing: Boolean, groupSummary: Boolean): Boolean =
        pkg == ownPkg || ongoing || groupSummary || pkg == "android" || pkg == "com.android.systemui"
}
