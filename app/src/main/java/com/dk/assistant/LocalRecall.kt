package com.dk.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v3.88: लोकल याददाश्त का दिमाग (Android import नहीं -> JVM unit test).
 *
 *  - हर बातचीत (यूज़र + DK, आवाज़ + टेक्स्ट) फ़ोन के SQLite में ([LocalMemory]). 30 दिन बाद पूरी बातचीत मिटती है,
 *    उससे पहले हर दिन का छोटा सार ([summarize]) अलग टेबल में - वह कभी अपने-आप नहीं मिटता.
 *  - "मैंने क्या बोला था / कल क्या बात हुई थी / मैंने उस बारे में क्या बताया था" = [isRecall] + [parse] +
 *    [search] + [answer] - पूरा फ़ोन पर, कोई API/इंटरनेट नहीं. न मिले तो झूठ नहीं: [NOT_FOUND].
 *  - सार भी फ़ोन पर नियमों से (AI को बातचीत नहीं भेजी जाती - कुछ भी upload नहीं).
 */
object LocalRecall {
    // सबसे पहले (बाकी property इन्हें शुरू में ही इस्तेमाल करती हैं)
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val SPACES = Regex("\\s+")
    data class Turn(val id: Long, val who: String, val text: String, val at: Long, val src: String = "")
    data class Point(val id: Long, val day: String, val text: String, val at: Long)

    /** [from]..[to] (ms), [words] = खोजने वाले मुख्य शब्द, [dk] = DK की बात पूछी ("तुमने क्या कहा था") */
    data class Query(val from: Long, val to: Long, val words: List<String>, val dk: Boolean, val label: String, val recent: Boolean)
    data class Hits(val turns: List<Turn>, val points: List<Point>)

    const val NOT_FOUND = "बॉस, मुझे ये याद नहीं मिल रहा।"
    const val KEEP_DAYS = 30
    const val DAY_MS = 24 * 60 * 60 * 1000L

    // ------------------------------------------------------------------ "याद वाला सवाल" है?
    private const val I = "(मैंने|मैने|मै ने|मैं ने|हमने|हम ने|maine|mainne|humne|hamne)"
    private const val YOU = "(तुमने|तुम ने|आपने|आप ने|तूने|tumne|aapne)"
    private const val SAID = "(बोला|बोली|बोले|कहा|कही|कहे|बताया|बताई|बताए|पूछा|पूछी|लिखा|bola|boli|kaha|kahi|bataya|batayi|pucha|poocha|likha)"
    private const val WHAT = "(क्या|क्या-क्या|कौन सी|कौनसी|कौन-सी|किस|kya|kaun si|kaunsi|kis)"
    private val R_I_WHAT = rx("$I.{0,60}?$WHAT\\s*(-?\\s*क्या)?\\s*$SAID")
    private val R_YOU_WHAT = rx("$YOU.{0,60}?$WHAT\\s*(-?\\s*क्या)?\\s*$SAID")
    private val R_TALK = rx("$WHAT\\s*(-?\\s*क्या)?\\s*(बात|बातें|बाते|चर्चा|baat|baatein|baate)\\s*(हुई|हुईं|हुवी|की\\s*थी|किये|की\\s*थीं|hui|ki\\s*thi)")
    private val R_ABOUT = rx("$I.{0,40}?(के\\s*बारे\\s*में|ke\\s*bare\\s*me|ke\\s*baare\\s*mein).{0,30}?$SAID")
    private val R_YAAD = rx("(याद\\s*है|याद\\s*हैं|yaad\\s*hai).{0,40}?($I|$YOU).{0,40}?($SAID|बात)")
    private val R_EN = rx("\\bwhat\\s+did\\s+(i|we|you)\\s+(say|tell|ask|mention|talk|discuss|write)|\\bwhat\\s+were\\s+we\\s+talking|\\bdo\\s+you\\s+remember\\s+what\\s+i")

    fun isRecall(raw: String): Boolean {
        val t = norm(raw)
        if (t.length < 6) return false
        return R_I_WHAT.containsMatchIn(t) || R_YOU_WHAT.containsMatchIn(t) || R_TALK.containsMatchIn(t) ||
            R_ABOUT.containsMatchIn(t) || R_YAAD.containsMatchIn(t) || R_EN.containsMatchIn(t)
    }

    // ------------------------------------------------------------------ समय + शब्द
    private val R_MIN = rx("(\\d{1,3})\\s*(मिनट|minute|min|mins|minutes)")
    private val R_HOUR = rx("(\\d{1,2})\\s*(घंटे|घंटा|घण्टे|hour|hours|ghante)")
    private val R_DAYS = rx("(\\d{1,2})\\s*(दिन|din|days?)\\s*(पहले|pehle|pahle|ago)")

