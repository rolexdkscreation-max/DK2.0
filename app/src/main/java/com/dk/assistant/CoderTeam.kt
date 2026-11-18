package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * कोडिंग एजेंट का प्रवेश-द्वार: काम [SevenRunner] (एक ही एजेंट) को देना, PR merge + APK release, और सूचना/आवाज़ ([announce]).
 * नवंबर 2026: पुरानी बिल्डर → रिव्यूअर → कोऑर्डिनेटर टीम (और उसके "सुझाव") हटाई - [Prefs] में एक-एजेंट हमेशा चालू था,
 * इसलिए वह रास्ता कभी नहीं चलता था.
 */
object CoderTeam {
    private fun say(role: String, t: String) = CoderSession.add(role, t)
    private fun step(t: String) { CoderSession.scope.launch { CoderSession.add("s", t) } }

    fun start(ctx: Context, task: String, fromVoice: Boolean = false): String? {
        val c = ctx.applicationContext
        OneKeyGate.planCheck(c, OneKeyGate.Work.CODING)?.let { return it }     // 18 नवंबर 2026: प्रीमियम लाइसेंस / मालिक
        OneKeyGate.check(c, OneKeyGate.Work.CODING)?.let { return it }
        // रिपो डॉक्टर: "रिपो चेक करो" / "PR #N ठीक करो" / "PR #N merge करो" / उसकी योजना की हाँ
        RepoDoctorRunner.intercept(c, task, fromVoice)?.let { return it.err }
        // कोडिंग एजेंट = हमेशा एक ही एजेंट (SevenRunner). पुरानी बिल्डर/रिव्यूअर टीम वाला रास्ता (कभी नहीं चलता था) हटाया - नवंबर 2026
        return SevenRunner.start(c, task, fromVoice)
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
        if (fromVoice) say("u", "🎤 PR #${p.number} merge करो")
        CoderSession.launch(c) {
            // नवंबर 2026 (तेज़): merge होते ही अगला (कतार वाला) काम; APK release (~14 मिनट) की निगरानी पीछे चलती रहती है
            mergeAndRelease(c, a, p, background = true)
        }
        CoderSession.listener?.invoke()
        return null
    }

    /**
     * डिप्लॉयमेंट: PR की दोबारा जाँच → merge → APK release (पक्का). रिपो डॉक्टर भी अपने काम के अंदर यही चलाता है.
     * @return true = merge हुआ और नई APK release पक्की
     */
    suspend fun mergeAndRelease(c: Context, a: CodingAgent, p: GitHub.Pull, background: Boolean = false): Boolean {
        val repo = a.repo; val base = a.baseBranch
        CoderReport.begin()
        // नवंबर 2026 (मालिक का screenshot): merge/release के समय स्क्रीन पर "मैनेजर काम बाँट रहा है" लिखा आता था - असल में गिट/रिलीज़ काम कर रहा है
        CoderSession.activeAgent = Seven.Member.GIT_RELEASE; CoderSession.activeNote = MergeBg.MERGING
        say("s", "🚀 डिप्लॉयमेंट: PR #${p.number} की दोबारा जाँच (tests/build/conflict, main आगे बढ़ा तो update + tests) करके merge कर रहा हूँ…")
        val mergedMs = System.currentTimeMillis()
        val res = try { a.mergePr { st -> step(st) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val m = (e.message ?: e.javaClass.simpleName).take(300)
            finish(c, "PR #${p.number} merge नहीं हुआ: $m", ok = false, title = "कोडिंग टीम: merge नहीं हुआ",
                spoken = "PR merge नहीं हो पाया। कोडिंग स्क्रीन पर वजह लिखी है।")
            return false
        }
        val sha = a.lastMergeSha
        if (sha.isBlank() && a.lastMergeAsk) {
            // checks चल रहे थे: इंतज़ार किया, अब सब पास - merge user की दोबारा हाँ से (यह गलती नहीं)
            say("d", res)
            announce(c, "कोडिंग टीम: PR #${p.number} merge के लिए तैयार", res,
                "बॉस, PR ${p.number} के सारे checks पास हो गए। merge करूँ? हाँ, merge करो बोलिए।")
            return false
        }
        if (sha.isBlank()) {
            finish(c, res, ok = false, title = "कोडिंग टीम: merge रुका", spoken = "PR अभी merge नहीं किया। " + res.lines().first().take(120))
            return false
        }
        say("d", res)
        if (background) {
            // merge हो गया: यह काम (और "काम चल रहा है") यहीं ख़त्म, अगला काम तुरंत; release की खबर आते ही चैट + आवाज़ में
            say("s", MergeBg.note(base))
            deployJob?.cancel()
            deployJob = CoderSession.scope.launch { try { release(c, a, p, sha, mergedMs, false) } catch (_: CancellationException) {} }
            return true
        }
        return release(c, a, p, sha, mergedMs, true)
    }

    /** नवंबर 2026: पीछे चलती APK release निगरानी (merge के बाद). नया merge आए तो पुरानी रुकती है */
    @Volatile private var deployJob: kotlinx.coroutines.Job? = null

    /** merge के बाद: main पर APK release build देखो / न चले तो खुद चलाओ, नतीजा बताओ. true = नई APK release पक्की */
    private suspend fun release(c: Context, a: CodingAgent, p: GitHub.Pull, sha: String, mergedMs: Long, shown: Boolean): Boolean {
        val repo = a.repo; val base = a.baseBranch
        if (shown) CoderSession.activeNote = MergeBg.RELEASING
        say("s", "🚀 डिप्लॉयमेंट: $base पर release build (APK) देख रहा हूँ — अपने-आप न चले तो खुद चलाऊँगा…")
        val (b, rl, why) = try { a.deployRelease(sha, mergedMs) { st -> step(st) } } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Triple(null, null, (e.message ?: e.javaClass.simpleName).take(200)) }
        val rel = rl?.url?.ifBlank { null } ?: "https://github.com/$repo/releases/latest"
        when {
            b != null && !b.ok -> finish(c, "PR #${p.number} merge हो गया, पर release build fail हुआ ✗\n${b.url}",
                ok = false, title = "कोडिंग टीम: release build fail", spoken = "PR merge हो गया, पर release build fail हो गया। कोडिंग स्क्रीन पर लिंक है।")
            why.isBlank() && rl != null -> finish(c, "PR #${p.number} merge हो गया और नई APK release पक्की बन गई ✓\n" +
                "Release: ${rl.name.ifBlank { rl.tag }} (${rl.tag}) — APK: ${rl.assets.firstOrNull { it.endsWith(".apk", true) }.orEmpty()}\n" +
                "Settings → अपडेट से install करें।\n$rel",
                ok = true, title = "कोडिंग टीम: नई APK release तैयार",
                spoken = "PR merge हो गया, बॉस। नई APK ${rl.name.ifBlank { rl.tag }} release हो गई है, सेटिंग्स में अपडेट से install कर लीजिए।")
            else -> finish(c, "PR #${p.number} merge हो गया, पर नई APK release पक्की नहीं हुई: $why\nGitHub पर देखें: https://github.com/$repo/actions\n$rel",
                ok = false, title = "कोडिंग टीम: merge हुआ, release नहीं",
                spoken = "PR merge हो गया, पर नई APK release पक्की नहीं हुई। कोडिंग स्क्रीन पर वजह लिखी है।")
        }
        return b?.ok != false && why.isBlank() && rl != null
    }

