package com.dk.assistant.agent.social

import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement

/**
 * नवंबर 2026 (मालिक): "WhatsApp पर गलती से जो मैसेज चला गया, उसे डिलीट कर दो" = फटाक से "सबके लिए डिलीट".
 *
 * जड़: ऐसी बात में "भेज" + "मैसेज" + "WhatsApp" होता है, तो WaRoute इसे नया मैसेज भेजना समझ लेता था
 * (whatsapp_task send_message) - डिलीट कभी होता ही नहीं था. अब पहले यह पहचान, फिर सीधे स्क्रीन पर:
 * चैट खोलो -> आपका आख़िरी भेजा मैसेज (दाईं ओर / ✓ वाला) लंबा दबाओ -> Delete -> "Delete for everyone"
 * -> स्क्रीन पर "You deleted this message" दिखे तभी "हो गया".
 * यहाँ सिर्फ़ बिना Android वाला हिस्सा (टेस्ट होता है); चलाना = agent.android.WaDeleteRunner.
 */
object WaDelete {
    data class Sent(val name: String, val digits: String, val text: String, val at: Long)

    /** DK ने अभी-अभी जो WhatsApp मैसेज भेजा ("उसे डिलीट करो" = यही) */
    @Volatile var lastSent: Sent? = null
        private set
    const val RECENT_MS = 24 * 60 * 60_000L

    fun remember(name: String, digits: String, text: String, now: Long = System.currentTimeMillis()) {
        if (digits.isNotBlank()) lastSent = Sent(name, digits, text, now)
    }

    fun forget() { lastSent = null }

    fun recent(now: Long = System.currentTimeMillis()): Sent? = lastSent?.takeIf { now - it.at in 0..RECENT_MS }

    private val DEL = listOf("डिलीट", "डिलिट", "delete", "dilit", "मिटा", "हटा", "unsend", "अनसेंड", "वापस ले", "वापस लो")
    private val MSG = listOf("मैसेज", "मेसेज", "message", "msg", "संदेश", "चैट", "chat", "whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "वॉट्सएप", "व्हाट्सअप", "whats app")
    private val SENT = listOf("भेजा", "भेजी", "भेज दिया", "चला गया", "चली गई", "गलती से", "galti se", "sent", "by mistake")
    private val MEDIA = listOf("फोटो", "फ़ोटो", "photo", "स्क्रीनशॉट", "screenshot", "तस्वीर", "image", "इमेज", "वीडियो", "video")
    private val WA = listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "वॉट्सएप", "व्हाट्सअप", "whats app")

    /**
     * यह भेजा हुआ मैसेज मिटाने की बात है.
     * "राहुल को बोल दो कि गाड़ी हटा दो" = मैसेज का हिस्सा है, मिटाना नहीं ( "कि" / "that" के बाद वाला).
     */
    fun isDelete(request: String, sentRecently: Boolean = false): Boolean {
        val t = " " + request.trim().lowercase() + " "
        val head = t.substringBefore(" कि ").substringBefore(" that ").substringBefore(" ki ")
        if (DEL.none { head.contains(it) }) return false
        if (listOf("मत ", " mat ", "नहीं", "don't", "do not").any { t.contains(it) }) return false
        val wa = WA.any { t.contains(it) }
        // "WhatsApp पर भेजी फोटो डिलीट करो" = WhatsApp का मैसेज; बिना WhatsApp के "फोटो डिलीट" = गैलरी (MediaPick)
        if (MEDIA.any { t.contains(it) } && !wa) return false
        if (MSG.any { t.contains(it) }) return true
        // "उसे डिलीट कर दो" - अभी-अभी DK ने WhatsApp भेजा था
        return sentRecently && (SENT.any { t.contains(it) } || listOf("उसे", "उसको", "वो वाला", " वह ", " it ").any { t.contains(it) })
    }

    /** "मेरे लिए" / "सिर्फ़ मेरे फ़ोन से" = Delete for me (सबके लिए नहीं) */
    fun forMeOnly(request: String): Boolean {
        val t = request.lowercase()
        return listOf("मेरे लिए", "सिर्फ़ मेरे", "सिर्फ मेरे", "for me", "mere liye").any { t.contains(it) } &&
            listOf("सबके", "सभी के", "everyone", "दोनों").none { t.contains(it) }
    }

    private val STOP = setOf("उसे", "उसको", "इसे", "जो", "मैंने", "मेने", "गलती", "से", "पर", "में", "मैसेज", "मेसेज", "message", "msg", "भेजा", "भेजी",
        "गया", "गई", "अभी", "वो", "वह", "उस", "मेरा", "मेरी", "last", "आखिरी", "आख़िरी", "वाला", "वाली", "डीके", "dk", "हाँ", "हां", "ठीक", "है", "ने", "का", "की", "के",
        "संदेश", "चैट", "chat", "जिसको", "जिसे", "किसी", "a", "the", "to", "ko", "को", "please", "प्लीज़", "प्लीज") + WA.toSet()

