package com.dk.assistant

/**
 * 22 नवंबर 2026 (मालिक: "क्या हाल है पूछा तो DK ने 'मुझे NVIDIA के शोधकर्ताओं द्वारा प्रशिक्षित किया गया है...' वही लाइनें
 * बार-बार लिख दीं. DK किसी भी AI कंपनी / API का नाम नहीं लेगा - जैसे Google, OpenAI, Meta नहीं लेता").
 *
 * जड़ (AiClients.kt): AI का जवाब बिना किसी जाँच के सीधे स्क्रीन / आवाज़ पर जाता था.
 *  1. छोटे मॉडल (जैसे NVIDIA Nemotron) कभी-कभी एक ही वाक्य बार-बार दोहराने के चक्कर (loop) में फँसकर token की सीमा
 *     (stream में 900) तक वही लिखते रहते हैं - वही जवाब इतिहास में जाकर अगली बार फिर नकल होता था.
 *  2. पहचान का नियम सिर्फ़ prompt में था; मॉडल उसे न माने तो "मैं NVIDIA / Google का भाषा मॉडल हूँ" सीधे दिख जाता था.
 *
 * यह गार्ड हर जवाब (हर कंपनी, छोटा या बड़ा मॉडल, stream या पूरा) पर:
 *  - loop: कोई लंबा वाक्य दोबारा आए तो रुका रहता है; उसके ठीक बाद भी पुराना वाक्य = loop, जवाब वहीं खत्म (stream बंद).
 *    नया वाक्य आए = दोहराव जायज़ था, दोनों दिखते हैं. पुराने वाक्य जैसी शुरुआत वाला अधूरा वाक्य दिखाया ही नहीं जाता.
 *  - पहचान: "मैं / मुझे / I ..." वाला वाक्य जिसमें कोई AI कंपनी / मॉडल का नाम + "बनाया / प्रशिक्षित / trained / model" हो,
 *    या "मैं एक भाषा मॉडल हूँ" - वह वाक्य पूरा हटता है. सब कुछ हट जाए तो [FALLBACK].
 *    आम बात में नाम ठीक है ("NVIDIA का शेयर", "Google Maps खोल दिया") - सिर्फ़ DK की अपनी पहचान वाले वाक्य हटते हैं.
 *  - तेज़: पहला वाक्य तुरंत (बस अधूरा शब्द एक पल रुकता है); "मैं / मुझे" से शुरू वाक्य के पहले कुछ शब्द ही रुकते हैं.
 *  - ``` (कोड) दिखते ही आगे का सब बिना छेड़े.
 * Pure Kotlin (Android नहीं) - JUnit से जाँच.
 */
class AnswerGuard {
    private val seen = HashSet<String>()
    private val cur = StringBuilder()
    private var emitted = 0
    private val held = StringBuilder()
    private var dupRun = 0
    private var pendingDot = false
    private var code = false
    private var shown = 0

    /** loop पकड़ा गया - आगे का सब बेकार, stream रोक दो */
    var stopped = false
        private set

    /** कोई पहचान वाला वाक्य हटाया गया */
    var dropped = false
        private set

    /** stream का नया टुकड़ा -> इसमें से अभी दिखाने लायक हिस्सा */
    fun feed(delta: String): String {
        if (stopped || delta.isEmpty()) return ""
        val out = StringBuilder()
        for (ch in delta) {
            if (code) { out.append(ch); continue }
            if (pendingDot) {
                pendingDot = false
                if (ch.isWhitespace()) { finish(out); if (stopped) return done(out) }
            }
            cur.append(ch)
            if (cur.endsWith("```")) { code = true; release(out, cur.length); cur.setLength(0); emitted = 0; continue }
            if (ch == '.') { if (cur.length < 2 || !cur[cur.length - 2].isDigit()) pendingDot = true }
            else if (isEnd(ch)) { finish(out); if (stopped) return done(out) }
        }
        if (!code && cur.isNotEmpty() && !hold()) {
            // पूरे शब्दों तक (अधूरा शब्द एक पल रुके). सिर्फ़ खाली जगह अकेले नहीं - वाक्य की शुरुआत तय रहे
            val b = lastBoundary()
            if (b > emitted && norm(cur.substring(emitted, b)).isNotEmpty()) release(out, b)
        }
        return done(out)
    }

