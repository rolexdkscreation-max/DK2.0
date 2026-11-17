package com.dk.assistant

import android.content.Context
import com.dk.assistant.backend.BackendApi
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 17 नवंबर 2026 (मालिक का "DK बैकएंड मास्टर प्रॉम्प्ट"): कोडिंग टीम / DK कंप्यूटर का हर काम सर्वर (/tasks) पर भी -
 * शुरू होते ही POST, हर असली कदम पर PATCH. History में दिखता है; ऐप बंद होकर खुले तो वहीं से ([CodingActivity]).
 * सिर्फ़ premium / मालिक (सर्वर बाकी को मना करता है). सर्वर न चले = चुपचाप (फ़ोन वाला इतिहास [TaskHistory] वैसा ही).
 */
object TaskSync {
    @Volatile private var app: Context? = null
    private val one = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-task-sync").apply { isDaemon = true } }
    /** एक ही काम के बहुत तेज़ कदम = हर 3 सेकंड में ज़्यादा से ज़्यादा एक PATCH (आख़िरी हालत हमेशा जाती है) */
    private val lastSent = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val created = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private val listener = TaskStatus.Listener { j, ended -> app?.let { c -> if (j.key == TaskStatus.CODER || j.key == TaskStatus.PC) send(c, j, ended) } }

    fun install(c: Context) { app = c.applicationContext; TaskStatus.listen(listener) }

    /** सर्वर वाला id - वही काम = वही id (फ़ोन के [TaskHistory] जैसा) */
    fun idOf(j: TaskStatus.Job) = "${j.key}-${j.started}"

    fun statusOf(s: TaskStatus.State) = when (s) {
        TaskStatus.State.RUNNING -> "running"; TaskStatus.State.WAITING -> "waiting"; TaskStatus.State.DONE -> "done"
        TaskStatus.State.NOT_DONE -> "stopped"; TaskStatus.State.FAILED -> "failed"; TaskStatus.State.CANCELLED -> "stopped"
    }

    /** भेजने वाला JSON (JVM test) - कोई key/token नहीं, सिर्फ़ काम की हालत */
    fun body(j: TaskStatus.Job, branch: String, prUrl: String, repo: String): JSONObject = JSONObject()
        .put("id", idOf(j))
        .put("title", j.task.take(200)).put("status", statusOf(j.state)).put("step", j.last.take(300))
        .put("progress", if (j.state == TaskStatus.State.DONE) 100 else minOf(95, j.steps * 5))
        .apply {
            if (branch.isNotBlank()) put("branch", branch)
            if (prUrl.isNotBlank()) put("pr_url", prUrl)
            if (j.state == TaskStatus.State.FAILED) put("error", j.result.take(300))
            put("meta", JSONObject().put("kind", if (j.key == TaskStatus.CODER) "coder" else "computer").put("who", j.who).put("repo", repo))
        }

    private fun send(c: Context, j: TaskStatus.Job, ended: Boolean) {
        if (!AiRouter.backendReady()) return
        if (BackendApi.lastLevel.isNotBlank() && BackendApi.lastLevel !in setOf("premium", "owner")) return
        val id = idOf(j)
        val now = System.currentTimeMillis()
        if (!ended && id in created && now - (lastSent[id] ?: 0L) < 3_000) return
        lastSent[id] = now
        val a = if (j.key == TaskStatus.CODER) CoderSession.agent else null
        val o = body(j, a?.branch.orEmpty(), a?.pr?.url.orEmpty(), a?.repo.orEmpty())
        one.execute {
            try {
                if (id in created) BackendApi.taskPatch(c, id, o.apply { remove("id"); remove("meta") })   // meta सिर्फ़ पहली बार (build की ख़बर न मिटे)
                else { BackendApi.taskPut(c, o); created.add(id) }
                if (ended) { lastSent.remove(id) }
            } catch (e: Exception) { DKLog.d("काम सर्वर पर नहीं गया: ${e.message?.take(60)}") }
        }
    }
}
