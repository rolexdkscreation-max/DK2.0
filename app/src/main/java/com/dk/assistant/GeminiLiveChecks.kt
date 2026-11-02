package com.dk.assistant

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.dk.assistant.GeminiLive.Companion.AGENT_TOOL_RECENT_MS
import com.dk.assistant.GeminiLive.Companion.CHUNK_MS
import com.dk.assistant.GeminiLive.Companion.IN_RATE
import com.dk.assistant.GeminiLive.Companion.LEAK_DEDUP_MS
import com.dk.assistant.GeminiLive.Companion.LEAK_ID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

// GeminiLive का हिस्सा: वादा/एजेंट जाँच, गलती से text में आया tool call, आवाज़ भेजना, idle.
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

/**
 * v3.75: बारी खत्म - Gemini ने सिर्फ़ "सर्च करके बताता हूँ / एक मिनट / कर रहा हूँ" कहा और कोई tool नहीं बुलाया?
 * तो चुप न रहो: सवाल था -> फ़ोन खुद सर्च करे और नतीजा Gemini को दे (वह बोले); काम था -> Gemini से phone_task करवाओ.
 */
internal fun GeminiLive.promiseCheck() {
    val now = System.currentTimeMillis()
    val (said, heard) = synchronized(turnLock) { replyText.toString().trim() to userText.toString().trim() }
    val user = (if (!userDone) turnUser.trim() else "").ifBlank { heard }.ifBlank { caption?.text().orEmpty().trim() }
    val kind = LivePromise.kind(said, user)
    if (kind == LivePromise.Kind.NONE || user.length < 2) return
    val sources = synchronized(turnLock) { turnSources }
    if (actEvidence || toolJobs.isNotEmpty() || sources.isNotEmpty() || fastJob?.isActive == true ||
        now - lastFastOkAt < DoneGate.FAST_EVIDENCE_MS || now - promiseFixAt < 15_000 || promiseJob?.isActive == true) return
    promiseFixAt = now
    log("वादा-जाँच: Gemini ने \"${said.take(50)}\" कहा पर कोई tool नहीं - ${if (kind == LivePromise.Kind.SEARCH) "फ़ोन खुद सर्च कर रहा है" else "phone_task करवा रहा हूँ"}")
    if (kind == LivePromise.Kind.ACTION) {
        // v3.6 (owner: "सुना, लिखा भी दिखा, पर काम नहीं किया"): पहले सिर्फ़ Gemini को याद दिलाते थे - वह फिर भी
        // अक्सर tool नहीं बुलाता था. अब फ़ोन खुद वही काम करता है, फिर Gemini को असली नतीजा (वह सच बोले).
        promiseJob = svc.scope.launch {
            DKState.hint(LivePromise.hintFor("phone_task", user))
            setMode(Mode.WORKING)
            val r = try { phoneTask(user) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
            DKState.hint("")
            log("वादा-जाँच: फ़ोन ने खुद काम किया - ${r.take(60)}")
            visionNote(LivePromise.actionDoneNote(user, r), evidence = LivePromise.actionOk(r))
        }
        return
    }
    promiseJob = svc.scope.launch {
        DKState.hint(LivePromise.hintFor("web_search", user))
        setMode(Mode.THINKING)
        val brain = BrainManager(svc).also { it.fromLive = true }
        var why = SearchGuard.Fail.TIMEOUT
        var r = try { withTimeoutOrNull(LiveSearch.totalTimeoutMs(Prefs.searchOwnKey(svc).isNotBlank()) + 3_000 + chainExtraMs()) { brain.search(user, deep = false) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { why = brain.lastSearchFail; log("वादा-जाँच सर्च fail: ${e.message}"); null }
        // v3.90: यहाँ भी बचाव वाली खोज, और fail हो तो असली वजह यूज़र को
        if (r == null || r.text.isBlank()) { val fb = fallbackSearch(user, why, brain); r = fb.first; why = fb.second }
        DKState.hint("")
        if (r == null || r.text.isBlank()) { visionNote(LivePromise.searchFailNote(user, why), evidence = false); return@launch }
        if (r.sources.isNotEmpty()) toolSources = Grounding.merge(toolSources, r.sources)
        visionNote(LivePromise.searchNote(user, r.text), evidence = true)
    }
}

/**
 * v3.5+: बारी ख़त्म - यूज़र ने किसी एजेंट (कहानी / कोडिंग / DK कंप्यूटर / वेबसाइट) को काम कहा, DK ने "हाँ, कर रही हूँ /
 * हो गया" कहा, पर उसका tool नहीं बुलाया? तो फ़ोन खुद वही tool चलाए - एजेंट की स्क्रीन खुले और काम वहाँ दिखे,
 * फिर Gemini को असली नतीजा (वह सच बोले). true = यहीं संभाल लिया (promiseCheck नहीं).
 */
internal fun GeminiLive.agentCheck(): Boolean {
    val now = System.currentTimeMillis()
    val (said, heard) = synchronized(turnLock) { replyText.toString().trim() to userText.toString().trim() }
    val user = (if (!userDone) turnUser.trim() else "").ifBlank { heard }.ifBlank { caption?.text().orEmpty().trim() }
    val recent = now - agentToolAt < AGENT_TOOL_RECENT_MS || toolJobs.isNotEmpty()
    val agent = LiveAgentRoute.missed(user, said, recent) ?: return false
    if (now - agentFixAt < 15_000) return false
    agentFixAt = now; agentToolAt = now
    log("एजेंट-जाँच: Gemini ने \"${said.take(50)}\" कहा पर ${agent.tool} नहीं बुलाया - फ़ोन खुद ${agent.title} को काम दे रहा है")
    DKState.hint("${agent.title} को काम दे रही हूँ…")
    svc.scope.launch {
        val a = JSONObject(); LiveAgentRoute.args(agent, user).forEach { (k, v) -> a.put(k, v) }
        val r = try { agentTool(agent.tool, a) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("एजेंट-जाँच: ${agent.tool} fail: ${e.message}"); "Tool failed (${e.javaClass.simpleName}) - tell the user it could not be done."
        }
        log("एजेंट-जाँच: ${agent.tool} -> ${r.take(80)}")
        DKState.hint("")
        if (DoneGate.notDoneYet(r)) actPending = true
        visionNote(LiveAgentRoute.note(agent, r), evidence = true)
    }
    return true
}

internal fun GeminiLive.newLeakFilter() = LeakedCall.Filter(onOpen = { onLeakOpen() }, onCall = { runLeaked(it) })

/** v4.1: जवाब में लिखी हुई tool call शुरू होती दिखी - Live की आवाज़ तुरंत बंद (वह "phone task…" बोलने वाला है) */
internal fun GeminiLive.onLeakOpen() {
    if (leakMute) return
    leakMute = true
    flushPlayback()
    log("Live ने tool call बुलाने की जगह लिख दी - छिपाई, आवाज़ रोकी, फ़ोन खुद काम करेगा")
}

/** v4.1: लिखी हुई call = असली tool की तरह उसी पल चलाओ (वही रास्ता जो सच्चे toolCall का है) */
internal fun GeminiLive.runLeaked(c: LeakedCall.Call) {
    val now = System.currentTimeMillis()
    val key = c.name + "|" + c.args.toString()
    if (key == lastLeakKey && now - lastLeakAt < LEAK_DEDUP_MS) { log("लिखी हुई ${c.name} - अभी-अभी यही चलाई, दोबारा नहीं"); return }
    lastLeakKey = key; lastLeakAt = now
    val args = JSONObject(); c.args.forEach { (k, v) -> args.put(k, v) }
    log("लिखी हुई call पकड़ी: ${c.name} - सीधे चला रहे हैं")
    lastToolAt = now
    runTools(JSONArray().put(JSONObject().put("id", LEAK_ID + now).put("name", c.name).put("args", args)))
}

internal fun GeminiLive.leakNote(name: String, result: String): String =
    "SYSTEM NOTE: you wrote the function call for $name as text instead of calling it, so the phone ran it itself. " +
        "Real result: \"" + result.take(800) + "\". Tell the user this real result in ONE short natural sentence in their language " +
        "(e.g. \"हो गया सर\") - never read out function names, braces, arguments or <ctrl> tokens. Do NOT call $name again for this request."

/** v3.80: tool चलते समय आवाज़ खत्म = "सोच/खोज रहा है" दिखे, सुन रहा नहीं (वरना लगता बात छूट गई) */
internal fun GeminiLive.idleMode() {
    if (DKState.mode == Mode.WORKING) return
    setMode(if (toolJobs.isNotEmpty()) Mode.THINKING else Mode.LISTENING)
}

/** sendLock ke andar hi bulao (setupComplete / garam connection) */
internal fun GeminiLive.flushPending() = synchronized(sendLock) {
    val list = synchronized(pending) { pending.toList().also { pending.clear() } }
    list.forEach { sendAudio(it) }
    if (list.isNotEmpty()) log("जुड़ते समय की ${list.size * CHUNK_MS} ms आवाज़ भेजी")
}

/**
 * silent=true: khaamoshi (shoonya) ka tukda - SilenceStreamGate tay karta hai bhejna hai ya nahi.
 * sendLock: teen thread yahan bhejte hain - ek hi kram me jaye.
 */
internal fun GeminiLive.sendAudio(pcm: ByteArray, silent: Boolean = false): Unit = synchronized(sendLock) {
    if ((micMuted || DKState.micMuted) && !silent) { sendAudio(ByteArray(pcm.size), true); return@synchronized }
    var end = false
    if (silent) {
        when (liveGate.onSilence()) {
            SilenceStreamGate.Action.SKIP -> { silenceSkipped++; return@synchronized }
            SilenceStreamGate.Action.SEND_THEN_END -> end = true
            SilenceStreamGate.Action.SEND -> {}
        }
    } else { liveGate.onReal(); endGate.onReal() }
    val w = ws
    if (w == null || !ready) {
        synchronized(pending) { pending.addLast(pcm); while (pending.size > 3000 / CHUNK_MS) { pending.removeFirst(); dropped++ } }
        return@synchronized
    }
    // network dheema: line me atka data. Pehle khaamoshi chhodo (32KB), asli awaaz sirf bahut atakne par (256KB) -
    // OkHttp ki line se purana nahi hata sakte, isliye pehle asli baat ke naye tukde hi gir jaate the.
    if (w.queueSize() > (if (silent) 32 * 1024 else 256 * 1024)) { dropped++; return@synchronized }
    val msg = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=$IN_RATE\",\"data\":\"" +
        Base64.encodeToString(pcm, Base64.NO_WRAP) + "\"}}}"
    if (w.tx(msg)) { sent++; sentBytes += pcm.size; utt.add(pcm, !silent, System.currentTimeMillis()) } else dropped++
    // 1s khaamoshi bheji -> stream band (docs: audioStreamEnd), ab asli awaaz aane tak upload nahi
    if (end && endGate.onSilenceEnd()) try { w.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}
}

/** Caption socket: asli awaaz hamesha; khaamoshi 1s tak, phir audioStreamEnd aur band */
internal fun GeminiLive.capSend(pcm: ByteArray, silent: Boolean = false) {
    val c = caption ?: return
    if (DKState.micMuted && !silent) return     // v3.59: म्यूट में लिखने वाले को भी आवाज़ नहीं
    if (!silent) { capGate.onReal(); c.send(pcm); return }
    when (capGate.onSilence()) {
        SilenceStreamGate.Action.SEND -> c.send(pcm, real = false)
        SilenceStreamGate.Action.SEND_THEN_END -> { c.send(pcm, real = false); c.endOfSpeech() }
        SilenceStreamGate.Action.SKIP -> {}
    }
}
