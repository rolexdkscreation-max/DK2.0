package com.dk.assistant

/**
 * v3.59: DK लाइव की आवाज़ + भाषा के पक्के डिफ़ॉल्ट (Android import नहीं -> JVM test).
 *
 * - आवाज़: यूज़र ने कुछ न चुना हो तो हमेशा "Sulafat" (पुरुष आवाज़ चुनी हो तो पुरुष सूची की पहली).
 *   किसी मॉडल पर चुनी आवाज़ न चले तो चुपचाप "Aoede" - यूज़र की चुनी पसंद नहीं बदलती.
 * - भाषा: speechConfig.languageCode = "hi-IN" (native-audio मॉडल इसे न लें तो उसके बिना).
 * - affective dialog: native-audio मॉडल पर चालू (3.1 Flash Live इसे नहीं लेता).
 *
 * जो चीज़ किसी मॉडल पर setup तोड़ दे, वह उस मॉडल के लिए याद रहती है ("off" सूची, Prefs में) -
 * अगली बार पहली कोशिश में ही बिना उसके, ताकि जुड़ने में देर न हो.
 * एक बातचीत में एक ही आवाज़: setup के समय एक बार तय, बीच में अपने-आप नहीं बदलती.
 */
object LiveVoice {
    const val DEFAULT = "Sulafat"
    const val FALLBACK = "Aoede"
    const val LANGUAGE = "hi-IN"
    const val OFF_KEY = "live_extras_off"

    /** Settings की पसंद से असली आवाज़ (खाली/अमान्य = डिफ़ॉल्ट) */
    fun pick(saved: String?, gender: String): String {
        val list = VoiceCatalog.geminiFor(gender)
        if (!saved.isNullOrBlank() && saved in list) return saved
        return if (DEFAULT in list) DEFAULT else list.first()
    }

    /** native audio मॉडल (सारे Live मॉडल: 2.5 native-audio, 3.x live) */
    fun isNative(model: String): Boolean = model.contains("native-audio") || model.contains("live")

    /** affective dialog देने लायक मॉडल */
    fun affectiveFor(model: String): Boolean =
        isNative(model) && !(model.contains("3.1") && model.contains("flash"))

    /** setup में भेजी जाने वाली अतिरिक्त चीज़ें */
    data class Extras(val voice: String, val lang: Boolean, val affective: Boolean)

    /** इस मॉडल के लिए शुरुआती Extras - "off" सूची (पहले टूट चुकी चीज़ें) हटाकर */
    fun start(model: String, chosenVoice: String, off: Set<String>): Extras = Extras(
        voice = if ("$model|voice:$chosenVoice" in off) FALLBACK else chosenVoice,
        lang = "$model|lang" !in off,
        affective = affectiveFor(model) && "$model|aff" !in off,
    )

    /** एक कदम पीछे: क्या हटाया + नया Extras. कुछ हटाने को न बचे तो null (तब पुराना रास्ता - अगला मॉडल) */
    data class Step(val next: Extras, val offEntry: String, val what: String)

    fun degrade(model: String, cur: Extras, reason: String, code: Int): Step? {
        val r = reason.lowercase()
        // मॉडल ही नहीं मिला / API key / कोटा -> यह आवाज़-भाषा की गलती नहीं, पुराना रास्ता
        if (r.contains("not found") || r.contains("api key") || r.contains("quota") || code == 401 || code == 403 || code == 404) return null
        val voiceOk = cur.voice != FALLBACK
        // पहले वह जिसका नाम error में साफ़ लिखा हो
        if (cur.affective && r.contains("affective"))
            return Step(cur.copy(affective = false), "$model|aff", "affective dialog")
        if (cur.lang && (r.contains("language")))
            return Step(cur.copy(lang = false), "$model|lang", "languageCode")
        if (voiceOk && (r.contains("voice") || r.contains("speaker")))
            return Step(cur.copy(voice = FALLBACK), "$model|voice:${cur.voice}", "आवाज़ ${cur.voice}")
        // साफ़ नाम नहीं (close reason कटा/सामान्य "invalid argument") -> एक-एक करके
        val generic = code == 1007 || r.contains("invalid") || r.contains("unknown name") || r.contains("not supported")
        if (!generic) return null
        if (cur.affective) return Step(cur.copy(affective = false), "$model|aff", "affective dialog")
        if (cur.lang) return Step(cur.copy(lang = false), "$model|lang", "languageCode")
        if (voiceOk) return Step(cur.copy(voice = FALLBACK), "$model|voice:${cur.voice}", "आवाज़ ${cur.voice}")
        return null
    }

    fun parseOff(s: String?): Set<String> = s.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun joinOff(set: Set<String>): String = set.joinToString("\n")
}
