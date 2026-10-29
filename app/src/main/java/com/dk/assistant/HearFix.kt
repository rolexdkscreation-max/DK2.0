package com.dk.assistant

/**
 * v4.2 (मालिक: "गलत सुना शब्द हो तो उसका सही मतलब निकाले, और तुरंत काम करे").
 *
 * धीमी आवाज़ / भोजपुरी-मराठी लहजा / Hindi-English मिली बात में सुनने वाला (caption) कुछ शब्द ग़लत लिखता है -
 * जैसे "YouTube खोलो" -> "YouTube फॉलो", "पवन सिंह" -> "पबा सिंगा". फ़ोन का तेज़ रास्ता (LocalRouter) शब्द मिलाकर
 * चलता है, इसलिए ऐसी बात फ़ोन पर तुरंत न होकर Gemini तक जाती थी (धीमा) या अटक जाती थी.
 *
 * यहाँ सिर्फ़ पक्के, आम ग़लत-सुने रूप ठीक होते हैं (pure, JUnit से जाँचा) - बाकी मतलब Gemini (prompt का HEARING नियम) निकालता है.
 * text पहले से lowercase आता है (LocalRouter.normalize).
 */
object HearFix {
    private val APPS = listOf(
        "youtube", "यूट्यूब", "यूट्यूब", "यू ट्यूब", "whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप",
        "chrome", "क्रोम", "camera", "कैमरा", "gallery", "गैलरी", "settings", "सेटिंग", "सेटिंग्स", "maps", "मैप",
        "instagram", "इंस्टाग्राम", "facebook", "फेसबुक", "spotify", "स्पॉटिफाई", "calculator", "कैलकुलेटर",
        "phone pe", "phonepe", "फोनपे", "paytm", "पेटीएम", "telegram", "टेलीग्राम", "snapchat", "स्नैपचैट"
    )

    /** "खोलो" के ग़लत सुने रूप (सिर्फ़ ऐप के नाम के ठीक बाद) */
    private const val OPEN_MISHEARD = "फॉलो|फ़ॉलो|फोलो|फालो|होलो|खोलू|खुलो|कोलो|घोलो|खोला|follow|kholu|holo"

    private val APP_ALT = APPS.distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    // "youtube फॉलो" -> "youtube खोलो", पर "instagram पर उसे फॉलो करो" / "youtube फॉलो करो" (सच में follow) नहीं
    private val OPEN_FIX = Regex("""(?<![\p{L}\p{M}])($APP_ALT)\s+(?:को\s+)?(?:$OPEN_MISHEARD)(?![\p{L}\p{M}])(?!\s+(?:कर|kar|कीजिए|करना|करें))""")

    /** आम नाम जो लहजे/धीमी आवाज़ में बिगड़कर लिखे जाते हैं -> सही नाम */
    private val NAMES: List<Pair<Regex, String>> = listOf(
        """(?:पबा|पबन|पवा|पावन|पवन|पोवन|pawan|pavan|paban)\s*(?:सिंगा|सिंघ|सिंह|सींग|सिंग|सिंगह|singh|singa|sing)""" to "पवन सिंह",
        """(?:खेसारी|खेसारि|केसरी|kesari|khesari)\s*(?:लाल|लाला|lal)(?:\s*(?:यादव|yadav))?""" to "खेसारी लाल यादव",
        """(?:अरजीत|अरीजीत|अरिजित|arjit|arijit)\s*(?:सिंह|सिंग|singh)""" to "अरिजीत सिंह",
    ).map { (p, v) -> Regex("""(?<![\p{L}\p{M}])$p(?![\p{L}\p{M}])""", RegexOption.IGNORE_CASE) to v }

    fun fix(t: String): String {
        if (t.isBlank()) return t
        var s = OPEN_FIX.replace(t) { m -> m.groupValues[1] + " खोलो" }
        for ((re, v) in NAMES) s = re.replace(s, v)
        return s
    }
}
