package com.dk.assistant.agent.social

import com.dk.assistant.NameMatch
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement

/**
 * नवंबर 2026 (मालिक: "WhatsApp खुलेगा तो स्क्रीन पर किसका-किसका नाम/नंबर दिख रहा है, वह मुझे बताएगा, पूछेगा किसका मैसेज करना है;
 * मैं बोलूँ 'हाँ, उसे मेरी तरफ़ से रिप्लाई' तो सामने वाले की बात समझकर उसी के हिसाब से जवाब देगा, इधर-उधर की बात नहीं;
 * 'जिसका भी मैसेज आ रहा है उससे बातें करो' तो आराम से बात करता रहेगा").
 *
 * यहाँ सिर्फ़ पक्का (pure) हिस्सा - स्क्रीन पढ़ना, नाम मिलाना, जवाब का prompt, सुरक्षा, अपने-आप बात (auto chat) के नियम.
 * फ़ोन चलाने वाला हिस्सा: agent/android/WaLook.kt, WaAutoChat.kt. JUnit: WaScreenTest.
 */
object WaScreen {
    /** चैट सूची की एक पंक्ति */
    data class Row(val name: String, val preview: String, val time: String, val unread: Int, val el: UiElement) {
        /** सेव नहीं है = नंबर ही नाम है */
        val isNumber: Boolean get() = name.count { it.isDigit() } >= 8 && name.none { it.isLetter() }
    }

    /** खुली चैट का एक मैसेज */
    data class Msg(val text: String, val mine: Boolean)

    private fun idEnds(e: UiElement, vararg ids: String) = ids.any { e.viewId.endsWith(":id/$it") }

    /** चैट सूची (WhatsApp की पहली स्क्रीन) */
    fun isChatList(o: ScreenObservation): Boolean = o.elements.any { idEnds(it, "conversations_row_contact_name") }

    /** कोई चैट खुली है (नीचे लिखने का खाना / ऊपर नाम) */
    fun isChat(o: ScreenObservation): Boolean =
        o.elements.any { idEnds(it, "entry") && it.editable } || o.elements.any { idEnds(it, "conversation_contact_name") }

    /** खुली चैट किसकी है (ऊपर वाला नाम) */
    fun chatTitle(o: ScreenObservation): String =
        o.elements.firstOrNull { idEnds(it, "conversation_contact_name") }?.let { it.text.ifBlank { it.desc } }?.trim().orEmpty()

    /** ग्रुप? (ऊपर नाम के नीचे सदस्यों की सूची, या ग्रुप का निशान) */
    fun isGroupChat(o: ScreenObservation): Boolean {
        val status = o.elements.firstOrNull { idEnds(it, "conversation_contact_status") }?.text.orEmpty()
        return status.count { it == ',' } >= 2 || o.elements.any { idEnds(it, "name_in_group", "name_in_group_tv") }
    }

    /** स्क्रीन पर दिख रही चैट की पंक्तियाँ (ऊपर से नीचे) */
    fun rows(o: ScreenObservation): List<Row> {
        val names = o.elements.filter { idEnds(it, "conversations_row_contact_name") && it.text.isNotBlank() }.sortedBy { it.bounds.top }
        return names.mapIndexed { i, n ->
            val top = n.bounds.top - 30
            val bottom = names.getOrNull(i + 1)?.bounds?.top?.minus(5) ?: (n.bounds.bottom + 140)
            val band = o.elements.filter { it !== n && it.bounds.centerY in top..bottom }
            val time = band.firstOrNull { idEnds(it, "conversations_row_date") }?.text?.trim().orEmpty()
            val unread = band.firstOrNull { idEnds(it, "conversations_row_message_count") }?.text?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val preview = band.firstOrNull { idEnds(it, "single_msg_tv", "conversations_row_message", "msg_from_tv") && it.text.isNotBlank() }?.text
                ?: band.firstOrNull { e -> e.text.isNotBlank() && !idEnds(e, "conversations_row_date", "conversations_row_message_count") &&
                    e.bounds.top >= n.bounds.bottom - 10 }?.text.orEmpty()
            Row(n.text.trim(), preview.trim().take(80), time, unread, n)
        }
    }

    /** Live को बताने लायक: कौन-कौन दिख रहा, किसके नए मैसेज */
    fun describe(rows: List<Row>, max: Int = 12): String {
        if (rows.isEmpty()) return "WhatsApp खुला, पर स्क्रीन पर कोई चैट नहीं दिखी।"
        val list = rows.take(max).mapIndexed { i, r ->
            "${i + 1}. ${r.name}${if (r.isNumber) " (सेव नहीं, नंबर)" else ""}" +
                (if (r.unread > 0) " — ${r.unread} नए मैसेज" else "") +
                (if (r.preview.isNotBlank()) ": \"${r.preview}\"" else "") + (if (r.time.isNotBlank()) " [${r.time}]" else "")
        }
        val un = rows.filter { it.unread > 0 }
        return "WHATSAPP OPEN on screen. Visible chats (top to bottom):\n" + list.joinToString("\n") +
            "\n" + (if (un.isEmpty()) "No unread chats visible." else "Unread from: " + un.joinToString(", ") { "${it.name} (${it.unread})" } + ".") +
            "\nTell the user, in short Hindi, whose names/numbers are visible and who sent new messages (read the previews). " +
            "Then ask whose chat to open or whom to reply. When he names one, call whatsapp_task action=open_chat with that name."
    }

