package com.dk.assistant.auto

import android.content.Context
import com.dk.assistant.AiClient
import com.dk.assistant.AiException
import com.dk.assistant.ApiScopes
import com.dk.assistant.CoderAi
import com.dk.assistant.GeminiClient
import com.dk.assistant.OpenAiCompatClient
import com.dk.assistant.Prefs
import com.dk.assistant.Providers

/**
 * ऑटोनोमस मोड का संचालक (orchestrator).
 *  - [plan]: बात ऑटोनोमस का काम है? (mode बंद = हमेशा null = DK पहले जैसा)
 *  - कोडिंग -> मौजूदा कोडिंग एजेंट (उसी GitHub जुड़ाव से), फ़ोन के काम -> AgentRunner का स्क्रीन वाला रास्ता (सुरक्षा के साथ)
 *  - keys: ऑटोनोमस की अपनी key (खाली = DK की मुख्य key); कोडिंग की अपनी key (खाली = कोडिंग एजेंट की key, वो भी नहीं = मुख्य key)
 *  - [emergencyStop]: अभी का हर ऑटोनोमस काम तुरंत बंद
 */
object AutoMode {
    /** ऑटोनोमस के कहने पर कोडिंग शुरू हुई: इतने समय तक कोडिंग AI = ऑटोनोमस वाली key (3 घंटे; बंद/रोकने पर तुरंत खत्म) */
    @Volatile var codingUntil = 0L
    private const val CODING_WINDOW_MS = 3 * 60 * 60 * 1000L

    fun plan(c: Context, goal: String): AutoPlan.Plan? {
        if (!AutoPrefs.on(c)) return null
        return AutoPlan.classify(goal, AutoPrefs.enabledKinds(c), AutoPrefs.customTasks(c))
    }

    /** key से कंपनी पहचानो (AIza = Gemini, gsk_ = Groq, sk-or- = OpenRouter); न पहचाने तो DK की चुनी कंपनी */
    private fun providerFor(c: Context, key: String) =
        Providers.byId(ApiScopes.detect(key) ?: Prefs.activeProvider(c)) ?: Providers.byId("gemini")

    /** ऑटोनोमस की अपनी key का AI. null = key खाली -> DK की मुख्य key (पहले जैसा) */
    fun brainClient(c: Context): Pair<String, AiClient>? {
        val key = AutoPrefs.secret(c, AutoPrefs.KEY_AUTO)
        val id = providerFor(c, key)?.id ?: return null
        return clientForKey(c, key)?.let { id to it }
    }

    private fun clientForKey(c: Context, key: String): AiClient? {
        if (key.isBlank()) return null
        val p = providerFor(c, key) ?: return null
        val model = Prefs.model(c, p.id).ifBlank { p.defaultModel }
        return if (p.isGemini) GeminiClient(key, model)
        else Prefs.baseUrl(c, p.id).takeIf { it.isNotBlank() }?.let { OpenAiCompatClient(p.name, it, key, model) }
    }

    /**
     * कोडिंग AI ऑटोनोमस की तरफ़ से (सिर्फ़ जब ऑटोनोमस ने कोडिंग शुरू की हो):
     * ऑटोनोमस-कोडिंग key -> (खाली) null = कोडिंग एजेंट की अपनी key. [mainFallback] = वो भी न हो तो DK की मुख्य key.
     */
    fun coderOverride(c: Context): CoderAi? {
        if (!codingActive(c)) return null
        return coderFor(c, AutoPrefs.secret(c, AutoPrefs.KEY_CODER))
    }

    fun coderMainFallback(c: Context): CoderAi? {
        if (!codingActive(c)) return null
        val active = Prefs.activeProvider(c)
        return coderFor(c, Prefs.key(c, active))
    }

    private fun coderFor(c: Context, key: String): CoderAi? {
        if (key.isBlank()) return null
        val p = providerFor(c, key) ?: return null
        val model = Prefs.scopeModel(c, ApiScopes.CODER, p.id).ifBlank { p.defaultModel }
        val base = Prefs.baseUrl(c, p.id)
        if (!p.isGemini && base.isBlank()) return null
        return CoderAi(p, key, model, base, note = ", ऑटोनोमस मोड की key")
    }

