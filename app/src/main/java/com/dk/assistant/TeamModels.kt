package com.dk.assistant

/**
 * v3.6 (owner का नियम): कोडिंग टीम में हर एजेंट को उसके काम जितना मॉडल.
 *  - तेज़/मज़बूत (कोडिंग एजेंट का चुना मॉडल, सुझाया gemini-3.8-flash): कोडर, डिबगर, प्लानर, रिव्यूअर - कोड लिखना/जाँचना
 *  - हल्का (gemini-3.5-flash-lite): प्रॉम्प्ट इंजीनियर, मैनेजर, गिट, टेस्टर, QA, डिप्लॉयमेंट, परफॉरमेंस - समझना/बताना
 *    (हल्का मॉडल जल्दी जवाब देता है = धीमे नेटवर्क पर प्रॉम्प्ट इंजीनियर देर तक "लिख रहा हूँ" पर अटका नहीं रहता)
 * दोनों एक ही keys पर (5 Gemini keys तक) - limit/नेटवर्क/server गलती पर अगली key अपने-आप.
 */
object TeamModels {
    /** v5.2: मॉडल सदस्य के हिसाब से - एक सदस्य = एक मॉडल (रिव्यूअर की परफॉरमेंस जाँच भी उसी मज़बूत मॉडल पर) */
    val FAST_MEMBERS: Set<Seven.Member> = setOf(Seven.Member.CODER, Seven.Member.DEBUGGER, Seven.Member.PLANNER, Seven.Member.REVIEWER,
        Seven.Member.EXPLAINER)   // v5.1: समझाने वाला गहरा पढ़ता-समझाता है = मज़बूत मॉडल
    val FAST: Set<Seven.Agent> = Seven.Agent.values().filter { it.member in FAST_MEMBERS }.toSet()
    fun fast(a: Seven.Agent): Boolean = a in FAST
    fun modelFor(a: Seven.Agent, fastModel: String, lightModel: String): String = if (fast(a)) fastModel else lightModel
}
