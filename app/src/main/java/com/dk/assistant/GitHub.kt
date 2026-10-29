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

    /**
     * हर GitHub API कॉल: अस्थायी गलती (नेटवर्क, 5xx, secondary rate limit) पर अपने-आप 2 बार और (कुल [Retry.ATTEMPTS]),
     * तब भी fail हो तभी गलती आगे (user को रिपोर्ट). 4xx (token/इजाज़त/नहीं मिला) पर दोबारा कोशिश नहीं.
     */
    private fun run(b: Request.Builder): String {
        var attempt = 1
        while (true) {
            try { return runOnce(b) } catch (e: Err) {
                if (attempt >= Retry.ATTEMPTS || !Retry.isTransient(e.code, e.message.orEmpty())) throw e
                DKLog.d("GitHub ${e.code}: ${e.message?.take(80)} - अपने-आप दोबारा (${attempt + 1}/${Retry.ATTEMPTS})")
            } catch (e: java.io.IOException) {
                if (attempt >= Retry.ATTEMPTS || !Retry.isNetwork(e)) throw e
                DKLog.d("GitHub नेटवर्क (${e.javaClass.simpleName}) - अपने-आप दोबारा (${attempt + 1}/${Retry.ATTEMPTS})")
            }
            Thread.sleep(Retry.backoffMs(attempt))
            attempt++
        }
    }

    private fun runOnce(b: Request.Builder): String = http.newCall(b.build()).execute().use { r ->
        val s = r.body?.string() ?: ""
        lastScopes = r.header("X-OAuth-Scopes")
        if (!r.isSuccessful) {
            val m = try { JSONObject(s).optString("message") } catch (_: Exception) { s.take(160) }
            throw Err(r.code, when (r.code) {
                401 -> "GitHub token गलत है या expire हो गया"
                403 -> if (m.contains("secondary rate limit", true)) "GitHub secondary rate limit ($m)"
                    else if (m.contains("rate limit", true)) "GitHub की limit खत्म, थोड़ी देर बाद"
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

    // ---------------------------------------------------------------- 7-एजेंट सिस्टम: रिपो/branch चुनना, draft PR
    /** token से खुलने वाले सारे रिपो (अपने + साथी + organization), नए पहले. maxPages × 100 तक */
    fun reposAll(maxPages: Int = 10): List<Repo> {
        val out = mutableListOf<Repo>()
        for (page in 1..maxPages) {
            val a = JSONArray(get("/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member"))
            (0 until a.length()).map { a.getJSONObject(it) }
                .forEach { out.add(Repo(it.optString("full_name"), it.optString("default_branch", "main"), it.optBoolean("private"))) }
            if (a.length() < 100) break
        }
        return out.distinctBy { it.fullName }
    }

    /** रिपो की branches (default पहले) */
    fun branches(repo: String, maxPages: Int = 5): List<String> {
        val out = mutableListOf<String>()
        for (page in 1..maxPages) {
            val a = JSONArray(get("/repos/$repo/branches?per_page=100&page=$page"))
            (0 until a.length()).forEach { out.add(a.getJSONObject(it).optString("name")) }
            if (a.length() < 100) break
        }
        return out.filter { it.isNotBlank() }
    }

    /** Draft PR: टेस्ट चलाने के लिए (GitHub Actions pull_request पर चलते हैं); पास होने पर ही review के लिए तैयार */
    fun createDraftPull(repo: String, head: String, base: String, title: String, body: String): Pull = try {
        pull(JSONObject(post("/repos/$repo/pulls", JSONObject().put("title", title.take(120)).put("head", head)
            .put("base", base).put("body", body).put("maintainer_can_modify", true).put("draft", true))))
    } catch (e: Err) {
        if (e.code == 422 && e.message.orEmpty().contains("already exists", true)) openPullFor(repo, head) ?: throw e
        // कुछ रिपो/plan में draft नहीं चलता -> साधारण PR (merge फिर भी सिर्फ़ मंज़ूरी से)
        else if (e.code == 422 && e.message.orEmpty().contains("draft", true)) createPull(repo, head, base, title, body) else throw e
    }

    /** PR draft है? */
    fun isDraft(repo: String, number: Int): Boolean = JSONObject(get("/repos/$repo/pulls/$number")).optBoolean("draft", false)

    /** Draft PR → "Ready for review" (REST में नहीं, GraphQL से) */
    fun markReady(repo: String, number: Int) {
        val node = JSONObject(get("/repos/$repo/pulls/$number")).optString("node_id")
        if (node.isBlank()) throw Err(404, "PR का node id नहीं मिला")
        val q = "mutation(${'$'}id:ID!){markPullRequestReadyForReview(input:{pullRequestId:${'$'}id}){pullRequest{isDraft}}}"
        val r = JSONObject(post("/graphql", JSONObject().put("query", q).put("variables", JSONObject().put("id", node))))
        r.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { throw Err(422, it.getJSONObject(0).optString("message")) }
    }

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
                    if (path.isNotBlank() && bytes.size <= 1_000_000 && !bytes.take(4000).contains(0.toByte()))
                        out[path] = String(bytes, Charsets.UTF_8)
                }
                if (total > 80_000_000L) break                        // bahut bada repo - baaki API se
                e = zin.nextEntry
            }
        }
        return out
    }

    data class Run(val id: Long, val name: String, val status: String, val conclusion: String, val url: String, val event: String = "")

    /** Is commit par chale GitHub Actions (build) */
    fun runsFor(repo: String, sha: String): List<Run> {
        val o = JSONObject(get("/repos/$repo/actions/runs?head_sha=$sha&per_page=10"))
        val a = o.optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Run(it.optLong("id"), it.optString("name"), it.optString("status"), it.optString("conclusion"), it.optString("html_url"), it.optString("event"))
        }
    }

    // ---------------------------------------------------------------- Emulator के screenshot (Actions artifact)
    data class Artifact(val id: Long, val name: String, val expired: Boolean, val size: Long)

    /** एक run के artifacts (जैसे uaag-screenshots, DK-apk) */
    fun artifacts(repo: String, runId: Long): List<Artifact> {
        val a = JSONObject(get("/repos/$repo/actions/runs/$runId/artifacts?per_page=30")).optJSONArray("artifacts") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Artifact(it.optLong("id"), it.optString("name"), it.optBoolean("expired"), it.optLong("size_in_bytes")) }
    }

    /**
     * artifact की zip से तस्वीरें (png/jpg) + छोटी text फ़ाइलें. GitHub zip के लिए दूसरी जगह भेजता है (redirect) -
     * OkHttp उसे खुद मानता है और दूसरी जगह token नहीं भेजता. [maxImages] से ज़्यादा तस्वीरें नहीं.
     * @return फ़ाइल-नाम -> bytes
     */
    fun artifactFiles(repo: String, artifactId: Long, maxImages: Int = 8): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        zipHttp.newCall(req("/repos/$repo/actions/artifacts/$artifactId/zip").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Err(resp.code, if (resp.code == 403 || resp.code == 404)
                "screenshot नहीं मिले (${resp.code}) — token में Actions: Read की इजाज़त दें" else "screenshot zip नहीं मिली (${resp.code})")
            val zin = java.util.zip.ZipInputStream(resp.body!!.byteStream())
            var e = zin.nextEntry
            var imgs = 0
            while (e != null) {
                val n = e.name.substringAfterLast('/')
                if (!e.isDirectory && n.isNotBlank()) {
                    val img = Shots.isImage(n)
                    if ((img && imgs < maxImages) || Shots.isText(n)) {
                        val b = zin.readBytes()
                        if (b.size <= 6_000_000) { out[n] = b; if (img) imgs++ }
                    }
                }
                e = zin.nextEntry
            }
        }
        return out
    }

    // ---------------------------------------------------------------- रिपो डॉक्टर: पूरे रिपो की हालत
    private fun iso(t: String): Long = try { java.time.Instant.parse(t).toEpochMilli() } catch (_: Exception) { 0L }

    /** सारे खुले PR (सबसे नए पहले) */
    fun openPulls(repo: String, max: Int = 30): List<RepoDoctor.Pr> {
        val a = JSONArray(get("/repos/$repo/pulls?state=open&per_page=${max.coerceIn(1, 100)}&sort=updated&direction=desc"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            RepoDoctor.Pr(o.optInt("number"), o.optString("title"), o.optString("html_url"),
                o.optJSONObject("head")?.optString("ref").orEmpty(), o.optJSONObject("base")?.optString("ref").orEmpty(),
                o.optBoolean("draft"), o.optJSONObject("head")?.optString("sha").orEmpty(),
                o.optJSONObject("user")?.optString("login").orEmpty(), iso(o.optString("updated_at")))
        }
    }

    /** एक PR (अपनाने के लिए). null = नहीं मिला */
    fun pullInfo(repo: String, number: Int): RepoDoctor.Pr? {
        val o = try { JSONObject(get("/repos/$repo/pulls/$number")) } catch (e: Err) { if (e.code == 404) return null else throw e }
        return RepoDoctor.Pr(o.optInt("number"), o.optString("title"), o.optString("html_url"),
            o.optJSONObject("head")?.optString("ref").orEmpty(), o.optJSONObject("base")?.optString("ref").orEmpty(),
            o.optBoolean("draft"), o.optJSONObject("head")?.optString("sha").orEmpty(),
            o.optJSONObject("user")?.optString("login").orEmpty(), iso(o.optString("updated_at")),
            if (o.isNull("mergeable")) null else o.optBoolean("mergeable"), open = o.optString("state") == "open")
    }

    /** जाँचकर्ता: PR में कौन-सी फ़ाइलें बदलीं (patch समेत) */
    fun pullFiles(repo: String, number: Int, max: Int = 300): List<RepoInspector.PFile> {
        val out = mutableListOf<RepoInspector.PFile>()
        var page = 1
        while (out.size < max && page <= 3) {
            val a = JSONArray(get("/repos/$repo/pulls/$number/files?per_page=100&page=$page"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                out += RepoInspector.PFile(o.optString("filename"), o.optString("status"), o.optInt("additions"), o.optInt("deletions"), o.optString("patch")) }
            if (a.length() < 100) break
            page++
        }
        return out.take(max)
    }

    /** जाँचकर्ता: PR का विवरण (body) + merged या नहीं */
    fun pullDetail(repo: String, number: Int): RepoInspector.PDetail? {
        val o = try { JSONObject(get("/repos/$repo/pulls/$number")) } catch (e: Err) { if (e.code == 404) return null else throw e }
        return RepoInspector.PDetail(o.optInt("number"), o.optString("title"), o.optString("html_url"), o.optString("body").let { if (it == "null") "" else it },
            o.optString("state"), !o.isNull("merged_at") && o.optString("merged_at").isNotBlank(), o.optString("merged_at").let { if (it == "null") "" else it },
            o.optJSONObject("head")?.optString("ref").orEmpty(), o.optJSONObject("head")?.optString("sha").orEmpty(),
            if (o.isNull("mergeable")) null else o.optBoolean("mergeable"), o.optString("mergeable_state"),
            o.optInt("changed_files"), o.optInt("additions"), o.optInt("deletions"), o.optInt("commits"))
    }

    /** जाँचकर्ता: हाल में बंद/merge हुए PR (सबसे नए पहले) */
    fun closedPulls(repo: String, n: Int = 8): List<RepoInspector.PDetail> {
        val a = JSONArray(get("/repos/$repo/pulls?state=closed&per_page=${n.coerceIn(1, 50)}&sort=updated&direction=desc"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            RepoInspector.PDetail(o.optInt("number"), o.optString("title"), o.optString("html_url"), o.optString("body").let { if (it == "null") "" else it },
                o.optString("state"), !o.isNull("merged_at") && o.optString("merged_at").isNotBlank(), o.optString("merged_at").let { if (it == "null") "" else it },
                o.optJSONObject("head")?.optString("ref").orEmpty(), o.optJSONObject("head")?.optString("sha").orEmpty())
        }
    }

    /** रिपो के सबसे नए GitHub Actions runs (हर branch के) */
    fun recentRuns(repo: String, n: Int = 40): List<RepoDoctor.RunI> {
        val o = JSONObject(get("/repos/$repo/actions/runs?per_page=${n.coerceIn(1, 100)}"))
        val a = o.optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            RepoDoctor.RunI(it.optLong("id"), it.optString("name"), it.optString("status"), it.optString("conclusion"),
                it.optString("html_url"), it.optString("event"), it.optString("head_branch"), it.optString("head_sha"),
                iso(it.optString("run_started_at").ifBlank { it.optString("created_at") }), it.optString("path").substringAfterLast('/'))
        }
    }

    /** commit कब बना (ms) */
    fun commitTimeMs(repo: String, sha: String): Long =
        iso(JSONObject(get("/repos/$repo/commits/$sha")).optJSONObject("commit")?.optJSONObject("committer")?.optString("date").orEmpty())

    /** head branch, base से कितने commit आगे है */
    /** v4.4: base...head में बदली हर फ़ाइल (status removed/modified/added, +/- लाइन) - डिबगर को पूरे काम का हिसाब */
    fun compareFiles(repo: String, base: String, head: String): List<CodeGuard.Change> {
        val a = JSONObject(get("/repos/$repo/compare/${enc(base)}...${enc(head)}")).optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            CodeGuard.Change(o.optString("filename"), o.optString("status"), o.optInt("additions"), o.optInt("deletions"))
        }
    }

    fun aheadBy(repo: String, base: String, head: String): Int =
        JSONObject(get("/repos/$repo/compare/${enc(base)}...${enc(head)}")).optInt("ahead_by", 0)

    /** फँसा हुआ run रोको */
    fun cancelRun(repo: String, runId: Long) { post("/repos/$repo/actions/runs/$runId/cancel", JSONObject()) }

    /** पूरा run दोबारा (cancel हुआ/फँसा run - rerun-failed-jobs सिर्फ़ fail पर चलता है) */
    fun rerunAll(repo: String, runId: Long) { post("/repos/$repo/actions/runs/$runId/rerun", JSONObject()) }

    /** सबसे नई release (डिप्लॉयमेंट एजेंट: APK सच में बनी?). null = कोई release नहीं */
    fun latestRelease(repo: String): SevenPlus.Deploy.Rel? {
        val s = try { get("/repos/$repo/releases/latest") } catch (e: Err) { if (e.code == 404) return null else throw e }
        val o = JSONObject(s)
        val a = o.optJSONArray("assets") ?: JSONArray()
        val ms = try { java.time.Instant.parse(o.optString("published_at")).toEpochMilli() } catch (_: Exception) { 0L }
        return SevenPlus.Deploy.Rel(o.optString("tag_name"), o.optString("name"), o.optString("target_commitish"),
            (0 until a.length()).map { a.getJSONObject(it).optString("name") }, o.optString("html_url"), ms)
    }

    /** workflow (जिसमें workflow_dispatch हो) को किसी branch पर चलाओ - PR से पहले build. token में Actions: Read and write चाहिए */
    fun dispatchWorkflow(repo: String, workflowFile: String, ref: String) {
        post("/repos/$repo/actions/workflows/${enc(workflowFile)}/dispatches", JSONObject().put("ref", ref))
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
            // v3.89: असली एरर सबसे ऊपर (फ़ाइल:लाइन के साथ), runner के path रिपो वाले, बेकार Task/warning लाइनें हटीं
            sb.append(BuildLog.digest(log, 30_000)).append("\n")
        }
        return sb.toString().ifBlank { "(build fail hua par log nahi mila)" }
    }

    private fun put(path: String, body: JSONObject) = run(req(path).put(body.toString().toRequestBody(JSON)))
    private fun delete(path: String) = run(req(path).delete())

    data class Pull(val number: Int, val url: String, val head: String, val base: String)

    private fun pull(o: JSONObject) = Pull(o.optInt("number"), o.optString("html_url"),
        o.optJSONObject("head")?.optString("ref").orEmpty(), o.optJSONObject("base")?.optString("ref").orEmpty())

    /** Pull Request banao (head branch -> base). Token me "Pull requests: Read and write" chahiye */
    fun createPull(repo: String, head: String, base: String, title: String, body: String): Pull = try {
        pull(JSONObject(post("/repos/$repo/pulls", JSONObject().put("title", title.take(120)).put("head", head)
            .put("base", base).put("body", body).put("maintainer_can_modify", true))))
    } catch (e: Err) {
        // ऑटो-रिट्राई में पहली कोशिश से PR बन चुका था (422 already exists) -> वही PR लो, दूसरा नहीं
        if (e.code == 422 && e.message.orEmpty().contains("already exists", true)) openPullFor(repo, head) ?: throw e else throw e
    }

    /** PR का विवरण बदलो (जैसे बैकअप/जड़ की जानकारी जोड़ना) */
    fun editPullBody(repo: String, number: Int, body: String) { patch("/repos/$repo/pulls/$number", JSONObject().put("body", body)) }

    /** head branch base से कितने commit पीछे है (base में नया काम आया जो PR के tests में नहीं चला) */
    fun behindBy(repo: String, base: String, head: String): Int =
        JSONObject(get("/repos/$repo/compare/${enc(base)}...${enc(head)}")).optInt("behind_by", 0)

    /** PR branch में नया base (main) लाओ - GitHub नया merge commit बनाता है और PR के tests फिर चलते हैं */
    fun updateBranch(repo: String, number: Int, expectedHeadSha: String) {
        put("/repos/$repo/pulls/$number/update-branch", JSONObject().put("expected_head_sha", expectedHeadSha))
    }

    /**
     * GitHub की अपनी दिक्कत (नेटवर्क/runner) से fail हुई jobs दोबारा चलाओ (code वही).
     * Token में Actions: Read and write चाहिए; न हो तो Err 403.
     */
    fun rerunFailed(repo: String, runId: Long) { post("/repos/$repo/actions/runs/$runId/rerun-failed-jobs", JSONObject()) }

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
