package com.dk.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Multi-agent coding team - ek hi option (Settings → कोडिंग एजेंट → टीम).
 *
 *  1. BUILDER   (CodingAgent.run)    - user ki baat sunkar code likhta/badalta hai
 *  2. REVIEWER  (CodingAgent.review) - bina pooche khud jaanchta hai: bug, compile galti, logic, adhoora kaam, bina maange badlaav
 *  3. COORDINATOR (yeh object)       - yojana banata hai, kab kisko kaam dena / wapas bhejna, push, GitHub build dekhna,
 *                                      sujhav user se poochna, aur kaam khatam hone par user ko batana (chat + awaaz + notification)
 *
 * User ek baar bolta/likhta hai; baaki chakra (Builder ⇄ Reviewer, phir push + build ⇄ fix) khud chalta hai.
 * Sujhav (jo user ne nahi maanga) kabhi khud nahi jode jaate - "हाँ" milne par hi agla kaam bante hain.
 */
object CoderTeam {
    const val MAX_REVIEW_ROUNDS = 5
    const val MAX_BUILD_ROUNDS = 3

    data class Suggestion(val id: Int, val title: String, val why: String, val task: String, var state: String = "pending") // pending/yes/no/done
    val suggestions = mutableListOf<Suggestion>()
    private var nextId = 1
    /** "हाँ" mile sujhav - mukhya kaam ke baad isi team se banenge */
    private val queue = ArrayDeque<String>()

    private fun say(role: String, t: String) = CoderSession.add(role, t)
    private fun step(t: String) { CoderSession.scope.launch { CoderSession.add("s", t) } }

