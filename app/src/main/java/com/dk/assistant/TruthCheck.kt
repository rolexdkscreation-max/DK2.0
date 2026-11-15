package com.dk.assistant

/**
 * 15 नवंबर 2026 (मालिक): "जो काम नहीं हुआ, फिर भी बोलता है हाँ हो गया; कोई कॉल नहीं आ रहा, फिर भी बोलता है कॉल आ रहा है.
 * जाँच करे, दोबारा देखे - 'मैं फिर से चेक करके देखता हूँ' - हुआ हो तभी हाँ, ना हो तो ना. कोई झूठा दावा नहीं."
 *
 * जड़ (पहले):
 *  1. कॉल ख़त्म (कटी / missed / किसी और ने उठाई) होने पर DK लाइव को कभी बताया ही नहीं जाता था - उसकी याद में
 *     "कॉल बज रहा है" रह जाता था, बाद में फिर बोल देता था "कॉल आ रहा है".
 *  2. ऐप का "Missed call" / "छूटी हुई कॉल" वाला notification भी (बटन हों तो) कॉल माना जा सकता था.
 *  3. फ़ोन का अपना नतीजा "(not confirmed on screen)" / "पक्का नहीं" को भी [LiveFast.ok] = हो गया मानता था.
 *  4. कॉल काटना / स्पीकर - बटन दबते ही "कर दिया", बाद में देखा ही नहीं कि सच में हुआ.
 *
 * यहाँ सिर्फ़ शुद्ध नियम (Android import नहीं -> JVM test).
 */
object TruthCheck {
    /** फ़ोन ने काम किया पर पक्का नहीं हुआ (दो बार देखा, नहीं दिखा) */
    const val NOT_CONFIRMED = "NOT_CONFIRMED"

    fun notConfirmed(r: String): Boolean {
        val t = r.trim(); val l = t.lowercase()
        return t.startsWith(NOT_CONFIRMED) || l.contains("(not confirmed") || l.contains("not verified") || l.startsWith("unverified") ||
            l.contains("could not confirm")
    }

    /** नतीजा साफ़ "नहीं हुआ" बताता है (अंग्रेज़ी / हिंदी) - DoneGate के पुराने शब्दों के अलावा */
    fun saysFailed(r: String): Boolean {
        val l = r.trim().lowercase()
        if (l.isEmpty()) return false
        return notConfirmed(r) || l.startsWith("not done") || l.startsWith("timeout") || l.contains("timed out") ||
            l.startsWith("permission") || l.contains("permission denied") || l.contains("permission is missing") || l.contains("no permission") ||
            l.startsWith("unable") || l.startsWith("couldn't") || l.startsWith("cannot") || l.startsWith("can't") ||
            l.startsWith("off:") || l.contains("feature is off") || l.contains("is turned off in settings") ||
            Regex("नहीं (हुआ|हुई|हो पाया|हो पाई|हो सका|हो सकी|खुला|खुली|मिला|मिली|कटी|कटा|उठी|उठा|गया|गई|भेजा|भेजी|चला|चली)").containsMatchIn(l)
    }

    // ------------------------------------------------------------------ कॉल का झूठा दावा

    private val CALL_CLAIM = Regex(
        "(कॉल|काल|फ़ोन|फोन|call|phone)\\s*(अभी\\s*)?(आ\\s*रहा|आ\\s*रही|बज\\s*रहा|बज\\s*रही|aa\\s*raha|aa\\s*rahi|baj\\s*raha)" +
            "|(is|are)\\s+calling(\\s+you)?|incoming\\s+call|कॉल\\s+कर\\s+रह(ा|ी|े)\\s+(है|हैं)",
        RegexOption.IGNORE_CASE
    )
    private val CALL_NOT = Regex("नहीं|नही|(^|\\s)(ना|न)(\\s|$)|कोई\\s+कॉल\\s+नहीं|\\bno\\b|\\bnot\\b|\\bnobody\\b|(^|\\s)(था|थी|थे)(\\s|$|[,।.!])|आया था|आई थी|missed|छूट|क्या|kya", RegexOption.IGNORE_CASE)

    /** DK की बात में "अभी कॉल आ रहा है" का दावा? (हर वाक्य अलग; "कोई कॉल नहीं आ रहा" / "आया था" दावा नहीं) */
    fun claimsCall(reply: String): Boolean =
        reply.split(Regex("(?<=[।.!?\\n])")).any { s -> CALL_CLAIM.containsMatchIn(s) && !CALL_NOT.containsMatchIn(s) }

    /** कॉल ख़त्म होने के बाद भी इतनी देर तक "कॉल आ रहा था" वाली बात रास्ते में हो सकती है - तब मत रोको */
    const val CALL_GRACE_MS = 4_000L

