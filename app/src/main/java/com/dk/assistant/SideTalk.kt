package com.dk.assistant

import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): "DK को पता हो कि मैं (बॉस) बोल रहा हूँ या कोई और. मैं किसी और से बात करूँ तो DK सुनेगा, लेकिन
 * जवाब नहीं देगा."
 *
 * तीन रास्ते:
 *  1. बोलकर: "DK, मैं किसी से बात कर रहा हूँ" / "थोड़ी देर चुप रहो, सुनती रहो" = सुनने वाला मोड (जवाब सिर्फ़ जब नाम "DK" लें).
 *     "DK वापस आओ" / "DK अब बात करो" या नाम लेकर कुछ भी कहें = खत्म. 3 मिनट कोई न बोले = अपने-आप खत्म (ज़्यादा से ज़्यादा 30 मिनट).
 *  2. आवाज़ की पहचान ("मेरी आवाज़ सिखाओ" से सीखी हो): कोई और बोला और अभी आप भी बोल रहे थे = आप दोनों आपस में बात कर रहे -
 *     बिना "DK" नाम वाली बात का जवाब नहीं (रोकना नहीं - आवाज़ Gemini तक जाती है, बस जवाब नहीं बजता).
 *  3. Live के prompt में नियम: बात किसी और से हो रही लगे तो चुप रहो.
 * कोई और (आवाज़ मालिक की नहीं) = पैसे / मैसेज भेजना / कॉल / डिलीट / सेटिंग जैसे बड़े काम नहीं.
 *
 * समय बाहर से (now) - JVM test.
 */
object SideTalk {
    const val PREF = "side_talk_on"
    const val TYPE = "side_talk"

    /** आख़िरी आवाज़ के इतनी देर बाद सुनने वाला मोड खत्म */
    const val QUIET_MS = 3 * 60_000L
    /** ज़्यादा से ज़्यादा */
    const val MAX_MS = 30 * 60_000L
    /** "कोई और अभी बोला था" कितनी देर तक माना जाए */
    const val OTHER_RECENT_MS = 15_000L
    /** "बॉस अभी बोले थे" */
    const val BOSS_RECENT_MS = 30_000L
    /** आवाज़ का फ़ैसला इतना पुराना हो तो इस बात का नहीं */
    const val VERDICT_FRESH_MS = 12_000L
    /** score सीमा से इतना नीचे = पक्का कोई और (बीच वाला = पता नहीं) */
    const val OTHER_MARGIN = 0.10f
    const val MIN_MS = 300

    enum class Who { BOSS, OTHER, UNKNOWN }

    fun on(c: android.content.Context) = Prefs.bool(c, PREF, true)

    // ------------------------------------------------------------------ हालत (एक ही, Live + पुराना रास्ता दोनों)

    class State {
        @Volatile var startedAt = 0L; private set
        @Volatile var lastSpeechAt = 0L; private set
        @Volatile var who = Who.UNKNOWN; private set
        @Volatile var whoAt = 0L; private set
        @Volatile var lastOtherAt = 0L; private set
        @Volatile var lastBossAt = 0L; private set
        /** इस आवाज़ से पहले वाली आवाज़ किसकी थी */
        @Volatile var prevWho = Who.UNKNOWN; private set

        fun start(now: Long) { startedAt = now; lastSpeechAt = now }
        fun end() { startedAt = 0L }
        fun touch(now: Long) { if (startedAt > 0) lastSpeechAt = now }

        fun listening(now: Long): Boolean {
            if (startedAt <= 0) return false
            if (now - startedAt > MAX_MS || now - lastSpeechAt > QUIET_MS) { startedAt = 0L; return false }
            return true
        }

        @Volatile var seq = 0; private set

        /** नई बात शुरू (आवाज़ की पहचान बाद में आएगी). @return इस बात का नंबर */
        fun begin(now: Long): Int {
            prevWho = if (whoAt > 0 && now - whoAt < OTHER_RECENT_MS) who else Who.UNKNOWN
            who = Who.UNKNOWN; whoAt = 0L
            touch(now)
            return ++seq
        }

        /** एक बात की आवाज़ पहचानी गई. पुरानी बात का देर से आया नतीजा (s != seq) = नहीं */
        fun voice(w: Who, now: Long, s: Int = seq) {
            if (s != seq) return
            who = w; whoAt = now
            if (w == Who.OTHER) lastOtherAt = now
            if (w == Who.BOSS) lastBossAt = now
        }

        /** इस बात की (ताज़ा) आवाज़ */
        fun current(now: Long): Who = if (whoAt > 0 && now - whoAt < VERDICT_FRESH_MS) who else Who.UNKNOWN

        fun reset() { startedAt = 0; lastSpeechAt = 0; who = Who.UNKNOWN; whoAt = 0; lastOtherAt = 0; lastBossAt = 0; prevWho = Who.UNKNOWN; bossTaskAt = 0; seq++ }

