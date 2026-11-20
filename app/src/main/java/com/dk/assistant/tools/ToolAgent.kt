package com.dk.assistant.tools

import android.content.Context
import com.dk.assistant.ApiScopes
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.GeminiModels
import com.dk.assistant.NetFix
import com.dk.assistant.Prefs
import com.dk.assistant.plus.FeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 15 नवंबर 2026 (मालिक, हिस्सा 1): "Gemini function calling से tool चुने, max 15 steps; हर step के बाद Retry Loop".
 *
 * सिर्फ़ तब चलता है जब Settings → AI दिमाग → "टूल एजेंट (Retry Loop)" चालू हो ([enabled], शुरू में बंद).
 * सीधे commands (ऐप खोलो, कॉल, टॉर्च …) पहले की तरह फ़ोन पर ही (LocalRouter) - यहाँ सिर्फ़ लंबे / उलझे फ़ोन-काम आते हैं.
 * यह न चल पाए (key नहीं / नेटवर्क / Gemini की गलती) = false लौटता है -> पुराना AgentRunner वाला रास्ता वैसा ही चलता है.
 */
class ToolAgent(
    private val svc: DKAccessibilityService,
    private val say: suspend (String) -> Unit,
    private val ask: suspend (String) -> String?,
) {
    companion object {
        const val FLAG = FeatureFlags.TOOL_AGENT

        fun enabled(c: Context): Boolean = FeatureFlags.on(c, FLAG)

        fun keys(c: Context): List<String> = GeminiModels.distinctKeys(
            listOf(Prefs.scopeKey(c, ApiScopes.CHAT, "gemini"), Prefs.key(c, "gemini")) + Prefs.liveGeminiKeys(c))

        fun models(c: Context): List<String> = com.dk.assistant.dkai.SelectedModel.geminiOrder(c, "tools वाले काम",   // 20 नवंबर 2026: चिप का Gemini पहले
            listOf(Prefs.scopeModel(c, ApiScopes.CHAT, "gemini"), GeminiModels.CODER, GeminiModels.LIGHT))
            .map { it.trim().removePrefix("models/") }.filter { it.startsWith("gemini") && !GeminiModels.isLegacy(it) }.distinct()

        private val JSON_T = "application/json; charset=utf-8".toMediaType()
        private val http by lazy { NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build() }
    }

    private class AiFail(msg: String) : Exception(msg)

    /** true = काम संभाल लिया (हो गया / साफ़ बता दिया कहाँ अटकी); false = पुराना रास्ता चलाओ */
    suspend fun run(goal: String): Boolean {
        val keys = keys(svc)
        if (keys.isEmpty()) return false
        val history = JSONArray().put(ToolWire.userTurn(goal, svc.currentPackage()))
        var steps = 0
        var first = true
        while (true) {
            coroutineContext.ensureActive()
            val resp = try { call(keys, history) } catch (e: AiFail) {
                DKLog.d("टूल एजेंट: Gemini नहीं चला (${e.message}) - " + if (first) "पुराना रास्ता" else "रुकी")
                if (first) return false
                say("AI से जवाब नहीं आया, इसलिए काम बीच में रुक गया।"); return true
            }
            first = false
            val turn = ToolWire.parse(resp)
            if (turn.content == null) { say("AI ने कोई जवाब नहीं दिया।"); return true }
            history.put(turn.content)
            if (turn.calls.isEmpty()) {
                say(turn.text.ifBlank { "हो गया।" }); return true
            }
            val results = ArrayList<Pair<String, JSONObject>>()
            for (c in turn.calls) {
                when (c.name) {
                    ToolRegistry.DONE -> { say(c.args.optString("summary").ifBlank { "हो गया।" }); return true }
                    ToolRegistry.ASK -> {
                        val ans = ask(c.args.optString("question").ifBlank { "थोड़ा और बताइए?" })
                        if (ans.isNullOrBlank()) { say("जवाब नहीं मिला, इसलिए काम रोक दिया।"); return true }
                        results += c.name to ToolWire.result(true, "USER SAID: $ans", 1)
                        continue
                    }
                }
                steps++
                if (steps > ToolPolicy.MAX_STEPS) { say(ToolPolicy.limitSpeech()); return true }
                // 16 नवंबर 2026 (ऑडिट G3): बिना कहे भेजना / भुगतान / डिलीट = मालिक की अपनी "हाँ" (AI का हाँ नहीं).
                // 18 नवंबर 2026 (मालिक: कार्ड पॉप-अप नहीं): आवाज़ में एक बार पूछो, ऐप मालिक का जवाब खुद जाँचे
                cardKind(c.name, c.args).takeIf { com.dk.assistant.agent.core.OutsideText.needsCard(it, goal) }?.let { k ->
                    val ans = ask("${k.hi} वाला कदम: ${argLine(c.args).take(120)}। क्या मैं करूँ? हाँ या ना बोलिए।")
                    if (!com.dk.assistant.agent.core.ConfirmGate.isYes(ans.orEmpty())) { say("ठीक है, यह कदम नहीं किया — काम यहीं रोक दिया।"); return true }
                }
                val out = ToolExec.run(svc, c.name, c.args, ask)
                DKLog.d("टूल एजेंट कदम $steps: ${c.name} ${c.args} -> ${if (out.ok) "✓" else "✗"} (${out.tries} बार, ${out.by})")
                if (out.question != null) {
                    // executor ने खुद सवाल पूछा (कई कॉन्टैक्ट / क्या लिखूँ) - user से पूछकर AI को बताओ
                    val ans = ask(out.question)
                    results += c.name to ToolWire.result(false, "NEEDS USER: ${out.question}", out.tries, mapOf("user_answer" to ans.orEmpty()))
                    continue
                }
                if (!out.ok) {
                    // मालिक: "max 3 बार, वरना साफ़ voice में बताए कहाँ अटका" - यहीं रुककर बताओ
                    say(ToolPolicy.stuckSpeech(c.name, argLine(c.args), out.text, out.tries))
                    return true
                }
                results += c.name to ToolWire.result(true, ToolPolicy.plain(out.text), out.tries)
            }
            if (results.isNotEmpty()) history.put(ToolWire.responses(results))
        }
    }

    private fun argLine(a: JSONObject): String = listOf("name", "text", "query", "app").firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } }.orEmpty()

    /** हर मॉडल × हर key - कोई भी गलती = अगला; सब नाकाम = AiFail */
    private suspend fun call(keys: List<String>, history: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val body = ToolWire.request(history).toString()
        var lastErr = "कोई key नहीं"
        for (m in models(svc)) for (k in keys) {
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                .header("x-goog-api-key", k).post(body.toRequestBody(JSON_T)).build()
            try {
                http.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (r.isSuccessful) return@withContext JSONObject(txt)
                    lastErr = "$m HTTP ${r.code}"   // 400/403 (गलत key) भी: अगली key / मॉडल
                    if (k == keys.last()) com.dk.assistant.dkai.SelectedModel.failedIf(svc, m, com.dk.assistant.AiException(r.code, txt.take(300)))
                }
            } catch (e: Exception) { lastErr = "$m ${e.javaClass.simpleName}" }
        }
        throw AiFail(lastErr)
    }

    /** 16 नवंबर 2026: इस tool से कौन-सा वापस न होने वाला काम */
    private fun cardKind(name: String, args: JSONObject): com.dk.assistant.agent.core.OutsideText.Kind = when (name) {
        ToolRegistry.WHATSAPP, ToolRegistry.SMS, ToolRegistry.LOCATION -> com.dk.assistant.agent.core.OutsideText.Kind.SEND
        ToolRegistry.TAP_TEXT -> com.dk.assistant.agent.core.OutsideText.kind(JSONObject().put("type", "click_text").put("text", args.optString("text")), "", "")
        else -> com.dk.assistant.agent.core.OutsideText.Kind.NONE
    }
}
