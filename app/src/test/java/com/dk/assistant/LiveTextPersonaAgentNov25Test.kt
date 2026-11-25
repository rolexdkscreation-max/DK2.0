package com.dk.assistant

import com.dk.assistant.agent.core.OutsideText
import com.dk.assistant.agent.master.ShopRules
import com.dk.assistant.agent.master.ShopRules.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 25 नवंबर 2026 (मालिक): बोली बात तुरंत लिखी दिखे; बातचीत का अनुवाद नहीं; हँसना / रूठना / गर्लफ्रेंड में हद से ज़्यादा प्यार;
 * गैलरी = हर बार एक बार पूछो; शॉपिंग में खुद कहा = तुरंत (कार्ट / आगे / कैश ऑन डिलीवरी); अलार्म न रुका = सच बताओ; OTP / पासवर्ड कभी नहीं.
 */
class LiveTextPersonaAgentNov25Test {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun userSpeech_showsAtOnce_evenWhenCaptionOn() {
        // caption बंद = हमेशा
        assertTrue(LiveUserText.show(false, 5_000, 1_000, 0))
        // caption चालू पर इस बार कुछ नहीं लिखा (आख़िरी लिखावट बोलना शुरू होने से पहले की) = Live का text तुरंत
        assertTrue(LiveUserText.show(true, 900, 1_000, 0))
        // Live ने ही पिछली बार लिखा = वही आगे लिखता रहे
        assertTrue(LiveUserText.show(true, 1_200, 1_000, 1_200))
        // caption ने इस बार लिख दिया = caption ही (आगे-पीछे नहीं)
        assertFalse(LiveUserText.show(true, 1_300, 1_000, 1_200))
        val server = src("GeminiLiveServer.kt")
        assertTrue(server.contains("showLiveUser(\"\$u…\")"))
        assertFalse(server.contains("if (!captionOn()) DKState.user(\"\$u…\")"))
    }

    @Test fun conversation_neverTranslated() {
        val pool = listOf("हाँ बॉस, मैं यहीं हूँ। बताइए क्या करना है?", "आज मौसम कैसा है", "")
        assertTrue(ConvoText.isConvo("हाँ बॉस, मैं यहीं हूँ। बताइए क्या करना है?", pool))
        assertTrue(ConvoText.isConvo("DK: हाँ बॉस, मैं यहीं हूँ। बताइए क्या करना है?", pool))
        assertTrue(ConvoText.isConvo("आप: आज मौसम कैसा है…", pool))
        assertTrue(ConvoText.isConvo("हाँ बॉस, मैं यहीं हूँ।", pool))
        // ऐप के छोटे बटन / लेबल का अनुवाद चलता रहे
        assertFalse(ConvoText.isConvo("सेटिंग", pool))
        assertFalse(ConvoText.isConvo("आवाज़", listOf("आवाज़ थोड़ी तेज़ कर दो बॉस")))
        assertTrue(src("UiLang.kt").contains("ConvoText.isConvoNow(t)"))
    }

