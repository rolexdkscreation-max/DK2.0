package com.dk.assistant

/**
 * v4.1: घंटी / चल रही कॉल / कॉल ख़त्म होने के कुछ सेकंड बाद - बिना DK बोले कॉल की कमांड.
 * [CallReply] (उठाओ / काटो / स्पीकर / कौन) वैसा ही; यहाँ नई: काटकर मैसेज, काटकर तैयार मैसेज ("बाद में कॉल करूँगा"),
 * म्यूट / अनम्यूट, चल रही कॉल काटो. Android import नहीं -> JVM test.
 */
object CallCmd {
    const val REJECT_MSG = "reject_msg"       // काटो और मैसेज भेजो <text> (text खाली = पूछो क्या भेजूँ)
    const val REJECT_BUSY = "reject_busy"     // काटो और बोलो बाद में कॉल करूँगा (Settings वाला तैयार मैसेज)
    const val MUTE = "mute"
    const val UNMUTE = "unmute"
    const val END = "end"                     // चल रही कॉल काटो

    data class Cmd(val action: String, val text: String = "")

    /** कॉल ख़त्म होने के बाद इतनी देर तक कॉल वाली कमांड बिना DK */
    const val WINDOW_MS = 6_000L

    /** अभी घंटी बज रही या कॉल चल रही? (सेवा जोड़ती है; test में null) */
    @Volatile var liveCheck: (() -> Boolean)? = null
    @Volatile private var endedAt = 0L
    const val CANCEL = "cancel"              // "क्या भेजूँ?" के जवाब में "रहने दो"
    const val AWAIT_MS = 20_000L
    @Volatile private var awaitUntil = 0L

    fun markEnded(now: Long) { endedAt = now }
    fun windowOpen(now: Long): Boolean = (try { liveCheck?.invoke() == true } catch (_: Exception) { false }) ||
        (endedAt > 0 && now - endedAt in 0..WINDOW_MS) || awaiting(now)
    fun reset() { endedAt = 0L; awaitUntil = 0L }

    /** "क्या मैसेज भेजूँ?" पूछा - अगली बात ही मैसेज */
    fun awaitMessage(now: Long) { awaitUntil = now + AWAIT_MS }
    fun doneMessage() { awaitUntil = 0L }
    fun awaiting(now: Long): Boolean = now < awaitUntil

    private val CANCEL_RX = Regex("^(रहने दो|रहने दीजिए|मत भेजो|मत भेजना|कुछ नहीं|कुछ मत भेजो|नहीं|cancel|rehne do|mat bhejo|no|nothing)$")

    /**
     * कॉल के समय सुनी बात -> नई कॉल-कमांड. "क्या भेजूँ?" के बाद की बात = वही मैसेज (DK/नाम नहीं पूछते).
     * null = नई कमांड नहीं (तब [CallReply.parse] वाला पुराना रास्ता).
     */
    fun route(heard: String, now: Long): Cmd? {
        parse(heard)?.let { doneMessage(); return it }
        if (!awaiting(now)) return null
        var t = heard.replace(Regex("[\u200B-\u200D\uFEFF]"), "").trim().trim('.', '।', '!', ',', '"')
        // "भेजो कि मैं मीटिंग में हूँ" / "लिखो ..." -> सिर्फ़ मैसेज
        t = t.replace(Regex("^(यह |ये |बस )?(भेजो|भेज दो|लिखो|लिख दो|बोलो|कहो|send|write)\\s*(कि|की|ki|that)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(भेज दो|भेजो|लिख दो|send kar do|send karo|send)$", RegexOption.IGNORE_CASE), "").trim()
        if (CANCEL_RX.matches(t.lowercase())) { doneMessage(); return Cmd(CANCEL) }
        if (!usableText(t)) return null
        doneMessage()
        return Cmd(REJECT_MSG, t)
    }

