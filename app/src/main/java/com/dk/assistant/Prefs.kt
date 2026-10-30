package com.dk.assistant

import android.content.Context
import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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
                // v3.91: "एक key से सब" का पहला फ़ैसला (एक बार) - नया install = चालू, पुराना user = बंद
                if (!p.contains(ApiScopes.ONE_KEY_ON))
                    p.edit().putBoolean(ApiScopes.ONE_KEY_ON, ApiScopes.oneKeyDefault { k -> if (p.contains(k)) p.all[k]?.toString() else null }).commit()
                scopesDone = true
            }
        }
        return p
    }
    @Volatile private var scopesDone = false

    // ---- हर हिस्से (DK लाइव / DK चैट / कोडिंग एजेंट / DK कंप्यूटर) की अपनी API - देखें [ApiScopes]
    /** इस हिस्से की मुख्य कंपनी (DK लाइव = हमेशा Gemini) */
    fun scopeActive(c: Context, scope: String): String = if (oneKeyOn(c)) "gemini" else scopeActiveRaw(c, scope)
    /**
     * v3.91: "एक key से सब" चालू = हर हिस्सा सिर्फ़ उसी एक Gemini key से (Groq/बाक़ी कंपनियाँ नहीं).
     * अलग-अलग keys की settings मिटती नहीं - बटन बंद करते ही वही पुरानी keys फिर चलती हैं.
     */
    fun oneKeyOn(c: Context): Boolean = sp(c).getBoolean(ApiScopes.ONE_KEY_ON, false)
    fun oneKey(c: Context): String = (sp(c).getString(ApiScopes.ONE_KEY, "") ?: "").trim()
    /** सेटिंग्स स्क्रीन के लिए: अलग-अलग keys असल में जो सेव हैं (एक-key मोड से बदले बिना) */
    fun scopeActiveRaw(c: Context, scope: String): String {
        if (scope == ApiScopes.LIVE) return "gemini"
        if (scope == ApiScopes.CHAT) return activeProviderRaw(c)
        val id = sp(c).getString(ApiScopes.activePref(scope), "") ?: ""
        return if (Providers.byId(id) != null) id else activeProviderRaw(c)
    }
    fun scopeKey(c: Context, scope: String, id: String): String =
        if (oneKeyOn(c)) ApiScopes.oneKeyFor(id, oneKey(c)) else scopeKeyRaw(c, scope, id)
    fun scopeKeyRaw(c: Context, scope: String, id: String) = (sp(c).getString(ApiScopes.pref(scope, "key", id), "") ?: "").trim()
    fun scopeModel(c: Context, scope: String, id: String): String {
        val def = if (scope == ApiScopes.CODER && id == "gemini") GeminiModels.CODER else Providers.byId(id)?.defaultModel ?: ""
        val p = sp(c)
        // v3.70: कोडिंग एजेंट का पुराना default (flash-lite) एक बार 3.8 Flash पर (coding के लिए सबसे तेज़ + अच्छा). बाद में user जो चुने वही
        if (scope == ApiScopes.CODER && id == "gemini" && !p.getBoolean("coder_model_v370", false)) {
            val cur = p.getString(ApiScopes.pref(scope, "model", id), "").orEmpty().trim()
            p.edit().apply { if (GeminiModels.oldCoderDefault(cur)) putString(ApiScopes.pref(scope, "model", id), GeminiModels.CODER); putBoolean("coder_model_v370", true) }.apply()
            if (GeminiModels.oldCoderDefault(cur)) return GeminiModels.CODER
        }
        return (p.getString(ApiScopes.pref(scope, "model", id), def) ?: def).ifBlank { def }
    }
    /**
     * कोडिंग एजेंट की Gemini keys: पहली (मुख्य) + key 2 + key 3 + key 4 + key 5. एक key के सारे मॉडल की limit खत्म -> अगली key.
     * खाली और दोहराई keys नहीं.
     */
    fun coderGeminiKeys(c: Context): List<String> = GeminiModels.distinctKeys(listOf(
        scopeKey(c, ApiScopes.CODER, "gemini"), scopeKey(c, ApiScopes.CODER, "gemini2"), scopeKey(c, ApiScopes.CODER, "gemini3"),
        scopeKey(c, ApiScopes.CODER, "gemini4"), scopeKey(c, ApiScopes.CODER, "gemini5")))
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
    fun activeProvider(c: Context): String = if (oneKeyOn(c)) "gemini" else activeProviderRaw(c)
    fun activeProviderRaw(c: Context): String {
        val id = sp(c).getString("active_provider", "gemini") ?: "gemini"
        return if (Providers.byId(id) != null) id else "gemini"
    }
    /**
     * दूसरी कंपनी की API पर अपने-आप जाना: अब हमेशा बंद - हर हिस्सा सिर्फ़ अपनी चुनी API से जवाब लेता है
     * (limit खत्म हो तो उसी key के दूसरे मॉडल पर). पुरानी "चालू" setting नज़रअंदाज़.
     */
    @Suppress("UNUSED_PARAMETER")
    fun fallback(c: Context) = false
    fun key(c: Context, id: String): String = if (oneKeyOn(c)) ApiScopes.oneKeyFor(id, oneKey(c)) else keyRaw(c, id)
    fun keyRaw(c: Context, id: String) = sp(c).getString("key_$id", "") ?: ""
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
    /** DK लाइव की Gemini keys: मुख्य + बैकअप 2 + बैकअप 3 (एक बंद/limit खत्म = चुपचाप अगली). खाली/दोहराई नहीं */
    fun liveGeminiKeys(c: Context): List<String> = LiveKeys.order(listOf(
        ttsGeminiKey(c), scopeKey(c, ApiScopes.LIVE, "gemini2"), scopeKey(c, ApiScopes.LIVE, "gemini3")))

    /**
     * v3.41: "आप:" लिखने वाले (gemini-3.5-transcribe-live) की अपनी अलग key - बोलने वाले Live की free limit
     * इसमें खर्च न हो. खाली = पुराना तरीका (DK लाइव वाली Gemini key ही).
     */
    const val CAPTION_KEY = "live_key_caption"
    /** v3.90: सिर्फ़ इंटरनेट खोज (Gemini + Google Search) की अपनी key. डली हो = खोज पहले इसी से, और गहरी (ज़्यादा कोण, ज़्यादा सोच) */
    const val SEARCH_KEY = "live_key_search"
    fun searchOwnKey(c: Context): String = if (oneKeyOn(c)) "" else searchOwnKeyRaw(c)
    fun captionOwnKey(c: Context): String = if (oneKeyOn(c)) "" else captionOwnKeyRaw(c)
    fun searchOwnKeyRaw(c: Context): String = sp(c).getString(SEARCH_KEY, "")?.trim() ?: ""
    /**
     * v3.93: इंटरनेट खोज का क्रम Tavily (मुख्य) -> Exa (दूसरा) -> Gemini [SEARCH_KEY] (आख़िरी). खाली = वह सेवा छोड़ो.
     * DK कंप्यूटर वाली "key_tavily" से अलग (वह सिर्फ़ DK कंप्यूटर की). Gemini नहीं हैं, इसलिए "एक key से सब" में भी चलती हैं.
     */
    const val TAVILY_SEARCH_KEY = "search_key_tavily"
    const val EXA_SEARCH_KEY = "search_key_exa"
    fun tavilySearchKey(c: Context): String = sp(c).getString(TAVILY_SEARCH_KEY, "")?.trim() ?: ""
    fun exaSearchKey(c: Context): String = sp(c).getString(EXA_SEARCH_KEY, "")?.trim() ?: ""
    fun captionOwnKeyRaw(c: Context): String = sp(c).getString(CAPTION_KEY, "")?.trim() ?: ""
    fun captionKey(c: Context): String = captionOwnKey(c).ifBlank { ttsGeminiKey(c) }

    // ---- options
    fun wakeMode(c: Context) = sp(c).getBoolean("wake_mode", false)
    fun autoUpdate(c: Context) = sp(c).getBoolean("auto_update", true)
    fun lastUpdateCheck(c: Context) = sp(c).getLong("upd_checked", 0L)
    fun liveMode(c: Context) = sp(c).getBoolean("live_mode", false)
    /** orb ka aakaar: earth, galaxy, atom, torus, geodesic, knot, dyson, face_male, face_female */
    /** Beech ka orb chhota (minimize) kiya hua hai? */
    fun orbMin(c: Context) = sp(c).getBoolean("orb_min", false)
    /** v3.88: हर बातचीत फ़ोन की लोकल याददाश्त (SQLite) में - 30 दिन, फिर सिर्फ़ सार */
    fun localMemoryOn(c: Context) = sp(c).getBoolean("local_memory_on", true)
    // ---- v3.66: सिर्फ़ मालिक की आवाज़ (voiceprint सिर्फ़ इसी फ़ोन में - backup बंद है, GitHub/इंटरनेट पर नहीं)
    /** परत 3: सिर्फ़ मालिक की आवाज़ पर जवाब */
    // v4.5: "सिर्फ़ मेरी आवाज़ सुनो" option हटाया (मालिक की माँग) - पहले चालू किया हो तब भी अब DK सबकी आवाज़ सुनेगा
    @Suppress("UNUSED_PARAMETER") fun ownerVoiceOn(c: Context) = false
    /** सख़्त = दूसरे लगभग कभी पास नहीं, मालिक को कभी-कभी दोबारा बोलना पड़े */
    fun ownerVoiceStrict(c: Context) = sp(c).getBoolean("owner_voice_strict", false)
    /** परत 4: दूर की / बहुत धीमी आवाज़ अनसुनी */
    fun ownerFarOn(c: Context) = sp(c).getBoolean("owner_far_on", true)
    /** v4.7: आपकी पास वाली आवाज़ के सीखे स्तर (NearVoice) */
    fun nearLevels(c: Context) = sp(c).getString("near_levels", "").orEmpty()
    /** v4.7: DK के जवाब के बीच बोलें तो DK रुककर सुने (false, नया) / जवाब चलता रहे और सवाल बाद में (true, पुराना) */
    fun bargeHold(c: Context) = sp(c).getBoolean("barge_hold", false)
    fun ownerPrint(c: Context): FloatArray? = com.dk.assistant.voice.VoicePrint.decode(sp(c).getString("owner_print", null))

    // ---- बायोमेट्रिक चेहरा पहचान (स्थानीय एन्क्रिप्टेड एम्बेडिंग)
    private val FACE_ENC_KEY = byteArrayOf(
        0x37.toByte(), 0x5a.toByte(), 0x7c.toByte(), 0x11.toByte(),
        0x88.toByte(), 0x9b.toByte(), 0xef.toByte(), 0x42.toByte(),
        0x2c.toByte(), 0x3d.toByte(), 0x5e.toByte(), 0x6f.toByte(),
        0x70.toByte(), 0x81.toByte(), 0x92.toByte(), 0xa3.toByte()
    )

    private fun floatsToBytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) buf.putFloat(f)
        return buf.array()
    }

    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) floats[i] = buf.float
        return floats
    }

    private fun encodeBase64(bytes: ByteArray): String = try {
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    } catch (e: Throwable) {
        java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun decodeBase64(str: String): ByteArray = try {
        android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
    } catch (e: Throwable) {
        java.util.Base64.getDecoder().decode(str)
    }

    private fun encryptFaceBytes(bytes: ByteArray): ByteArray {
        val key = SecretKeySpec(FACE_ENC_KEY, "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(bytes)
    }

    private fun decryptFaceBytes(bytes: ByteArray): ByteArray {
        val key = SecretKeySpec(FACE_ENC_KEY, "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(bytes)
    }

    /** v3.91: पहचान किस मॉडल से बनी ("sface1" = असली SFace). पुराने तरीके वाली पहचान नहीं मानी जाती */
    fun ownerFaceModel(c: Context): String = sp(c).getString("owner_face_model", "") ?: ""
    /** DK लाइव के कैमरे पर चेहरा पहचान का निशान (पहले से चालू था - वैसा ही) */
    fun faceHudOn(c: Context) = sp(c).getBoolean("face_hud_on", true)
    fun faceStrict(c: Context) = sp(c).getBoolean("face_strict", false)
    fun hasOwnerFace(c: Context): Boolean = !sp(c).getString("owner_face_print", null).isNullOrBlank()

    fun ownerFacePrint(c: Context): FloatArray? {
        val raw = sp(c).getString("owner_face_print", null) ?: return null
        if (raw.isBlank()) return null
        return try {
            val enc = decodeBase64(raw)
            val dec = decryptFaceBytes(enc)
            bytesToFloats(dec)
        } catch (e: Exception) {
            null
        }
    }

    fun setOwnerFacePrint(c: Context, embedding: FloatArray?) {
        edit(c) {
            if (embedding == null || embedding.isEmpty()) {
                remove("owner_face_print")
            } else {
                val raw = floatsToBytes(embedding)
                val enc = encryptFaceBytes(raw)
                putString("owner_face_print", encodeBase64(enc))
            }
        }
    }

    /** सीखते समय मालिक की आवाज़ का स्तर (RMS) */
    fun ownerLevel(c: Context) = sp(c).getFloat("owner_level", 0f).toDouble()
    /** पहचानी आवाज़ से धीरे-धीरे सीखा स्तर */
    fun ownerLevelNow(c: Context) = sp(c).getFloat("owner_level_now", 0f).toDouble()
    // ---- v3.81: "Hey DK" - फ़ोन के अंदर (इंटरनेट नहीं), सिर्फ़ मालिक की आवाज़ पर जागना
    fun heyDkOn(c: Context) = sp(c).getBoolean("hey_dk_on", false)
    /** जागते ही क्या बोले ("हाँ सर, बोलिए सर, ...") */
    fun heyDkAcks(c: Context) = sp(c).getString("hey_dk_acks", "") ?: ""
    fun heyDkSense(c: Context): com.dk.assistant.voice.WakeMatch.Sense =
        com.dk.assistant.voice.WakeMatch.Sense.values().firstOrNull { it.name == sp(c).getString("hey_dk_sense", "NORMAL") }
            ?: com.dk.assistant.voice.WakeMatch.Sense.NORMAL
    /** "Hey DK" बोलते समय की आवाज़-पहचान (मालिक) */
    /** सीखते समय जो माइक-रास्ता चला (MediaRecorder.AudioSource) */
    fun heyDkSource(c: Context) = sp(c).getInt("hey_dk_src", android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
    fun heyDkPrint(c: Context): FloatArray? = com.dk.assistant.voice.VoicePrint.decode(sp(c).getString("hey_dk_print", null))
    /** v3.65: हटाया गया आकार (dna/tesseract/wormhole/sphere3d) या अनजान -> earth */
    /** v4: जिसने कभी आकार नहीं चुना उसे नया नियॉन ओर्ब; पहले चुना हुआ आकार वैसा ही */
    fun orbStyle(c: Context) = Shapes.valid(sp(c).getString("orb_style", Shapes.NEON))
    /** v3.71: ORB की जगह क्या दिखे - "orb" (डिफ़ॉल्ट) या "girl" (लड़की अवतार) */
    fun avatarKind(c: Context): String = if (sp(c).getString("avatar_kind", "orb") == "girl") "girl" else "orb"
    /** v3.57: DK सुन/बोल रहा हो तो स्क्रीन के किनारों पर चमक */
    fun edgeGlow(c: Context) = sp(c).getBoolean("edge_glow", true)
    /** v3.58: पहली बार का परिचय पूरा/छोड़ा (पुराने उपयोगकर्ता पर अपने-आप true) */
    fun onboarded(c: Context) = sp(c).getBoolean("onboarded", false)
    /** v3.59: यूज़र ने खुद माइक/लाइव बंद किया - जब तक वह खुद चालू न करे, माइक अपने-आप नहीं खुलेगा (wake word भी नहीं) */
    fun micUserOff(c: Context) = sp(c).getBoolean("mic_user_off", false)
    /** v3.74: स्क्रीन देखना - डिफ़ॉल्ट बंद. सिर्फ़ user खुद चालू करे ("स्क्रीन देखो" / सेटिंग) तब */
    fun screenshot(c: Context) = ScreenGuard.canView(sp(c).getBoolean(ScreenGuard.PREF_KEY, false), sp(c).getBoolean(ScreenGuard.USER_OK_KEY, false))
    fun ttsRate(c: Context) = sp(c).getFloat("tts_rate", 1.05f)
    fun ttsPitch(c: Context) = sp(c).getFloat("tts_pitch", 1.0f)

    // ---- awaaz (mahila/purush)
    /** v3.30: DK के जवाब की भाषा - "hinglish" (शुरुआती) / "hi" / "en" (Lang) */
    /** v3.59: कुछ न चुना हो तो हिंदी (पहले Hinglish). जिसने Settings में चुना, उसकी पसंद वैसी ही */
    fun replyLang(c: Context) = Lang.norm(sp(c).getString(Lang.KEY, Lang.HINDI) ?: Lang.HINDI)
    fun loadLang(c: Context) { Lang.current = replyLang(c) }
    fun voiceGender(c: Context) = sp(c).getString("voice_gender", "female") ?: "female"
    /** v3.74: फ़ोन की आवाज़ (google) बंद - हमेशा "gemini" */
    @Suppress("UNUSED_PARAMETER")
    fun voiceEngine(c: Context) = "gemini"
    fun voiceStyle(c: Context) = sp(c).getString("voice_style", "natural") ?: "natural"
    fun googleVoice(c: Context) = sp(c).getString("google_voice", "") ?: ""
    /** v3.59: किस मॉडल पर कौन-सी Live चीज़ (आवाज़/hi-IN/affective) setup तोड़ती है - अगली बार उसके बिना */
    fun liveExtrasOff(c: Context): Set<String> = LiveVoice.parseOff(sp(c).getString(LiveVoice.OFF_KEY, ""))
    fun addLiveExtrasOff(c: Context, entry: String) = edit(c) { putString(LiveVoice.OFF_KEY, LiveVoice.joinOff(liveExtrasOff(c) + entry)) }
    /** v4.3: लोकेशन की इजाज़त एक बार अपने-आप माँग ली (फिर हर बार ऐप खुलने पर नहीं) */
    fun locAsked(c: Context) = sp(c).getBoolean("loc_asked_v43", false)
    fun geminiVoice(c: Context): String {
        // v3.59: कुछ न चुना हो तो हमेशा Sulafat (LiveVoice) - पुरुष आवाज़ चुनी हो तो पुरुष सूची की पहली
        // v4.3 (मालिक): एक बार सबकी आवाज़ Sulafat पर (Live + Gemini TTS एक जैसी). बाद में Settings से बदली तो वही रहेगी
        if (!sp(c).getBoolean("voice_sulafat_v43", false)) edit(c) { putString("gemini_voice", SpeakingStyle.VOICE); putBoolean("voice_sulafat_v43", true) }
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
    /** v4.1: सिर्फ़ "DK ..." से शुरू होने वाली बात सुनो (शुरू में बंद = पहले जैसा) */
    fun dkOnly(c: Context) = sp(c).getBoolean("dk_only_listen", false)
    fun setDkOnly(c: Context, on: Boolean) = sp(c).edit().putBoolean("dk_only_listen", on).apply()
    /** v4.1: कॉल काटकर भेजा जाने वाला तैयार मैसेज (Settings में बदल सकते हैं) */
    const val CALL_BUSY_DEFAULT = "अभी व्यस्त हूँ, थोड़ी देर में कॉल करता हूँ।"
    fun callBusyMsg(c: Context) = (sp(c).getString("call_busy_msg", CALL_BUSY_DEFAULT) ?: "").ifBlank { CALL_BUSY_DEFAULT }
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
    /** v3.6: हल्के एजेंटों (प्रॉम्प्ट इंजीनियर, मैनेजर...) का Gemini मॉडल */
    fun coderLightModel(c: Context): String = sp(c).getString("coder_light_model", null)?.trim()?.removePrefix("models/")?.ifBlank { null } ?: GeminiModels.LIGHT
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
    /** कोडिंग एजेंट की अभी खुली बातचीत (इतिहास का धागा) */
    fun coderThread(c: Context) = sp(c).getString("coder_thread", "").orEmpty()
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