    /**
     * Naya kaam (screen ya awaaz se). Jawab: null = shuru ho gaya, warna kya theek karna hai.
     */
    fun start(ctx: Context, task: String, fromVoice: Boolean = false): String? {
        val c = ctx.applicationContext
        val p = CoderSession.problems(c)
        if (p.isNotEmpty()) return "कोडिंग एजेंट तैयार नहीं: " + p.joinToString(", ")
        if (CoderSession.busy) {
            // pehle wala chal raha hai -> line me lagao (rokna nahi)
            queue.addLast(task); say("u", task); say("s", "कोऑर्डिनेटर: अभी का काम पूरा होते ही यह शुरू होगा (कतार में ${queue.size})")
            return null
        }
        val a = CoderSession.agentFor(c) ?: return "GitHub या AI सेटअप नहीं मिला"
        say("u", (if (fromVoice) "🎤 " else "") + task)
        CoderSession.job = CoderSession.scope.launch {
            try {
                var t: String? = task
                while (t != null) { pipeline(c, a, t); t = queue.removeFirstOrNull() }
            } catch (e: CancellationException) { say("e", "टीम का काम रोक दिया।")
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName).take(300)
                say("e", "गलती: $msg"); notifyUser(c, "कोडिंग टीम रुक गई", msg)
            } finally { a.teamMode = false; a.onSuggest = null; CoderSession.listener?.invoke() }
        }
        CoderSession.listener?.invoke()
        return null
    }

    // ================================================================ coordinator ka poora chakra
    private suspend fun pipeline(c: Context, a: CodingAgent, task: String) {
        a.teamMode = true
        a.onSuggest = { t, w, tk -> CoderSession.scope.launch { addSuggestion(c, t, w, tk) } }

        // 1) yojana: lakshya + "kab maana jaye ki kaam pura" (Reviewer isi se jaanchega)
        say("s", "कोऑर्डिनेटर: काम समझ रहा हूँ…")
        val plan = plan(c, task)
        if (plan.second.isNotEmpty()) say("s", "कोऑर्डिनेटर: पूरा होने की शर्तें —\n" + plan.second.joinToString("\n") { "• $it" })

        // 2) Builder
        say("s", "बिल्डर: काम शुरू…")
        var out = a.run(task + if (plan.first.isNotBlank()) "\n(Coordinator brief: ${plan.first})" else "") { st -> step("बिल्डर: $st") }
        var asked = 0
        while (out.question && asked < 2) {
            // team mode me user ko beech me nahi rokte - sabse sahi andaza lagwao
            asked++
            say("s", "कोऑर्डिनेटर: बिल्डर ने पूछा \"${out.summary.take(120)}\" — सबसे सही अंदाज़े से आगे बढ़ने को कहा")
            out = a.run("Coordinator: do not wait for the user. Choose the most reasonable option yourself, note the assumption in the summary, and finish the task.") { st -> step("बिल्डर: $st") }
        }
        if (out.question) { finish(c, "बिल्डर को जानकारी चाहिए: ${out.summary}", ok = false); return }
        if (a.staged.isEmpty()) { finish(c, out.summary.ifBlank { "कोई बदलाव ज़रूरी नहीं था।" }, ok = true, title = "जवाब तैयार"); return }
        var commit = out.commit.ifBlank { "DK: ${task.take(60)}" }
        val summary = out.summary.ifBlank { "काम हो गया।" }

        // 3) Builder ⇄ Reviewer jab tak sahi na ho
        var buildRound = 0
        while (true) {
            var r: CodingAgent.Review? = null
            for (round in 1..MAX_REVIEW_ROUNDS) {
                say("s", "रिव्यूअर: जाँच शुरू (राउंड $round)…")
                r = a.review(task, plan.second, round) { st -> step(st) }
                if (r.pass) { say("d", "रिव्यूअर ✓ पास (राउंड $round)\n${r.tested.take(400)}"); break }
                say("e", "रिव्यूअर ✗ ${r.issues.size} गड़बड़ी (राउंड $round)\n" + r.issues.take(6).joinToString("\n") { "• ${it.take(200)}" })
                if (round == MAX_REVIEW_ROUNDS) break
                say("s", "कोऑर्डिनेटर: बिल्डर को ठीक करने भेजा")
                val fix = a.run("REVIEWER (round $round) found these problems in your staged changes. Fix every one properly; change nothing unrelated:\n" +
                    r.issues.joinToString("\n") { "- $it" }) { st -> step("बिल्डर: $st") }
                if (fix.commit.isNotBlank()) commit = fix.commit
            }
            if (r?.pass != true) {
                finish(c, "$MAX_REVIEW_ROUNDS राउंड के बाद भी रिव्यूअर संतुष्ट नहीं। बदलाव push नहीं किए — नीचे \"फ़र्क देखें\" से देखें।\nबाकी गड़बड़ी:\n" +
                    (r?.issues ?: emptyList()).take(5).joinToString("\n") { "• ${it.take(160)}" }, ok = false)
                return
            }

            // 4) push (team mode: apne-aap) + GitHub build = asli compile/test
            say("s", "कोऑर्डिनेटर: GitHub पर push…")
            val n = a.staged.size
            val url = a.push(commit)
            say("d", "Push हो गया ($n फ़ाइल)\n$url")
            say("s", "कोऑर्डिनेटर: GitHub build देख रहा हूँ…")
            val b = a.watchBuild(a.lastSha) { st -> step(st) }
            if (b == null) {
                finish(c, summary + "\n\n✓ रिव्यूअर पास • ✓ Push\n⚠ GitHub build की जानकारी नहीं मिली (token में Actions: Read दें) — build अलग से देखें।", ok = true)
                return
            }
            if (b.ok) { finish(c, summary + "\n\n✓ रिव्यूअर पास • ✓ Push • ✓ GitHub build सफल\n${b.url}", ok = true); return }
            buildRound++
            say("e", "Build fail ✗ (${buildRound}/$MAX_BUILD_ROUNDS)\n${b.url}")
            if (buildRound >= MAX_BUILD_ROUNDS) { finish(c, "$MAX_BUILD_ROUNDS बार ठीक करने के बाद भी build fail।\n${b.url}", ok = false); return }
            say("s", "कोऑर्डिनेटर: build की गलती बिल्डर को भेजी")
            val fix = a.run("BUILD FAILED on GitHub Actions after the last push (commit ${a.lastSha.take(7)}). Find the real cause, read the mentioned files/lines, fix ONLY that.\nBUILD LOG:\n${b.log}") { st -> step("बिल्डर: $st") }
            commit = fix.commit.ifBlank { "Fix build" }
            if (a.staged.isEmpty()) { finish(c, "बिल्डर build की गलती नहीं ढूँढ पाया।\n${b.url}", ok = false); return }
            // dobara Reviewer -> push -> build
        }
    }

    /** Coordinator ki chhoti yojana: (brief, acceptance criteria) + zaroori sujhav */
    private suspend fun plan(c: Context, task: String): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val ai = CoderAi.fromPrefs(c) ?: return@withContext "" to emptyList()
        val sys = """
You are DK Coordinator for a coding team (Builder + Reviewer). Read the user's task (Hindi/Hinglish) and reply with ONLY one JSON object:
{"brief":"one English sentence: what exactly to build/change","acceptance":["3-6 short Hindi checks that prove the task is fully done"],
 "suggestions":[{"title":"Hindi","why":"simple Hindi reason","task":"English task"}]}
acceptance must only cover what the user asked. suggestions: ONLY things the user did not mention but that are technically required or clearly important
(e.g. a needed Android permission, crash/security/data-loss risk). Usually []. Never put them into acceptance.
""".trimIndent()
        try {
            val txt = ai.chat(sys, listOf("user" to "TASK: $task"))
            val s = txt.indexOf('{'); val e = txt.lastIndexOf('}')
            val o = JSONObject(txt.substring(s, e + 1))
            val acc = o.optJSONArray("acceptance")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            o.optJSONArray("suggestions")?.let { arr ->
                for (i in 0 until minOf(2, arr.length())) arr.optJSONObject(i)?.let { sg ->
                    val t = sg.optString("title"); val tk = sg.optString("task")
                    if (t.isNotBlank() && tk.isNotBlank()) withContext(Dispatchers.Main) { addSuggestion(c, t, sg.optString("why"), tk) }
                }
            }
            o.optString("brief") to acc.take(6)
        } catch (e: Exception) { DKLog.d("Coordinator plan nahi bana: ${e.message}"); "" to emptyList() }
    }

    // ================================================================ sujhav (proactive suggestions)
    private fun addSuggestion(c: Context, title: String, why: String, task: String) {
        if (suggestions.any { it.title.equals(title, true) || it.task.equals(task, true) }) return
        val sg = Suggestion(nextId++, title.take(80), why.take(300), task.take(600))
        suggestions.add(sg)
        say("s", "💡 सुझाव: ${sg.title} — नीचे हाँ/नहीं चुनें (काम चलता रहेगा)")
        // awaaz se bhi bata do (chhota) - mukhya kaam nahi rukta
        val svc = DKAccessibilityService.instance
        if (svc != null && !DKState.live) svc.scope.launch { try { svc.speaker.speak("एक सुझाव है: ${sg.title}। जोड़ना हो तो बोलिए, सुझाव जोड़ दो।") } catch (_: Exception) {} }
        CoderSession.listener?.invoke()
    }

    val pending get() = suggestions.filter { it.state == "pending" }

    /** User ka faisla. yes = mukhya kaam ke baad (ya abhi, agar khaali) team isse banayegi */
    fun decide(c: Context, id: Int, yes: Boolean) {
        val sg = suggestions.firstOrNull { it.id == id && it.state == "pending" } ?: return
        sg.state = if (yes) "yes" else "no"
        if (!yes) { say("s", "सुझाव छोड़ा: ${sg.title}"); CoderSession.listener?.invoke(); return }
        say("s", "सुझाव मंज़ूर: ${sg.title}")
        val t = "APPROVED SUGGESTION from the user: ${sg.task}"
        if (CoderSession.busy) { queue.addLast(t); say("s", "कोऑर्डिनेटर: मौजूदा काम के बाद इसे बनाएँगे") }
        else start(c, t)
        CoderSession.listener?.invoke()
    }

    /** Awaaz se: "सुझाव जोड़ दो" / "सुझाव नहीं" - true = sambhal liya */
    fun voiceDecision(c: Context, text: String): String? {
        val low = text.lowercase()
        if (!(low.contains("सुझाव") || low.contains("suggestion") || low.contains("sujhav"))) return null
        val sg = pending.lastOrNull() ?: return "अभी कोई सुझाव बाकी नहीं है।"
        val no = listOf("नहीं", "नही", "मत", "छोड़", "रद्द", "no", "nahi", "mat").any { low.contains(it) }
        val yes = listOf("जोड़", "हाँ", "हां", "ठीक", "कर दो", "बना", "yes", "haan", "add", "जोड").any { low.contains(it) }
        if (!no && !yes) return null
        decide(c, sg.id, yes && !no)
        return if (yes && !no) "ठीक है, \"${sg.title}\" भी जोड़ दूँगी।" else "ठीक है, \"${sg.title}\" नहीं जोड़ूँगी।"
    }

    // ================================================================ user ko batana
    private fun finish(c: Context, text: String, ok: Boolean, title: String? = null) {
        say(if (ok) "d" else "e", (if (ok) "✅ " else "⚠ ") + text)
        val head = title ?: if (ok) "कोडिंग टीम: काम पूरा" else "कोडिंग टीम: काम अधूरा"
        notifyUser(c, head, text.lines().first().take(120))
        val spoken = if (ok) "कोडिंग का काम पूरा हो गया, बॉस। जाँच और build दोनों हो गए।" else "कोडिंग का काम पूरा नहीं हो पाया। कोडिंग स्क्रीन पर वजह लिखी है."
        
        // Notify DK state / live / main chat so DK tells the user directly
        DKState.reply(spoken)
        
        val svc = DKAccessibilityService.instance
        if (svc != null && !DKState.live) svc.scope.launch { try { svc.speaker.speak(if (title != null) "कोडिंग वाला जवाब तैयार है।" else spoken) } catch (_: Exception) {} }
    }

    private fun notifyUser(c: Context, title: String, text: String) = try {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("dk_coder") == null)
            nm.createNotificationChannel(NotificationChannel("dk_coder", "DK कोडिंग टीम", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 7301, Intent(c, CodingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, "dk_coder") else @Suppress("DEPRECATION") android.app.Notification.Builder(c))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true)
        nm.notify(7301, b.build())
    } catch (_: Exception) {}
}
