package com.dk.assistant.forge

import org.json.JSONObject

/**
 * DK AI ब्रीफ़ Part 14: Gemini से आए HTML को साफ़/पूरा करना.
 * Android import नहीं (सिर्फ़ org.json) -> JVM test.
 */
object ForgeHtml {
    /** इतना लंबा अधूरा पेज मरम्मत करके चल जाता है; इससे छोटा = "Gemini बीच में रुक गया" */
    const val MIN_TRUNCATED_OK = 14_000
    /** इससे छोटा जवाब = दोबारा कोशिश (असली पेज नहीं) */
    const val MIN_COMPLETE = 1_500
    const val SAFETY_ID = "dk-forge-safety"

    private val FENCE_OPEN = Regex("^\\s*```[a-zA-Z0-9_-]*\\s*\\n?")
    private val FENCE_CLOSE = Regex("\\n?\\s*```\\s*$")

    /** ```html ... ``` हटाओ, और <!DOCTYPE / <html से पहले की बातें भी */
    fun stripFences(text: String): String {
        var s = text.replace("\r\n", "\n")
        s = FENCE_OPEN.replace(s, "")
        s = FENCE_CLOSE.replace(s, "")
        val low = s.lowercase()
        val start = listOf(low.indexOf("<!doctype"), low.indexOf("<html")).filter { it >= 0 }.minOrNull()
        if (start != null && start > 0) s = s.substring(start)
        // पेज के बाद बची बातें / बंद fence
        val end = s.lowercase().lastIndexOf("</html>")
        if (end >= 0) s = s.substring(0, end + "</html>".length)
        return s.trim()
    }

    fun looksLikeHtml(text: String?): Boolean {
        val l = text?.lowercase() ?: return false
        return (l.contains("<!doctype html") || l.contains("<html")) && (l.contains("<body") || l.contains("<div") || l.contains("<section"))
    }

    fun isComplete(html: String): Boolean = html.lowercase().contains("</html>")

    private fun count(low: String, s: String): Int {
        var n = 0; var i = low.indexOf(s)
        while (i >= 0) { n++; i = low.indexOf(s, i + s.length) }
        return n
    }

    /** अधूरा पेज: आधा कटा tag हटाओ, खुले script/style/body/html बंद करो */
    fun repairTruncated(html: String): String {
        var s = html
        val lt = s.lastIndexOf('<'); val gt = s.lastIndexOf('>')
        if (lt > gt) s = s.substring(0, lt)
        val low = s.lowercase()
        val sb = StringBuilder(s)
        if (count(low, "<script") > count(low, "</script>")) sb.append("\n</script>")
        if (count(low, "<style") > count(low, "</style>")) sb.append("\n</style>")
        if (low.contains("<body") && !low.contains("</body>")) sb.append("\n</body>")
        if (!low.contains("</html>")) sb.append("\n</html>")
        return sb.toString()
    }

    /**
     * एक बार ही डलने वाली script: 1.4 s बाद जो content अब भी opacity 0 पर स्क्रीन में अटका है उसे दिखा दो
     * (fixed / pointer-events none / aria-hidden / canvas छोड़कर). दोबारा लगाने पर कुछ नहीं बदलता.
     */
    fun withSafetyNet(html: String): String {
        if (html.contains("id=\"$SAFETY_ID\"")) return html
        val script = "<script id=\"$SAFETY_ID\">(function(){function f(){try{var vh=innerHeight;" +
            "document.querySelectorAll('body *').forEach(function(e){if(e.tagName==='CANVAS'||e.tagName==='SCRIPT')return;" +
            "if(e.closest('[aria-hidden=\"true\"]'))return;var c=getComputedStyle(e);" +
            "if(c.position==='fixed'||c.pointerEvents==='none')return;" +
            "if(parseFloat(c.opacity)<0.05){var r=e.getBoundingClientRect();if(r.width>0&&r.height>0&&r.top<vh&&r.bottom>0){" +
            "e.style.transition='opacity .6s ease, transform .6s ease';e.style.opacity='1';e.style.transform='none';}}});}catch(x){}}" +
            "setTimeout(f,1400);addEventListener('scroll',function(){clearTimeout(window.__dkS);window.__dkS=setTimeout(f,1400);},{passive:true});})();</script>"
        val low = html.lowercase()
        val i = low.lastIndexOf("</body>")
        return if (i >= 0) html.substring(0, i) + script + "\n" + html.substring(i) else "$html\n$script"
    }

