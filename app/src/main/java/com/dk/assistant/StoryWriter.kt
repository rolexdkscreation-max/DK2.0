package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Story Writer Agent (v4.0): lambi Hindi kahaniyaan (10-30 min) - sirf text, koi awaaz nahi.
 * Kahani chhote bhaagon (~450 shabd) me banti hai: har bhaag = ek alag request, pichhle saare bhaag context me
 * (naam/baatein na badlein). Iska system prompt alag hai - baaki agents (DK, Coding) ka prompt nahi chhoota.
 */
object StoryPlan {
    const val WORDS_PER_MIN = 140
    const val WORDS_PER_PART = 450
    val LENGTHS = listOf(10, 15, 20, 25, 30)

    fun words(minutes: Int) = minutes * WORDS_PER_MIN
    fun parts(minutes: Int) = ceil(words(minutes).toDouble() / WORDS_PER_PART).toInt().coerceAtLeast(2)
    /** 10 min: 1 twist, 20 min: 2, 30 min: 3 (system prompt ke hisaab se) */
    fun twists(minutes: Int) = when { minutes <= 14 -> 1; minutes <= 24 -> 2; else -> 3 }

    /** Is bhaag ki request (user turn). part = 1..total */
    fun request(topic: String, minutes: Int, part: Int, total: Int): String {
        val per = WORDS_PER_PART
        val stage = when {
            part == total -> "This is the LAST part: bring the climax and a satisfying, heartfelt ending. The story must be fully complete at the end of this part."
            part == total - 1 -> "Build strongly towards the climax, but do NOT end the story yet."
            else -> "Keep the story moving with steady suspense. Do NOT end the story yet."
        }
        val common = "Write about $per Hindi words. $stage Every sentence must be complete; end this part at the end of a complete sentence (never with \"...\"). " +
            "Do NOT write any part heading (like \"भाग $part\"), notes, word counts or explanations - only the story text."
        return if (part == 1) {
            val t = topic.trim().ifBlank { "(कोई विषय नहीं दिया - खुद एक सच्चा, दिल को छूने वाला, रोज़ की ज़िंदगी से जुड़ा विषय चुनो)" }
            "कहानी का विषय / निर्देश: $t\n" +
                "कहानी की लंबाई: $minutes मिनट (लगभग ${words(minutes)} शब्द), कुल $total भाग, हर भाग लगभग $per शब्द। " +
                "Twists: ${twists(minutes)}.\n\n" +
                "Now write ONLY part 1 of $total. First line: the story title as \"शीर्षक: <title>\". Then start the story with a strong hook. $common"
        } else {
            "Now write ONLY part $part of $total. Continue exactly from where part ${part - 1} ended - same names, same facts, nothing new without setup. " +
                "Start directly with a dialogue line. Do not repeat the title. $common"
        }
    }

