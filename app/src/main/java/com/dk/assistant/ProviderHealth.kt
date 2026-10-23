package com.dk.assistant

import java.util.concurrent.ConcurrentHashMap

/**
 * Har AI key ki sehat: kaunsi key abhi chal rahi hai, kaunsi kitni tez hai, kaunsi limit/galti me hai.
 * DK isi se tay karta hai ki pehle kis key se jawab mangna hai (sabse tez + chalti hui pehle).
 */
object ProviderHealth {
    private class H { var until = 0L; var reason = ""; var ema = 0.0; var ok = 0; var strikes = 0; var daily = false }
    private val map = ConcurrentHashMap<String, H>()
    private fun h(id: String) = map.getOrPut(id) { H() }

    /** pehli baar ka andaza (ms) - Groq sabse tez */
    private fun prior(id: String) = when (id) { "groq" -> 700.0; "gemini" -> 1300.0; else -> 2200.0 }

    /** रोज़ की limit फ़ोन पर सेव (ऐप दोबारा खुलने पर भी उसी key को बार-बार न मारें) */
    private fun dayKey(id: String) = "ph_day_$id"

    fun usable(id: String) = System.currentTimeMillis() > h(id).until && !dailyOut(id)

    /** इस key की आज की फ्री limit खत्म - अगले दिन तक बिल्कुल मत आज़माओ */
    fun dailyOut(id: String): Boolean {
        val x = h(id)
        if (x.daily && System.currentTimeMillis() < x.until) return true
        val u = try { Cooldown.until(dayKey(id)) } catch (_: Throwable) { 0L }   // unit test में Android नहीं
        if (u > System.currentTimeMillis()) { x.daily = true; x.until = maxOf(x.until, u); if (x.reason.isBlank()) x.reason = dailyReason(u); return true }
        return false
    }

    /** limit/गलती वाली key: अभी दोबारा आज़माना ठीक है? (रोज़ की limit / गलत key = नहीं) */
    fun retryable(id: String): Boolean = !dailyOut(id) && !h(id).reason.startsWith("key")

    private fun dailyReason(at: Long) = "आज की फ्री लिमिट खत्म (${RateLimit.clock(at)} फिर मिलेगी)"
    fun speed(id: String): Double = h(id).let { if (it.ok == 0) prior(id) else it.ema }

    fun ok(id: String, firstTokenMs: Long) {
        val x = h(id)
        x.ema = if (x.ok == 0) firstTokenMs.toDouble() else x.ema * 0.6 + firstTokenMs * 0.4
        x.ok++; x.until = 0; x.reason = ""; x.strikes = 0; x.daily = false
    }

    fun fail(id: String, e: Throwable) {
        val x = h(id)
        val code = (e as? AiException)?.code ?: 0
        val now = System.currentTimeMillis()
        if (RateLimit.daily(e)) {
            val at = RateLimit.nextUtcMidnight(now)
            x.until = at; x.daily = true; x.reason = dailyReason(at)
            try { Cooldown.block(dayKey(id), at - now) } catch (_: Throwable) {}
            x.ema = maxOf(x.ema, prior(id)) + 800
            return
        }
        if (code == 429) x.strikes++ else x.strikes = 0
        val wait = when {
            code == 401 || code == 403 -> 30 * 60_000L
            code == 404 -> 10 * 60_000L
            code == 429 -> RateLimit.backoffMs(x.strikes, (e as? AiException)?.retryAfter ?: 0)
            e is java.net.UnknownHostException -> 5_000L
            e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException -> 20_000L
            else -> 15_000L
        }
        x.until = now + wait
        x.reason = short(e)
        // dheemi key ko peeche karo
        x.ema = maxOf(x.ema, prior(id)) + 800
    }

    fun reason(id: String) = h(id).reason

    /** Galti ka chhota Hindi kaaran (DK bolkar batata hai) */
    fun short(e: Throwable): String {
        val code = (e as? AiException)?.code ?: 0
        val msg = e.message ?: ""
        return when {
            e is java.net.UnknownHostException -> "इंटरनेट नहीं मिल रहा"
            e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException -> "सर्वर बहुत धीमा है"
            RateLimit.daily(e) -> dailyReason(RateLimit.nextUtcMidnight(System.currentTimeMillis()))
            code == 429 -> "फ्री लिमिट अभी खत्म है"
            code == 401 || code == 403 || msg.contains("API key", true) -> "key गलत है या बंद है"
            code == 404 -> "मॉडल का नाम गलत है"
            code == 400 -> "मॉडल ने सवाल नहीं लिया"
            code >= 500 -> "सर्वर में दिक्कत है"
            msg == "NO_KEY" -> "कोई key नहीं डली"
            else -> "जवाब नहीं आया"
        }
    }
}
