package com.dk.assistant
import java.io.File
import kotlin.math.max

data class Case(val id: String, val lang: String, val rate: String, val cond: String, val end: Long,
                val pauses: List<Pair<Long, Long>>, val rms: DoubleArray, val caps: List<Pair<Long, String>>)

fun load(p: String) = File(p).readLines().filter { it.isNotBlank() }.map { l ->
    val f = l.split('\t')
    Case(f[0], f[1], f[2], f[3], f[4].toLong(),
        if (f[5].isEmpty()) emptyList() else f[5].split(';').map { val (a, b) = it.split(':'); a.toLong() to b.toLong() },
        f[6].split(',').map { it.toDouble() }.toDoubleArray(),
        if (f.size < 8 || f[7].isEmpty()) emptyList() else f[7].split('\u001f').map { val (t, w) = it.split('\u001e'); t.toLong() to w })
}

/** एक कॉन्फ़िगरेशन = फ़ोन का VAD + server का VAD */
interface Policy {
    val name: String
    val serverSilenceMs: Int
    fun newDet(): TurnDetector
    /** mic loop का early-end: true = अभी खत्म */
    fun early(det: TurnDetector, cap: String, capStableMs: Long, now: Long, capAt: Long = 0): Boolean
    /** onLocalEndOfSpeech: true = audioStreamEnd रोको */
    fun hold(cap: String, capAt: Long, lastVoiceAt: Long): Boolean
    /** हर फ़्रेम से पहले (adaptive): */
    fun before(det: TurnDetector, cap: String, capAt: Long, now: Long) {}
}

data class R(val c: Case, val subs: List<Long>, val falseEot: Int, val missed: Boolean, val lat: Long)

fun run(c: Case, p: Policy): R {
    val det = p.newDet(); val endGate = StreamEndGate(); val gate = SilenceStreamGate(40, 1000)
    val noiseFloor = c.rms.take(20).sorted()[10]
    val srvThr = max(300.0, noiseFloor * 1.8)
    var capBase = 0L; var capSeen = ""; var capSeenAt = 0L
    var srvActive = false; var srvSil = 0; val subs = ArrayList<Long>(); var pendingBaseAt = -1L
    fun cap(now: Long): Pair<String, Long> {
        val ws = c.caps.filter { it.first <= now && it.first > capBase }
        return ws.joinToString(" ") { it.second } to (ws.lastOrNull()?.first ?: 0L)
    }
    fun submit(now: Long) { subs.add(now); srvActive = false; srvSil = 0; pendingBaseAt = now + 800 }
    for (i in c.rms.indices) {
        val now = i * 40L; val rms = c.rms[i]
        if (pendingBaseAt in 1..now) { capBase = pendingBaseAt; pendingBaseAt = -1 }   // जवाब शुरू = caption की बात बंद
        val (capT, capAt) = cap(now)
        p.before(det, capT, capAt, now)
        var ev = det.feed(rms, now)
        if (ev == TurnDetector.Event.NONE && det.inSpeech && det.silentMs > 0) {
            if (capT != capSeen) { capSeen = capT; capSeenAt = now }
            if (p.early(det, capT, now - capSeenAt, now, capAt)) ev = TurnDetector.Event.SPEECH_END
        }
        val real = det.inSpeech || ev == TurnDetector.Event.SPEECH_END
        if (real) {
            gate.onReal(); endGate.onReal()
            if (rms > srvThr) { srvActive = true; srvSil = 0 } else if (srvActive) srvSil += 40
        } else {
            if (srvActive) srvSil += 40
            if (gate.onSilence() == SilenceStreamGate.Action.SEND_THEN_END && endGate.onSilenceEnd() && srvActive) submit(now)
        }
        if (ev == TurnDetector.Event.SPEECH_END) {
            val hold = p.hold(capT, capAt, det.lastVoiceAt)
            if (!hold && endGate.onSpeechEnd(det.lastSpeechMs, BargeGate.realWords(capT)) && srvActive) submit(now)
        }
        if (srvActive && srvSil >= p.serverSilenceMs) submit(now)
    }
    val fe = subs.count { it < c.end - 40 }
    val after = subs.firstOrNull { it >= c.end - 40 }
    val missed = after == null || after - c.end > 4000
    return R(c, subs, fe, missed, if (after != null) after - c.end else -1)
}

fun pct(xs: List<Long>, q: Double): Long { if (xs.isEmpty()) return -1; val s = xs.sorted(); return s[((s.size - 1) * q).toInt()] }

