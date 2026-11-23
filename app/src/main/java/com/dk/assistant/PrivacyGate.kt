package com.dk.assistant

import org.json.JSONObject

/**
 * 23 नवंबर 2026 (मालिक: "बिना पूछे गैलरी / स्क्रीन नहीं देखनी"): DK यूज़र के कहे बिना और उसकी साफ़ "हाँ" के बिना
 * गैलरी, स्क्रीन, कैमरा, फ़ाइलें या फ़ोन की कोई निजी चीज़ नहीं देखता.
 *
 *  - [RULE]: DK के हर सिस्टम प्रॉम्प्ट में (BrainManager के सारे prompt + Live).
 *  - कोड का गेट (सिर्फ़ प्रॉम्प्ट पर भरोसा नहीं): [kind] बताता है कि tool call निजी चीज़ देखेगा; [check] फ़ैसला करता है.
 *    पहली बार = [Gate.Ask] (कुछ नहीं देखा, AI पूछे "क्या मैं ये देखूँ?" और स्क्रीन पर हाँ / नहीं का कार्ड).
 *    पूछने के *बाद* यूज़र की अपनी आवाज़ में साफ़ "हाँ" या कार्ड पर "हाँ" = [Gate.Run]. "नहीं" = [Gate.Deny]. चुप्पी = [Gate.NotHeard].
 *  - अनुमति सिर्फ़ उसी एक काम के लिए: [Gate.Run] होते ही pending मिट जाता है - अगली बार फिर पूछा जाता है.
 * pure हिस्सा JVM test में ([PrivacyGateTest]).
 */
object PrivacyGate {
    /** सिस्टम प्रॉम्प्ट का नियम (मालिक के शब्द, ज्यों के त्यों) */
    const val RULE = "PRIVACY RULE (most important, never break it): " +
        "यूज़र के कहे बिना तुम कभी भी गैलरी (फ़ोटो/वीडियो), स्क्रीन, कैमरा, फ़ाइलें या फ़ोन की कोई भी निजी चीज़ अपने आप नहीं देखोगे और न पढ़ोगे। " +
        "जब यूज़र ऐसा करने को कहे (जैसे 'गैलरी देखो', 'स्क्रीन देखकर बताओ', 'WhatsApp पर ये भेजो और देखकर बताओ'), तब काम शुरू करने से पहले एक बार ज़रूर पूछो: 'क्या मैं ये देखूँ?' " +
        "जब यूज़र साफ़ 'हाँ' बोले, तभी देखना शुरू करो। 'नहीं' या चुप्पी का मतलब है नहीं देखना। " +
        "यह अनुमति सिर्फ़ उसी एक काम के लिए है, अगले काम के लिए फिर से पूछो। " +
        "बिना अनुमति कुछ भी देखा हुआ याद मत रखो और किसी और को मत बताओ।"

    /** यूज़र से पूछा जाने वाला सवाल */
    const val QUESTION = "क्या मैं ये देखूँ?"

    enum class Kind(val hi: String) {
        SCREEN("आपकी स्क्रीन"), GALLERY("आपकी गैलरी (फ़ोटो/वीडियो)"), CAMERA("कैमरा"), CHAT("आपकी चैट"), FILES("आपकी फ़ाइलें")
    }

    /** WhatsApp के वे काम जो चैट / स्क्रीन पढ़ते हैं */
    val WA_READ = setOf("read_unread", "unread", "read", "screen_chats", "chats", "screen", "open_chat", "read_chat_screen",
        "smart_reply", "reply_for_me", "reply")

    /** pure: यह tool call कौन-सी निजी चीज़ देखेगा (null = कुछ निजी नहीं, बिना पूछे चलो) */
    fun kind(tool: String, args: JSONObject): Kind? = when (tool) {
        "look_screen", "read_screen" -> Kind.SCREEN
        "screen_share" -> if (args.optBoolean("on", true)) Kind.SCREEN else null
        "camera" -> if (args.optBoolean("on", true)) Kind.CAMERA else null
        "look_photo" -> Kind.GALLERY
        "read_chat_screen" -> Kind.CHAT
        "whatsapp_task" -> if (args.optString("action").trim().lowercase() in WA_READ) Kind.CHAT else null
        "social_media_task" -> if (args.optString("media_query").isNotBlank() || args.optString("media").trim().lowercase() == "latest_gallery") Kind.GALLERY else null
        else -> null
    }

    data class Pending(val kind: Kind, val tool: String, val askedAt: Long)

    sealed class Gate {
        object Run : Gate()
        object Ask : Gate()
        object Deny : Gate()
        object NotHeard : Gate()
    }

    const val WINDOW_MS = 2 * 60_000L

    /**
     * pure: [pending] = पहले पूछा गया सवाल; [userAt]/[userText] = यूज़र की आख़िरी बोली हुई बात;
     * [tapAt]/[tapYes] = कार्ड पर आख़िरी टैप (0 = नहीं दबाया).
     */
    fun check(kind: Kind, tool: String, pending: Pending?, userAt: Long, userText: String, tapAt: Long, tapYes: Boolean, now: Long): Gate {
        if (pending == null || pending.kind != kind || pending.tool != tool || now - pending.askedAt !in 0..WINDOW_MS) return Gate.Ask
        // कार्ड का टैप और आवाज़ - जो पूछने के बाद सबसे आख़िर में आया वही फ़ैसला
        val tapAfter = tapAt > pending.askedAt
        val voiceAfter = userAt > pending.askedAt
        if (tapAfter && (!voiceAfter || tapAt >= userAt)) return if (tapYes) Gate.Run else Gate.Deny
        if (voiceAfter) {
            val t = userText.trim().removeSuffix("…").trim()
            if (com.dk.assistant.agent.core.ConfirmGate.isYes(t)) return Gate.Run
            if (com.dk.assistant.plus.ConfirmGate.Rules.isNo(t)) return Gate.Deny
        }
        return Gate.NotHeard
    }

