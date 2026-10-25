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
        // पहली बार: पुरानी साझा keys से DK लाइव / कोडिंग / कंप्यूटर की अपनी-अपनी keys (चैट = पुरानी ही)
        if (!scopesDone) synchronized(this) {
            if (!scopesDone) {
                val add = ApiScopes.migrate { k -> if (p.contains(k)) p.all[k]?.toString() else null }
                if (add.isNotEmpty()) p.edit().apply { add.forEach { (k, v) -> if (!p.contains(k) || k == ApiScopes.MIGRATED) putString(k, v) } }.commit()
                scopesDone = true
            }
        }
        return p
    }
    @Volatile private var scopesDone = false

    // ---- हर हिस्से (DK लाइव / DK चैट / कोडिंग एजेंट / DK कंप्यूटर) की अपनी API - देखें [ApiScopes]
    /** इस हिस्से की मुख्य कंपनी (DK लाइव = हमेशा Gemini) */
    fun scopeActive(c: Context, scope: String): String {
        if (scope == ApiScopes.LIVE) return "gemini"
        if (scope == ApiScopes.CHAT) return activeProvider(c)
        val id = sp(c).getString(ApiScopes.activePref(scope), "") ?: ""
        return if (Providers.byId(id) != null) id else activeProvider(c)
    }
    fun scopeKey(c: Context, scope: String, id: String) = (sp(c).getString(ApiScopes.pref(scope, "key", id), "") ?: "").trim()
    fun scopeModel(c: Context, scope: String, id: String): String {
        val def = Providers.byId(id)?.defaultModel ?: ""
        return (sp(c).getString(ApiScopes.pref(scope, "model", id), def) ?: def).ifBlank { def }
    }
    fun scopeBase(c: Context, scope: String, id: String): String {
        val def = Providers.byId(id)?.baseUrl ?: ""
        return (sp(c).getString(ApiScopes.pref(scope, "base", id), def) ?: def).ifBlank { def }
    }
    /** उसी key के बैकअप मॉडल (limit खत्म हो तो) - दूसरी कंपनी नहीं */
    fun scopeBackups(c: Context, scope: String, id: String): List<String> =
        ApiScopes.backupModels(id, scopeModel(c, scope, id), fastModels(c, id), models(c, id))

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
    /**
     * दूसरी कंपनी की API पर अपने-आप जाना: अब हमेशा बंद - हर हिस्सा सिर्फ़ अपनी चुनी API से जवाब लेता है
     * (limit खत्म हो तो उसी key के दूसरे मॉडल पर). पुरानी "चालू" setting नज़रअंदाज़.
     */
    @Suppress("UNUSED_PARAMETER")
    fun fallback(c: Context) = false
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

    /** सुनने (आवाज़ → text) की keys - सिर्फ़ DK लाइव की अपनी: Groq (Whisper, तेज़) फिर Gemini */
    fun sttKeys(c: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        scopeKey(c, ApiScopes.LIVE, "groq").takeIf { it.isNotBlank() }?.let { out.add("groq" to it) }
        scopeKey(c, ApiScopes.LIVE, "gemini").takeIf { it.isNotBlank() }?.let { out.add("gemini" to it) }
        return out.distinctBy { it.second }
    }

    /** Gemini Live और Gemini आवाज़ (बोलना) की key - सिर्फ़ DK लाइव की अपनी Gemini key */
    fun ttsGeminiKey(c: Context): String = scopeKey(c, ApiScopes.LIVE, "gemini")

    /**
     * v3.41: "आप:" लिखने वाले (gemini-3.5-transcribe-live) की अपनी अलग key - बोलने वाले Live की free limit
     * इसमें खर्च न हो. खाली = पुराना तरीका (DK लाइव वाली Gemini key ही).
     */
    const val CAPTION_KEY = "live_key_caption"
    fun captionOwnKey(c: Context): String = sp(c).getString(CAPTION_KEY, "")?.trim() ?: ""
    fun captionKey(c: Context): String = captionOwnKey(c).ifBlank { ttsGeminiKey(c) }

    // ---- options
    fun wakeMode(c: Context) = sp(c).getBoolean("wake_mode", false)
    fun autoUpdate(c: Context) = sp(c).getBoolean("auto_update", true)
    fun lastUpdateCheck(c: Context) = sp(c).getLong("upd_checked", 0L)
    fun liveMode(c: Context) = sp(c).getBoolean("live_mode", false)
    /** orb ka aakaar: earth, galaxy, atom, torus, geodesic, knot, dyson, face_male, face_female */
    /** Beech ka orb chhota (minimize) kiya hua hai? */
    fun orbMin(c: Context) = sp(c).getBoolean("orb_min", false)
    // ---- v3.66: सिर्फ़ मालिक की आवाज़ (voiceprint सिर्फ़ इसी फ़ोन में - backup बंद है, GitHub/इंटरनेट पर नहीं)
    /** परत 3: सिर्फ़ मालिक की आवाज़ पर जवाब */
    fun ownerVoiceOn(c: Context) = sp(c).getBoolean("owner_voice_on", false)
    /** सख़्त = दूसरे लगभग कभी पास नहीं, मालिक को कभी-कभी दोबारा बोलना पड़े */
    fun ownerVoiceStrict(c: Context) = sp(c).getBoolean("owner_voice_strict", false)
    /** परत 4: दूर की / बहुत धीमी आवाज़ अनसुनी */
    fun ownerFarOn(c: Context) = sp(c).getBoolean("owner_far_on", true)
    fun ownerPrint(c: Context): FloatArray? = com.dk.assistant.voice.VoicePrint.decode(sp(c).getString("owner_print", null))
    /** सीखते समय मालिक की आवाज़ का स्तर (RMS) */
    fun ownerLevel(c: Context) = sp(c).getFloat("owner_level", 0f).toDouble()
    /** पहचानी आवाज़ से धीरे-धीरे सीखा स्तर */
    fun ownerLevelNow(c: Context) = sp(c).getFloat("owner_level_now", 0f).toDouble()
    /** v3.65: हटाया गया आकार (dna/tesseract/wormhole/sphere3d) या अनजान -> earth */
    fun orbStyle(c: Context) = Shapes.valid(sp(c).getString("orb_style", "earth"))
    /** v3.57: DK सुन/बोल रहा हो तो स्क्रीन के किनारों पर चमक */
    fun edgeGlow(c: Context) = sp(c).getBoolean("edge_glow", true)
    /** v3.58: पहली बार का परिचय पूरा/छोड़ा (पुराने उपयोगकर्ता पर अपने-आप true) */
    fun onboarded(c: Context) = sp(c).getBoolean("onboarded", false)
    /** v3.59: यूज़र ने खुद माइक/लाइव बंद किया - जब तक वह खुद चालू न करे, माइक अपने-आप नहीं खुलेगा (wake word भी नहीं) */
    fun micUserOff(c: Context) = sp(c).getBoolean("mic_user_off", false)
    fun screenshot(c: Context) = sp(c).getBoolean("screenshot", true)
    fun ttsRate(c: Context) = sp(c).getFloat("tts_rate", 1.05f)
    fun ttsPitch(c: Context) = sp(c).getFloat("tts_pitch", 1.0f)

    // ---- awaaz (mahila/purush)
    /** v3.30: DK के जवाब की भाषा - "hinglish" (शुरुआती) / "hi" / "en" (Lang) */
    /** v3.59: कुछ न चुना हो तो हिंदी (पहले Hinglish). जिसने Settings में चुना, उसकी पसंद वैसी ही */
    fun replyLang(c: Context) = Lang.norm(sp(c).getString(Lang.KEY, Lang.HINDI) ?: Lang.HINDI)
    fun loadLang(c: Context) { Lang.current = replyLang(c) }
    fun voiceGender(c: Context) = sp(c).getString("voice_gender", "female") ?: "female"
    /** "google" = phone ki awaaz, "gemini" = sabse natural (Gemini key se) */
    fun voiceEngine(c: Context) = sp(c).getString("voice_engine", "google") ?: "google"
    fun voiceStyle(c: Context) = sp(c).getString("voice_style", "natural") ?: "natural"
    fun googleVoice(c: Context) = sp(c).getString("google_voice", "") ?: ""
    /** v3.59: किस मॉडल पर कौन-सी Live चीज़ (आवाज़/hi-IN/affective) setup तोड़ती है - अगली बार उसके बिना */
    fun liveExtrasOff(c: Context): Set<String> = LiveVoice.parseOff(sp(c).getString(LiveVoice.OFF_KEY, ""))
    fun addLiveExtrasOff(c: Context, entry: String) = edit(c) { putString(LiveVoice.OFF_KEY, LiveVoice.joinOff(liveExtrasOff(c) + entry)) }
    fun geminiVoice(c: Context): String {
        // v3.59: कुछ न चुना हो तो हमेशा Sulafat (LiveVoice) - पुरुष आवाज़ चुनी हो तो पुरुष सूची की पहली
        return LiveVoice.pick(sp(c).getString("gemini_voice", "") ?: "", voiceGender(c))
    }

    // ---- sunna: "auto" = Gemini Live (key ho to) warna bina beep, "classic" = Whisper+AI+TTS, "google" = Google mic (beep aa sakti hai)
    fun listenEngine(c: Context) = sp(c).getString("listen_engine", "auto") ?: "auto"

    /** Pichhli baar jo Gemini Live model chala */
    fun liveModel(c: Context) = sp(c).getString("live_model", "") ?: ""

    // ---- v3.45: सच्चा दोस्त
    /** दोस्त वाला लहजा + पिछली बातचीत की याद system instruction में */
    fun friendMode(c: Context) = sp(c).getBoolean("friend_mode", true)
    /** ऐप खुलते ही DK खुद बात शुरू करे (DK लाइव चालू करके) */
    fun friendOpener(c: Context) = sp(c).getBoolean("friend_opener", true)
    /** स्थानीय खबर (Google Search) आखिरी बार कब - 3 घंटे में एक बार */
    fun lastLocalNewsAt(c: Context) = sp(c).getLong("friend_local_news_at", 0L)

    /** v3.41: हर Live मॉडल का नापा जवाब-समय (ms) - सबसे तेज़ मॉडल पहले लगे */
    /** Gemini Live models ki list ka cache (LiveModelCache.encode) - app restart ke baad bhi turant jode */
    fun liveModelCache(c: Context): String = sp(c).getString("live_models_cache", "") ?: ""
    fun saveLiveModelCache(c: Context, v: String) = edit(c) { putString("live_models_cache", v) }

    fun liveLatency(c: Context): Map<String, Long> = LiveModelRank.decode(sp(c).getString("live_latency", "") ?: "")
    fun recordLiveLatency(c: Context, model: String, ms: Long) {
        val m = liveLatency(c).toMutableMap()
        val v = LiveModelRank.blend(m[model], ms) ?: return
        m[model] = v
        edit(c) { putString("live_latency", LiveModelRank.encode(m)) }
    }

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
    /** कोडिंग एजेंट: एक काम में ज़्यादा से ज़्यादा कदम (3-30, default 5) */
    fun coderMaxSteps(c: Context) = (sp(c).getString("coder_max_steps", null)?.trim()?.toIntOrNull() ?: CodingAgent.DEFAULT_STEPS).coerceIn(3, 30)
    fun coderBuildFix(c: Context) = sp(c).getBoolean("coder_buildfix", true)
    /** सुरक्षित PR मोड (default चालू): main पर सीधे push नहीं - नई feature branch → PR → tests/build → आप review करके Merge */
    /** सुरक्षित PR मोड अब हमेशा चालू: main पर कभी सीधे push नहीं (पुरानी "बंद" setting भी नज़रअंदाज़) */
    @Suppress("UNUSED_PARAMETER")
    fun coderSafePr(c: Context) = true
    /** Multi-agent coding team (Builder -> Reviewer -> Coordinator), ek baar bolo baaki apne-aap */
    fun coderTeam(c: Context) = sp(c).getBoolean("coder_team", true)
    /** 7 एजेंट वाला नया सिस्टम (मैनेजर/प्लानर/कोडर/रिव्यूअर/टेस्टर/डिबगर/गिट). बंद = पुराना एजेंट/टीम जैसी की तैसी */
    fun coderSeven(c: Context): Boolean {
        val p = sp(c)
        // 11-एजेंट सिस्टम अब default चालू (एक बार, user के कहने पर). बाद में Settings से बंद किया तो बंद ही रहेगा
        if (!p.getBoolean("eleven_default_v1", false)) p.edit().putBoolean("coder_seven", true).putBoolean("eleven_default_v1", true).apply()
        return p.getBoolean("coder_seven", true)
    }
    /** user ने खुद नॉर्मल मोड चुना (GitHub होते हुए भी गिट के बिना) */
    fun coderNormal(c: Context) = sp(c).getBoolean("coder_normal", false)
    /** Coding agent ka khula PR (app restart ke baad bhi "Merge करें" card ke liye) */
    fun coderPrRepo(c: Context) = sp(c).getString("coder_pr_repo", "") ?: ""
    fun coderPrBase(c: Context) = sp(c).getString("coder_pr_base", "") ?: ""
    fun coderPrNum(c: Context) = sp(c).getInt("coder_pr_num", 0)
    fun coderPrUrl(c: Context) = sp(c).getString("coder_pr_url", "") ?: ""
    fun coderPrHead(c: Context) = sp(c).getString("coder_pr_head", "") ?: ""
    /** User bole to peeche ka gaana/video dheema (~20%), jawab ke baad wapas */
    fun duckMedia(c: Context) = sp(c).getBoolean("duck_media", true)
    /** v3.67: WhatsApp संदेश बिना "भेज दूँ?" पूछे भेजो - मालिक खुद चालू करे (default बंद) */
    fun waAutoSend(c: Context) = sp(c).getBoolean("wa_auto_send", false)

    // ---- DK कंप्यूटर (कई कदम वाला एजेंट: search, वेब पढ़ना, कोड, फ़ाइलें, फ़ोन, कोडिंग, रिमाइंडर)
    fun computerOn(c: Context) = sp(c).getBoolean("pc_on", true)
    /** "same" = वही AI जो बातचीत में; वरना provider id */
    fun computerProvider(c: Context) = sp(c).getString("pc_provider", "same") ?: "same"
    fun computerModel(c: Context) = sp(c).getString("pc_model", "") ?: ""
    /** auto | tavily | brave | gemini */
    fun computerSearch(c: Context) = sp(c).getString("pc_search", "auto") ?: "auto"
    fun tavilyKey(c: Context) = sp(c).getString("key_tavily", "") ?: ""
    fun braveKey(c: Context) = sp(c).getString("key_brave", "") ?: ""
    /** मैसेज/कॉल/पेमेंट जैसे फ़ोन के काम से पहले पूछो (default चालू) */
    fun computerConfirm(c: Context) = sp(c).getBoolean("pc_confirm", true)
    fun computerMaxSteps(c: Context) = sp(c).getInt("pc_max_steps", 40).coerceIn(5, 60)
    /** कौन-से टूल चालू (default सब) */
    fun computerTool(c: Context, id: String) = sp(c).getBoolean("pc_tool_$id", true)

    // ---- provider ke models ki list (cache)
    fun models(c: Context, id: String): List<String> =
        (sp(c).getString("models_$id", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun edit(c: Context, block: SharedPreferences.Editor.() -> Unit) {
        val e = sp(c).edit(); e.block(); e.commit()   // commit = turant disk par save
    }
}
