package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * DK ka dimaag. Aapki chuni hui AI company ko pehle try karta hai,
 * fail ho (limit khatam / internet / galat key) to baaki keys wali companies try karta hai.
 */
class BrainManager(private val ctx: Context) {

    private val history = mutableListOf<Turn>()
    var lastUsed: String = ""
        private set

    fun reset() = history.clear()

    private fun clientFor(p: Provider): AiClient? {
        val key = Prefs.key(ctx, p.id)
        if (key.isBlank()) return null
        val model = Prefs.model(ctx, p.id)
        if (model.isBlank()) return null
        return if (p.isGemini) GeminiClient(key, model)
        else {
            val base = Prefs.baseUrl(ctx, p.id)
            if (base.isBlank()) null else OpenAiCompatClient(p.name, base, key, model)
        }
    }

    /** Pehle chuna hua provider, phir (fallback ON ho to) baaki. */
    private fun orderedProviders(): List<Provider> {
        val active = Providers.byId(Prefs.activeProvider(ctx))
        val rest = Providers.all.filter { it.id != active?.id }
        return if (Prefs.fallback(ctx)) listOfNotNull(active) + rest else listOfNotNull(active)
    }

    fun hasUsableProvider() = orderedProviders().any { clientFor(it) != null }

    /** Galti ko aasaan Hindi me samjhao (bolne + screen par dikhane ke liye). */
    fun explain(e: Exception): String {
        val msg = e.message ?: ""
        val code = (e as? AiException)?.code ?: 0
        return when {
            e is java.net.UnknownHostException -> "इंटरनेट नहीं मिल रहा। नेट चालू करके फिर बोलिए।"
            e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException ->
                "AI बहुत देर लगा रहा है। इंटरनेट धीमा है या सर्वर व्यस्त है, फिर से बोलिए।"
            code == 429 -> "फ्री लिमिट खत्म हो गई है। थोड़ी देर बाद कोशिश कीजिए, या सेटिंग्स में दूसरी AI की key डालिए।"
            code == 401 || code == 403 || msg.contains("API key", true) ->
                "API key गलत है या उसे इजाज़त नहीं है। सेटिंग्स में key दोबारा पेस्ट करके टेस्ट कीजिए।"
            code == 404 -> "यह मॉडल नहीं मिला। सेटिंग्स में मॉडल का नाम ठीक कीजिए।"
            msg == "NO_KEY" -> "पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।"
            else -> "AI से जवाब नहीं मिला (गलती $code)।"
        }
    }

    suspend fun next(prompt: String, imageB64: String?, memoryNote: String): JSONObject =
        withContext(Dispatchers.IO) {
            val system = buildSystemPrompt()
            var lastError: Exception? = null
            for (p in orderedProviders()) {
                val client = clientFor(p) ?: continue
                val img = if (p.vision) imageB64 else null
                try {
                    val answer = try {
                        client.complete(system, history, prompt, img)
                    } catch (e: AiException) {
                        // kuch model photo nahi lete - bina photo dobara try
                        if (img != null && e.code == 400) client.complete(system, history, prompt, null) else throw e
                    }
                    lastUsed = client.label
                    DKLog.d("AI: ${client.label}")
                    remember(memoryNote, answer)
                    return@withContext parseJson(answer)
                } catch (e: Exception) {
                    DKLog.d("${client.label} fail: ${e.message}")
                    lastError = e
                }
            }
            throw lastError ?: AiException(-1, "NO_KEY")
        }

    /** Settings me "टेस्ट" button ke liye. */
    suspend fun test(p: Provider): String = withContext(Dispatchers.IO) {
        val client = clientFor(p) ?: return@withContext "पहले API key और model डालिए"
        try {
            val r = client.complete("Reply in one short Hindi sentence.", emptyList(), "नमस्ते, क्या तुम काम कर रहे हो?", null)
            "✓ चल रहा है: ${r.take(80)}"
        } catch (e: AiException) {
            "✗ Error ${e.code}: ${e.message?.take(150)}"
        } catch (e: Exception) {
            "✗ ${e.message?.take(150)}"
        }
    }

    private fun remember(note: String, answer: String) {
        history.add(Turn("user", note.take(1500)))
        history.add(Turn("model", answer.take(1500)))
        while (history.size > 24) history.removeAt(0)
        if (history.firstOrNull()?.role == "model") history.removeAt(0)
    }

    private fun parseJson(text: String): JSONObject {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return JSONObject().put("say", text.trim()).put("done", true)
        return try { JSONObject(text.substring(s, e + 1)) }
        catch (ex: Exception) { JSONObject().put("say", text.trim()).put("done", true) }
    }

    private fun buildSystemPrompt(): String {
        val name = Prefs.assistantName(ctx)
        val user = Prefs.userName(ctx)
        val persona = Prefs.personality(ctx)
        val intro = buildString {
            append("You are $name, a friendly voice assistant that fully controls the user's Android phone.\n")
            if (user.isNotBlank()) append("The user's name is $user. Address them by name occasionally, warmly.\n")
            if (persona.isNotBlank()) append("Personality / extra instructions from the user: $persona\n")
        }
        return intro + BASE_PROMPT
    }

    companion object {
        val BASE_PROMPT = """
The user speaks Hindi/Hinglish. Every turn you receive: the user's goal, results of previous actions,
the current foreground app, a numbered list of on-screen elements, and sometimes a screenshot.

Reply with ONLY one JSON object (no markdown, no extra text):
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
{"type":"look"}                                   you need a SCREENSHOT to understand the screen; it arrives next turn

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
10. CHAT MODE: if the input says "CHAT MODE" you get no screen info. Answer normal talk/questions directly
    (done true). If the user actually wants something done on the phone, reply exactly {"need_screen": true}.
11. Only use "look" when the element list is not enough (images, games, unclear screen).
""".trimIndent()
    }
}
