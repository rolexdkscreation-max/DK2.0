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
    /**
     * सिस्टम प्रॉम्प्ट का नियम. 23 नवंबर 2026 (मालिक, दूसरी बार): "मैंने खुद साफ़ बोला है तो पूछने की ज़रूरत नहीं -
     * गलती से सुना हो या कहीं लिखा / पढ़ा देखा हो, तभी पूछे. हाँ कहने के बाद एक बार में काम."
     */
    const val RULE = "PRIVACY RULE: " +
        "बिना कहे गैलरी, स्क्रीन, कैमरा, चैट या कोई निजी चीज़ मत देखो, पीछे से कभी स्कैन नहीं। " +
        "यूज़र ने खुद साफ़ कहा ('WhatsApp चेक करो', 'DK लेंस खोलो', शॉपिंग में 'कार्ट में ऐड करो') = दोबारा मत पूछो, सीधे करो। " +
        "गैलरी की फ़ोटो / वीडियो = हर बार पहले एक बार ज़रूर पूछो, चाहे उसने खुद कहा हो। " +
        "गलती से सुना लगे या बात किसी मैसेज/स्क्रीन में लिखी दिखे, तभी एक बार पूछो 'क्या मैं ये देखूँ?'; " +
        "'हाँ / ठीक है / देख लो' पर पूरा काम एक बार में, फिर से मत पूछो; 'नहीं' या चुप्पी = मत देखो। " +
        "अनुमति सिर्फ़ उसी एक काम की। बिना अनुमति देखा याद मत रखो। " +
        "अपना काम ('मैप/गाना/Flipkart पे सर्च करो', 'स्क्रॉल करो', 'पसंद नहीं, और दिखाओ') = बिना पूछे स्क्रीन देखो, नतीजे बताओ, अगला कदम पूछो। " +
        "DK लेंस खुद खोला = चालू रहने तक बिना पूछे देखो, बताओ।"

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
    /** यूज़र का अपना साफ़ हुक्म इतनी देर तक उसी काम का माना जाता है */
    const val EXPLICIT_MS = 90_000L
    /** हाँ / हुक्म के बाद एक ही काम के कई कदम (जैसे WhatsApp खोलो -> चैट पढ़ो) - इतनी देर, जब तक यूज़र नई बात न कहे */
    const val TASK_MS = 3 * 60_000L

    private val NOUNS: Map<Kind, List<String>> = mapOf(
        Kind.SCREEN to listOf("स्क्रीन", "स्क्रिन", "screen"),
        Kind.GALLERY to listOf("गैलरी", "गेलरी", "gallery", "galary", "फ़ोटो", "फोटो", "photo", "तस्वीर", "pic", "वीडियो", "video", "इमेज", "image"),
        Kind.CAMERA to listOf("कैमरा", "camera", "लेंस", "लैंस", "लेन्स", "lens", "स्कैन", "scan"),
        Kind.CHAT to listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "वॉट्सएप", "चैट", "chat", "मैसेज", "मेसेज", "message", "msg", "unread", "इनबॉक्स", "inbox"),
        Kind.FILES to listOf("फ़ाइल", "फाइल", "file", "डॉक्यूमेंट", "document"))
    private val VERBS = listOf("देख", "दिखा", "चेक", "पढ़", "पढ", "बता", "खोल", "ओपन", "स्कैन", "शेयर", "सुना", "जवाब", "चालू",
        "dekh", "dikha", "check", "padh", "read", "bata", "khol", "open", "scan", "share", "suna", "reply", "see", "look", "show", "chalu")
    private val NEG = setOf("मत", "नहीं", "नही", "ना", "mat", "nahi", "nahin", "nai", "don't", "dont", "never", "no")
    private val YES_EXTRA = listOf("हाँ", "हां", "ठीक", "चलो", "ज़रूर", "जरूर", "बिल्कुल", "बिलकुल", "कर लो", "कर दो",
        "haan", "han", "ha", "theek", "thik", "ok", "okay", "chalo", "jarur", "zaroor", "bilkul", "yes", "sure", "kar lo", "kar do")

    private fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{M}']+")).filter { it.isNotEmpty() }
    private fun has(t: String, w: List<String>, stem: String): Boolean {
        val s = stem.lowercase()
        return if (s.any { it in 'a'..'z' }) (if (s.contains(' ')) " ${w.joinToString(" ")} ".contains(" $s") else w.any { it.startsWith(s) })
        else t.contains(s)
    }

    /**
     * pure: यूज़र की अपनी बात [text] इसी [kind] को देखने / पढ़ने का साफ़ हुक्म है? ("गैलरी देखो", "WhatsApp पे जाके चेक करो",
     * "DK लेंस ओपन करो"). "मत" / "नहीं" वाली बात कभी हुक्म नहीं.
     */
    fun asksFor(kind: Kind, text: String): Boolean {
        val t = text.trim().removeSuffix("…").trim().lowercase()
        if (t.isEmpty()) return false
        val w = words(t)
        if (w.any { it in NEG }) return false
        return NOUNS[kind].orEmpty().any { has(t, w, it) } && VERBS.any { has(t, w, it) }
    }

    /** pure: "क्या मैं ये देखूँ?" के जवाब में हाँ? ("हाँ", "ठीक है देख लो", "ये देखो", "ok कर लो") - सवाल / ना वाली बात कभी नहीं */
    fun isYesAnswer(text: String): Boolean {
        val t = text.trim().removeSuffix("…").trim()
        if (t.isEmpty() || t.contains('?')) return false
        if (com.dk.assistant.agent.core.ConfirmGate.isYes(t)) return true
        val low = t.lowercase(); val w = words(low)
        if (w.isEmpty() || w.size > 8 || w.any { it in NEG }) return false
        return YES_EXTRA.any { has(low, w, it) && (it.any { c -> c !in 'a'..'z' } || it.contains(' ') || it in w) } || VERBS.any { has(low, w, it) }
    }

    /** pure: पूछने ([askedAt]) के बाद की यूज़र की बातें [said] (समय के साथ) + कार्ड का टैप - सबसे आख़िरी साफ़ जवाब */
    fun answerAfter(askedAt: Long, said: List<Pair<String, Long>>, tapAt: Long, tapYes: Boolean): Gate {
        var best: Gate = Gate.NotHeard; var at = askedAt
        for ((t, time) in said) {
            if (time <= askedAt || time < at) continue
            val g = when { isYesAnswer(t) -> Gate.Run; com.dk.assistant.plus.ConfirmGate.Rules.isNo(t) -> Gate.Deny; else -> null } ?: continue
            best = g; at = time
        }
        if (tapAt > askedAt && tapAt >= at) best = if (tapYes) Gate.Run else Gate.Deny
        return best
    }

    /**
     * pure फ़ैसला. [explicit] = यूज़र ने खुद अभी यही कहा; [granted] = इसी काम की हाँ पहले मिल चुकी.
     * [pending] = पहले पूछा गया सवाल (किसी भी tool से, बस वही निजी चीज़); [said] = यूज़र की हाल की बातें.
     */
    fun check(kind: Kind, explicit: Boolean, granted: Boolean, pending: Pending?, said: List<Pair<String, Long>>, tapAt: Long, tapYes: Boolean, now: Long): Gate {
        if (explicit || granted) return Gate.Run
        if (pending == null || pending.kind != kind || now - pending.askedAt !in 0..WINDOW_MS) return Gate.Ask
        return answerAfter(pending.askedAt, said, tapAt, tapYes)
    }

    /** pure: हाँ / हुक्म [at] पर मिला था; [lastSaidAt] = यूज़र की आख़िरी पूरी बात. अभी भी वही काम? (नई बात = नया काम) */
    fun taskGrantValid(at: Long?, lastSaidAt: Long, now: Long): Boolean = at != null && now - at in 0..TASK_MS && lastSaidAt <= at + 5_000

    fun askNote(kind: Kind): String =
        "PRIVACY_ASK: NOTHING was seen or read yet (${kind.name.lowercase()}). The user's privacy rule: before looking at ${kind.hi} you MUST ask him first. " +
            "Ask exactly in his language, one short line: \"$QUESTION\" (say what you will look at: ${kind.hi}). A हाँ / नहीं card is also on his screen. " +
            "Ask only ONCE, then WAIT quietly. When HE HIMSELF says yes (हाँ / ठीक है / देख लो / ये देखो, or taps हाँ), call this tool again right away and finish the whole task in one go - do not ask again. " +
            "'नहीं' or silence = do not look. The app checks his own words and taps."

    fun denyNote(kind: Kind): String =
        "PRIVACY_DENIED: the user said no - ${kind.hi} was NOT looked at and nothing was read. Say in one short line that you did not look. Do not ask again for this task."

    const val NOT_HEARD = "PRIVACY_NOT_HEARD: you already asked and his answer has not reached the app yet, so nothing was looked at. " +
        "Do NOT ask the question again and do not keep talking - stay quiet and wait for his reply. " +
        "As soon as he says yes (हाँ / ठीक है / देख लो / ये देखो) call this tool again once and do the whole task. Never say you have looked."

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

    /** इसी काम की हाँ / हुक्म (निजी चीज़ -> कब मिली). एक काम के कई कदम बिना दोबारा पूछे */
    private val taskGrants = java.util.concurrent.ConcurrentHashMap<Kind, Long>()

    private fun lastSaidAt(): Long = DKState.userTurns().lastOrNull()?.second ?: 0L

    /** यूज़र की अपनी ताज़ा बात (बोली या लिखी) इसी चीज़ को देखने का साफ़ हुक्म है? */
    fun explicitNow(kind: Kind, now: Long = System.currentTimeMillis()): Boolean =
        DKState.lastUserAt > 0 && now - DKState.lastUserAt in 0..EXPLICIT_MS &&
            (asksFor(kind, DKState.lastUser) || (kind == Kind.CAMERA && lensSaid(DKState.lastUser)))

    /** 28 नवंबर 2026 (मालिक): "DK लेंस ओपन करो" खुद कहा = तुरंत खोलो, कुछ मत पूछो ([LensCmd] वाली बात) */
    fun lensSaid(text: String): Boolean = LensCmd.parse(text)?.on == true

    /**
     * 28 नवंबर 2026 (मालिक: "लेंस खोला तो बार-बार मत पूछो"): यूज़र का खोला DK लेंस चालू है = उस पूरे सत्र में कैमरा देखना
     * उसी की हाँ में ([LiveVision] भरता है). लेंस बंद = फिर से पुराना नियम.
     */
    @Volatile var lensOn = false

    /**
     * 25 नवंबर 2026 (मालिक): "गैलरी देखने को बोला जाए, कहीं से भी कमांड मिले, तो कन्फर्म एक बार ज़रूर करेगा" = गैलरी में खुद कहना भी
     * काफ़ी नहीं, एक बार हाँ ज़रूर. शॉपिंग ऐप पर "कार्ट में ऐड करो / आगे प्रोसेस" जैसा हुक्म = स्क्रीन देखना उसी हुक्म में शामिल.
     */
    fun explicitOk(kind: Kind, text: String): Boolean = when (kind) {
        Kind.GALLERY -> false
        Kind.SCREEN -> asksFor(kind, text) || screenTask(text)
        Kind.CAMERA -> asksFor(kind, text) || lensSaid(text)
        else -> asksFor(kind, text)
    }

    private val SCREEN_TASK = listOf("कार्ट", "कार्ड में", "cart", "ऐड कर", "ऐड टू", "add kar", "add to", "आगे प्रोसेस", "प्रोसीड", "proceed",
        "ऑर्डर कर", "ऑर्डर प्लेस", "order kar", "place order", "चेकआउट", "checkout", "कैश ऑन डिलीवरी", "cash on delivery", "बटन दबा", "दबा दो",
        "क्लिक कर", "click", "टैप कर", "tap kar", "सेलेक्ट कर", "select kar", "साइज़ चुन", "साइज चुन")

    /** pure: स्क्रीन पर कुछ करने का साफ़ हुक्म (शॉपिंग में बटन दबाना) - तब स्क्रीन देखना उसी हुक्म का हिस्सा */
    fun screenTask(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        // 28 नवंबर 2026 (मालिक): "नहीं, ये पसंद नहीं आ रहा - और दिखाओ / नीचे स्क्रॉल करो" = आगे देखने का हुक्म (मना नहीं)
        if (words(noCheck(t)).any { it in NEG }) return DISLIKE.any { t.contains(it) } && words(t).none { it == "मत" || it == "mat" } && STOP_WORDS.none { t.contains(it) }
        return SCREEN_TASK.any { t.contains(it) } || appFlow(t)
    }

    /**
     * 28 नवंबर 2026 (मालिक): "मैप पे X सर्च करो और देखो है या नहीं", "पवन सिंह का सॉन्ग सर्च करो", "Flipkart पे X सर्च करो",
     * "नीचे स्क्रॉल करो", "पहला वाला प्ले करो" - यूज़र का अपना काम = स्क्रीन पर आया नतीजा पढ़ना उसी काम में (बिना पूछे).
     */
    private val FLOW_VERB = listOf("सर्च", "search", "खोज", "ढूँढ", "ढूंढ", "ढूढ", "स्क्रॉल", "scroll", "प्ले", "play", "चला दो", "चलाओ",
        "नीचे कर", "नीचे जा", "ऊपर कर", "और दिखा", "दूसरा दिखा", "अगला दिखा", "है या नहीं", "है कि नहीं")
    private val FLOW_PLACE = listOf("मैप", "map", "नक्शे", "flipkart", "फ्लिपकार्ट", "फ़्लिपकार्ट", "amazon", "अमेज़न", "अमेजन", "meesho", "मीशो",
        "myntra", "मिंत्रा", "youtube", "यूट्यूब", "यूटूब", "गाना", "गाने", "सॉन्ग", "song", "वीडियो", "video", "रिजल्ट", "result", "आइटम", "item",
        "प्रोडक्ट", "product", "स्क्रॉल", "scroll", "नीचे", "पहला", "दूसरा", "तीसरा", "वाला")
    private val DISLIKE = listOf("पसंद नहीं", "पसंद नही", "अच्छा नहीं", "अच्छा नही", "और दिखा", "दूसरा दिखा", "आगे दिखा", "नीचे स्क्रॉल", "स्क्रॉल कर", "नीचे कर")
    /** "है या नहीं" = जाँच का सवाल, मना नहीं */
    private fun noCheck(t: String) = t.replace(Regex("(है )?(या|कि|ki|ya) (नहीं|नही|nahi|nahin)"), " ")
    private val STOP_WORDS = listOf("रुको", "रुक जा", "बंद कर", "stop", "स्क्रीन नहीं")

    /** pure: ऐप में खोज / स्क्रॉल / चलाना वाला यूज़र का हुक्म ("मैप पे सर्च करो", "गाना सर्च करो", "नीचे स्क्रॉल करो") */
    fun appFlow(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || words(noCheck(t)).any { it in NEG }) return false
        return FLOW_VERB.any { t.contains(it) } && FLOW_PLACE.any { t.contains(it) }
    }

    private fun explicitAllowed(kind: Kind, now: Long): Boolean =
        DKState.lastUserAt > 0 && now - DKState.lastUserAt in 0..EXPLICIT_MS && explicitOk(kind, DKState.lastUser)

    /**
     * Live / agent के tool से ठीक पहले. null = चलाओ (यूज़र ने खुद कहा / हाँ कहा / कुछ निजी नहीं).
     * वरना AI को लौटने वाली लाइन - tool **नहीं** चला.
     */
    fun gate(tool: String, args: JSONObject, now: Long = System.currentTimeMillis()): String? {
        val k = kind(tool, args) ?: return null
        // 28 नवंबर 2026 (मालिक): लेंस यूज़र ने खोला है और चालू है = उसी सत्र में कैमरा देखना पूछे बिना
        if (k == Kind.CAMERA && lensOn) return null
        val explicit = explicitAllowed(k, now)
        val granted = taskGrantValid(taskGrants[k], lastSaidAt(), now)
        val said = DKState.userTurns().toMutableList()
        if (DKState.lastUser.isNotBlank() && said.lastOrNull()?.first != DKState.lastUser) said += DKState.lastUser to DKState.lastUserAt
        return when (check(k, explicit, granted, pending, said, tapAt, tapYes, now)) {
            Gate.Run -> {
                if (!granted) taskGrants[k] = now
                if (pending?.kind == k) pending = null
                DKLog.d("निजता: $tool - ${if (explicit) "यूज़र ने खुद कहा, बिना पूछे" else "यूज़र की हाँ"} (सिर्फ़ इसी काम के लिए)")
                null
            }
            Gate.Deny -> { pending = null; taskGrants.remove(k); DKLog.d("निजता: $tool - यूज़र ने ना कहा, नहीं देखा"); denyNote(k) }
            Gate.NotHeard -> { DKLog.d("निजता: $tool - जवाब अभी नहीं आया, चुप रहकर रुको"); NOT_HEARD }
            Gate.Ask -> {
                pending = Pending(k, tool, now)
                DKLog.d("निजता: $tool - बात साफ़ नहीं, एक बार पूछा \"$QUESTION\"")
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
    fun reset() { pending = null; tapAt = 0L; tapYes = false; grants.clear(); taskGrants.clear() }
}