fun report(p: Policy, cases: List<Case>): String {
    val rs = cases.map { run(it, p) }
    val sb = StringBuilder("== ${p.name} (server ${p.serverSilenceMs}ms)\n")
    fun line(tag: String, xs: List<R>) {
        val withPause = xs.filter { it.c.pauses.isNotEmpty() }
        val lat = xs.filter { !it.missed }.map { it.lat }
        sb.append(String.format("%-14s n=%3d  falseEOT(cut)=%3d/%-3d  missedEOT=%2d  latency median=%4d p95=%4d\n", tag, xs.size,
            withPause.count { it.falseEot > 0 }, withPause.size, xs.count { it.missed }, pct(lat, .5), pct(lat, .95)))
    }
    line("ALL", rs)
    for (k in listOf("hi", "en", "hg")) line("lang=$k", rs.filter { it.c.lang == k })
    for (k in listOf("slow", "normal", "fast")) line("rate=$k", rs.filter { it.c.rate == k })
    for (k in listOf("clean", "fan", "tv", "gaps", "soft")) line("cond=$k", rs.filter { it.c.cond == k })
    val sp = rs.filter { it.c.pauses.isNotEmpty() }
    val shortP = sp.filter { r -> r.c.pauses.all { it.second - it.first < 500 } }
    val longP = sp.filter { r -> r.c.pauses.any { it.second - it.first >= 500 } }
    line("pause<500ms", shortP); line("pause>=500ms", longP); line("no pause", rs.filter { it.c.pauses.isEmpty() })
    return sb.toString()
}

class OldPolicy : Policy {
    override val name = "BEFORE (main)"
    override val serverSilenceMs = 500
    override fun newDet() = TurnDetector(40, endSilenceMs = 500, startFactor = 1.7, startMin = 150.0, loudNoiseFactor = 1.5,
        longSpeechMs = TurnEnd.LONG_SPEECH_MS, longEndSilenceMs = TurnEnd.LONG_END_SILENCE_MS, steadyResetMs = 6_000,
        keepFactor = 1.4, keepMin = 140.0, tailFactor = 0.12)
    override fun early(det: TurnDetector, cap: String, capStableMs: Long, now: Long, capAt: Long) =
        det.silentMs >= TurnEnd.EARLY_END_MS && det.speechMsNow < TurnEnd.LONG_SPEECH_MS && capStableMs >= 200 && TurnEnd.earlyOk(cap) && det.forceEnd()
    override fun hold(cap: String, capAt: Long, lastVoiceAt: Long) = TurnEnd.continues(cap) && TurnEnd.captionFresh(capAt, lastVoiceAt)
}

fun main(args: Array<String>) {
    val cases = load(args[0])
    val pols = mutableListOf<Policy>(OldPolicy())
    for (sp in (System.getenv("VAR") ?: "1200:700:1100:-1").split(",")) { val (a,b,c2,d) = sp.split(":").map { it.toInt() }; pols.add(NewPolicy(a,b,c2,d)) }
    if (System.getenv("NOISEONLY") != null) { noiseReport(cases, pols); return }
    System.getenv("DBG")?.let { d -> d.split(",").forEach { id -> debugCase(cases.first { it.id == id }, pols.last()) }; return }
    for (p in pols) print(report(p, cases))
    if (System.getenv("SLOW") != null) for (p in pols.drop(1)) { println("-- slow ${p.name}"); cases.map { run(it, p) }.filter { it.lat > 900 || it.missed }.forEach { r -> println("  ${r.c.id} lat=${r.lat} subs=${r.subs} end=${r.c.end} lastcap='${r.c.caps.takeLast(3).joinToString(" "){it.second}}'") } }
    if (args.size > 1) for (p in pols) { println("-- ${p.name} झूठे अंत:"); cases.map { run(it, p) }.filter { it.falseEot > 0 || it.missed }.take(40).forEach { println("  ${it.c.id} subs=${it.subs} end=${it.c.end} lat=${it.lat}") } }
}

fun debugCase(c: Case, p: Policy) {
    val r = run(c, p)
    println("${c.id} end=${c.end} pauses=${c.pauses} subs=${r.subs}")
    // frame trace near first sub
    val det = p.newDet()
    for (i in c.rms.indices) { val now = i*40L
        val ws = c.caps.filter { it.first <= now }; val cap = ws.joinToString(" "){it.second}; val capAt = ws.lastOrNull()?.first ?: 0L
        p.before(det, cap, capAt, now); val ev = det.feed(c.rms[i], now)
        if (ev != TurnDetector.Event.NONE || (r.subs.isNotEmpty() && now in (r.subs[0]-700)..(r.subs[0]+40) && i%3==0))
            println("  t=$now rms=${c.rms[i].toInt()} noise=${det.noise.toInt()} ev=$ev in=${det.inSpeech} sil=${det.silentMs} need=${det.endNeedMs} lt=${det.lastTalkAt} pause=${det.typicalPauseMs} cap='${cap.takeLast(30)}' capAt=$capAt lv=${det.lastVoiceAt}")
    }
}
fun noiseReport(cases: List<Case>, pols: List<Policy>) {
    for (p in pols) { val rs = cases.map { run(it, p) }
        println("NOISE ${p.serverSilenceMs}ms noise-only 240s -> false turns = ${rs.sumOf { it.subs.size }} " + rs.groupBy { it.c.cond }.map { (k, v) -> "$k=${v.sumOf { it.subs.size }}" }) }
}
