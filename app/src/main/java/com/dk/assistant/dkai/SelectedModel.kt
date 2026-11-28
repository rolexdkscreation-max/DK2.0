package com.dk.assistant.dkai

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dk.assistant.AiException
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.Prefs
import com.dk.assistant.Provider
import com.dk.assistant.Providers
import com.dk.assistant.StrongAi
import com.dk.assistant.UiLang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** DataStore (एक ही instance पूरे ऐप में) - चिप में चुना मॉडल यहीं सेव होता है */
private val Context.selectedModelStore: DataStore<Preferences> by preferencesDataStore(name = "dk_selected_model")

/**
 * 20 नवंबर 2026 (मालिक: "चिप में चुना मॉडल हर दिमाग के काम में चले"): पूरे ऐप का एक ही केंद्रीय [selectedModel].
 *
 *  - चिप (इनपुट बार का मॉडल बटन) में चुनते ही DataStore में सेव + याद (memory) में - हर जगह से तुरंत पढ़ा जा सके.
 *  - null / "Auto" = कुछ नहीं चुना: पुरानी routing बिल्कुल पहले जैसी (Settings → AI दिमाग के pins, fallback क्रम सब).
 *  - चुना हो = टेक्स्ट चैट, वॉइस, कोडिंग एजेंट, DK कंप्यूटर, कहानी, वेबसाइट, Pipeline... सबमें यही मॉडल पहले.
 *    यह न चले (limit / गलती) = [failed] छोटा संदेश ("कौन-सा मॉडल फेल हुआ") और फिर पहले वाला fallback. चुपचाप नहीं.
 *  - [Choice.supportsVoice] = यह मॉडल खुद आवाज़ में सुनता-बोलता है (Gemini Live / native-audio). तब वॉइस में वही;
 *    नहीं = मॉडल सिर्फ़ टेक्स्ट बनाता है और मौजूदा TTS उसे बोलता है ([textVoice]).
 *
 * पुराने कोड के लिए वही पुराने Prefs (active_provider, model_<कंपनी>, dkai_model_picked) भी भरे जाते हैं - कुछ नहीं टूटता.
 */
object SelectedModel {
    const val AUTO = "auto"

    /** चिप का चुना मॉडल. [supportsVoice] = capability flag (आवाज़ का रास्ता इसी से तय) */
    data class Choice(val provider: String, val model: String, val supportsVoice: Boolean) {
        val label: String get() = ModelNamesSafe.label(model)
    }

    // Prefs में आईना (sync पढ़ने के लिए - ऐप खुलते ही, DataStore पढ़ने से पहले)
    internal const val MIRROR_PROVIDER = "sel_model_provider"
    internal const val MIRROR_MODEL = "sel_model_name"
    internal const val MIRROR_VOICE = "sel_model_voice"

    private val K_PROVIDER = stringPreferencesKey("provider")
    private val K_MODEL = stringPreferencesKey("model")
    private val K_VOICE = booleanPreferencesKey("supports_voice")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<Choice?>(null)
    /** UI / सेवा देख सके कि चुना मॉडल बदला */
    val flow: StateFlow<Choice?> = state.asStateFlow()
    @Volatile private var loaded = false

    // ------------------------------------------------------------------ pure (JUnit)
    /** खाली / "auto" = null (Auto) */
    fun decode(provider: String?, model: String?, voice: Boolean): Choice? {
        val p = provider.orEmpty().trim(); val m = model.orEmpty().trim().removePrefix("models/")
        if (p.isBlank() || m.isBlank() || p == AUTO || m == AUTO) return null
        return Choice(p, m, voice || ModelCaps.supportsVoice(p, m))
    }

    /** यह मॉडल ([model]) चुना हुआ ही है? (models/ और बड़े-छोटे अक्षर का फ़र्क नहीं) */
    fun same(a: String?, b: String?): Boolean =
        a.orEmpty().trim().removePrefix("models/").equals(b.orEmpty().trim().removePrefix("models/"), ignoreCase = true)

    /** Gemini के मॉडल-क्रम में चुना Gemini (टेक्स्ट) मॉडल सबसे पहले, बाकी वैसे ही - दोहराव नहीं */
    fun firstOf(chosen: String?, list: List<String>): List<String> {
        val m = chosen.orEmpty().trim().removePrefix("models/")
        if (m.isBlank()) return list
        return listOf(m) + list.filterNot { same(it, m) }
    }

