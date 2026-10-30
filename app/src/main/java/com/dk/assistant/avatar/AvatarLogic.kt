package com.dk.assistant.avatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * v3.71: जवाब का भाव (खुश / उदास / गुस्सा / भावुक) - आवाज़ के स्टाइल के लिए. Android import नहीं (JVM test).
 * (लड़की अवतार और उसके चित्र मालिक के कहने पर पूरी तरह हटाए गए - सिर्फ़ भाव वाला हिस्सा बचा है)
 */

/** Gemini का भाव-टैग. voice = आवाज़ का अपने-आप जुड़ने वाला स्टाइल */
enum class Mood(val voice: String) {
    NORMAL(""),
    HAPPY("हँसमुख, खुश आवाज़ में, चेहरे पर मुस्कान के साथ बोलो।"),
    SAD("धीमी, नरम और उदास आवाज़ में बोलो।"),
    EMOTIONAL("धीमी, नरम और भावुक आवाज़ में बोलो।"),
    ANGRY("तेज़ और सख़्त आवाज़ में, गुस्से के साथ बोलो।");
}

/** जवाब की शुरुआत का [खुश] / [उदास] / [गुस्सा] / [भावुक] */
object EmotionTag {
    private val WORDS: Map<String, Mood> = mapOf(
        "खुश" to Mood.HAPPY, "ख़ुश" to Mood.HAPPY, "happy" to Mood.HAPPY,
        "उदास" to Mood.SAD, "दुखी" to Mood.SAD, "sad" to Mood.SAD,
        "भावुक" to Mood.EMOTIONAL, "emotional" to Mood.EMOTIONAL,
        "गुस्सा" to Mood.ANGRY, "ग़ुस्सा" to Mood.ANGRY, "गुस्से" to Mood.ANGRY, "angry" to Mood.ANGRY,
        "सामान्य" to Mood.NORMAL, "normal" to Mood.NORMAL, "neutral" to Mood.NORMAL,
    )
    /** बीच में कहीं दोबारा लिख दे तो भी आवाज़/सबटाइटल में न जाए */
    private val ANY = Regex("\\s*[\\[(]\\s*(" + WORDS.keys.joinToString("|") { Regex.escape(it) } + ")\\s*[\\])]\\s*", RegexOption.IGNORE_CASE)

    fun moodOf(word: String): Mood? = WORDS[word.trim().lowercase()]

    /** पूरा text: (भाव, बिना टैग का text). टैग न हो = (null, वही text) */
    fun split(text: String): Pair<Mood?, String> {
        val t = text.trimStart()
        if (t.isEmpty() || (t[0] != '[' && t[0] != '(')) return null to stripAll(text)
        val close = t.indexOfFirst { it == ']' || it == ')' }
        if (close < 0) return null to text
        val m = moodOf(t.substring(1, close)) ?: return null to stripAll(text)
        return m to stripAll(t.substring(close + 1)).trimStart()
    }

    fun stripAll(text: String): String = if (text.indexOf('[') < 0 && text.indexOf('(') < 0) text else ANY.replace(text, " ").let {
        if (text.firstOrNull()?.isWhitespace() == false) it.trimStart() else it
    }

    /**
     * बहते टुकड़ों के लिए: टैग दो टुकड़ों में बँटकर आ सकता है ("[ख" + "ुश] अच्छा").
     * शुरू का हिस्सा तब तक रोकता है जब तक तय न हो कि टैग है या नहीं (ज़्यादा से ज़्यादा 24 अक्षर).
     */
    class Filter(private val onMood: (Mood) -> Unit = {}) {
        private val buf = StringBuilder()
        private var decided = false
        var mood: Mood? = null
            private set

        fun push(chunk: String): String {
            if (decided) return stripAll(chunk)
            buf.append(chunk)
            val t = buf.toString().trimStart()
            if (t.isEmpty()) return ""
            if (t[0] != '[' && t[0] != '(') return release(null, buf.toString())
            val close = t.indexOfFirst { it == ']' || it == ')' }
            if (close < 0) return if (t.length > 24) release(null, buf.toString()) else ""
            val m = moodOf(t.substring(1, close))
            return if (m != null) release(m, t.substring(close + 1).trimStart()) else release(null, buf.toString())
        }

        /** जवाब ख़त्म: रुका हिस्सा (अगर कोई) छोड़ दो */
        fun finish(): String = if (decided) "" else release(null, buf.toString())

        private fun release(m: Mood?, rest: String): String {
            decided = true; buf.setLength(0)
            mood = m
            onMood(m ?: Mood.NORMAL)
            return stripAll(rest)
        }
    }
}

/** Live की सीधी आवाज़ (टैग नहीं बोला जा सकता): लिखावट के शब्दों से हल्का अंदाज़ा */
object EmotionGuess {
    private val SAD = listOf("माफ़", "माफ", "दुख", "अफ़सोस", "अफसोस", "उदास", "बुरा लगा", "रोना", "sorry")
    private val ANGRY = listOf("गुस्सा", "बिल्कुल नहीं", "बस करो", "बकवास", "हद है", "चुप")
    private val HAPPY = listOf("बधाई", "वाह", "शाबाश", "बहुत अच्छा", "मज़ा", "हाहा", "खुशी")
    fun of(text: String): Mood {
        val t = text.lowercase()
        return when {
            ANGRY.any { t.contains(it) } -> Mood.ANGRY
            SAD.any { t.contains(it) } -> Mood.SAD
            HAPPY.any { t.contains(it) } -> Mood.HAPPY
            else -> Mood.NORMAL
        }
    }
}

/**
 * अभी का भाव - पूरे ऐप में एक. जवाब ख़त्म होने के [HOLD_MS] बाद भाव अपने-आप सामान्य.
 * आवाज़ इंजन [voiceStyle] से अपना स्टाइल-निर्देश लेते हैं (भाव का निर्देश उसमें जुड़ जाता है).
 */
object MoodState {
    const val HOLD_MS = 3500L
    @Volatile var mood: Mood = Mood.NORMAL
        private set
    @Volatile private var doneAt = 0L

    fun set(m: Mood) { mood = m; doneAt = 0L }
    fun replyDone(now: Long = System.currentTimeMillis()) { if (doneAt == 0L) doneAt = now }
    fun reset() { mood = Mood.NORMAL; doneAt = 0L }
    /** जवाब का text पूरा आ गया पर आवाज़ अभी बज रही है = गिनती आवाज़ रुकने से शुरू हो */
    fun stillSpeaking(now: Long = System.currentTimeMillis()) { if (doneAt > 0) doneAt = now }

    fun current(now: Long = System.currentTimeMillis()): Mood =
        if (doneAt > 0 && now - doneAt > HOLD_MS) Mood.NORMAL else mood

    /** इंजन का अपना स्टाइल + इस जवाब के भाव वाला निर्देश (जवाब के आख़िरी वाक्य तक वही - अगला जवाब नया भाव रखता है) */
    fun voiceStyle(base: String): String {
        val add = mood.voice
        if (add.isBlank()) return base
        return if (base.isBlank()) add else base.trimEnd() + " " + add
    }
}
