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
        const val TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
        fun fromPrefs(c: android.content.Context): GitHub? = Prefs.ghToken(c).takeIf { it.isNotBlank() }?.let { GitHub(it) }
    }
}
