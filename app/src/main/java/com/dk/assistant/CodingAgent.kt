package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Coding ke liye alag AI (lambe jawab, lamba timeout). Settings me alag key/model ho to woh,
 * warna wahi AI jo baatcheet me chal rahi hai.
 */
class CoderAi(val provider: Provider, private val key: String, val model: String, private val base: String) {
    val label get() = "${provider.name} ($model)"

    fun chat(system: String, msgs: List<Pair<String, String>>): String =
        if (provider.isGemini) gemini(system, msgs) else openAi(system, msgs, 16000)

    private fun gemini(system: String, msgs: List<Pair<String, String>>): String {
        val order = (listOf(model) + GeminiClient.BACKUP_MODELS).distinct()
        var last: Exception? = null
        for (m in order) {
            try { return geminiOnce(m, system, msgs) } catch (e: AiException) {
                last = e
                if (e.code !in listOf(404, 429, 500, 503)) throw e
                DKLog.d("Coder: $m -> ${e.code}, अगला मॉडल")
            }
        }
        throw last ?: AiException(0, "Gemini fail")
    }

    private fun geminiOnce(m: String, system: String, msgs: List<Pair<String, String>>): String {
        val contents = JSONArray()
        msgs.forEach { (r, t) ->
            contents.put(JSONObject().put("role", if (r == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 32000).put("temperature", 0.2))
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .header("x-goog-api-key", key).post(body.toString().toRequestBody(JSON)).build()
        val o = JSONObject(exec(req))
        val p = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException(0, "Gemini ने खाली जवाब दिया")
        val sb = StringBuilder()
        for (i in 0 until p.length()) { val x = p.optJSONObject(i) ?: continue; if (!x.optBoolean("thought")) sb.append(x.optString("text")) }
        return sb.toString()
    }

    private fun openAi(system: String, msgs: List<Pair<String, String>>, maxTok: Int): String {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        msgs.forEach { (r, t) -> arr.put(JSONObject().put("role", if (r == "user") "user" else "assistant").put("content", t)) }
        val body = JSONObject().put("model", model).put("messages", arr).put("max_tokens", maxTok).put("temperature", 0.2)
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "medium")
        val req = Request.Builder().url(base.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant").header("X-Title", "DK Coding Agent")
            .post(body.toString().toRequestBody(JSON)).build()
        val raw = try { exec(req) } catch (e: AiException) {
            // kuch free models itne lambe jawab nahi dete -> chhota maango
            if (e.code == 400 && maxTok > 4000 && (e.message ?: "").contains("token", true)) return openAi(system, msgs, maxTok / 2)
            throw e
        }
        val msg = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiException(0, "${provider.name} ने खाली जवाब दिया")
        return msg.optString("content").replace(Regex("(?s)<think>.*?</think>"), "").trim()
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

        /** Settings se coding wali AI. null = key nahi */
        fun fromPrefs(c: Context): CoderAi? {
            val pid = Prefs.coderProvider(c)
            val same = pid == "same" || Providers.byId(pid) == null
            val p = Providers.byId(if (same) Prefs.activeProvider(c) else pid) ?: return null
            val key = (if (same) "" else Prefs.coderKey(c)).ifBlank { Prefs.key(c, p.id) }
            val model = (if (same) "" else Prefs.coderModel(c)).ifBlank { Prefs.model(c, p.id) }
            val base = (if (same) "" else Prefs.coderBase(c)).ifBlank { Prefs.baseUrl(c, p.id) }
            if (key.isBlank() || model.isBlank() || (!p.isGemini && base.isBlank())) return null
            return CoderAi(p, key, model, base)
        }
    }
}

/**
 * Coding agent: GitHub repo padhta hai, files likhta/badalta/mitata hai (pehle \"staged\"),
 * phir ek commit me push. Har kadam UI ko batata hai.
 */
class CodingAgent(private val gh: GitHub, var repo: String, var branch: String, private val ai: CoderAi) {

    data class Outcome(val summary: String, val commit: String, val question: Boolean)

    /** path -> naya content (null = mitana) - abhi push nahi hua */
    val staged = LinkedHashMap<String, String?>()
    private val original = HashMap<String, String?>()
    private val msgs = mutableListOf<Pair<String, String>>()
    private var files: List<GitHub.Entry>? = null

    fun reset() { staged.clear(); original.clear(); msgs.clear(); files = null }

    private fun system() = """
You are DK Coder, an expert software engineer working DIRECTLY on the GitHub repository "$repo" (branch "$branch") through tools.
The user speaks Hindi/Hinglish. Think carefully, read the relevant files before changing them, keep the existing style, and make complete, compiling changes.

Reply with EXACTLY ONE tool call per message: a JSON object on the first line, optionally followed by content blocks. No other text.
Tools:
{"tool":"list","path":"optional/folder"}            -> list files (all files if path empty)
{"tool":"read","path":"file/path.ext"}              -> read a file (shows staged version if you changed it)
{"tool":"search","query":"words"}                   -> search code in the repo
{"tool":"write","path":"file/path.ext"}             -> create or fully replace a file; put the COMPLETE new content after it:
<<<FILE
...entire file content...
FILE>>>
{"tool":"edit","path":"file/path.ext"}              -> replace one exact unique snippet (preferred for small changes in big files):
<<<FIND
exact existing text (copy it exactly, enough lines to be unique)
FIND>>>
<<<REPLACE
new text
REPLACE>>>
{"tool":"delete","path":"file/path.ext"}            -> delete a file
{"tool":"ask","question":"Hindi question"}          -> ONLY if the request is truly unclear; waits for the user
{"tool":"done","summary":"Hindi summary of what you changed and why (short bullet lines)","commit":"short English commit message"}

Rules:
- Changes are only STAGED; the app pushes them as one commit after "done". Never claim it is pushed.
- Never put secrets/API keys in code. Do not delete files unless the user asked or it is clearly required.
- If the user only asks a question about the code, read what you need and answer in "done" summary with commit "".
- Keep each write/edit focused. For big files prefer several "edit" calls over one huge "write".
""".trimIndent()

    /** Ek kaam chalao (ya pichhle "ask" ka jawab). onStep = UI ke liye chhoti line */
    suspend fun run(task: String, onStep: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        if (msgs.isEmpty()) {
            onStep("$repo ($branch) की फ़ाइलें देख रहा हूँ…")
            files = gh.tree(repo, branch)
            val list = files!!.take(500).joinToString("\n") { it.path }
            msgs.add("user" to "Repository files (${files!!.size}):\n$list" + (if (files!!.size > 500) "\n…(more, use list)" else ""))
            msgs.add("model" to "{\"tool\":\"list\",\"path\":\"\"}")
            msgs.add("user" to "OK. Waiting for the task.")
            msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"क्या करना है?\"}")
        }
        msgs.add("user" to "TASK FROM USER: $task" +
            if (staged.isNotEmpty()) "\n(Already staged, not pushed: ${staged.keys.joinToString()})" else "")
        var step = 0
        while (true) {
            ensureActive()
            if (++step > 40) return@withContext Outcome("40 कदम हो गए, यहीं रोका। अब तक के बदलाव नीचे हैं।", "DK: partial changes", false)
            trimContext()
            val reply = try { ai.chat(system(), msgs) } catch (e: AiException) {
                throw AiException(e.code, "${ai.label}: ${e.message}")
            }
            msgs.add("model" to reply)
            val call = parse(reply)
            if (call == null) {
                onStep("…")
                msgs.add("user" to "ERROR: your reply had no valid JSON tool call on the first line. Reply with exactly one tool call.")
                continue
            }
            val (o, blocks) = call
            val tool = o.optString("tool"); val path = o.optString("path").trim().trimStart('/')
            if (tool == "ask") return@withContext Outcome(o.optString("question").ifBlank { "थोड़ा और बताइए?" }, "", true)
            if (tool == "done") return@withContext Outcome(o.optString("summary").ifBlank { "हो गया।" },
                o.optString("commit").ifBlank { if (staged.isEmpty()) "" else "DK: code changes" }, false)
            val result = try { runTool(tool, path, o, blocks, onStep) } catch (e: GitHub.Err) { "GITHUB ERROR: ${e.message}" }
            msgs.add("user" to "RESULT ($tool${if (path.isNotBlank()) " $path" else ""}):\n$result")
        }
        Outcome("", "", false)
    }

    private fun runTool(tool: String, path: String, o: JSONObject, blocks: Map<String, String>, onStep: (String) -> Unit): String {
        when (tool) {
            "list" -> {
                onStep("फ़ाइलें देखीं: ${path.ifBlank { "/" }}")
                val all = files ?: gh.tree(repo, branch).also { files = it }
                val gone = staged.filterValues { it == null }.keys
                val names = (all.map { it.path } + staged.filterValues { it != null }.keys).distinct()
                    .filter { it !in gone }
                    .filter { path.isBlank() || it.startsWith(path.trimEnd('/') + "/") || it == path }
                return names.take(800).joinToString("\n").ifBlank { "(no files)" }
            }
            "read" -> {
                onStep("पढ़ रहा हूँ: $path")
                val t = current(path) ?: return "NOT FOUND: $path"
                return if (t.length > 60_000) t.take(60_000) + "\n…(file cut at 60000 chars; use edit with exact snippets)" else t
            }
            "search" -> {
                val q = o.optString("query")
                onStep("खोज: $q")
                val r = try { gh.search(repo, q) } catch (e: Exception) { emptyList() }
                // GitHub search kabhi-kabhi khaali deta hai -> file naam me bhi dekho
                val byName = (files ?: emptyList()).map { it.path }.filter { it.contains(q, true) }
                return (r + byName).distinct().take(40).joinToString("\n").ifBlank { "No results. Try list/read." }
            }
            "write" -> {
                if (path.isBlank()) return "ERROR: path missing"
                val c = blocks["FILE"] ?: return "ERROR: missing <<<FILE ... FILE>>> block"
                remember(path)
                staged[path] = c
                onStep("लिखा: $path (${c.lines().size} लाइन)")
                return "OK staged $path (${c.length} chars)"
            }
            "edit" -> {
                val f = blocks["FIND"]; val r = blocks["REPLACE"]
                if (f == null || r == null) return "ERROR: need <<<FIND ... FIND>>> and <<<REPLACE ... REPLACE>>> blocks"
                val cur = current(path) ?: return "NOT FOUND: $path"
                val n = cur.split(f).size - 1
                return when {
                    f.isEmpty() -> "ERROR: FIND is empty"
                    n == 0 -> "ERROR: FIND text not found exactly in $path. Read the file again and copy the exact text."
                    n > 1 -> "ERROR: FIND text occurs $n times in $path. Include more surrounding lines."
                    else -> { remember(path); staged[path] = cur.replace(f, r); onStep("बदला: $path"); "OK edited $path" }
                }
            }
            "delete" -> {
                if (current(path) == null) return "NOT FOUND: $path"
                remember(path); staged[path] = null
                onStep("हटाया: $path"); return "OK staged delete $path"
            }
            else -> return "ERROR: unknown tool '$tool'"
        }
    }

    /** Staged ho to wahi, warna GitHub se */
    private fun current(path: String): String? =
        if (staged.containsKey(path)) staged[path] else original.getOrPut(path) { gh.read(repo, path, branch) }

    private fun remember(path: String) { if (!original.containsKey(path)) original[path] = try { gh.read(repo, path, branch) } catch (_: Exception) { null } }

    /** Badlaav ki chhoti list: "+ naya", "- hataya", "~ badla (+a -b)" */
    fun changeList(): String = staged.entries.joinToString("\n") { (p, c) ->
        val o = original[p]
        when {
            c == null -> "−  $p (हटाया)"
            o == null -> "+  $p (नई, ${c.lines().size} लाइन)"
            else -> {
                val a = o.lines().toHashSet(); val b = c.lines().toHashSet()
                "~  $p (+${c.lines().count { it !in a }} −${o.lines().count { it !in b }})"
            }
        }
    }

    /** Push: saare staged ek commit me */
    suspend fun push(message: String): String = withContext(Dispatchers.IO) {
        val url = gh.commit(repo, branch, staged.toMap(), message.ifBlank { "DK: code changes" })
        // ab GitHub par yahi naya code hai
        staged.forEach { (p, c) -> original[p] = c }
        staged.clear(); files = null
        msgs.add("user" to "PUSHED to $branch: $url. The repository now contains these changes.")
        msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"और कुछ?\"}")
        url
    }

