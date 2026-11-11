package com.dk.assistant.agent.android

import com.dk.assistant.BrainManager
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.social.OpenSpec
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.WaDelete
import com.dk.assistant.agent.social.WaScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * नवंबर 2026: WhatsApp खोलकर स्क्रीन देखना (कौन-कौन दिख रहा, किसके नए मैसेज), किसी की चैट खोलकर पढ़ना,
 * और मालिक की ओर से समझदार जवाब (सामने वाले की बात के हिसाब से). पक्का हिस्सा: [WaScreen].
 * मैसेज का text log नहीं होता.
 */
object WaLook {
    private val lock = Mutex()
    private fun wa(o: ScreenObservation?) = o != null && o.pkg.startsWith(Platform.WHATSAPP.pkg)

    /** WhatsApp खोलो और चैट सूची तक पहुँचो (कोई चैट खुली हो = पीछे) */
    private suspend fun chatList(svc: DKAccessibilityService, d: AccessibilityScreenDriver): ScreenObservation? {
        var o = d.capture()
        if (!wa(o)) {
            if (!SocialAgent.open(svc, OpenSpec.Launch(Platform.WHATSAPP.pkg))) return null
            o = null
            for (i in 0 until 12) { delay(if (i == 0) 900 else 400); val c = d.capture(); if (wa(c)) { o = c; if (WaScreen.isChatList(c!!)) return c } }
        }
        for (i in 0 until 3) {
            val c = o ?: return null
            if (WaScreen.isChatList(c)) return c
            d.back(); delay(500); o = d.capture()?.takeIf { wa(it) }
        }
        return o?.takeIf { WaScreen.isChatList(it) }
    }

    /** "WhatsApp खोलकर बताओ किसका-किसका दिख रहा / किसके मैसेज आए" */
    suspend fun screenChats(svc: DKAccessibilityService): String = lock.withLock {
        val d = AccessibilityScreenDriver(svc)
        val o = chatList(svc, d) ?: return "FAILED: WhatsApp की चैट सूची स्क्रीन पर नहीं मिली (WhatsApp खुला नहीं / लॉक है)।"
        val rows = WaScreen.rows(o)
        DKLog.d("WhatsApp स्क्रीन: ${rows.size} चैट दिखीं, ${rows.count { it.unread > 0 }} में नए मैसेज")
        WaScreen.describe(rows)
    }

    /** किसी की चैट खोलो (स्क्रीन की पंक्ति / कॉन्टैक्ट / नंबर) और पढ़ो. contact खाली = अभी खुली चैट */
    suspend fun openChat(svc: DKAccessibilityService, contact: String): String = lock.withLock { open(svc, contact).first }

    /** @return (Live के लिए नतीजा, खुली चैट + नाम + अंक या null) */
    private suspend fun open(svc: DKAccessibilityService, contact: String): Pair<String, Triple<ScreenObservation, String, String>?> {
        val d = AccessibilityScreenDriver(svc)
        val said = contact.trim()
        var digits = ""
        var who = said
        if (said.isEmpty()) {
            val o = d.capture()
            if (wa(o) && WaScreen.isChat(o!!)) return ready(o, WaScreen.chatTitle(o), "")
            return "NEEDS USER CHOICE: किसकी चैट खोलूँ? Ask the user for the name (or call action=screen_chats to see who is on screen)." to null
        }
        // पहले स्क्रीन पर दिख रही पंक्ति (मालिक ने वहीं से नाम चुना)
        val list = chatList(svc, d)
        val row = list?.let { WaScreen.pick(said, WaScreen.rows(it)) }
        if (list != null && row != null) {
            d.click(list, row.el); who = row.name
        } else {
            when (val c = SocialAgent.contact(svc, said)) {
                is SocialAgent.Contact.Found -> { digits = c.digits; who = c.name }
                is SocialAgent.Contact.Ask -> return "NEEDS USER CHOICE: ${c.msg}. (स्क्रीन पर भी यह नाम नहीं दिखा.) Ask the user" to null
            }
            if (!SocialAgent.open(svc, OpenSpec.Url("https://wa.me/$digits", Platform.WHATSAPP.pkg))) return "FAILED: WhatsApp नहीं खुला।" to null
        }
        for (i in 0 until 14) {
            delay(if (i == 0) 800 else 400)
            val c = d.capture() ?: continue
            if (wa(c) && WaScreen.isChat(c)) return ready(c, WaScreen.chatTitle(c).ifBlank { who }, digits)
        }
        return "FAILED: $who की चैट स्क्रीन पर नहीं खुली।" to null
    }

