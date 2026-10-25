package com.dk.assistant.avatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * v3.71: लड़की अवतार का दिमाग़ - Android import नहीं (JVM test).
 * चित्र: avatar_body + face_<भाव>_<रूप>, सब 1024x1536, चेहरा हर चित्र में एक ही जगह.
 */

/** सिर्फ़ तीन चेहरे. normal = सामान्य/खुश, sad = उदास/भावुक, angry = गुस्सा */
enum class Face(val key: String, val mouths: List<String>) {
    NORMAL("normal", listOf("base", "half", "wide")),
    SAD("sad", listOf("base", "half")),
    ANGRY("angry", listOf("base", "half"));

    /** blink समेत इस चेहरे के सारे चित्र-नाम */
    fun files(): List<String> = (mouths + "blink").map { "face_${key}_$it" }
}

/** Gemini का भाव-टैग. face = कौन-सा चेहरा, voice = आवाज़ का अपने-आप जुड़ने वाला स्टाइल */
enum class Mood(val face: Face, val voice: String) {
    NORMAL(Face.NORMAL, ""),
    HAPPY(Face.NORMAL, "हँसमुख, खुश आवाज़ में, चेहरे पर मुस्कान के साथ बोलो।"),
    SAD(Face.SAD, "धीमी, नरम और उदास आवाज़ में बोलो।"),
    EMOTIONAL(Face.SAD, "धीमी, नरम और भावुक आवाज़ में बोलो।"),
    ANGRY(Face.ANGRY, "तेज़ और सख़्त आवाज़ में, गुस्से के साथ बोलो।");
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
 * अभी का भाव - पूरे ऐप में एक. जवाब ख़त्म होने के [HOLD_MS] बाद चेहरा अपने-आप सामान्य.
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

    fun face(now: Long = System.currentTimeMillis()): Face = current(now).face

    /** इंजन का अपना स्टाइल + इस जवाब के भाव वाला निर्देश (जवाब के आख़िरी वाक्य तक वही - अगला जवाब नया भाव रखता है) */
    fun voiceStyle(base: String): String {
        val add = mood.voice
        if (add.isBlank()) return base
        return if (base.isBlank()) add else base.trimEnd() + " " + add
    }
}

/** मुँह का रूप */
enum class Mouth(val key: String) { BASE("base"), HALF("half"), WIDE("wide") }

/**
 * आवाज़ का ज़ोर (0..1, हर 30-50 ms) -> base / half / wide. चिकनाई + दहलीज़ पर ठहराव ताकि मुँह न काँपे.
 * sad/angry में सिर्फ़ base और half.
 */
class MouthMapper(
    private val halfAt: Float = 0.10f,
    private val wideAt: Float = 0.38f,
    private val gap: Float = 0.03f,
    private val tauMs: Float = 55f,
) {
    var level = 0f
        private set
    var mouth = Mouth.BASE
        private set

    /** raw = null = आवाज़ बंद -> तुरंत base */
    fun update(raw: Float?, dtMs: Long, face: Face): Mouth {
        if (raw == null) { level = 0f; mouth = Mouth.BASE; return mouth }
        val k = 1f - exp(-dtMs.coerceIn(1, 200) / tauMs)
        level += (raw.coerceIn(0f, 1f) - level) * k
        val up = when (mouth) {
            Mouth.BASE -> if (level > wideAt + gap) Mouth.WIDE else if (level > halfAt + gap) Mouth.HALF else Mouth.BASE
            Mouth.HALF -> if (level > wideAt + gap) Mouth.WIDE else if (level < halfAt - gap) Mouth.BASE else Mouth.HALF
            Mouth.WIDE -> if (level < halfAt - gap) Mouth.BASE else if (level < wideAt - gap) Mouth.HALF else Mouth.WIDE
        }
        mouth = if (up == Mouth.WIDE && Mouth.WIDE.key !in face.mouths) Mouth.HALF else up
        return mouth
    }
}

/** हर 3-5 सेकंड पर बेतरतीब समय पर ~120 ms की पलक */
class BlinkClock(private val rnd: kotlin.random.Random = kotlin.random.Random.Default,
                 private val minMs: Long = 3000, private val maxMs: Long = 5000, val durMs: Long = 120) {
    private var nextAt = -1L
    fun closed(now: Long): Boolean {
        if (nextAt < 0) nextAt = now + rnd.nextLong(minMs, maxMs + 1)
        if (now >= nextAt + durMs) nextAt = now + rnd.nextLong(minMs, maxMs + 1)
        return now >= nextAt && now < nextAt + durMs
    }
    fun next(): Long = nextAt
}

/**
 * हवा: धीमी, बेतरतीब - कभी थोड़ी तेज़, कभी लगभग रुकी, पर कभी पूरी बंद नहीं.
 * [step] हर frame: लहर का phase और ताक़त (0.12..1) लौटाता है.
 */
class Wind(private val rnd: kotlin.random.Random = kotlin.random.Random.Default) {
    var phase = 0.0
        private set
    var strength = 0.4f
        private set
    private var target = 0.4f
    private var changeAt = 0L

