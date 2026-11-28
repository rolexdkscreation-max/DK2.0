package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 28 नवंबर 2026 (मालिक: "कोडिंग एजेंट स्लो है, Gemini न चले तो अगली API, फास्ट मॉडल से काम, बग क्यों नहीं ढूँढ पाता").
 * [CoderHealth] (चलती/तेज़ API पहले, टूटी पीछे, छोटी सीमा वाली API पर कटी request नहीं) + Solo कोडर की
 * कमज़ोर-मॉडल वाली गलतियाँ (पंक्ति-नंबर/``` वाला FIND, "**READ:**" कमांड, छोटे-बड़े अक्षर वाली खोज).
 */
class CoderFastFallbackTest {
    @After fun clean() = CoderHealth.reset()

    private val labels = listOf("Gemini", "Groq", "OpenRouter", "Z.ai", "NVIDIA")

    @Test fun order_unchanged_whenNothingKnown() {
        assertEquals(labels, CoderHealth.order(labels, { false }, { null }))
    }

    @Test fun order_failedApiGoesLast_neverRemoved() {
        val o = CoderHealth.order(labels, { it == "Gemini" || it == "Groq" }, { null })
        assertEquals(listOf("OpenRouter", "Z.ai", "NVIDIA", "Gemini", "Groq"), o)
        assertEquals(labels.toSet(), o.toSet())                     // कोई API हटती नहीं
    }

    @Test fun order_mainFirst_thenFastestWorking() {
        val speed = mapOf("NVIDIA" to 3_000.0, "OpenRouter" to 20_000.0)
        val o = CoderHealth.order(labels, { false }, { speed[it] })
        assertEquals(listOf("Gemini", "NVIDIA", "OpenRouter", "Groq", "Z.ai"), o)   // मुख्य पहले, फिर नापी हुई तेज़
        val g = CoderHealth.order(labels, { it == "Gemini" }, { speed[it] })
        assertEquals("NVIDIA", g.first())                              // मुख्य आराम पर = सबसे तेज़ चलती API
        assertEquals("Gemini", g.last())
    }

    @Test fun failAndOk_withClock() {
        CoderHealth.fail("Groq", 429, 0, now = 1_000L)
        assertTrue(CoderHealth.cooling("Groq", now = 1_000L + 59_000L))
        assertFalse(CoderHealth.cooling("Groq", now = 1_000L + 61_000L))
        CoderHealth.fail("Groq", 401, now = 0L)
        CoderHealth.ok("Groq", 4_000L)
        assertFalse(CoderHealth.cooling("Groq", now = 1L))                 // चल गई = आराम खत्म
        CoderHealth.ok("Groq", 9_000L)
        assertEquals(6_000.0, CoderHealth.speed("Groq")!!, 0.1)           // औसत
        CoderHealth.fail("X", 400, now = 0L)
        assertFalse(CoderHealth.cooling("X", now = 1L))                    // request की गलती = आराम नहीं
    }

    @Test fun coolTimes() {
        assertEquals(60_000L, CoderHealth.coolMs(429, 0))
        assertEquals(20_000L, CoderHealth.coolMs(429, 5))
        assertEquals(300_000L, CoderHealth.coolMs(429, 9_999))
        assertEquals(30_000L, CoderHealth.coolMs(503, 0))
        assertEquals(30_000L, CoderHealth.coolMs(-1, 0))
        assertEquals(0L, CoderHealth.coolMs(400, 0))
    }

    @Test fun tooSmall_onlyWhenAnotherApiLeft() {
        // Groq free 8K TPM: सीमा ~20k अक्षर, request 120k = दूसरी API हो तो छोड़ो
        assertTrue(CoderHealth.tooSmall(room = 20_000, need = 120_000, lastResort = false))
        assertFalse(CoderHealth.tooSmall(room = 20_000, need = 120_000, lastResort = true))   // आख़िरी API = कटी ही सही
        assertFalse(CoderHealth.tooSmall(room = 20_000, need = 10_000, lastResort = false))   // छोटी request समा गई
        assertFalse(CoderHealth.tooSmall(room = 150_000, need = 400_000, lastResort = false)) // बड़ी सीमा = पहले जैसा छोटा करो
    }

    @Test fun waitOnlyForSameCompany() {
        assertTrue(CoderHealth.waitBeforeNext("gemini", "gemini"))
        assertFalse(CoderHealth.waitBeforeNext("gemini", "groq"))
        assertFalse(CoderHealth.waitBeforeNext(null, "groq"))
    }

    @Test fun wiredInCoderAi() {
        val s = File(listOf("src/main/java/com/dk/assistant/CoderAi.kt", "app/src/main/java/com/dk/assistant/CoderAi.kt").first { File(it).exists() }).readText()
        assertTrue(s.contains("CoderHealth.order(chain.map { it.selfLabel }, now0)"))
        assertTrue(s.contains("CoderHealth.ok(ai.selfLabel"))
        assertTrue(s.contains("CoderHealth.fail(ai.selfLabel"))
        assertTrue(s.contains("CoderHealth.tooSmall(room, CoderContext.total(msgs), lastResort)"))
        assertTrue(s.contains("busyUntil(m) > now"))
        assertTrue(s.contains("r.header(\"retry-after\")"))
    }

    // ---------------- Solo कोडर: कमज़ोर मॉडल के जवाब भी चलें

    private val src = "fun a() {\n    x()\n}\nfun b() {\n    y()\n}\n"

    @Test fun apply_findWithLineNumbers() {
        val r = Solo.apply(src, listOf("4| fun b() {\n5|     y()" to "4| fun b() {\n5|     z()"))
        assertEquals("fun a() {\n    x()\n}\nfun b() {\n    z()\n}\n", r.content)
    }

    @Test fun apply_findWithFences() {
        val r = Solo.apply(src, listOf("```kotlin\n    x()\n```" to "```kotlin\n    q()\n```"))
        assertEquals("fun a() {\n    q()\n}\nfun b() {\n    y()\n}\n", r.content)
    }

    @Test fun apply_realMissStillFails() {
        val r = Solo.apply(src, listOf("7| nothing()" to "x"))
        assertNull(r.content)
        assertTrue(r.errors.single().contains("नहीं मिला"))
        assertNull(Solo.cleanFind("plain()", "x"))
    }

    @Test fun parse_markdownDecoratedCommands() {
        val acts = Solo.parse("**READ:** src/A.kt\n- GREP: fun go\n1. LIST: app/src\n`CHECK`")
        assertEquals(Solo.Act.Read("src/A.kt"), acts[0])
        assertEquals(Solo.Act.Grep("fun go"), acts[1])
        assertEquals(Solo.Act.ListFiles("app/src"), acts[2])
        assertEquals(Solo.Act.Check, acts[3])
        assertTrue(Solo.parse("मैं अभी सोच रहा हूँ").isEmpty())
        assertTrue(Solo.parse("- यह एक आम लाइन है").isEmpty())
    }

    @Test fun parse_fencedFileContent() {
        val w = Solo.parse("FILE: src/N.kt\n<<<FILE\n```kotlin\npackage x\n```\nFILE>>>").single() as Solo.Act.Write
        assertEquals("package x", w.content)
        val md = Solo.parse("FILE: README.md\n<<<FILE\n```\ncode\n```\nFILE>>>").single() as Solo.Act.Write
        assertEquals("```\ncode\n```", md.content)                      // markdown फ़ाइल वैसी ही
    }

    @Test fun parse_doneSummaryUntouched() {
        val d = Solo.parse("DONE: Fix it\n- बदली फ़ाइलें: A.kt\n1. CHECK साफ़").single() as Solo.Act.Done
        assertTrue(d.summary.contains("बदली फ़ाइलें"))
    }

    @Test fun grep_ignoreCaseWhenNoExactHit() {
        val ws = mapOf("app/S.kt" to "class SettingsScreen\n")
        assertEquals(listOf("app/S.kt:1: class SettingsScreen"), Solo.grep(ws, "settingsscreen"))
        assertEquals(1, Solo.grep(ws, "Settings").size)
    }
}
