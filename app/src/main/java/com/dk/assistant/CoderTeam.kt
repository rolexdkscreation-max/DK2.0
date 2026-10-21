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
 *                                      sujhav user से पूछना, aur kaam khatam hone par user ko batana (chat + awaaz + notification)
 *
 * User ek baar bolta/likhta hai; baaki chakra (Builder ⇄ Reviewer, phir push + build ⇄ fix) khud chalta hai.
 * Sujhav (jo user ने नहीं मांगा) कभी खुद नहीं जोड़े जाते - "हाँ" मिलने पर ही अगले काम बनते हैं।
 */
object CoderTeam {
    const val MAX_REVIEW_ROUNDS = 5
    const val MAX_BUILD_ROUNDS = 3

    data class Suggestion(val id: Int, val title: String, val why: String, val task: String, var state: String = "pending") // pending/yes/no/done
    val suggestions = mutableListOf<Suggestion>()
    private var nextId = 1
    private val queue = ArrayDeque<String>()

    private fun say(role: String, t: String) = CoderSession.add(role, t)
    private fun step(t: String) { CoderSession.scope.launch { CoderSession.add("s", t) } }

    fun start(ctx: Context, task: String, fromVoice: Boolean = false): String? {
        val c = ctx.applicationContext
        val p = CoderSession.problems(c)
        if (p.isNotEmpty()) return "कोडिंग एजेंट तैयार नहीं: " + p.joinToString(", ")
        if (CoderSession.busy) {
            queue.addLast(task); say("u", task); say("s", "कोऑर्डिनेटर: अभी का काम पूरा होते ही यह शुरू होगा (कतार में ${queue.size})")
            return null
        }
        val a = CoderSession.agentFor(c) ?: return "GitHub या AI सेटअप नहीं मिला"
        say("u", (if (fromVoice) "🎤 " else "") + task)
        CoderSession.launch(c) {
            try {
                var currentTask: String? = task
                while (currentTask != null) {
                    val taskToRun = currentTask
                    kotlinx.coroutines.withTimeout(10 * 60 * 1000L) {
                        pipeline(c, a, taskToRun)
                    }
                    currentTask = queue.removeFirstOrNull()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val msg = "कोडिंग का काम तय समय (10 मिनट) में पूरा नहीं हो पाया।"
                say("e", msg)
                finish(c, msg, ok = false, title = "कोडिंग टीम: टाइमआउट")
            } catch (e: CancellationException) {
                say("e", "टीम का काम रोक दिया।")
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName).take(300)
                say("e", "गलती: $msg")
                finish(c, "कोडिंग टीम में त्रुटि आई: $msg", ok = false, title = "कोडिंग टीम: विफलता")
            } finally { a.teamMode = false; a.onSuggest = null; CoderSession.listener?.invoke() }
        }
        CoderSession.listener?.invoke()
        return null
    }

