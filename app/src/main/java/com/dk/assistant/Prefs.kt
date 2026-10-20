package com.dk.assistant

import android.content.Context
import android.content.SharedPreferences

/** App ki saari settings phone me save hoti hain (naam, API keys, model, awaaz, options). */
object Prefs {
    private fun sp(c: Context): SharedPreferences {
        val p = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
        // Purane version (sirf Gemini) ki key ko naye format me le aao
        if (p.contains("api_key")) {
            val old = p.getString("api_key", "") ?: ""
            val oldModel = p.getString("model", "") ?: ""
            p.edit().apply {
                if (old.isNotBlank()) putString("key_gemini", old)
                if (oldModel.isNotBlank()) putString("model_gemini", oldModel)
                remove("api_key"); remove("model")
            }.apply()
        }
        return p
    }

    // ---- profile
    fun userName(c: Context) = sp(c).getString("user_name", "") ?: ""
    fun assistantName(c: Context) = (sp(c).getString("assistant_name", "DK") ?: "DK").ifBlank { "DK" }
    fun personality(c: Context) = sp(c).getString("personality", "") ?: ""
    fun wakeWords(c: Context): List<String> {
        val raw = sp(c).getString("wake_words", "डीके, दीके, डी के, dk, d k") ?: ""
        val list = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableList()
        list.add(assistantName(c).lowercase())
        return list.distinct()
    }
    fun wakeWordsRaw(c: Context) = sp(c).getString("wake_words", "डीके, दीके, डी के, dk, d k") ?: ""

    // ---- AI providers
    fun activeProvider(c: Context) = sp(c).getString("active_provider", "gemini") ?: "gemini"
    fun fallback(c: Context) = sp(c).getBoolean("fallback", true)
    fun key(c: Context, id: String) = sp(c).getString("key_$id", "") ?: ""
    fun model(c: Context, id: String): String {
        val def = Providers.byId(id)?.defaultModel ?: ""
        return (sp(c).getString("model_$id", def) ?: def).ifBlank { def }
    }
    fun baseUrl(c: Context, id: String): String {
        val def = Providers.byId(id)?.baseUrl ?: ""
        return (sp(c).getString("base_$id", def) ?: def).ifBlank { def }
    }
    fun hasAnyKey(c: Context) = Providers.all.any { key(c, it.id).isNotBlank() }

    // ---- options
    fun wakeMode(c: Context) = sp(c).getBoolean("wake_mode", false)
    fun autoUpdate(c: Context) = sp(c).getBoolean("auto_update", true)
    fun lastUpdateCheck(c: Context) = sp(c).getLong("upd_checked", 0L)
    fun liveMode(c: Context) = sp(c).getBoolean("live_mode", false)
    fun screenshot(c: Context) = sp(c).getBoolean("screenshot", true)
    fun ttsRate(c: Context) = sp(c).getFloat("tts_rate", 1.05f)
    fun ttsPitch(c: Context) = sp(c).getFloat("tts_pitch", 1.0f)

    // ---- awaaz (mahila/purush)
    fun voiceGender(c: Context) = sp(c).getString("voice_gender", "female") ?: "female"
    /** "google" = phone ki awaaz, "gemini" = sabse natural (Gemini key se) */
    fun voiceEngine(c: Context) = sp(c).getString("voice_engine", "google") ?: "google"
    fun googleVoice(c: Context) = sp(c).getString("google_voice", "") ?: ""
    fun geminiVoice(c: Context): String {
        val g = voiceGender(c)
        val v = sp(c).getString("gemini_voice", "") ?: ""
        return if (v.isNotBlank() && v in VoiceCatalog.geminiFor(g)) v else VoiceCatalog.geminiFor(g).first()
    }

    // ---- sunna: "auto" = bina beep (Groq/Gemini key ho to), "google" = Google mic (beep aa sakti hai)
    fun listenEngine(c: Context) = sp(c).getString("listen_engine", "auto") ?: "auto"

    fun announceCalls(c: Context) = sp(c).getBoolean("announce_calls", true)
    /** Sawaal ka sahi/taaza jawab Google Search se (Gemini key ho to) */
    fun webAnswers(c: Context) = sp(c).getBoolean("web_answers", true)

    // ---- provider ke models ki list (cache)
    fun models(c: Context, id: String): List<String> =
        (sp(c).getString("models_$id", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun edit(c: Context, block: SharedPreferences.Editor.() -> Unit) {
        val e = sp(c).edit(); e.block(); e.commit()   // commit = turant disk par save
    }
}