        /** 14 नवंबर 2026 (मालिक: "मैं अपने बॉस की बात मानूँगा"): मालिक की आवाज़ ने आख़िरी काम (गाना, ऐप, मैसेज…) कब दिया */
        @Volatile var bossTaskAt = 0L; private set
        fun bossDid(now: Long) { bossTaskAt = now }

        /**
         * जवाब बजे या नहीं. @return null = बजे; वरना कारण (log के लिए).
         * @param heard इस बात का लिखा रूप (caption / Live का)
         */
        fun muteReason(now: Long, heard: String, wake: List<String> = emptyList()): String? {
            val addr = addressed(heard, wake)
            if (listening(now)) {
                if (parse(heard) == true) return null          // खुद मोड शुरू करने वाली बात (उसमें DK नाम हो तब भी मोड चलता रहे)
                if (addr) { end(); return null }
                return "सुनने वाला मोड - DK का नाम नहीं"
            }
            if (addr) return null
            val cur = current(now)
            // कोई और बोला और अभी-अभी आप बोल रहे थे = आप दोनों की बात
            if (cur == Who.OTHER && lastBossAt > 0 && now - lastBossAt < BOSS_RECENT_MS) return "कोई और आपसे बात कर रहा है"
            // आप बोले, ठीक पहले कोई और बोला था = आप उसे जवाब दे रहे
            if (cur == Who.BOSS && prevWho == Who.OTHER && now - lastOtherAt < OTHER_RECENT_MS) return "आप किसी और को जवाब दे रहे हैं"
            return null
        }
    }

    val global = State()

    // ------------------------------------------------------------------ pure नियम

    private val NAME = Regex("(?:^|[\\s,.!?।])(?:डी\\s?\\.?\\s?के|दी\\s?\\.?\\s?के|डीके|dk|d\\.k|deekay)(?=$|[\\s,.!?।:;-])", RegexOption.IGNORE_CASE)

