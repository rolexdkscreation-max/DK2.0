package com.dk.assistant

/**
 * v3.151 (मालिक की शिकायत): "धीरज कुमार को मैसेज करो" -> DK: "क्या मैसेज करना है?" -> "उसे कल घूमने चलना है"
 * -> DK फिर पूछता था "कॉन्टैक्ट का नाम बताओ"।
 * जड़: दूसरी बार मॉडल whatsapp_task में contact खाली / "उसे" भेजता था (या phone_task में सिर्फ़ आख़िरी बात),
 * और DK पहले बताया नाम भूल जाता था।
 * अब: पिछले कुछ मिनट की असली बातचीत (DKState.messages) से छूटा नाम / संदेश भर लिया जाता है।
 * जो फिर भी न मिले, उसके लिए मॉडल को साफ़ बताया जाता है कि क्या पहले से पता है - सिर्फ़ बाकी पूछे।
 */
object MsgDraft {
    const val WINDOW_MS = 5 * 60_000L

    data class Turn(val who: String, val text: String, val at: Long)
    data class Filled(val contact: String, val message: String, val fromHistory: Boolean)

    private val PRONOUN = setOf(
        "उसे", "उसको", "उस", "उन्हें", "उनको", "उन", "इसे", "इसको", "इन्हें", "इनको", "वो", "वह", "उसी", "उसीको", "उन्हीं",
        "him", "her", "them", "he", "she", "they", "use", "usko", "unko", "unhe", "same", "को", "ko",
        "कॉन्टैक्ट", "contact", "person", "उसी को"
    )
    private val INTENT = Regex("(मैसेज|मेसेज|मैसेज़|मेसेज़|संदेश|message|msg|whatsapp|व्हाट्स\\S*|वॉट्स\\S*|वाट्स\\S*)", RegexOption.IGNORE_CASE)
    private val MSG_WORD = "(?:मैसेज|मेसेज|मैसेज़|मेसेज़|संदेश|message|msg|text)"
    /** "धीरज कुमार का/के मैसेज करो" (सुनने में "को" की जगह "का" आ जाता है) -> "को" */
    private val KA = Regex("\\s+(?:का|के|की|ko|ka|ke)\\s+(?=(?:(?:व्हाट्स|वॉट्स|वाट्स|whatsapp)\\S*\\s+(?:पर\\s+|pe\\s+|par\\s+)?)?$MSG_WORD)", RegexOption.IGNORE_CASE)
    private val WAKE = Regex("^\\s*(?:hey\\s+|ok\\s+|अरे\\s+)?(?:डीके|डी\\s*के|dk|d\\.k\\.?)[\\s,।!]+", RegexOption.IGNORE_CASE)
    /** DK ने संदेश पूछा: "क्या मैसेज करना है?" / "क्या लिखूँ?" / "what message" */
    private val ASK_MSG = Regex("(क्या|what|कौन\\s*सा).{0,30}(मैसेज|मेसेज|संदेश|लिख|भेज|message|बोलना|कहना|text)", RegexOption.IGNORE_CASE)
    private val MSG_TAIL = Regex("[\\s,।]*(?:ऐसा|यह|ये|यही|इतना)?\\s*(?:करके|कर\\s*के|बोल\\s*के|बोलकर|लिखकर|लिख\\s*के)?\\s*$MSG_WORD\\s*(?:कर|भेज|send|लिख)\\S*(?:\\s*(?:दो|देना|दीजिए|दे|do|dena))?[\\s।.!]*$", RegexOption.IGNORE_CASE)
    private val MSG_HEAD = Regex("^\\s*(?:उसे|उसको|उन्हें|उनको|उसे\\s+बोलो|बोलो|बोल\\s*दो|लिखो|कि|that)[\\s,]+(?:कि\\s+|बोलो\\s+(?:कि\\s+)?)?", RegexOption.IGNORE_CASE)

    /** खाली, "उसे", "him" जैसे = नाम नहीं */
    fun isPronoun(c: String): Boolean {
        val t = c.trim().trim(',', '।', '.', '?').lowercase()
        if (t.isBlank()) return true
        return t in PRONOUN || t.split(Regex("\\s+")).all { it in PRONOUN }
    }

    /** "डीके, धीरज कुमार का मैसेज करो" -> "धीरज कुमार"; मैसेज वाली बात न हो / नाम न हो -> "" */
    fun contactOf(text: String): String {
        if (!INTENT.containsMatchIn(text)) return ""
        val t = KA.replace(WAKE.replace(text, ""), " को ")
        val c = TaskQueue.parseWa(t).first.trim().trim(',', '।', '.', '?')
        return if (isPronoun(c)) "" else c
    }

    /** "उसे कल घूमने जाना है, चलो करके मैसेज करना" -> "कल घूमने जाना है, चलो" */
    fun cleanMessage(text: String): String {
        var t = WAKE.replace(text.trim(), "")
        t = MSG_TAIL.replace(t, "")
        t = MSG_HEAD.replace(t, "")
        return t.trim().trim(',', '।', '"', '\'', '“', '”').trim()
    }

