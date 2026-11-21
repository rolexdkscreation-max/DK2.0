package com.dk.assistant.voice

/**
 * 21 नवंबर 2026 (मालिक: "मेरे फैमिली मेंबर का भी आवाज़ पहचान सके ... मम्मी, पापा, भैया ... आसपास बोल रहा है तो DK
 * पहचान सके कौन बोल रहा है"): परिवार की आवाज़ें - pure नियम (JVM test: [VoiceFamilyTest]).
 *
 *  - हर सदस्य: नाम / रिश्ता (मम्मी, पापा, भैया ... या कोई भी नाम) + उसके सैंपल (वॉइस गार्डियन वाला ही मॉडल).
 *  - मालिक (Boss) अलग - वॉइस गार्डियन की अपनी सीखी आवाज़; यहाँ सिर्फ़ मिलान में साथ.
 *  - पहचान: सबसे ([VgRules.multiScore]) मिलाओ; सबसे ऊपर वाला अपनी सीमा से ऊपर हो और दूसरे नंबर से कम से कम
 *    [MARGIN] आगे हो = वही. वरना "पता नहीं" (ग़लत नाम लेने से अच्छा नाम न लेना).
 *  - सैंपल सिर्फ़ इसी फ़ोन में, ताले (Keystore) में बंद ([VoiceGuardian] रखता है).
 */
object VoiceFamily {
    const val MAX_MEMBERS = 8
    const val OWNER_NAME = "बॉस"

    /**
     * सबसे ऊपर और दूसरे नंबर के बीच कम से कम इतना फ़र्क.
     * कंप्यूटर पर नापा (LibriSpeech 40 लोग, 5 "परिवार" + 35 अनजान, 1.5 सेकंड): 0.05 पर ग़लत नाम लगभग नहीं, सही पहचान ज़्यादा नहीं गिरती.
     * ReDimNet2-B6 + 8 घर (बॉस 15 + परिवार 6 सैंपल): साफ़ 100% सही, शोर (5 dB) में 94-99%, ग़लत नाम 0%, अनजान को नाम ~1%.
     */
    const val MARGIN = 0.05f
    /** सदस्य की सीमा: उसकी अपनी आवाज़ से ([VgRules.calibrate]); सैंपल कम = यह */
    const val MEMBER_TH = 0.45f

    /** जल्दी चुनने वाले रिश्ते (Settings में बटन) - कोई भी दूसरा नाम भी लिख सकते हैं */
    val RELATIONS = listOf("मम्मी", "पापा", "भैया", "दीदी", "भाई", "बहन", "दादा जी", "दादी जी", "पत्नी", "बेटा", "बेटी", "दोस्त")

    /** परिवार वालों के सीखने के 6 वाक्य (सिर्फ़ सुझाव - कुछ भी बोल सकते हैं, पहचान आवाज़ की होती है) */
    val PHRASES = listOf(
        "डीके, आज मौसम कैसा रहेगा, ज़रा बताओ।",
        "मुझे कोई अच्छा सा पुराना गाना सुनाओ।",
        "अभी कितने बजे हैं, और आज कौन सा दिन है?",
        "डीके, आज की ताज़ा ख़बरें सुनाओ।",
        "खाने में आज क्या बनाया जाए, कुछ बताओ।",
        "डीके, मेरी आवाज़ अच्छे से पहचान लो।",
    )

    class Member(val id: String, val name: String, val templates: List<FloatArray>, val th: Float) {
        val centroid: FloatArray? get() = VoicePrint.mean(templates)
    }

    /** पहचान का नतीजा. name = null = पता नहीं (कोई अनजान, या दो के बीच पक्का नहीं) */
    data class Pick(val name: String?, val owner: Boolean, val score: Float, val second: Float, val memberId: String? = null) {
        val known: Boolean get() = name != null
    }

