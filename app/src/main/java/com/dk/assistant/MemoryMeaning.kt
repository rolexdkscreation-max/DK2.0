package com.dk.assistant

/**
 * 18 नवंबर 2026 (मालिक: "DK का अपना दिमाग - पुरानी बातें फ़ोन से, मतलब समझकर ढूँढे"):
 * याद की खोज पहले सिर्फ़ उसी शब्द से होती थी - "बाइक" बोला था और अब "गाड़ी" पूछा, या "rahul" लिखा था और
 * अब "राहुल" बोला, तो बात नहीं मिलती थी. यह फ़ोन पर ही (कोई API / model / इंटरनेट नहीं) दो तरह से जोड़ता है:
 *
 *  1. [GROUPS] - एक मतलब वाले शब्द (हिंदी + Hinglish + English): बाइक = गाड़ी = bike = gaadi ...
 *  2. [skeleton] - देवनागरी और रोमन (Hinglish) एक ही नाम: "राहुल" = "rahul", "नाशिक" = "nashik".
 *     सिर्फ़ अलग-अलग लिपि के शब्दों में (एक ही लिपि में यह गलत मिलान करता), और कम से कम 3 व्यंजन.
 *
 * सीधा मिलान (वही शब्द) हमेशा ऊपर रहता है ([LocalRecall.meaningScore] में 2 अंक, यहाँ वाले मिलान 1 अंक).
 * Pure logic - Android import नहीं -> JVM test ([MemoryMeaningTest]).
 */
object MemoryMeaning {
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

    /** एक मतलब वाले शब्द. नया जोड़ना हो तो यहीं (सब [LocalRecall.norm] से मिलाए जाते हैं) */
    private val RAW_GROUPS = listOf(
        "बाइक गाड़ी गाडी स्कूटी स्कूटर मोटरसाइकिल कार bike gaadi gadi gaddi scooty scooter motorcycle car vehicle वाहन",
        "पत्नी बीवी घरवाली वाइफ wife biwi patni bibi gharwali",
        "पति हसबैंड घरवाला husband pati",
        "माँ मां मम्मी माता अम्मी मम्मा mother mom mummy maa mummi mata",
        "पापा पिताजी पिता बाबा डैडी father dad papa pitaji daddy",
        "भाई भैया brother bhai bhaiya bhaiyya",
        "बहन दीदी sister behen bahan didi",
        "बेटा son beta", "बेटी daughter beti",
        "दोस्त मित्र यार फ्रेंड friend dost mitra",
        "पैसे पैसा रुपये रुपए रुपया पेमेंट money paisa paise rupees rupaye payment",
        "नौकरी जॉब ऑफिस दफ़्तर दफ्तर job naukri office",
        "डॉक्टर डाक्टर अस्पताल हॉस्पिटल doctor hospital daktar",
        "दवा दवाई गोली मेडिसिन medicine dawa dawai tablet",
        "जन्मदिन बर्थडे सालगिरह birthday janamdin bday",
        "शादी विवाह वेडिंग marriage wedding shaadi shadi vivah",
        "फ़ोन फोन मोबाइल phone mobile",
        "खाना भोजन food khana bhojan",
        "घर मकान home house ghar makan",
        "स्कूल कॉलेज पढ़ाई पढाई school college padhai study",
        "परीक्षा एग्जाम एग्ज़ाम पेपर exam pariksha paper test",
        "मीटिंग बैठक meeting baithak",
        "यात्रा सफ़र सफर ट्रिप घूमने travel trip yatra safar tour",
        "फ़िल्म फिल्म पिक्चर मूवी movie film picture",
        "गाना गाने संगीत म्यूज़िक म्यूजिक song songs gaana gana music",
        "पसंद फेवरेट फ़ेवरेट favourite favorite pasand like",
        "प्रोजेक्ट ऐप एप्लिकेशन project app application",
        "दुकान शॉप बिज़नेस बिजनेस धंधा shop dukan business dhandha",
        "बीमार बीमारी तबीयत तबियत बुखार sick ill bimar tabiyat fever bukhar",
        "पता एड्रेस address pata",
        "नंबर संख्या number",
    )

    private val GROUPS: List<Set<String>> = RAW_GROUPS.map { g -> g.split(' ').filter { it.isNotBlank() }.map { LocalRecall.norm(it) }.toSet() }

