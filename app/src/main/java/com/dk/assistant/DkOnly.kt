package com.dk.assistant

/**
 * v4.1: "सिर्फ़ DK बोलने पर सुनो" (Settings, शुरू में बंद).
 * चालू हो तो हर वाक्य अलग से जाँचो: शुरुआत में "डीके / हे डीके / हेलो डीके / DK / D.K / डी के / दीके" हो तभी काम,
 * DK हटाकर बाकी बात = कमांड. नहीं तो वह बात पूरी तरह छोड़ो (न AI, न याददाश्त, न chat, न जवाब).
 * ढीला मिलान नहीं: "डीकेट", "दिखा", "decor" जैसे शब्द DK नहीं माने जाते.
 * Android import नहीं -> JVM test.
 */
object DkOnly {
    private const val ZW = "\u200B\u200C\u200D\uFEFF"
    private val GREET = "(?:(?:हे|हेय|है|हाय|हेलो|हैलो|हलो|ओके|ओ के|hey|hi|hello|helo|ok|okay)[\\s,.!।]*)?"
    // DK के रूप: डीके/डी के/डी.के/दीके/दी के/डीकेे, dk / d k / d.k / d. k / deekay
    private val NAME = "(?:डी\\s?\\.?\\s?के|दी\\s?\\.?\\s?के|डि\\s?के|डीके|d\\s?\\.?\\s?k\\.?|dee\\s?kay|deekay)"
    private val START = Regex("^\\s*$GREET$NAME(?=$|[\\s,.!?।:;-])[\\s,.!?।:;-]*", RegexOption.IGNORE_CASE)

    /** null = वाक्य DK से शुरू नहीं; वरना DK हटाकर बची हुई बात (खाली भी हो सकती है = सिर्फ़ "DK" बोला) */
    fun strip(text: String): String? {
        val t = text.filterNot { it in ZW }.trim()
        if (t.isEmpty()) return null
        val m = START.find(t) ?: return null
        return t.substring(m.range.last + 1).trim()
    }

    fun starts(text: String): Boolean = strip(text) != null

    /** लंबी बात को वाक्यों में (। ? ! पर; "D.K." वाले बिंदु पर नहीं) - हर वाक्य अलग जाँचने के लिए */
    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[।?!])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * पूरी बात में से सिर्फ़ DK वाले वाक्यों की कमांड (DK हटाकर). कोई नहीं = null (पूरी बात छोड़ो).
     * सिर्फ़ "DK" बोला (बाकी खाली) = "" (सुनने को तैयार, कोई काम नहीं).
     */
    fun filter(text: String): String? {
        val keep = sentences(text).mapNotNull { strip(it)?.trimEnd('।', '.', '!', ',', ' ') }
        if (keep.isEmpty()) return null
        return keep.filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Live (Gemini) के लिए - फ़ोन पहले से सिर्फ़ DK वाली आवाज़ भेजता है, यह दूसरी सुरक्षा */
    const val PROMPT = "\nDK-ONLY LISTENING IS ON: the user starts every request with your name \"DK\" (डीके / हे डीके / हेलो डीके). " +
        "\"DK\" is only your name, not part of the request - act on the words after it. If what you hear does NOT start with DK " +
        "(other people talking, TV, a video, the user talking to someone else), stay completely silent: no reply, no tool call, nothing. " +
        "The only exception is an incoming or just-ended phone call: then call commands (answer, reject, speaker, mute) work without DK.\n"

    /** setting चालू नहीं = बात जैसी थी वैसी; चालू = [filter] */
    fun gate(on: Boolean, text: String): String? = if (!on) text else filter(text)
}
