package com.dk.assistant.agent

import com.dk.assistant.AssistantBrand
import com.dk.assistant.TalkStyle
import com.dk.assistant.agent.social.WaRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaRouteAndIdentityTest {
    @Test fun whatsappSendGoesToWhatsappTask() {
        assertTrue(WaRoute.isWhatsAppSend("व्हाट्सएप पर राहुल को मैसेज भेजो कि मैं आ रहा हूँ"))
        assertTrue(WaRoute.isWhatsAppSend("send hi to mom on WhatsApp"))
        assertTrue(WaRoute.isWhatsAppSend("राहुल को WhatsApp पर बोल दो कि कल मिलते हैं"))
        assertFalse(WaRoute.isWhatsAppSend("व्हाट्सएप खोलो"))
        assertFalse(WaRoute.isWhatsAppSend("व्हाट्सएप पर क्या मैसेज आया पढ़ो"))
        assertFalse(WaRoute.isWhatsAppSend("राहुल को व्हाट्सएप कॉल करो"))
        assertFalse(WaRoute.isWhatsAppSend("माँ को मैसेज भेजो"))     // SMS वाला रास्ता वैसे ही
        assertTrue(WaRoute.redirect("x").contains("whatsapp_task"))
        assertTrue(WaRoute.redirect("x").contains("Nothing was sent"))
    }

    @Test fun identityAlwaysOwnerNeverGoogle() {
        val r = AssistantBrand.IDENTITY_RULE
        assertTrue(r.contains(AssistantBrand.OWNER_NAME))
        assertTrue(r.contains(AssistantBrand.CREATOR_ANSWER))
        for (q in listOf("तुम कैसे काम करते हो", "तुम्हारा दिमाग कैसे काम करता है", "तुम किसके लिए काम करते हो", "तुम्हें किसने सिखाया"))
            assertTrue(q, r.contains(q))
        assertTrue(r.contains("NEVER say Google"))
    }

    @Test fun voiceHasEmotionRulesButStaysHonest() {
        assertTrue(TalkStyle.EMOTION.contains("emotion", ignoreCase = true))
        assertTrue(TalkStyle.NATURAL.contains("NO FALSE DONE"))
    }
}
