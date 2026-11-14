package com.dk.assistant

/**
 * 14 नवंबर 2026 (मालिक: "Solo मोड में सारे संदेश 'DK एजेंट' नाम से दिखाओ, मैनेजर वाले शब्द हटाओ").
 *
 * कोडिंग एजेंट अब एक ही दिमाग है ([Prefs.coderSolo] = हमेशा true), पर अंदर का ढाँचा ([SevenTeam]) पुराना टीम वाला है
 * और हर संदेश किसी पद के नाम से छपता था: "👔 मैनेजर: …", "🔍 रिव्यूअर: वापस कोडर को…".
 * यहाँ एक ही जगह सब संदेश साफ़ होते हैं - स्क्रीन, इतिहास, सूचना और आवाज़ में सिर्फ़ "🧠 DK एजेंट".
 * pure - JVM test (SoloVoiceTest).
 */
object SoloVoice {
    const val NAME = "DK एजेंट"
    const val ICON = "🧠"
    const val LABEL = "$ICON $NAME"

    /** पुराने पदों के निशान (Seven.Member के icon + जाँचकर्ता) */
    private val ICONS = listOf("👔", "🗺", "⌨", "🔍", "🧪", "🐞", "🌿", "🎓", "🔎")

    /** पहले लंबे वाक्यांश, फिर अकेले शब्द (क्रम ज़रूरी) */
    private val PHRASES = listOf(
        "जवाब मैं (मैनेजर) दे रहा हूँ" to "जवाब मैं दे रहा हूँ",
        "मैनेजर की समझ" to "मेरी समझ",
        "मैनेजर का छोटा जवाब" to "छोटा जवाब",
        "मैनेजर ने" to "मैंने",
        "टेस्टर (QA जाँच) ने" to "QA जाँच ने",
        "प्लानर से सुधार करवा रहा हूँ" to "सुधार का नया प्लान बना रहा हूँ",
        "प्लानर से इस हिस्से का" to "इस हिस्से का",
        "प्लानर से" to "नए प्लान से",
        "प्लानर अटका" to "प्लान अटका",
        "रणनीति बदल रहा हूँ: प्लानर" to "रणनीति बदल रहा हूँ:",
        "वापस कोडर को" to "फिर से ठीक कर रहा हूँ",
        "वही कोडर" to NAME,
        "कोडर काम शुरू कर रहा है" to "$NAME काम शुरू कर रहा है",
        "7 एजेंट की टीम" to NAME, "7-एजेंट" to NAME, "11 की टीम" to NAME,
        "कोडिंग टीम" to NAME,
        "पूरी टीम" to NAME,
        "काम सौंप रहा हूँ" to "काम शुरू कर रहा हूँ",
    )
    private val WORDS = listOf("मैनेजर", "प्लानर", "कोडर", "रिव्यूअर", "टेस्टर", "डिबगर", "गिट/रिलीज़", "समझाने वाला", "समझाने वाले", "जाँचकर्ता", "टीम")

    /** "→ X को सौंप रहा हूँ / को सौंपा" = काम किसी और को देना - अब खुद करता है */
    private val HAND_OFF = Regex("""(→\s*)?(\S+\s+){0,2}को\s+सौंप(ा|ी|े|ता|ते)?(\s+रहा\s+हूँ)?""")
    private val MANY_SPACES = Regex("""[ \t]{2,}""")

    /** संदेश से पद के नाम / सौंपना हटाओ */
    fun clean(t: String): String {
        if (t.isEmpty()) return t
        var s = t
        for (i in ICONS) s = s.replace("$i ", "").replace(i, "")
        for ((a, b) in PHRASES) s = s.replace(a, b)
        s = HAND_OFF.replace(s) { m -> if (m.value.trimStart().startsWith("→")) "→ खुद कर रहा हूँ" else "खुद कर रहा हूँ" }
        for (w in WORDS) s = s.replace(w, NAME)
        // "DK एजेंट: DK एजेंट: …" / "DK एजेंट (DK एजेंट)" जैसे दोहराव
        s = s.replace("$NAME: $NAME:", "$NAME:").replace("$NAME ($NAME)", NAME).replace("$NAME → $NAME", NAME)
        return s.lines().joinToString("\n") { MANY_SPACES.replace(it, " ").trimEnd() }
    }

    /** स्क्रीन की एक लाइन: "🧠 DK एजेंट: …" */
    fun line(t: String): String = "$LABEL: " + clean(t)
}