    @Test fun persona_laughSulkLove() {
        val f = DkPersona.finalFor(DkPersona.P.FRIENDLY, "धीरज")
        assertTrue(f.contains("real soft laugh"))
        assertTrue(f.contains("रूठना"))
        assertTrue(f.contains("never guilt, never refuse a task"))
        val g = DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "धीरज")
        assertTrue(g.contains("beyond every limit"))
        assertTrue(g.contains("overflowing care"))
        assertFalse(DkPersona.finalFor(DkPersona.P.PRO, "धीरज").contains("beyond every limit"))
        assertTrue(DkPersona.TRUST_RULES.contains("did NOT happen"))
        assertTrue(DkPersona.TRUST_RULES.contains("OTP, password, PIN"))
        assertTrue(DkPersona.TRUST_RULES.contains("you can erase"))
        assertTrue(src("DkPersona.kt").contains("+ TRUST_RULES +"))
    }

    @Test fun gallery_alwaysAsksOnce_screenForShopping() {
        assertFalse(PrivacyGate.explicitOk(PrivacyGate.Kind.GALLERY, "गैलरी देखो"))
        assertTrue(PrivacyGate.explicitOk(PrivacyGate.Kind.CHAT, "WhatsApp पे जाके चेक करो"))
        assertTrue(PrivacyGate.explicitOk(PrivacyGate.Kind.SCREEN, "तुरंत ऐड करो, कार्ट में ऐड करो"))
        assertTrue(PrivacyGate.explicitOk(PrivacyGate.Kind.SCREEN, "कैश ऑन डिलीवरी कर दो"))
        assertFalse(PrivacyGate.explicitOk(PrivacyGate.Kind.SCREEN, "कार्ट में मत डालो"))
        assertTrue(src("tools/ToolAgent.kt").contains("PrivacyGate.explicitOk(pk, goal)"))
    }

    @Test fun shopping_ownerSaid_noExtraAsk_payNever() {
        val pay: (String) -> Boolean = { OutsideText.isPayLabel(it) }
        assertTrue(ShopRules.cartAsked("तुरंत ऐड करो, कार्ड में ऐड करो"))
        assertTrue(ShopRules.codAsked("कैश ऑन डिलीवरी कर दो"))
        assertTrue(ShopRules.buyAsked("आगे प्रोसेस करो"))
        assertFalse(ShopRules.buyAsked("काली शर्ट दिखाओ"))
        // COD कहा = COD विकल्प ऑर्डर का कदम; न कहा = पहले जैसा भुगतान (कभी नहीं)
        assertEquals(Tap.BUY, ShopRules.tap("Cash on Delivery", cod = true))
        assertEquals(Tap.PAY, ShopRules.tap("Cash on Delivery"))
        assertEquals(Tap.PAY, ShopRules.tap("UPI ID डालें", cod = true))
        assertTrue(ShopRules.ownerSaid(listOf("Place Order"), "कैश ऑन डिलीवरी कर दो", pay))
        assertTrue(ShopRules.ownerSaid(listOf("Proceed to Checkout"), "आगे प्रोसेस करो", pay))
        assertTrue(ShopRules.ownerSaid(listOf("Add to cart"), "कार्ट में ऐड करो", pay))
        assertFalse(ShopRules.ownerSaid(listOf("Place Order"), "यह शर्ट दिखाओ", pay))
        assertFalse(ShopRules.ownerSaid(listOf("Pay ₹499"), "कैश ऑन डिलीवरी कर दो", pay))
        assertFalse(ShopRules.ownerSaid(listOf("Proceed to Pay"), "आगे प्रोसेस करो", pay))
        assertFalse(ShopRules.ownerSaid(emptyList(), "आगे प्रोसेस करो", pay))
        val runner = src("AgentRunner.kt")
        assertTrue(runner.contains("ShopRules.ownerSaid("))
        assertTrue(runner.contains("!ownerSaid && com.dk.assistant.agent.core.OutsideText.needsCard(k, goal)"))
        assertTrue(ShopRules.AGENT_NOTE.contains("never tap Pay / UPI"))
    }

    @Test fun alarm_neverFalselyDone() {
        val c = AlarmOff.Cmd(false, AlarmOff.Mode.NEXT)
        assertEquals(AlarmOff.STILL, AlarmOff.verdict(c, true, true, true))
        assertTrue(AlarmOff.verdict(c, true, true, false).startsWith("OK"))
        assertTrue(AlarmOff.verdict(c, true, null, null).startsWith("OK"))
        assertEquals(AlarmOff.FAIL, AlarmOff.verdict(c, false, true, true))
        assertTrue(AlarmOff.STILL.contains("बंद नहीं हुआ"))
    }

    @Test fun anythingTyped_canBeErased() {
        assertTrue(src("BrainManager.kt").contains("ERASE / clear the text in box N"))
    }
}