    fun step(now: Long, dtMs: Long) {
        if (now >= changeAt) {
            // 25% बार लगभग ठहरी हवा, कभी-कभी झोंका
            target = when (rnd.nextInt(8)) { 0, 1 -> 0.12f + rnd.nextFloat() * 0.1f; 7 -> 0.8f + rnd.nextFloat() * 0.2f; else -> 0.3f + rnd.nextFloat() * 0.35f }
            changeAt = now + rnd.nextLong(2200, 6500)
        }
        val k = 1f - exp(-dtMs / 1400f)
        strength += (target - strength) * k
        // गति भी ताक़त के साथ: तेज़ हवा = तेज़ लहर, ठहरी = बहुत धीमी (पर चलती)
        phase += dtMs / 1000.0 * (0.55 + 1.1 * strength) * 2 * PI / 3.2
        if (phase > 2000 * PI) phase -= 2000 * PI
    }
}

/**
 * चित्र के नक्शे (0..1, avatar_body के हिसाब से). असली चित्र आने पर सिर्फ़ ये अंक बदलने होंगे.
 */
object AvatarGeometry {
    const val IMG_W = 1024
    const val IMG_H = 1536
    /** चेहरे की त्वचा (भौंहों से ठुड्डी तक), बाल नहीं - 1024x1536 पर. नमूना चित्र से नापा */
    const val FACE_L = 300
    const val FACE_T = 290
    const val FACE_R = 740
    const val FACE_B = 640
    /** किनारे का धुंधलापन: superellipse का भीतरी हिस्सा (0..1) पूरा, बाहर की ओर धीरे-धीरे 0 */
    const val FEATHER_IN = 0.78f

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t)
    }

    /**
     * बालों पर हवा का असर (0..1). चेहरा, गर्दन, बीच का धड़ और कंधों का बाहरी किनारा = 0.
     * बालों के सिरों (नीचे, किनारों) की तरफ़ धीरे-धीरे बढ़ता है.
     */
    fun hairWeight(u: Float, v: Float): Float {
        val dx = abs(u - 0.5f)
        val side = smooth(0.17f, 0.30f, dx)                       // बीच का खंभा (चेहरा/गर्दन/सीना) = 0
        val down = smooth(0.30f, 0.92f, v).pow(1.3f)              // सिर/चेहरे की ऊँचाई पर 0, सिरों पर पूरा
        val shoulder = 1f - smooth(0.30f, 0.37f, dx) * smooth(0.40f, 0.50f, v)   // कंधे/आस्तीन का बाहरी किनारा (बालों के बाहर)
        val bottom = 1f - 0.7f * smooth(0.90f, 1.0f, v)           // बाल ख़त्म, नीचे सिर्फ़ कोट
        return (side * down * shoulder * bottom).coerceIn(0f, 1f)
    }

    /** चेहरे के mask का अल्फ़ा (0..1). u,v = चेहरे के आयत में 0..1 */
    fun faceAlpha(u: Float, v: Float): Float {
        val x = abs(u * 2 - 1); val y = abs(v * 2 - 1)
        val r = (x.pow(4) + y.pow(4)).pow(0.25f)
        return 1f - smooth(FEATHER_IN, 1f, r)
    }

    /** हवा से एक बिंदु कितना हिले (px, चित्र के हिसाब से). लहर ऊपर से नीचे की ओर चलती है */
    fun windOffset(u: Float, v: Float, phase: Double, strength: Float): Pair<Float, Float> {
        val w = hairWeight(u, v)
        if (w <= 0f) return 0f to 0f
        val wave = sin(phase - v * 5.0).toFloat() + 0.35f * sin(phase * 1.7 - v * 9.0 + u * 3.0).toFloat()
        val dir = if (u < 0.5f) -1f else 1f
        val dx = IMG_W * 0.016f * strength * w * wave
        val dy = IMG_H * 0.003f * strength * w * sin(phase * 0.9 - v * 4.0).toFloat() * dir
        return dx to dy
    }

    /** साँस: बहुत हल्का ऊपर-नीचे (px चित्र में), ~4.2 s का चक्र */
    fun breath(tMs: Long): Float = (IMG_H * 0.0028f * sin(tMs / 4200.0 * 2 * PI)).toFloat()

    /** ContentScale.Fit: चित्र पूरा, बीच में. (scale, left, top) */
    fun fit(viewW: Int, viewH: Int, imgW: Int = IMG_W, imgH: Int = IMG_H): Triple<Float, Float, Float> {
        if (viewW <= 0 || viewH <= 0) return Triple(0f, 0f, 0f)
        val s = minOf(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
        return Triple(s, (viewW - imgW * s) / 2f, (viewH - imgH * s) / 2f)
    }

    /** मेमोरी: स्क्रीन पर जितना बड़ा दिखेगा उससे ज़्यादा बड़ा मत खोलो (2 की घात) */
    fun sampleSize(imgW: Int, imgH: Int, needW: Int, needH: Int): Int {
        if (needW <= 0 || needH <= 0) return 1
        var s = 1
        while (imgW / (s * 2) >= needW && imgH / (s * 2) >= needH) s *= 2
        return s
    }
}
