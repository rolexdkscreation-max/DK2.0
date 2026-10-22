package com.dk.assistant

/**
 * कोडिंग एजेंट का context छोटा रखना और loop रोकना (सब pure - unit tests में जाँचा जाता है).
 *  - हर बार पूरा इतिहास नहीं: शुरू की 2 बातें + अभी का काम + पिछले कुछ संदेश; बीच वाले एक छोटी सूची में
 *  - बड़ी फ़ाइल हिस्सों (chunks) में
 *  - 413 (request बहुत बड़ी) पर अगली request कितनी छोटी हो
 *  - एक ही चेतावनी लगातार 2 बार = रुको
 */
object CoderContext {
    /** एक संदेश/फ़ाइल हिस्से की सामान्य सीमा */
    const val CHUNK_CHARS = 12_000
    /** हर AI request की सामान्य सीमा (अक्षर) - 413 मिलने पर उस मॉडल के लिए अपने-आप और छोटी */
    const val DEFAULT_BUDGET = 60_000
    const val MIN_BUDGET = 6_000
    /** पिछले कितने संदेश पूरे भेजें */
    const val KEEP_TAIL = 8

    fun total(msgs: List<Pair<String, String>>) = msgs.sumOf { it.second.length }

    private fun cap(t: String, n: Int): String =
        if (t.length <= n) t else t.take(n) + "\n…(${t.length - n} अक्षर छोटे किए; ज़रूरत हो तो फिर read/grep करें)"

    /**
     * AI को भेजने लायक छोटा इतिहास. msgs वैसे ही रहते हैं - सिर्फ़ भेजी जाने वाली copy छोटी.
     * Roles user/model बारी-बारी रहते हैं (head model पर खत्म, tail user से शुरू).
     * [note] = हमेशा साथ जाए (जैसे staged फ़ाइलों की list).
     */
    fun compact(msgs: List<Pair<String, String>>, budget: Int = DEFAULT_BUDGET, keepTail: Int = KEEP_TAIL, note: String = ""): List<Pair<String, String>> {
        val b = budget.coerceAtLeast(MIN_BUDGET)
        if (msgs.isEmpty()) return msgs
        val headN = if (msgs.size >= 2 && msgs[0].first == "user" && msgs[1].first == "model") 2 else 0
        var start = (msgs.size - keepTail.coerceAtLeast(1)).coerceAtLeast(headN)
        while (start < msgs.size - 1 && msgs[start].first != "user") start++
        val head = msgs.subList(0, headN).map { (r, t) -> r to cap(t, b / 6) }
        val middle = msgs.subList(headN, start)
        val tail = msgs.subList(start, msgs.size).toMutableList()
        if (middle.isNotEmpty() || note.isNotBlank()) {
            val task = msgs.lastOrNull { it.first == "user" && it.second.startsWith("TASK FROM USER") }
                ?.takeIf { t -> tail.none { it === t } }?.second
            val lines = middle.takeLast(24).map { (r, t) ->
                (if (r == "user") "← " else "→ ") + t.lineSequence().firstOrNull().orEmpty().take(140)
            }
            val sb = StringBuilder()
            if (task != null) sb.append("CURRENT ").append(cap(task, 3000)).append("\n\n")
            if (middle.isNotEmpty()) sb.append("EARLIER STEPS (shortened to save space - read files again if you need details):\n")
                .append(lines.joinToString("\n")).append("\n\n")
            if (note.isNotBlank()) sb.append(note).append("\n\n")
            if (tail.isNotEmpty() && tail[0].first == "user") tail[0] = "user" to sb.toString() + tail[0].second
            else tail.add(0, "user" to sb.toString().trimEnd())
        }
        // अब भी बड़ा? पुराने tail संदेश पहले छोटे करो, आखिरी सबसे बाद में
        var each = CHUNK_CHARS
        var out = head + tail.mapIndexed { i, (r, t) -> r to cap(t, if (i == tail.lastIndex) b / 2 else each) }
        while (total(out) > b && each > 600) {
            each /= 2
            out = head + tail.mapIndexed { i, (r, t) -> r to cap(t, if (i == tail.lastIndex) b / 2 else each) }
        }
        if (total(out) > b) out = out.mapIndexed { i, (r, t) -> r to cap(t, if (i == out.lastIndex) b / 2 else 400) }
        return out
    }

