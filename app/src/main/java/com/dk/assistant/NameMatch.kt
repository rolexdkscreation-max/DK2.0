package com.dk.assistant

/**
 * v3.33: नाम मिलाना (कॉन्टैक्ट / ऐप) - हिंदी, English, Hinglish.
 * आवाज़ से "राहुल" आता है पर फ़ोन में "Rahul" लिखा होता है - इसलिए देवनागरी को रोमन में बदलकर
 * एक "आवाज़ वाली चाबी" (phonetic key) से मिलाते हैं. Android import नहीं -> JVM unit test.
 */
object NameMatch {
    private val CONS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095E' to "f", '\u095C' to "r", '\u095D' to "rh",
        '\u0931' to "r", '\u0933' to "l"
    )
    private val NUKTA = mapOf('क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'फ' to "f", 'ड' to "r", 'ढ' to "rh")
    private val VOWEL = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e"
    )
    private val MATRA = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e"
    )
    private const val VIRAMA = '्'
    private const val NUKTA_SIGN = '़'

    fun hasDevanagari(s: String) = s.any { it in '\u0900'..'\u097F' }

    /** देवनागरी -> मोटा-मोटा रोमन ("राहुल शर्मा" -> "raahul sharmaa"). बाकी अक्षर वैसे ही (lowercase). */
    fun latin(s: String): String {
        val out = StringBuilder()
        val chars = s.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            var cons = CONS[c]
            if (cons != null) {
                var j = i + 1
                if (j < chars.size && chars[j] == NUKTA_SIGN) { cons = NUKTA[c] ?: cons; j++ }
                out.append(cons)
                val next = chars.getOrNull(j)
                when {
                    next == VIRAMA -> j++
                    next != null && MATRA.containsKey(next) -> { out.append(MATRA[next]); j++ }
                    // शब्द का आखिरी व्यंजन: "अ" नहीं बोलते (राहुल = rahul, rahula नहीं)
                    next == null || !(next in '\u0900'..'\u097F') -> {}
                    else -> out.append('a')
                }
                i = j
                continue
            }
            val v = VOWEL[c]
            if (v != null) { out.append(v); i++; continue }
            when (c) {
                'ं', 'ँ' -> out.append('n')
                'ः' -> out.append('h')
                NUKTA_SIGN, VIRAMA -> {}
                else -> out.append(MATRA[c] ?: c.lowercaseChar().toString())
            }
            i++
        }
        return out.toString()
    }

    /** एक शब्द की आवाज़ वाली चाबी: aa=a, ee=i, ph=f, w=v, z=j, kh=k ... दोहरे अक्षर एक */
    fun key(word: String): String {
        var w = latin(word.lowercase()).filter { it.isLetterOrDigit() }
        if (w.isEmpty()) return ""
        w = w.replace("aa", "a").replace("ee", "i").replace("oo", "u").replace("ou", "u")
            .replace("ph", "f").replace("ck", "k").replace("x", "ks").replace("q", "k")
            .replace("w", "v").replace("z", "j").replace("y", "i")
        // c अकेला = k ("chandan" का ch बचाओ)
        w = w.replace("ch", "\u0001").replace("c", "k").replace("\u0001", "c")
        // व्यंजन के बाद h (kh, bh, sh, th, dh) हटाओ
        val sb = StringBuilder()
        for (ch in w) {
            if (ch == 'h' && sb.isNotEmpty() && sb.last() !in "aeiou") continue
            sb.append(ch)
        }
        w = sb.toString().replace("ai", "e").replace("au", "o")
        // दोहरे अक्षर एक (mummy -> mumi)
        val d = StringBuilder()
        for (ch in w) if (d.isEmpty() || d.last() != ch) d.append(ch)
        return d.toString()
    }

    /** स्वर-ढीली चाबी */
    fun loose(k: String): String {
        val m = k.map { when (it) { 'u', 'o' -> 'a'; 'e' -> 'i'; else -> it } }.joinToString("")
        val d = StringBuilder()
        for (ch in m) if (d.isEmpty() || d.last() != ch) d.append(ch)
        return d.toString()
    }

    /** सिर्फ़ व्यंजन (सबसे ढीली चाबी): "rahul" -> "rl" (h भी हटता है) */
    fun skeleton(word: String): String {
        val k = key(word)
        if (k.isEmpty()) return ""
        val s = k.first() + k.drop(1).filter { it !in "aeiouh" }
        val d = StringBuilder()
        for (ch in s) if (d.isEmpty() || d.last() != ch) d.append(ch)
        return d.toString()
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotBlank() }

    /**
     * बोला गया नाम बनाम एक लिखा नाम: 0 = नहीं मिला, 100 = बिल्कुल वही.
     * पूरा नाम > हर बोले शब्द की चाबी मिली > कंकाल (व्यंजन) मिला.
     */
    fun score(spoken: String, name: String): Int {
        val sw = words(spoken); val nw = words(name)
        if (sw.isEmpty() || nw.isEmpty()) return 0
        if (sw.joinToString(" ") == nw.joinToString(" ")) return 100
        val sk = sw.map { key(it) }; val nk = nw.map { key(it) }
        if (sk == nk) return 95
        if (sk.joinToString("") == nk.joinToString("")) return 92            // "whats app" = "WhatsApp"
        // बोले गए सारे शब्द नाम में (क्रम से) - "राहुल" -> "Rahul Sharma"
        if (sk.all { it in nk }) return if (sk.size >= 2) 90 else 80
        // स्वर ढीले (u/o = a, e = i): "मम्मी" (mami) = "Mummy" (mumi)
        val sl = sk.map { loose(it) }; val nl = nk.map { loose(it) }
        if (sl == nl || sl.joinToString("") == nl.joinToString("")) return 85
        if (sl.all { it in nl }) return if (sl.size >= 2) 82 else 75
        val ss = sw.map { skeleton(it) }; val ns = nw.map { skeleton(it) }
        if (ss.all { s -> s.length >= 3 && s in ns }) return if (ss.size >= 2) 70 else 60
        return 0
    }

    /** कई नामों में सबसे अच्छे (बराबर अंक वाले सभी). कम से कम [min] अंक. */
    fun <T> best(spoken: String, items: List<T>, name: (T) -> String, min: Int = 60): List<T> {
        var top = 0
        val scored = items.map { it to score(spoken, name(it)) }
        scored.forEach { if (it.second > top) top = it.second }
        if (top < min) return emptyList()
        return scored.filter { it.second == top }.map { it.first }
    }
}
