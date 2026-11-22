package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 22 नवंबर 2026 (मालिक): "जी बॉस" सिर्फ़ शुरुआत में / बुलाने पर; "हेलो जी boss" नहीं, "हेलो बॉस" */
class NamePlacementTest {
    private val n = NameRule.names("बॉस", "")

    @Test fun helloWithoutJi() {
        assertEquals("हेलो boss... आपकी आवाज़ सुनकर सुबह खूबसूरत हो गई।",
            NameRule.tidy("हेलो जी boss... आपकी आवाज़ सुनकर सुबह खूबसूरत हो गई।", n))
        assertEquals("नमस्ते बॉस", NameRule.tidy("नमस्ते जी बॉस", n))
    }

    @Test fun tailMovesToStartInFullReply() {
        assertEquals("जी बॉस, मैं यह काम कर रहा हूँ।", NameRule.tidy("मैं यह काम कर रहा हूँ जी बॉस।", n))
        // बाद वाले वाक्य में सिर्फ़ हटता है
        assertEquals("जी बॉस, हो गया। और कुछ करना है?", NameRule.tidy("हो गया जी बॉस। और कुछ करना है जी बॉस?", n))
    }

    @Test fun tailDroppedWhenStreaming() {
        assertEquals("मैं यहाँ काम कर रहा हूँ।", NameRule.tidy("मैं यहाँ काम कर रहा हूँ जी बॉस।", n, stream = true))
    }

    @Test fun goodPlacesStay() {
        for (t in listOf("जी बॉस, मैं यहाँ काम कर रहा हूँ।", "हाँ जी बॉस", "ठीक है जी सर", "जी बॉस", "मेरे बॉस ने कहा था।", "आप कैसे हैं जी?"))
            assertEquals(t, NameRule.tidy(t, n))
        assertEquals("हो गया जी", NameRule.tidy("हो गया जी", emptyList()))
    }

    @Test fun cannedLineNoJiAfterVerb() {
        assertEquals("हो गया धीरज", NameRule.swap("हो गया बॉस", "धीरज", ""))
        assertEquals("हाँ जी धीरज", NameRule.swap("हाँ बॉस", "धीरज", ""))
        assertEquals("ठीक है जी सर", NameRule.swap("ठीक है बॉस", "सर", ""))
        assertEquals("जी पापा, कॉल आ रहा है।", NameRule.swap("बॉस, कॉल आ रहा है।", "पापा", ""))
    }

    @Test fun promptRules() {
        val b = NameRule.block("बॉस", "DK")
        assertTrue(b.contains("\"जी बॉस\"") && b.contains("ONLY at the START") && b.contains("Never \"हेलो जी बॉस\""))
        val p = NameRule.placement("बॉस")
        assertTrue(p.contains("NEVER in the middle or at the END") && p.contains("\"हेलो बॉस\""))
        assertFalse(ChatGreet.newNote("जी धीरज").contains("नमस्ते जी"))
        assertTrue(ChatGreet.newNote("जी धीरज").contains("\"नमस्ते धीरज\""))
    }
}