    private suspend fun pipeline(c: Context, a: CodingAgent, task: String) {
        a.teamMode = true
        a.onSuggest = { t, w, tk -> CoderSession.scope.launch { addSuggestion(c, t, w, tk) } }

        say("s", "कोऑर्डिनेटर: काम समझ रहा हूँ…")
        val plan = plan(c, task)
        if (plan.second.isNotEmpty()) say("s", "कोऑर्डिनेटर: पूरा होने की शर्तें —\n" + plan.second.joinToString("\n") { "• $it" })

        say("s", "बिल्डर: काम शुरू…")
        var out = a.run(task + if (plan.first.isNotBlank()) "\n(Coordinator brief: ${plan.first})" else "") { st -> step("बिल्डर: $st") }
        var asked = 0
        while (out.question && asked < 2) {
            asked++
            say("s", "कोऑर्डिनेटर: बिल्डर ने पूछा \"${out.summary.take(120)}\" — सबसे सही अंदाज़े से आगे बढ़ने को कहा")
            out = a.run("Coordinator: do not wait for the user. Choose the most reasonable option yourself, note the assumption in the summary, and finish the task.") { st -> step("बिल्डर: $st") }
        }
        if (out.question) { finish(c, "बिल्डर को जानकारी चाहिए: ${out.summary}", ok = false); return }
        if (a.staged.isEmpty()) { finish(c, out.summary.ifBlank { "कोई बदलाव ज़रूरी नहीं था।" }, ok = true, title = "जवाब तैयार"); return }
        var commit = out.commit.ifBlank { "DK: ${task.take(60)}" }
        val summary = out.summary.ifBlank { "काम हो गया।" }

        var buildRound = 0
        while (true) {
            var r: CodingAgent.Review? = null
            for (round in 1..MAX_REVIEW_ROUNDS) {
                say("s", "रिव्यूअर: जाँच शुरू (राउंड $round)…")
                r = a.review(task, plan.second, round) { st -> step(st) }
                val currentR = r
                if (currentR?.pass == true) { say("d", "रिव्यूअर ✓ पास (राउंड $round)\n${currentR.tested.take(400)}"); break }
                say("e", "रिव्यूअर ✗ ${currentR?.issues?.size ?: 0} गड़बड़ी (राउंड $round)\n" + (currentR?.issues ?: emptyList()).take(6).joinToString("\n") { "• ${it.take(200)}" })
                if (round == MAX_REVIEW_ROUNDS) break
                say("s", "कोऑर्डिनेटर: बिल्डर को ठीक करने भेजा")
                val fix = a.run("REVIEWER (round $round) found these problems in your staged changes. Fix every one properly; change nothing unrelated:\n" +
                    (currentR?.issues ?: emptyList()).joinToString("\n") { "- $it" }) { st -> step("बिल्डर: $st") }
                if (fix.commit.isNotBlank()) commit = fix.commit
            }
            val finalR = r
            if (finalR?.pass != true) {
                finish(c, "$MAX_REVIEW_ROUNDS राउंड के बाद भी रिव्यूअर संतुष्ट नहीं। बदलाव push नहीं किए — नीचे \"फ़र्क देखें\" से देखें।\nबाकी गड़बड़ी:\n" +
                    (finalR?.issues ?: emptyList()).take(5).joinToString("\n") { "• ${it.take(160)}" }, ok = false)
                return
            }

            say("s", "कोऑर्डिनेटर: GitHub पर push…")
            val n = a.staged.size
            val url = a.push(commit)
            say("d", "Push हो गया ($n फ़ाइल) → ${a.branch}\n$url" + when {
                a.pr != null -> "\n🔀 PR #${a.pr!!.number} → ${a.baseBranch}: ${a.pr!!.url}"
                a.safePr && a.prError.isNotBlank() -> "\n⚠ PR नहीं बना: ${a.prError}"
                else -> ""
            })
            say("s", "कोऑर्डिनेटर: GitHub build देख रहा हूँ…")
            val b = a.watchBuild(a.lastSha) { st -> step(st) }
            if (b == null) {
                finish(c, summary + "\n\n✓ रिव्यूअर पास • ✓ Push\n⚠ GitHub build की जानकारी नहीं मिली (token में Actions: Read दें) — build अलग से देखें।", ok = true)
                return
            }
            if (b.ok) {
                val prNote = a.pr?.let { "\n🔀 PR #${it.number} review के लिए तैयार — GitHub पर देखकर कोडिंग स्क्रीन पर \"Merge करें\" दबाएँ या DK से बोलें \"PR merge कर दो\"। Merge होते ही GitHub Actions नई APK release बनाएगा (main में अभी कुछ नहीं गया)\n${it.url}" }
                    ?: if (a.canRetryPr) "\n⚠ PR नहीं बना, इसलिए Merge का बटन नहीं है: ${a.prError}\nकोडिंग स्क्रीन पर \"PR फिर बनाएँ\" दबाएँ।" else ""
                finish(c, summary + "\n\n✓ रिव्यूअर पास • ✓ Push • ✓ GitHub tests + build सफल\n${b.url}$prNote", ok = true); return
            }
            buildRound++
            say("e", "Build fail ✗ (${buildRound}/$MAX_BUILD_ROUNDS)\n${b.url}")
            if (buildRound >= MAX_BUILD_ROUNDS) { finish(c, "$MAX_BUILD_ROUNDS बार ठीक करने के बाद भी build fail。\n${b.url}", ok = false); return }
            say("s", "कोऑर्डिनेटर: build की गलती बिल्डर को भेजी")
            val fix = a.run("BUILD FAILED on GitHub Actions after the last push (commit ${a.lastSha.take(7)}). Find the real cause, read the mentioned files/lines, fix ONLY that.\nBUILD LOG:\n${b.log}") { st -> step("बिल्डर: $st") }
            commit = fix.commit.ifBlank { "Fix build" }
            if (a.staged.isEmpty()) { finish(c, "बिल्डर build की गलती नहीं ढूँढ पाया。\n${b.url}", ok = false); return }
        }
    }

