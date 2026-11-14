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
import com.dk.assistant.GeminiLive.Companion.ASK_HINT
import com.dk.assistant.GeminiLive.Companion.SERVER_SILENCE_MS
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

// GeminiLive का हिस्सा: बोलना खत्म होने पर: जवाब न आए, अटके, तेज़ जवाब, बीच में टोकना, रुकी बातें.
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

/**
 * Phone ne pakda: user ~500ms se chup (hybrid VAD). Turant audioStreamEnd -> server apna chup-intezaar chhod kar
 * jawab banana shuru karta hai. Na chale to server ka apna VAD (500ms) fallback hai.
 */
internal fun GeminiLive.onLocalEndOfSpeech(now: Long) {
    val w = ws
    trace.speechEndAt = turnDet.lastVoiceAt.takeIf { it > 0 } ?: now
    trace.endpointAt = now
    trace.firstChunkAt = 0; trace.firstPlayAt = 0
    eosAt = trace.speechEndAt; firstAudioAt = 0
    trace.backlogBytes = w?.queueSize() ?: 0
    beginTimeline(trace.speechEndAt, now, "फ़ोन VAD")
    if (trace.backlogBytes == 0L) timeline.upload(now)
    // v3.39: एक बात पर एक ही "बात खत्म"; बहुत छोटी आवाज़ (खटखट) पर बिना caption शब्दों के नहीं - सर्वर का VAD संभाले
    val capNow = caption?.text().orEmpty()
    val words = BargeGate.realWords(capNow)
    // v3.46: वाक्य "और / तो / कि / ये बताओ…" पर रुका = आप आगे बोलेंगे. तब "बात खत्म" मत भेजो (पहले हर ठहराव पर
    // Gemini अधूरी बात का जवाब बनाने लगता, फिर आप आगे बोलते तो रद्द - कई वाक्यों पर जवाब बहुत देर से आता था)
    // v3.152 (एक key, caption धीमा): caption का text आपकी आख़िरी आवाज़ से पीछे छूटा = वह पुराना अधूरा हिस्सा है,
    // उस पर "वाक्य अधूरा" मानकर "बात खत्म" रोकना = हर बार जवाब देर से। तब रोको मत।
    val holdEnd = TurnEnd.continues(capNow) && TurnEnd.captionFresh(caption?.textAt ?: 0L, turnDet.lastVoiceAt)
    val endSent = !holdEnd && ready && w != null && endGate.onSpeechEnd(turnDet.lastSpeechMs, words) &&
        try { w.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) { false }
    caption?.endOfSpeech()
    // DK abhi jawab bana/bol raha hai = server sun chuka; warna 2.5s me kuch na aaye to "nahi suna".
    // dropOld = user ne purana jawab toka tha: woh "generating" PURANE jawab ka hai, is nayi baat ka nahi -
    // pehle yahan watchdog band ho jaata tha aur toka-ke-boli baat ko server na sune to DK chup reh jaata.
    if ((generating && !dropOld) || isPlaying()) replyWatch.onServerAnswered() else replyWatch.onEndOfSpeech(now, turnDet.lastSpeechMs)
    scheduleFast()
    log("फ़ोन: बोलना खत्म (${now - trace.speechEndAt} ms चुप) → " + (if (endSent) "audioStreamEnd भेजा" else if (holdEnd) "audioStreamEnd रोका (वाक्य अधूरा लगा - सर्वर ${SERVER_SILENCE_MS}ms चुप्पी पर तय करेगा)" else "audioStreamEnd नहीं (पहले भेजा जा चुका / बहुत छोटी आवाज़)") +
        if (trace.backlogBytes > 16 * 1024) " • नेटवर्क में ${trace.backlogBytes / 1024}KB अटका (upload धीमा)" else "")
    // sochne ka halka pulse - sannata na lage
    if (!isPlaying() && DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
}

/**
 * v3.9.1: user ne asli baat boli, phone ne audioStreamEnd bheja, par 2.5s me Gemini se koi nishaani nahi
 * (na ACTIVITY_START, na text, na awaaz) = server ne awaaz suni hi nahi. Pehle DK yahin chup ho jaata tha.
 * Ab: caption ne shabd likhe hain to wahi text Gemini ko seedha bhejo (jawab pakka aaye).
 * Shabd nahi = shor tha -> Listening + peeche ki awaaz wapas (pehle 40-80s dheemi atki rehti thi).
 */
internal fun GeminiLive.onNoReply() {
    if (!ready || muteTurn) return
    val now0 = System.currentTimeMillis()
    // v3.46: पहले यहाँ सीधा return = बचाव का इकलौता मौका खत्म और बात हमेशा के लिए खो जाती थी
    // (उसी पल फ़ोन का काम चल रहा हो / आप फिर बोलने लगे / DK बोल रहा हो). अब थोड़ी देर बाद फिर देखो.
    if ((generating && !dropOld) || isPlaying() || toolJobs.isNotEmpty() || turnDet.inSpeech) {
        if (!turnDet.inSpeech) replyWatch.defer(now0)
        return
    }
    if (!textRescue) {
        // नवंबर 2026: caption का text कभी Gemini को नहीं (पीछे का शोर / फ़ोन की आवाज़ भी caption बनती है; बीच का text = 1011)
        unheard++
        log("Gemini से ${LiveStay.NO_REPLY_MS / 1000}s में कुछ नहीं - caption text नहीं भेजा (बचाव-text बंद), सुन रहा हूँ (#$unheard)")
        // नवंबर 2026 (मालिक): स्क्रीन पर लिखी बात (caption में असली शब्द) का जवाब आना चाहिए - नया connection + वही आवाज़ (text नहीं)
        if (LiveRecover.unheardWorthSwap(caption?.text().orEmpty(), utt.speechMs())) unheardSwapAt = now0
        if (DKState.mode == Mode.THINKING) setMode(Mode.LISTENING)
        Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
        return
    }
    val t = caption?.text().orEmpty().trim()
    // caption के शब्द थोड़ी देर से आते हैं (उसका जुड़ाव हर 50s बदलता है) - एक-दो बार और रुको, फिर "शोर" मानो
    if (!BargeGate.realWords(t) && rescueWaits < 2 && replyWatch.defer(now0, 600)) { rescueWaits++; return }
    rescueWaits = 0
    unheard++
    if (!BargeGate.realWords(t)) {
        log("Gemini से ${LiveStay.NO_REPLY_MS / 1000}s में कुछ नहीं आया और caption में शब्द नहीं - शोर था, सुन रहा हूँ (#$unheard)")
        if (DKState.mode == Mode.THINKING) setMode(Mode.LISTENING)
        Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
        return
    }
    val now = System.currentTimeMillis()
    eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
    log("आवाज़ वाली बात की समय-रेखा: ${timeline.summary()}")
    timeline.begin(now, now, "caption text (नहीं सुना था)"); waitingLogged = false
    replyWatch.onRescued()               // ek baat ke liye ek hi baar (atka-nudge bhi nahi)
    val ok = try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) == true } catch (_: Exception) { false }
    if (ok) rescued++
    log("Gemini ने आवाज़ नहीं सुनी (${LiveStay.NO_REPLY_MS / 1000}s में कुछ नहीं) - caption का text सीधे भेजा: \"${t.take(60)}\"" + if (ok) "" else " (भेज नहीं पाया)")
    if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
}

