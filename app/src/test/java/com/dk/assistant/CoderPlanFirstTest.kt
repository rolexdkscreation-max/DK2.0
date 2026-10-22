package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** कोडिंग एजेंट पहले बताता है कि क्या पढ़/समझ रहा है, फिर पूरा काम करता है */
class CoderPlanFirstTest {
    @Test fun autoPlan_readFirstThenFix() {
        val p = CodingAgent.autoPlan("read", "", listOf("app/src/CodingAgent.kt", "app/src/GitHub.kt"), "")
        assertTrue(p.startsWith("पहले CodingAgent.kt, GitHub.kt का कोड पढ़ रहा हूँ"))
        assertTrue(p.contains("फिर समझकर पूरा काम ठीक करूँगा"))
        assertTrue(CodingAgent.autoPlan("grep", "", emptyList(), "push(").contains("\"push(\" कहाँ है"))
        assertTrue(CodingAgent.autoPlan("edit", "a/B.kt", emptyList(), "").startsWith("B.kt में बदलाव"))
        assertEquals("", CodingAgent.autoPlan("done", "", emptyList(), ""))
    }

    @Test fun teamFallbackPlan_mentionsNewPr() {
        assertTrue(CoderTeam.firstPlan(true).contains("पहले कोड पढ़कर"))
        assertTrue(CoderTeam.firstPlan(true).contains("नई PR"))
        assertTrue(!CoderTeam.firstPlan(false).contains("PR"))
    }

    @Test fun finalReport_showsPlanFirst() {
        val r = CoderReport.final(true, "हो गया", emptyList(), plan = "पहले X पढ़ रहा हूँ, फिर Y ठीक करूँगा")
        assertTrue(r.startsWith("📋 रिपोर्ट\n🧭 योजना: पहले X पढ़ रहा हूँ"))
        assertTrue(r.indexOf("🧭") < r.indexOf("✔ क्या हुआ"))
        assertTrue(!CoderReport.final(true, "हो गया", emptyList(), plan = "").contains("🧭"))
    }
}
