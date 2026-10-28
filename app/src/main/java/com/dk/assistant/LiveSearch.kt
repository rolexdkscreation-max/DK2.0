package com.dk.assistant

/**
 * v3.80: लाइव (ताज़ा) जानकारी - एक नहीं, कई तरफ़ से खोजकर, मिलाकर पक्का जवाब.
 *
 * 1) [plan]: सवाल देखकर कौन-कौन सी खोज - हमेशा "आम + ताज़ा ख़बर"; सरकारी सवाल (योजना, नियम, फ़ॉर्म, भर्ती, रिज़ल्ट...)
 *    पर पहले सरकारी/आधिकारिक वेबसाइटें (gov.in, nic.in, PIB, मंत्रालय, RBI, UIDAI, ECI...).
 * 2) हर खोज अलग Google Search (Gemini) - साथ-साथ (parallel). हर खोज तारीख़ और वेबसाइट का नाम बताती है.
 * 3) [mergeSystem]: सारे नतीजे मिलाकर एक जवाब - जिस पर स्रोत सहमत हों वही पक्का; सरकारी > बड़े अख़बार > बाकी;
 *    फ़र्क हो तो साफ़ बताओ; जानकारी कितनी ताज़ा, वह भी.
 * 4) [remember]/[sourcesLine]: आख़िरी खोज के स्रोत याद - यूज़र पूछे "यह कहाँ से पता किया" तो वेबसाइटों के नाम.
 *
 * Android import नहीं -> JVM test.
 */
object LiveSearch {
    data class Angle(val id: String, val instruction: String)

    /** सरकारी / आधिकारिक जानकारी वाले शब्द */
    private val GOV = listOf(
        "सरकार", "सरकारी", "government", "govt", "gov ", "योजना", "yojana", "scheme", "स्कीम", "मंत्रालय", "ministry", "मंत्री",
        "प्रधानमंत्री", "मुख्यमंत्री", "pm ", "cm ", "आधार", "aadhaar", "aadhar", "पैन कार्ड", "pan card", "राशन", "ration", "पेंशन", "pension",
        "किसान", "kisan", "gst", "जीएसटी", "इनकम टैक्स", "income tax", "आयकर", "itr", "टैक्स", "rto", "ड्राइविंग लाइसेंस", "driving licence",
        "driving license", "पासपोर्ट", "passport", "वोटर", "voter", "चुनाव आयोग", "election commission", "भर्ती", "vacancy", "recruitment",
        "नौकरी", "sarkari", "एडमिट कार्ड", "admit card", "परीक्षा", "exam", "रिजल्ट", "रिज़ल्ट", "result", "बोर्ड", "upsc", "mpsc", "ssc",
        "रेलवे", "railway", "irctc", "आवेदन", "apply", "फॉर्म", "फ़ॉर्म", "form ", "सब्सिडी", "subsidy", "लाभार्थी", "beneficiary",
        "rbi", "आरबीआई", "sebi", "epf", "pf ", "uidai", "नगर निगम", "municipal", "ग्राम पंचायत", "panchayat", "तहसील", "कलेक्टर",
        "कानून", " law ", "अधिनियम", " act ", "अदालत", "court", "सुप्रीम कोर्ट", "हाई कोर्ट", "नियम बदल", "new rule", "नया नियम", "बजट", "budget",
        "लाडकी बहीण", "ladki bahin", "आयुष्मान", "ayushman", "उज्ज्वला", "मनरेगा", "mgnrega", "बिजली बिल", "electricity bill", "msedcl", "महावितरण",
    )

    /** ख़बर / ताज़ा घटना वाले शब्द */
    private val NEWS = listOf("खबर", "ख़बर", "न्यूज", "न्यूज़", "news", "समाचार", "क्या हुआ", "हादसा", "ब्रेकिंग", "breaking", "ताज़ा", "ताजा", "latest", "आज")

    fun isGov(q: String): Boolean { val t = " " + q.lowercase() + " "; return GOV.any { t.contains(it) } }
    fun isNews(q: String): Boolean { val t = q.lowercase(); return NEWS.any { t.contains(it) } }