    /** गलती -> छोटा कारण (यूज़र को) */
    fun reason(e: Throwable?): String {
        val code = (e as? AiException)?.code ?: 0
        val msg = e?.message.orEmpty().lowercase()
        return when {
            code == 429 && (msg.contains("limit: 0") || msg.contains("free_tier") || msg.contains("free tier")) -> "यह मॉडल आपकी फ्री key पर नहीं चलता (paid key चाहिए)"
            code == 402 || msg.contains("insufficient") || msg.contains("credit") -> "key में पैसे/credit नहीं (paid मॉडल)"
            code == 429 -> "limit खत्म"
            code == 401 -> "key गलत"
            code == 403 -> "इस key को इस मॉडल की इजाज़त नहीं (paid हो सकता है)"
            code == 404 -> "मॉडल अब उपलब्ध नहीं"
            code == 400 -> "मॉडल ने सवाल नहीं लिया"
            code >= 500 -> "कंपनी का सर्वर अभी नहीं चल रहा"
            e is java.io.IOException -> "नेट/समय की दिक्कत"
            else -> "गलती"
        }
    }

    /** फेल होने का छोटा संदेश: "चुना मॉडल X नहीं चला (कारण) — अब दूसरे मॉडल से" */
    fun failText(model: String, why: String, next: String?): String =
        "चुना मॉडल ${ModelNamesSafe.label(model)} नहीं चला ($why) — " + (next?.takeIf { it.isNotBlank() }?.let { "अब $it से" } ?: "अब पहले वाले fallback से")

    // ------------------------------------------------------------------ फ़ोन पर
    /** अभी चुना मॉडल (null = Auto / कुछ नहीं चुना). कहीं से भी, किसी भी thread से */
    fun get(c: Context): Choice? {
        if (!loaded) load(c)
        return state.value
    }

    /** Auto नहीं? */
    fun on(c: Context): Boolean = get(c) != null

    @Synchronized private fun load(c: Context) {
        if (loaded) return
        val app = c.applicationContext
        val saved = Prefs.str(app, MIRROR_PROVIDER, "")
        state.value = if (saved.isBlank() && DkaiPick.on(app)) {
            // पुराने वर्ज़न में होम से चुना मॉडल (dkai_model_picked) - वही चुना रहे
            val id = Prefs.activeProvider(app)
            decode(id, Prefs.model(app, id), false)?.also { mirror(app, it) }
        } else decode(saved, Prefs.str(app, MIRROR_MODEL, ""), Prefs.bool(app, MIRROR_VOICE, false))
        loaded = true
        // असली जगह DataStore: पीछे से पढ़कर याद/आईना ठीक करो (आईना कभी पीछे छूटा हो तो)
        scope.launch {
            try {
                val p = app.selectedModelStore.data.first()
                val fromStore = decode(p[K_PROVIDER], p[K_MODEL], p[K_VOICE] ?: false)
                val stored = p[K_PROVIDER] != null
                if (stored && fromStore != state.value) { state.value = fromStore; mirror(app, fromStore) }
                else if (!stored && state.value != null) write(app, state.value)   // पुराना आईना -> DataStore में भी
            } catch (e: Exception) { DKLog.d("चुना मॉडल (DataStore) पढ़ा नहीं गया: ${e.message?.take(60)}") }
        }
    }

    /**
     * चिप में चुना ([choice] = null = Auto). याद में तुरंत, DataStore + पुराने Prefs में पीछे से.
     * Auto = पुराना "dkai_model_picked" निशान भी हटता है - तब चैट की routing बिल्कुल पहले जैसी.
     */
    fun set(c: Context, choice: Choice?) {
        val app = c.applicationContext
        loaded = true
        state.value = choice
        mirror(app, choice)
        Prefs.edit(app) {
            if (choice == null) putBoolean(DkaiPick.PREF, false)
            else {
                // पुराने रास्ते (चैट की कंपनी + उसका मॉडल) भी - जो कोड अभी Prefs.model() पढ़ता है वह भी यही चलाए
                if (Providers.byId(choice.provider) != null) { putString("active_provider", choice.provider); putString("model_${choice.provider}", choice.model) }
                putBoolean(DkaiPick.PREF, true)
            }
        }
        com.dk.assistant.voice.engine.VoiceEngines.liveChanged()
        DKLog.d("चुना मॉडल: " + (choice?.let { "${it.provider} ${it.model} (आवाज़: ${if (it.supportsVoice) "खुद" else "TTS"})" } ?: "Auto (पहले जैसी routing)"))
        scope.launch { write(app, choice) }
    }

