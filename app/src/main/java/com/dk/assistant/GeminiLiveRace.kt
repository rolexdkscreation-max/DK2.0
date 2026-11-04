package com.dk.assistant

import com.dk.assistant.GeminiLive.Attempt
import com.dk.assistant.GeminiLive.Companion.LIVE_HOST
import com.dk.assistant.GeminiLive.Companion.PARK_MS
import com.dk.assistant.GeminiLive.Companion.URL
import com.dk.assistant.GeminiLive.Parked
import com.dk.assistant.GeminiLive.Race
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

// GeminiLive का हिस्सा: (मॉडल × key) की दौड़: कई connection एक साथ खोलकर सबसे तेज़ चुनना, WebSocket connect.
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

/** बँटवारे में यह मॉडल TEXT लेता है? (planSplit जैसा, पर हर मॉडल का अपना) */
internal fun GeminiLive.textFor(m: String): Boolean = LiveSetupRules.askText(m, connSplit)

internal fun GeminiLive.startRace(keys: List<String>, models: List<String>, live: Boolean, resumeId: String?): Race {
    curRace?.let { if (!it.over) cancelRace(it, "नई दौड़") }
    val r = Race(live, keys.size)
    curRace = r
    val now = System.currentTimeMillis()
    // नवंबर 2026: 1011 से टूटे मॉडल (6 घंटे) सबसे पीछे - पहले 2 मिनट बाद फिर उन्हीं पर लौट आता था
    val safe = LiveStay.safeOrder(models) { broken1011.isBroken(it, now) }
    var queue = health.order(safe, keys.size, now) { Cooldown.blocked(LiveKeys.cooldownName(it.keyIdx, it.model)) }.toMutableList()
    // सारी जोड़ियाँ सिर्फ़ नेट की वजह से रुकी हों और पिछली नाकामी 55s से पुरानी = फिर आज़माओ (बैकअप मोड से Live पर लौटने की कोशिश)
    if (queue.isEmpty() && now - lastRaceFailAt > 55_000L) {
        health.clearComboFails()
        // नवंबर 2026: इस सत्र में अभी तक कोई दौड़ नाकाम नहीं (ऐप/सेवा नई शुरू) - पुरानी save हुई key-रोक से
        // शुरू होते ही "server जवाब नहीं दे रहा" नहीं; एक साफ़ मौका
        if (lastRaceFailAt == 0L) health.clearKeyBad()
        if (lastRaceFailAt == 0L) {
            // नवंबर 2026: फ़ोन में save हुई Live मॉडल-रोक भी एक बार साफ़ (पहले यह कभी नहीं हटती थी - कोशिश ही नहीं होती थी)
            val held = models.flatMap { m -> keys.indices.map { LiveKeys.cooldownName(it, m) } }.filter { Cooldown.blocked(it) }
            if (held.isNotEmpty()) { held.forEach { Cooldown.clear(it) }; log("नई शुरुआत: पुरानी save हुई Live रोक हटाई (${held.size}) - एक साफ़ मौका") }
        }
        saveHealth()
        queue = health.order(safe, keys.size, now) { Cooldown.blocked(LiveKeys.cooldownName(it.keyIdx, it.model)) }.toMutableList()
    }
    // नवंबर 2026: फिर भी सब रुका = चुपचाप 0 जोड़ियाँ नहीं - जिसकी रोक सबसे जल्दी खत्म हो, उस एक जोड़ी पर एक कोशिश
    if (queue.isEmpty() && models.isNotEmpty() && now - lastProbeAt > LiveRace.COMBO_FAIL_MS) {
        val untilOf = { c: LiveRace.Combo -> Cooldown.until(LiveKeys.cooldownName(c.keyIdx, c.model)) }
        LiveRace.probeWhenEmpty(models, keys.size, untilOf)?.let { c ->
            lastProbeAt = now; queue.add(c)
            log("सारी जोड़ियाँ रुकी थीं (" + models.joinToString { m -> m + " " + ((Cooldown.until(LiveKeys.cooldownName(0, m)) - now) / 60_000).coerceAtLeast(0) + "m" } +
                ") - एक जाँच-कोशिश: ${c.label}")
        }
    }
    val tmo = LiveRace.timeoutMs(health.recentTimes())
    // नवंबर 2026: बातचीत वहीं से (handle) वाली जोड़ी सबसे पहले - दूसरी जीतती तो handle छूटता (संदर्भ खो जाता)
    if (resumeId != null) queue.indexOfFirst { it.id == resumeId }.takeIf { it > 0 }?.let { queue.add(0, queue.removeAt(it)) }
    // नवंबर 2026 (DKI-D24C3A): setup पर बार-बार अटका मॉडल 30 मिनट सबसे पीछे (हटाया नहीं) - एक key पर एक समय एक ही
    // जोड़ी चलती है, अटका मॉडल पहले = हर बार 10s+ का इंतज़ार
    queue = LiveRecover.Demote.order(queue, now) { it.model }.toMutableList()
    // नवंबर 2026 (मालिक): चल रही बातचीत की जोड़ी पहले, फिर उसी key के बाकी मॉडल, फिर अगली key ([LiveRace.stickyOrder])
    val stickyId = resumeId ?: health.lastWin.takeIf { it.isNotBlank() }
    val hung = queue.filter { LiveRecover.Demote.demoted(it.model, now) }
    queue = (LiveRace.stickyOrder(queue - hung.toSet(), stickyId) + hung).toMutableList()
    var stickyRetried = 0
    log("दौड़${if (live) "" else " (पीछे से, माइक नहीं)"}: ${queue.size} जोड़ियाँ, हर एक का इंतज़ार $tmo ms (हाल के नाप: ${health.recentTimes().joinToString()}) • " +
        queue.take(4).joinToString { it.label })
    if (queue.isEmpty()) {
        r.lastFail = lastRaceFail?.takeIf { now - lastRaceFailAt < 10 * 60_000L } ?: (0 to "सारी जोड़ियाँ अभी रुकी हुई हैं (अभी-अभी नाकाम)")
        r.over = true
        return r
    }
    svc.scope.launch(Dispatchers.IO) {
        val inFlight = ArrayList<Attempt>()
        var lastLaunch = 0L
        try {
            while (!r.winner.isCompleted && !r.cancelled) {
                val t = System.currentTimeMillis()
                for (a in inFlight.toList()) {
                    if (a.won) continue
                    if (a.closed.isCompleted) {
                        inFlight.remove(a)
                        val (code, reason) = a.closed.await()
                        attemptFailed(r, a, code, reason, queue); lastLaunch = 0L      // नाकाम = अगली तुरंत
                        if (LiveRace.retrySticky(a.c.id == stickyId, LiveRace.classify(code, reason), stickyRetried)) {
                            stickyRetried++; queue.removeAll { it.id == a.c.id }; queue.add(0, a.c)
                            log("दौड़: चल रही जोड़ी [${a.c.label}] नेट से चूकी - मॉडल नहीं बदला, उसी पर एक बार और")
                        }
                    } else if (t - a.t0 > LiveRace.attemptTimeoutMs(tmo, opened = a.sentAt > 0L,
                            othersLeft = queue.isNotEmpty() || inFlight.size > 1)) {
                        inFlight.remove(a); killAttempt(a, "setup timeout")
                        health.comboFail(a.c, t)
                        health.time(LiveRace.timeoutPenaltyMs(t - a.t0))     // अगली बार इंतज़ार बढ़े (पहले कभी नहीं बढ़ता था)
                        saveHealth()
                        // WebSocket खुला ही नहीं (onOpen नहीं आया) = Google के server तक पहुँचे ही नहीं; खुला पर setupComplete नहीं = धीमा
                        if (a.sentAt == 0L) DkDns.reportUnreachable(LIVE_HOST)
                        else if (LiveRecover.Demote.setupHung(a.c.model, t))
                            log("मॉडल ${a.c.model}: socket खुला पर लगातार ${LiveRecover.Demote.STREAK} बार setup का जवाब नहीं - ${LiveRecover.Demote.DEMOTE_MS / 60_000} मिनट दौड़ में सबसे पीछे (हटाया नहीं)")
                        r.lastFail = 0 to (if (a.sentAt == 0L) "connection खुला ही नहीं - ${tmo / 1000}s में Google के server तक नहीं पहुँचा"
                            else "setup का जवाब ${tmo / 1000} सेकंड में नहीं आया"); r.lastFailCombo = a.c
                        log("दौड़: [${a.c.label}] ${t - a.t0} ms में setup नहीं - यह जोड़ी 2 मिनट नहीं, अगली तुरंत")
                        lastLaunch = 0L
                        if (LiveRace.retrySticky(a.c.id == stickyId, null, stickyRetried)) {
                            stickyRetried++; queue.removeAll { it.id == a.c.id }; queue.add(0, a.c)
                            log("दौड़: चल रही जोड़ी [${a.c.label}] का setup धीमा - मॉडल नहीं बदला, उसी पर एक बार और")
                        }
                    }
                }
                if (r.winner.isCompleted || r.cancelled) break
                // नवंबर 2026: एक key पर एक समय में एक ही जोड़ी (फ्री key की ~3 सत्र वाली सीमा - caption/standby भी उसी key पर)
                val ni = LiveRace.nextLaunchable(queue, inFlight.map { it.c.keyIdx }.toSet())
                if (ni >= 0 && LiveRace.shouldLaunch(inFlight.size, queue.size, t, lastLaunch) &&
                    !LiveRace.holdForSticky(inFlight.map { it.c.id }, stickyId)) {
                    val c = queue.removeAt(ni)
                    val a = Attempt(c, keys[c.keyIdx], textFor(c.model), resume = c.id == resumeId)
                    inFlight.add(a); r.atts.add(a); lastLaunch = t
                    log("जुड़ रहा हूँ: [${c.label}]" + (if (inFlight.size > 1) " (साथ में ${inFlight.size - 1} और चल रही)" else "") + if (a.resume) " (पुरानी बातचीत)" else "")
                    if (r.live) DKState.setLink("जुड़ रहा है… ${c.label}")
                    openAttempt(r, a)
                }
                if (inFlight.isEmpty() && queue.isEmpty()) break
                delay(25)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { log("दौड़ की गलती: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            r.over = true
            if (!r.winner.isCompleted) inFlight.forEach { killAttempt(it, if (r.cancelled) "दौड़ रोकी" else "दौड़ खत्म") }
            if (!r.winner.isCompleted && !r.cancelled) log("दौड़ खत्म - कोई जोड़ी नहीं जुड़ी (${r.lastFail?.second?.take(80) ?: "-"}) • खुले connection: ${r.opened.get()}/${r.atts.size}")
            if (!r.winner.isCompleted) r.lastFail?.let { lastRaceFail = it; lastRaceFailAt = System.currentTimeMillis() }
        }
    }
    return r
}

internal fun GeminiLive.cancelRace(r: Race, why: String) {
    synchronized(raceLock) { r.cancelled = true; r.handOff = false }
    r.atts.forEach { if (!it.won) killAttempt(it, why) }
}

internal fun GeminiLive.killAttempt(a: Attempt, why: String) {
    a.dead = true
    a.w?.let { ourClose[it] = why; try { it.cancel() } catch (_: Exception) {} }
}

internal fun GeminiLive.attemptFailed(r: Race, a: Attempt, code: Int, reason: String, queue: MutableList<LiveRace.Combo>) {
    val now = System.currentTimeMillis(); val ms = now - a.t0
    r.lastFail = code to reason; r.lastFailCombo = a.c
    when (LiveRace.classify(code, reason)) {
        LiveRace.Fail.KEY -> if (LiveRace.quotaOnlyForCombo(code, reason)) {
            // limit/quota = सिर्फ़ यही (मॉडल × key) रुके; उसी key के बाकी मॉडल आज़माओ (पहले पूरी key 10 मिनट बंद)
            health.comboFail(a.c, now)
            log("दौड़: [${a.c.label}] $ms ms - इस मॉडल की limit (code=$code) - यह जोड़ी 2 मिनट नहीं, उसी key का अगला मॉडल तुरंत")
        } else {
            health.keyBad(a.c.keyIdx, now)
            queue.removeAll { it.keyIdx == a.c.keyIdx }
            log("दौड़: [${a.c.label}] $ms ms - key #${a.c.keyIdx + 1} नहीं चली (code=$code${if (LiveRace.isQuota(code, reason)) ", limit" else ""}) - 10 मिनट तक यह key नहीं, अगली तुरंत")
        }
        LiveRace.Fail.MODEL -> {
            for (k in 0 until r.keyCount) health.comboFail(LiveRace.Combo(a.c.model, k), now)
            queue.removeAll { it.model == a.c.model }
            log("दौड़: [${a.c.label}] $ms ms - यह मॉडल नहीं मिला/नहीं चला (code=$code) - 2 मिनट तक नहीं, अगला तुरंत")
        }
        LiveRace.Fail.CONFIG -> {
            if (r.config == null) { r.config = code to reason; r.configCombo = a.c }
            log("दौड़: [${a.c.label}] $ms ms - setup की बनावट नहीं ली (code=$code ${reason.take(80)}) - ठीक करके फिर")
        }
        LiveRace.Fail.NET -> if (code == 1011 && model1011.hit(a.c.model, now) || code == 1011 && LiveStay.known1011(a.c.model)) {
            // नवंबर 2026: जुड़ते समय भी इसी मॉडल पर बार-बार 1011 = Google की तरफ़ मॉडल की खराबी, नेट नहीं - पूरा मॉडल हटाओ
            for (k in 0 until r.keyCount) health.comboFail(LiveRace.Combo(a.c.model, k), now)
            markBroken1011(a.c.model)
            queue.removeAll { it.model == a.c.model }
            log("दौड़: [${a.c.label}] $ms ms - इस मॉडल पर बार-बार 1011 (Google की तरफ़ खराबी) - यह मॉडल अभी नहीं, अगला तुरंत")
        } else {
            health.comboFail(a.c, now)
            log("दौड़: [${a.c.label}] $ms ms - connection की गलती (code=$code ${reason.take(80)}) - यह जोड़ी 2 मिनट नहीं, अगली तुरंत")
        }
    }
    saveHealth()
}

internal fun GeminiLive.openAttempt(r: Race, a: Attempt) {
    val req = Request.Builder().url(URL).header("x-goog-api-key", a.key).build()
    a.w = http.newWebSocket(req, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            a.sentAt = System.currentTimeMillis()
            r.opened.incrementAndGet()
            if (a.dead) return
            if (!webSocket.tx(setupJson(a.c.model, a.text, a.resume))) a.closed.complete(0 to "setup नहीं भेज पाया")
        }
        override fun onMessage(webSocket: WebSocket, text: String) { onAttemptMsg(r, a, webSocket, text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { onAttemptMsg(r, a, webSocket, bytes.utf8()) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val ours = ourClose.remove(webSocket)
            if (a.won || !a.dead) {
                if (ours != null) log("हमने बंद किया ($ours) code=$code")
                else log("server ने बंद किया [${a.c.label}] code=$code reason=${reason.ifBlank { "-" }}")
            }
            a.closed.complete(code to reason)
            try { webSocket.close(1000, null) } catch (_: Exception) {}
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ourClose.remove(webSocket); a.closed.complete(code to reason) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val body = try { response?.body?.string()?.take(200) } catch (_: Exception) { null }
            val why = (t.javaClass.simpleName + ": " + (t.message ?: "")) + (body?.let { " | $it" } ?: "")
            ourClose.remove(webSocket)
            if (a.won || !a.dead) log("connection गलती [${a.c.label}]: http=${response?.code} $why")
            a.closed.complete((response?.code ?: 0) to why)
        }
    })
}

internal fun GeminiLive.onAttemptMsg(r: Race, a: Attempt, w: WebSocket, text: String) {
    if (a.won) { if (w === ws) onServer(text, a.setup); return }
    if (a.dead || !text.contains("setupComplete")) return
    val now = System.currentTimeMillis(); val ms = now - a.t0
    var live = false
    val first = synchronized(raceLock) {
        if (r.winner.isCompleted || r.cancelled) false else {
            a.won = true
            if (r.handOff) {
                live = true
                // जीतने वाला अब Live: ws/मॉडल/key नंबर इसी के (बाकी जोड़ियाँ नीचे बंद)
                ws = w; model = a.c.model; keyIdx = a.c.keyIdx; connText = a.text; setupSentAt = a.sentAt
                connResumed = a.resume && handle != null
                wire.reset()
            }
            r.won = a; r.winner.complete(a); true
        }
    }
    if (!first) { killAttempt(a, "दौड़ में दूसरा जीता"); return }
    health.ok(a.c, ms, now); saveHealth(); LiveRecover.Demote.ok(a.c.model)
    Cooldown.clear(LiveKeys.cooldownName(a.c.keyIdx, a.c.model))
    log("दौड़ जीती: [${a.c.label}] जुड़ने में $ms ms" + if (r.atts.size > 1) " (${r.atts.size} जोड़ियाँ आज़माईं)" else "")
    r.atts.forEach { if (it !== a) killAttempt(it, "दौड़: दूसरा जीता") }
    if (live) onServer(text, a.setup) else parkRaced(a, w)
}

/**
 * नवंबर 2026 (मालिक): goAway = server जल्द काटेगा. कटने का इंतज़ार नहीं: DK बोल रहा हो या चुप - अभी पीछे से
 * उसी बातचीत (handle) का नया connection तैयार (standby). नया तैयार होते ही उसी पर (0 ms); बीच में बात हुई (handle बदला)
 * तो ताज़ा handle से. पुराना जवाब पूरा होने के बाद ही पुराना बंद (नीचे loop का goAway). नियम: [LiveSpare].
 */
internal fun GeminiLive.onGoAway(timeLeft: String) {
    val left = LiveStay.timeLeftMs(timeLeft)
    log("goAway: ${timeLeft.ifBlank { "?" }} बाकी" + if (left >= 0) " (${left} ms)" else "")
    goAwayDeadline = System.currentTimeMillis() + LiveSpare.goAwayLeft(left)
    goAway = true
    prepareSpare("goAway", idle = !(turnActive || isPlaying() || toolJobs.isNotEmpty() || generating))
}

/** standby (दूसरा connection) उसी ताज़ा बातचीत से बना और ज़िंदा है? */
internal fun GeminiLive.spareReady(): Boolean = parked?.let {
    !it.closed.isCompleted && System.currentTimeMillis() - it.at < PARK_MS && it.resumedFrom != null && it.resumedFrom == handle
} == true

/**
 * नवंबर 2026 (मालिक): कटने से पहले ही दूसरा connection - goAway पर, या connection [LiveSpare.PREPARE_AT_MS] पुराना होते ही.
 * resume handle से बनता है = वही बातचीत, वही याद. लौटाता है: तैयारी शुरू हुई?
 */
internal fun GeminiLive.prepareSpare(why: String, idle: Boolean, force: Boolean = false): Boolean {
    val now = System.currentTimeMillis()
    val p = parked
    val alive = p != null && !p.closed.isCompleted && now - p.at < PARK_MS
    val fresh = alive && p!!.resumedFrom != null && p.resumedFrom == handle
    val stale = alive && !fresh
    // force (थामो और बदलो): पिछली तैयारी का 10s इंतज़ार नहीं - अभी के ताज़ा handle से (पुरानी दौड़ startRace में रुकती है)
    if (force) { if (stopped || handle == null || curKeys.isEmpty() || fresh) return fresh }
    else if (!LiveSpare.shouldPrepare(stopped, handle != null, curKeys.isNotEmpty(), fresh, stale, idle, now - spareTriedAt)) return false
    if (p != null) { parked = null; closeWs(p.ws, "standby पुराना (बातचीत आगे बढ़ी)") }
    val h = handle; val m = model; val k = keyIdx
    parkHandle = h; spareTriedAt = now; spareWanted = true
    log("$why: पीछे से उसी बातचीत का दूसरा connection पहले से तैयार कर रहा हूँ (बिना रुकावट बदलेगा)")
    return try { startRace(curKeys, listOf(m) + curModels.filter { it != m }, live = false, resumeId = LiveRace.Combo(m, k).id); true }
    catch (e: Exception) { spareWanted = false; log("$why: पीछे का connection शुरू नहीं हुआ: ${e.message}"); false }
}

/** दौड़ जीती पर run() इंतज़ार छोड़ चुका था - standby में रखो; अगली बार Live इसे तुरंत (0 ms) लेता है */
internal fun GeminiLive.parkRaced(a: Attempt, w: WebSocket) {
    // पहले से तैयार किया दूसरा connection (prepareSpare) पुरानी बातचीत से नहीं जुड़ा = नई बातचीत होती (याद टूटती) - मत रखो
    if (spareWanted && !a.resume) { spareWanted = false; closeWs(w, "standby: बातचीत वहीं से नहीं जुड़ी"); log("पीछे का connection नई बातचीत बनता - छोड़ा (अगली बार फिर)"); return }
    spareWanted = false
    parked?.let { old -> closeWs(old.ws, "standby बदला") }
    val p = Parked(w, a.closed, a.c.model, System.currentTimeMillis(), connSplit, a.text, connLiveVer, a.c.keyIdx,
        resumedFrom = if (a.resume) parkHandle else null)
    parked = p
    log("पीछे से Live तैयार [${a.c.label}] - standby में (DK चुप होते ही Live पर वापस)")
    svc.scope.launch {
        delay(PARK_MS)
        if (parked === p) { parked = null; closeWs(p.ws, "standby khatam") }
    }
}

/** v5.1: याद/पिछली बातें setup के बाद (setup छोटा रहे). turnComplete=false = DK इस पर जवाब नहीं बनाता */
internal fun GeminiLive.sendContext(w: WebSocket?) {
    if (w == null || connResumed) return
    synchronized(ctxSent) { if (!ctxSent.add(w)) return }
    val t = try { BrainManager(svc).liveMemoryContext() } catch (e: Exception) { log("याद नहीं बनी: ${e.message}"); "" }
    if (t.isBlank()) return
    val msg = JSONObject().put("clientContent", JSONObject()
        .put("turns", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", LiveRace.contextNote(t))))))
        .put("turnComplete", false)).toString()
    if (w.tx(msg)) log("याद/पिछली बातें जुड़ने के बाद भेजीं (${t.length} अक्षर) - setup छोटा रहा")
}

internal fun GeminiLive.connect(key: String, m: String, closed: CompletableDeferred<Pair<Int, String>>, setup: CompletableDeferred<Unit>) {
    ready = false
    val req = Request.Builder().url(URL).header("x-goog-api-key", key).build()
    ws = http.newWebSocket(req, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            setupSentAt = System.currentTimeMillis()
            wire.reset()
            if (!webSocket.tx(setupJson(m))) closed.complete(0 to "setup नहीं भेज पाया")
        }
        override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === ws) onServer(text, setup) }
        // Gemini JSON ko binary frame me bhi bhejta hai
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (webSocket === ws) onServer(bytes.utf8(), setup) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val ours = ourClose.remove(webSocket)
            if (ours != null) log("हमने बंद किया ($ours) code=$code")
            else log("server ने बंद किया code=$code reason=${reason.ifBlank { "-" }}")
            closed.complete(code to reason)
            try { webSocket.close(1000, null) } catch (_: Exception) {}
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ourClose.remove(webSocket); closed.complete(code to reason) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val body = try { response?.body?.string()?.take(200) } catch (_: Exception) { null }
            val why = (t.javaClass.simpleName + ": " + (t.message ?: "")) + (body?.let { " | $it" } ?: "")
            ourClose.remove(webSocket)
            log("connection गलती: http=${response?.code} $why")
            closed.complete((response?.code ?: 0) to why)
        }
    })
}