    private const val OFFICIAL_SITES = "official government and authority websites first: india.gov.in, *.gov.in, *.nic.in, pib.gov.in, " +
        "the concerned central/state ministry or department portal (e.g. maharashtra.gov.in, mahadbt), RBI, SEBI, UIDAI, ECI, IRCTC/Indian Railways, " +
        "UPSC/MPSC/SSC/board sites, courts' sites, egazette"

    /** कौन-कौन सी खोज (2 या 3). पहली हमेशा सबसे भरोसे वाली तरफ़ */
    /** v3.90: यह key खोज की अपनी key है = गहरी खोज */
    fun deepFor(key: String, searchKey: String): Boolean = searchKey.isNotBlank() && key.trim() == searchKey.trim()
    fun angleTimeoutMs(deep: Boolean): Long = if (deep) 28_000L else 18_000L
    /** Live की web_search पूरा कितना रुके (कोण + मिलाना) */
    fun totalTimeoutMs(deep: Boolean): Long = if (deep) 46_000L else 32_000L

    fun plan(question: String, deep: Boolean): List<Angle> {
        val l = plan(question)
        if (!deep) return l
        return l + Angle("detail", "Search deeply like a careful researcher: open several authoritative pages (official, reference, expert and " +
            "reputed news), collect the COMPLETE details - exact numbers, dates, rules, steps, eligibility, background, reasons, and what changed " +
            "recently. Cross-check each important fact on at least two websites.")
    }

    fun plan(question: String): List<Angle> {
        val gov = isGov(question)
        val list = mutableListOf<Angle>()
        if (gov) list += Angle("official", "Search $OFFICIAL_SITES. Use the latest official notification, circular, press release or portal page. " +
            "Give the exact current facts (amounts, dates, eligibility, last date, how to apply, official link name).")
        list += Angle("web", "Search widely across several reliable websites (reference sites, reputed Indian and international news, " +
            "official sites, specialist sites for the topic). Prefer the newest pages and check their publish/update date.")
        if (!gov || isNews(question)) list += Angle("news", "Search the most recent news reports (today and the last few days) from reputed " +
            "news sites (PTI, ANI, The Hindu, Indian Express, Times of India, NDTV, Hindustan Times, BBC, Reuters, Lokmat, Dainik Bhaskar...). " +
            "Find what is the latest update right now.")
        return list
    }

    /** हर एक खोज का system निर्देश */
    fun angleSystem(a: Angle, nowLine: String, name: String, deep: Boolean = false): String =
        "You are the research helper of $name, a Hindi voice assistant. $nowLine\n" +
        "ALWAYS use Google Search${if (deep) " - run several different searches and read multiple pages, do not stop at the first result" else ""}. ${a.instruction}\n" +
        "Report ONLY facts you found, as ${if (deep) "6-15" else "3-8"} short lines in plain text (Hindi or English is fine). For every fact write in brackets " +
        "the website name and the date of that information, like (pib.gov.in, 26 Oct 2026). Numbers with units. " +
        "If the newest information you found is old, say how old. If you found nothing reliable, write exactly: NOTHING RELIABLE FOUND."

    /** मिलाने वाले का system निर्देश */
    fun mergeSystem(nowLine: String, name: String, user: String, deep: Boolean = false): String = buildString {
        if (deep) append("DEEP SEARCH: give a complete, well-organised answer with all the important details the reports found (still in spoken style, no lists of links). ")
        append("You are $name, a Hindi voice assistant")
        if (user.isNotBlank()) append(" talking to $user")
        append(". $nowLine\n")
        append("Below are several independent web search reports for the user's question (each from different websites). Combine them into ONE correct answer:\n")
        append("- State as fact only what the reports agree on, or what an official/government source says. Trust order: official/government > reputed news > other sites.\n")
        append("- If reports disagree, give the most authoritative/newest one and briefly mention the difference.\n")
        append("- Say how fresh the information is when it matters (e.g. \"आज सुबह तक\", \"26 अक्टूबर की जानकारी के मुताबिक़\").\n")
        append("- Never invent numbers, dates, names or links. If nothing reliable was found, say honestly that you could not find confirmed information right now.\n")
        append("- Answer in natural spoken Hindi (Devanagari; English names ok), 2-5 short sentences, most important first, no markdown, no lists, no URLs.\n")
        append("- Do not say \"I searched\" or list the websites unless the user asked where the information came from.")
    }

