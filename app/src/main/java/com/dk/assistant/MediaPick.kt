package com.dk.assistant

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * नवंबर 2026 (मालिक): "आज के सारे स्क्रीनशॉट डिलीट करो", "कल का स्क्रीनशॉट डिलीट करो",
 * "ऊपर से 50 फोटो डिलीट करो", "नीचे से 10 फोटो मिटाओ" - सब सीधे फ़ोन पर (बिना AI एजेंट).
 *
 * पहले सिर्फ़ "आखिरी 1-20 फोटो" (LocalRouter.deleteMedia) - बाकी सब AI एजेंट के पास जाता था, जो गैलरी में
 * टैप करते-करते रुक जाता था. अब गिनती (500 तक), दिन (आज / कल / परसों), ऊपर (नई) / नीचे (पुरानी) समझते हैं.
 * गोल-मोल ("कुछ फोटो", "फोटो डिलीट करो", फ़ोन की "सारी फोटो") = पहले पूछो, अंदाज़े से कुछ नहीं मिटाते.
 * सब ट्रैश में जाता है (30 दिन तक वापस) - MediaDelete.run.
 */
object MediaPick {
    const val MAX = 500

    enum class From { NEW, OLD }

    /** day: -1 = कोई दिन नहीं, 0 = आज, 1 = कल, 2 = परसों. all = उस दिन / उस तरह की सारी (count = MAX तक) */
    data class Spec(val kind: String, val count: Int, val day: Int = -1, val from: From = From.NEW, val all: Boolean = false) {
        val shot get() = kind == "screenshot"
        fun toJson(): JSONObject = JSONObject().put("type", "delete_media").put("kind", kind).put("count", count)
            .put("day", day).put("from", if (from == From.OLD) "old" else "new").put("all", all)

        /** "आज के सारे स्क्रीनशॉट" / "ऊपर की 50 फ़ोटो" - बोलने लायक */
        fun hi(n: Int = count): String {
            val what = if (shot) "स्क्रीनशॉट" else "फ़ोटो"
            val d = when (day) { 0 -> "आज के "; 1 -> "कल के "; 2 -> "परसों के "; else -> "" }
            return when {
                all && day >= 0 -> "${d}सारे $n $what"
                all -> "सारे $n $what"
                from == From.OLD -> "${d}सबसे पुराने $n $what"
                day >= 0 -> "${d}सबसे नए $n $what"
                n == 1 -> "आखिरी $what"
                else -> "आखिरी $n $what"
            }
        }

        fun en(): String {
            val d = when (day) { 0 -> " from today"; 1 -> " from yesterday"; 2 -> " from the day before yesterday"; else -> "" }
            val k = if (shot) "screenshot(s)" else "photo(s)"
            return when {
                all -> "moved all $k$d to trash"
                from == From.OLD -> "moved the oldest $count $k$d to trash"
                else -> "moved the last $count $k$d to trash"
            }
        }
    }

    sealed class Out {
        data class Go(val spec: Spec) : Out()
        data class Ask(val msg: String) : Out()
    }

    fun fromJson(a: JSONObject): Spec = Spec(
        kind = if (a.optString("kind", "photo") == "screenshot") "screenshot" else "photo",
        count = a.optInt("count", 1).coerceIn(1, MAX),
        day = a.optInt("day", -1).coerceIn(-1, 30),
        from = if (a.optString("from") == "old") From.OLD else From.NEW,
        all = a.optBoolean("all", false),
    )

