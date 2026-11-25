package com.dk.assistant

/**
 * 25 नवंबर 2026 (मालिक: "भाषा में तीन - हिंदी, English और Hinglish"): ऐप की स्क्रीन Hinglish में =
 * code में लिखी हिंदी (देवनागरी) को रोमन अक्षरों में ("अपना असली नाम लिखें" -> "Apna asli naam likhen").
 * English शब्द / नंबर / चिह्न वैसे ही. pure (Android import नहीं) -> JVM test.
 *
 * नियम (आम बोलचाल वाली Hinglish): शब्द के आख़िर का "अ" नहीं बोलते (नाम = naam), बीच का "अ" भी तब नहीं जब
 * आगे वाले अक्षर पर मात्रा हो और पीछे स्वर हो (असली = asli, करना = karna). एक शब्दांश वाले शब्द में "आ" = aa
 * (नाम = naam, काम = kaam), लंबे शब्द में a (भाषा = bhasha).
 */
object Translit {

    private val CONS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
        'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l",
        // पहले से नुक़्ते वाले अक्षर
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f", '\u095F' to "y",
    )
    /** नुक़्ता (़) लगने पर */
    private val NUKTA = mapOf('क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f", 'य' to "y")

    private val VOWEL = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )
    private val MATRA = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )
    private const val VIRAMA = '्'
    private const val NUKTA_SIGN = '़'
    private const val ANUSVARA = 'ं'
    private const val CHANDRA = 'ँ'
    private const val VISARGA = 'ः'

    private fun dev(c: Char) = c in '\u0900'..'\u097F'

    /** पूरा text: देवनागरी वाले शब्द रोमन में, बाकी वैसा ही. पहला अक्षर बड़ा (वाक्य जैसा) */
    fun hinglish(s: String): String {
        if (s.none { dev(it) }) return s
        val out = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (!dev(c)) { out.append(c); i++; continue }
            var j = i
            while (j < s.length && dev(s[j]) && s[j] != '।' && s[j] != '॥') j++
            if (j == i) { out.append(if (c == '।') "." else "."); i++; continue }
            out.append(word(s.substring(i, j)))
            i = j
        }
        val r = out.toString().replace(Regex("[०-९]")) { m -> ((m.value[0] - '०')).toString() }
        val k = r.indexOfFirst { it.isLetter() }
        return if (k >= 0 && r[k].isLowerCase() && (k == 0 || !r[k - 1].isLetter())) r.substring(0, k) + r[k].uppercaseChar() + r.substring(k + 1) else r
    }

    /** एक शब्द के अक्षर-खंड: व्यंजन (साथ में मात्रा/हलंत) या स्वर. [inherent] = मात्रा नहीं, अपने-आप वाला "अ" */
    private class Seg(val base: String, var vowel: String, val isCons: Boolean, val inherent: Boolean, val aa: Boolean) {
        var nasal = ""
    }

    fun word(w: String): String {
        val segs = ArrayList<Seg>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val cons = CONS[c]
            when {
                cons != null -> {
                    var base = cons
                    var k = i + 1
                    if (k < w.length && w[k] == NUKTA_SIGN) { base = NUKTA[c] ?: cons; k++ }
                    var v = "a"; var inh = true; var aa = false
                    if (k < w.length && w[k] == VIRAMA) { v = ""; inh = false; k++ }
                    else if (k < w.length && MATRA.containsKey(w[k])) { aa = w[k] == 'ा'; v = MATRA.getValue(w[k]); inh = false; k++ }
                    segs.add(Seg(base, v, true, inh, aa))
                    i = k
                }
                VOWEL.containsKey(c) -> { segs.add(Seg(VOWEL.getValue(c), "", false, false, false)); i++ }
                c == ANUSVARA || c == CHANDRA -> { segs.lastOrNull()?.let { it.nasal = "n" }; i++ }
                c == VISARGA -> { segs.lastOrNull()?.let { it.nasal += "h" }; i++ }
                else -> i++
            }
        }
        if (segs.isEmpty()) return ""
        val n = segs.size
        fun voiced(x: Seg) = !x.isCons || x.vowel.isNotEmpty()
        // आख़िरी व्यंजन का "अ" नहीं (एक अक्षर वाले शब्द में रखें: "न" = na)
        if (n > 1 && segs[n - 1].inherent && segs[n - 1].nasal.isEmpty()) segs[n - 1].vowel = ""
        // बीच का: पीछे स्वर हो, आगे वाले पर स्वर हो -> "अ" हटे (असली = asli, करना = karna); लगातार दो नहीं हटते
        for (k in 1 until n - 1) {
            val sg = segs[k]
            if (!sg.inherent || sg.nasal.isNotEmpty()) continue
            if (voiced(segs[k - 1]) && voiced(segs[k + 1])) sg.vowel = ""
        }
        val syll = segs.count { voiced(it) }
        val sb = StringBuilder()
        for ((k, sg) in segs.withIndex()) {
            sb.append(sg.base)
            // "आ" की मात्रा: एक शब्दांश वाले शब्द में aa (नाम = naam, काम = kaam)
            sb.append(if (sg.aa && syll == 1) "aa" else sg.vowel)
            if (sg.nasal.isNotEmpty()) {
                val nx = segs.getOrNull(k + 1)?.base?.firstOrNull()
                sb.append(if (sg.nasal.startsWith("n") && nx != null && nx in "pbm") "m" + sg.nasal.drop(1) else sg.nasal)
            }
        }
        return sb.toString()
    }
}