    fun voiceDecision(c: Context, text: String): String? {
        // रिपो डॉक्टर पहले: दूसरे नंबर का PR merge, रिपो की हालत, उसकी योजना की हाँ
        RepoDoctorRunner.intercept(c, text, true)?.let { return it.err ?: it.voice.ifBlank { "ठीक है।" } }
        // "PR merge कर दो" -> jaanch ke baad merge + release APK
        SafePr.mergeIntent(text)?.let { yes ->
            if (!yes) return "ठीक है, PR अभी merge नहीं करूँगी।"
            return merge(c, fromVoice = true) ?: "ठीक है, PR के tests और build देखकर merge कर रही हूँ। नई APK release बनते ही बताऊँगी।"
        }
        return null
    }

    private fun finish(c: Context, text: String, ok: Boolean, title: String? = null, spoken: String? = null) {
        // हर काम के बाद छोटी रिपोर्ट: क्या दिक्कत आई (bug/error/limit) और कैसे ठीक हुई
        // एक ही बात दो बार नहीं: fail पर गलती सिर्फ़ रिपोर्ट के "क्या नहीं हुआ" में
        // यूज़र को छोटी रिपोर्ट: हुआ/नहीं, API चली/नहीं, वजह, हल (API/मॉडल बदलने जैसी अंदर की बातें नहीं)
        say(if (ok) "d" else "e", if (ok) ShortReport.text(true, "", CoderSession.agent?.pr?.url.orEmpty(), CoderSession.agent != null) + "\n\n" + text
            else ShortReport.text(false, text, CoderSession.agent?.pr?.url.orEmpty()))
        val head = title ?: if (ok) "कोडिंग टीम: काम पूरा" else "कोडिंग टीम: काम अधूरा"
        CoderSound.notify(c, CoderSound.kindFor(ok, title), head, text.lines().first().take(120))
        val prReady = CoderSession.agent?.pr != null
        val voice = spoken ?: if (ok && prReady) "कोडिंग का काम पूरा हो गया, बॉस। जाँच और build हो गए, PR आपके review के लिए तैयार है। Merge करना हो तो बोलिए, PR merge कर दो।"
            else if (ok) "कोडिंग का काम पूरा हो गया, बॉस। जाँच और build दोनों हो गए।" else "कोडिंग का काम पूरा नहीं हो पाया। कोडिंग स्क्रीन पर वजह लिखी है."

        DKState.reply(voice)

        val svc = DKAccessibilityService.instance
        if (svc != null && !DKState.live) svc.scope.launch { try { svc.speaker.speak(if (title != null && spoken == null) "कोडिंग वाला जवाब तैयार है।" else voice) } catch (e: Exception) { Quiet.note(e) } }
    }

    /** 7-एजेंट सिस्टम: notification + आवाज़ (रिपोर्ट स्क्रीन पर पहले ही लिखी जा चुकी) */
    fun announce(c: Context, title0: String, text0: String, voice0: String, kind: String = CoderSound.DONE) {
        // 14 नवंबर 2026: सूचना और आवाज़ में भी "कोडिंग टीम / मैनेजर" नहीं - सिर्फ़ DK एजेंट ([SoloVoice])
        val title = SoloVoice.clean(title0)
        val text = SoloVoice.clean(text0)
        val voice = SoloVoice.clean(voice0)
        CoderSound.notify(c, kind, title, text.lines().first().take(120))
        DKState.reply(voice)
        val svc = DKAccessibilityService.instance
        if (svc != null && !DKState.live) svc.scope.launch { try { svc.speaker.speak(voice) } catch (e: Exception) { Quiet.note(e) } }
    }

    // v3.89: पुराना "dk_coder" (फ़ोन की आम tone) हटा - अब CoderSound की अलग आवाज़ें (काम पूरा / बग / रुका)
}