    private fun ready(o: ScreenObservation, who: String, digits: String) =
        WaScreen.describeChat(who.ifBlank { "this person" }, WaScreen.messages(o)) to Triple(o, who, digits)

    /**
     * मालिक की ओर से जवाब: चैट पढ़ो -> सामने वाले की आख़िरी बात के हिसाब से AI जवाब -> लिखकर भेजो -> स्क्रीन पर पक्का.
     * hint = मालिक ने क्या कहलवाना है (खाली = DK खुद समझकर).
     */
    suspend fun smartReply(svc: DKAccessibilityService, contact: String, hint: String): String = lock.withLock {
        val (note, got) = open(svc, contact)
        val (o, who, digits) = got ?: return note
        val msgs = WaScreen.messages(o)
        if (msgs.isEmpty()) return "FAILED: $who की चैट में कोई मैसेज नहीं दिखा - किस बात का जवाब दूँ? Ask the user what to say"
        if (msgs.last().mine && hint.isBlank()) return "NOTHING NEW: $who की चैट में आख़िरी मैसेज आपका ही है - उधर से नया कुछ नहीं आया। Tell the user"
        if (WaScreen.needsOwner(msgs.last().text) && hint.isBlank())
            return "NEEDS USER CHOICE (nothing sent): $who पैसे / OTP / बैंक जैसी बात कर रहे हैं - \"${msgs.last().text.take(120)}\". DK ने अपने से जवाब नहीं दिया। Ask the user exactly what to reply"
        val text = WaScreen.clean(BrainManager(svc).quickText(WaScreen.SYSTEM, WaScreen.replyPrompt(who, msgs, hint)))
            ?: return "FAILED: जवाब नहीं बन पाया (AI से जवाब नहीं आया) - कुछ नहीं भेजा।"
        if (WaScreen.unsafe(text)) return "NEEDS USER CHOICE (nothing sent): बने जवाब में निजी जानकारी (OTP/पासवर्ड/लंबे अंक) थी, इसलिए नहीं भेजा। Ask the user what to send"
        val r = send(svc, text)
        if (r == null) {
            DKLog.d("WhatsApp: $who को मालिक की ओर से जवाब भेजा (${text.length} अक्षर)")
            if (digits.isNotBlank()) WaDelete.remember(who, digits, text)
            return "SENT on the user's behalf to $who: \"$text\". Tell the user briefly what $who had said and what DK replied."
        }
        return r
    }

    /** खुली चैट में text लिखो और भेजो. null = भेज दिया (स्क्रीन पर दिखा), वरना कारण */
    internal suspend fun send(svc: DKAccessibilityService, text: String): String? {
        val d = AccessibilityScreenDriver(svc)
        val o = d.capture()?.takeIf { wa(it) && WaScreen.isChat(it) } ?: return "FAILED: चैट स्क्रीन पर नहीं है - कुछ नहीं भेजा।"
        val box = o.elements.firstOrNull { it.editable && it.viewId.endsWith(":id/entry") } ?: o.elements.lastOrNull { it.editable }
            ?: return "FAILED: लिखने का खाना नहीं मिला - कुछ नहीं भेजा।"
        d.setText(o, box, text)
        for (i in 0 until 6) {
            delay(300)
            val c = d.capture() ?: continue
            val btn = c.elements.firstOrNull { it.viewId.endsWith(":id/send") }
                ?: c.elements.firstOrNull { e -> e.clickable && e.desc.lowercase().let { it == "send" || it == "भेजें" || it == "भेजें बटन" } }
                ?: continue
            d.click(c, btn)
            for (j in 0 until 8) {
                delay(350)
                val a = d.capture() ?: continue
                if (WaScreen.messages(a).takeLast(4).any { it.mine && it.text.trim() == text.trim() }) return null
            }
            return "SUBMITTED_UNVERIFIED: भेजें दबा दिया (\"$text\"), पर स्क्रीन पर पक्का नहीं दिखा - WhatsApp में एक बार देख लीजिए।"
        }
        return "FAILED: भेजें का बटन नहीं मिला - मैसेज लिखा पड़ा है, भेजा नहीं।"
    }
}