    /** नाम साफ़ करो: 1-24 अक्षर, बीच में टैब/नई लाइन नहीं. खाली / बहुत लंबा = null */
    fun cleanName(raw: String?): String? {
        val t = raw.orEmpty().replace(Regex("[\\t\\r\\n|;]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.length > 24) return null
        if (t == OWNER_NAME || t.equals("boss", ignoreCase = true)) return null      // "बॉस" सिर्फ़ मालिक
        return t
    }

    fun newId(now: Long, taken: Collection<String>): String {
        var n = now
        while ("m$n" in taken) n++
        return "m$n"
    }

    /** सदस्य की सीमा: उसकी आवाज़ के हिसाब से ([VgRules.calibrate]), वरना [MEMBER_TH] */
    fun memberTh(templates: List<FloatArray>): Float = VgRules.calibrate(templates) ?: MEMBER_TH

    /** एक सदस्य से मिलान (सारे सैंपल + औसत) */
    fun scoreOf(m: Member, e: FloatArray): Float = VgRules.multiScore(m.centroid, m.templates, e)

    /**
     * कौन बोल रहा है. ownerTh = वॉइस गार्डियन की सीमा. ownerScore null = मालिक की आवाज़ सीखी नहीं (तब सिर्फ़ परिवार).
     */
    fun identify(ownerScore: Float?, ownerTh: Float, members: List<Member>, e: FloatArray): Pick =
        decide(ownerScore, ownerTh, members.map { Triple(it, scoreOf(it, e), it.th) })

    /** [identify] का फ़ैसला वाला हिस्सा (score पहले से निकले हों) */
    fun decide(ownerScore: Float?, ownerTh: Float, scored: List<Triple<Member, Float, Float>>): Pick {
        data class C(val name: String, val owner: Boolean, val s: Float, val th: Float, val id: String?)
        val all = buildList {
            if (ownerScore != null && ownerScore.isFinite()) add(C(OWNER_NAME, true, ownerScore, ownerTh, null))
            scored.forEach { (m, s, th) -> if (s.isFinite()) add(C(m.name, false, s, th, m.id)) }
        }.sortedByDescending { it.s }
        if (all.isEmpty()) return Pick(null, false, Float.NaN, Float.NaN)
        val best = all[0]
        val second = all.getOrNull(1)?.s ?: -1f
        val ok = best.s >= best.th && best.s - second >= MARGIN
        return if (ok) Pick(best.name, best.owner, best.s, second, best.id) else Pick(null, false, best.s, second)
    }

    // ------------------------------------------------------------ सेव (एक लाइन = एक सदस्य: id \t नाम \t सीमा \t सैंपल)
    fun encode(list: List<Member>): String = list.joinToString("\n") { m ->
        listOf(m.id, cleanName(m.name) ?: "?", String.format(java.util.Locale.US, "%.4f", m.th), VgRules.encodeAll(m.templates)).joinToString("\t")
    }

    fun decode(s: String?): List<Member> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split("\n").mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 4) return@mapNotNull null
            val name = cleanName(p[1]) ?: return@mapNotNull null
            val t = VgRules.decodeAll(p[3])
            if (t.isEmpty()) return@mapNotNull null
            Member(p[0], name, t, VgRules.clampTh(p[2].toFloatOrNull() ?: MEMBER_TH))
        }.take(MAX_MEMBERS)
    }

    /** नया सदस्य जोड़ो / उसी नाम वाला बदलो (नाम पर बड़े-छोटे अक्षर का फ़र्क नहीं) */
    fun upsert(list: List<Member>, m: Member): List<Member> {
        val rest = list.filter { !it.name.equals(m.name, ignoreCase = true) && it.id != m.id }
        return (rest + m).takeLast(MAX_MEMBERS)
    }

    fun remove(list: List<Member>, id: String): List<Member> = list.filter { it.id != id }

    /** Settings की "कौन बोल रहा है" जाँच की लाइन */
    fun testLine(p: Pick?): String = when {
        p == null -> "आवाज़ साफ़ नहीं आई — फिर से 3 सेकंड बोलिए"
        p.owner -> "✓ यह आप (बॉस) हैं — मिलान ${VgRules.pct(p.score)}%"
        p.name != null -> "✓ यह ${p.name} हैं — मिलान ${VgRules.pct(p.score)}%"
        p.score.isFinite() -> "? पक्का नहीं — किसी सीखी आवाज़ से साफ़ नहीं मिली (सबसे ज़्यादा ${VgRules.pct(p.score)}%)"
        else -> "पहले अपनी (बॉस) या परिवार की आवाज़ सिखाएँ"
    }
}
