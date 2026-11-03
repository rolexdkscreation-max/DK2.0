package com.dk.assistant

import kotlin.random.Random

/**
 * Offline simulation (Gradle build इसे compile नहीं करता): 30 मिनट की लगातार बातचीत, Gemini का connection ~10 मिनट पर
 * कटता है (कटने से पहले goAway). पुराना तरीका (सिर्फ़ LiveSpare 1-3) बनाम नया (+ 4 थामो और बदलो) - दोनों असली
 * LiveSpare नियमों से. नापते हैं: server ने अचानक काटा (DK बोलते/आप बोलते), बिना रुकावट बदला, आपकी बात कितनी देर रुकी.
 *
 * मॉडल (अंदाज़ा, असली Gemini नहीं): जुड़ने का समय 400-1500ms (5% नाकाम), handle जवाब पूरा होने के 300ms बाद,
 * goAway कटने से 5s पहले, आप DK के जवाब के बाद 0.2-15s में फिर बोलते.
 */
private const val TICK = 50L
private val BUSY = System.getenv("BUSY") == "1"   // तेज़ बातचीत: 80% रुकना 0.2-0.8s, बाकी 0.8-3s
private const val CUT_AT = 600_000L
private const val GOAWAY_BEFORE = 5_000L

private enum class S { USER, WAIT, PLAY, PAUSE }

class Res {
    var hardCuts = 0; var cutWhilePlaying = 0; var cutWhileUser = 0; var seamless = 0; var plainSwitch = 0
    var freezes = 0; var fallbacks = 0; val userDelays = ArrayList<Long>(); var droppedMs = 0L; var connections = 0
}