    /** "राहुल को भेजा मैसेज डिलीट करो" -> "राहुल"; "मम्मी वाली चैट में..." -> "मम्मी"; नाम नहीं = "" */
    fun contactIn(request: String): String {
        val w = request.trim().replace(Regex("[,.!?।\"']"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val low = w.map { it.lowercase() }
        val at = low.indices.firstOrNull { i -> low[i] in setOf("को", "ko", "वाला", "वाली", "वाले") ||
            (low[i] in setOf("की", "के", "ki", "ke") && low.getOrNull(i + 1)?.let { it.startsWith("चैट") || it == "chat" } == true) }
        if (at != null) {
            val name = (maxOf(0, at - 2) until at).map { w[it] }.filter { it.lowercase() !in STOP }
            if (name.isNotEmpty()) return name.joinToString(" ")
        }
        val to = low.indexOf("to")
        if (to >= 0 && to + 1 < w.size && low[to + 1] !in STOP) return w[to + 1]
        return ""
    }

    // ---------------------------------------------------------------- स्क्रीन (WhatsApp चैट)

    private val TICK = listOf("read", "delivered", "sent", "pending", "पढ़ा गया", "पढ़ लिया", "डिलीवर", "भेजा गया", "भेज दिया गया", "seen")
    private val DELETED_BUBBLE = listOf("you deleted this message", "this message was deleted", "आपने यह मैसेज डिलीट किया", "यह मैसेज डिलीट कर दिया गया",
        "आपने यह संदेश हटा दिया", "यह संदेश हटा दिया गया", "आपने यह मैसेज हटाया")
    private val TIME = Regex("^\\d{1,2}:\\d{2}( ?[ap]\\.?m\\.?)?$", RegexOption.IGNORE_CASE)

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** मैसेज वाले बुलबुले (WhatsApp की id; न मिले तो बीच वाले हिस्से के text) */
    fun bubbles(o: ScreenObservation): List<UiElement> {
        val byId = o.elements.filter { it.viewId.endsWith(":id/message_text") || it.viewId.endsWith(":id/conversation_text") || it.viewId.endsWith(":id/caption") }
        if (byId.isNotEmpty()) return byId
        return o.elements.filter { e ->
            e.text.isNotBlank() && !e.editable && e.bounds.top > o.height * 0.1 && e.bounds.bottom < o.height * 0.9 &&
                !TIME.matches(e.text.trim()) && e.viewId.let { !it.endsWith("conversation_contact_name") && !it.endsWith("date") }
        }
    }

    /** आपका भेजा (दाईं ओर / उसी पंक्ति में ✓✓) */
    fun outgoing(o: ScreenObservation, e: UiElement): Boolean {
        val ticks = o.elements.filter { s -> s.viewId.endsWith(":id/status") || TICK.any { s.desc.lowercase() == it } }
        if (ticks.any { s -> s.bounds.centerY in (e.bounds.top - 20)..(e.bounds.bottom + 90) && s.bounds.centerX > e.bounds.left }) return true
        if (ticks.isNotEmpty() && o.elements.any { it.viewId.endsWith(":id/status") }) return false
        return e.bounds.left > o.width * 0.12 && e.bounds.right > o.width * 0.72
    }

    /** मिटाना है वह बुलबुला: DK का भेजा text मिले तो वही, वरना आपका सबसे नीचे वाला */
    fun target(o: ScreenObservation, want: String = ""): UiElement? {
        val mine = bubbles(o).filter { b -> outgoing(o, b) && DELETED_BUBBLE.none { norm(b.text).contains(it) } }
        if (mine.isEmpty()) return null
        val w = norm(want).take(40)
        if (w.length >= 3) mine.lastOrNull { norm(it.text).contains(w) || w.contains(norm(it.text).take(40)) }?.let { return it }
        return mine.maxByOrNull { it.bounds.bottom }
    }

    private val DELETE_BTN = listOf("delete", "डिलीट करें", "डिलीट", "हटाएँ", "हटाएं", "मिटाएँ", "मिटाएं")

    /** चुनने के बाद ऊपर वाला Delete (कूड़ेदान) */
    fun deleteButton(o: ScreenObservation): UiElement? =
        o.elements.firstOrNull { it.viewId.endsWith(":id/menuitem_delete") }
            ?: o.elements.firstOrNull { e -> e.bounds.top < o.height * 0.2 && DELETE_BTN.any { norm(e.desc) == it || norm(e.text) == it } }

    private val EVERYONE = listOf("delete for everyone", "सभी के लिए", "सबके लिए", "सब के लिए", "everyone")
    private val FOR_ME = listOf("delete for me", "मेरे लिए", "for me")
    private val CANCEL = listOf("cancel", "रद्द करें", "रद्द", "कैंसल")

    fun everyoneButton(o: ScreenObservation): UiElement? = o.elements.firstOrNull { e -> EVERYONE.any { norm(e.label).contains(it) } && !e.editable }
    fun forMeButton(o: ScreenObservation): UiElement? = o.elements.firstOrNull { e -> FOR_ME.any { norm(e.label).contains(it) } && EVERYONE.none { norm(e.label).contains(it) } }
    fun cancelButton(o: ScreenObservation): UiElement? = o.elements.firstOrNull { e -> CANCEL.any { norm(e.label) == it } }

    /** मिटने के बाद: "You deleted this message" दिखा और वह text अब नहीं */
    fun deleted(o: ScreenObservation, gone: String): Boolean {
        val g = norm(gone)
        val shows = o.elements.any { e -> DELETED_BUBBLE.any { norm(e.label).contains(it) } }
        val still = g.isNotEmpty() && bubbles(o).any { norm(it.text) == g && outgoing(o, it) }
        return shows && !still
    }

    /** Live / AgentRunner के लिए सच्चा जवाब */
    fun done(name: String, everyone: Boolean) =
        "DONE: ${if (name.isNotBlank()) "$name वाली चैट में " else ""}आपका आखिरी भेजा मैसेज ${if (everyone) "सबके लिए" else "आपके फ़ोन से"} डिलीट कर दिया - स्क्रीन पर \"You deleted this message\" दिख रहा है।"
}