    fun askNote(kind: Kind): String =
        "PRIVACY_ASK: NOTHING was seen or read yet (${kind.name.lowercase()}). The user's privacy rule: before looking at ${kind.hi} you MUST ask him first. " +
            "Ask exactly in his language, one short line: \"$QUESTION\" (say what you will look at: ${kind.hi}). A हाँ / नहीं card is also on his screen. " +
            "Then WAIT. Only after HE HIMSELF clearly says yes (or taps हाँ), call this same tool again. 'नहीं' or silence = do not look. " +
            "The app checks his own words and taps - never call it again without his yes."

    fun denyNote(kind: Kind): String =
        "PRIVACY_DENIED: the user said no - ${kind.hi} was NOT looked at and nothing was read. Say in one short line that you did not look. Do not ask again for this task."

    const val NOT_HEARD = "PRIVACY_NOT_HEARD: the app did not hear the user himself clearly say yes after your question, so nothing was looked at. " +
        "Ask him again in one short line (\"$QUESTION\") and wait. Never say you have looked."

    // ------------------------------------------------------------------ हालत (Live सत्र भर)
    @Volatile var pending: Pending? = null
        private set
    @Volatile var tapAt = 0L
        private set
    @Volatile var tapYes = false
        private set
    /** कार्ड दिखाने वाला (DKApp में जुड़ता है) - null = सिर्फ़ आवाज़ में पूछना */
    @Volatile var showCard: ((Kind, (Boolean) -> Unit) -> Unit)? = null
    /** कार्ड पर टैप हुआ - Live को बताने वाला (GeminiLive जोड़ता है) */
    @Volatile var onTap: ((Kind, String, Boolean) -> Unit)? = null

    /** कार्ड पर यूज़र का टैप */
    fun tap(yes: Boolean, now: Long = System.currentTimeMillis()) {
        tapAt = now; tapYes = yes
        val p = pending ?: return
        try { onTap?.invoke(p.kind, p.tool, yes) } catch (e: Exception) { Quiet.note(e) }
    }

    /**
     * Live / agent के tool से ठीक पहले. null = यूज़र ने इसी काम के लिए हाँ कहा (या कुछ निजी नहीं), चलाओ.
     * वरना AI को लौटने वाली लाइन - tool **नहीं** चला.
     */
    fun gate(tool: String, args: JSONObject, now: Long = System.currentTimeMillis()): String? {
        val k = kind(tool, args) ?: return null
        return when (check(k, tool, pending, DKState.lastUserAt, DKState.lastUser, tapAt, tapYes, now)) {
            Gate.Run -> { pending = null; DKLog.d("निजता: $tool - यूज़र ने हाँ कहा, सिर्फ़ इसी काम के लिए"); null }
            Gate.Deny -> { pending = null; DKLog.d("निजता: $tool - यूज़र ने ना कहा, नहीं देखा"); denyNote(k) }
            Gate.NotHeard -> { DKLog.d("निजता: $tool - साफ़ हाँ नहीं सुनी, नहीं देखा"); NOT_HEARD }
            Gate.Ask -> {
                pending = Pending(k, tool, now)
                DKLog.d("निजता: $tool - पहले पूछा \"$QUESTION\"")
                try { showCard?.invoke(k) { yes -> tap(yes) } } catch (e: Exception) { Quiet.note(e) }
                askNote(k)
            }
        }
    }

    // ------------------------------------------------------------------ बिना AI वाले रास्ते (सीधी आवाज़-कमांड / agent loop)
    private val grants = java.util.concurrent.ConcurrentHashMap<Kind, Long>()
    const val GRANT_MS = 60_000L

    /** pure: [at] पर मिली हाँ अभी ([now]) भी उसी काम के लिए मान्य? */
    fun grantValid(at: Long?, now: Long): Boolean = at != null && now - at in 0..GRANT_MS

    /** इसी काम की हाँ मिली है? हाँ = true और हाँ ख़र्च (अगले काम के लिए फिर पूछना) */
    fun consumeGrant(kind: Kind, now: Long = System.currentTimeMillis()): Boolean {
        val ok = grantValid(grants.remove(kind), now)
        if (ok) DKLog.d("निजता: ${kind.name} - यूज़र की हाँ, सिर्फ़ इसी काम के लिए")
        return ok
    }

    /** हाँ / नहीं का कार्ड; हाँ = [run] (एक बार की अनुमति के साथ). false = कार्ड नहीं दिख सका (कुछ नहीं देखा) */
    fun askThen(kind: Kind, run: () -> Unit): Boolean {
        val show = showCard ?: return false
        return try {
            show(kind) { yes ->
                if (yes) { grants[kind] = System.currentTimeMillis(); run() } else DKLog.d("निजता: ${kind.name} - यूज़र ने ना कहा")
            }
            true
        } catch (e: Exception) { Quiet.note(e); false }
    }

    /** agent loop में (आवाज़ से पूछ सकता है): सवाल की लाइन */
    fun askLine(kind: Kind): String = "$QUESTION (${kind.hi}) हाँ या नहीं बोलिए।"

    /** test / नया सत्र */
    fun reset() { pending = null; tapAt = 0L; tapYes = false; grants.clear() }
}
