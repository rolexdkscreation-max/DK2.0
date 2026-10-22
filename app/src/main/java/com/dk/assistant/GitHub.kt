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
 * Token: github.com -> Settings -> Developer settings -> Fine-grained token -> Contents: Read and write,
 * Pull requests: Read and write (सुरक्षित PR मोड), Actions: Read (build/tests देखना).
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

    /** आखिरी जवाब का X-OAuth-Scopes (सिर्फ़ classic token भेजता है; fine-grained = null) */
    @Volatile private var lastScopes: String? = null

    private fun run(b: Request.Builder): String = http.newCall(b.build()).execute().use { r ->
        val s = r.body?.string() ?: ""
        lastScopes = r.header("X-OAuth-Scopes")
        if (!r.isSuccessful) {
            val m = try { JSONObject(s).optString("message") } catch (_: Exception) { s.take(160) }
            throw Err(r.code, when (r.code) {
                401 -> "GitHub token गलत है या expire हो गया"
                403 -> if (m.contains("rate limit", true)) "GitHub की limit खत्म, थोड़ी देर बाद"
                    else permissionAdvice(403, r.header("X-Accepted-GitHub-Permissions"), lastScopes, m, "")
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
                    l.contains("Unresolved reference") || l.contains("Exception") || l.startsWith("> ") || l.contains("##[error]") ||
                    l.contains(" FAILED") || l.contains("AssertionError") || l.contains("tests completed")
            }.map { it.index }
            val pick = sortedSetOf<Int>()
            key.take(60).forEach { k -> for (x in (k - 1)..(k + 3)) if (x in lines.indices) pick.add(x) }
            val body = if (pick.isEmpty()) lines.takeLast(80) else pick.map { lines[it] }
            sb.append(body.joinToString("\n").take(9000)).append("\n")
        }
        return sb.toString().ifBlank { "(build fail hua par log nahi mila)" }
    }

    private fun put(path: String, body: JSONObject) = run(req(path).put(body.toString().toRequestBody(JSON)))
    private fun delete(path: String) = run(req(path).delete())

    data class Pull(val number: Int, val url: String, val head: String, val base: String)

    private fun pull(o: JSONObject) = Pull(o.optInt("number"), o.optString("html_url"),
        o.optJSONObject("head")?.optString("ref").orEmpty(), o.optJSONObject("base")?.optString("ref").orEmpty())

    /** Pull Request banao (head branch -> base). Token me "Pull requests: Read and write" chahiye */
    fun createPull(repo: String, head: String, base: String, title: String, body: String): Pull =
        pull(JSONObject(post("/repos/$repo/pulls", JSONObject().put("title", title.take(120)).put("head", head)
            .put("base", base).put("body", body).put("maintainer_can_modify", true))))

    /** Is branch ka khula PR (agar pehle se bana ho) */
    fun openPullFor(repo: String, head: String): Pull? {
        val owner = repo.substringBefore('/')
        val a = JSONArray(get("/repos/$repo/pulls?state=open&per_page=5&head=" + java.net.URLEncoder.encode("$owner:$head", "UTF-8")))
        return if (a.length() == 0) null else pull(a.getJSONObject(0))
    }

    /** PR ki halat: state (open/closed), merged, mergeable (null = GitHub abhi hisaab laga raha) aur head sha */
    data class PullState(val open: Boolean, val merged: Boolean, val mergeable: Boolean?, val headSha: String)

    fun pullState(repo: String, number: Int): PullState {
        val o = JSONObject(get("/repos/$repo/pulls/$number"))
        return PullState(o.optString("state") == "open", o.optBoolean("merged"),
            if (o.isNull("mergeable")) null else o.optBoolean("mergeable"), o.optJSONObject("head")?.optString("sha").orEmpty())
    }

    /** PR merge (squash = main par ek saaf commit). @return merge commit sha */
    fun mergePull(repo: String, number: Int, title: String, method: String = "squash"): String =
        JSONObject(put("/repos/$repo/pulls/$number/merge", JSONObject().put("merge_method", method).put("commit_title", title.take(120))))
            .optString("sha")

    fun deleteBranch(repo: String, branch: String) { delete("/repos/$repo/git/refs/heads/${enc(branch)}") }

    fun createBranch(repo: String, newBranch: String, from: String) {
        val sha = JSONObject(get("/repos/$repo/git/ref/heads/${enc(from)}")).getJSONObject("object").getString("sha")
        post("/repos/$repo/git/refs", JSONObject().put("ref", "refs/heads/$newBranch").put("sha", sha))
    }

    /**
     * Push से पहले: क्या इस token से इस repo में लिख सकते हैं (Contents: Read and write)?
     * @return null = ठीक है; वरना साफ़ हिंदी में कि token में क्या बदलना है
     */
    fun checkWrite(repo: String): String? {
        val info = try { JSONObject(get("/repos/$repo")) } catch (e: Err) {
            return if (e.code == 404) permissionAdvice(404, null, lastScopes, "", repo) else if (e.code == 401) e.message else null
        }
        val scopes = lastScopes
        // classic token: repo scope चाहिए
        if (!scopes.isNullOrBlank() && !scopes.split(',').map { it.trim() }.any { it == "repo" || (it == "public_repo" && !info.optBoolean("private")) })
            return permissionAdvice(403, null, scopes, "", repo)
        // आपका GitHub account ही इस repo में लिख नहीं सकता
        if (info.optJSONObject("permissions")?.optBoolean("push", true) == false)
            return "आपके GitHub account को $repo में लिखने (push) की इजाज़त नहीं है — repo के मालिक से Write access माँगें।"
        // fine-grained token: एक छोटा blob बनाकर असली इजाज़त जाँचो (commit नहीं बनता, कुछ नहीं बदलता)
        return try {
            post("/repos/$repo/git/blobs", JSONObject().put("content", "dk").put("encoding", "utf-8")); null
        } catch (e: Err) {
            when (e.code) {
                403, 404 -> permissionAdvice(e.code, null, scopes, "", repo)
                else -> null // नेटवर्क/दूसरी दिक्कत - असली commit में पता चलेगा
            }
        }
    }

    fun remoteBranchSha(repo: String, branch: String): String =
        JSONObject(get("/repos/$repo/git/ref/heads/${enc(branch)}")).getJSONObject("object").getString("sha")

    /**
     * Saare badlaav EK commit me push. changes: path -> naya content (null = file mitao).
     * @return commit ka link
     */
    fun commit(repo: String, branch: String, changes: Map<String, String?>, message: String): String {
        require(changes.isNotEmpty()) { "कोई बदलाव नहीं" }
        return try { commitOnce(repo, branch, changes, message) } catch (e: Err) {
            // बीच में branch पर कोई और commit आ गया (fast forward नहीं) -> नए head पर एक बार और
            if (e.code != 422 || !e.message.orEmpty().contains("fast forward", true)) throw e
            commitOnce(repo, branch, changes, message)
        }
    }

    private fun commitOnce(repo: String, branch: String, changes: Map<String, String?>, message: String): String {
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
        val sha = c.getString("sha")
        patch("/repos/$repo/git/refs/heads/${enc(branch)}", JSONObject().put("sha", sha).put("force", false))
        // पक्का करो: GitHub पर branch सच में नए commit पर है - तभी "push हो गया"
        val now = try { remoteBranchSha(repo, branch) } catch (_: Exception) { sha }
        if (now != sha) throw Err(409, "Push पक्का नहीं हुआ: $branch अभी भी ${now.take(7)} पर है, नया commit ${sha.take(7)} नहीं पहुँचा — फिर से push करें")
        return c.optString("html_url").ifBlank { "https://github.com/$repo/commit/${c.getString("sha")}" }
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        private val zipHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
        const val TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
        /**
         * 403/404 पर साफ़ हिंदी: token में क्या बदलना है.
         * accepted = X-Accepted-GitHub-Permissions (fine-grained), scopes = X-OAuth-Scopes (classic).
         */
        fun permissionAdvice(code: Int, accepted: String?, scopes: String?, msg: String, repo: String): String {
            val r = if (repo.isBlank()) "इस repo" else repo
            val need = accepted?.split(',', ';')?.map { it.trim() }?.filter { it.isNotBlank() }
                ?.joinToString { p -> p.substringBefore('=').replace('_', ' ').replaceFirstChar { it.uppercase() } + ": " + if (p.endsWith("write")) "Read and write" else "Read" }
            return when {
                !scopes.isNullOrBlank() -> "GitHub token (classic) में \"repo\" scope नहीं है (अभी: $scopes)। " +
                    "github.com → Settings → Developer settings → Personal access tokens → Tokens (classic) → token खोलें → \"repo\" पर ✔ लगाएँ → Update token।"
                code == 404 -> "Token को $r दिखता ही नहीं। github.com → Settings → Developer settings → Fine-grained tokens → token खोलें → " +
                    "Repository access में \"$r\" चुनें, और Repository permissions → Contents: Read and write दें → Save।"
                else -> "Token को $r में लिखने की इजाज़त नहीं${if (msg.isBlank()) "" else " ($msg)"}। " +
                    "github.com → Settings → Developer settings → Fine-grained tokens → token खोलें → Repository access में यह repo, " +
                    "Repository permissions → ${need?.takeIf { it.isNotBlank() } ?: "Contents: Read and write"} दें → Save। फिर काम दोबारा भेजें।"
            }
        }

        fun fromPrefs(c: android.content.Context): GitHub? = Prefs.ghToken(c).takeIf { it.isNotBlank() }?.let { GitHub(it) }
    }
}
