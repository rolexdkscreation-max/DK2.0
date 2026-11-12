package com.dk.assistant.insights

import com.dk.assistant.LiveAgentRoute

/**
 * नवंबर 2026 (मालिक): "DK लाइव बोलता है 'हाँ, बैकग्राउंड में रिव्यू कर रहा हूँ' पर स्क्रीन पर कुछ नहीं दिखता - झूठ न बोले.
 * सच में कर रहा हो तभी 'हाँ' बोले, और रिव्यू मेरे सामने स्क्रीन पर दिखे - कौन-सा ऐप, उसके अंदर क्या कर रहा है."
 *
 * जड़ (root cause):
 *  1. GeminiLiveChecks.kt agentCheck() -> LiveAgentRoute सिर्फ़ कहानी / वेबसाइट / DK कंप्यूटर / कोडिंग पहचानता था. Live मॉडल ने
 *     social_insights बुलाए बिना "हाँ, रिव्यू कर रहा हूँ" कहा तो promiseCheck() उसे इंटरनेट सर्च (या आम phone_task) बना देता था -
 *     रिव्यू कभी शुरू ही नहीं होता था = झूठ.
 *  2. InsightsAgent.liveTool() हर बार "STARTED ON SCREEN" लौटाता था - ऐप खुला या नहीं, फ़ोन लॉक है, ऐप फ़ोन में है ही नहीं - बिना जाँचे.
 *  3. TaskStatus.endNote / summary सोशल रिव्यू को भी "background task" कहते थे - मॉडल वही शब्द दोहराता था.
 *
 * यह हिस्सा Android के बिना (JVM test): कब फ़ोन खुद रिव्यू शुरू करे, कब मॉडल को सुधारे, और tool का सच्चा जवाब.
 */
object InsightsTruth {
    /** "पीछे / बैकग्राउंड में कर रहा हूँ" - सोशल रिव्यू के लिए यह हमेशा गलत है */
    private val BACKGROUND = listOf(
        "बैकग्राउंड", "बैक ग्राउंड", "बैकग्राउन्ड", "background", "back ground", "पीछे से कर", "पीछे से देख", "पीछे चल", "पीछे-पीछे",
        "छुपकर", "छुप कर", "चुपचाप", "बिना दिखे", "बिना दिखाए", "hidden", "behind the scenes",
        "بیک گراؤنڈ", "بیک گراونڈ", "پیچھے سے", "چھپ کر", "خاموشی سے",
    )
    /** रिव्यू वाली बात (DK के जवाब में) */
    private val REVIEW = listOf(
        "रिव्यू", "रीव्यू", "review", "एनालिसिस", "analysis", "analyz", "analys", "इनसाइट", "insight", "आँकड़े", "आंकड़े",
        "डैशबोर्ड", "dashboard", "एनालिटिक्स", "analytics", "ریویو", "جائزہ", "تجزیہ", "ڈیش بورڈ",
    )
    /** यूज़र हालत पूछ रहा है ("रिव्यू कर रहे हो?") - नया काम नहीं कह रहा */
    private val STATUS_Q = listOf(
        "कर रहे हो", "कर रही हो", "कर रहा है क्या", "कर रही है क्या", "हो रहा है", "हो रहा है क्या", "चल रहा है", "चल रहा है क्या", "कहाँ तक", "कहां तक",
        "kar rahe ho", "kar rahi ho", "ho raha hai", "chal raha", "kahan tak", "are you doing", "is it running", "are you reviewing",
        "کر رہے ہو", "کر رہی ہو", "ہو رہا ہے", "چل رہا ہے", "کہاں تک",
    )

    private fun has(t: String, w: List<String>): Boolean { val x = " " + t.lowercase() + " "; return w.any { x.contains(it.lowercase()) } }

    fun claimsBackground(said: String): Boolean = has(said, BACKGROUND)
    fun mentionsReview(t: String): Boolean = has(t, REVIEW)
    fun asksStatus(user: String): Boolean = has(user, STATUS_Q)