    fun mergeInput(question: String, reports: List<Pair<String, String>>): String = buildString {
        append("USER QUESTION: ").append(question.trim()).append("\n\n")
        reports.forEachIndexed { i, (id, text) -> append("REPORT ${i + 1} ($id search):\n").append(text.trim().take(2500)).append("\n\n") }
    }

    /** खोज ने कुछ पक्का नहीं पाया */
    fun empty(text: String): Boolean = text.isBlank() || text.contains("NOTHING RELIABLE FOUND")

    /** Live के tool जवाब के साथ: स्रोत (वेबसाइट के नाम) - पूछने पर बताने के लिए */
    fun sourcesLine(sources: List<Pair<String, String>>): String {
        if (sources.isEmpty()) return ""
        val names = sources.map { (label, url) -> site(label, url) }.filter { it.isNotBlank() }.distinct().take(8)
        return if (names.isEmpty()) "" else "SOURCES (websites used): " + names.joinToString(", ") +
            ". Do not read these out by yourself; if the user asks where this information came from, tell these website names in Hindi."
    }

    /** वेबसाइट का नाम: Google की redirect कड़ी हो तो title (Gemini वहाँ "ndtv.com" जैसा नाम देता है) */
    fun site(label: String, url: String): String {
        val h = Grounding.host(url)
        return if (h.isBlank() || h.contains("vertexaisearch") || h.endsWith("google.com")) label.trim() else h
    }

    /** कई खोजों के स्रोत जोड़ो (एक वेबसाइट एक बार), ज़्यादा से ज़्यादा [max] */
    fun mergeSources(lists: List<List<Pair<String, String>>>, max: Int = 10): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (l in lists) for (s in l) {
            val n = site(s.first, s.second).lowercase()
            if (n.isBlank() || out.any { site(it.first, it.second).lowercase() == n || it.second == s.second }) continue
            out += s
            if (out.size >= max) return out
        }
        return out
    }

    // ------------------------------------------------------------------ "कहाँ से पता किया?"
    @Volatile var lastQuestion = ""
        private set
    @Volatile var lastSources: List<Pair<String, String>> = emptyList()
        private set
    @Volatile var lastAt = 0L
        private set

    fun remember(question: String, sources: List<Pair<String, String>>, at: Long = System.currentTimeMillis()) {
        if (sources.isEmpty()) return
        lastQuestion = question.trim().take(200); lastSources = sources.take(10); lastAt = at
    }

    private val ASK_SOURCE = listOf("कहाँ से", "कहां से", "किस वेबसाइट", "किस साइट", "कौन सी वेबसाइट", "कौन-सी वेबसाइट", "कौनसी वेबसाइट",
        "स्रोत", "सोर्स", "source", "where did you get", "where is this from", "किसने बताया", "कहाँ पढ़ा", "कहां पढ़ा", "कहाँ देखा", "कहां देखा")

    /** यूज़र पूछ रहा है कि जानकारी कहाँ से ली */
    fun asksSource(text: String): Boolean { val t = text.lowercase(); return ASK_SOURCE.any { t.contains(it) } }

    /** chat/Live prompt में: पिछली खोज (30 मिनट तक) के स्रोत */
    fun memoryBlock(at: Long = System.currentTimeMillis()): String {
        if (lastSources.isEmpty() || at - lastAt > 30 * 60_000L) return ""
        val names = lastSources.map { (l, u) -> site(l, u) }.filter { it.isNotBlank() }.distinct().take(8)
        if (names.isEmpty()) return ""
        return "\n\nLAST WEB SEARCH (\"${lastQuestion.take(80)}\") used these websites: ${names.joinToString(", ")}. " +
            "If the user asks where you got the information, name these websites honestly (do not invent others)."
    }

    /** सीधा जवाब: "यह जानकारी मैंने ... से ली थी" */
    fun sourceReply(at: Long = System.currentTimeMillis()): String? {
        if (lastSources.isEmpty() || at - lastAt > 30 * 60_000L) return null
        val names = lastSources.map { (l, u) -> site(l, u) }.filter { it.isNotBlank() }.distinct().take(6)
        if (names.isEmpty()) return null
        return "यह जानकारी मैंने इन वेबसाइटों से मिलाकर ली थी: ${names.joinToString(", ")}।"
    }
}
