package com.dk.assistant

/**
 * 18 नवंबर 2026 (मालिक का लॉग): मुफ़्त (free tier) Gemini key पर Gemini 3+ के साथ googleSearch चलता ही नहीं -
 * Google सिर्फ़ "You exceeded your current quota…" वाला 429 देता है (न "tool" न "grounding" शब्द, न मॉडल की limit का ब्योरा).
 * जड़: चैट के हर जवाब में googleSearch चालू था, तो हर "हेलो" पर 429 और "फ्री लिमिट अभी खत्म है" - जबकि उसी key से Live चल रहा था.
 *
 * अब: खोज के साथ 429 (कुछ भी आने से पहले) = उसी मॉडल से बिना खोज एक बार. बिना खोज चल गया = यह key खोज नहीं ले सकती -
 * [BLOCK_MS] तक इस key पर चैट सीधे बिना खोज (हर संदेश पर बेकार request नहीं). बिना खोज भी 429 = असली limit (पहले जैसा संदेश).
 * key का पूरा text कहीं नहीं रखा जाता - सिर्फ़ hash, और सिर्फ़ इसी चलते ऐप की याद में. Pure (JUnit).
 */
object SearchBlock {
    const val BLOCK_MS = 6 * 60 * 60_000L
    const val MSG = "google_search grounding not available on this key (Gemini free tier)"

    private val until = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private fun id(key: String) = key.trim().hashCode()

    /** खोज वाले जवाब की यह गलती = बिना खोज एक बार आज़माओ? */
    fun retryWithout(code: Int): Boolean = code == 429

    /** बिना खोज चल गया - इस key पर कुछ देर खोज नहीं */
    fun mark(key: String, now: Long = System.currentTimeMillis()) {
        if (key.isNotBlank()) until[id(key)] = now + BLOCK_MS
    }

    fun blocked(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val u = until[id(key)] ?: return false
        if (u > now) return true
        until.remove(id(key)); return false
    }

    /** test / नई key */
    fun clear() = until.clear()
}