    /** बात DK से कही गई (शुरू में या कहीं भी नाम / जगाने वाला शब्द) */
    fun addressed(text: String, wake: List<String> = emptyList()): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (DkOnly.filter(t) != null) return true
        if (NAME.containsMatchIn(t)) return true
        val l = t.lowercase()
        return wake.any { w -> w.isNotBlank() && w.length >= 3 && l.contains(w.lowercase()) }
    }

    private val START = listOf("किसी से बात कर रहा", "किसी और से बात", "किसी से बात करने", "मैं बात कर रहा हूँ", "मैं बात कर रहा हूं",
        "थोड़ी देर चुप", "थोड़ा चुप रह", "थोड़ी देर शांत", "चुप होकर सुन", "चुपचाप सुन", "सुनती रहो", "सुनते रहो", "बस सुनो", "बीच में मत बोल",
        "बीच में मत बोलना", "listen only", "just listen", "मुझे बात करने दो", "बात करने दो")
    private val STOP = listOf("वापस आओ", "वापस आ जाओ", "अब बात करो", "अब बोल सकती", "बात खत्म हो गई", "बात ख़त्म हो गई",
        "सुनना बंद", "you can talk", "come back")

    /** true = सुनने वाला मोड शुरू, false = खत्म, null = यह बात नहीं */
    fun parse(raw: String): Boolean? {
        val t = raw.lowercase().replace(Regex("[,।!?.]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.length > 80) return null
        if (STOP.any { t.contains(it) }) return false
        if (START.any { t.contains(it) }) return true
        return null
    }

    /** आवाज़ के मिलान (score) से कौन. बहुत छोटी आवाज़ / मिलान नहीं = पता नहीं (रोकना नहीं) */
    fun classify(score: Float?, threshold: Float, ms: Int): Who = when {
        score == null || !score.isFinite() || ms < MIN_MS -> Who.UNKNOWN
        score >= threshold -> Who.BOSS
        score < threshold - OTHER_MARGIN -> Who.OTHER
        else -> Who.UNKNOWN
    }

    private val PHONE_RISK = listOf("भेज", "send", "pay", "पे ", "पेमेंट", "payment", "upi", "पैसे", "ट्रांसफ़र", "ट्रांसफर", "transfer",
        "डिलीट", "delete", "हटा", "मिटा", "लॉक", "lock", "अनइंस्टॉल", "uninstall", "रीसेट", "reset", "पासवर्ड", "password", "कॉल कर", "call")
    private val ACTION_RISK = listOf("send", "reply", "post", "comment", "delete", "remove", "pay", "transfer", "call", "dm")

    /**
     * किसी और की आवाज़ पर बड़ा काम = नहीं. @return null = करने दो; वरना Gemini को जाने वाला जवाब.
     */
    fun blockFor(tool: String, args: JSONObject, who: Who): String? {
        if (who != Who.OTHER) return null
        val risky = when (tool) {
            "notification_reply", "phone_call", "dk_settings", "forget_memory" -> true
            "whatsapp_task", "social_media_task", "connector_task" -> args.optString("action").lowercase().let { a -> ACTION_RISK.any { a.contains(it) } }
            "form_fill" -> args.optString("action").lowercase() == "submit"
            "phone_task" -> (" " + args.optString("request").lowercase() + " ").let { r -> PHONE_RISK.any { r.contains(it) } }
            else -> false
        }
        return if (!risky) null else
            "BLOCKED: this voice is not the owner's (another person). Do NOT do it. Tell them politely in one short line that only the owner can ask for this."
    }

    // ------------------------------------------------------------------ मालिक पहले (14 नवंबर 2026)
    /** मालिक का काम दिए इतनी देर तक कोई और उसे बदलवा / रुकवा नहीं सकता */
    const val OWNER_HOLD_MS = 3 * 60_000L

    /** सिर्फ़ जानकारी वाले tool (कुछ बदलते नहीं) - कोई और भी पूछ सकता है */
    private val INFO_TOOLS = setOf("current_time", "deep_research", "deep_think", "dk_features", "dk_inspector", "look_photo", "look_screen",
        "notifications", "recall_conversation", "reel_stats", "remember_fact", "shop_search", "social_insights", "task_status", "web_search")

    /** यह tool फ़ोन पर कुछ करता/बदलता है? (जानकारी = नहीं) */
    fun isAction(tool: String, args: JSONObject): Boolean =
        if (tool == "local_info") args.optBoolean("go") else tool !in INFO_TOOLS

    /**
     * मालिक: "मैंने कुछ बोला और मेरा दोस्त कुछ और करने को बोले, तो DK मेरी बात माने." मालिक की आवाज़ ने अभी-अभी
     * ([OWNER_HOLD_MS]) कोई काम दिया और अब दूसरी आवाज़ (आवाज़ सिखाई हो तभी पता चलता है) कोई काम माँगे = नहीं.
     * छोटे काम मालिक के न होने पर (या बहुत देर बाद) चलते रहते हैं. @return null = करने दो.
     */
    fun ownerFirst(tool: String, args: JSONObject, who: Who, bossTaskAt: Long, now: Long): String? {
        if (who != Who.OTHER || !isAction(tool, args) || bossTaskAt <= 0 || now - bossTaskAt > OWNER_HOLD_MS) return null
        return "BLOCKED: the owner gave you a task a moment ago and this is a different voice (not the owner) asking for another action. " +
            "The owner always comes first - do NOT do it and do not change or stop the owner's task. Say politely in one short line: " +
            "\"माफ़ कीजिए, अभी मैं अपने बॉस का काम कर रही हूँ — मैं अपने बॉस की बात ही मानूँगी।\""
    }

    /** Live prompt में (Settings चालू हो तो) */
    const val PROMPT = "\nWHO IS SPEAKING / SIDE TALK:\n" +
        "- The owner (your boss) is the main person you serve. Other people may be near him (family, friends, colleagues).\n" +
        "- If the owner is clearly talking to SOMEONE ELSE (not to you) - e.g. answering another person, a conversation between them, " +
        "talking on another phone - stay completely SILENT: no reply, no tool call. Just listen and remember the context quietly.\n" +
        "- Reply only when the words are meant for you: your name (DK / डीके) is used, or it is clearly a request/question to an assistant.\n" +
        "- Someone other than the owner may talk to you: be polite and helpful with small things, but never send messages, pay, call, delete, " +
        "change settings or share the owner's private information for them.\n" +
        "- The owner always comes first: if someone else asks you to change, cancel or stop what the owner just asked, or gives a command " +
        "against the owner's, refuse politely (\"मैं अपने बॉस की बात ही मानूँगी\").\n" +
        "- If the owner says \"मैं किसी से बात कर रहा हूँ\" / \"थोड़ी देर चुप रहो\": reply with only \"ठीक है, मैं सुनती रहूँगी\" and then stay silent until he says your name.\n"

    fun quick(start: Boolean) = QuickCommands.Quick(
        JSONObject().put("type", TYPE).put("on", start),
        if (start) "ठीक है, मैं चुपचाप सुनती रहूँगी। जब चाहिए, बस 'DK' बोलिए।" else "मैं वापस आ गई, सर। बताइए।")

    /** perform: सुनने वाला मोड चालू/बंद */
    fun apply(on: Boolean, now: Long = System.currentTimeMillis()) {
        if (on) global.start(now) else global.end()
        DKLog.d(if (on) "सुनने वाला मोड चालू (जवाब सिर्फ़ DK नाम पर)" else "सुनने वाला मोड बंद")
    }
}
