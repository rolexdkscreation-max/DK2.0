package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक: DK लाइव का जवाब देर से): आम सवाल = एक खोज, मिलाने वाला दूसरा AI कॉल नहीं */
class LiveSearchQuickTest {
    @Test fun normalQuestion_oneSearchNoMerge() {
        val q = "आज सोने का भाव क्या है"
        assertTrue(LiveSearch.quick(q, deep = false))
        assertEquals(listOf("web"), LiveSearch.liveAngles(q, false).map { it.id })
        assertTrue(LiveSearch.skipMerge(1, deep = false))
        assertFalse(LiveSearch.skipMerge(2, deep = false))
        assertFalse(LiveSearch.skipMerge(1, deep = true))
    }

    @Test fun govNewsDeep_stillMultiAngle() {
        val gov = "लाडकी बहीण योजना की नई किस्त कब आएगी"
        assertFalse(LiveSearch.quick(gov, false))
        assertEquals(LiveSearch.plan(gov, false), LiveSearch.liveAngles(gov, false))
        assertEquals("official", LiveSearch.liveAngles(gov, false).first().id)
        val news = "आज की ताज़ा खबर क्या है"
        assertFalse(LiveSearch.quick(news, false))
        assertTrue(LiveSearch.liveAngles(news, false).size >= 2)
        val q = "आज सोने का भाव क्या है"
        assertEquals(LiveSearch.plan(q, true), LiveSearch.liveAngles(q, true))   // गहरी खोज = पहले जैसी पूरी
    }

    @Test fun wiredInBrain() {
        val b = listOf(File("src/main/java/com/dk/assistant/BrainManager.kt"), File("app/src/main/java/com/dk/assistant/BrainManager.kt")).first { it.exists() }.readText()
        assertTrue(b.contains("LiveSearch.liveAngles(question, deepS)"))
        assertTrue(b.contains("if (LiveSearch.skipMerge(good.size, deepS)) null else"))
    }
}