    /** [w] के एक-मतलब वाले शब्द (खुद [w] नहीं); छोटे रूप ("गाड़ियों" -> "गाडी") भी पहचाने */
    fun synonyms(w: String): Set<String> {
        val n = LocalRecall.norm(w)
        if (n.length < 2) return emptySet()
        val out = LinkedHashSet<String>()
        for (g in GROUPS) if (g.any { same(it, n) }) out.addAll(g)
        out.remove(n)
        return out
    }

    private fun same(a: String, b: String) = a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b.dropLast(1)) || b.startsWith(a.dropLast(1))))

    /** खोज-शब्द [w] का बात के शब्दों [toks] से मतलब/लिपि वाला मिलान (सीधा मिलान नहीं गिनता) */
    fun related(w: String, toks: List<String>): Boolean {
        val syn = synonyms(w)
        if (syn.isNotEmpty() && toks.any { t -> syn.any { same(it, t) } }) return true
        val sw = skeleton(w) ?: return false
        val dw = isDeva(w)
        return toks.any { t -> isDeva(t) != dw && skeleton(t) == sw }
    }

    fun tokens(text: String): List<String> = WORD.findAll(LocalRecall.norm(text)).map { it.value }.toList()

    /** पुरानी (बहुत बड़ी याद वाली) SQL LIKE खोज के लिए: शब्द + उनके एक-मतलब वाले (कुल [max] तक) */
    fun expand(words: List<String>, max: Int = 12): List<String> {
        val out = LinkedHashSet<String>(words)
        for (w in words) for (s in synonyms(w)) { if (out.size >= max) break; out.add(s) }
        return out.toList().take(max)
    }

    // ------------------------------------------------------------------ देवनागरी <-> रोमन
    private fun isDeva(s: String) = s.any { it in '\u0900'..'\u097F' }

    private val DEVA = mapOf(
        'क' to 'k', 'ख' to 'k', 'ग' to 'g', 'घ' to 'g', 'ङ' to 'n', 'च' to 'c', 'छ' to 'c', 'ज' to 'j', 'झ' to 'j', 'ञ' to 'n',
        'ट' to 't', 'ठ' to 't', 'ड' to 'd', 'ढ' to 'd', 'ण' to 'n', 'त' to 't', 'थ' to 't', 'द' to 'd', 'ध' to 'd', 'न' to 'n',
        'प' to 'p', 'फ' to 'f', 'ब' to 'b', 'भ' to 'b', 'म' to 'm', 'य' to 'y', 'र' to 'r', 'ल' to 'l', 'व' to 'v',
        'श' to 's', 'ष' to 's', 'स' to 's', 'ह' to 'h',
    )
    private val LATIN_PAIRS = listOf("chh" to "c", "ch" to "c", "kh" to "k", "gh" to "g", "jh" to "j", "th" to "t", "dh" to "d",
        "ph" to "f", "bh" to "b", "sh" to "s", "w" to "v", "z" to "j", "q" to "k", "x" to "ks", "ck" to "k")
    private const val VOWELS = "aeiou"

    /**
     * व्यंजनों का ढाँचा (स्वर/मात्रा हटाकर, दोहरे एक): "राहुल" -> "rhl", "rahul" -> "rhl"; "मुंबई"/"mumbai" -> "mb".
     * 3 से कम व्यंजन = null (इतने छोटे ढाँचे पर भरोसा नहीं).
     */
    fun skeleton(word: String): String? {
        val n = LocalRecall.norm(word)
        if (n.length < 3) return null
        val raw = StringBuilder()
        if (isDeva(n)) {
            for (ch in n) DEVA[ch]?.let { raw.append(it) }
        } else {
            if (!n.all { it in 'a'..'z' }) return null
            var s = n
            for ((a, b) in LATIN_PAIRS) s = s.replace(a, b.uppercase())
            s = s.lowercase()
            for ((i, ch) in s.withIndex()) {
                if (ch in VOWELS) continue
                // "pankaj"/"पंकज": अनुस्वार (ं) देवनागरी में गिना नहीं जाता - व्यंजन से पहले वाला n/m भी नहीं
                val next = s.getOrNull(i + 1)
                if ((ch == 'n' || ch == 'm') && next != null && next !in VOWELS && next != 'y') continue
                raw.append(ch)
            }
        }
        val out = StringBuilder()
        for (ch in raw) if (out.isEmpty() || out.last() != ch) out.append(ch)
        return out.toString().takeIf { it.length >= 3 }
    }
}
