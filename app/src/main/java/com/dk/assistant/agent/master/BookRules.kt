package com.dk.assistant.agent.master

/**
 * 25 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 4 - "बुकिंग एजेंट").
 * बुकिंग एजेंट का pure हिस्सा (JVM test). असली काम पहले से बना फ़ोन वाला एजेंट ([com.dk.assistant.AgentRunner]) और
 * DK कंप्यूटर ब्राउज़र ही करते हैं - यहाँ सिर्फ़ मास्टर के नियम:
 *  - बात से यात्रा समझना ([trip]): किस चीज़ की बुकिंग, कहाँ से, कहाँ तक, कब, कितने लोग, क्लास
 *  - जो ज़रूरी बात नहीं पता, उसे खुद कभी नहीं बनाना - पहले मालिक से पूछना ([missing], [askNote])
 *  - "Book now / Confirm ride" = हर बार ब्योरे के साथ पूछकर ([tap]); भुगतान कभी नहीं (पेमेंट पेज मालिक को)
 * [com.dk.assistant.plus.FeatureFlags.MASTER_AGENT] बंद = इसका कोई हिस्सा नहीं चलता.
 */
object BookRules {
    enum class Kind(val hi: String) {
        TRAIN("ट्रेन टिकट"), BUS("बस टिकट"), FLIGHT("फ्लाइट टिकट"), HOTEL("होटल"), CAB("कैब / ऑटो"), MOVIE("मूवी टिकट"), OTHER("बुकिंग")
    }

    /** बुकिंग ऐप - [kinds] = किस चीज़ के लिए */
    data class App(val label: String, val pkgs: List<String>, val kinds: Set<Kind>, val words: List<String>)

    val APPS = listOf(
        App("IRCTC Rail Connect", listOf("cris.org.in.prs.ima"), setOf(Kind.TRAIN), listOf("irctc", "आईआरसीटीसी", "रेल कनेक्ट", "rail connect")),
        App("ixigo", listOf("com.ixigo.train.ixitrain", "com.ixigo"), setOf(Kind.TRAIN, Kind.FLIGHT, Kind.BUS), listOf("ixigo", "इक्सिगो", "इक्सीगो")),
        App("ConfirmTkt", listOf("com.confirmtkt.lite"), setOf(Kind.TRAIN), listOf("confirmtkt", "confirm tkt", "कन्फर्म टिकट")),
        App("redBus", listOf("in.redbus.android"), setOf(Kind.BUS), listOf("redbus", "red bus", "रेडबस", "रेड बस")),
        App("MakeMyTrip", listOf("com.makemytrip"), setOf(Kind.FLIGHT, Kind.HOTEL, Kind.BUS, Kind.TRAIN), listOf("makemytrip", "make my trip", "मेक माय ट्रिप", "mmt")),
        App("Goibibo", listOf("com.goibibo"), setOf(Kind.FLIGHT, Kind.HOTEL, Kind.BUS), listOf("goibibo", "गोइबिबो", "गोआईबीबो")),
        App("OYO", listOf("com.oyo.consumer"), setOf(Kind.HOTEL), listOf("oyo", "ओयो")),
        App("Booking.com", listOf("com.booking"), setOf(Kind.HOTEL), listOf("booking.com", "बुकिंग डॉट कॉम")),
        App("Uber", listOf("com.ubercab"), setOf(Kind.CAB), listOf("uber", "उबर", "ऊबर")),
        App("Ola", listOf("com.olacabs.customer"), setOf(Kind.CAB), listOf(" ola ", "ओला")),
        App("Rapido", listOf("com.rapido.passenger"), setOf(Kind.CAB), listOf("rapido", "रैपिडो", "रापिडो")),
        App("BookMyShow", listOf("com.bt.bms"), setOf(Kind.MOVIE), listOf("bookmyshow", "book my show", "बुक माय शो")),
        App("District", listOf("com.application.zomato.district"), setOf(Kind.MOVIE), listOf("district", "डिस्ट्रिक्ट")),
    )

