package com.dk.assistant

import org.json.JSONObject

/**
 * v3.29: DK लाइव में फ़ोन के सीधे काम (ऐप खोलो, टॉर्च, आवाज़, YouTube पर चलाओ…) तुरंत.
 *
 * जड़: Gemini Live में tool call "रुकने वाला" (blocking) है - Google docs: मॉडल tool का जवाब आने तक बोलता नहीं.
 * पहले phone_task हर बार पूरा AgentRunner (60s तक) चलाता था और Gemini चुप इंतज़ार करता था.
 * अब: (1) बात खत्म होते ही caption का पक्का text सीधे QuickCommands से - Gemini के सोचने से पहले काम हो जाता है;
 * (2) फिर Gemini जो phone_task माँगे वह उसी काम का दोहराव हो तो तुरंत "हो गया" (दोबारा नहीं करते);
 * (3) सीधा काम न हो तो भी QuickCommands पहले, AgentRunner बाद में; ज़्यादा देर हो तो Gemini को सच बताओ - "अभी चल रहा है".
 *
 * Android import नहीं (सिर्फ़ org.json) -> JVM unit test.
 */
object LiveFast {
    /** इनका नतीजा बोलकर बताना है (दूरी, किसका कॉल) - Gemini/AgentRunner के रास्ते से */
    private val SKIP = setOf("distance", "who_calls")

    /**
     * v3.33: बात खत्म होते ही caption से (Gemini से पहले) ये नहीं करते - वापस न होने वाले काम.
     * caption में एक शब्द गलत सुना = गलत आदमी को कॉल. ये Gemini के phone_task से (उसने पूरी आवाज़ सुनी) - फिर भी फ़ोन पर, बिना AgentRunner.
     */
    private val NOT_SPECULATIVE = setOf("call_contact")

    /**
     * बात का पक्का text -> तुरंत करने लायक सीधा काम (या null).
     * v3.33: QuickCommands की जगह LocalRouter (उसी से शुरू होता है + कॉल/सर्च/फ़ोटो/कई कदम). fast = caption वाला रास्ता.
     */
    fun pick(text: String, wakeWords: List<String>, fg: String = "", fast: Boolean = false): QuickCommands.Quick? {
        val t = text.trim()
        if (t.length < 2) return null
        val q = LocalRouter.route(t, wakeWords, fg) ?: return null
        val type = q.action.optString("type")
        if (type in SKIP) return null
        if (fast && (type in NOT_SPECULATIVE || (type == "steps" && q.action.toString().let { s -> NOT_SPECULATIVE.any { s.contains("\"$it\"") } }))) return null
        return q
    }

    fun ok(type: String, r: String) = !r.startsWith("ERROR") && !r.startsWith("ASK:") && !r.startsWith("STEP_FAILED") &&
        !(type == "youtube_play" && !r.startsWith("PLAYING") && !r.startsWith("VIDEO_OPENED")) &&
        !(type == "delete_media" && !r.startsWith("DELETED:"))

    /** Gemini को tool का सच्चा नतीजा (कभी "done" झूठ नहीं) */
    fun quickResult(q: QuickCommands.Quick, r: String, already: Boolean): String {
        val type = q.action.optString("type")
        val what = q.ack.ifBlank { describe(q.action) }
        val said = LocalRouter.userText(r)
        // v3.33: user से पूछना है (कई कॉन्टैक्ट / कई ऐप) - Gemini वही सवाल पूछे, खुद कुछ न करे
        if (r.startsWith("ASK:")) return "NEEDS USER CHOICE (nothing was done yet): ${said ?: r}. Ask the user exactly this in their language. Do NOT call, open or do anything until they answer."
        // सच्चा नतीजा, जो फ़ोन ने खुद जाँचा ("डायलर खोला" = कॉल लगी नहीं, "वीडियो खोला" = चल रहा पक्का नहीं)
        if (said != null && ok(type, r)) return (if (already) "ALREADY DONE just now (do not repeat it): " else "DONE: ") +
            "$what. Phone says: \"$said\". Tell the user this honestly in a few words; do not claim more than it says."
        if (said != null) return "FAILED: $said Tell the user honestly; do not try another way unless they ask."
        return if (ok(type, r)) (if (already) "ALREADY DONE just now (do not repeat it): " else "DONE: ") + "$what. Tell the user in 2-5 words."
        else "FAILED: could not do it ($what, ${r.take(80)}). Tell the user honestly it did not work."
    }