/**
 * v3.29: Gemini ne baat SUNI (text aaya) par 4.5s tak jawab shuru nahi kiya (na awaaz, na tool).
 * Pehle: kuch nahi - 12s baad sirf "Listening", user ko jawab hi nahi milta tha ("सुनता है पर जवाब नहीं देता").
 * Ab: wahi baat (caption ka text) ek baar text me dobara - jawab pakka shuru ho.
 */
internal fun GeminiLive.onStalled() {
    if (!ready) return
    if (!textRescue) { log("Gemini ने सुना पर जवाब धीमा - वही बात text में दोबारा नहीं भेजी (बचाव-text बंद), इंतज़ार"); return }
    if ((generating && !dropOld) || isPlaying() || toolJobs.isNotEmpty() || turnDet.inSpeech) {
        if (!turnDet.inSpeech && !isPlaying() && !(generating && !dropOld)) replyWatch.deferAnswer(System.currentTimeMillis())
        return
    }
    val t = caption?.text().orEmpty().trim().ifBlank { synchronized(turnLock) { userText.toString().trim() } }
    if (!BargeGate.realWords(t)) return
    val now = System.currentTimeMillis()
    eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
    log("अटकी बात की समय-रेखा: ${timeline.summary()}")
    timeline.begin(now, now, "अटका - text में दोबारा"); waitingLogged = false
    val ok = try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) == true } catch (_: Exception) { false }
    if (ok) stalledN++
    log("Gemini ने सुना पर 4.5s में जवाब शुरू नहीं किया - वही बात text में दोबारा भेजी: \"${t.take(60)}\"" + if (ok) "" else " (भेज नहीं पाया)")
    if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
}

