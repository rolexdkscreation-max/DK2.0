package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक: "DK में भी cloud Linux sandbox - GitHub Codespaces वाला तरीका जोड़ो").
 *
 * GitHub की API से codespace बनाया/चालू/बंद हो सकता है, पर उसके अंदर command चलाने का endpoint नहीं; private port पर
 * codespace का अपना token चाहिए (फ़ोन के पास नहीं). इसलिए उल्टा रास्ता - codespace खुद काम उठाता है:
 *  1. DK commit push करता है (पहले जैसा), फिर सिर्फ़ एक ref बनाता है: dk-run/<sha12> -> वही commit (नया commit नहीं).
 *  2. codespace के अंदर .devcontainer/dk-runner.sh वह माँग देखकर उसी commit पर `gradle :app:testDebugUnitTest` चलाता है.
 *  3. नतीजा branch dk-result/<sha12> की dk-result.txt में ("DK-RESULT OK|FAIL", secs=, असली compile/test गलतियाँ).
 *  4. DK पढ़कर ref हटाता है. fail = असली compiler/test लाइनें तुरंत डिबगर को (5-10 मिनट CI का इंतज़ार नहीं).
 *     pass = आगे पहले जैसा GitHub Actions (आख़िरी पहरा). कुछ भी न चले (इजाज़त नहीं / घंटे ख़त्म / देर) = null -> पुराना CI रास्ता.
 * कोई port नहीं खुलता, कोई नई key नहीं; runner कोई और command कभी नहीं चलाता.
 */