/**
 * Hinglish में अंग्रेज़ी से आए शब्द असली अंग्रेज़ी में ("ऐप" = app, "सेटिंग" = setting) - बाकी [Translit.word].
 * [fromDict] = UI शब्दकोश (ui_en.tsv) के एक-शब्द वाले जोड़े: रोमन ढाँचा अंग्रेज़ी से मिले तो वही शब्द (कैमरा -> Camera).
 */
class HinglishText(fromDict: Map<String, String> = emptyMap()) {
    private val loan: Map<String, String> = HashMap<String, String>().apply {
        for ((k, v) in fromDict) {
            if (k.contains(' ') || v.contains(' ') || k.none { it in '\u0900'..'\u097F' }) continue
            if (v.length < 2 || !v.all { it.isLetter() && it.code < 128 }) continue
            if (sameSound(Translit.word(k), v)) put(k, v.lowercase().let { if (v.first().isUpperCase() && v.drop(1).any { c -> c.isUpperCase() }) v else it })
        }
        putAll(LOAN)
    }

    fun tr(s: String): String {
        if (s.none { it in '\u0900'..'\u097F' }) return s
        val r = WORD.replace(s) { m -> loan[m.value] ?: Translit.word(m.value) }
        return Translit.hinglish(r)
    }

    companion object {
        private val WORD = Regex("[\\u0900-\\u0963\\u0970-\\u097F]+")

        /** रोमन ढाँचा (स्वर हटाकर, मिलते-जुलते व्यंजन एक) - "kaimra" ~ "camera", "otomeshan" ~ "automation" */
        fun skeleton(s: String): String {
            var t = s.lowercase().replace("tion", "shn").replace("sion", "shn").replace("ph", "f").replace("ck", "k")
                .replace("c", "k").replace("q", "k").replace("w", "v").replace("x", "ks").replace("z", "j")
                .replace("th", "t").replace("dh", "d").replace("bh", "b").replace("kh", "k").replace("gh", "g")
            t = t.filter { it.isLetter() && it !in "aeiouy" }
            val sb = StringBuilder()
            for (c in t) if (sb.isEmpty() || sb.last() != c) sb.append(c)
            return sb.toString()
        }

        fun sameSound(roman: String, english: String): Boolean {
            val a = skeleton(roman); val b = skeleton(english)
            if (a.isEmpty() || b.isEmpty()) return false
            if (a == b) return a.length >= 2 || roman.length <= 4
            val d = lev(a, b)
            return a.length >= 3 && d <= 1
        }

        private fun lev(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        /** सबसे ज़्यादा आने वाले अंग्रेज़ी शब्द (देवनागरी में लिखे) - पक्के */
        val LOAN: Map<String, String> = mapOf(
            "ऐप" to "app", "एप" to "app", "टैप" to "tap", "सेटिंग" to "setting", "सेटिंग्स" to "settings", "फ़ोन" to "phone", "फोन" to "phone",
            "कैमरा" to "camera", "कैमरे" to "camera", "मोड" to "mode", "लॉक" to "lock", "अनलॉक" to "unlock", "स्क्रीन" to "screen", "कॉल" to "call",
            "मैसेज" to "message", "फ़ोटो" to "photo", "फोटो" to "photo", "वीडियो" to "video", "रिपोर्ट" to "report", "इंटरनेट" to "internet",
            "बटन" to "button", "टच" to "touch", "नंबर" to "number", "एजेंट" to "agent", "रील" to "reel", "रीलें" to "reels", "चेक" to "check", "सेट" to "set",
            "सर्च" to "search", "बैटरी" to "battery", "पैटर्न" to "pattern", "पेज" to "page", "होम" to "home", "बैकअप" to "backup", "कोडिंग" to "coding",
            "सायरन" to "siren", "लोकेशन" to "location", "सर्वर" to "server", "लिमिट" to "limit", "अलार्म" to "alarm", "ऑन" to "on", "ऑफ" to "off",
            "लाइव" to "Live", "नेटवर्क" to "network", "सोशल" to "social", "रिसर्च" to "research", "इमेज" to "image", "गैलरी" to "gallery",
            "पासवर्ड" to "password", "ओपन" to "open", "पेमेंट" to "payment", "स्कैन" to "scan", "कमांड" to "command", "पोस्ट" to "post",
            "डिफ़ॉल्ट" to "default", "प्लान" to "plan", "मैप" to "map", "वॉइस" to "voice", "ईमेल" to "email", "गार्ड" to "guard", "टेस्ट" to "test",
            "गार्डियन" to "guardian", "गूगल" to "Google", "मोबाइल" to "mobile", "चार्जर" to "charger", "डीके" to "DK", "ऑटोमेशन" to "automation",
            "डेटा" to "data", "कंप्यूटर" to "computer", "प्रीमियम" to "premium", "बेसिक" to "basic", "लाइसेंस" to "license", "थीम" to "theme",
            "प्रोफ़ाइल" to "profile", "प्रोफाइल" to "profile", "ऑटोनोमस" to "autonomous", "टेम्पलेट" to "template", "रिव्यू" to "review",
            "कनेक्टर" to "connector", "डिबग" to "debug", "ऑटो" to "auto", "रिप्लाई" to "reply", "इंजन" to "engine", "ओर्ब" to "orb",
            "एनिमेशन" to "animation", "वॉलपेपर" to "wallpaper", "डाउनलोड" to "download", "इंस्टॉल" to "install", "अपडेट" to "update",
            "फ़ाइल" to "file", "फाइल" to "file", "स्टोरी" to "story", "राइटर" to "writer", "कंटेंट" to "content", "राइटिंग" to "writing",
            "स्क्रिप्ट" to "script", "कैप्शन" to "caption", "हैशटैग" to "hashtag", "यूट्यूब" to "YouTube", "इंस्टाग्राम" to "Instagram",
            "फेसबुक" to "Facebook", "व्हाट्सएप" to "WhatsApp", "स्टूडियो" to "studio", "ट्रेंड" to "trend", "शॉर्ट्स" to "shorts",
            "रिस्टोर" to "restore", "इवेंट" to "event", "ट्रिगर" to "trigger", "ड्राइविंग" to "driving", "फ़ेस" to "face", "फेस" to "face",
            "फ़िंगरप्रिंट" to "fingerprint", "पिन" to "PIN", "टिक" to "tick", "यूज़र" to "user", "परमिशन" to "permission", "कार्ड" to "card",
            "साइज़" to "size", "डार्क" to "dark", "लाइट" to "light", "चैट" to "chat", "मेमोरी" to "memory", "इनकॉग्निटो" to "incognito",
            "वेक" to "wake", "वर्ड" to "word", "स्विच" to "switch", "टाइमर" to "timer", "रिमाइंडर" to "reminder", "ब्राउज़र" to "browser",
            "शॉपिंग" to "shopping", "बुकिंग" to "booking", "फ़ॉर्म" to "form", "मास्टर" to "master", "कनेक्शन" to "connection", "सिग्नल" to "signal",
        )
    }
}
