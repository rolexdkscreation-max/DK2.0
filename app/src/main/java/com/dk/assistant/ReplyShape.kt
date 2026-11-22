package com.dk.assistant

/**
 * 22 नवंबर 2026 (मालिक: "ऊपर response लिखा आ रहा है और ब्रैकेट - ये सब नहीं दिखना चाहिए"):
 * कुछ मॉडल (DK सर्वर / बैकअप / खोज का जवाब लिखने वाला) सादे जवाब को JSON में लपेट देते हैं:
 * `{ "response": "..." }`, ```json ... ```. दिखाने / बोलने से पहले असली वाक्य निकालो.
 *
 * सिर्फ़ "एक ही बोलने वाला field" वाला JSON खोला जाता है. फ़ोन-काम वाला JSON ({"say", "actions", "done"...})
 * जैसा है वैसा रहता है (उसे AgentRunner ख़ुद पढ़ता है). Android के बिना (JVM test).
 */
object ReplyShape {
    /** जवाब वाले field - पहला भरा हुआ लिया जाता है */
    val KEYS = listOf("response", "reply", "answer", "say", "text", "message", "content", "output", "result")
    /** इनमें से कोई भी हो = फ़ोन-काम वाला JSON, मत खोलो */
    private val WORK = setOf("actions", "action", "need_search", "need_research", "need_screen", "need_think", "done", "remember", "tool", "tool_calls")

    private val FENCE = Regex("^```[a-zA-Z]*\\s*([\\s\\S]*?)\\s*```$")
    private val OPEN = Regex("^\\{\\s*\"(" + KEYS.joinToString("|") + ")\"\\s*:\\s*\"")

    /** पूरा जवाब: JSON-लपेट हटे तो अंदर का वाक्य, नहीं तो वही text */
    fun unwrap(text: String): String {
        val raw = text.trim()
        if (raw.isEmpty()) return text
        val body = FENCE.find(raw)?.groupValues?.get(1)?.trim() ?: raw
        if (!body.startsWith("{")) return if (body !== raw) body else text
        val obj = try { org.json.JSONObject(body) } catch (_: Exception) { null }
        if (obj == null) return partial(body) ?: text
        val names = obj.keys().asSequence().toList()
        if (names.any { it in WORK }) return text
        for (k in KEYS) {
            val v = obj.opt(k)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return text
    }

    /**
     * आधा / टूटा JSON (stream चलते समय या ग़लत quote): `{ "response": "आज रविवार...` -> `आज रविवार...`.
     * बोलने वाला field न मिले = null.
     */
    fun partial(text: String): String? {
        val t = text.trimStart()
        val m = OPEN.find(t) ?: return null
        var s = t.substring(m.range.last + 1)
        s = s.replace(Regex("\"\\s*(,\\s*\"[a-z_]+\"\\s*:[\\s\\S]*)?}?\\s*(```)?\\s*$"), "")
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\/", "/").trim()
    }

    /** stream का अब तक का text: JSON-लपेट शुरू हो चुका हो तो सिर्फ़ अंदर का हिस्सा (शुरुआती `{` / ```json भी न दिखे) */
    fun live(text: String): String {
        val t = text.trimStart().removePrefix("```json").removePrefix("```JSON").removePrefix("```").trimStart()
        if (!t.startsWith("{")) return text
        partial(t)?.let { return it }
        // अभी सिर्फ़ `{` या `{ "resp` आया है - कुछ मत दिखाओ
        return if (Regex("^\\{\\s*(\"[a-z_]*\"?\\s*:?\\s*\"?)?$").matches(t)) "" else text
    }
}
