package com.dk.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DK ka "dimaag". Gemini API (free tier) ko screen ki jaankari + aapki command bhejta hai,
 * aur JSON me actions wapas leta hai (kya click karna hai, kya type karna hai, kya bolna hai).
 */
class GeminiBrain(private val apiKey: String, val model: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Baatcheet ki memory (sirf text, screenshot nahi - taaki free quota bache). */
    private val history = mutableListOf<JSONObject>()

    fun reset() = history.clear()

    suspend fun next(prompt: String, imageBase64: String?, memoryNote: String): JSONObject =
        withContext(Dispatchers.IO) {
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (imageBase64 != null) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", imageBase64)
                    )
                )
            }
            val contents = JSONArray()
            history.forEach { contents.put(it) }
            contents.put(JSONObject().put("role", "user").put("parts", parts))

            val body = JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val raw = client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    if (resp.code == 429) throw IOException("QUOTA: free limit khatam, thodi der baad try karo")
                    throw IOException("HTTP ${resp.code}: ${txt.take(300)}")
                }
                txt
            }

            val answer = extractText(JSONObject(raw))
            val json = parseJson(answer)

            history.add(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", memoryNote))))
            history.add(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", answer))))
            while (history.size > 24) history.removeAt(0)  // purani baatein hata do
            if (history.isNotEmpty() && history[0].optString("role") == "model") history.removeAt(0)
            json
        }

    private fun extractText(resp: JSONObject): String {
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IOException("AI ne khali jawab diya: ${resp.toString().take(200)}")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    private fun parseJson(text: String): JSONObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return JSONObject().put("say", text).put("done", true)
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: Exception) {
            JSONObject().put("say", text).put("done", true)
        }
    }

    companion object {
        val SYSTEM_PROMPT = """
You are DK, a friendly voice assistant that fully controls the user's Android phone.
The user speaks Hindi/Hinglish. Every turn you receive: the user's goal, results of previous actions,
the current foreground app, a numbered list of on-screen elements, and sometimes a screenshot.

Reply with ONLY one JSON object:
{"say": "<short natural sentence to speak, Hindi in Devanagari, common English words allowed; may be empty>",
 "actions": [ ... ],
 "done": true or false}

Available actions:
{"type":"open_app","name":"YouTube"}            open an installed app by its name (English name)
{"type":"youtube_search","query":"..."}          open YouTube search results directly
{"type":"web_search","query":"..."}              Google search
{"type":"open_url","url":"https://..."}
{"type":"dial","number":"..."}                    opens dialer with number (user presses call)
{"type":"click","id":N}                           tap element N from the list
{"type":"long_click","id":N}                      long press element N (e.g. select a photo)
{"type":"type","id":N,"text":"..."}               put text into editable element N (omit id = focused field)
{"type":"enter"}                                  press keyboard search/enter on focused field
{"type":"scroll","direction":"down|up|left|right"}
{"type":"tap","x":X,"y":Y}                        tap screen pixel (use only if no element fits)
{"type":"swipe","x1":X,"y1":Y,"x2":X,"y2":Y}
{"type":"back"} {"type":"home"} {"type":"recents"} {"type":"notifications"} {"type":"quick_settings"}
{"type":"wait","ms":1500}
{"type":"ask","question":"..."}                   ask the user by voice; their answer arrives next turn

Rules:
1. Work step by step. After actions that change the screen, set "done": false so you can see the new screen.
   Usually give 1 action per turn (max 3 if the screen will not change between them).
2. Only use element ids from the CURRENT screen list. Never invent ids.
3. Before irreversible actions (delete, post/publish, send message, payment, uninstall, call) you MUST
   use "ask" to confirm, unless the user already clearly confirmed. If the user says no, stop.
4. If the request is just conversation or a question, answer in "say", no actions, "done": true.
5. To play something on YouTube: youtube_search, then click the first video result (not ads/shorts if possible).
6. Facebook post: open_app Facebook, tap the "What's on your mind?" box, type the text, confirm with ask, then tap Post.
7. Gallery/photos: open_app Gallery (or Photos), long_click photos to select, tap Delete, confirm with ask first.
8. If something fails, try another way (scroll, back, search inside app). If stuck, say so and set done true.
9. Keep "say" very short (1 sentence) while working. When done, briefly tell the result.
""".trimIndent()
    }
}