    enum class Fix { NONE, START, NOT_RUNNING, ON_SCREEN }

    /**
     * बारी खत्म: यूज़र ने [user] कहा, DK ने [said] कहा.
     * [toolRecent] = social_insights अभी-अभी सच में चला; [running] = रिव्यू अभी स्क्रीन पर चल रहा है.
     *  - START       = DK ने "हाँ, कर रहा हूँ" कहा पर tool नहीं बुलाया, कुछ चल भी नहीं रहा -> फ़ोन खुद सच में शुरू करे
     *  - NOT_RUNNING = यूज़र ने पूछा "रिव्यू हो रहा है?" और DK ने हाँ कहा, पर कुछ नहीं चल रहा -> सच बोलवाओ
     *  - ON_SCREEN   = DK ने "बैकग्राउंड में" कहा -> सुधरवाओ: यह स्क्रीन पर, आपके सामने है (अभी का असली कदम)
     */
    fun check(user: String, said: String, toolRecent: Boolean, running: Boolean, bg: Boolean = false): Fix {
        val u = user.trim(); val s = said.trim()
        if (s.isEmpty()) return Fix.NONE
        // नवंबर 2026 (मालिक ने खुद कहा "बैकग्राउंड में करो"): तब "बैकग्राउंड में" सच है - सुधारना गलत
        if (bg && (running || toolRecent) && claimsBackground(s)) return Fix.NONE
        val asked = u.length >= 3 && InsightsVoice.parse(u) != null
        val talk = asked || (mentionsReview(s) && (InsightsVoice.platformsIn("$s $u").isNotEmpty() || running || toolRecent)) ||
            ((running || toolRecent) && mentionsReview(u))
        if (!talk) return Fix.NONE
        if (running || toolRecent) return if (claimsBackground(s)) Fix.ON_SCREEN else Fix.NONE
        if (!LiveAgentRoute.agreed(s)) return Fix.NONE
        return if (asksStatus(u)) Fix.NOT_RUNNING else Fix.START
    }

    /** tool चलने के बाद असल में क्या हुआ (कुछ सेकंड देखकर) */
    enum class Proof { OPENED, API, LOCKED, NO_APP, FAILED, SLOW, BUSY }

    /** DK लाइव को social_insights का सच्चा जवाब - जो सच में हुआ वही */
    fun toolReply(p: Proof, say: String, app: String, step: String): String {
        val st = step.trim().take(140)
        return when (p) {
            Proof.OPENED -> "STARTED ON SCREEN (verified: the $app app is open on the phone screen right now and DK is working inside it; a strip at the top shows every real step). " +
                "Say this to the user in short: \"$say\". Never say it is happening in the background or hidden. Do NOT give any numbers now - wait for the SYSTEM NOTE with the real result."
            Proof.API -> "STARTED: DK is reading the official API numbers now; the progress strip is on the screen (latest real step: \"$st\"). " +
                "Tell the user exactly that, in short. Do not say an app is open. Do NOT give numbers now - wait for the SYSTEM NOTE."
            Proof.BUSY -> "ALREADY RUNNING ON SCREEN (not a new start). Latest real step: \"$st\". Tell the user the review is already going on in front of him on the screen and what it is doing right now. Never say background."
            Proof.LOCKED -> "WAITING - NOT REVIEWING YET: the phone is locked, so DK cannot open the app. Tell the user truthfully: \"फ़ोन लॉक है - अनलॉक कर दीजिए, फिर मैं आपके सामने ऐप खोलकर रिव्यू करूँगी।\" " +
                "Do NOT say you are reviewing."
            Proof.NO_APP -> "NOT STARTED: ${st.ifBlank { "the app is not installed on this phone" }}. Tell the user truthfully that the review could not start and why. Do NOT say you are reviewing."
            Proof.FAILED -> "NOT STARTED: ${st.ifBlank { "the app did not open" }}. Tell the user truthfully that the review did not start and why. Do NOT say you are reviewing."
            Proof.SLOW -> "STARTING ON SCREEN, NOT OPEN YET: the app has not come up yet; latest real step: \"$st\". Tell the user only that you are opening the app on his screen right now " +
                "(he will see it). Do not claim you are already reviewing, never say background, no numbers."
        }
    }