/**
 * v3.29: बात खत्म -> caption का text 650ms में स्थिर हो और सीधा काम हो (ऐप खोलो, टॉर्च, आवाज़, YouTube पर चलाओ…)
 * तो Gemini के सोचने/tool call का इंतज़ार किए बिना तुरंत करो. Gemini बाद में वही phone_task माँगे तो दोबारा नहीं (Memo).
 * (अधूरे text पर नहीं: "टॉर्च" सुनकर जला दे और पूरी बात "टॉर्च बंद करो" हो - इसलिए दो बार देखकर ही)
 */
internal fun GeminiLive.scheduleFast() {
    if (ComputerSession.ask != null) return
    fastJob?.cancel()
    fastJob = svc.scope.launch {
        fun cur() = caption?.text().orEmpty().trim().ifBlank { if (!userDone) turnUser.trim() else "" }
        // v3.41: 300+350 ms -> ghanti me 150 ms; baaki 200 ms (+250 sirf jab caption ne text pakka nahi kiya)
        val ringing = svc.callRinging()
        delay(if (ringing) 150 else 200); val a = cur()
        val b = if (ringing || (a.isNotBlank() && a == capFinal)) a else { delay(250); cur() }
        if (b.isBlank() || a != b || turnDet.inSpeech || isPlaying() || stopped) return@launch
        // v3.40: घंटी बज रही है -> "हाँ / उठाओ / स्पीकर पर / काट दो" फ़ोन तुरंत करे (Gemini के tool का इंतज़ार नहीं)
        if (svc.callRinging()) {
            val act = CallReply.parse(b)
            if (act != null) { callTool(act, "फ़ोन ने सुना: \"${b.take(30)}\""); return@launch }
        }
        // नवंबर 2026: "फोन लॉक / अनलॉक करो" = अलग, न रुकने वाला काम (नीचे runLockNow) - 15s वाला दोहराव-रोक
        // यहाँ नहीं (पहले वही बात दोबारा बोलने पर कुछ नहीं होता था); दो बार चलने से Memo की छोटी खिड़की रोकती है
        PhoneLockVoice.instant(b, Prefs.wakeWords(svc), needName = dkOnlyNow)?.let { act ->
            runLockNow(PhoneLockVoice.quick(act), b, "बोलना खत्म"); return@launch
        }
        // v3.46: वही बात दोबारा router में नहीं (हर ठहराव/शोर पर वही caption दोबारा जाता था)
        if (!routeDedupe.fresh(b, System.currentTimeMillis())) return@launch
        val tc = System.nanoTime()
        val q = LiveFast.pick(b, Prefs.wakeWords(svc), svc.currentPackage(), fast = true)
        val classMs = (System.nanoTime() - tc) / 1_000_000
        // "स्क्रीन शेयर करो / बंद करो" Live में = असली स्क्रीन शेयर (Gemini देखे), पुराना screenshot switch नहीं
        if (q != null && q.action.optString("type") == "screen_view") {
            val r = LiveVision.setScreen(svc, q.action.optBoolean("on", true))
            log("तुरंत: स्क्रीन शेयर ${if (q.action.optBoolean("on", true)) "चालू" else "बंद"} -> ${r.take(60)}"); return@launch
        }
        if (q == null) { log("रूटर: \"${b.take(30)}\" फ़ोन पर सीधा नहीं - Gemini संभालेगा (वर्गीकरण $classMs ms)"); return@launch }
        if (PhoneLockVoice.isLockType(q.action.optString("type"))) { runLockNow(q, b, "रूटर"); return@launch }
        // नवंबर 2026 (DKI-87912D): आधी बात पर बड़ा काम नहीं - थोड़ा और रुककर देखो आप आगे तो नहीं बोल रहे
        val settle = LiveFast.settleMs(q.action.optString("type"))
        if (settle > 0) {
            delay(settle)
            if (cur() != b || turnDet.inSpeech || stopped) {
                log("तुरंत काम रुका (${q.action.optString("type")}) - आप आगे बोल रहे थे, पूरी बात पर फिर से"); routeDedupe.reset(); return@launch
            }
        }
        if (!fastMemo.begin(q, System.currentTimeMillis())) return@launch
        val t0 = System.currentTimeMillis()
        val r = try { NativeActionEngine.exec(svc, q, "Live बोलना खत्म") } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
        if (!isActive || LiveFast.cancelled(r)) {
            fastMemo.cancel(q); routeDedupe.reset()
            log("तुरंत काम रद्द (${q.action.optString("type")}) - आप आगे बोले, पूरी बात पर फिर से (${System.currentTimeMillis() - t0} ms)")
            return@launch
        }
        fastMemo.finish(q, r); fastN++
        if (LiveFast.ok(q.action.optString("type"), r) && !r.startsWith("VIDEO_OPENED")) lastFastOkAt = System.currentTimeMillis()
        log("तुरंत काम (Gemini से पहले) \"${b.take(40)}\" -> ${q.action.optString("type")} = ${r.take(60)} (वर्गीकरण $classMs ms, काम+जाँच ${System.currentTimeMillis() - t0} ms)")
    }
}

