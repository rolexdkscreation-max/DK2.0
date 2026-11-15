package com.dk.assistant.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 15 नवंबर 2026 (हिस्सा 1): Gemini REST function calling का pure JSON हिस्सा - request बनाना / जवाब पढ़ना.
 * Android नहीं -> JVM test ([ToolRegistryTest]).
 */
object ToolWire {

    data class Call(val name: String, val args: JSONObject)

    data class Turn(val calls: List<Call>, val text: String, val content: JSONObject?)

    val SYSTEM = """
You are DK, a Hindi voice assistant that controls the user's Android phone ONLY through the provided tools.
Rules:
- Plan briefly, then call ONE tool at a time and wait for its result. At most ${ToolPolicy.MAX_STEPS} tool calls per task.
- Every tool result has ok=true/false and result text. Never claim something happened unless a tool result says ok=true.
- If a tool fails, DK has already retried it; choose another way only if one clearly exists, otherwise call done and say honestly what failed.
- Use read_screen when you need to know what is on the screen before tapping.
- Use ask_user only when something essential is missing (which contact, what message). Keep questions short, in Hindi.
- Never buy, pay, delete data, or enter passwords / OTP.
- When the task is complete, call done(summary) with one short, natural Hindi sentence (Devanagari).
""".trimIndent()

    fun request(history: JSONArray): JSONObject = JSONObject()
        .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
        .put("contents", history)
        .put("tools", ToolRegistry.declarations())
        .put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))
        .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 2048))

    fun userTurn(goal: String, app: String): JSONObject = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject()
        .put("text", "USER TASK (Hindi/Hinglish): $goal\nFOREGROUND APP: ${app.ifBlank { "unknown" }}")))

    /** model का content वैसे ही वापस history में (Gemini 3 के thoughtSignature समेत) */
    fun parse(resp: JSONObject): Turn {
        val content = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content") ?: return Turn(emptyList(), "", null)
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val calls = ArrayList<Call>()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought")) continue
            p.optJSONObject("functionCall")?.let { fc -> calls += Call(fc.optString("name"), fc.optJSONObject("args") ?: JSONObject()) }
            p.optString("text").takeIf { it.isNotBlank() }?.let { sb.append(it) }
        }
        if (!content.has("role")) content.put("role", "model")
        return Turn(calls, sb.toString().trim(), content)
    }

    /** हर tool के नतीजे का एक functionResponse part - एक ही user turn में */
    fun responses(results: List<Pair<String, JSONObject>>): JSONObject {
        val parts = JSONArray()
        for ((n, r) in results) parts.put(JSONObject().put("functionResponse", JSONObject().put("name", n).put("response", r)))
        return JSONObject().put("role", "user").put("parts", parts)
    }

    fun result(ok: Boolean, text: String, tries: Int, extra: Map<String, String> = emptyMap()): JSONObject =
        JSONObject().put("ok", ok).put("result", text.take(4000)).put("tries", tries).apply { extra.forEach { (k, v) -> put(k, v) } }
}