    fun describe(a: JSONObject): String = when (a.optString("type")) {
        "open_app" -> "opened ${a.optString("name")}"
        "youtube_play" -> "playing \"${a.optString("query")}\" on YouTube"
        "torch" -> if (a.optBoolean("on", true)) "torch on" else "torch off"
        "volume" -> "volume ${a.optString("direction")}" + if (a.has("level")) " ${a.optInt("level")}%" else ""
        "youtube_search" -> "searched \"${a.optString("query")}\" on YouTube"
        "shop_search" -> "searched \"${a.optString("query")}\" in ${a.optString("app")}"
        "call_contact" -> "call ${a.optString("name")}"
        "call_log" -> "opened recent calls"
        "gallery_latest" -> "opened the latest photo"
        "delete_media" -> "moved the last ${a.optInt("count", 1)} ${a.optString("kind", "photo")}(s) to trash"
        "steps" -> a.optJSONArray("steps")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> describe(o) } }.joinToString(", then ") } ?: "steps"
        else -> a.optString("type").replace('_', ' ')
    }

    /** phone_task (AgentRunner) का सच्चा नतीजा */
    fun agentResult(finished: Boolean, reply: String?, failed: Boolean = false): String = when {
        !finished -> "STILL RUNNING in the background - it is NOT finished yet. Tell the user honestly that you are doing it and will tell them when it is done. Do not say it is done."
        // v3.46: गलती (AI limit / डिलीट की पुष्टि नहीं) पहले "FINISHED. Result: …" बनकर जाती थी - Gemini "हो गया" बोल देता था
        failed -> "FAILED - the task was NOT done (or could not be confirmed). Reason: ${reply.orEmpty().ifBlank { "unknown" }}. " +
            "Tell the user honestly in one short sentence that it did NOT happen and why. Never say it was done."
        // v3.51: Result = phone agent ने खुद जो बोला; उससे ज़्यादा "कर दिया" नहीं
        !reply.isNullOrBlank() -> "FINISHED. Result: $reply (report only this; do not claim anything more was done)"
        else -> "FINISHED the steps, but there is no confirmation message - so it is NOT confirmed that it worked. " +
            "Tell the user you tried but could not confirm it; never say it is done."
    }

    /** काम बाद में पूरा हुआ - Gemini को (user की बात नहीं) बताओ ताकि वह एक वाक्य में बोले */
    fun lateNote(request: String, reply: String?, failed: Boolean = false): String =
        if (failed) "SYSTEM NOTE (this is not the user speaking): the phone task \"${request.take(120)}\" FAILED - it was NOT done " +
            "(or could not be confirmed). Reason: ${reply.orEmpty().take(300).ifBlank { "unknown" }}. Tell the user honestly in one short sentence; never say it was done."
        else "SYSTEM NOTE (this is not the user speaking): the phone task \"${request.take(120)}\" has now finished. " +
            (if (reply.isNullOrBlank()) "There is no confirmation message." else "Result: ${reply.take(300)}") +
            " Tell the user in one short honest sentence."

    /** बात खत्म होते ही किया गया सीधा काम - Gemini उसी को दोबारा माँगे तो दोहराना नहीं */
    class Memo(private val windowMs: Long = 15_000) {
        private data class Done(val key: String, val type: String, val at: Long, @Volatile var result: String?)
        @Volatile private var last: Done? = null

        /** true = नया काम (करो); false = वही काम अभी-अभी शुरू/पूरा हो चुका */
        @Synchronized fun begin(q: QuickCommands.Quick, now: Long): Boolean {
            val key = q.action.toString(); val l = last
            if (l != null && l.key == key && now - l.at < windowMs) return false
            last = Done(key, q.action.optString("type"), now, null)
            return true
        }

        fun finish(q: QuickCommands.Quick, r: String) { last?.takeIf { it.key == q.action.toString() }?.result = r }

        /**
         * Gemini का phone_task इसी काम का दोहराव है? हाँ = उसका नतीजा (null = अभी चल रहा), नहीं = NOT_SAME.
         * वही action, या वही किस्म (Gemini ने शब्द बदले) 15s के अंदर.
         */
        fun same(q: QuickCommands.Quick?, now: Long): String? {
            val l = last ?: return NOT_SAME
            if (q == null || now - l.at >= windowMs) return NOT_SAME
            return if (l.key == q.action.toString() || l.type == q.action.optString("type")) l.result else NOT_SAME
        }

        fun reset() { last = null }

        companion object { const val NOT_SAME = "\u0000not-same" }
    }
}