    /**
     * Review ke baad Merge (screen ka button ya awaaz "PR merge कर दो"):
     * PR khula + conflict nahi + saare tests/build hare -> merge -> main par "Build DK APK" release dekhna -> user ko batana.
     * @return null = shuru ho gaya; warna wajah (Hindi)
     */
    fun merge(ctx: Context, fromVoice: Boolean = false): String? {
        val c = ctx.applicationContext
        val p0 = CoderSession.problems(c)
        if (p0.isNotEmpty()) return "कोडिंग एजेंट तैयार नहीं: " + p0.joinToString(", ")
        if (CoderSession.busy) return "अभी कोडिंग का काम चल रहा है — पूरा होने के बाद merge कर दूँगी।"
        val a = CoderSession.agentFor(c) ?: return "GitHub या AI सेटअप नहीं मिला"
        val p = a.pr ?: return if (a.canRetryPr) "PR बना ही नहीं था: ${a.prError}" else "अभी merge के लिए कोई PR तैयार नहीं है।"
        val repo = a.repo; val base = a.baseBranch
        if (fromVoice) say("u", "🎤 PR #${p.number} merge करो")
        CoderSession.launch(c) {
            say("s", "PR #${p.number} की जाँच (tests/build/conflict) करके merge कर रहा हूँ…")
            val res = try { a.mergePr() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val m = (e.message ?: e.javaClass.simpleName).take(300)
                finish(c, "PR #${p.number} merge नहीं हुआ: $m", ok = false, title = "कोडिंग टीम: merge नहीं हुआ",
                    spoken = "PR merge नहीं हो पाया। कोडिंग स्क्रीन पर वजह लिखी है।")
                return@launch
            }
            val sha = a.lastMergeSha
            if (sha.isBlank()) {
                finish(c, res, ok = false, title = "कोडिंग टीम: merge रुका", spoken = "PR अभी merge नहीं किया। " + res.lines().first().take(120))
                return@launch
            }
            say("d", res)
            say("s", "$base पर GitHub Actions की release build (APK) देख रहा हूँ…")
            val b = try { a.watchBuild(sha) { st -> step(st) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val rel = "https://github.com/$repo/releases/latest"
            when {
                b == null -> finish(c, "PR #${p.number} merge हो गया। Release build की जानकारी नहीं मिली — GitHub पर Actions देखें।\n$rel",
                    ok = true, title = "कोडिंग टीम: merge हो गया", spoken = "PR merge हो गया, बॉस। Release build GitHub पर देख लीजिए।")
                b.ok -> finish(c, "PR #${p.number} merge हो गया और नई APK release बन गई ✓\nSettings → अपडेट से install करें।\n$rel",
                    ok = true, title = "कोडिंग टीम: नई APK release तैयार",
                    spoken = "PR merge हो गया, बॉस। नई APK release बन गई है, सेटिंग्स में अपडेट से install कर लीजिए।")
                else -> finish(c, "PR #${p.number} merge हो गया, पर release build fail हुआ ✗\n${b.url}",
                    ok = false, title = "कोडिंग टीम: release build fail", spoken = "PR merge हो गया, पर release build fail हो गया। कोडिंग स्क्रीन पर लिंक है।")
            }
        }
        CoderSession.listener?.invoke()
        return null
    }

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

    private fun addSuggestion(c: Context, title: String, why: String, task: String) {
        if (suggestions.any { it.title.equals(title, true) || it.task.equals(task, true) }) return
        val sg = Suggestion(nextId++, title.take(80), why.take(300), task.take(600))
        suggestions.add(sg)
        say("s", "💡 सुझाव: ${sg.title} — नीचे हाँ/नहीं चुनें (काम चलता रहेगा)")
        val svc = DKAccessibilityService.instance
        if (svc != null && !DKState.live) svc.scope.launch { try { svc.speaker.speak("एक सुझाव है: ${sg.title}। जोड़ना हो तो बोलिए, सुझाव जोड़ दो।") } catch (_: Exception) {} }
        CoderSession.listener?.invoke()
    }

    val pending get() = suggestions.filter { it.state == "pending" }

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

    fun voiceDecision(c: Context, text: String): String? {
        // "PR merge कर दो" -> jaanch ke baad merge + release APK
        SafePr.mergeIntent(text)?.let { yes ->
            if (!yes) return "ठीक है, PR अभी merge नहीं करूँगी।"
            return merge(c, fromVoice = true) ?: "ठीक है, PR के tests और build देखकर merge कर रही हूँ। नई APK release बनते ही बताऊँगी।"
        }
        val low = text.lowercase()
        if (!(low.contains("सुझाव") || low.contains("suggestion") || low.contains("sujhav"))) return null
        val sg = pending.lastOrNull() ?: return "अभी कोई सुझाव बाकी नहीं है।"
        val no = listOf("नहीं", "नही", "मत", "छोड़", "रद्द", "no", "nahi", "mat").any { low.contains(it) }
        val yes = listOf("जोड़", "हाँ", "हां", "ठीक", "कर दो", "बना", "yes", "haan", "add", "जोड").any { low.contains(it) }
        if (!no && !yes) return null
        decide(c, sg.id, yes && !no)
        return if (yes && !no) "ठीक है, \"${sg.title}\" भी जोड़ दूँगी।" else "ठीक है, \"${sg.title}\" नहीं जोड़ूँगी।"
    }

    private fun finish(c: Context, text: String, ok: Boolean, title: String? = null, spoken: String? = null) {
        say(if (ok) "d" else "e", (if (ok) "✅ " else "⚠ ") + text)
        val head = title ?: if (ok) "कोडिंग टीम: काम पूरा" else "कोडिंग टीम: काम अधूरा"
        notifyUser(c, head, text.lines().first().take(120))
        val prReady = CoderSession.agent?.pr != null
        val voice = spoken ?: if (ok && prReady) "कोडिंग का काम पूरा हो गया, बॉस। जाँच और build हो गए, PR आपके review के लिए तैयार है। Merge करना हो तो बोलिए, PR merge कर दो।"
            else if (ok) "कोडिंग का काम पूरा हो गया, बॉस। जाँच और build दोनों हो गए।" else "कोडिंग का काम पूरा नहीं हो पाया। कोडिंग स्क्रीन पर वजह लिखी है."

        DKState.reply(voice)

        val svc = DKAccessibilityService.instance
        if (svc != null && !DKState.live) svc.scope.launch { try { svc.speaker.speak(if (title != null && spoken == null) "कोडिंग वाला जवाब तैयार है।" else voice) } catch (_: Exception) {} }
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