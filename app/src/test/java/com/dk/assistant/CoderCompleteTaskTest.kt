package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** कई फ़ाइलों वाला काम आधा न रहे: नई फ़ाइल बनी पर इस्तेमाल नहीं = अधूरा; हर बदली फ़ाइल पर अतिरिक्त कदम */
class CoderCompleteTaskTest {
    private val cfg = "package com.dk.assistant\n\nobject ModelConfig {\n    const val CODER = \"x\"\n}\n"

    @Test fun newFileNotUsed_isIncomplete() {
        val r = CodingAgent.unusedNewFiles(mapOf("app/src/main/java/com/dk/assistant/ModelConfig.kt" to cfg),
            mapOf("app/src/main/java/com/dk/assistant/ModelConfig.kt" to cfg))
        assertEquals(listOf("app/src/main/java/com/dk/assistant/ModelConfig.kt" to "ModelConfig"), r)
    }

    @Test fun newFileUsedElsewhere_isFine() {
        val r = CodingAgent.unusedNewFiles(mapOf("a/ModelConfig.kt" to cfg),
            mapOf("a/ModelConfig.kt" to cfg, "a/TextChat.kt" to "val m = ModelConfig.CODER"))
        assertTrue(r.isEmpty())
        // नाम का हिस्सा भर (ModelConfigX) गिनती में नहीं
        assertEquals(1, CodingAgent.unusedNewFiles(mapOf("a/ModelConfig.kt" to cfg),
            mapOf("a/ModelConfig.kt" to cfg, "a/B.kt" to "val q = ModelConfigX")).size)
        // test फ़ाइल और गैर-कोड फ़ाइल नहीं जाँचते
        assertTrue(CodingAgent.unusedNewFiles(mapOf("app/src/test/X.kt" to "class XTest", "README.md" to "class A"), emptyMap()).isEmpty())
    }

    @Test fun stepLimit_growsWithChangedFiles() {
        assertEquals(5, CodingAgent.stepLimit(5, 0, 0))
        assertEquals(7, CodingAgent.stepLimit(5, 3, 0))
        assertEquals(11, CodingAgent.stepLimit(5, 2, 4))
        assertEquals(15, CodingAgent.stepLimit(5, 9, 20))
    }
}