    fun codingActive(c: Context) = AutoPrefs.on(c) && System.currentTimeMillis() < codingUntil

    /** कोडिंग एजेंट को काम सौंपो. लौटता है: बोलने लायक बात */
    fun startCoding(c: Context, task: String): String {
        codingUntil = System.currentTimeMillis() + CODING_WINDOW_MS
        AutoLog.add(c, "कोडिंग का काम कोडिंग एजेंट को दिया: $task")
        val p = com.dk.assistant.CoderSession.problems(c)
        if (p.isNotEmpty()) {
            codingUntil = 0L
            val m = "कोडिंग का काम शुरू नहीं हो पाया: " + p.joinToString(", ") + "।"
            AutoLog.add(c, "रुका: $m")
            return m
        }
        val err = com.dk.assistant.CoderTeam.start(c, task, fromVoice = true)
        if (err != null) { codingUntil = 0L; AutoLog.add(c, "रुका: $err"); return err }
        AutoLog.add(c, "कोडिंग एजेंट ने काम ले लिया (हर कदम कोडिंग स्क्रीन पर)")
        return "ठीक है, यह कोडिंग का काम है — कोडिंग एजेंट को सौंप दिया। वह GitHub रिपॉज़िटरी से कोड पढ़कर बदलाव करेगा, " +
            "गलती आए तो खुद ठीक करने की कोशिश करेगा, और पूरा होने पर बताएगा कि क्या बदला। हर कदम कोडिंग स्क्रीन पर दिखेगा।"
    }

    /** इमरजेंसी स्टॉप: फ़ोन वाला काम + कोडिंग एजेंट का काम तुरंत बंद */
    fun emergencyStop(c: Context) {
        codingUntil = 0L
        try { com.dk.assistant.DKAccessibilityService.instance?.agent?.stop() } catch (_: Exception) { }
        try { com.dk.assistant.CoderSession.job?.cancel() } catch (_: Exception) { }
        AutoLog.add(c, "इमरजेंसी स्टॉप दबाया — सारा ऑटोनोमस काम रोक दिया")
    }

    /** "कुंजी जाँचो": छोटा-सा सवाल भेजकर साफ़ नतीजा (key कभी लॉग में नहीं) */
    fun checkKey(c: Context, key: String): String {
        if (key.isBlank()) return "Key खाली है — खाली छोड़ने पर DK की मुख्य key इस्तेमाल होगी।"
        val cl = clientForKey(c, key) ?: return "यह key किस कंपनी की है, पहचान नहीं पाई (Gemini key AIza…, Groq gsk_…, OpenRouter sk-or-… से शुरू होती है)।"
        return try {
            cl.complete("Reply with OK only.", emptyList(), "ping", null)
            "✓ Key सही है और चल रही है (${cl.label})।"
        } catch (e: AiException) {
            when (e.code) {
                400, 401, 403 -> "✗ Key गलत है या इस मॉडल की इजाज़त नहीं (code ${e.code})। key दोबारा कॉपी करके डालें।"
                404 -> "✗ मॉडल नहीं मिला (code 404) — Settings → AI दिमाग में मॉडल बदलें।"
                429 -> "✗ इस key की limit अभी खत्म है (code 429) — थोड़ी देर बाद जाँचें या दूसरी key डालें।"
                else -> "✗ AI से जवाब नहीं आया (code ${e.code})।"
            }
        } catch (e: java.io.IOException) {
            "✗ इंटरनेट से जुड़ नहीं पाई (${e.javaClass.simpleName}) — नेट देखकर फिर जाँचें। key गलत है, ऐसा पक्का नहीं।"
        } catch (e: Exception) {
            "✗ जाँच नहीं हो पाई (${e.javaClass.simpleName})।"
        }
    }
}
