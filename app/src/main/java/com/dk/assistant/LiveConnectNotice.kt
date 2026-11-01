package com.dk.assistant

/**
 * मालिक (v5.x): "माइक ऑन होते ही DK लाइव 1 सेकंड में जुड़े, टूटे तो 1 सेकंड में फिर जुड़े।
 * बाय चांस न जुड़ पाए तो यूज़र को बोल दे - ताकि उसे न लगे कि DK सुन नहीं रही या जवाब नहीं दे रही।"
 *
 * Pure logic (कोई Android import नहीं) -> JVM unit test में चलता है.
 *  - जुड़ने की कोशिश लगातार इतनी बार fail = यूज़र को एक बार बोलो (हर कोशिश पर नहीं - बार-बार बड़बड़ नहीं)
 *  - फिर जुड़ गया = स्क्रीन पर "जुड़ गई, अब बोलिए" (Live का माइक चालू है, इसलिए फ़ोन की आवाज़ में नहीं - गूँज न बने)
 */
object LiveConnectNotice {
    /** इतनी लगातार नाकाम कोशिशें (हर कोशिश के बीच ≤1s) = यूज़र को बताओ */
    const val TELL_AFTER_FAILS = 2

    fun shouldTell(failsInRow: Int, alreadyTold: Boolean): Boolean = !alreadyTold && failsInRow >= TELL_AFTER_FAILS

    const val TROUBLE = "सर, अभी DK लाइव से जुड़ नहीं पा रही हूँ, शायद नेटवर्क कमज़ोर है। मैं फिर से जोड़ रही हूँ, जुड़ते ही बता दूँगी।"
    const val BACK = "सर, DK लाइव फिर से जुड़ गई। अब बोलिए, मैं सुन रही हूँ।"

    const val MIC = "सर, माइक अभी कोई दूसरा ऐप इस्तेमाल कर रहा है, इसलिए DK लाइव सुन नहीं पा रही। मैं फिर से जोड़ रही हूँ।"

    /** Live रुका (Fatal) - यूज़र को कौन-सी लाइन बोलें */
    fun forStop(reason: String?, recoverable: Boolean): String = when {
        (reason ?: "").contains("माइक") -> MIC
        recoverable -> TROUBLE
        else -> hard(reason)
    }

    /** असली रुकावट (key / limit) - कारण सीधे-सादे शब्दों में, तकनीकी गड़बड़ नहीं */
    fun hard(reason: String?): String {
        val r = (reason ?: "").lowercase()
        val why = when {
            r.contains("limit") || r.contains("लिमिट") || r.contains("quota") || r.contains("429") -> "आज की फ्री लिमिट खत्म हो गई है"
            r.contains("key") -> "Gemini की key काम नहीं कर रही, Settings में key देख लीजिए"
            r.contains("network") || r.contains("नेटवर्क") || r.contains("timeout") || r.contains("host") -> "नेटवर्क नहीं मिल रहा"
            else -> "कुछ देर के लिए Live सर्वर नहीं मिल रहा"
        }
        return "सर, DK लाइव अभी नहीं जुड़ पा रही, $why। मैं पुराने तरीके से सुन रही हूँ और पीछे से जोड़ने की कोशिश जारी है।"
    }
}