fun sim(newPolicy: Boolean, seed: Int, totalMs: Long = 30 * 60_000L): Res {
    val rnd = Random(seed); val r = Res()
    var now = 0L
    var st = S.PAUSE; var stUntil = 1_000L; var replyStartAt = 0L
    var handle = 1; var handleAt = 0L; var lastReplyDone = 0L; var pendingHandleAt = -1L
    var upAt = 0L; r.connections = 1
    var goAway = false; var deadline = 0L
    var spareFrom = -1; var spareReadyAt = -1L; var spareFail = false; var spareTriedAt = -1_000_000L; var spareAt = 0L
    var parkedFrom = -1; var parkedAt = 0L
    var freezeAt = 0L; var freezeEnd = -1_000_000L
    var userSpokeInFreezeAt = -1L    // आप थामे समय कब बोलने लगे
    var deliverBlockedUntil = 0L      // आवाज़ server तक नहीं पहुँच रही (थामे / नया connection बन रहा) - जवाब इसके बाद
    var gapUntil = -1L                // अचानक कटा: नया connection बनने तक
    fun connectMs() = (400 + rnd.nextInt(1100)).toLong()
    fun spareReady() = parkedFrom >= 0 && parkedFrom == handle && now - parkedAt < 5 * 60_000L
    fun idle() = st == S.PAUSE
    fun startSpare() { spareFrom = handle; spareReadyAt = now + connectMs(); spareFail = rnd.nextDouble() < 0.05; spareTriedAt = now; spareAt = now }
    fun switchTo(seamless: Boolean) {
        if (seamless) r.seamless++ else { r.plainSwitch++; deliverBlockedUntil = maxOf(deliverBlockedUntil, now + connectMs()) }
        upAt = now; goAway = false; parkedFrom = -1; spareFrom = -1; spareReadyAt = -1; r.connections++
    }
    while (now < totalMs) {
        now += TICK
        // ---- बातचीत
        if (now >= stUntil) when (st) {
            S.PAUSE -> { st = S.USER; stUntil = now + 1_500 + rnd.nextLong(4_500) }
            S.USER -> { st = S.WAIT; replyStartAt = maxOf(now, deliverBlockedUntil) + 500 + rnd.nextLong(700); stUntil = replyStartAt }
            S.WAIT -> { st = S.PLAY; stUntil = now + 2_000 + rnd.nextLong(8_000) }
            S.PLAY -> { st = S.PAUSE; lastReplyDone = now; pendingHandleAt = now + 300
                val p = rnd.nextDouble() * (if (BUSY) 0.5 else 1.0); stUntil = now + when { p < 0.4 -> 200 + rnd.nextLong(600); p < 0.8 -> 800 + rnd.nextLong(2_200); else -> 3_000 + rnd.nextLong(12_000) } }
        }
        if (st == S.USER && freezeAt > 0 && userSpokeInFreezeAt < 0) userSpokeInFreezeAt = now
        if (pendingHandleAt in 1..now) { handle++; handleAt = now; pendingHandleAt = -1 }
        // ---- standby बन गया?
        if (spareReadyAt in 1..now) { if (!spareFail) { parkedFrom = spareFrom; parkedAt = now }; spareReadyAt = -1 }
        val preparing = spareReadyAt > 0
        if (gapUntil > 0) { if (now >= gapUntil) gapUntil = -1; continue }
        val age = now - upAt
        // ---- server
        if (!goAway && age >= CUT_AT - GOAWAY_BEFORE) { goAway = true; deadline = now + GOAWAY_BEFORE
            if (LiveSpare.shouldPrepare(false, true, true, spareReady(), parkedFrom >= 0 && !spareReady(), idle(), now - spareTriedAt)) startSpare() }
        if (age >= CUT_AT) {
            r.hardCuts++; if (st == S.PLAY) r.cutWhilePlaying++; if (st == S.USER) r.cutWhileUser++
            if (freezeAt > 0) { freezeAt = 0; freezeEnd = now }
            val c = connectMs(); gapUntil = now + c; deliverBlockedUntil = maxOf(deliverBlockedUntil, now + c)
            if (st == S.PLAY) stUntil += c         // जवाब बीच में रुका, दोबारा जुड़ने के बाद
            upAt = now + c; goAway = false; parkedFrom = -1; spareReadyAt = -1; r.connections++
            continue
        }
        // ---- नया: थामो और बदलो
        if (newPolicy) {
            if (freezeAt > 0) {
                when (LiveSpare.freezeStep(spareReady(), handleSame = spareFrom == handle || parkedFrom == handle, preparing = preparing || spareReady(),
                    frozenMs = now - freezeAt, goAway = goAway, msLeft = deadline - now)) {
                    LiveSpare.Freeze.WAIT -> { deliverBlockedUntil = maxOf(deliverBlockedUntil, now + TICK); continue }
                    LiveSpare.Freeze.SWITCH -> { if (userSpokeInFreezeAt > 0) r.userDelays.add(now - userSpokeInFreezeAt); freezeAt = 0; userSpokeInFreezeAt = -1; switchTo(true); continue }
                    LiveSpare.Freeze.RESUME_OLD -> { r.fallbacks++; if (userSpokeInFreezeAt > 0) r.userDelays.add(now - userSpokeInFreezeAt); freezeAt = 0; freezeEnd = now; userSpokeInFreezeAt = -1 }
                    LiveSpare.Freeze.BREAK -> { r.fallbacks++; if (userSpokeInFreezeAt > 0) r.userDelays.add(now - userSpokeInFreezeAt); freezeAt = 0; freezeEnd = now; userSpokeInFreezeAt = -1; switchTo(false); continue }
                }
            }
            val quietFrom = lastReplyDone
            if (LiveSpare.startFreeze(LiveSpare.freezeDue(goAway, age), idle(), freezeAt > 0, spareReady(), true, true,
                    LiveSpare.handleFresh(handleAt, lastReplyDone), now - quietFrom, now - freezeEnd)) {
                freezeAt = now; r.freezes++; userSpokeInFreezeAt = -1
                if (!(preparing && spareFrom == handle)) startSpare()
                continue
            }
        }
        // ---- पुराना (LiveSpare 1-3, असली loop जैसा)
        if (LiveSpare.due(goAway, age) && LiveSpare.shouldPrepare(false, true, true, spareReady(), parkedFrom >= 0 && !spareReady(), idle(), now - spareTriedAt) && !preparing) startSpare()
        if (goAway && (st == S.PAUSE)) {
            if (LiveSpare.waitForSpare(spareReady(), now - spareTriedAt, deadline - now)) continue
            if (newPolicy && !spareReady() && LiveSpare.goAwaySettling(now - freezeEnd >= LiveSpare.FREEZE_RETRY_MS, deadline - now)) continue
            switchTo(spareReady()); continue
        }
        if (!goAway && LiveSpare.rotateNow(age, spareReady(), idle())) { switchTo(true); continue }
    }
    return r
}

fun pct(v: List<Long>, p: Double): Long = if (v.isEmpty()) 0 else v.sorted()[((p * v.size).toInt()).coerceIn(0, v.size - 1)]

fun main() {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    val runs = (System.getenv("RUNS") ?: "300").toInt()
    println(if (BUSY) "तेज़ बातचीत (लगभग हर रुकना छोटा)" else "सामान्य बातचीत")
    for (np in listOf(false, true)) {
        val all = (1..runs).map { sim(np, it) }
        val conns = all.sumOf { it.connections - 1 }
        val d = all.flatMap { it.userDelays }
        println("== ${if (np) "नया (थामो और बदलो)" else "पुराना (main)"}: $runs बातचीत × 30 मिनट, connection बदले $conns")
        println("  server ने अचानक काटा: ${all.sumOf { it.hardCuts }} (DK बोलते: ${all.sumOf { it.cutWhilePlaying }}, आप बोलते: ${all.sumOf { it.cutWhileUser }})")
        println("  बिना रुकावट बदला: ${all.sumOf { it.seamless }} • सामान्य नया connection (शांत पल में): ${all.sumOf { it.plainSwitch }}")
        if (np) println("  थामा: ${all.sumOf { it.freezes }} • पुराने पर लौटा/नाकाम: ${all.sumOf { it.fallbacks }} • थामे समय आप बोले: ${d.size} बार, आपकी बात रुकी median ${pct(d, 0.5)} ms, p95 ${pct(d, 0.95)} ms, max ${d.maxOrNull() ?: 0} ms")
    }
}