    /** stream खत्म - बचा हुआ हिस्सा (अधूरा दोहराव हो तो कुछ नहीं) */
    fun flush(): String {
        val out = StringBuilder()
        if (!stopped && !code && cur.isNotEmpty()) {
            val n = norm(cur)
            if (emitted == 0 && n.isNotEmpty() && seen.any { it != n && it.startsWith(n) }) { cur.setLength(0) }   // कटा हुआ दोहराव
            else finish(out)
        }
        // आख़िर में एक बार का दोहराव (loop नहीं) = जायज़, दिखा दो
        if (!stopped && held.isNotEmpty()) out.append(held)
        held.setLength(0)
        if (shown + out.length == 0 && dropped) out.append(FALLBACK)
        return done(out)
    }

    private fun done(out: StringBuilder): String { shown += out.length; return out.toString() }

    private fun isEnd(ch: Char): Boolean = ch == '।' || ch == '!' || ch == '?' || ch == '\n' || ch == '|'

    /** अभी दिखाना है या रुकना */
    private fun hold(): Boolean {
        val n = norm(cur)
        val w = words(cur)
        if (emitted == 0) {
            if (n.isEmpty()) return true
            if (seen.any { it.startsWith(n) }) return true
            val first = w.firstOrNull() ?: return true
            if (first in FP && w.size < FP_WORDS) return true
        }
        return w.any { it in FP } && BRAND.containsMatchIn(cur.toString().lowercase())
    }

    /** इस जगह तक पूरे शब्द हैं (आख़िरी अधूरा शब्द रुका रहे) */
    private fun lastBoundary(): Int {
        for (i in cur.length - 1 downTo 0) if (!isWordChar(cur[i])) return i + 1
        return 0
    }

    private fun finish(out: StringBuilder) {
        val n = norm(cur)
        val ident = selfIdentity(cur.toString())
        val long = n.length >= MIN
        val dup = long && n in seen
        if (long && !dup) seen.add(n)
        if (ident) dropped = true
        val part = if (ident) "" else cur.substring(emitted)
        when {
            dup -> { dupRun++; if (dupRun >= 2) { stopped = true; held.setLength(0) } else held.append(part) }
            !long && held.isNotEmpty() -> held.append(part)
            else -> { if (long) dupRun = 0; out.append(held); held.setLength(0); out.append(part) }
        }
        cur.setLength(0); emitted = 0
    }

    private fun release(out: StringBuilder, upTo: Int) {
        if (held.isNotEmpty()) { out.append(held); held.setLength(0); dupRun = 0 }
        if (emitted < upTo) { out.append(cur, emitted, upTo); emitted = upTo }
    }

