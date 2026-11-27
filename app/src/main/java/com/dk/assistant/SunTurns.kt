package com.dk.assistant

import kotlin.math.abs

/**
 * 27 नवंबर 2026 (मालिक: "आउटडोर में धूप रहते रंग वाली जाँच नहीं होती… फोटो वीडियो से नहीं होना चाहिए, रियल इंसान से ही").
 *
 * तेज़ धूप में स्क्रीन की रंगीन रोशनी ([FlashLive]) चेहरे पर दिखती ही नहीं (धूप ~1 लाख lux, स्क्रीन कुछ सौ) - वह फ़िज़िक्स है,
 * कोई सेटिंग उसे नहीं बदल सकती. तब (और सिर्फ़ तब, [QuickFaceGate] तय करता है) यह दूसरी लाइव जाँच - random सिर-घुमाना, पर
 * पुराने "एक तरफ़" वाले से बहुत सख़्त, ताकि सिर इधर-उधर घुमाता रिकॉर्ड किया वीडियो पास न हो:
 *  - [STEPS] कदम, हर बार नया random क्रम, और उसमें **एक ही तरफ़ लगातार दो बार** ज़रूर (जैसे बाएँ, बाएँ, दाएँ).
 *    बार-बार दोनों तरफ़ घूमता वीडियो "बाएँ - सीधे - फिर बाएँ" नहीं कर सकता - बीच में दूसरी तरफ़ जाते ही फ़ेल.
 *  - हर कदम पहले सीधे चेहरे पर ही माँगा जाता है; माँगने के [MIN_REACT_MS] से पहले घूम गया (पहले से घूमता वीडियो) = फ़ेल.
 *  - माँगी तरफ़ [STEP_MS] में पहुँचना ज़रूरी, उल्टी तरफ़ गया = तुरंत फ़ेल; फिर वापस सीधे.
 * साथ में (गेट में): वही लगातार एक चेहरा, AI anti-spoof की पुरानी सख़्त शर्त, और आख़िर में सीधे चेहरे पर सख़्त मालिक-मिलान. Pure - JVM test.
 */
class SunTurns(val steps: IntArray = randomSteps()) {
    enum class State { WAIT, DONE, FAIL }

    /** अभी कौन-सा कदम (0 से), [asked] = तरफ़ माँग ली गई (सीधा चेहरा मिलने के बाद) */
    var index = 0
        private set
    private var askedAt = 0L
    var asked = false
        private set
    private var turned = false
    var why = ""
        private set

    val side get() = steps[index.coerceAtMost(steps.size - 1)]

    fun update(now: Long, yaw: Float?): State {
        if (index >= steps.size) return State.DONE
        val y = yaw ?: return if (asked && now - askedAt > STEP_MS) fail("समय पर सिर नहीं घूमा") else State.WAIT
        val s = steps[index]
        if (!asked) {
            // माँगना सिर्फ़ सीधे चेहरे पर - ताकि घुमाना माँगने के बाद ही हो
            if (abs(y) <= FaceLiveness.CENTER) { asked = true; askedAt = now; turned = false }
            return State.WAIT
        }
        if (y * s <= -FaceLiveness.TURN) return fail("उल्टी तरफ़ घूमा")
        if (!turned) {
            if (y * s >= FaceLiveness.TURN) {
                if (now - askedAt < MIN_REACT_MS) return fail("माँगने से पहले ही घूमा")
                turned = true
            } else if (now - askedAt > STEP_MS) return fail("समय पर सिर नहीं घूमा")
            return State.WAIT
        }
        // घूम गया - अब वापस सीधे, तब अगला कदम
        if (abs(y) <= FaceLiveness.CENTER) { index++; asked = false; turned = false }
        else if (now - askedAt > STEP_MS * 2) return fail("वापस सीधे नहीं आया")
        return if (index >= steps.size) State.DONE else State.WAIT
    }

    private fun fail(w: String): State { why = w; return State.FAIL }

    fun hint(): String {
        val n = "(${(index + 1).coerceAtMost(steps.size)}/${steps.size})"
        return when {
            index >= steps.size -> "✓ धूप वाली लाइव जाँच पूरी — सीधे देखिए"
            !asked -> "☀ धूप वाली लाइव जाँच $n — पहले सीधे कैमरे में देखिए"
            turned -> "✓ ठीक — अब सीधे कैमरे में देखिए $n"
            else -> "☀ धूप वाली लाइव जाँच $n — सिर हल्का-सा ${FaceLiveness.side(steps[index])} घुमाइए"
        }
    }

    companion object {
        const val STEPS = 3
        const val STEP_MS = 3_000L
        /** इंसान को सुनकर / पढ़कर घूमने में कम से कम इतना लगता है */
        const val MIN_REACT_MS = 200L

        /** हर बार नया: दोनों तरफ़ आएँ और कहीं एक ही तरफ़ लगातार दो बार (बारी-बारी घूमता वीडियो पास न हो) */
        fun randomSteps(rnd: kotlin.random.Random = kotlin.random.Random.Default): IntArray {
            while (true) {
                val s = IntArray(STEPS) { if (rnd.nextBoolean()) 1 else -1 }
                if (s.any { it > 0 } && s.any { it < 0 } && s.toList().zipWithNext().any { (a, b) -> a == b }) return s
            }
        }
    }
}