class CoderSandbox(
    private val api: Api,
    private val repo: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    /** GitHub REST (असल में [GitHub.call]; JUnit में नकली) */
    fun interface Api { fun call(method: String, path: String, body: JSONObject?): String }

    /** इस काम में sandbox बंद (इजाज़त नहीं / टूट गया) - दोबारा कोशिश नहीं, वजह एक बार बताई */
    @Volatile var off: String = ""; private set
    @Volatile private var name: String = ""

    /** codespace तैयार करवाओ (बनाओ / चालू करो) - इंतज़ार नहीं. काम शुरू होते ही, ताकि push तक गरम रहे */
    fun warm(onStep: (String) -> Unit = {}) {
        if (off.isNotEmpty()) return
        try {
            val cs = find() ?: create().also { onStep("☁ Codespace sandbox पहली बार बन रहा है (Android SDK लगेगा - पहली जाँच में कुछ मिनट लगेंगे)") }
            name = cs.optString("name")
            if (Sandbox.needsStart(cs.optString("state"))) start()
        } catch (e: Exception) { disable(e) }
    }

    /**
     * इस commit पर असली compile + unit tests. @return नतीजा, या null = sandbox इस बार काम का नहीं (पुराना CI रास्ता लो)
     */
    suspend fun check(sha: String, onStep: (String) -> Unit): TeamTest? {
        if (off.isNotEmpty() || sha.length < 12) return null
        val key = Sandbox.key(sha)
        try {
            if (name.isEmpty()) warm(onStep)
            if (off.isNotEmpty() || name.isEmpty()) return null
            // चालू होने तक
            val upBy = now() + Sandbox.START_WAIT_MS
            while (true) {
                val st = state()
                if (Sandbox.ready(st)) break
                if (Sandbox.dead(st)) { onStep("☁ Codespace sandbox की हालत \"$st\" — इस बार GitHub Actions से जाँच"); return null }
                if (Sandbox.needsStart(st)) start()
                if (now() > upBy) { onStep("☁ Codespace sandbox समय पर चालू नहीं हुआ — इस बार GitHub Actions से जाँच"); return null }
                sleep(Sandbox.POLL_MS)
            }
            request(sha, key)
            onStep("☁ Codespace sandbox में असली compile + सारे unit tests चल रहे हैं (commit ${sha.take(7)})")
            val by = now() + Sandbox.RESULT_WAIT_MS
            while (now() <= by) {
                sleep(Sandbox.POLL_MS)
                val txt = result(key) ?: continue
                val r = Sandbox.parse(txt) ?: continue
                cleanup(key)
                onStep(if (r.ok) "☁ Sandbox: compile + unit tests पास (${r.secs}s) — अब GitHub Actions की आख़िरी जाँच"
                       else "☁ Sandbox: गलती मिली (${r.secs}s) — असली compiler/test की लाइनें डिबगर को, CI का इंतज़ार किए बिना")
                return Sandbox.toTest(r)
            }
            cleanup(key)
            onStep("☁ Sandbox का नतीजा ${Sandbox.RESULT_WAIT_MS / 60_000} मिनट में नहीं आया (पहली बार SDK लग रहा हो सकता है) — इस बार GitHub Actions से जाँच")
            return null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { disable(e); onStep("☁ Sandbox: $off — GitHub Actions से जाँच"); return null }
    }

    /** काम ख़त्म: codespace बंद (फ़्री घंटे बचें). disk/SDK बचा रहता है */
    fun stop() {
        val n = name.ifEmpty { return }
        try { api.call("POST", "/user/codespaces/$n/stop", null) } catch (_: Exception) {}
    }

    // ---- GitHub कॉल ----
    private fun find(): JSONObject? = Sandbox.pick(JSONObject(api.call("GET", "/repos/$repo/codespaces?per_page=50", null)).optJSONArray("codespaces"), repo)

    private fun create(): JSONObject {
        val machines = JSONObject(api.call("GET", "/repos/$repo/codespaces/machines", null)).optJSONArray("machines")
        val body = JSONObject().put("ref", Sandbox.REF).put("devcontainer_path", Sandbox.DEVCONTAINER)
            .put("display_name", Sandbox.NAME).put("idle_timeout_minutes", Sandbox.IDLE_MIN).put("retention_period_minutes", Sandbox.KEEP_MIN)
        Sandbox.machine(machines)?.let { body.put("machine", it) }
        return JSONObject(api.call("POST", "/repos/$repo/codespaces", body))
    }

    private fun start() { try { api.call("POST", "/user/codespaces/$name/start", null) } catch (e: GitHub.Err) { if (e.code != 409) throw e } }
    private fun state(): String = JSONObject(api.call("GET", "/user/codespaces/$name", null)).optString("state")

    private fun request(sha: String, key: String) {
        // पिछली बार का बचा नतीजा (उसी commit का) = पहले हटाओ, ताकि पुराना न पढ़ें
        try { api.call("DELETE", "/repos/$repo/git/refs/heads/${Sandbox.RESULT}$key", null) } catch (_: Exception) {}
        try { api.call("POST", "/repos/$repo/git/refs", JSONObject().put("ref", "refs/heads/${Sandbox.RUN}$key").put("sha", sha)) }
        catch (e: GitHub.Err) { if (e.code != 422) throw e }   // पहले से माँगा हुआ = ठीक
    }

    private fun result(key: String): String? = try {
        Sandbox.decode(JSONObject(api.call("GET", "/repos/$repo/contents/${Sandbox.FILE}?ref=${Sandbox.RESULT}$key", null)))
    } catch (e: GitHub.Err) { if (e.code == 404) null else throw e }

    private fun cleanup(key: String) {
        for (r in listOf(Sandbox.RUN, Sandbox.RESULT)) try { api.call("DELETE", "/repos/$repo/git/refs/heads/$r$key", null) } catch (_: Exception) {}
    }

    private fun disable(e: Exception) {
        off = Sandbox.why(e)
        runCatching { DKLog.d("Codespace sandbox बंद: $off") }   // JUnit (बिना Android) में भी न टूटे
    }
}

/** पक्का (pure) हिस्सा - JUnit: CoderSandboxTest */
object Sandbox {
    const val NAME = "dk-sandbox"
    const val REF = "main"
    const val DEVCONTAINER = ".devcontainer/devcontainer.json"
    const val RUN = "dk-run/"
    const val RESULT = "dk-result/"
    const val FILE = "dk-result.txt"
    const val IDLE_MIN = 20
    /** रुका codespace 30 दिन तक रखा (SDK/Gradle cache बचे); उसके बाद GitHub हटाता है, अगली बार फिर बनेगा */
    const val KEEP_MIN = 43_200
    const val POLL_MS = 8_000L
    const val START_WAIT_MS = 5 * 60_000L
    const val RESULT_WAIT_MS = 15 * 60_000L
    const val LOG_MAX = 6_000

    fun key(sha: String) = sha.trim().lowercase().take(12)

    /** इस repo का DK वाला codespace (नाम dk-sandbox; न हो तो इसी devcontainer वाला) */
    fun pick(list: JSONArray?, repo: String): JSONObject? {
        val all = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }
            .filter { it.optJSONObject("repository")?.optString("full_name").equals(repo, ignoreCase = true) }
            .filter { !dead(it.optString("state")) }
        return all.firstOrNull { it.optString("display_name") == NAME }
            ?: all.firstOrNull { it.optString("devcontainer_path") == DEVCONTAINER }
    }

    /** सबसे छोटी मशीन (कम core = फ़्री घंटे ज़्यादा चलें) */
    fun machine(list: JSONArray?): String? =
        (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }
            .filter { it.optString("operating_system", "linux") == "linux" }
            .minByOrNull { it.optInt("cpus", 99) }?.optString("name")?.takeIf { it.isNotBlank() }

    fun ready(state: String) = state == "Available"
    fun needsStart(state: String) = state == "Shutdown" || state == "ShuttingDown"
    fun dead(state: String) = state in setOf("Failed", "Unavailable", "Deleted", "Archived", "Unknown")

    /** contents API का जवाब -> text */
    fun decode(o: JSONObject): String? {
        val c = o.optString("content").ifBlank { return null }
        return try { String(java.util.Base64.getMimeDecoder().decode(c), Charsets.UTF_8) } catch (_: Exception) { null }
    }

    /** runner की फ़ाइल -> नतीजा */
    fun parse(text: String): Sandbox.R? {
        val lines = text.lines()
        val head = lines.firstOrNull()?.trim().orEmpty()
        val ok = when (head) { "DK-RESULT OK" -> true; "DK-RESULT FAIL" -> false; else -> return null }
        val secs = lines.firstOrNull { it.startsWith("secs=") }?.removePrefix("secs=")?.trim()?.toIntOrNull() ?: 0
        val body = lines.drop(1).filterNot { it.startsWith("secs=") || it.startsWith("sha=") }.joinToString("\n").trim()
        return R(ok, secs, body.take(LOG_MAX))
    }

    data class R(val ok: Boolean, val secs: Int, val log: String)

    fun toTest(r: R): TeamTest = TeamTest(r.ok, if (r.ok) "" else "Codespace sandbox (gradle :app:testDebugUnitTest):\n" + r.log, "",
        if (r.ok) "Codespace sandbox में compile + unit tests पास (${r.secs}s)" else "", TeamTest.BRANCH)

    /** गलती -> छोटी वजह (एक बार बताओ, फिर इस काम में sandbox बंद) */
    fun why(e: Exception): String = when ((e as? GitHub.Err)?.code) {
        401 -> "GitHub token गलत/expire"
        403, 404 -> "token में Codespaces की इजाज़त नहीं (classic: codespace scope; fine-grained: Codespaces + Codespaces lifecycle admin = write)"
        402 -> "Codespaces के फ़्री घंटे ख़त्म / billing नहीं"
        else -> "चल नहीं पाया (${e.message?.take(80) ?: e.javaClass.simpleName})"
    }
}