    data class Trip(
        val kind: Kind,
        val from: String? = null,
        val to: String? = null,
        val date: String? = null,
        val people: Int? = null,
        val cls: String? = null,
        val app: App? = null,
    )

    private fun norm(s: String) = " " + s.lowercase().replace(Regex("[,.!?।\"']+"), " ").replace(Regex("\\s+"), " ").trim() + " "
    private fun has(l: String, w: List<String>) = w.any { l.contains(it) }

    private val K_TRAIN = listOf("ट्रेन", "train", "रेल", "rail", "irctc", "तत्काल", "tatkal", "sleeper", "स्लीपर", " 3ac ", " 2ac ", " 3a ", " 2a ")
    private val K_BUS = listOf(" बस ", " bus ", "redbus", "रेडबस", "volvo", "वॉल्वो")
    private val K_FLIGHT = listOf("फ्लाइट", "फ़्लाइट", "flight", "हवाई", "plane", "प्लेन", "विमान")
    private val K_HOTEL = listOf("होटल", "hotel", "रूम", " room ", "oyo", "ओयो", "stay", "लॉज", "lodge", "resort", "रिसॉर्ट")
    private val K_CAB = listOf("कैब", " cab ", "टैक्सी", "taxi", " ola ", "ओला", "uber", "उबर", "rapido", "रैपिडो", "ऑटो", " auto ", "बाइक टैक्सी", " ride ", "राइड")
    private val K_MOVIE = listOf("मूवी", "movie", "फिल्म", "film", "पिक्चर", "picture", "bookmyshow", "सिनेमा", "cinema", "शो की टिकट")

    fun kind(req: String): Kind {
        val l = norm(req)
        return when {
            has(l, K_FLIGHT) -> Kind.FLIGHT
            has(l, K_TRAIN) -> Kind.TRAIN
            has(l, K_BUS) -> Kind.BUS
            has(l, K_MOVIE) -> Kind.MOVIE
            has(l, K_HOTEL) -> Kind.HOTEL
            has(l, K_CAB) -> Kind.CAB
            else -> Kind.OTHER
        }
    }

    /** जगह नहीं हैं - "से / तक" के पास आने वाले आम शब्द */
    private val NOT_PLACE = setOf("आज", "कल", "परसों", "मुझे", "मेरे", "हमें", "हम", "एक", "दो", "तीन", "चार", "पाँच", "पांच", "ट्रेन", "बस", "फ्लाइट", "कैब", "टैक्सी",
        "ऑटो", "होटल", "टिकट", "की", "का", "के", "में", "पर", "और", "यहाँ", "यहां", "अभी", "जल्दी", "सुबह", "शाम", "रात", "दोपहर", "बुक", "करो", "कर", "दो",
        "today", "tomorrow", "me", "my", "i", "a", "the", "train", "bus", "flight", "cab", "taxi", "ticket", "tickets", "book", "hotel", "and", "please", "now",
        "से", "तक", "लिए", "जाना", "है", "उबर", "ओला", "रैपिडो", "uber", "ola", "rapido", "irctc", "redbus", "बजे", "तारीख", "लोग", "लोगों")

    private fun place(w: String?): String? = w?.trim()?.takeIf { it.length >= 2 && it !in NOT_PLACE && !it.any { c -> c.isDigit() } }

    /** "नई दिल्ली" / "new delhi" / "navi mumbai" - दो शब्द वाली जगह */
    private val PREFIX = setOf("नई", "नयी", "नवी", "new", "navi", "उत्तर", "दक्षिण", "north", "south", "पुराना", "old")

    private fun words(s: String) = s.trim().split(' ').filter { it.isNotBlank() }

    private fun before(ws: List<String>, i: Int): String? {
        val w = place(ws.getOrNull(i - 1)) ?: return null
        val p = ws.getOrNull(i - 2)
        return if (p != null && p in PREFIX) "$p $w" else w
    }