    private fun mirror(app: Context, choice: Choice?) = Prefs.edit(app) {
        putString(MIRROR_PROVIDER, choice?.provider ?: AUTO); putString(MIRROR_MODEL, choice?.model ?: AUTO)
        putBoolean(MIRROR_VOICE, choice?.supportsVoice == true)
    }

    private suspend fun write(app: Context, choice: Choice?) {
        try {
            app.selectedModelStore.edit { p ->
                p[K_PROVIDER] = choice?.provider ?: AUTO; p[K_MODEL] = choice?.model ?: AUTO; p[K_VOICE] = choice?.supportsVoice == true
            }
        } catch (e: Exception) { DKLog.d("चुना मॉडल (DataStore) सेव नहीं हुआ: ${e.message?.take(60)}") }
    }

    /** चुना Gemini टेक्स्ट मॉडल (Gemini वाले कामों में सबसे पहले). Gemini नहीं / आवाज़ वाला / Auto = null */
    fun geminiText(c: Context): String? = get(c)?.takeIf { it.provider == "gemini" && !it.supportsVoice }?.model

    /**
     * सिर्फ़ Gemini वाले काम ([work] - स्क्रीन देखना, फ़ोटो, वेबसाइट, tools...) का मॉडल-क्रम: चुना Gemini (टेक्स्ट) मॉडल सबसे पहले.
     * चुना मॉडल दूसरी कंपनी का = क्रम वैसा ही, पर एक बार बताओ कि यह काम Gemini से हुआ. Auto = [list] वैसी ही.
     */
    fun geminiOrder(c: Context?, work: String, list: List<String>): List<String> {
        c ?: return list
        val ch = get(c) ?: return list
        if (ch.provider != "gemini") { geminiOnly(c, work); return list }
        return firstOf(geminiText(c), list)
    }

    /** चुना मॉडल खुद आवाज़ वाला (Gemini Live / native-audio) = वही मॉडल Live में. नहीं = null */
    fun voiceModel(c: Context): String? = get(c)?.takeIf { it.provider == "gemini" && it.supportsVoice }?.model

    /** चुना मॉडल सिर्फ़ टेक्स्ट वाला = वॉइस में वही जवाब बनाए और मौजूदा TTS बोले (STT -> मॉडल -> TTS) */
    fun textVoice(c: Context): Boolean = get(c)?.let { !it.supportsVoice } == true && resolve(c) != null

    /** चैट/काम के लिए चुना मॉडल किस key + पते से चलेगा. null = Auto, या उस कंपनी की key ही नहीं */
    class Resolved(val provider: Provider, val key: String, val model: String, val base: String, val choice: Choice)

    fun resolve(c: Context): Resolved? {
        val ch = get(c) ?: return null
        if (ch.supportsVoice) {
            // आवाज़ वाला मॉडल टेक्स्ट के कामों में नहीं चलता - वहाँ उसी key का Gemini (पहले जैसा) चलेगा
            return null
        }
        val p = Providers.byId(ch.provider)
        if (p != null) {
            val key = keyFor(c, p.id)
            if (key.isBlank()) return null
            val base = if (p.isGemini) "" else Prefs.baseUrl(c, p.id).ifBlank { p.baseUrl }
            if (!p.isGemini && base.isBlank()) return null
            return Resolved(p, key, ch.model, base, ch)
        }
        // 28 नवंबर 2026: चिप में One AI का मॉडल (AI दिमाग का "सिर्फ़ One AI मॉडल" बटन)
        if (ch.provider == com.dk.assistant.OneAi.ID) {
            val k = com.dk.assistant.OneAi.keys(c, com.dk.assistant.ApiScopes.CHAT).firstOrNull() ?: return null
            return Resolved(com.dk.assistant.OneAi.provider, k, ch.model, com.dk.assistant.OneAi.BASE, ch)
        }
        val st = strong(c)?.takeIf { it.provider.id == ch.provider } ?: return null
        return Resolved(st.provider, st.key, ch.model, st.base, ch)
    }

    /** इस कंपनी की ऐप में सेव कोई भी key (चैट की, फिर बाकी हिस्सों की उधार, Gemini = DK लाइव की भी) */
    fun keyFor(c: Context, id: String): String {
        val own = Prefs.key(c, id)
        if (own.isNotBlank()) return own
        if (id == "gemini") return Prefs.liveGeminiKeys(c).firstOrNull().orEmpty()
        return strong(c)?.takeIf { it.provider.id == id }?.key.orEmpty()
    }

