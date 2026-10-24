package com.dk.assistant

/**
 * DK AI ब्रीफ़ Part 04: Gemini Live कभी-कभी वही tool call (वही id) दोबारा भेज देता है
 * (दोबारा जुड़ने / session resume पर). वही id दूसरी बार आए तो काम दोबारा नहीं - वरना दो बार call/मैसेज.
 * सीमित याद (LinkedHashSet, सबसे पुराना पहले हटता है). Android import नहीं -> JVM test.
 */
class ToolCallDeduplicator(private val max: Int = 256) {
    private val seen = LinkedHashSet<String>()

    /** true = नया call, चलाओ. false = यह id पहले आ चुकी है, छोड़ो. खाली id हमेशा नया (पहचान नहीं). */
    @Synchronized fun firstTime(id: String?): Boolean {
        val k = id?.trim().orEmpty()
        if (k.isEmpty()) return true
        if (!seen.add(k)) return false
        while (seen.size > max) seen.remove(seen.first())
        return true
    }

    @Synchronized fun size(): Int = seen.size
    @Synchronized fun clear() = seen.clear()
}