    /**
     * आख़िरी HTML: fence हटाओ; पूरा = ठीक; अधूरा पर काफ़ी लंबा = मरम्मत; वरना null (दोबारा कोशिश/गलती).
     */
    fun finalize(raw: String): String? {
        val s = stripFences(raw)
        if (!looksLikeHtml(s)) return null
        if (isComplete(s) && s.length >= MIN_COMPLETE) return withSafetyNet(s)
        if (s.length >= MIN_TRUNCATED_OK) return withSafetyNet(repairTruncated(s))
        return null
    }

    /** दोबारा कोशिश चाहिए? (बहुत छोटा या </html> के बिना) */
    fun needsRetry(raw: String): Boolean {
        val s = stripFences(raw)
        return s.length < MIN_COMPLETE || !isComplete(s)
    }
}

/** Gemini streamGenerateContent?alt=sse की "data:" पंक्तियाँ */
object SseParser {
    /** "data: {...}" -> JSON text, "[DONE]"/खाली/दूसरी पंक्ति -> null */
    fun payload(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("data:")) return null
        val p = t.removePrefix("data:").trim()
        if (p.isEmpty() || p == "[DONE]") return null
        return p
    }

    private fun obj(p: String): JSONObject? = try { JSONObject(p) } catch (_: Exception) { null }

    /** इस टुकड़े का नया text (सारे parts जोड़कर; "thought" वाले parts छोड़कर) */
    fun extractText(line: String): String {
        val o = payload(line)?.let { obj(it) } ?: return ""
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        return sb.toString()
    }

    /** {"error":{"code":503,"status":"UNAVAILABLE","message":...}} -> "503 UNAVAILABLE: message" */
    fun extractError(line: String): String? {
        val raw = payload(line) ?: line.trim().takeIf { it.startsWith("{") } ?: return null
        val e = obj(raw)?.optJSONObject("error") ?: return null
        return listOf(e.optInt("code", 0).takeIf { it > 0 }?.toString(), e.optString("status").ifBlank { null })
            .filterNotNull().joinToString(" ").let { head ->
                val m = e.optString("message").take(200)
                if (head.isBlank()) m else if (m.isBlank()) head else "$head: $m"
            }
    }

    fun extractFinishReason(line: String): String? {
        val o = payload(line)?.let { obj(it) } ?: return null
        return o.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")?.ifBlank { null }
    }

    /** बोलने लायक गलती का संदेश */
    fun friendlyError(code: Int, reason: String?): String {
        val r = reason.orEmpty().uppercase()
        return when {
            code == 503 || r.contains("UNAVAILABLE") || r.contains("OVERLOADED") -> "Gemini अभी बहुत व्यस्त है, थोड़ी देर बाद दोबारा कोशिश करें।"
            code == 429 || r.contains("RESOURCE_EXHAUSTED") -> "Gemini की मुफ़्त सीमा अभी खत्म है, थोड़ी देर बाद कोशिश करें।"
            code == 401 || code == 403 || r.contains("API_KEY") || r.contains("PERMISSION_DENIED") -> "Gemini key सही नहीं है। सेटिंग्स में Gemini key जाँच लें।"
            code == 400 -> "Gemini ने यह अनुरोध नहीं माना (400)।"
            code >= 500 -> "Gemini सर्वर में दिक्कत है ($code)। थोड़ी देर बाद कोशिश करें।"
            else -> "वेबसाइट नहीं बन पाई" + (if (code > 0) " ($code)" else "") + "।"
        }
    }

    fun isOverloaded(code: Int, reason: String?): Boolean =
        code == 503 || reason.orEmpty().uppercase().let { it.contains("UNAVAILABLE") || it.contains("OVERLOADED") }
}