    /** कोडिंग एजेंट → मज़बूत मॉडल वाली key (Claude / OpenAI / DeepSeek ...) */
    fun strong(c: Context): StrongAi.Setup? =
        StrongAi.setup(Prefs.coderStrongProvider(c), Prefs.coderStrongKey(c), Prefs.coderStrongModel(c))

    // ------------------------------------------------------------------ गलती का संदेश (नियम 4)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val told = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val TELL_GAP_MS = 45_000L

    /**
     * चुना मॉडल फेल: छोटा संदेश (toast + DK की पट्टी + log), फिर बुलाने वाला पुराना fallback चलाता है.
     * एक ही मॉडल का वही संदेश 45 सेकंड में दोबारा नहीं (हर शब्द पर बाढ़ न आए).
     */
    fun failed(c: Context, model: String, e: Throwable?, next: String? = null) {
        DKLog.d("चुना मॉडल फेल [${e?.javaClass?.simpleName}: ${e?.message?.take(120)}]")
        failedWhy(c, model, reason(e), next)
    }

    /** [failed] जैसा, पर कारण पहले से लिखा हुआ ([why]) */
    fun failedWhy(c: Context, model: String, why: String, next: String?) {
        val text = failText(model, why.take(80), next)
        DKLog.d(text)
        val now = System.currentTimeMillis()
        val k = model.lowercase() + "|" + why.take(40)
        val last = told[k] ?: 0L
        if (now - last < TELL_GAP_MS) return
        told[k] = now
        try { DKState.hint(text) } catch (x: Exception) { DKLog.d("hint: ${x.message}") }
        val app = c.applicationContext
        main.post { try { UiLang.toast(app, text, android.widget.Toast.LENGTH_LONG).show() } catch (x: Exception) { DKLog.d("toast: ${x.message}") } }
    }

    /** [model] चुना हुआ ही है तो [failed] (Gemini के मॉडल-loop में हर फेल पर बुलाया जा सकता है) */
    fun failedIf(c: Context, model: String, e: Throwable?) {
        val ch = get(c) ?: return
        if (same(ch.model, model)) failed(c, model, e)
    }

    /** काम में चुने मॉडल की जगह दूसरा चला (Live / वेबसाइट): बताओ */
    fun usedInstead(c: Context, chosen: String, used: String) {
        if (same(chosen, used) || used.isBlank()) return
        failedWhy(c, chosen, "इस key पर नहीं मिला / नहीं चला", ModelNamesSafe.label(used))
    }

    /**
     * कुछ काम सिर्फ़ Gemini से होते हैं (स्क्रीन देखना, Live, वेबसाइट) - चुना मॉडल दूसरी कंपनी का हो तो
     * एक बार बताओ कि यह काम Gemini से हुआ (चुपचाप नहीं).
     */
    private val toldGeminiOnly = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun geminiOnly(c: Context, work: String) {
        val ch = get(c) ?: return
        if (ch.provider == "gemini" || !toldGeminiOnly.add(work)) return
        val text = "$work सिर्फ़ Gemini से होता है — चुना ${ModelNamesSafe.label(ch.model)} यहाँ नहीं चलता, Gemini से किया"
        DKLog.d(text)
        try { DKState.hint(text) } catch (x: Exception) { DKLog.d("hint: ${x.message}") }
    }
}

/** मॉडल के नाम का साफ़ रूप (ModelNames न चले तो id ही) */
internal object ModelNamesSafe {
    fun label(id: String): String = try { com.dk.assistant.ModelNames.fromId(id).ifBlank { id } } catch (_: Exception) { id }
}

/**
 * हर मॉडल की क्षमता. [supportsVoice] = मॉडल खुद आवाज़ सुनता-बोलता है (Gemini Live: bidiGenerateContent / native-audio / live).
 * बाकी सब = सिर्फ़ टेक्स्ट (आवाज़ मौजूदा TTS से).
 */
object ModelCaps {
    private val VOICE = Regex("native-audio|-live|live-|realtime", RegexOption.IGNORE_CASE)

    fun supportsVoice(provider: String, model: String): Boolean =
        provider == "gemini" && VOICE.containsMatchIn(model.removePrefix("models/")) && !model.contains("transcribe", ignoreCase = true)
}
