package com.dk.assistant

import java.util.concurrent.ConcurrentHashMap

/**
 * Har AI key ki sehat: kaunsi key abhi chal rahi hai, kaunsi kitni tez hai, kaunsi limit/galti me hai.
 * DK isi se tay karta hai ki pehle kis key se jawab mangna hai (sabse tez + chalti hui pehle).
 */
object ProviderHealth {
    private class H { var until = 0L; var reason = ""; var ema = 0.0; var ok = 0 }
    private val map = ConcurrentHashMap<String, H>()
    private fun h(id: String) = map.getOrPut(id) { H() }

    /** pehli baar ka andaza (ms) - Groq sabse tez */
    private fun prior(id: String) = when (id) { "groq" -> 700.0; "gemini" -> 1300.0; "mistral" -> 1600.0; else -> 2200.0 }

    fun usable(id: String) = System.currentTimeMillis() > h(id).until
    fun speed(id: String): Double = h(id).let { if (it.ok == 0) prior(id) else it.ema }

    fun ok(id: String, firstTokenMs: Long) {
        val x = h(id)
        x.ema = if (x.ok == 0) firstTokenMs.toDouble() else x.ema * 0.6 + firstTokenMs * 0.4
        x.ok++; x.until = 0; x.reason = ""
    }

    fun fail(id: String, e: Throwable) {
        val x = h(id)
        val code = (e as? AiException)?.code ?: 0
        val wait = when {
            code == 401 || code == 403 -> 30 * 60_000L
            code == 404 -> 10 * 60_000L
            code == 429 -> 60_000L
            e is java.net.UnknownHostException -> 5_000L
            e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException -> 20_000L
            else -> 15_000L
        }
        x.until = System.currentTimeMillis() + wait
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
