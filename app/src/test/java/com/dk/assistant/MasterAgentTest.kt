package com.dk.assistant

import com.dk.assistant.agent.master.ActionLog
import com.dk.assistant.agent.master.MasterRules
import com.dk.assistant.agent.master.MasterRules.Risk
import com.dk.assistant.agent.master.MasterRules.Specialist
import com.dk.assistant.plus.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 24 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 1): केंद्रीय DK मास्टर */
class MasterAgentTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun classify_picksSpecialist() {
        assertEquals(Specialist.BOOKING, MasterRules.classify("कल मुंबई की ट्रेन टिकट बुक करो"))
        assertEquals(Specialist.BOOKING, MasterRules.classify("Ola cab book karo"))
        assertEquals(Specialist.SHOPPING, MasterRules.classify("Flipkart पर सस्ता जूता खरीदना है"))
        assertEquals(Specialist.FORM, MasterRules.classify("यह फॉर्म भर दो"))
        assertEquals(Specialist.SOCIAL, MasterRules.classify("Facebook खोलकर पोस्ट देखो"))
        assertEquals(Specialist.BROWSER, MasterRules.classify("यह वेबसाइट खोलो"))
        assertEquals(Specialist.PHONE, MasterRules.classify("वाई-फाई चालू करो"))
    }

    @Test fun facebook_isNotBooking() {
        assertFalse(MasterRules.classify("facebook kholo") == Specialist.BOOKING)
        assertFalse(MasterRules.classify("फेसबुक खोलो") == Specialist.BOOKING)
    }

    @Test fun risks_found() {
        assertTrue(Risk.MONEY in MasterRules.risks("राहुल को 500 रुपये भेजो"))
        assertTrue(Risk.OTP in MasterRules.risks("OTP डाल दो"))
        assertTrue(Risk.PASSWORD in MasterRules.risks("password भर दो"))
        assertTrue(Risk.IDENTITY in MasterRules.risks("आधार नंबर भर दो"))
        assertTrue(Risk.IRREVERSIBLE in MasterRules.risks("सारी फ़ाइलें डिलीट करो"))
        assertTrue(MasterRules.risks("मौसम बताओ").isEmpty())
    }

    @Test fun booking_neverPays_finalStepsConfirm() {
        val p = MasterRules.plan("होटल बुक करो")
        assertTrue(p.needsConfirm)
        assertTrue(p.steps.last().confirm)
        assertTrue(p.steps.last().hi.contains("भुगतान आप खुद"))
        assertTrue(MasterRules.agentNote(p).contains("never make the online payment"))
    }

    @Test fun form_neverInvents() {
        val p = MasterRules.plan("कॉलेज का फॉर्म भर दो")
        assertTrue(MasterRules.agentNote(p).contains("never invent personal details"))
        assertTrue(p.steps.last().confirm)
    }

    @Test fun risky_phoneTask_lastStepConfirms() {
        val p = MasterRules.plan("सारी फोटो डिलीट करो")
        assertEquals(Specialist.PHONE, p.specialist)
        assertTrue(p.steps.last().confirm)
        assertFalse(MasterRules.plan("टॉर्च जलाओ").needsConfirm)
    }

    @Test fun planText_hindi_withStop() {
        val t = MasterRules.planText(MasterRules.plan("Amazon से फोन खरीदो"))
        assertTrue(t.startsWith("प्लान (शॉपिंग)"))
        assertTrue(t.contains("[पुष्टि]"))
        assertTrue(t.contains("रुको"))
    }

    @Test fun sharesSecret_onlyWhenSending() {
        assertTrue(MasterRules.sharesSecret("मेरा OTP राहुल को भेज दो"))
        assertTrue(MasterRules.sharesSecret("send my password to him"))
        assertFalse(MasterRules.sharesSecret("OTP वाला मैसेज पढ़ो"))
        assertFalse(MasterRules.sharesSecret("राहुल को hi भेज दो"))
    }

    @Test fun stopWords() {
        assertTrue(MasterRules.isStop("रुको"))
        assertTrue(MasterRules.isStop("DK रुक जाओ"))
        assertTrue(MasterRules.isStop("stop"))
        assertFalse(MasterRules.isStop("गाना चलाओ"))
    }

    @Test fun log_masksDigits_andKeepsNewestFirst() {
        assertFalse(ActionLog.Rules.mask("OTP 482913 डाल दो").contains("482913"))
        assertTrue(ActionLog.Rules.mask("2 फोटो").contains("2 फोटो"))
        val l = ActionLog.Rules.add(listOf("b", "a"), "c", max = 2)
        assertEquals(listOf("c", "b"), l)
    }

    @Test fun flag_offByDefault_andShownInSettings() {
        val f = FeatureFlags.byKey(FeatureFlags.MASTER_AGENT)!!
        assertFalse(f.default)
        assertTrue(FeatureFlags.ready(FeatureFlags.MASTER_AGENT))
        assertTrue(src("SettingsBrain.kt").contains("masterAgentCard()"))
    }

    @Test fun wired_beforeAgentRun_oldPathsUntouched() {
        val q = src("GeminiLiveQueue.kt")
        val i = q.indexOf("DkMaster.before(svc, req)")
        assertTrue(i > 0)
        assertTrue(i < q.indexOf("val job = agent.run(req, speak = false, note = note)"))
        // सीधे फ़ोन वाले काम (LiveFast) मास्टर से पहले ही - वैसे ही
        assertTrue(q.indexOf("NativeActionEngine.exec(svc, q, \"Live phone_task\")") < i)
        // मास्टर बंद = कुछ नहीं बदलता
        assertTrue(src("agent/master/DkMaster.kt").contains("if (!on(c)) return null to null"))
    }
}