    private fun after(ws: List<String>, i: Int): String? {
        val a = ws.getOrNull(i + 1) ?: return null
        if (a in PREFIX) return place(ws.getOrNull(i + 2))?.let { "$a $it" }
        return place(a)
    }

    /** कहाँ से - कहाँ तक */
    fun places(req: String): Pair<String?, String?> {
        val ws = words(norm(req))
        // "नासिक से पुणे" / "from nashik to pune" / "nashik to pune"
        ws.indexOf("से").takeIf { it > 0 }?.let { i ->
            val f = before(ws, i)
            val t = after(ws, i)
            if (f != null || t != null) return f to t
        }
        ws.indexOf("from").takeIf { it >= 0 }?.let { i ->
            val f = after(ws, i)
            val j = ws.indexOf("to").takeIf { it > i }
            return f to j?.let { after(ws, it) }
        }
        ws.indexOf("to").takeIf { it > 0 }?.let { i ->
            val f = before(ws, i)
            val t = after(ws, i)
            if (t != null) return f to t
        }
        // सिर्फ़ मंज़िल: "पुणे के लिए" / "स्टेशन तक" / "पुणे जाना है" / "airport ke liye"
        listOf("के लिए", "तक", "जाना", "जाने", "ke liye", "tak", "jana", "में", "mein", "in").forEach { k ->
            val i = norm(req).indexOf(" $k ")
            if (i > 0) {
                val left = words(norm(req).substring(0, i))
                before(left + listOf("#"), left.size)?.let { return null to it }
            }
        }
        return null to null
    }