    private val DEL = listOf("डिलीट", "डिलिट", "delete", "dilit", "मिटा", "हटा", "remove", "trash", "ट्रैश", "साफ़ कर", "साफ कर", "उड़ा दो", "उड़ाओ")
    private val PHOTO = listOf("photo", "फोटो", "फ़ोटो", "तस्वीर", "picture", "pic", "image", "इमेज", "selfie", "सेल्फी", "सेल्फ़ी", "pics")
    private val SHOT = listOf("screenshot", "स्क्रीनशॉट", "स्क्रीन शॉट", "स्क्रीनशॉर्ट", "screen shot", "स्क्रीनशाट")
    private val SKIP = listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "chat", "चैट", "मैसेज", "message", "instagram", "इंस्टा",
        "facebook", "वीडियो", "video", "फ़ाइल", "फाइल", "file", "pdf")
    private val THIS = listOf("यह", "ये", "इस", "this", "ye", "yeh", "खुली", "स्क्रीन पर")
    private val OLD = listOf("नीचे से", "नीचे वाल", "नीचे की", "नीचे के", "neeche", "niche se", "bottom", "पुरान", "purani", "purane", "oldest", "old ")
    private val NEW = listOf("ऊपर से", "ऊपर वाल", "ऊपर की", "ऊपर के", "upar", "top", "आखिरी", "आख़िरी", "last", "latest", "नई", "नए", "नयी", "new", "recent", "हाल की", "अभी")
    private val ALL = listOf("सारी", "सारे", "सारा", "सभी", "सब ", "सबको", "all", "पूरी", "पूरे", "saari", "saare", "sab ", "sabhi", "जितनी", "जितने", "जितना", "jitne", "jitni")
    private val SOME = listOf("कुछ", "kuch", "some", "few", "थोड़ी", "थोड़े")
    private val DAY = mapOf("आज" to 0, "today" to 0, "aaj" to 0, "aj" to 0, "कल" to 1, "yesterday" to 1, "kal" to 1, "परसों" to 2, "parso" to 2, "parson" to 2)
    private val NUM = mapOf("एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9,
        "दस" to 10, "ग्यारह" to 11, "बारह" to 12, "पंद्रह" to 15, "बीस" to 20, "पच्चीस" to 25, "तीस" to 30, "चालीस" to 40, "पचास" to 50, "साठ" to 60,
        "सत्तर" to 70, "अस्सी" to 80, "नब्बे" to 90, "सौ" to 100, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10,
        "twenty" to 20, "fifty" to 50, "hundred" to 100, "ek" to 1, "teen" to 3, "char" to 4, "panch" to 5, "das" to 10, "bees" to 20, "pachas" to 50, "sau" to 100)
    // "कर दो" / "हटा दो" का "दो" गिनती नहीं
    // (Java का \\b देवनागरी मात्रा पर नहीं टिकता - इसलिए खाली जगह देखकर)
    private val GLUE = Regex("(कर|दे|हटा|मिटा|डिलीट|डिलिट|उड़ा|साफ़|साफ)\\s+(दो|दीजिए|दीजिये|देना)(?=\\s|$)|(?<=\\s)(kar|de|hata|mita|delete)\\s+do(?=\\s|$)")

    private fun has(t: String, w: List<String>) = w.any { t.contains(it) }

    /** null = यह फ़ोटो मिटाने वाली बात नहीं (या स्क्रीन पर खुली "यह फ़ोटो" - वह एजेंट देखे) */
    fun parse(raw: String): Out? {
        val t = " " + raw.trim().lowercase().replace(Regex("[०-९]")) { m -> ('0' + (m.value[0] - '०')).toString() } + " "
        if (LocalRouter.negated(t) || !has(t, DEL)) return null
        val shot = has(t, SHOT) || Regex("\\bss\\b").containsMatchIn(t)
        if (!shot && !has(t, PHOTO)) return null
        if (has(t, SKIP)) return null
        val clean = GLUE.replace(t, " ")
        val words = clean.split(Regex("[\\s,.!?।\\-–/]+")).filter { it.isNotEmpty() }
        val day = words.firstNotNullOfOrNull { DAY[it] }
        val count = words.firstNotNullOfOrNull { w -> w.toIntOrNull() ?: NUM[w] }
        val old = has(clean, OLD)
        val newer = has(clean, NEW)
        val all = has(clean, ALL)
        val kind = if (shot) "screenshot" else "photo"
        val what = if (shot) "स्क्रीनशॉट" else "फ़ोटो"
        if (count == null && day == null && !all && has(t, THIS)) return null
        val from = if (old && !newer) From.OLD else From.NEW
        return when {
            count != null && count < 1 -> null
            count != null && count > MAX -> Out.Ask("एक बार में ज़्यादा से ज़्यादा $MAX $what मिटा सकता हूँ - $MAX मिटाऊँ, या कम बताइए?")
            count != null -> Out.Go(Spec(kind, count, day ?: -1, from, all = false))
            day != null -> Out.Go(Spec(kind, MAX, day, from, all = true))
            all && shot -> Out.Go(Spec(kind, MAX, -1, from, all = true))
            all -> Out.Ask("फ़ोन की सारी फ़ोटो एक साथ नहीं मिटाता - आज की, कल की, या कितनी (ऊपर से नई / नीचे से पुरानी) बताइए।")
            has(clean, SOME) -> Out.Ask("कितनी $what मिटाऊँ - और ऊपर से (नई) या नीचे से (पुरानी)?")
            old || newer -> Out.Go(Spec(kind, 1, -1, from))
            else -> Out.Ask("कौन-सी $what मिटाऊँ - आखिरी वाली, आज की, या कितनी (ऊपर से / नीचे से)?")
        }
    }

    /** Live / AgentRunner: पहले पुराना पक्का रास्ता (आखिरी 1-20), फिर नया */
    fun quick(raw: String): Out? = LocalRouter.deleteMedia(raw)?.let { Out.Go(fromJson(it.action)) } ?: parse(raw)

    /** "DELETED: ..." / "NEEDS USER CHOICE (...): ... Ask the user" -> बोलने लायक हिंदी */
    fun spoken(r: String): String {
        var t = r.trim()
        Regex("^[A-Z_ ]+(\\([^)]*\\))?\\s*:\\s*").find(t)?.let { t = t.substring(it.range.last + 1) }
        t = t.substringBefore(" Ask the user").trim()
        return t.ifBlank { "हो नहीं पाया।" }
    }

    /** उस दिन की शुरुआत / अंत (सेकंड, MediaStore DATE_ADDED) - फ़ोन के अपने समय-क्षेत्र में */
    fun dayRange(day: Int, now: Long, tz: TimeZone = TimeZone.getDefault()): LongRange {
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -day)
        }
        val start = c.timeInMillis / 1000
        c.add(Calendar.DAY_OF_MONTH, 1)
        return start until c.timeInMillis / 1000
    }
}