    fun discard() {
        staged.clear()
        msgs.add("user" to "User DISCARDED all staged changes. Repository is unchanged.")
        msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"ठीक है, क्या बदलूँ?\"}")
    }

    /** Purane lambe nateeje chhote karo (free models ka context chhota hota hai) */
    private fun trimContext() {
        var total = msgs.sumOf { it.second.length }
        var i = 4
        while (total > 140_000 && i < msgs.size - 6) {
            val (r, t) = msgs[i]
            if (t.length > 1500) {
                val short = t.take(400) + "\n…(old content removed to save space; read again if needed)"
                total -= t.length - short.length; msgs[i] = r to short
            }
            i++
        }
    }

    companion object {
        /** Pehli line ka JSON + <<<NAAM ... NAAM>>> blocks */
        fun parse(reply: String): Pair<JSONObject, Map<String, String>>? {
            val txt = reply.replace(Regex("^```(?:json)?\\s*"), "")
            val head = txt.substringBefore("<<<")
            val s = head.indexOf('{'); if (s < 0) return null
            var depth = 0; var inStr = false; var esc = false; var end = -1
            for (i in s until head.length) {
                val ch = head[i]
                if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
                when (ch) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = i; break } } }
            }
            if (end < 0) return null
            val o = try { JSONObject(head.substring(s, end + 1)) } catch (_: Exception) { return null }
            val blocks = HashMap<String, String>()
            Regex("(?s)<<<(FILE|FIND|REPLACE)\\r?\\n(.*?)\\r?\\n?\\1>>>").findAll(txt).forEach { blocks[it.groupValues[1]] = it.groupValues[2] }
            return o to blocks
        }
    }
}
