package com.dk.assistant

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub connector (REST API + aapka Personal Access Token).
 * Padhna, file likhna/badalna/mitana aur ek hi commit me push (Git Data API).
 * Token: github.com -> Settings -> Developer settings -> Fine-grained token -> Contents: Read and write.
 */
class GitHub(private val token: String) {

    class Err(val code: Int, msg: String) : java.io.IOException(msg)
    data class Repo(val fullName: String, val branch: String, val private: Boolean)
    data class Entry(val path: String, val size: Int)

    private val api = "https://api.github.com"
    private val JSON = "application/json".toMediaType()

    private fun req(path: String) = Request.Builder().url(if (path.startsWith("http")) path else api + path)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "DK-Assistant")

    private fun run(b: Request.Builder): String = http.newCall(b.build()).execute().use { r ->
        val s = r.body?.string() ?: ""
        if (!r.isSuccessful) {
            val m = try { JSONObject(s).optString("message") } catch (_: Exception) { s.take(160) }
            throw Err(r.code, when (r.code) {
                401 -> "GitHub token गलत है या expire हो गया"
                403 -> if (m.contains("rate limit", true)) "GitHub की limit खत्म, थोड़ी देर बाद" else "इस token को इजाज़त नहीं ($m) — token में Contents: Read and write दें"
                404 -> "नहीं मिला (repo/file/branch का नाम देखें, या token को इस repo की इजाज़त दें)"
                409 -> "Repo खाली है या टकराव ($m)"
                422 -> "GitHub ने मना किया: $m"
                else -> "GitHub ${r.code}: $m"
            })
        }
        s
    }

    private fun get(path: String) = run(req(path).get())
    private fun post(path: String, body: JSONObject) = run(req(path).post(body.toString().toRequestBody(JSON)))
    private fun patch(path: String, body: JSONObject) = run(req(path).patch(body.toString().toRequestBody(JSON)))

    private fun enc(p: String) = p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** Token kiska hai (login naam) */
    fun me(): String = JSONObject(get("/user")).optString("login")

    /** Aapke repos (naye pehle) */
    fun repos(): List<Repo> {
        val a = JSONArray(get("/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator"))
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Repo(it.optString("full_name"), it.optString("default_branch", "main"), it.optBoolean("private")) }
    }

    fun defaultBranch(repo: String): String = JSONObject(get("/repos/$repo")).optString("default_branch", "main")

    /** Repo ki saari files (sirf files, folder nahi) */
    fun tree(repo: String, branch: String): List<Entry> {
        val o = JSONObject(get("/repos/$repo/git/trees/${enc(branch)}?recursive=1"))
        val t = o.optJSONArray("tree") ?: JSONArray()
        return (0 until t.length()).map { t.getJSONObject(it) }.filter { it.optString("type") == "blob" }
            .map { Entry(it.optString("path"), it.optInt("size")) }
    }

    /** File padho (text). null = file nahi hai */
    fun read(repo: String, path: String, branch: String): String? {
        val s = try { get("/repos/$repo/contents/${enc(path)}?ref=${enc(branch)}") } catch (e: Err) { if (e.code == 404) return null else throw e }
        val o = JSONObject(s)
        if (o.optString("type") != "file") throw Err(400, "$path file नहीं है")
        val c = o.optString("content")
        if (c.isBlank() && o.optInt("size") > 0) {
            // 1 MB se badi: blob se lo
            val b = JSONObject(get("/repos/$repo/git/blobs/${o.optString("sha")}"))
            return String(Base64.decode(b.optString("content"), Base64.DEFAULT), Charsets.UTF_8)
        }
        return String(Base64.decode(c, Base64.DEFAULT), Charsets.UTF_8)
    }

    /** Code me shabd dhundo (GitHub code search; sirf default branch) */
    fun search(repo: String, q: String): List<String> {
        val o = JSONObject(get("/search/code?per_page=30&q=" + java.net.URLEncoder.encode("$q repo:$repo", "UTF-8")))
        val a = o.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).optString("path") }
    }

    /**
     * Poore repo ki ek copy (zip) - har file alag-alag API se maangne ki jagah EK request.
     * Sirf text files (binary / 400 KB se badi chhod di). path -> content
     */
    fun snapshot(repo: String, branch: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val r = zipHttp.newCall(req("/repos/$repo/zipball/${enc(branch)}").get().build()).execute()
        r.use { resp ->
            if (!resp.isSuccessful) throw Err(resp.code, "repo zip नहीं मिला (${resp.code})")
            val zin = java.util.zip.ZipInputStream(resp.body!!.byteStream())
            var e = zin.nextEntry
            var total = 0L
            while (e != null) {
                if (!e.isDirectory) {
                    val path = e.name.substringAfter('/')           // "owner-repo-sha/" hatao
                    val bytes = zin.readBytes()
                    total += bytes.size
                    if (path.isNotBlank() && bytes.size <= 400_000 && !bytes.take(4000).contains(0.toByte()))
                        out[path] = String(bytes, Charsets.UTF_8)
                }
                if (total > 40_000_000L) break                        // bahut bada repo - baaki API se
                e = zin.nextEntry
            }
        }
        return out
    }

    data class Run(val id: Long, val name: String, val status: String, val conclusion: String, val url: String)

    /** Is commit par chale GitHub Actions (build) */
    fun runsFor(repo: String, sha: String): List<Run> {
        val o = JSONObject(get("/repos/$repo/actions/runs?head_sha=$sha&per_page=10"))
        val a = o.optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Run(it.optLong("id"), it.optString("name"), it.optString("status"), it.optString("conclusion"), it.optString("html_url"))
        }
    }

    /** Fail hue build ki asli galtiyan (Kotlin "e: ...", "error:", Gradle "What went wrong") - chhota hissa */
    fun failureLog(repo: String, runId: Long): String {
        val jobs = JSONObject(get("/repos/$repo/actions/runs/$runId/jobs?per_page=30")).optJSONArray("jobs") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            if (j.optString("conclusion") != "failure") continue
            val steps = j.optJSONArray("steps") ?: JSONArray()
            val failedStep = (0 until steps.length()).map { steps.getJSONObject(it) }.firstOrNull { it.optString("conclusion") == "failure" }?.optString("name") ?: "?"
            sb.append("JOB: ${j.optString("name")} • FAILED STEP: $failedStep\n")
            val log = try { get("/repos/$repo/actions/jobs/${j.optLong("id")}/logs") } catch (e: Exception) { "" }
            val lines = log.lines().map { it.replace(Regex("^\\S+Z\\s"), "") }       // timestamp hatao
            val key = lines.withIndex().filter { (_, l) ->
                l.startsWith("e: ") || l.contains("error:", true) || l.contains("What went wrong") || l.contains("FAILURE:") ||
                    l.contains("Unresolved reference") || l.contains("Exception") || l.startsWith("> ") || l.contains("##[error]")
            }.map { it.index }
            val pick = sortedSetOf<Int>()
            key.take(60).forEach { k -> for (x in (k - 1)..(k + 3)) if (x in lines.indices) pick.add(x) }
            val body = if (pick.isEmpty()) lines.takeLast(80) else pick.map { lines[it] }
            sb.append(body.joinToString("\n").take(9000)).append("\n")
        }
        return sb.toString().ifBlank { "(build fail hua par log nahi mila)" }
    }

    fun createBranch(repo: String, newBranch: String, from: String) {
        val sha = JSONObject(get("/repos/$repo/git/ref/heads/${enc(from)}")).getJSONObject("object").getString("sha")
        post("/repos/$repo/git/refs", JSONObject().put("ref", "refs/heads/$newBranch").put("sha", sha))
    }

    /**
     * Saare badlaav EK commit me push. changes: path -> naya content (null = file mitao).
     * @return commit ka link
     */
    fun commit(repo: String, branch: String, changes: Map<String, String?>, message: String): String {
        require(changes.isNotEmpty()) { "कोई बदलाव नहीं" }
        val head = JSONObject(get("/repos/$repo/git/ref/heads/${enc(branch)}")).getJSONObject("object").getString("sha")
        val baseTree = JSONObject(get("/repos/$repo/git/commits/$head")).getJSONObject("tree").getString("sha")
        val items = JSONArray()
        changes.forEach { (path, content) ->
            val it = JSONObject().put("path", path.trimStart('/')).put("mode", "100644").put("type", "blob")
            if (content == null) it.put("sha", JSONObject.NULL) else it.put("content", content)
            items.put(it)
        }
        val tree = JSONObject(post("/repos/$repo/git/trees", JSONObject().put("base_tree", baseTree).put("tree", items))).getString("sha")
        val c = JSONObject(post("/repos/$repo/git/commits", JSONObject().put("message", message)
            .put("tree", tree).put("parents", JSONArray().put(head))))
        patch("/repos/$repo/git/refs/heads/${enc(branch)}", JSONObject().put("sha", c.getString("sha")).put("force", false))
        return c.optString("html_url").ifBlank { "https://github.com/$repo/commit/${c.getString("sha")}" }
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        private val zipHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
        const val TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
        fun fromPrefs(c: android.content.Context): GitHub? = Prefs.ghToken(c).takeIf { it.isNotBlank() }?.let { GitHub(it) }
    }
}
