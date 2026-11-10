package com.dk.assistant

/**
 * v3.6 (owner का नियम): कोडिंग टीम में हर एजेंट को उसके काम जितना मॉडल.
 *  - तेज़/मज़बूत (कोडिंग एजेंट का चुना मॉडल, सुझाया gemini-3.8-flash): कोडर, डिबगर, प्लानर, रिव्यूअर - कोड लिखना/जाँचना
 *  - बाकी (Settings का "हल्के एजेंटों का मॉडल"): प्रॉम्प्ट इंजीनियर, मैनेजर, गिट, टेस्टर, QA, डिप्लॉयमेंट, परफॉरमेंस - समझना/बताना.
 *    नवंबर 2026 (मालिक): default भी gemini-3.8-flash - सारे एजेंट एक के बाद एक वही मॉडल; धीमे नेटवर्क पर user चाहे तो flash-lite चुने
 * दोनों एक ही keys पर (5 Gemini keys तक) - limit/नेटवर्क/server गलती पर अगली key अपने-आप.
 */
object TeamModels {
    /** v5.2: मॉडल सदस्य के हिसाब से - एक सदस्य = एक मॉडल (रिव्यूअर की परफॉरमेंस जाँच भी उसी मज़बूत मॉडल पर) */
    val FAST_MEMBERS: Set<Seven.Member> = setOf(Seven.Member.CODER, Seven.Member.DEBUGGER, Seven.Member.PLANNER, Seven.Member.REVIEWER,
        Seven.Member.EXPLAINER)   // v5.1: समझाने वाला गहरा पढ़ता-समझाता है = मज़बूत मॉडल
    val FAST: Set<Seven.Agent> = Seven.Agent.values().filter { it.member in FAST_MEMBERS }.toSet()
    fun fast(a: Seven.Agent): Boolean = a in FAST
    fun modelFor(a: Seven.Agent, fastModel: String, lightModel: String): String = if (fast(a)) fastModel else lightModel
    /** नवंबर 2026: मज़बूत मॉडल (Settings → कोडिंग एजेंट) हो तो कोडर/डिबगर/प्लानर उस पर, बाकी तेज़/हल्का पहले जैसा */
    fun <T> pick(a: Seven.Agent, fastAi: T, lightAi: T, strongAi: T?): T = when {
        strongAi != null && StrongAi.uses(a) -> strongAi
        fast(a) -> fastAi
        else -> lightAi
    }
    /**
     * नवंबर 2026 (मालिक: "काम समझने और समझाने में बहुत समय"): बात करने वाले एजेंट (समझना/सौंपना/समझाना/branch) -
     * Settings में सोचने का स्तर "ऑटो" हो तो इन पर कम सोच (low) = जवाब कई गुना जल्दी. कोड लिखने/जाँचने वाले
     * (प्लानर, कोडर, रिव्यूअर, डिबगर, टेस्टर, QA) पहले जैसे पूरी सोच के साथ - गुणवत्ता वही.
     */
    val QUICK_TALK: Set<Seven.Agent> = setOf(Seven.Agent.MANAGER, Seven.Agent.PROMPT, Seven.Agent.EXPLAINER, Seven.Agent.GIT)
    /** इस एजेंट के लिए सोचने का स्तर: user ने खुद चुना हो तो वही, ऑटो हो तो बात वाले एजेंट = low */
    fun thinkFor(a: Seven.Agent, userLevel: String): String =
        if (ThinkLevel.norm(userLevel) == ThinkLevel.AUTO && a in QUICK_TALK) ThinkLevel.LOW else userLevel

    /** नवंबर 2026: Settings वाला दायरा ([StrongAi.PREF_SCOPE]) - "gates" = जाँचने वाले भी मज़बूत मॉडल पर */
    fun <T> pick(a: Seven.Agent, fastAi: T, lightAi: T, strongAi: T?, scope: String): T = when {
        strongAi != null && StrongAi.uses(a, scope) -> strongAi
        fast(a) -> fastAi
        else -> lightAi
    }
}