/**
 * PAKKA: user sach me bol raha hai (caption me asli shabd / caption band ho to lagatar awaaz).
 * Ab DK chup + purane tukde phenko + user ki awaaz (shak ke samay se) Gemini ko -> server generation rokta hai.
 */
internal fun GeminiLive.confirmBarge(why: String) {
    realBarges++
    log("यूज़र सच में बोल रहा ($why) - DK चुप, सुन रहा हूँ")
    // sendLock: shak ke samay ki (purani) awaaz pehle, mic ka naya tukda uske baad (ulti-pulti awaaz = server na samjhe)
    synchronized(sendLock) {
        val buf = synchronized(bargeBuf) { bargeBuf.toList().also { bargeBuf.clear() } }
        bargeIn(why)
        track?.let { try { it.setVolume(1f) } catch (_: Exception) {} }
        turnDet.forceSpeech(System.currentTimeMillis())     // gate khula: user ki baaki baat bhi jaye
        replyWatch.onSpeechStart()                         // nayi baat: iska apna "suna / jawab" hisaab
        buf.forEach { sendAudio(it) }
    }
}

/** DK ने अभी तक जो बोला/बोल रहा है (गूँज की जाँच के लिए) - बारी ख़त्म हो चुकी हो तब भी पिछला जवाब */
internal fun GeminiLive.dkHeardNow(): String = synchronized(turnLock) { replyText.toString() + " " + (pendingReply ?: "") } + " " + lastDkLine

/**
 * v3.5+: HOLD ख़त्म - यूज़र ने अपना अगला सवाल पूरा बोल दिया. गूँज/शोर नहीं तो कतार में; जवाब पूरा होते ही [flushAsks].
 * Gemini को अभी कुछ नहीं गया था, इसलिए DK का चलता जवाब पूरा बजता है.
 */
internal fun GeminiLive.finishHold(now: Long) {
    val t = (caption?.text().orEmpty().ifBlank { holdCap }).trim().trimEnd('…').trim()
    caption?.discard()
    synchronized(bargeBuf) { bargeBuf.clear() }
    synchronized(sendLock) { bargeGate.reset() }
    barging = false; bargeDet.reset(); holdCap = ""; holdCapAt = 0L; holdLoudAt = 0L; holdFinal = false
    track?.let { try { it.setVolume(1f) } catch (_: Exception) {} }
    when {
        !BargeGate.realWords(t) || BargeGate.isEcho(t, dkHeardNow()) || BargeGate.isRepeat(t, turnUser) -> {
            log("बीच की आवाज़ (\"${t.take(30)}\") DK की गूँज / पुरानी बात थी - छोड़ी"); DKState.hint("")
        }
        LiveStop.isStop(t) -> { askQueue.clear(); DKState.hint(""); bargeIn("रुको: ${t.take(30)}") }
        askQueue.add(t) -> {
            askAt = now
            log("बीच में पूछा सवाल याद रखा (\"${t.take(40)}\") - जवाब पूरा होते ही बताऊँगा (कतार: ${askQueue.size()})")
            DKState.hint(ASK_HINT)
        }
    }
}

