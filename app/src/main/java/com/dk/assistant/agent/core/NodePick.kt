package com.dk.assistant.agent.core

/**
 * v3.156 (मालिक: "ChatGPT में prompt लिख देता है पर भेज नहीं पाता / टैप नहीं कर पाता"):
 * स्क्रीन पढ़ते समय पहले सिर्फ़ शुरू की 140-220 चीज़ें गिनी जाती थीं - लंबी chat में पुराने संदेश ही
 * जगह भर देते थे और नीचे वाला "भेजें" बटन / लिखने का खाना सूची में आता ही नहीं था।
 * अब पूरी स्क्रीन पढ़ते हैं, और सीमा से ज़्यादा हो तो ज़रूरी चीज़ें पहले रखते हैं:
 * लिखने का खाना > दबने वाले बटन > नीचे का हिस्सा (composer / send / tabs) > नाम वाली चीज़ें।
 * क्रम वही रहता है जो स्क्रीन पर है (ऊपर से नीचे)।
 */
object NodePick {
    /** पूरी स्क्रीन में ज़्यादा से ज़्यादा इतनी चीज़ें पढ़ो (पहले 140/220 पर रुक जाते थे) */
    const val RAW_MAX = 1500
    /** पेड़ की गहराई (Compose ऐप बहुत गहरे होते हैं - पहले 40/45 पर कट जाता था) */
    const val DEPTH_MAX = 90

    data class Cand(
        val editable: Boolean,
        val clickable: Boolean,
        val labeled: Boolean,
        val top: Int,
        val bottom: Int,
    )

    fun score(c: Cand, screenH: Int): Int {
        var s = 0
        if (c.editable) s += 100
        if (c.clickable) s += 40
        // नीचे का हिस्सा: लिखने का खाना, भेजें, नीचे वाले tab - chat ऐप में सबसे ज़रूरी
        if (screenH > 0 && c.bottom >= screenH * 0.70) s += 30
        if (c.labeled) s += 10
        return s
    }

    /** किन index को रखना है (स्क्रीन वाले क्रम में). cap से कम हों तो सब */
    fun keep(cands: List<Cand>, cap: Int, screenH: Int): List<Int> {
        if (cands.size <= cap) return cands.indices.toList()
        return cands.indices
            .sortedWith(compareByDescending<Int> { score(cands[it], screenH) }.thenBy { it })
            .take(cap)
            .sorted()
    }
}
