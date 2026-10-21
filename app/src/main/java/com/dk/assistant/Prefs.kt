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
    /** Hata di gayi company (Mistral, Z.ai, OpenAI, xAI) chuni thi to Gemini */
    fun activeProvider(c: Context): String {
        val id = sp(c).getString("active_provider", "gemini") ?: "gemini"
        return if (Providers.byId(id) != null) id else "gemini"
    }
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

    /** Auto-setup ne jo free models sabse tez paaye (tez pehle) - backup ke liye */
    fun fastModels(c: Context, id: String): List<String> =
        (sp(c).getString("fast_$id", "") ?: "").split('\n').filter { it.isNotBlank() }

    // ---- AAWAZ (sunna/bolna) ki ALAG key - taaki jawab wali key ki limit se takraav na ho
    fun voiceKey(c: Context) = sp(c).getString("voice_key", "") ?: ""
    fun voiceProvider(c: Context) = sp(c).getString("voice_provider", "") ?: ""

    /** Sunne (awaaz -> text) ke liye keys, kram se: pehle alag awaaz wali key, phir jawab wali Groq/Gemini key */
    fun sttKeys(c: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val vk = voiceKey(c)
        val vp = voiceProvider(c).ifBlank { KeySetup.detect(vk) ?: "" }
        if (vk.isNotBlank() && (vp == "groq" || vp == "gemini")) out.add(vp to vk)
        key(c, "groq").takeIf { it.isNotBlank() }?.let { out.add("groq" to it) }
        key(c, "gemini").takeIf { it.isNotBlank() }?.let { out.add("gemini" to it) }
        return out.distinctBy { it.second }
    }

    /** Gemini awaaz (bolna) ke liye key: alag awaaz wali Gemini key ho to wahi, warna jawab wali */
    fun ttsGeminiKey(c: Context): String {
        val vk = voiceKey(c)
        val vp = voiceProvider(c).ifBlank { KeySetup.detect(vk) ?: "" }
        return if (vk.isNotBlank() && vp == "gemini") vk else key(c, "gemini")
    }

    // ---- options
    fun wakeMode(c: Context) = sp(c).getBoolean("wake_mode", false)
    fun autoUpdate(c: Context) = sp(c).getBoolean("auto_update", true)
    fun lastUpdateCheck(c: Context) = sp(c).getLong("upd_checked", 0L)
    fun liveMode(c: Context) = sp(c).getBoolean("live_mode", false)
    /** orb ka aakaar: earth, galaxy, atom, dna, torus, face_male, face_female */
    /** Beech ka orb chhota (minimize) kiya hua hai? */
    fun orbMin(c: Context) = sp(c).getBoolean("orb_min", false)
    fun orbStyle(c: Context) = sp(c).getString("orb_style", "earth") ?: "earth"
    fun screenshot(c: Context) = sp(c).getBoolean("screenshot", true)
    fun ttsRate(c: Context) = sp(c).getFloat("tts_rate", 1.05f)
    fun ttsPitch(c: Context) = sp(c).getFloat("tts_pitch", 1.0f)

    // ---- awaaz (mahila/purush)
    fun voiceGender(c: Context) = sp(c).getString("voice_gender", "female") ?: "female"
    /** "google" = phone ki awaaz, "gemini" = sabse natural (Gemini key se) */
    fun voiceEngine(c: Context) = sp(c).getString("voice_engine", "google") ?: "google"
    fun voiceStyle(c: Context) = sp(c).getString("voice_style", "natural") ?: "natural"
    fun googleVoice(c: Context) = sp(c).getString("google_voice", "") ?: ""
    fun geminiVoice(c: Context): String {
        val g = voiceGender(c)
        val v = sp(c).getString("gemini_voice", "") ?: ""
        return if (v.isNotBlank() && v in VoiceCatalog.geminiFor(g)) v else VoiceCatalog.geminiFor(g).first()
    }

    // ---- sunna: "auto" = Gemini Live (key ho to) warna bina beep, "classic" = Whisper+AI+TTS, "google" = Google mic (beep aa sakti hai)
    fun listenEngine(c: Context) = sp(c).getString("listen_engine", "auto") ?: "auto"

    /** Pichhli baar jo Gemini Live model chala */
    fun liveModel(c: Context) = sp(c).getString("live_model", "") ?: ""

    fun micDeniedOnce(c: Context) = sp(c).getBoolean("mic_denied", false)

    fun announceCalls(c: Context) = sp(c).getBoolean("announce_calls", true)
    /** Sawaal ka sahi/taaza jawab Google Search se (Gemini key ho to) */
    fun webAnswers(c: Context) = sp(c).getBoolean("web_answers", true)

    // ---- coding agent (default BAND) + GitHub connector
    fun coderOn(c: Context) = sp(c).getBoolean("coder_on", false)
    fun ghToken(c: Context) = sp(c).getString("gh_token", "") ?: ""
    fun ghRepo(c: Context) = sp(c).getString("gh_repo", "") ?: ""
    fun ghBranch(c: Context) = (sp(c).getString("gh_branch", "") ?: "").ifBlank { "main" }
    /** "same" = wahi AI jo baatcheet me; warna provider id (alag key) */
    fun coderProvider(c: Context) = sp(c).getString("coder_provider", "same") ?: "same"
    fun coderKey(c: Context) = sp(c).getString("coder_key", "") ?: ""
    fun coderModel(c: Context) = sp(c).getString("coder_model", "") ?: ""
    fun coderBase(c: Context) = sp(c).getString("coder_base", "") ?: ""
    /** true = bina pooche push (default: pehle dikhao, phir "Push" dabane par) */
    fun coderAutoPush(c: Context) = sp(c).getBoolean("coder_autopush", false)
    fun coderBuildFix(c: Context) = sp(c).getBoolean("coder_buildfix", true)
    /** Multi-agent coding team (Builder -> Reviewer -> Coordinator), ek baar bolo baaki apne-aap */
    fun coderTeam(c: Context) = sp(c).getBoolean("coder_team", true)
    /** User bole to peeche ka gaana/video dheema (~20%), jawab ke baad wapas */
    fun duckMedia(c: Context) = sp(c).getBoolean("duck_media", true)

    // ---- provider ke models ki list (cache)
    fun models(c: Context, id: String): List<String> =
        (sp(c).getString("models_$id", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun edit(c: Context, block: SharedPreferences.Editor.() -> Unit) {
        val e = sp(c).edit(); e.block(); e.commit()   // commit = turant disk par save
    }
}
