package com.dk.assistant.agent.core

/**
 * 16 नवंबर 2026 (ऑडिट A1, मालिक: "DK Live WhatsApp send aur delete par confirm card lagao").
 *
 * जड़: DK लाइव के `whatsapp_task` में भेजना / डिलीट सीधे चलता था - ऐप का अपना "हाँ / ना" कार्ड ([com.dk.assistant.plus.ConfirmGate])
 * सिर्फ़ notification reply पर था. DK ने अभी-अभी किसी का मैसेज / नोटिफ़िकेशन पढ़ा हो ("इसे सबको भेज दो") तो Gemini बिना पूछे
 * भेज सकता था. अब (Pure, JVM test: LiveWaGateTest):
 *  - डिलीट = हमेशा कार्ड (कार्ड [com.dk.assistant.agent.android.WaDeleteRunner] खुद दिखाता है - मिटने वाला असली मैसेज दिखाकर,
 *    ताकि DK लाइव के दोनों रास्ते और चैट वाला रास्ता, तीनों ढके रहें).
 *  - भेजना = कार्ड तब, जब (1) अभी-अभी बाहर की लिखावट (नोटिफ़िकेशन / WhatsApp स्क्रीन) पढ़ी गई, या
 *    (2) मालिक की अपनी आखिरी बात में भेजने को कहा ही नहीं गया ([OutsideText.needsCard]).
 *  - WhatsApp की चैट / स्क्रीन पढ़ना = बाहर की लिखावट ([readsOthers]) - उसके बाद 3 मिनट तक भेजने पर कार्ड.
 */
object LiveWaGate {
    /** दूसरे को कुछ जाता है (मैसेज, लोकेशन, फ़ोटो, नंबर, जवाब) */
    val SEND_ACTS = setOf(
        "send_message", "send", "message", "send_contact", "send_location", "current_location", "send_live_location",
        "send_camera_photo", "camera_photo", "smart_reply", "reply_for_me", "reply", "chat_reply",
    )
    val DELETE_ACTS = setOf("delete_message", "delete", "unsend", "delete_last")
    /** दूसरों का लिखा पढ़ना */
    val READ_ACTS = setOf("read_unread", "unread", "read", "screen_chats", "chats", "screen", "open_chat", "read_chat_screen", "chat_mode_on",
        "chatting_mode_on", "whatsapp_mode_on")

    fun kind(act: String): OutsideText.Kind = when (act.trim().lowercase()) {
        in DELETE_ACTS -> OutsideText.Kind.DELETE
        in SEND_ACTS -> OutsideText.Kind.SEND
        else -> OutsideText.Kind.NONE
    }

    fun readsOthers(act: String) = act.trim().lowercase() in READ_ACTS

    /**
     * DK लाइव के भेजने से पहले मालिक से (आवाज़ में) पूछना है? (डिलीट वाला पूछना WaDeleteRunner में)
     * 18 नवंबर 2026 (मालिक: "मालिक ने खुद बोला है तो तुरंत भेज दे; अनजान हो तो एक बार 'हाँ भेज दूँ?' पूछे"):
     * पहले अभी-अभी कोई नोटिफ़िकेशन / चैट पढ़ी हो तो मालिक के "भेज दो" कहने पर भी हर बार कार्ड खुलता था. अब सिर्फ़ तब पूछो
     * जब मालिक की अपनी हाल की बातों ([ownerSaid]) में भेजने को कहा ही न गया हो. [untrustedRecent] (अभी किसी और का लिखा पढ़ा) =
     * "मैसेज" शब्द काफ़ी नहीं, भेजने वाली क्रिया चाहिए ([OutsideText.ownerAskedToSend]) - "राहुल का मैसेज पढ़ो" पर बहका AI बिना पूछे न भेजे.
     */
    fun sendNeedsCard(act: String, ownerSaid: String, untrustedRecent: Boolean): Boolean =
        kind(act) == OutsideText.Kind.SEND && ownerNeedsAsk(ownerSaid, untrustedRecent)

    /** भेजने से पहले मालिक से पूछना है? (WhatsApp / नोटिफ़िकेशन जवाब / फ़ोन-एजेंट - सब एक नियम) */
    fun ownerNeedsAsk(ownerSaid: String, untrustedRecent: Boolean): Boolean =
        if (untrustedRecent) !OutsideText.ownerAskedToSend(ownerSaid) else OutsideText.needsCard(OutsideText.Kind.SEND, ownerSaid)

    /** आवाज़ वाले सवाल का शीर्षक */
    fun sendTitle(contact: String) = "WhatsApp पर ${contact.trim().ifBlank { "किसी" }} को भेजूँ?"

    /** सवाल में क्या जाएगा */
    fun sendDetail(act: String, message: String, untrustedRecent: Boolean): String = buildString {
        val m = message.trim()
        append(if (m.isNotEmpty()) "\"${m.take(300)}\"" else "(${act.trim().lowercase()})")
        if (untrustedRecent) append("\n\nDK ने अभी-अभी किसी और का लिखा (नोटिफ़िकेशन / चैट) पढ़ा है - पक्का आप ही भेजना चाहते हैं?")
    }

    fun deleteTitle(who: String) = "WhatsApp पर ${who.trim().ifBlank { "इस चैट" }} का मैसेज मिटाऊँ?"

    fun deleteDetail(text: String, forMe: Boolean): String =
        "\"${text.trim().take(300)}\"\n\n" + if (forMe) "सिर्फ़ आपके फ़ोन से मिटेगा।" else "सबके लिए मिटेगा - वापस नहीं आएगा।"
}
