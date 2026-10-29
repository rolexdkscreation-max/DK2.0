package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WaCallTest {
    private val wake = listOf("डीके", "दीके", "डी के", "dk", "d k")
    private fun p(t: String) = WaCall.parse(t.lowercase())

    @Test fun voiceCall_nameClean() {
        assertEquals(WaCall.Cmd("राहुल", false), p("व्हाट्सएप पर राहुल को कॉल करो"))
        assertEquals(WaCall.Cmd("राहुल", false), p("राहुल को व्हाट्सएप पर कॉल लगाओ"))
        assertEquals(WaCall.Cmd("राहुल", false), p("राहुल को व्हाट्सऐप कॉल कर दो"))
        assertEquals(WaCall.Cmd("मम्मी", false), p("मम्मी को वॉट्सऐप पर फोन लगाओ"))
        assertEquals(WaCall.Cmd("rahul", false), p("WhatsApp call Rahul"))
        assertEquals(WaCall.Cmd("rahul sharma", false), p("call Rahul Sharma on WhatsApp"))
        assertEquals(WaCall.Cmd("राहुल", false), p("व्हाट्सएप से राहुल को वॉइस कॉल करो"))
    }

    @Test fun videoCall() {
        assertEquals(WaCall.Cmd("राहुल", true), p("राहुल को व्हाट्सएप वीडियो कॉल लगाओ"))
        assertEquals(WaCall.Cmd("पापा", true), p("व्हाट्सएप पर पापा को वीडियो कॉल करो"))
        assertEquals(WaCall.Cmd("rahul", true), p("WhatsApp video call Rahul"))
    }

    @Test fun notThis() {
        assertNull(p("राहुल को कॉल करो"))                         // आम SIM कॉल
        assertNull(p("व्हाट्सएप खोलो"))
        assertNull(p("व्हाट्सएप पर राहुल को मैसेज भेजो कि कॉल करे"))
        assertNull(p("व्हाट्सएप कॉल उठाओ"))
        assertNull(p("व्हाट्सएप कॉल काट दो"))
        assertNull(p("किसका व्हाट्सएप कॉल आ रहा है"))
        assertNull(p("व्हाट्सएप कॉल करो"))                        // नाम नहीं -> AI पूछे
    }

    @Test fun router_waCall_notSimCall() {
        val r = LocalRouter.route("डीके व्हाट्सएप पर राहुल को कॉल करो", wake)!!
        assertEquals("wa_call", r.action.optString("type"))
        assertEquals("राहुल", r.action.optString("name"))
        assertFalse(r.action.optBoolean("video"))
        val v = LocalRouter.route("राहुल को व्हाट्सएप वीडियो कॉल लगाओ", wake)!!
        assertEquals("wa_call", v.action.optString("type")); assertEquals(true, v.action.optBoolean("video"))
        // आम कॉल वैसी ही
        assertEquals("call_contact", LocalRouter.route("राहुल को कॉल करो", wake)!!.action.optString("type"))
    }

    @Test fun json_roundTrip() {
        val c = WaCall.Cmd("राहुल", true)
        assertEquals(c, WaCall.fromJson(WaCall.toJson(c)))
    }

    @Test fun live_tool_hasCallActions_andResultHonest() {
        val src = listOf(java.io.File("src/main/java/com/dk/assistant/GeminiLive.kt"), java.io.File("app/src/main/java/com/dk/assistant/GeminiLive.kt")).first { it.exists() }.readText()
        assert(src.contains(".put(\"voice_call\").put(\"video_call\")"))
        val q = QuickCommands.Quick(WaCall.toJson(WaCall.Cmd("राहुल", false)), "")
        assert(LiveFast.quickResult(q, "ASK: WhatsApp पर किसे कॉल करूँ - Rahul या Rahul Sharma?", false).startsWith("NEEDS USER CHOICE"))
        assert(LiveFast.quickResult(q, "ERROR_NO_CONTACT: \"राहुल\" नाम का कोई WhatsApp पर नहीं मिला।", false).startsWith("FAILED"))
        assert(LiveFast.quickResult(q, "CALLING: Rahul को WhatsApp कॉल लगा रहा हूँ।", false).startsWith("DONE"))
    }
}