    /**
     * मॉडल ने जो भेजा (contact, message) + हाल की बातचीत -> पूरा भरा।
     * नाम: मॉडल का खाली/सर्वनाम हो तो यूज़र की हाल की "X को मैसेज" वाली बात से।
     * संदेश: खाली हो तो DK के "क्या मैसेज करना है?" के बाद यूज़र ने जो कहा।
     */
    fun fill(contact: String, message: String, turns: List<Turn>, now: Long): Filled {
        val recent = turns.filter { it.at in (now - WINDOW_MS)..(now + 5_000) && it.text.isNotBlank() && !it.text.endsWith("…") }.sortedBy { it.at }
        var c = contact.trim()
        var m = message.trim()
        var hist = false
        if (isPronoun(c)) {
            val found = recent.asReversed().asSequence().filter { it.who == "u" }.map { contactOf(it.text) }.firstOrNull { it.isNotBlank() }
            c = if (found != null) { hist = true; found } else ""
        }
        if (m.isBlank()) {
            val ask = recent.indexOfLast { it.who == "d" && ASK_MSG.containsMatchIn(it.text) }
            if (ask >= 0) {
                val ans = recent.drop(ask + 1).firstOrNull { it.who == "u" }?.text?.let { cleanMessage(it) }.orEmpty()
                if (ans.length >= 2) { m = ans; hist = true }
            }
        }
        return Filled(c, m, hist)
    }

    /** whatsapp_task send_message के args में छूटा नाम/संदेश भरो (बातचीत से) */
    fun applyTo(args: org.json.JSONObject, turns: List<Turn>, now: Long): Filled {
        val f = fill(args.optString("contact"), args.optString("message"), turns, now)
        if (f.contact.isNotBlank()) args.put("contact", f.contact)
        if (f.message.isNotBlank()) args.put("message", f.message)
        return f
    }

    /** अभी भी कुछ छूटा = मॉडल को: क्या पता है, सिर्फ़ बाकी पूछो (दोबारा वही मत पूछो) */
    fun missingNote(f: Filled): String? {
        if (f.contact.isNotBlank() && f.message.isNotBlank()) return null
        val known = buildList {
            if (f.contact.isNotBlank()) add("contact=\"${f.contact}\"")
            if (f.message.isNotBlank()) add("message=\"${f.message.take(120)}\"")
        }
        val need = if (f.contact.isBlank() && f.message.isBlank()) "the contact name and the message text"
            else if (f.contact.isBlank()) "ONLY the contact name" else "ONLY the message text"
        return "not_done: send_message needs contact and message. Nothing was sent. " +
            (if (known.isNotEmpty()) "ALREADY KNOWN from this conversation: ${known.joinToString(", ")} - do NOT ask for it again. " else "") +
            "Ask the user $need in one short line, then call whatsapp_task again with BOTH contact and message filled."
    }

    /** DKState की बातचीत -> Turn */
    fun turnsFromState(): List<Turn> = DKState.messages.map { Turn(it.role, it.text, it.time) }

    /** phone_task में सिर्फ़ "कल घूमने चलना है, मैसेज कर दो" आया (नाम नहीं, SMS नहीं) पर अभी-अभी नाम बताया था -> whatsapp_task */
    fun redirectPhoneTask(req: String, turns: List<Turn>, now: Long): String? {
        val t = req.lowercase()
        if (!Regex(MSG_WORD, RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        if (t.contains("sms") || t.contains("एसएमएस")) return null
        if (Regex("(पढ़|पढो|read|unread|कौन\\s*सा|कोई\\s*मैसेज|आया|खोल|open|ऐप|app)", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        if (contactOf(req).isNotBlank()) return null
        val c = recentContact(turns, now) ?: return null
        val msg = cleanMessage(req)
        return "not_done: this is the WhatsApp message the user asked for a moment ago - nothing was sent by phone_task. " +
            "Call whatsapp_task now with action=send_message, contact=\"$c\" (he already told you the name - do NOT ask it again)" +
            (if (msg.length >= 2) " and message=\"${msg.take(200).replace("\"", "'")}\"." else " and the message text he gave.")
    }

    fun recentContact(turns: List<Turn>, now: Long): String? =
        turns.filter { it.who == "u" && it.at in (now - WINDOW_MS)..(now + 5_000) }.sortedBy { it.at }.asReversed()
            .asSequence().map { contactOf(it.text) }.firstOrNull { it.isNotBlank() }

    /** नए Live connection के लिए (लोकल याददाश्त बंद हो तब भी): अभी की बातचीत के आख़िरी कुछ मिनट */
    fun recentBlock(turns: List<Turn>, now: Long, windowMs: Long = 30 * 60_000L, maxTurns: Int = 12, maxChars: Int = 1500): String {
        val keep = turns.filter { it.at in (now - windowMs)..now && it.text.isNotBlank() && !it.text.endsWith("…") }.sortedBy { it.at }.takeLast(maxTurns)
        if (keep.isEmpty()) return ""
        val lines = ArrayList<String>(); var len = 0
        for (t in keep.asReversed()) {
            val line = "\n- " + (if (t.who == "u") "User" else "You") + ": " + t.text.take(200)
            if (len + line.length > maxChars) break
            lines.add(line); len += line.length
        }
        val sb = StringBuilder("\nTHIS CONVERSATION SO FAR (newest last) - continue it; never ask again for anything the user already told you here:")
        lines.asReversed().forEach { sb.append(it) }
        return sb.append("\n").toString()
    }
}
