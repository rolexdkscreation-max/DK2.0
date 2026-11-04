package com.dk.assistant.agent.social

import com.dk.assistant.NameMatch

/**
 * नवंबर 2026 (मालिक: "कोई फ़ाइल भेजनी हो तो DK फ़ाइल ढूँढे, निकाले और जिसे बोलें उसे WhatsApp पर भेजे").
 * बोली बात ("आधार कार्ड वाली PDF", "सबसे नई फोटो", "मेरा रिज़्यूमे") -> फ़ोन की फ़ाइलों में से सही फ़ाइल.
 * Android import नहीं -> JVM unit test. फ़ाइलों की सूची Android वाला हिस्सा ([com.dk.assistant.agent.android.FileFinder]) देता है.
 */
object FileMatch {
    data class Cand(val name: String, val mime: String, val uri: String, val at: Long)
    /** words = नाम वाले शब्द; kind = image / video / audio / pdf / doc / html / null; latest = "सबसे नई" */
    data class Query(val words: String, val kind: String?, val latest: Boolean)

    private val KIND = linkedMapOf(
        "image" to listOf("फोटो", "फ़ोटो", "photo", "photos", "image", "इमेज", "तस्वीर", "pic", "picture", "पिक", "स्क्रीनशॉट", "screenshot", "selfie", "सेल्फी"),
        "video" to listOf("वीडियो", "विडियो", "video", "videos", "रील", "reel"),
        "audio" to listOf("गाना", "ऑडियो", "audio", "song", "mp3", "रिकॉर्डिंग", "recording"),
        "pdf" to listOf("pdf", "पीडीएफ", "पी डी एफ"),
        "doc" to listOf("डॉक्यूमेंट", "document", "doc", "docx", "word", "excel", "xlsx", "ppt", "pptx", "शीट", "sheet"),
        "html" to listOf("वेबसाइट", "website", "साइट", "site", "html", "गेम", "game", "पेज", "page"),
    )
    private val LATEST = listOf("latest", "latest_photo", "latest_shared", "सबसे नई", "सबसे नया", "नई", "नया", "नए", "आख़िरी", "आखिरी", "last", "recent",
        "अभी वाली", "अभी वाला", "अभी की", "new", "newest")
    private val STOP = setOf("फाइल", "फ़ाइल", "file", "files", "वाली", "वाला", "वाले", "की", "का", "के", "को", "में", "मे", "से", "पर", "मेरी", "मेरा", "मेरे",
        "my", "the", "a", "an", "of", "भेजो", "भेज", "दो", "send", "whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "सबसे", "अभी", "यह", "वो", "वह", "this", "that",
        "बनाई", "बनाया", "बना", "हुई", "हुआ", "गई", "गया", "made", "dk", "डीके", "आज", "today", "कल")

    fun query(raw: String): Query {
        var t = " " + raw.lowercase().replace(Regex("[,.!?।\"'()]"), " ").replace('_', ' ') + " "
        var kind: String? = null
        for ((k, ws) in KIND) for (w in ws.sortedByDescending { it.length }) if (t.contains(" $w ")) { if (kind == null) kind = k; t = t.replace(" $w ", " ") }
        var latest = false
        for (w in LATEST.sortedByDescending { it.length }) if (t.contains(" $w ")) { latest = true; t = t.replace(" $w ", " ") }
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() && it !in STOP }.joinToString(" ")
        // "latest_photo" जैसा मॉडल का शब्द
        if (raw.trim().lowercase().startsWith("latest_photo")) kind = kind ?: "image"
        return Query(words, kind, latest || words.isEmpty())
    }

    fun kindOf(c: Cand): String {
        val n = c.name.lowercase(); val m = c.mime.lowercase()
        return when {
            m.startsWith("image/") -> "image"
            m.startsWith("video/") -> "video"
            m.startsWith("audio/") -> "audio"
            m == "application/pdf" || n.endsWith(".pdf") -> "pdf"
            m.contains("html") || n.endsWith(".html") || n.endsWith(".htm") -> "html"
            else -> "doc"
        }
    }

    /** नाम (बिना extension, _ / - हटाकर) - "Aadhar_Card_2024.pdf" -> "aadhar card 2024" */
    fun baseName(name: String) = name.substringBeforeLast('.').replace(Regex("[_\\-.]+"), " ").trim()

    /**
     * सबसे सही फ़ाइलें (बराबर अंक वाली सभी, नई पहले). खाली = कुछ नहीं मिला.
     * नाम बोला = नाम से (कम से कम 60 अंक); सिर्फ़ "सबसे नई फोटो" = उस तरह की सबसे नई.
     */
    fun best(raw: String, all: List<Cand>): List<Cand> {
        val q = query(raw)
        val pool = (if (q.kind == null) all else all.filter { kindOf(it) == q.kind }).sortedByDescending { it.at }
        if (pool.isEmpty()) return emptyList()
        if (q.words.isEmpty()) return if (q.latest && q.kind != null) listOf(pool.first()) else emptyList()
        val scored = pool.map { it to score(q.words, it.name) }
        val top = scored.maxOf { it.second }
        if (top < 60) return emptyList()
        val hits = scored.filter { it.second == top }.map { it.first }
        return if (q.latest) hits.take(1) else hits
    }

    fun score(words: String, name: String): Int {
        val base = baseName(name)
        val s = NameMatch.score(words, base)
        if (s > 0) return s
        // नाम का हिस्सा सीधे लिखा हो ("resume" -> "Rahul_Resume_final")
        val w = words.lowercase().replace(" ", "")
        return if (w.length >= 4 && base.lowercase().replace(" ", "").contains(w)) 70 else 0
    }

    /** कई मिलीं तो पूछने वाली बात */
    fun askWhich(hits: List<Cand>) = "कई फ़ाइलें मिलीं: " + hits.take(4).joinToString(", ") { it.name } + "। कौन सी भेजूँ?"
}