    private val DATE_WORDS = listOf("आज" to "आज", "today" to "आज", "aaj" to "आज", "परसों" to "परसों", "parso" to "परसों", "day after tomorrow" to "परसों",
        "tomorrow" to "कल", "कल" to "कल", "kal" to "कल")
    private val MONTHS = listOf("जनवरी", "फ़रवरी", "फरवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "सितम्बर", "अक्टूबर", "नवंबर", "नवम्बर", "दिसंबर", "दिसम्बर",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec")
    private val DAYS = listOf("सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    /** कब - मालिक के अपने शब्द ("कल", "26 नवंबर", "26/11", "शनिवार") - अपने से तारीख नहीं बनाते */
    fun date(req: String): String? {
        val l = norm(req)
        Regex(" (\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))? ").find(l)?.let { return it.value.trim() }
        val mon = MONTHS.joinToString("|")
        Regex(" (\\d{1,2})\\s*(?:st|nd|rd|th)?\\s*($mon) ").find(l)?.let { return it.value.trim() }
        Regex(" (\\d{1,2})\\s*(?:तारीख|tarikh|tareekh|तारीक) ").find(l)?.let { return it.value.trim() }
        DAYS.firstOrNull { l.contains(" $it ") || l.contains(" $it को ") }?.let { d ->
            val next = l.contains(" अगले ") || l.contains(" next ") || l.contains(" agle ")
            return (if (next) "अगले " else "") + d
        }
        DATE_WORDS.firstOrNull { (w, _) -> l.contains(" $w ") }?.let { return it.second }
        return null
    }

    private val NUM = mapOf("एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पाँच" to 5, "पांच" to 5, "छह" to 6, "छः" to 6, "सात" to 7, "आठ" to 8,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5)
    private const val WHO = "(?:लोग|लोगों|लोगो|आदमी|व्यक्ति|टिकट|टिकिट|सीट|सीटें|ticket|tickets|seat|seats|people|persons|person|adults|adult|passengers|passenger|log|logo|guests|guest|मेहमान)"

    /** कितने लोग (कहा न हो = null, अपने से 1 नहीं मानते) */
    fun people(req: String): Int? {
        val l = norm(req)
        Regex(" (\\d{1,2}) $WHO ").find(l)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..20 } }
        Regex(" ([\\p{L}\\p{M}]+) $WHO ").findAll(l).forEach { m -> NUM[m.groupValues[1]]?.let { return it } }
        if (has(l, listOf(" हम दोनों ", " दोनों के लिए ", " hum dono ", " both of us ", " for two "))) return 2
        if (has(l, listOf(" मेरे लिए ", " अकेले ", " अकेला ", " सिर्फ़ मैं ", " सिर्फ मैं ", " for me ", " just me ", " mere liye ", " akele "))) return 1
        return null
    }

    private val CLASSES = listOf(" sleeper " to "Sleeper", " स्लीपर " to "Sleeper", " 3ac " to "3AC", " 3a " to "3AC", " थर्ड एसी " to "3AC", " 2ac " to "2AC", " 2a " to "2AC",
        " सेकंड एसी " to "2AC", " 1ac " to "1AC", " फर्स्ट एसी " to "1AC", " chair car " to "Chair Car", " चेयर कार " to "Chair Car", " general " to "General", " जनरल " to "General",
        " tatkal " to "Tatkal", " तत्काल " to "Tatkal", " economy " to "Economy", " इकॉनमी " to "Economy", " business " to "Business", " बिज़नेस " to "Business",
        " seater " to "Seater", " सीटर " to "Seater", " ac बस " to "AC", " ac bus " to "AC", " bike " to "Bike", " बाइक " to "Bike", " auto " to "Auto", " ऑटो " to "Auto")

    private val INTENT = listOf("बुक", "book", "टिकट", "टिकिट", "ticket", "tikat", "reserv", "रिज़र्व", "रिजर्व", "बुलाओ", "बुला दो", "bulao", "bula do",
        "मंगाओ", "मँगाओ", "करवा दो", "करवाओ", "चाहिए", "chahiye", "जाना है", "jana hai", "ले चलो", "ride चाहिए", "कमरा चाहिए")

    /** सच में बुकिंग करनी है? ("Uber खोलो" = नहीं, "Uber बुक करो / स्टेशन के लिए कैब चाहिए" = हाँ) */
    fun isBooking(req: String): Boolean = has(norm(req), INTENT)

    fun app(req: String): App? {
        val l = norm(req)
        return APPS.firstOrNull { a -> a.words.any { l.contains(it) } }
    }

    fun trip(req: String): Trip {
        val (f, t) = places(req)
        val l = norm(req)
        return Trip(kind(req), f, t, date(req), people(req), CLASSES.firstOrNull { l.contains(it.first) }?.second, app(req))
    }

    /** बिना इसके बुकिंग नहीं हो सकती - हिंदी में, पूछने के क्रम में */
    fun missing(t: Trip): List<String> = buildList {
        when (t.kind) {
            Kind.TRAIN, Kind.BUS, Kind.FLIGHT -> {
                if (t.from == null) add("कहाँ से")
                if (t.to == null) add("कहाँ तक")
                if (t.date == null) add("किस तारीख को")
                if (t.people == null) add("कितने लोग")
            }
            Kind.HOTEL -> {
                if (t.to == null && t.from == null) add("किस शहर में")
                if (t.date == null) add("किस तारीख से")
                if (t.people == null) add("कितने लोग")
            }
            Kind.CAB -> if (t.to == null) add("कहाँ जाना है")
            Kind.MOVIE -> {
                if (t.date == null) add("किस दिन")
                if (t.people == null) add("कितनी टिकट")
            }
            Kind.OTHER -> Unit
        }
    }

    /** ब्योरा एक लाइन में ("ट्रेन टिकट: नासिक → पुणे, कल, 2 लोग, Sleeper") */
    fun summary(t: Trip): String = buildString {
        append(t.kind.hi).append(':')
        if (t.from != null && t.to != null) append(' ').append(t.from).append(" → ").append(t.to)
        else (t.to ?: t.from)?.let { append(' ').append(it) }
        t.date?.let { append(", ").append(it) }
        t.people?.let { append(", ").append(it).append(if (t.kind == Kind.MOVIE) " टिकट" else " लोग") }
        t.cls?.let { append(", ").append(it) }
        t.app?.let { append(" (").append(it.label).append(')') }
    }

    /** उस किस्म के, फ़ोन में मौजूद ऐप */
    fun appsFor(k: Kind, installed: (String) -> Boolean): List<App> = APPS.filter { (k == Kind.OTHER || k in it.kinds) && it.pkgs.any(installed) }

    /** ज़रूरी बात नहीं पता = एजेंट मत चलाओ, पहले मालिक से पूछो (Gemini को यही लौटता है) */
    fun askNote(t: Trip, miss: List<String>): String {
        val q = "${t.kind.hi} के लिए बताइए — ${miss.joinToString(", ")}?"
        return "ASK_USER: DK MASTER (booking) - these booking details are missing: ${miss.joinToString(", ")}. Do NOT guess or fill them yourself. " +
            "Say exactly this one short line and wait: \"$q\". When he answers, call phone_task again with the full request in his words plus his answer."
    }

    /** एजेंट के साथ जाने वाला नियम (ब्योरा + ऐप + सख़्ती) */
    fun agentNote(t: Trip, apps: List<App>): String = buildString {
        append("DK MASTER (booking): owner's details = ").append(summary(t)).append(". Use exactly these - never change or invent a place, date, name, age or count; ")
        if (t.app == null && apps.size > 1) append("he did not name an app - first ask which one: ").append(apps.joinToString(", ") { it.label }).append("; ")
        else if (t.app == null && apps.size == 1) append("use ").append(apps[0].label).append("; ")
        else if (t.app == null) append("no booking app for this is installed - tell him and offer to open the website in DK Computer; ")
        append("fill only what he told you, ask him for passenger name / age / ID / phone if needed (never make them up); ")
        append("show him the options (time, price) as read on the screen and let him choose; ")
        append("before Book / Confirm / Reserve DK asks him itself - if he says no, stop; ")
        append("NEVER make the online payment: when the payment page or Pay button comes, stop and tell him to pay himself.")
    }

    enum class Tap(val hi: String) { BOOK("बुकिंग पक्की करना"), PAY("भुगतान") }

    private val BOOK_W = listOf("book now", "book ticket", "book tickets", "book seat", "book seats", "book ride", "book cab", "book auto", "book bike", "book room",
        "book hotel", "book stay", "confirm booking", "confirm & book", "confirm and book", "confirm ride", "confirm pickup", "request ride", "request uber",
        "confirm uber", "confirm ola", "book ola", "book uber", "book rapido", "confirm rapido", "reserve", "proceed to book", "continue to book", "complete booking",
        "बुक करें", "बुक करो", "बुक नाउ", "बुकिंग पक्की", "राइड बुक", "टिकट बुक", "सीट बुक", "बुकिंग कन्फर्म", "बुकिंग करें")

    /** बटन का नाम -> [Tap] (null = आम बटन). [booking] = मालिक का काम बुकिंग वाला ("Confirm" भी पक्का करना है) */
    fun tap(label: String, booking: Boolean = false): Tap? {
        if (ShopRules.tap(label) == ShopRules.Tap.PAY) return Tap.PAY
        val l = " " + label.lowercase().replace(Regex("[\\s\\u00a0\"()]+"), " ").trim() + " "
        if (l.isBlank()) return null
        return when {
            has(l, BOOK_W) -> Tap.BOOK
            booking && has(l, listOf(" confirm ", " पक्का करें ", " कन्फर्म ", " request ")) -> Tap.BOOK
            else -> null
        }
    }

    fun askLine(t: Trip?, label: String): String =
        "बुकिंग पक्की करने वाला कदम: \"${label.trim().take(50)}\"" + (t?.let { "। ब्योरा: ${summary(it)}" } ?: "") + "। क्या मैं दबाऊँ? हाँ या ना बोलिए।"

    const val PAY_HANDOVER = "अब भुगतान का पेज है — बुकिंग में भुगतान मैं कभी नहीं करती। ऐप इसी पेज पर खुला है, भुगतान आप खुद कीजिए।"
}