    /** बोला नाम -> स्क्रीन की पंक्ति (नाम, नंबर के अंक, या \"पहला/दूसरा\") */
    fun pick(spoken: String, rows: List<Row>): Row? {
        val s = spoken.trim()
        if (s.isEmpty() || rows.isEmpty()) return null
        ordinal(s)?.let { return rows.getOrNull(it) }
        val d = s.filter { it.isDigit() }
        if (d.length >= 4) rows.firstOrNull { r -> r.name.filter { it.isDigit() }.endsWith(d.takeLast(10)) }?.let { return it }
        rows.firstOrNull { it.name.equals(s, ignoreCase = true) }?.let { return it }
        return NameMatch.best(s, rows, { it.name }, min = 70).firstOrNull()
    }

    private val ORD = listOf(
        listOf("पहला", "पहले", "पहली", "first", "1st", "pehla", "pahla", "पहले वाला"),
        listOf("दूसरा", "दूसरे", "दूसरी", "second", "2nd", "dusra", "doosra"),
        listOf("तीसरा", "तीसरे", "तीसरी", "third", "3rd", "teesra", "tisra"),
        listOf("चौथा", "चौथे", "चौथी", "fourth", "4th", "chautha"),
        listOf("पाँचवाँ", "पांचवा", "पाँचवें", "fifth", "5th"),
    )

    private fun ordinal(s: String): Int? {
        val w = s.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (w.size > 3) return null
        if (w.size == 1 && w[0].toIntOrNull() in 1..12) return w[0].toInt() - 1
        return ORD.indexOfFirst { names -> w.any { it in names } }.takeIf { it >= 0 }
    }

    /** खुली चैट के मैसेज, ऊपर से नीचे (कौन-सा मेरा) */
    fun messages(o: ScreenObservation): List<Msg> =
        WaDelete.bubbles(o).sortedBy { it.bounds.top }.map { Msg(it.text.trim(), WaDelete.outgoing(o, it)) }.filter { it.text.isNotEmpty() }

    /** Live को चैट पढ़कर बताने लायक */
    fun describeChat(who: String, msgs: List<Msg>, max: Int = 10): String {
        if (msgs.isEmpty()) return "CHAT OPEN with $who, but no messages are visible on screen."
        val last = msgs.takeLast(max).joinToString("\n") { (if (it.mine) "आप: " else "$who: ") + it.text.take(200) }
        val waiting = msgs.last().let { !it.mine }
        return "CHAT OPEN with $who. Last messages on screen (oldest first):\n$last\n" +
            (if (waiting) "$who's last message is waiting for a reply. Tell the user briefly what $who said / asked, then ask if DK should reply on his behalf (whatsapp_task action=smart_reply)."
            else "The last message is the user's own - nothing new from $who. Tell him that.")
    }

    // ---------- मालिक की ओर से जवाब ----------

    const val SYSTEM = "You reply on WhatsApp AS the phone owner (first person, his own voice) - never as an assistant or AI. " +
        "Read the chat and answer ONLY what the other person last said or asked, staying on that topic - no unrelated talk. " +
        "Match their language and script exactly (Hindi in Devanagari / Hinglish in Latin / English), and their tone (friendly, respectful with elders). " +
        "Keep it short and natural like a real WhatsApp text: 1-2 sentences, no greeting essay, no hashtags, emojis only if they used them. " +
        "Never invent facts, promises, amounts, addresses, times or meetings the owner has not told you - if the answer needs that, give a short " +
        "polite holding reply (e.g. 'अभी थोड़ा busy हूँ, थोड़ी देर में बताता हूँ'). Never share OTP, passwords, PIN, bank or card details. " +
        "Output ONLY the message text to send - no quotes, no labels, no explanation."

    /** AI को जाने वाला सवाल: बातचीत + मालिक का इशारा (क्या कहना है, अगर बताया) */
    fun replyPrompt(who: String, msgs: List<Msg>, hint: String = "", max: Int = 14): String {
        val chat = msgs.takeLast(max).joinToString("\n") { (if (it.mine) "Me" else who.ifBlank { "Them" }) + ": " + it.text.take(400) }
        return "Chat with ${who.ifBlank { "this person" }} (oldest first):\n$chat\n\n" +
            (if (hint.isNotBlank()) "The owner says what to convey: \"${hint.trim()}\" - say that, naturally, as a reply to their last message.\n" else "") +
            "Write my next reply."
    }

