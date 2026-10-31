package com.dk.assistant

/**
 * v4.1 (मालिक: "YouTube खोलो और पवन सिंह का गाना चलाओ" -> स्क्रीन पर `phone_task{request:…}<ctrl43>` लिखा आया,
 * "फ़ोन कमांड दिया है" बोला, पर काम कुछ नहीं हुआ).
 *
 * जड़: Gemini Live कभी-कभी function call (toolCall) भेजने की जगह उसे अपने जवाब के शब्दों में ही लिख/बोल देता है
 * (`<ctrlNN>` उसका अंदरूनी निशान है जो बाहर आ गया). ऐप को toolCall मिलता ही नहीं = phoneTask() चलता ही नहीं,
 * और LivePromise की "वादा-जाँच" भी नहीं जागती (जवाब में "कर रहा हूँ" जैसा कोई शब्द नहीं).
 *
 * इलाज (यह pure हिस्सा, JUnit से जाँचा जाता है): जवाब के text की धारा में से ऐसी लिखी हुई call पकड़ो,
 * उसे स्क्रीन/आवाज़ से हटाओ, और उसका नाम + arguments लौटाओ ताकि फ़ोन उसी पल असली tool चला दे.
 */
object LeakedCall {
    /** Live में declare किए गए tools (GeminiLive.kt) */
    val TOOLS = listOf(
        "social_media_task_control", "social_media_task", "notification_reply", "recall_conversation",
        "computer_task", "build_website", "connector_task", "whatsapp_task", "forget_memory", "remember_fact",
        "screen_share", "teacher_mode", "dk_settings", "dk_inspector", "current_time", "coding_task", "notifications", "dk_features",
        "task_status", "phone_task", "phone_call", "story_task", "web_search", "deep_think", "local_info", "camera"
    )

    /** बिना नाम वाला argument (`phone_task{YouTube खोलो}`) किस key में जाए */
    private val MAIN_ARG = mapOf(
        "phone_task" to "request", "web_search" to "query", "deep_think" to "question", "recall_conversation" to "question",
        "coding_task" to "task", "computer_task" to "task", "story_task" to "topic", "build_website" to "prompt",
        "remember_fact" to "fact", "forget_memory" to "about", "notifications" to "app", "phone_call" to "action",
        "whatsapp_task" to "action", "social_media_task" to "action", "social_media_task_control" to "action",
        "connector_task" to "action"
    )

    /** "phone_task" के अलावा जिन tools को बिना किसी argument के भी चलाया जा सकता है */
    private val NO_ARG_OK = setOf("current_time", "local_info", "task_status", "dk_features", "notifications")

    data class Call(val name: String, val args: Map<String, String>)

    // "phone _task" जैसा टूटा नाम भी (लिखावट कभी-कभी "_" के आस-पास जगह डाल देती है)
    private val NAME_ALT = TOOLS.joinToString("|") { n -> n.split('_').joinToString("""\s*_\s*""") { Regex.escape(it) } }
    private val OPEN = Regex("""(?i)(?<![a-z_])(?:print\s*\(\s*)?(?:default_api\s*\.\s*)?($NAME_ALT)\s*([{(])""")
    private val CTRL = Regex("""<\s*/?\s*ctrl\s*\d*\s*>""", RegexOption.IGNORE_CASE)
    private val CTRL_TAIL = Regex("""<\s*/?\s*(?:c(?:t(?:r(?:l\s*\d*)?)?)?)?\s*$""", RegexOption.IGNORE_CASE)
    private val WORD_TAIL = Regex("""(?i)(?:default_api\s*\.\s*)?[a-z][a-z_\s]*$""")

    /** पूरे text से लिखी हुई calls निकालो: (साफ़ text, calls) */
    fun extract(text: String): Pair<String, List<Call>> {
        val f = Filter()
        val out = StringBuilder(f.push(text)).append(f.finish())
        return tidy(out.toString()) to f.calls()
    }

    /** स्क्रीन/आवाज़ के लिए साफ़ text */
    fun clean(text: String): String = extract(text).first

    /** text में लिखी हुई call (या ctrl निशान) है? */
    fun has(text: String): Boolean = OPEN.containsMatchIn(text) || CTRL.containsMatchIn(text)

    private fun tidy(s: String): String = s.replace(Regex("""[ \t]{2,}"""), " ").trim()

