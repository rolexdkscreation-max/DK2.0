package com.dk.assistant

import com.dk.assistant.voice.st3.St3Load
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक का 15:53 लॉग): "थ्रेड जाँच 4 = 1918 ms ... -> 4 चुना" बातचीत के बीच चली और जवाब की पहली आवाज़
 * 8099 ms / 10751 ms में आई. अब जाँच सिर्फ़ तब जब मॉडल खुला न हो और आवाज़ बन/बज न रही हो.
 */
class St3BenchIdleTest {
    private fun src(p: String): String {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        return java.io.File(root, p).readText()
    }

    @Test fun rule() {
        assertTrue(St3Load.benchNow(modelOpen = false, busy = false))      // ऐप खुलते / Settings से "फिर से जाँचें"
        assertFalse(St3Load.benchNow(modelOpen = true, busy = false))      // बातचीत में मॉडल खुला
        assertFalse(St3Load.benchNow(modelOpen = false, busy = true))      // आवाज़ बन/बज रही
    }

    @Test fun wired() {
        val eng = src("com/dk/assistant/voice/st3/St3Engine.kt")
        assertTrue(eng.contains("if (St3Load.benchNow(synth != null, St3Load.busy())) bench(app)"))
        assertFalse(eng.contains("if (St3Store.threads(app) <= 0) bench(app)"))
        // Settings का "थ्रेड फिर से जाँचें" पहले मॉडल बंद करता है - तो वहाँ जाँच चलती रहे
        val re = eng.substring(eng.indexOf("fun rebench("))
        assertTrue(re.indexOf("releaseNow()") in 0 until re.indexOf("warm(c)"))
    }
}