    /** AI के जवाब से भेजने लायक सादा text (null = भेजने लायक कुछ नहीं) */
    fun clean(raw: String?): String? {
        var t = raw?.trim().orEmpty()
        t = t.removePrefix("```").removeSuffix("```").trim()
        t = t.replace(Regex("^(reply|message|me|मैं|जवाब|answer)\\s*[:：-]\\s*", RegexOption.IGNORE_CASE), "")
        t = t.trim().trim('"', '“', '”', '\'').trim()
        if (t.isEmpty()) return null
        return t.take(500)
    }

    private val PRIVATE = Regex("\\b(otp|password|passcode|pin|cvv|upi pin|पासवर्ड|ओटीपी|पिन)\\b", RegexOption.IGNORE_CASE)
    private val LONG_DIGITS = Regex("\\d[\\d\\s-]{5,}\\d")

    /** भेजने वाले text में निजी चीज़? (OTP/पासवर्ड/लंबे अंक) */
    fun unsafe(text: String): Boolean = PRIVATE.containsMatchIn(text) || LONG_DIGITS.findAll(text).any { m -> m.value.count { it.isDigit() } >= 6 }

    private val SENSITIVE_ASK = Regex("(otp|password|पासवर्ड|ओटीपी|\\bpin\\b|cvv|bank|बैंक|account number|खाता नंबर|पैसे भेज|paise bhej|transfer|udhaar|उधार|loan|लोन)", RegexOption.IGNORE_CASE)

    /** सामने वाला ऐसा कुछ माँग रहा जिस पर मालिक खुद फ़ैसला करे (पैसे, OTP, बैंक...) */
    fun needsOwner(text: String): Boolean = SENSITIVE_ASK.containsMatchIn(text)

    // ---------- अपने-आप बात (auto chat) ----------

    /** एक सत्र: किससे (खाली = जिसका भी आए), कब तक, ज़्यादा से ज़्यादा कितने जवाब */
    data class Session(val target: String, val until: Long, val maxReplies: Int = MAX_REPLIES, val hint: String = "", var replies: Int = 0,
                       val lastSent: MutableMap<String, String> = mutableMapOf(), val lastAt: MutableMap<String, Long> = mutableMapOf())

    const val MAX_REPLIES = 30
    const val DEFAULT_MIN = 30
    const val MAX_MIN = 180
    /** एक ही व्यक्ति को दो जवाबों के बीच कम से कम */
    const val GAP_MS = 6_000L

    fun minutes(asked: Int): Int = if (asked <= 0) DEFAULT_MIN else asked.coerceAtMost(MAX_MIN)

    sealed class Decision {
        object Reply : Decision()
        data class Skip(val why: String) : Decision()
        /** मालिक खुद देखे (पैसे/OTP...) - Live को बताओ */
        data class AskOwner(val why: String) : Decision()
    }

    /**
     * WhatsApp का नया notification आया - अपने-आप जवाब दें?
     * title = भेजने वाला / चैट का नाम, lastText = सबसे नया मैसेज, lastMine = सबसे नया मैसेज मालिक का ही (DK का भेजा जवाब लौटकर आया)
     */
    fun decide(s: Session?, title: String, lastText: String, lastMine: Boolean, isGroup: Boolean, canReply: Boolean, now: Long): Decision {
        if (s == null) return Decision.Skip("off")
        if (now > s.until) return Decision.Skip("time over")
        if (s.replies >= s.maxReplies) return Decision.Skip("max replies")
        if (title.isBlank() || lastText.isBlank()) return Decision.Skip("empty")
        val named = s.target.isNotBlank()
        if (named && !nameMatches(s.target, title)) return Decision.Skip("other person")
        if (isGroup && !named) return Decision.Skip("group")
        if (!canReply) return Decision.Skip("no reply action")
        if (lastMine) return Decision.Skip("mine")
        val k = key(title)
        if (s.lastSent[k]?.trim() == lastText.trim()) return Decision.Skip("mine")
        if (now - (s.lastAt[k] ?: 0L) < GAP_MS) return Decision.Skip("too soon")
        if (needsOwner(lastText)) return Decision.AskOwner("sensitive")
        return Decision.Reply
    }

    fun key(title: String) = title.trim().lowercase()

    fun nameMatches(target: String, title: String): Boolean {
        val a = target.trim().lowercase(); val b = title.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        if (b.contains(a) || a.contains(b)) return true
        val da = a.filter { it.isDigit() }; val db = b.filter { it.isDigit() }
        if (da.length >= 6 && db.length >= 6 && (da.takeLast(10) == db.takeLast(10))) return true
        return NameMatch.score(target, title) >= 70
    }

    /** जवाब भेज दिया - याद रखो (लौटकर आया notification दोबारा जवाब न बनवाए) */
    fun sent(s: Session, title: String, text: String, now: Long) {
        s.replies++; s.lastSent[key(title)] = text; s.lastAt[key(title)] = now
    }
}