    companion object {
        /** इससे छोटे वाक्य ("ठीक है।", "धन्यवाद।") दोहराए जा सकते हैं - उन्हें नहीं गिनते */
        const val MIN = 12
        /** "मैं / मुझे" से शुरू वाक्य के इतने शब्द तक रुककर देखो कि पहचान वाली बात तो नहीं */
        const val FP_WORDS = 6
        /** पूरा जवाब सिर्फ़ पहचान वाली बातें था */
        const val FALLBACK = "मैं DK हूँ। बताइए, क्या मदद करूँ?"

        private val FP = setOf("मैं", "मै", "मुझे", "मुझको", "मेरा", "मेरी", "मेरे", "i", "i'm", "im", "me", "my", "myself", "main", "mujhe", "mera", "meri")

        /** कोई भी AI कंपनी / मॉडल / API (English + देवनागरी) */
        val BRAND = Regex(
            "(?<![\\p{L}\\p{M}\\p{N}])(nvidia|nemotron|google|gemini|gemma|deepmind|openai|open ai|chatgpt|gpt|anthropic|claude|meta|llama|" +
                "groq|grok|xai|z\\.ai|zhipu|glm|mistral|cohere|qwen|alibaba|deepseek|microsoft|copilot|cloudflare|openrouter|perplexity|" +
                "baidu|ernie|kimi|moonshot|minimax|granite|hugging ?face|" +
                "एनवीडिया|एनविडिया|नेमोट्रॉन|नेमोट्रोन|गूगल|जेमिनी|जेमिनाई|जेम्मा|ओपनएआई|ओपन एआई|चैटजीपीटी|चैट जीपीटी|एंथ्रोपिक|एन्थ्रोपिक|" +
                "क्लॉड|मेटा|लामा|ग्रोक|मिस्ट्रल|कोहेयर|क्वेन|अलीबाबा|डीपसीक|डीप सीक|माइक्रोसॉफ्ट|माइक्रोसॉफ़्ट)(?![\\p{L}\\p{M}\\p{N}])"
        )
        private val MODEL = Regex("(language model|भाषा मॉडल|भाषा-मॉडल|भाषाई मॉडल|ai model|ai मॉडल|एआई मॉडल|ए आई मॉडल|लैंग्वेज मॉडल|(?<![a-z])llm(?![a-z])|large language)")
        private val MADE = Regex("(बनाया|बनाई गई|प्रशिक्षित|ट्रेन किया|ट्रेनिंग|सिखाया|विकसित|डेवलप|आधारित|शोधकर्ता|मॉडल|trained|created|built by|developed|made by|based on|powered by|researcher|model)")

        fun norm(s: CharSequence): String = s.toString().lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), "")

        private fun isWordChar(c: Char) = Character.isLetterOrDigit(c) || Character.getType(c).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
        } || c == '\''

        private fun words(s: CharSequence): List<String> = Regex("[\\p{L}\\p{M}\\p{N}']+").findAll(s.toString().lowercase()).map { it.value }.toList()

        /** यह वाक्य DK की अपनी पहचान किसी AI कंपनी / मॉडल से जोड़ता है? (JSON के { } वाले हिस्से नहीं छूते) */
        fun selfIdentity(sentence: String): Boolean {
            if (sentence.contains('{') || sentence.contains('}')) return false
            val l = sentence.lowercase()
            if (words(l).none { it in FP }) return false
            return MODEL.containsMatchIn(l) || (BRAND.containsMatchIn(l) && MADE.containsMatchIn(l))
        }

        /** पूरा जवाब एक साथ (बिना stream / पुराना इतिहास) */
        fun clean(text: String): String {
            if (text.isEmpty()) return text
            val g = AnswerGuard()
            return (g.feed(text) + g.flush()).trim()
        }

        /**
         * किसी भी client के stream पर गार्ड: [run] को कच्चे टुकड़े देने वाला callback मिलता है; [onDelta] को सिर्फ़ साफ़ हिस्सा.
         * loop पकड़ा = stream वहीं बंद ([Stop]), जितना साफ़ आया वही जवाब.
         */
        fun stream(onDelta: (String) -> Unit, run: ((String) -> Unit) -> String): String {
            val g = AnswerGuard()
            val out = StringBuilder()
            fun emit(s: String) { if (s.isNotEmpty()) { out.append(s); onDelta(s) } }
            try { run { d -> emit(g.feed(d)); if (g.stopped) throw Stop() } } catch (_: Stop) { }
            emit(g.flush())
            return out.toString()
        }
    }

    /** loop पकड़ा - stream वहीं बंद */
    private class Stop : RuntimeException("repeat-loop")
}