    private val HEADING = Regex("^\\s*[#*\\s]*(भाग|part|पार्ट)\\s*[0-9०-९]+.*$", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("^\\s*[#*\\s]*(शीर्षक|title|कहानी का नाम)\\s*[:：-]\\s*", RegexOption.IGNORE_CASE)
    private val END = charArrayOf('।', '!', '?', '|', '॥')

    /** AI ke text se bhaag-heading, markdown aur khaali lines saaf */
    fun clean(raw: String): String = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        .replace("**", "").replace("__", "")
        .lines().map { it.trimEnd().replace(Regex("^#+\\s*"), "") }
        .filterNot { HEADING.matches(it) && it.length < 40 }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    /** Pehle bhaag se shirshak alag: (title, baaki kahani) */
    fun splitTitle(text: String): Pair<String, String> {
        val lines = text.lines()
        val i = lines.indexOfFirst { it.isNotBlank() }
        if (i < 0) return "" to text
        val first = lines[i]
        if (!TITLE.containsMatchIn(first)) return "" to text
        val title = first.replace(TITLE, "").trim().trim('"', '“', '”', '*')
        return title to lines.drop(i + 1).joinToString("\n").trim()
    }

    /** Aadha kata vakya hatao: aakhri poore vakya tak (। ! ? aur uske baad ka band quote) */
    fun trimToSentence(t: String): String {
        val s = t.trimEnd().removeSuffix("...").removeSuffix("…").trimEnd()
        if (s.isEmpty()) return s
        val last = s.last()
        if (last in END || ((last == '"' || last == '”' || last == ')') && s.length > 1 && s[s.length - 2] in END)) return s
        val idx = s.lastIndexOfAny(END)
        if (idx < s.length / 2) return s           // aadhe se zyada katega - jaisa hai waisa rakho
        var end = idx + 1
        while (end < s.length && (s[end] == '"' || s[end] == '”' || s[end] == ')')) end++
        return s.substring(0, end)
    }

    fun wordCount(t: String) = t.split(Regex("\\s+")).count { it.isNotBlank() }
}

/** Kahani likhne wali AI: "AI दिमाग" me chuni company/key/model. Apna HTTP - Coding/DK ka koi client nahi badla */
class StoryAi(val provider: Provider, private val key: String, val model: String, private val base: String) {
    val label get() = "${provider.name} ($model)"

    data class Out(val text: String, val cut: Boolean)

    fun write(system: String, msgs: List<Pair<String, String>>): Out =
        if (provider.isGemini) gemini(system, msgs) else openAi(system, msgs, 6000)

    private fun gemini(system: String, msgs: List<Pair<String, String>>): Out {
        var last: Exception? = null
        for (m in (listOf(model) + GeminiClient.BACKUP_MODELS).distinct()) {
            try { return geminiOnce(m, system, msgs) } catch (e: AiException) {
                last = e
                if (e.code !in listOf(404, 429, 500, 503)) throw e
                DKLog.d("Story: $m -> ${e.code}, अगला मॉडल")
            }
        }
        throw last ?: AiException(0, "Gemini से जवाब नहीं मिला")
    }

    private fun geminiOnce(m: String, system: String, msgs: List<Pair<String, String>>): Out {
        val contents = JSONArray()
        msgs.forEach { (r, t) ->
            contents.put(JSONObject().put("role", if (r == "user") "user" else "model").put("parts", JSONArray().put(JSONObject().put("text", t))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 8192).put("temperature", 0.9))
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .header("x-goog-api-key", key).post(body.toString().toRequestBody(JSON)).build()
        val c = JSONObject(exec(req)).optJSONArray("candidates")?.optJSONObject(0) ?: throw AiException(0, "Gemini ने खाली जवाब दिया")
        val p = c.optJSONObject("content")?.optJSONArray("parts") ?: throw AiException(0, "Gemini ने खाली जवाब दिया (${c.optString("finishReason")})")
        val sb = StringBuilder()
        for (i in 0 until p.length()) { val x = p.optJSONObject(i) ?: continue; if (!x.optBoolean("thought")) sb.append(x.optString("text")) }
        return Out(sb.toString(), c.optString("finishReason") == "MAX_TOKENS")
    }

    private fun openAi(system: String, msgs: List<Pair<String, String>>, maxTok: Int): Out {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        msgs.forEach { (r, t) -> arr.put(JSONObject().put("role", if (r == "user") "user" else "assistant").put("content", t)) }
        val body = JSONObject().put("model", model).put("messages", arr).put("max_tokens", maxTok).put("temperature", 0.9)
        val req = Request.Builder().url(base.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant").header("X-Title", "DK Story Writer")
            .post(body.toString().toRequestBody(JSON)).build()
        val raw = try { exec(req) } catch (e: AiException) {
            if (e.code == 400 && maxTok > 2000 && (e.message ?: "").contains("token", true)) return openAi(system, msgs, maxTok / 2)
            throw e
        }
        val ch = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0) ?: throw AiException(0, "${provider.name} ने खाली जवाब दिया")
        return Out(ch.optJSONObject("message")?.optString("content").orEmpty(), ch.optString("finish_reason") == "length")
    }

    private fun exec(req: Request): String = http.newCall(req).execute().use { r ->
        val s = r.body?.string() ?: ""
        if (!r.isSuccessful) {
            val m = try { JSONObject(s).optJSONObject("error")?.optString("message") ?: s.take(200) } catch (_: Exception) { s.take(200) }
            throw AiException(r.code, m)
        }
        s
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

        /** "AI दिमाग" wali company. null = key/model nahi */
        fun fromPrefs(c: Context): StoryAi? {
            val p = Providers.byId(Prefs.activeProvider(c)) ?: return null
            val key = Prefs.key(c, p.id); val model = Prefs.model(c, p.id); val base = Prefs.baseUrl(c, p.id)
            if (key.isBlank() || model.isBlank() || (!p.isGemini && base.isBlank())) return null
            return StoryAi(p, key, model, base)
        }
    }
}

/** Kahani ka session - screen band/ghumane par bhi bhaag aur chal raha kaam bane rahein */
object StorySession {
    var topic = ""
    var minutes = 10
    var total = 0
    var title = ""
    val parts = mutableListOf<String>()
    /** Kis bhaag par galti hui (retry ke liye) - null = koi galti nahi */
    var error: String? = null
    var stopped = false
    var job: Job? = null
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile var listener: (() -> Unit)? = null
    val busy get() = job?.isActive == true
    val done get() = total > 0 && parts.size >= total