    /** बड़ी फ़ाइल का एक हिस्सा: line नंबर के साथ, और अगला हिस्सा कैसे पढ़ें */
    fun chunk(text: String, startLine: Int = 1, maxChars: Int = CHUNK_CHARS, path: String = "…"): String {
        val lines = text.lines()
        val a = startLine.coerceIn(1, lines.size.coerceAtLeast(1))
        val sb = StringBuilder()
        var i = a
        while (i <= lines.size) {
            val l = "$i| ${lines[i - 1]}\n"
            if (sb.length + l.length > maxChars && i > a) break
            sb.append(l); i++
        }
        val end = i - 1
        val parts = (text.length + maxChars - 1) / maxChars
        return "(${lines.size} lines total; showing $a-$end, about ${parts.coerceAtLeast(1)} parts" +
            (if (end < lines.size) "; next part: {\"tool\":\"read\",\"path\":\"$path\",\"start\":${end + 1}}" else "; end of file") + ")\n" + sb.toString().trimEnd()
    }

    // ------------------------------------------------------------ 413 / बहुत बड़ी request
    /** 413 या "context/request बहुत बड़ी" वाली 400 */
    fun tooLarge(code: Int, msg: String): Boolean {
        if (code == 413) return true
        if (code != 400) return false
        val m = msg.lowercase()
        return listOf("too large", "too long", "context length", "maximum context", "reduce the length", "reduce your message",
            "exceeds the maximum", "input token count", "context_length_exceeded", "request too large").any { m.contains(it) }
    }

    /** Groq जैसा संदेश: "Limit 8000, Requested 21034" -> (limit, requested) */
    fun tokenLimit(msg: String): Pair<Int, Int>? {
        val m = Regex("(?i)limit\\s*:?\\s*(\\d+)\\D{1,20}requested\\s*:?\\s*(\\d+)").find(msg) ?: return null
        val l = m.groupValues[1].toIntOrNull() ?: return null
        val r = m.groupValues[2].toIntOrNull() ?: return null
        return if (l > 0 && r > l) l to r else null
    }

    /** अगली request कितनी हो: (अक्षर, maxTokens). वही बड़ी request दोबारा कभी नहीं */
    fun shrink(curChars: Int, curMaxTok: Int, msg: String): Pair<Int, Int> {
        val lim = tokenLimit(msg)
        if (lim == null) return (curChars / 2).coerceAtLeast(MIN_BUDGET).coerceAtMost(curChars - 1) to (curMaxTok / 2).coerceAtLeast(1024)
        val (limit, requested) = lim
        val maxTok = minOf(curMaxTok, (limit / 4).coerceAtLeast(512))
        val inputAllowed = (limit * 0.85 - maxTok).coerceAtLeast(limit * 0.3)
        // कुछ API "Requested" में maxTokens जोड़ती हैं, कुछ नहीं - दोनों में जो छोटा, वही (सुरक्षित)
        val withOut = inputAllowed / (requested - curMaxTok).coerceAtLeast(1)
        val inputOnly = inputAllowed / requested
        val ratio = minOf(withOut, inputOnly).coerceAtMost(0.9)
        val chars = (curChars * ratio).toInt().coerceAtLeast(MIN_BUDGET / 2).coerceAtMost(curChars - 1)
        return chars to maxTok
    }

    /** 429 के बाद अगले मॉडल/API से पहले छोटा इंतज़ार: 2s, 4s, 8s (ज़्यादा से ज़्यादा 8s) */
    fun backoffMs(n429: Int): Long = if (n429 <= 0) 0L else (2000L shl (n429 - 1).coerceAtMost(2)).coerceAtMost(8000L)
}

/** एक ही चेतावनी लगातार 2 बार -> रुको (बार-बार का चक्र नहीं) */
class WarnGuard(private val limit: Int = 2) {
    private var last = ""
    private var count = 0
    /** true = यही चेतावनी अब [limit] बार लगातार हो गई, रुक जाओ */
    fun hit(warning: String): Boolean {
        val k = key(warning)
        if (k == last) count++ else { last = k; count = 1 }
        return count >= limit
    }
    /** ठीक-ठाक कदम हुआ: गिनती फिर से */
    fun ok() { last = ""; count = 0 }
    companion object {
        /** नंबर/रास्ते का छोटा फ़र्क हो तो भी वही चेतावनी मानो */
        fun key(w: String) = w.lineSequence().firstOrNull().orEmpty().trim().lowercase().replace(Regex("\\d+"), "#").take(120)
    }
}