    private fun norm(t: String) = " " + t.lowercase().replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replace(Regex("[,.!?।\"']+"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private val MSG_WORDS = listOf("मैसेज", "मेसेज", "मेसिज", "message", "msg", "एसएमएस", "sms", "मैसेज़", "संदेश", "text कर", "टेक्स्ट")
    private val SEND_VERB = Regex("\\s(भेजो|भेज दो|भेज देना|भेजना|कर दो|करो|send|send karo|bhejo|bhej do)\\s*$")
    private val LATER = listOf("बाद में कॉल", "बाद में बात", "बाद में फ़ोन", "बाद में फोन", "call later", "call you later",
        "baad me call", "baad mein call", "बिज़ी हूँ", "बिजी हूँ", "व्यस्त हूँ", "busy hu", "busy hoon", "मीटिंग में हूँ", "meeting me hu")
    private val CUT = listOf("काट", "कट ", "reject", "decline", "cut", "kaat", "मना कर", "रिजेक्ट")

    /** null = यह नई कमांड नहीं (तब [CallReply.parse] देखो) */
    fun parse(heard: String): Cmd? {
        val l = norm(heard)
        if (l.isBlank()) return null
        fun has(vararg w: String) = w.any { l.contains(it) }
        // DK का अपना सवाल गूँज बनकर
        if (has("क्या भेजूँ", "क्या भेजूं", "उठाऊँ या", "उठाऊं या")) return null
        val cut = CUT.any { l.contains(it) }
        val msg = MSG_WORDS.any { l.contains(it) }
        val later = LATER.any { l.contains(it) }
        val say = has("बोलो", "बोल दो", "कह दो", "कहो", "बता दो", "say ", "bol do", "bolo", "keh do")
        if (cut && later && !msg && (say || has(" कि "))) return Cmd(REJECT_BUSY)
        if (cut && msg) {
            // "काटो और मैसेज भेजो कि मैं मीटिंग में हूँ" / "cut and message I am driving"
            val idx = MSG_WORDS.map { l.indexOf(it).let { i -> if (i >= 0) i + it.length else -1 } }.filter { it >= 0 }.maxOrNull() ?: -1
            var rest = if (idx >= 0) l.substring(idx) else ""
            rest = rest.replace(Regex("^\\s*(भेजो|भेज दो|भेजना|कर दो|करो|send|bhejo)?\\s*(कि|की|ki|that|:)?\\s*"), " ")
            rest = rest.replace(SEND_VERB, " ").trim()
            if (rest.isBlank()) {
                // "मैसेज" से पहले लिखा: "काटो और 'मैं ड्राइव कर रहा हूँ' मैसेज भेजो"
                val before = l.substring(0, (MSG_WORDS.map { l.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0))
                rest = before.replace(Regex("^.*?(काट दो|काट के|काटकर|काट कर|काटो|reject karo|cut karo|cut)\\s*(और|and|aur)?\\s*"), "")
                    .replace(Regex("\\s(का|की|के|वाला|ये|यह)\\s*$"), "").trim()
                if (CUT.any { rest.contains(it) }) rest = ""
            }
            // "काटो और मैसेज भेजो बाद में कॉल करूँगा वाला" जैसा = तैयार मैसेज; बाकी जो बोला वही
            if (rest.isBlank() && later) return Cmd(REJECT_BUSY)
            return Cmd(REJECT_MSG, rest.trim())
        }
        if (cut && later) return Cmd(REJECT_BUSY)
        val unm = has("अनम्यूट", "अन म्यूट", "unmute", "un mute", "म्यूट हटा", "म्यूट बंद", "mute hata", "mute band", "mute off", "माइक चालू कर", "आवाज़ जाने दो")
        if (unm) return Cmd(UNMUTE)
        if (has("म्यूट", "mute", "माइक बंद कर दो कॉल", "मेरी आवाज़ बंद")) return Cmd(MUTE)
        if (has("कॉल काट", "कॉल कट", "call cut", "call kaat", "end call", "call end", "कॉल ख़त्म", "कॉल खत्म", "कॉल बंद", "फोन रख", "फ़ोन रख", "hang up"))
            return Cmd(END)
        return null
    }

    /** काटकर भेजने का मैसेज ठीक है? (बहुत छोटा/सिर्फ़ "हाँ" नहीं) */
    fun usableText(t: String): Boolean = t.trim().length >= 2 && !Regex("^(हाँ|हां|ना|नहीं|ok|yes|no)$").matches(t.trim().lowercase())

    // ------------------------------------------------------------ बोलने वाली बात (शुद्ध)

    /** "सर, राहुल का WhatsApp कॉल आ रहा है।" - नाम नहीं तो नंबर, वह भी नहीं तो "अनजान नंबर" */
    fun announce(who: String, app: String = "", video: Boolean = false): String {
        val w = who.trim()
        val kind = (if (app.isBlank()) "" else "$app ") + (if (video) "वीडियो कॉल" else "कॉल")
        if (w.isNotBlank()) return "सर, $w का $kind आ रहा है।"
        return if (app.isBlank()) "सर, अनजान नंबर से कॉल आ रहा है।" else "सर, $app पर किसी का ${if (video) "वीडियो कॉल" else "कॉल"} आ रहा है।"
    }
}
