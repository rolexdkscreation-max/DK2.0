package com.dk.assistant.voice.st3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक का लॉग 16:21: "तरीका आम -> हल्का ... अब 4 कदम" और "रोबोट जैसी गूंज"):
 * 4 कदम पर Supertonic की आवाज़ धातु-सी, इसलिए हल्के तरीके में भी कम से कम 5 कदम.
 */
class St3LightStepsTest {
    @Test fun lightNeverBelowFive() {
        assertTrue(St3Load.LIGHT_STEPS >= 5)
        assertEquals(5, St3Load.stepsFor(St3Load.Mode.LIGHT, 8))
        assertEquals(3, St3Load.stepsFor(St3Load.Mode.LIGHT, 3))      // मालिक ने खुद कम रखे तो वही
        assertEquals(8, St3Load.stepsFor(St3Load.Mode.NORMAL, 8))
    }
}
