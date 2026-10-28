package com.dk.assistant.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplateCommandsTest {
    private val all = TemplateCommands.BUILTIN
    private fun m(s: String) = TemplateCommands.match(s, all)
    private fun id(s: String) = m(s)?.t?.id

    @Test fun whatsappMessageSlots() {
        val x = m("व्हाट्सएप ओपन करो धीरज कुमार को हेलो मैसेज भेज दो")!!
        assertEquals("wa_send", x.t.id); assertEquals("धीरज कुमार", x["a"]); assertEquals("हेलो", x["b"])
        val y = m("राहुल को व्हाट्सएप पर मैसेज भेजो कि मैं 5 बजे आऊँगा")!!
        assertEquals("राहुल", y["a"]); assertEquals("मैं 5 बजे आऊँगा", y["b"])
        assertEquals("wa_send", id("DK, whatsapp open karo Mummy को good morning message bhej do"))
    }

    @Test fun openAndCall() {
        assertEquals("wa_open", id("व्हाट्सएप खोलो"))
        assertEquals("wa_open", id("व्हाट्सऐप ओपन करो"))
        val c = m("मम्मी को कॉल करो")!!; assertEquals("call", c.t.id); assertEquals("मम्मी", c["a"])
        assertNull(m("व्हाट्सएप पर राहुल को कॉल करो"))
    }

    @Test fun socialAndYoutube() {
        assertEquals("ig_open", id("इंस्टाग्राम खोलो"))
        val ig = m("इंस्टाग्राम पर virat kohli सर्च करो")!!; assertEquals("ig_search", ig.t.id); assertEquals("virat kohli", ig["a"])
        assertEquals("fb_open", id("फ़ेसबुक खोलो"))
        assertEquals("fb_search", id("फेसबुक पर नासिक न्यूज़ सर्च करो"))
        val yt = m("यूट्यूब पर अरिजीत सिंह के गाने चलाओ")!!; assertEquals("yt_play", yt.t.id); assertEquals("अरिजीत सिंह के गाने", yt["a"])
        assertEquals("yt_search", id("यूट्यूब पर कुकिंग वीडियो सर्च करो"))
    }

    @Test fun maps() {
        val g = m("मैप खोलो नासिक रोड स्टेशन जाना है")!!; assertEquals("navigate", g.t.action); assertEquals("नासिक रोड स्टेशन", g["a"])
        assertEquals("पुणे", m("पुणे का रास्ता बताओ")!!["a"])
        val d = m("मुंबई कितनी दूर है")!!; assertEquals("map_dist", d.t.id); assertEquals("मुंबई", d["a"])
    }

    @Test fun alarmTimer() {
        val a = m("सुबह 7 बजे का अलार्म लगाओ")!!; assertEquals("alarm", a.t.id)
        assertEquals(TemplateTime.Clock(7, 0), TemplateTime.parseClock(a["a"], 22, 0))
        assertEquals(TemplateTime.Clock(18, 30), TemplateTime.parseClock("शाम 6:30", 9, 0))
        assertEquals(TemplateTime.Clock(19, 30), TemplateTime.parseClock("शाम साढ़े सात", 9, 0))
        assertEquals(TemplateTime.Clock(14, 0), TemplateTime.parseClock("दोपहर 2", 9, 0))
        assertEquals(TemplateTime.Clock(22, 0), TemplateTime.parseClock("रात 10", 9, 0))
        assertEquals(TemplateTime.Clock(17, 0), TemplateTime.parseClock("5", 14, 0))   // अभी 2 बजे -> शाम 5
        assertEquals(TemplateTime.Clock(5, 0), TemplateTime.parseClock("5", 1, 0))     // अभी रात 1 -> सुबह 5
        assertEquals(TemplateTime.Clock(8, 45), TemplateTime.parseClock("सुबह पौने नौ", 22, 0))
        assertEquals("सुबह 7:00 बजे", TemplateTime.Clock(7, 0).spoken())
        val t = m("10 मिनट का टाइमर लगाओ")!!; assertEquals("timer", t.t.id); assertEquals(10, TemplateTime.minutes(t["a"]))
        assertEquals(5, TemplateTime.minutes("पांच"))
        assertNull(m("बहुत मिनट का टाइमर लगाओ"))
    }

    @Test fun shopping() {
        val s = m("फ्लिपकार्ट पर रेड टी शर्ट सर्च करो")!!; assertEquals("shop_search", s.t.id); assertEquals("flipkart", s["app"]); assertEquals("रेड टी शर्ट", s["q"])
        assertEquals("zomato", m("ज़ोमैटो पर पनीर टिक्का सर्च करो")!!["app"])
        assertEquals("instamart", m("इंस्टामार्ट पर दूध सर्च करो")!!["app"])
        assertEquals("cart_add_this", id("इसे कार्ट में जोड़ो"))
        val c = m("अमूल दूध को कार्ट में डालो")!!; assertEquals("cart_add", c.t.id); assertEquals("अमूल दूध", c["a"])
        assertEquals("cart_open", id("कार्ट खोलो"))
        assertEquals("blinkit", m("ब्लिंकिट का कार्ट खोलो")!!["app"])
    }

    @Test fun deviceAndCalls() {
        assertEquals("scroll_down", id("नीचे स्क्रॉल करो")); assertEquals("scroll_up", id("ऊपर स्क्रॉल करो"))
        assertEquals("back", id("पीछे जाओ")); assertEquals("back", id("बैक"))
        assertEquals("home", id("होम पर आओ"))
        assertEquals("vol_up", id("आवाज़ बढ़ाओ")); assertEquals("vol_down", id("आवाज कम करो"))
        val v = m("आवाज़ 50 करो")!!; assertEquals("vol_set", v.t.id); assertEquals("50", v["a"])
        assertEquals("call_answer", id("उठाओ")); assertEquals("call_answer", id("कॉल उठाओ"))
        assertEquals("call_end", id("काट दो")); assertEquals("call_end", id("कट करो"))
    }

    @Test fun noMatchGoesToAi() {
        assertNull(m("आज मौसम कैसा है"))
        assertNull(m("मुझे एक कहानी सुनाओ"))
        assertNull(m("व्हाट्सएप क्या है"))
    }

    @Test fun switchedOffAndUserTemplate() {
        val off = all.map { if (it.id == "wa_open") it.copy(on = false) else it }
        assertNull(TemplateCommands.match("व्हाट्सएप खोलो", off))
        val mine = TemplateCommands.Template("u1", "गाना", "मेरा गाना ___ बजाओ", listOf(TemplateCommands.userPattern("मेरा गाना ___ बजाओ")),
            listOf("गाना"), "app_search", "spotify", builtin = false)
        val x = TemplateCommands.match("मेरा गाना तुम ही हो बजाओ", listOf(mine) + all)!!
        assertEquals("u1", x.t.id); assertEquals("तुम ही हो", x["a"])
    }

    /** हर अंदर वाला साँचा एक बार: उदाहरण की खाली जगह भरकर -> वही साँचा मिले */
    @Test fun everyBuiltinRunsOnce() {
        val fill = mapOf(
            "wa_send" to "व्हाट्सएप ओपन करो धीरज कुमार को हेलो मैसेज भेज दो", "wa_open" to "व्हाट्सएप खोलो", "call" to "पापा को कॉल करो",
            "ig_search" to "इंस्टाग्राम पर नासिक सर्च करो", "ig_open" to "इंस्टाग्राम खोलो", "fb_search" to "फेसबुक पर नासिक सर्च करो",
            "fb_open" to "फेसबुक खोलो", "yt_play" to "यूट्यूब पर भजन चलाओ", "yt_search" to "यूट्यूब पर भजन सर्च करो",
            "map_go" to "मैप खोलो पंचवटी जाना है", "map_dist" to "पंचवटी कितनी दूर है", "alarm" to "सुबह 6 बजे का अलार्म लगाओ",
            "timer" to "5 मिनट का टाइमर लगाओ", "shop_search" to "मीशो पर साड़ी सर्च करो", "cart_add_this" to "इसे कार्ट में जोड़ो",
            "cart_add" to "आटा को कार्ट में डालो", "cart_open" to "कार्ट खोलो", "scroll_down" to "नीचे स्क्रॉल करो", "scroll_up" to "ऊपर स्क्रॉल करो",
            "back" to "पीछे जाओ", "home" to "होम पर आओ", "vol_up" to "आवाज़ बढ़ाओ", "vol_down" to "आवाज़ कम करो", "vol_set" to "आवाज़ 30 करो",
            "call_answer" to "कॉल उठाओ", "call_end" to "कट करो"
        )
        assertEquals(all.map { it.id }.toSet(), fill.keys)
        for ((id, text) in fill) assertEquals(text, id, m(text)?.t?.id)
    }
}