    private fun notifyUi() = listener?.invoke()

    /** Nayi kahani: pichhli saaf, bhaag 1 se */
    fun start(c: Context, topic: String, minutes: Int) {
        if (busy) return
        this.topic = topic.trim(); this.minutes = minutes
        total = StoryPlan.parts(minutes); title = ""; parts.clear(); error = null; stopped = false
        resume(c)
    }

    fun stop() { stopped = true; job?.cancel() }

    /** Agla bhaag (ya galti wala bhaag dobara) se poora hone tak */
    fun resume(c: Context) {
        if (busy || total == 0 || done) return
        val app = c.applicationContext
        error = null; stopped = false
        job = scope.launch {
            try {
                val ai = StoryAi.fromPrefs(app) ?: run { error = "कहानी के लिए AI key नहीं मिली — सेटिंग्स → AI दिमाग में key डालें"; return@launch }
                while (parts.size < total) {
                    val n = parts.size + 1
                    notifyUi()
                    val text = withContext(Dispatchers.IO) { writePart(ai, n) }
                    if (text.isBlank()) throw AiException(0, "AI ने खाली भाग दिया")
                    parts.add(text)
                    DKLog.d("Story: भाग $n/$total (${StoryPlan.wordCount(text)} शब्द)")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (stopped) error = "रोक दिया — भाग ${parts.size + 1} से आगे लिख सकते हैं"
            } catch (e: Exception) {
                error = "भाग ${parts.size + 1} नहीं लिख पाया: " + (e.message ?: e.javaClass.simpleName).take(200)
                DKLog.d("Story गलती: ${e.javaClass.simpleName} ${e.message}")
            } finally { notifyUi() }
        }
        notifyUi()
    }

    /** Ek bhaag: pichhle saare bhaag context me (user request / model jawab ki jodi) */
    private fun writePart(ai: StoryAi, n: Int): String {
        val msgs = mutableListOf<Pair<String, String>>()
        parts.forEachIndexed { i, p ->
            msgs.add("user" to StoryPlan.request(topic, minutes, i + 1, total))
            msgs.add("model" to (if (i == 0 && title.isNotBlank()) "शीर्षक: $title\n\n$p" else p))
        }
        msgs.add("user" to StoryPlan.request(topic, minutes, n, total))
        val out = ai.write(StoryPrompt.SYSTEM, msgs)
        var t = StoryPlan.clean(out.text)
        if (n == 1) { val (tt, rest) = StoryPlan.splitTitle(t); if (tt.isNotBlank()) { title = tt; t = rest } }
        return StoryPlan.trimToSentence(t)
    }

    fun fullText(): String = buildString {
        if (title.isNotBlank()) append(title).append("\n\n")
        parts.forEachIndexed { i, p -> if (i > 0) append("\n\n"); append(p) }
    }
}

/** Story Writer Agent ka apna, pakka system prompt (har request me). DK/Coding ke prompt se alag. */
object StoryPrompt {
    val SYSTEM = """
You are "Story Writer Agent", an expert Hindi storyteller. You write long, emotional, film-like stories (moral stories and any other genre) for a YouTube channel. The audience is aged 13 and above. When the user gives a topic or instruction, write the full story directly without asking unnecessary questions. If no topic is given, pick a genuine, relatable one yourself.
1. Length
You can write 10 to 30 minute stories in one go. Use about 140 spoken Hindi words per minute (10 min ≈ 1,400 words, 20 min ≈ 2,800, 30 min ≈ 4,200).
Deliver long stories in many short parts (about 400–500 words each), never a few huge blocks. Every new part must start directly with dialogue.
2. Language
Very simple village-style spoken Hindi that even an unlettered person understands instantly. No Sanskritized, literary, or English words.
Everyday desi expressions ("अरे भैया", "हां हां", "सुन तो ज़रा") and rural vocabulary (खेत, चूल्हा, मंडी, टपरी, गुड़) where they fit naturally.
Give each character a distinct way of speaking.
Narration must be in plain simple Hindi only.
3. Dialogue vs Narration
80% dialogue, 20% narration. Dialogue drives the story.
Dialogue lines must be short and natural, the way real people talk. Split a long thought across two turns. Every line must be a grammatically complete sentence, with no trailing "...".
Add a short emotion/tone cue in brackets to every dialogue line, e.g. (रोते हुए), (गुस्से में), (धीमी आवाज़ में).
Narration appears only for scene changes, time jumps, or necessary description, in small amounts and spread thinly. Never one big block.
4. Story Structure
Hook: the first 2–3 lines must grab the listener (a shocking line, a mystery, or an emotional moment).
Real conflict: a genuine problem that village or city families actually face, so the viewer feels "yeh toh hamare saath bhi hua hai". Use a different problem each time.
Drama and emotion: build moments that make listeners tear up.
Suspense and twists: decide yourself how many to include by story length (e.g. 10 min: 1 twist; 20 min: 2; 30 min: 3, with steady suspense). The listener must not be able to predict what happens next.
Climax: the strongest emotional and dramatic peak, placed near the end.
Ending: a satisfying, heartfelt close (happy, sad, or bittersweet as the story needs). For moral stories, the lesson should come out of the events, never as a lecture.
No story jumps: show every important scene. Nothing should appear suddenly without earlier setup.
5. Proverbs and Idioms
Use real, well-known kahavatein and muhavare (e.g. "जैसी करनी वैसी भरनी", "देर है अंधेर नहीं") only where they fit naturally, often spoken by elders. Never force or invent fake ones.
6. Content Rules
Suitable for ages 13+. No vulgarity, politics, or hate. No heavy dialect; keep a very light village flavour only through address words (काका, दादा, अरे भैया).
Every story must be original and never repeat earlier ones.
7. Final Check (before delivering)
No broken or half words, no inconsistencies in names or facts, no sudden unexplained elements, dialogue/narration ratio close to 80/20, and the story feels human-written, not artificial.
8. Output Format
Story title first, then the story in parts. Use character name labels before dialogue lines (e.g. रामू (रोते हुए): ...). Do not add explanations or notes unless the user asks.
""".trim()
}