/** जवाब पूरा हुआ - बीच में पूछा सवाल अब Gemini को (यूज़र की नई बारी की तरह, "आप:" लाइन भी) */
internal fun GeminiLive.flushAsks(now: Long) {
    val raw = askQueue.drain() ?: return
    if (!textRescue) { log("बीच में पकड़ी बात (caption) text में नहीं भेजी (बचाव-text बंद): ${raw.take(40)}"); return }
    // v4.1: "सिर्फ़ DK बोलने पर सुनो" - बीच में पूछी बात में DK से शुरू होने वाले वाक्य ही (DK हटाकर)
    val t = if (!dkOnlyNow) raw else DkOnly.filter(raw)?.takeIf { it.isNotBlank() } ?: run {
        log("बीच की बात DK से शुरू नहीं हुई - छोड़ी (\"${raw.take(40)}\")"); return
    }
    try {
        startTurn(); turnUser = t; userDone = false
        caption?.discard()
        DKState.hint(""); DKState.user("$t…")
        setMode(Mode.THINKING)
        eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
        beginTimeline(now, now, "बीच में पूछा सवाल (जवाब के बाद)")
        ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
        lastAct = now
        log("बीच में पूछा सवाल अब भेजा: ${t.take(60)}")
    } catch (_: Exception) {}
}

/**
 * नवंबर 2026 (मालिक: "लाइव बातचीत में 'फोन लॉक / अनलॉक करो' बोलूँ तो तुरंत हो"): फ़ोन लॉक / अनलॉक अलग job में।
 * पहले यह fastJob के अंदर चलता था - अनलॉक के बीच (PIN डलते समय) कोई भी नया "बोलना खत्म" (DK की अपनी
 * आवाज़ / शोर) fastJob रद्द कर देता था: आधा PIN डला, फिर Gemini के phone_task ने दोबारा पूरा PIN डाला = गलत PIN,
 * और दो बार गलत पर आवाज़ से अनलॉक ही रुक जाता था। अब: एक बार शुरू = पूरा होता है, एक समय में एक ही (PhoneUnlocker.gate)।
 */
internal fun GeminiLive.runLockNow(q: QuickCommands.Quick, heard: String, from: String) {
    if (stopped || !fastMemo.begin(q, System.currentTimeMillis())) return
    val type = q.action.optString("type")
    // 14 नवंबर 2026 (मालिक: "अनलॉक करो बोलूँ तो 'सर, मैं अनलॉक नहीं कर सकूँगा' न बोले"): इस बात का Gemini वाला जवाब बंद -
    // काम फ़ोन करेगा, फिर Gemini ठीक वही एक लाइन बोलेगा जो फ़ोन ने सच में किया (PhoneLockVoice.liveSay)
    muteTurn = true; muteAt = System.currentTimeMillis()
    if (isPlaying()) flushPlayback()
    svc.scope.launch {
        val t0 = System.currentTimeMillis()
        val r = try { svc.perform(q.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
        fastMemo.finish(q, r); fastN++
        if (LiveFast.ok(type, r)) lastFastOkAt = System.currentTimeMillis()
        log("तुरंत फ़ोन $type ($from, Gemini से पहले) \"${heard.take(40)}\" -> ${r.take(60)} (${System.currentTimeMillis() - t0} ms)")
        // बंद किया हुआ जवाब पूरा होने दो (turnComplete पर muteTurn अपने-आप हटता है); Gemini ने कुछ शुरू ही न किया हो तो खुद हटाओ
        withTimeoutOrNull(3_000) { while (muteTurn && (generating || System.currentTimeMillis() - muteAt < 1_200)) delay(80) }
        if (muteTurn && !generating) muteTurn = false
        if (!stopped) visionNote(PhoneLockVoice.liveNote(type, r), evidence = true)
    }
}
