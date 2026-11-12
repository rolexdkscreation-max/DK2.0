package com.dk.assistant.agent

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.FormFill
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): किसी भी ऐप में फ़ॉर्म - पूरा पढ़ो, खानों के नाम पहचानो, एक-एक पूछकर उसी ऐप में भरो */
class FormFillTest {
    private var n = 0
    private fun t(text: String, top: Int, left: Int = 40, right: Int = 1000, h: Int = 50) = UiElement(n++, text = text, cls = "TextView", bounds = Box(left, top, right, top + h))
    private fun ed(text: String, top: Int, desc: String = "", left: Int = 40) = UiElement(n++, text = text, desc = desc, cls = "EditText", editable = true, clickable = true, bounds = Box(left, top, 1000, top + 120))

    private fun form(): ScreenObservation {
        n = 0
        return ScreenObservation(1, "com.android.chrome", listOf(
            t("नौकरी का आवेदन फ़ॉर्म", 100),
            t("पूरा नाम *", 200), ed("", 260),
            t("मोबाइल नंबर", 420), ed("98765", 480),
            ed("ईमेल", 650),                                                // सिर्फ़ hint, कोई नाम ऊपर नहीं
            t("Password", 820), ed("", 880),
            UiElement(n++, text = "मैं शर्तें मानता हूँ", cls = "CheckBox", clickable = true, bounds = Box(40, 1050, 1000, 1110)),
            UiElement(n++, text = "राज्य चुनें", viewId = "com.x:id/state_spinner", cls = "Spinner", clickable = true, bounds = Box(40, 1150, 1000, 1250)),
            UiElement(n++, text = "Submit", cls = "Button", clickable = true, bounds = Box(40, 1400, 1000, 1500)),
        ))
    }

    @Test fun fields_withLabels_kinds_values() {
        val f = FormFill.fields(form())
        assertEquals(listOf("पूरा नाम *", "मोबाइल नंबर", "ईमेल", "Password", "मैं शर्तें मानता हूँ", "राज्य चुनें"), f.map { it.label })
        assertEquals("", f[0].value); assertEquals("98765", f[1].value); assertEquals("", f[2].value)
        assertEquals(FormFill.Kind.CHECK, f[4].kind); assertEquals(FormFill.Kind.CHOICE, f[5].kind)
        assertTrue(f[3].secret); assertFalse(f[0].secret)
    }

    @Test fun match_byNumber_ordinal_name() {
        val f = FormFill.fields(form())
        assertEquals("मोबाइल नंबर", FormFill.match(f, "2")?.label)
        assertEquals("पूरा नाम *", FormFill.match(f, "पहला")?.label)
        assertEquals("ईमेल", FormFill.match(f, "ईमेल")?.label)
        assertEquals("मोबाइल नंबर", FormFill.match(f, "मोबाइल")?.label)
        assertEquals("पूरा नाम *", FormFill.match(f, "नाम")?.label)
        assertNull(FormFill.match(f, "xyz"))
    }

    @Test fun mergeAcrossScrolls_noDuplicates() {
        val a = FormFill.fields(form())
        val m = FormFill.merge(a, a.take(2).map { it.copy(value = "भरा") })
        assertEquals(a.size, m.size)
        assertEquals("भरा", m[0].value)
        assertEquals(listOf("a", "b", "c"), FormFill.mergeTexts(listOf("a", "b"), listOf("b", " c ", "")))
    }

    @Test fun submit_and_yes() {
        assertEquals("Submit", FormFill.submitButton(form())?.text)
        assertEquals(true, FormFill.yes("हाँ टिक करो")); assertEquals(false, FormFill.yes("नहीं")); assertNull(FormFill.yes("शायद"))
    }

    @Test fun describe_showsWholeScreen_asksOneByOne_neverGuess() {
        val o = form()
        val s = FormFill.describe("Chrome", FormFill.fields(o), o.texts())
        assertTrue(s.contains("नौकरी का आवेदन फ़ॉर्म"))                     // पूरी स्क्रीन का लिखा
        assertTrue(s.contains("1. पूरा नाम *") && s.contains("= \"98765\""))
        assertTrue(s.contains("no back") && s.contains("one question at a time") && s.contains("Never invent"))
        assertTrue(s.contains("निजी"))
    }

    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun agent_staysInApp_neverBack_toolWired() {
        val a = src("agent/android/FormFillAgent.kt")
        assertFalse(a.contains(".back()") || a.contains("performGlobalAction"))
        assertTrue(a.contains("if (now == null || now.pkg != pkg)"))
        assertTrue(a.contains("Mutex"))
        assertTrue(src("GeminiLiveSetup.kt").contains("put(\"name\", \"form_fill\")"))
        assertTrue(src("GeminiLiveTools.kt").contains("\"form_fill\" -> com.dk.assistant.agent.android.FormFillAgent.liveTool(svc, args)"))
        assertTrue("form_fill" in com.dk.assistant.LeakedCall.TOOLS)
    }
}
