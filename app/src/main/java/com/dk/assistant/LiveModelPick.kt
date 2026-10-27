package com.dk.assistant

import android.content.Context

/**
 * v3.80: DK लाइव का मॉडल खुद चुनना (आवाज़ वाले हिस्से में). "" = अपने-आप (सबसे तेज़ / जो चले).
 * चुना मॉडल हमेशा सूची में सबसे पहले - न चले तो बाकी पहले जैसे बारी-बारी.
 */
object LiveModelPick {
    private const val KEY = "live_model_pick"

    fun get(c: Context): String = com.dk.assistant.voice.engine.VoiceEngines.sp(c).getString(KEY, "") ?: ""

    fun set(c: Context, model: String) {
        if (model.trim() == get(c)) return
        com.dk.assistant.voice.engine.VoiceEngines.sp(c).edit().putString(KEY, model.trim()).apply()
        com.dk.assistant.voice.engine.VoiceEngines.liveChanged()
    }

    /** चुना मॉडल सबसे आगे (सूची में न हो तब भी - यूज़र ने चुना है; न चले तो अगला) */
    fun order(pick: String, models: List<String>): List<String> {
        val p = pick.trim()
        if (p.isBlank()) return models
        return listOf(p) + models.filter { it != p }
    }

    /** दिखाने लायक सूची: पिछली बार मिले मॉडल + जाने-माने, बिना दोहराव */
    fun choices(c: Context): List<String> {
        val cached = LiveModelCache.decode(Prefs.liveModelCache(c))?.third.orEmpty()
        return (listOf(Prefs.liveModel(c)) + cached + GeminiLive.FALLBACK_MODELS)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }
}