    /**
     * नवंबर 2026: मालिक ने खुद "बैकग्राउंड में करो / ऐप मत खोलो" कहा - सच: ऐप नहीं खुलेगा, सिर्फ़ आधिकारिक API, कदम ऊपर सूचना में.
     */
    fun bgReply(step: String, already: Boolean): String =
        (if (already) "ALREADY RUNNING IN THE BACKGROUND (the user asked for it): " else "STARTED IN THE BACKGROUND (the user asked for it): ") +
            "DK is NOT opening any app; it reads only the official API numbers, and the progress is shown in the notification at the top" +
            (if (step.isNotBlank()) " (latest real step: \"${step.take(140)}\")" else "") +
            ". Tell the user in one short line that it is running without opening the app and he can keep doing his work. No numbers now - wait for the SYSTEM NOTE."

    /** बैकग्राउंड माँगा पर कोई API (कनेक्टर) नहीं जुड़ा - बिना ऐप खोले पढ़ना मुमकिन नहीं; सच बताओ, विकल्प दो */
    fun bgImpossible(platforms: String, savedAgo: String?): String =
        "NOT STARTED: the user asked for a background review of $platforms, but no official API connector is connected for it, and Android does not let DK " +
            "read another app's screen without opening it. Tell him truthfully in short Hindi and give the choices: " +
            (if (savedAgo != null) "(1) answer right now from the numbers DK saved in the last review ($savedAgo) - social_insights action=ask; " else "") +
            "(2) do it on screen in front of him now (social_insights action=start without background); " +
            "(3) connect the connector once in DK Settings -> कनेक्टर, after that background reviews work. Do NOT say you are reviewing."

    /** Proof का फ़ैसला (pure): ऐप खुला? API? फ़ोन लॉक? काम खत्म हो गया? */
    fun decide(opened: Boolean, apiOnly: Boolean, locked: Boolean, ended: Boolean, endedWithData: Boolean, problem: String): Proof = when {
        opened -> Proof.OPENED
        apiOnly -> Proof.API
        ended && endedWithData -> Proof.API
        ended && Num.nk(problem).contains(Num.nk("ऐप फ़ोन में नहीं")) -> Proof.NO_APP
        ended -> Proof.FAILED
        locked -> Proof.LOCKED
        else -> Proof.SLOW
    }

    fun startedNote(result: String): String =
        "SYSTEM NOTE: you told the user you are reviewing his social media, but you did NOT call social_insights - nothing was happening on the phone. " +
            "The phone has now done it itself. Real result: \"${result.take(700)}\". Follow that result exactly: tell the user in ONE short line what is really " +
            "happening now (on his screen, in front of him - never background). Do NOT call social_insights again for this request."

    fun notRunningNote(): String =
        "SYSTEM NOTE: you just said yes, but NO social media review is running on the phone right now (nothing is open, nothing is being read). " +
            "Correct yourself honestly in one short line, e.g. \"माफ़ कीजिए, अभी कोई रिव्यू नहीं चल रहा - कहें तो अभी आपके सामने स्क्रीन पर शुरू कर दूँ?\" " +
            "Never claim work that is not happening."

    fun onScreenNote(step: String?): String =
        "SYSTEM NOTE: you said the social review is happening in the background - that is wrong. It runs ON THE SCREEN in front of the user" +
            (if (!step.isNullOrBlank()) "; the latest real step is \"${step.take(140)}\"" else "") +
            ". Correct yourself in one short line, e.g. \"माफ़ कीजिए - बैकग्राउंड में नहीं, आपके सामने स्क्रीन पर कर रही हूँ" +
            (if (!step.isNullOrBlank()) ", अभी: ${step.take(80)}" else "") + "\". Never say background again for this review."

    /** एक बारी में दो बार सुधार नहीं */
    @Volatile var lastFixAt = 0L
    const val FIX_GAP_MS = 15_000L
}