    /** call के अंदर का हिस्सा -> args. JSON (`"request":"…"`), `request:…`, `request=…` या सिर्फ़ text - सब चलता है */
    fun parseArgs(name: String, inner: String): Map<String, String> {
        var s = inner.trim()
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length - 1).trim()
        if (s.isEmpty()) return emptyMap()
        val key = Regex("""(?:^|,)\s*["']?([a-zA-Z_][a-zA-Z0-9_]*)["']?\s*[:=]""")
        val marks = key.findAll(s).toList()
        if (marks.isEmpty() || marks.first().range.first > 0) {
            return mapOf((MAIN_ARG[name] ?: "request") to unquote(s))
        }
        val m = LinkedHashMap<String, String>()
        for ((i, mk) in marks.withIndex()) {
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else s.length
            val v = unquote(s.substring(mk.range.last + 1, end))
            m[mk.groupValues[1]] = v
        }
        return m
    }

    private fun unquote(v: String): String {
        var t = v.trim().trimEnd(',').trim()
        if (t.length >= 2 && (t.first() == '"' || t.first() == '\'') && t.last() == t.first()) t = t.substring(1, t.length - 1)
        return t.replace("\\\"", "\"").trim()
    }

    /** call में काम की कोई बात है? (ख़ाली phone_task{} नहीं चलाते) */
    fun runnable(c: Call): Boolean = c.name in NO_ARG_OK || c.args.values.any { it.isNotBlank() }

    /**
     * धारा वाला फ़िल्टर: Live जवाब टुकड़ों में भेजता है ("phone", "_task{", "request:YouTube…}") - इसलिए जो हिस्सा
     * call की शुरुआत हो सकता है उसे रोके रखो; call पूरी होते ही [onCall] (उसी पल), और उसका text कभी बाहर नहीं.
     * [onOpen] = call शुरू होती दिखी (Live की अपनी आवाज़ तुरंत रोकने के लिए).
     */
    class Filter(private val onOpen: () -> Unit = {}, private val onCall: (Call) -> Unit = {}) {
        private val buf = StringBuilder()
        private val found = ArrayList<Call>()
        private var opened = false
        fun calls(): List<Call> = found.toList()
        /** इस बारी में कोई लिखी call दिखी (पूरी या अधूरी) */
        fun sawCall(): Boolean = opened || found.isNotEmpty()

        fun push(chunk: String): String {
            buf.append(chunk)
            return drain(final = false)
        }

        /** बारी खत्म: रुका हुआ हिस्सा - अधूरी call भी हो तो चलाओ, बाकी text लौटाओ */
        fun finish(): String = drain(final = true)

        private fun drain(final: Boolean): String {
            val out = StringBuilder()
            while (true) {
                val s = CTRL.replace(buf, "")
                buf.setLength(0); buf.append(s)
                val m = OPEN.find(buf)
                if (m == null) {
                    // पूरी call नहीं - आख़िरी शब्द किसी tool के नाम की शुरुआत हो सकता है? (या "<ctr" अधूरा निशान)
                    val hold = if (final) 0 else holdLen(buf)
                    out.append(buf, 0, buf.length - hold)
                    val rest = buf.substring(buf.length - hold)
                    buf.setLength(0); buf.append(rest)
                    return out.toString()
                }
                if (!opened) { opened = true; try { onOpen() } catch (_: Exception) {} }
                out.append(buf, 0, m.range.first)
                val name = m.groupValues[1].replace(Regex("""\s+"""), "").lowercase()
                val close = closeOf(buf, m.range.last)
                if (close < 0) {
                    if (!final && buf.length - m.range.first < MAX_CALL_CHARS) {
                        val rest = buf.substring(m.range.first); buf.setLength(0); buf.append(rest)
                        return out.toString()
                    }
                    // बारी खत्म (या बहुत लंबी) और बंद नहीं हुई - जितना है उतने से call
                    emit(Call(name, parseArgs(name, buf.substring(m.range.last + 1))))
                    buf.setLength(0)
                    return out.toString()
                }
                emit(Call(name, parseArgs(name, buf.substring(m.range.last + 1, close))))
                var after = close + 1
                // print(phone_task(...)) वाला बाहरी ")" भी
                if (buf.substring(m.range.first, m.range.last).lowercase().startsWith("print")) {
                    while (after < buf.length && buf[after].isWhitespace()) after++
                    if (after < buf.length && buf[after] == ')') after++
                }
                val rest = buf.substring(after); buf.setLength(0); buf.append(rest)
            }
        }

        private fun emit(c: Call) {
            if (!runnable(c)) return
            found.add(c)
            try { onCall(c) } catch (_: Exception) {}
        }
    }

    const val MAX_CALL_CHARS = 1200

    /** [open] = "{" या "(" का index; उसका बंद होने वाला index (quotes के अंदर के ब्रैकेट नहीं गिनते), न मिले = -1 */
    fun closeOf(s: CharSequence, open: Int): Int {
        val o = s[open]; val c = if (o == '{') '}' else ')'
        var depth = 0; var q: Char? = null; var i = open
        while (i < s.length) {
            val ch = s[i]
            if (q != null) {
                if (ch == '\\') { i += 2; continue }
                if (ch == q) q = null
            } else if (ch == '"') {
                q = ch
            } else if (ch == o) {
                depth++
            } else if (ch == c) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** text का आख़िरी हिस्सा जो किसी tool call की शुरुआत हो सकता है - उतने अक्षर रोको */
    fun holdLen(s: CharSequence): Int {
        CTRL_TAIL.find(s)?.let { if (it.value.isNotEmpty()) return it.value.length }
        val w = WORD_TAIL.find(s) ?: return 0
        // शब्द की शुरुआत से (पहला अक्षर जो पिछले अक्षर से अलग शब्द है)
        var start = w.range.first
        if (start > 0 && (s[start - 1].isLetterOrDigit() || s[start - 1] == '_')) return 0
        val tail = s.substring(start)
        val compact = tail.replace(Regex("""\s+"""), "").lowercase().removePrefix("default_api.")
        if (compact.isEmpty()) return 0
        // "phone_task " (नाम पूरा, ब्रैकेट बाकी) या "phone_ta" (नाम अधूरा)
        val could = TOOLS.any { it.startsWith(compact) || compact == it } || "default_api.".startsWith(compact)
        if (!could) {
            // सिर्फ़ आख़िरी शब्द देखो ("YouTube phone" -> "phone")
            val last = Regex("""(?i)[a-z_]+\s*$""").find(tail) ?: return 0
            val lc = last.value.trim().lowercase()
            if (lc.isEmpty() || TOOLS.none { it.startsWith(lc) }) return 0
            start = start + last.range.first
            if (start > 0 && (s[start - 1].isLetterOrDigit() || s[start - 1] == '_')) return 0
            return s.length - start
        }
        return s.length - start
    }
}
