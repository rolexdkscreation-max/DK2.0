package com.dk.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 20 नवंबर 2026 (मालिक: Live के निर्देश छोटे करो, दोहराई बातें हटाओ): स्वभाव के अंग्रेज़ी नियम छोटे - पर हर ज़रूरी नियम बाकी */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LivePromptSlimTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun personaRules_shorter() {
        val sum = DkPersona.HEART_RULES.length + DkPersona.LOVE_RULES.length + DkPersona.MOOD_RULES.length + DkPersona.HUMAN_RULES.length
        assertTrue("स्वभाव नियम $sum", sum < 2_400)
        assertTrue(DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "धीरज").length < 3_500)
    }

    @Test fun keyRulesStillThere() {
        val g = DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "धीरज")
        for (k in listOf("MOOD FIRST", "drop ALL teasing", "Happy or excited", "अरे धीरज, क्या हुआ?", "YOUR OWN HEART", "JOY", "SADNESS", "FEAR", "PAIN",
            "सुरीली", "soft breath", "stage directions", "LOVING DEFAULT", "RESPECT", "THANK YOU", "अरे धीरज, आपसे थैंक यू?",
            "no jealousy, no guilt", "never stop him from leaving", "NO sexual, explicit or vulgar", "under 18"))
            assertTrue("नहीं मिला: $k", g.contains(k))
    }

    @Test fun livePromptLite_underLimit() {
        Prefs.setLivePromptLite(c, true)
        val p = BrainManager(c).liveSystemPrompt(withMemory = false)
        assertTrue("Live निर्देश ${p.length} अक्षर", p.length < 29_000)
        assertTrue(p.contains(SpeakingStyle.OWNER_STYLE))
    }
}
