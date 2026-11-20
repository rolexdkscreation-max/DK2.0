package com.dk.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 20 नवंबर 2026 (मालिक, लॉग: deep_think में 41 s रुकावट): जड़ - हर कोशिश के अपने लंबे timeout थे (चिप 20 s + Z.ai/NVIDIA/Groq
 * 20 s + Gemini 45 s) और `withTimeoutOrNull` के अंदर `withContext(IO)` वाली blocking HTTP call समय पूरा होने पर भी
 * नहीं रुकती (वह call लौटने तक इंतज़ार). अब: हर कोशिश ज़्यादा से ज़्यादा [STEP_MS], पूरा tool [TOTAL_MS] - समय पूरा = उसी
 * पल अगली key/कंपनी (पुरानी call पीछे अपने-आप ख़त्म होती है, बातचीत उसका इंतज़ार नहीं करती).
 */
object ThinkTime {
    /** एक key/कंपनी की एक कोशिश */
    const val STEP_MS = 8_000L
    /** पूरा deep_think tool */
    const val TOTAL_MS = 12_000L

    /** इस कोशिश को कितना समय मिले (पूरे tool का बचा समय भी देखो). 0 = समय ख़त्म. Pure - JUnit से जाँच */
    fun stepMs(deadline: Long, now: Long): Long = minOf(STEP_MS, deadline - now).coerceAtLeast(0L)

    private val pool = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [block] अलग से चलाओ, [ms] में न लौटे = null (block की blocking call हमें नहीं रोकती) */
    suspend fun <T> within(ms: Long, block: suspend () -> T): T? {
        if (ms <= 0L) return null
        val d = pool.async { block() }
        return withTimeoutOrNull(ms) { d.await() } ?: run { d.cancel(); null }
    }
}