    /** यह जवाब झूठा कॉल-दावा है: न घंटी, न चलती कॉल, न अभी-अभी कॉल की बात */
    fun falseCall(reply: String, ringing: Boolean, inCall: Boolean, lastCallNoteAt: Long, lastCallGoneAt: Long, now: Long): Boolean {
        if (ringing || inCall) return false
        if (!claimsCall(reply)) return false
        if (lastCallGoneAt > 0 && now - lastCallGoneAt < CALL_GRACE_MS) return false
        // घंटी की ख़बर भेजी और अभी गई नहीं (कॉल ख़त्म का पता नहीं चला) - तब भी 75 सेकंड से पुरानी = झूठ
        return !(lastCallNoteAt > lastCallGoneAt && now - lastCallNoteAt < 75_000L)
    }

    enum class CallEnd { ANSWERED, ENDED }

    /** DK लाइव को चुपचाप बताओ (जवाब नहीं बनाता): कॉल अब नहीं बज रहा */
    fun callGoneNote(who: String, how: CallEnd): String {
        val name = who.ifBlank { "the caller" }
        return when (how) {
            CallEnd.ANSWERED -> "UPDATE: the call from \"$name\" is no longer ringing - it was picked up and is now connected (in progress). " +
                "Do NOT say a call is ringing/coming any more."
            CallEnd.ENDED -> "UPDATE: the call from \"$name\" has STOPPED ringing (it was cut, missed or ended). There is NO call ringing now. " +
                "Never say that a call is coming/ringing unless a NEW SYSTEM NOTE says a call is ringing RIGHT NOW."
        }
    }

    fun falseCallNote(said: String): String =
        "SYSTEM NOTE (this is not the user speaking): you started saying \"${said.take(120)}\" - but NO call is ringing on the phone right now " +
            "(DK checked the phone's real call state), so DK stopped that line. Say honestly in one short line that there is no call right now " +
            "(e.g. \"माफ़ करना बॉस, अभी कोई कॉल नहीं आ रहा\"), then continue normally. Only say a call is ringing after a SYSTEM NOTE says so."

    // ------------------------------------------------------------------ ऐप कॉल के notification

    private val MISSED = Regex("(?i)missed|छूटी|छूटा|मिस्ड|मिस कॉल|मिस्ड कॉल|no answer|unanswered|call ended|कॉल समाप्त हुई|declined|अस्वीकार किया")

    /** "Missed voice call" / "छूटी हुई कॉल" - यह बजती कॉल नहीं */
    fun missedCall(title: String, text: String): Boolean = MISSED.containsMatchIn(title) || MISSED.containsMatchIn(text)

    /** इतना पुराना notification (DK बाद में जुड़ा / फिर से पढ़ा) बजती कॉल नहीं - घंटी 60s से ज़्यादा नहीं चलती */
    const val RING_FRESH_MS = 60_000L
    fun freshRing(postedAt: Long, now: Long): Boolean = postedAt <= 0L || now - postedAt in -5_000L..RING_FRESH_MS

    // ------------------------------------------------------------------ दोबारा जाँच

    /** काम सच में हुआ? पहली बार न दिखे तो एक बार और (थोड़ा रुककर) - दोनों बार नहीं = पक्का नहीं */
    suspend fun twice(firstMs: Long, againMs: Long, check: suspend (Long) -> Boolean): Pair<Boolean, Boolean> {
        if (check(firstMs)) return true to false
        return check(againMs) to true
    }

    /** बिना-Live आवाज़: "पक्का नहीं" वाले नतीजे पर सच्चा वाक्य (कभी "हो गया" नहीं) */
    fun honestSay(type: String, r: String): String {
        val detail = r.trim().let { if (it.startsWith(NOT_CONFIRMED)) it.removePrefix(NOT_CONFIRMED).trimStart(':', ' ', '-') else "" }
        if (detail.isNotBlank()) return "मैंने दोबारा चेक किया - $detail"
        return when (type) {
            "open_app" -> "मैंने दोबारा चेक किया - ऐप सामने नहीं दिखा। एक बार स्क्रीन देख लीजिए।"
            else -> "मैंने दोबारा चेक किया - काम पक्का नहीं हुआ। एक बार फ़ोन पर देख लीजिए।"
        }
    }

    /** Live prompt: सच्चाई के पक्के नियम (कॉल + काम) */
    const val PROMPT = "\nTRUTH (very important, the owner's rule): never claim anything you have not seen in a tool result or SYSTEM NOTE. " +
        "CALLS: say a call is ringing/coming ONLY right after a SYSTEM NOTE says a call is ringing RIGHT NOW; after an UPDATE says it stopped, it is over. " +
        "If the user asks whether someone is calling, call phone_call with action=who and say only what it returns. " +
        "TASKS: if a result says NOT_CONFIRMED / not verified / failed / still running, it is NOT done - say so honestly " +
        "(e.g. \"मैंने दोबारा चेक किया बॉस, अभी हुआ नहीं\"). If you are unsure whether something worked, say \"एक सेकंड, मैं फिर से चेक करता हूँ\" " +
        "and call the right tool (task_status / the same tool) again - say \"हो गया\" only when a result really says DONE/verified, otherwise say no."
}
