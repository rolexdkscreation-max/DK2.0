package com.dk.assistant.agent

import com.dk.assistant.agent.core.ConfirmGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16 नवंबर 2026 (ऑडिट G4): आवाज़ वाला "हाँ" ढीला था - लंबी बात में कहीं भी "ok" / "ha" = भेज दिया */
class ConfirmStrictYesTest {
    @Test fun shortClearYes() {
        for (y in listOf("ok", "okay sir", "जी", "जी हाँ", "ha ji", "हाँ बॉस", "yes please", "haan bhej do", "ठीक है", "sure"))
            assertTrue(y, ConfirmGate.isYes(y))
    }

    @Test fun fillerOrLongTalkIsNotYes() {
        for (n in listOf(
            "ok पहले बताओ किसको जा रहा है",
            "ha ye kya likha hai dikhao",
            "okay so what is this message about",
            "हा मतलब वो वाला नहीं",
            "yes?", "ok?", "sir", "boss",
            "ji main soch raha hoon baad mein karte hain kal",
        )) assertFalse(n, ConfirmGate.isYes(n))
    }
}
