package com.dk.assistant

/**
 * नवंबर 2026 (Live जाँच #5): बातचीत की हालत एक जगह. पहले "अभी क्या हो रहा है" कई flags में बिखरा था (playing,
 * turnDet.inSpeech, generating, turnActive, toolJobs) और हर बचाव-परत (Rescue / Recover / Reliable / Stay / StartHold…)
 * अपना-अपना हिसाब रखती थी - इसलिए अटकने पर log से पता नहीं चलता था कि DK किस हालत में कितनी देर फँसा.
 *
 * यह class हर 40ms माइक के टुकड़े पर उन्हीं flags से एक ही हालत [derive] करती है, हर बदलाव का इतिहास रखती है और
 * किसी हालत में तय सीमा से ज़्यादा रुकने पर एक बार साफ़ संदेश देती है ("अटका: सोच रहा 14s • इतिहास …").
 * यह खुद कुछ नहीं तोड़ता/जोड़ता (बचाव पुरानी परतें ही करती हैं) - बस एक सच्चा हिसाब, ताकि आगे परतें इसी पर मिलाई जा सकें.
 *
 * Pure logic -> JVM unit test.
 */
class LiveTurnState(private val keep: Int = 24) {
    enum class S(val hi: String, val maxMs: Long) {
        IDLE("बंद", 0),
        LISTENING("सुन रहा", 0),
        USER_SPEAKING("आप बोल रहे", 45_000),
        THINKING("सोच रहा", 12_000),
        TOOL("काम कर रहा", 150_000),
        SPEAKING("बोल रहा", 90_000),
    }

    data class Change(val from: S, val to: S, val at: Long)

    var state = S.IDLE; private set
    var since = 0L; private set
    private val hist = ArrayDeque<Change>()
    private var reported = false
    /** हर हालत कितनी बार अटकी (stats के लिए) */
    val stuckCount = IntArray(S.values().size)
    var changes = 0; private set

    companion object {
        /** flags -> एक हालत. क्रम मायने रखता है: काम > DK बोल रहा > आप बोल रहे > सोच रहा > सुन रहा */
        fun derive(ready: Boolean, playing: Boolean, userSpeaking: Boolean, toolRunning: Boolean, thinking: Boolean): S = when {
            !ready && !playing -> S.IDLE
            toolRunning -> S.TOOL
            playing -> S.SPEAKING
            userSpeaking -> S.USER_SPEAKING
            thinking -> S.THINKING
            else -> S.LISTENING
        }
    }

    fun reset(now: Long = 0L) { state = S.IDLE; since = now; hist.clear(); reported = false }

    /**
     * हर टुकड़े पर. लौटाता: इस हालत में पहली बार सीमा पार हुई तो log का संदेश, नहीं तो null.
     */
    fun observe(s: S, now: Long): String? {
        if (s != state) {
            hist.addLast(Change(state, s, now)); while (hist.size > keep) hist.removeFirst()
            state = s; since = now; reported = false; changes++
            return null
        }
        if (reported || s.maxMs <= 0 || since == 0L) return null
        val dur = now - since
        if (dur < s.maxMs) return null
        reported = true; stuckCount[s.ordinal]++
        return "अटका: ${s.hi} ${dur / 1000}s • इतिहास: ${history(now, 6)}"
    }

    /** "सुन रहा 2.1s ← सोच रहा 0.8s ← आप बोल रहे 3.0s" (नया पहले) */
    fun history(now: Long, n: Int = 6): String {
        val out = ArrayList<String>()
        out += "${state.hi} ${fmt(now - since)}"
        val h = hist.toList()
        for (i in h.indices.reversed()) {
            if (out.size >= n) break
            val end = h[i].at
            val start = if (i > 0) h[i - 1].at else end
            out += "${h[i].from.hi} ${fmt(end - start)}"
        }
        return out.joinToString(" ← ")
    }

    fun stuckSummary(): String = S.values().filter { stuckCount[it.ordinal] > 0 }
        .joinToString(", ") { "${it.hi} ${stuckCount[it.ordinal]}" }

    private fun fmt(ms: Long) = if (ms < 0) "0s" else String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0)
}