    fun parse(raw: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Query {
        val t = norm(raw)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun dayStart(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        var from = now - KEEP_DAYS * DAY_MS
        var to = now
        var label = ""
        var recent = false
        var day: LocalDate? = null
        R_MIN.find(t)?.let { m ->
            val n = m.groupValues[1].toLong().coerceIn(1, 600)
            from = now - (n + 10) * 60_000L; label = "$n मिनट पहले"; recent = true
        }
        if (!recent) R_HOUR.find(t)?.let { m ->
            val n = m.groupValues[1].toLong().coerceIn(1, 48)
            from = now - (n + 1) * 3_600_000L; label = "$n घंटे पहले"; recent = true
        }
        if (!recent && rx("(अभी|थोड़ी\\s*देर|थोड़ी\\s*देर|थोडी\\s*देर|अभी-अभी|abhi|thodi\\s*der|just\\s*now|a\\s*moment\\s*ago|few\\s*minutes)").containsMatchIn(t)) {
            from = now - 20 * 60_000L; label = "अभी थोड़ी देर पहले"; recent = true
        }
        if (!recent) {
            val dm = R_DAYS.find(t)
            day = when {
                dm != null -> today.minusDays(dm.groupValues[1].toLong().coerceIn(1, 60))
                rx("(परसों|parso|parson|day\\s*before\\s*yesterday)").containsMatchIn(t) -> today.minusDays(2)
                rx("(^|\\s)(कल|kal|yesterday)(\\s|$|\\?|,|।)").containsMatchIn(t) -> today.minusDays(1)
                rx("(^|\\s)(आज|aaj|today)(\\s|$|\\?|,|।)").containsMatchIn(t) -> today
                else -> null
            }
            if (day != null) {
                from = dayStart(day); to = minOf(now, dayStart(day.plusDays(1)) - 1)
                label = dayLabel(day, today)
            } else if (rx("(पिछले\\s*हफ़्ते|पिछले\\s*हफ्ते|पिछले\\s*सप्ताह|last\\s*week|pichhle\\s*hafte|is\\s*hafte|इस\\s*हफ़्ते|इस\\s*हफ्ते)").containsMatchIn(t)) {
                from = now - 7 * DAY_MS; label = "पिछले हफ़्ते"
            }
            // दिन का हिस्सा (सुबह/दोपहर/शाम/रात) - दिन न बोला हो तो आज
            val part = when {
                rx("(सुबह|subah|morning)").containsMatchIn(t) -> 5 to 12
                rx("(दोपहर|dopahar|afternoon)").containsMatchIn(t) -> 12 to 17
                rx("(शाम|shaam|sham|evening)").containsMatchIn(t) -> 17 to 21
                rx("(रात|raat|night)").containsMatchIn(t) -> 20 to 24
                else -> null
            }
            if (part != null) {
                val d = day ?: today
                from = dayStart(d) + part.first * 3_600_000L
                to = minOf(now, dayStart(d) + part.second * 3_600_000L)
                label = (if (day == null) "आज" else dayLabel(d, today)) + " " + partName(part.first)
            }
        }
        val dk = rx(YOU).containsMatchIn(t) || rx("\\bwhat\\s+did\\s+you\\b").containsMatchIn(t)
        return Query(from, to, keywords(raw), dk, label, recent)
    }

    private fun partName(h: Int) = when (h) { 5 -> "सुबह"; 12 -> "दोपहर"; 17 -> "शाम"; else -> "रात" }
    private fun dayLabel(d: LocalDate, today: LocalDate) = when (d) {
        today -> "आज"
        today.minusDays(1) -> "कल"
        today.minusDays(2) -> "परसों"
        else -> d.format(DateTimeFormatter.ofPattern("d MMMM", Locale("hi", "IN")))
    }

    private val STOP = (
        "मैंने मैने मैं मै हमने हम तुमने तुम आपने आप तूने तुझे मुझे मुझसे मेरे मेरा मेरी हमारी हमारा हमारे तुम्हें तुम्हे आपको " +
        "क्या क्या-क्या कौन कौनसी कौन-सी सी किस किसके था थी थे थीं है हैं हो हूँ हूं होगा बोला बोली बोले कहा कही कहे बताया बताई बताए " +
        "पूछा पूछी लिखा बात बातें बाते बातचीत चर्चा हुई हुईं की के का को में मे से पर ने और भी तो ही उस इस उसके इसके उन इन बारे " +
        "अभी अभी-अभी थोड़ी थोड़ी थोडी देर पहले आज कल परसों दिन दिनों हफ़्ते हफ्ते सप्ताह पिछले सुबह शाम दोपहर रात मिनट घंटे घंटा " +
        "याद वो वह ये यह जो कि एक बॉस डीके सर यार ज़रा जरा बताओ बताना बता दो सकते सकती " +
        "maine mainne humne hamne tumne aapne mujhe mera meri mere kya kaun kis tha thi the hai hain bola boli kaha kahi bataya pucha poocha " +
        "baat baatein hui ki ke ka ko me mein se par ne aur bhi to us is uske bare baare abhi thodi der pehle pahle aaj kal parso din " +
        "yaad wo woh ye yeh jo boss dk sir yaar batao " +
        "what did i we you say said tell told ask asked mention talk talked discuss about the a an of to in on at was were is do remember " +
        "yesterday today ago minutes minute hours hour just now last week morning evening night that this it"
        ).split(" ").filter { it.isNotBlank() }.map { norm(it) }.toSet()

    fun keywords(raw: String): List<String> =
        WORD.findAll(norm(raw)).map { it.value }
            .filter { it.length >= 2 && it !in STOP && !it.all { c -> c.isDigit() } }
            .distinct().take(8).toList()

    // ------------------------------------------------------------------ खोज
    /**
     * [turns] = समय के क्रम में (पुरानी पहले). याद वाले सवाल और उनके ठीक बाद DK के याद वाले जवाब खोज में नहीं.
     * शब्द हों = जितने ज़्यादा शब्द मिलें उतना ऊपर (बराबर हो तो नई बात ऊपर). शब्द न हों = उस समय की आख़िरी बातें.
     */
    fun search(q: Query, turns: List<Turn>, points: List<Point>, max: Int = 3): Hits {
        val skip = HashSet<Long>()
        for ((i, x) in turns.withIndex()) {
            if (x.who == "u" && isRecall(x.text)) {
                skip.add(x.id)
                turns.getOrNull(i + 1)?.takeIf { it.who == "d" }?.let { skip.add(it.id) }
            }
            if (x.who == "u" && isForget(x.text)) skip.add(x.id)
        }
        val who = if (q.dk) "d" else "u"
        val inTime = turns.filter { it.at in q.from..q.to && it.id !in skip && it.who == who && it.text.isNotBlank() }
        val tHits = if (q.words.isEmpty()) inTime.takeLast(max)
        else inTime.map { it to score(q.words, it.text) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Turn, Int>> { it.second }.thenByDescending { it.first.at })
            .take(max).map { it.first }.sortedBy { it.at }
        val pHits = if (q.words.isEmpty()) {
            if (q.recent || tHits.isNotEmpty()) emptyList() else points.filter { it.at in q.from..q.to || q.label.isBlank() }.takeLast(max)
        } else points.map { it to score(q.words, it.text) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Point, Int>> { it.second }.thenByDescending { it.first.at })
            .take(max).map { it.first }
        return Hits(tHits, pHits)
    }

    /** कितने शब्द मिले (हिंदी रूप: "राहुल"/"राहुल को", "प्रोजेक्ट"/"प्रोजेक्ट्स" - शुरुआती हिस्सा भी चलेगा) */
    fun score(words: List<String>, text: String): Int {
        val n = norm(text)
        val toks = WORD.findAll(n).map { it.value }.toList()
        return words.count { w -> n.contains(w) || (w.length >= 4 && toks.any { it.startsWith(w.dropLast(1)) || w.startsWith(it) && it.length >= 4 }) }
    }

    // ------------------------------------------------------------------ जवाब (फ़ोन पर बना)
    fun answer(q: Query, h: Hits, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (h.turns.isEmpty() && h.points.isEmpty()) return NOT_FOUND
        val who = if (q.dk) "मैंने" else "आपने"
        return buildString {
            if (h.turns.isNotEmpty()) {
                if (h.turns.size == 1) {
                    val x = h.turns[0]
                    append("बॉस, ${whenText(x.at, now, zone)} $who कहा था: “${x.text.trim().take(300)}”")
                } else {
                    append("बॉस, ${q.label.ifBlank { "पहले" }} $who ये बातें कही थीं:")
                    h.turns.forEach { append("\n• “${it.text.trim().take(220)}” (${whenText(it.at, now, zone)})") }
                }
            }
            if (h.points.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(if (h.turns.isEmpty()) "बॉस, पुरानी बातों के सार में यह मिला:" else "सार में यह भी है:")
                h.points.forEach { append("\n• ${it.text.trim().take(220)} (${dayText(it.day, now, zone)})") }
            }
        }
    }

    fun whenText(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val ago = now - at
        if (ago in 0 until 60_000L) return "अभी"
        if (ago in 60_000L until 60 * 60_000L) return "${ago / 60_000L} मिनट पहले"
        val z = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = z.format(DateTimeFormatter.ofPattern("h:mm", Locale.US))
        val part = when (z.hour) { in 5..11 -> "सुबह"; in 12..16 -> "दोपहर"; in 17..20 -> "शाम"; else -> "रात" }
        return "${dayLabel(z.toLocalDate(), today)} $part $time बजे"
    }

    private fun dayText(day: String, now: Long, zone: ZoneId): String = try {
        dayLabel(LocalDate.parse(day), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    } catch (_: Exception) { day }

    fun dayOf(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()

    // ------------------------------------------------------------------ सेव करने से पहले
    /** null = सेव मत करो (खाली / पासवर्ड-बैंक वाली बात). वरना निजी नंबर हटाकर बाकी बात */
    fun storable(text: String): String? {
        val t = text.trim().trimEnd('…').trim()
        if (t.length < 2) return null
        if (!Sensitive.isSensitive(t)) return t.take(4000)
        return Sensitive.redact(t).takeIf { it.length >= 2 && !Sensitive.isSensitive(it) }?.take(4000)
    }

    // ------------------------------------------------------------------ "ये भूल जाओ"
    private val FORGET = rx("(भूल\\s*जाओ|भूल\\s*जा|भुला\\s*दो|मिटा\\s*दो|डिलीट\\s*कर\\s*दो|bhool\\s*jao|bhul\\s*jao|bhula\\s*do|forget\\s+(it|this|that|about))")
    fun isForget(raw: String) = FORGET.containsMatchIn(norm(raw))

    /** "ये/वो/इसे" भूल जाओ = पिछली बात (कोई खास शब्द नहीं) */
    fun forgetsLast(about: String): Boolean {
        val k = keywords(about).filterNot { it in THIS }
        return k.isEmpty()
    }
    private val THIS = setOf("इसे", "उसे", "इसको", "उसको", "वाली", "वाला", "बात", "it", "this", "that", "ise", "use", "bhool", "jao", "भूल", "जाओ", "मिटा", "दो", "forget").map { norm(it) }.toSet()

    /** "ये भूल जाओ" से पहले की आख़िरी असली बात (याद/भूल वाले सवाल नहीं) */
    fun lastRealUser(turns: List<Turn>, before: Long): Turn? =
        turns.lastOrNull { it.who == "u" && it.at < before && !isForget(it.text) && !isRecall(it.text) && it.text.trim().length > 2 }

    // ------------------------------------------------------------------ सार (फ़ोन पर, नियम से)
    private data class Rule(val cat: String, val re: Regex)
    private val RULES = listOf(
        Rule("पसंद", rx("(पसंद|नापसंद|अच्छा\\s*लगता|अच्छी\\s*लगती|अच्छे\\s*लगते|बुरा\\s*लगता|नफ़रत|नफरत|फ़ेवरेट|फेवरेट|favou?rite|i\\s+(like|love|hate)|pasand|accha\\s*lagta)")),
        Rule("लोग", rx("(मेरा\\s*नाम|मेरी\\s*(पत्नी|बीवी|बहन|माँ|मां|मम्मी|बेटी|दोस्त|गर्लफ्रेंड|मंगेतर|दादी|नानी)|मेरे\\s*(पापा|पिताजी|भाई|बेटे|बेटा|दोस्त|पति|बॉस|दादा|नाना|चाचा|मामा)|मेरा\\s*(भाई|बेटा|दोस्त|बॉस)|का\\s*नाम\\s*\\S+\\s*है|my\\s+(name|wife|brother|sister|mother|father|son|daughter|friend|boss)|mera\\s*naam)")),
        Rule("काम", rx("(प्रोजेक्ट|project|मेरी\\s*कंपनी|मेरा\\s*बिज़नेस|बिजनेस|business|ऑफिस|office|नौकरी|जॉब|\\bjob\\b|ऐप\\s*बना|app\\s*bana|रिपॉजिटरी|repository|क्लाइंट|client|दुकान)")),
        Rule("तारीख", rx("(जन्मदिन|बर्थडे|birthday|सालगिरह|anniversary|शादी|मीटिंग|meeting|परीक्षा|एग्ज़ाम|एग्जाम|exam|इंटरव्यू|interview|डेडलाइन|deadline|अपॉइंटमेंट|appointment|\\d{1,2}\\s*(जनवरी|फ़रवरी|फरवरी|मार्च|अप्रैल|मई|जून|जुलाई|अगस्त|सितंबर|सितम्बर|अक्टूबर|अक्तूबर|नवंबर|नवम्बर|दिसंबर|दिसम्बर|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec))")),
        Rule("वादा/फ़ैसला", rx("(वादा|promise|तय\\s*किया|तय\\s*कर\\s*लिया|फ़ैसला|फैसला|decide|सोच\\s*लिया|ठान\\s*लिया|याद\\s*रखना|याद\\s*रखो|भूलना\\s*मत|ज़रूर\\s*करूँगा|करूँगा|करूंगा|i\\s+will|i'll)")),
        Rule("ज़रूरी", rx("(ज़रूरी|जरूरी|important|मेरा\\s*पता|address|मेरा\\s*नंबर|मेरी\\s*गाड़ी|मेरी\\s*गाडी|एलर्जी|allergy|दवा|दवाई|medicine)")),
    )
    private val TRIVIAL = rx("^(हाँ|हां|नहीं|ठीक है|ओके|ok|okay|हेलो|हैलो|hello|hi|hey|थैंक्स|धन्यवाद|शुक्रिया|thanks|bye|बाय|अच्छा|हम्म|hmm)[\\s.!?।]*$")

    /** एक दिन की बातों में से सिर्फ़ याद रखने लायक (यूज़र की बात, निजी नंबर/पासवर्ड नहीं, फ़ोन के छोटे-मोटे हुक्म नहीं) */
    fun summarize(turns: List<Turn>, max: Int = 12): List<String> {
        val out = LinkedHashMap<String, String>()
        for (x in turns) {
            if (x.who != "u") continue
            val t = x.text.trim()
            if (t.length < 8 || TRIVIAL.matches(norm(t)) || isRecall(t) || isForget(t) || Sensitive.isSensitive(t)) continue
            for (s in t.split(rx("(?<=[।.!?\\n])\\s*")).map { it.trim() }.filter { it.length >= 8 }) {
                val n = norm(s)
                val r = RULES.firstOrNull { it.re.containsMatchIn(n) } ?: continue
                // "अलार्म लगाओ / यूट्यूब खोलो" जैसे हुक्म (बिना पसंद/तारीख वाली बात) याद लायक नहीं
                if (r.cat == "वादा/फ़ैसला" && rx("(लगाओ|खोलो|भेजो|चलाओ|बंद\\s*करो|कर\\s*दो)").containsMatchIn(n) && !rx("(याद\\s*रख|भूलना\\s*मत)").containsMatchIn(n)) continue
                val key = WORD.findAll(n).map { it.value }.joinToString(" ")
                if (key.isBlank() || out.keys.any { it == key || it.contains(key) || key.contains(it) }) continue
                out[key] = "${r.cat}: ${s.take(200)}"
                if (out.size >= max) return out.values.toList()
            }
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ 30 दिन
    fun cutoff(now: Long) = now - KEEP_DAYS * DAY_MS

    /** मिटाने से पहले जिन दिनों का सार बनना है: [cutoff] से पुराने + आज से पहले वाले, जिनका सार अभी नहीं बना */
    fun daysToSummarize(turnDays: Collection<String>, doneDays: Set<String>, today: String): List<String> =
        turnDays.filter { it < today && it !in doneDays }.distinct().sorted()

    /** prompt के लिए छोटा सार (पूरी पुरानी बातचीत की जगह) */
    fun promptBlock(points: List<Point>, maxChars: Int = 600): String {
        if (points.isEmpty()) return ""
        val sb = StringBuilder("\nLOCAL MEMORY (short notes from earlier days, saved only on this phone):")
        for (p in points.sortedByDescending { it.at }) {
            val line = "\n- ${p.day}: ${p.text.take(160)}"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        sb.append("\nIf the user asks what he said earlier / what we talked about (मैंने क्या बोला था, कल क्या बात हुई थी), call recall_conversation - never guess.\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ शब्द

    fun norm(s: String): String = s.lowercase(Locale.ROOT)
        .replace("़", "").replace("ँ", "ं")
        .replace('’', '\'').replace(SPACES, " ").trim()

    /** नुक़्ता/चंद्रबिंदु वाला और बिना वाला एक जैसा (थोड़ी = थोडी, हफ़्ते = हफ्ते) - pattern पर भी वही */
    private fun rx(p: String) = Regex(p.replace("़", "").replace("ँ", "ं"))
}
