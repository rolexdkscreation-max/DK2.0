package com.dk.assistant

/**
 * v3.7 (मालिक का नया नियम - पुराने "छोटा ठीक है, कर रहा हूँ" की जगह):
 *  1. फ़ोन का कोई भी काम कहा (WhatsApp मैसेज/कॉल, YouTube वीडियो, Flipkart खोज, अलार्म, टॉर्च…) =
 *     काम से पहले DK कुछ नहीं बोलता. काम हो जाने के बाद ही "हो गया सर" + "और कुछ करना है सर?".
 *     कई काम = सब हो जाने के बाद ही.
 *  2. आम बातचीत में आवाज़ रोकनी नहीं - "लगा / चला / सेट / ऑन" जैसे शब्द आने भर से जवाब 2-8 सेकंड रुका रहता था (देरी).
 *     अब हुक्म न हो तो ज़्यादा से ज़्यादा [SOFT_HOLD_MS] (बस इतना कि झूठा "हो गया" पकड़ा जा सके).
 *  3. हुक्म में Gemini काम के लिए ज़रूरी सवाल पूछे ("किसे भेजूँ?") = तुरंत बोलो, रोको नहीं.
 * Pure - unit test से जाँचा.
 */
object TaskFirst {
    /** आम बात (हुक्म नहीं): इतनी देर (घड़ी से) में कोई "हो गया" दावा नहीं = बजा दो */
    const val SOFT_HOLD_MS = 900L
    /** हुक्म: इतनी देर में भी tool नहीं आया और दावा नहीं = Gemini बात कर रहा है (जैसे "यह नहीं हो सकता") - बजा दो */
    const val CMD_TALK_MS = 3_500L

    private val ASK = Regex(
        "\\?|？|किसे|किसको|किस को|किसका|कौन|कौनसा|कौन सा|कौन-सा|कितने बजे|कब का|क्या भेजूँ|क्या भेजूं|क्या लिखूँ|क्या लिखूं|" +
            "बताइए|बताइये|बताओ कि|which|whom|what time|who\\b",
        RegexOption.IGNORE_CASE)

    /** काम के लिए ज़रूरी सवाल? (रोके बिना बोलना है) */
    fun isAsk(text: String): Boolean = text.isNotBlank() && !HonestGuard.claimsDone(text) && ASK.containsMatchIn(text)

    /**
     * हुक्म वाले काम में tool आया - उससे पहले जो बोला (ठीक है / कर रहा हूँ / इधर-उधर की बात / झूठा "हो गया") = कुछ नहीं बजेगा.
     * सिर्फ़ ज़रूरी सवाल बचता है (वह भी आम तौर पर tool के बिना आता है).
     */
    fun beforeToolCommand(heldText: String): DoneGate.Verdict =
        if (isAsk(heldText)) DoneGate.Verdict.PLAY else DoneGate.Verdict.DROP

    /** रुकी आवाज़ अभी छोड़ दें? [command] = हुक्म और अभी tool नहीं आया, [heldMs] = रोकना शुरू हुए कितनी देर (घड़ी) */
    fun release(command: Boolean, heldMs: Long, heldText: String): Boolean {
        if (HonestGuard.claimsDone(heldText)) return false          // झूठा "हो गया" - रुका रहे (बारी के अंत में फ़ैसला)
        if (!command) return heldMs >= SOFT_HOLD_MS
        return isAsk(heldText) || heldMs >= CMD_TALK_MS
    }

    /** Live के prompt का आख़िरी हिस्सा - पहले के "छोटा ack" नियम से ऊपर */
    fun prompt(@Suppress("UNUSED_PARAMETER") female: Boolean): String {
        val done = "कर दिया"
        return "\n\nOWNER'S NEWEST RULE FOR PHONE TASKS (this replaces any earlier rule about a short acknowledgement before the work): " +
            "when the user asks you to DO anything on the phone - WhatsApp message or call, phone call, open YouTube / a video, search on Flipkart or any app, " +
            "alarm, torch, settings, anything - say NOTHING before the work, not even \"ठीक है\" or \"कर रहा हूँ\". Call phone_task (or the right tool) at once, silently. " +
            "Speak only AFTER the tool result: if it says DONE/PLAYING/SENT/COMPLETED, confirm in one short line like \"हो गया सर, $done\" and then ask " +
            "\"और कुछ करना है सर?\". If the user gave several tasks, do ALL of them first, then confirm once and ask. If it failed or is not verified, say that honestly. " +
            "Do not start any other talk or new topic before the task is finished. Only if the task truly cannot be done without an answer (e.g. whom to send to) ask that one short question. " +
            "The SPEED NOTE about starting with a short sentence does NOT apply to phone tasks - there the first thing you say is the result. " +
            "For normal conversation (no task) answer immediately, no waiting."
    }
}

/**
 * v3.7: "मेरी बात दिखी, DK का जवाब भी दिखा, पर आवाज़ नहीं आई" - बँटवारे वाला आवाज़ इंजन (Gemini TTS) सीमा/नेटवर्क से चुप रहा
 * और ऐप सिर्फ़ लिखता रहा. अब: उसी समय से [OFF_MS] तक Gemini Live की अपनी आवाज़ (नया connection) और छूटा जवाब दोबारा बोला जाता है.
 */
object SilentVoice {
    const val OFF_MS = 10L * 60 * 1000

    fun resayNote(text: String): String =
        "SYSTEM NOTE (this is not the user speaking): your previous reply was only shown on screen - the user could NOT hear it because the voice failed. " +
            "Say it again now, briefly and naturally, in the same language: \"${text.take(400).replace("\"", "'")}\". Do not mention any technical problem and do not call any tool for it."
}
