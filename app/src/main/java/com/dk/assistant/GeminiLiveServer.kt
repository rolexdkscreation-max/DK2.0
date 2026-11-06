package com.dk.assistant

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import okhttp3.WebSocket
import org.json.JSONObject

// GeminiLive का हिस्सा: Gemini server से आए हर message को संभालना (setupComplete, आवाज़, caption, tool call, interrupted).
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

/**
 * OkHttp ke listener se koi exception bahar gaya to OkHttp poora WebSocket fail kar deta hai (onFailure) =
 * chalta jawab beech me kho jata aur reconnect. Ek sandesh ki gadbad se session mat todo - log karo, aage chalo.
 */
internal fun GeminiLive.onServer(msg: String, setup: CompletableDeferred<Unit>) {
    try { handleServer(msg, setup) } catch (e: Exception) {
        handlerErrors++
        log("server संदेश संभालने में गलती #$handlerErrors (${e.javaClass.simpleName}: ${e.message?.take(80)}) - connection चालू रखा")
    }
}

internal fun GeminiLive.handleServer(msg: String, setup: CompletableDeferred<Unit>) {
    val o = try { JSONObject(msg) } catch (e: Exception) { log("JSON नहीं समझा (${msg.length} bytes)"); return }
    if (o.has("setupComplete")) {
        if (setupSentAt > 0) { trace.rttMs = System.currentTimeMillis() - setupSentAt; log("setup का जवाब ${trace.rttMs} ms (network RTT + server)"); NetSpeed.onSetup(trace.rttMs) }
        // ready + purani (judte samay ki) awaaz ek saath, lock me: mic ka naya tukda purane se pehle na nikle
        // v5.1: पहले याद (छोटे setup के बाद), फिर जुड़ते समय की आवाज़ - फ़ोन ने जो 3s तक जमा की
        synchronized(sendLock) { sendContext(ws); ready = true; flushPending(); sendReplay() }
        setup.complete(Unit)
        if (firstText != null) openerText = null; sendFirstText(); sendResend(); sendOpener()
        // v3.7: पिछला जवाब सिर्फ़ लिखा गया था (आवाज़ नहीं बनी) - अब Live की अपनी आवाज़ में दोबारा
        if (!connSplit && silentResay.isNotBlank()) { val t = silentResay; silentResay = ""; visionNote(SilentVoice.resayNote(t), evidence = false) }
        return
    }
    lastAct = System.currentTimeMillis()
    o.optJSONObject("sessionResumptionUpdate")?.let {
        if (it.optBoolean("resumable") && it.optString("newHandle").isNotBlank()) handle = it.optString("newHandle")
        if (it.optBoolean("resumable") && it.optString("newHandle").isNotBlank()) handleAt = System.currentTimeMillis()   // नवंबर 2026: handle कब ताज़ा हुआ (LiveSpare.handleFresh)
    }
    o.optJSONObject("goAway")?.let { onGoAway(it.optString("timeLeft")) }
    o.optJSONObject("voiceActivity")?.optString("type")?.let { t ->
        lastAnyServerAt = System.currentTimeMillis()      // नवंबर 2026: server ने सुना = session ज़िंदा (अटका नहीं)
        if (t == "ACTIVITY_START" || t == "ACTIVITY_END") replyWatch.onServerHeard()
        if (t == "ACTIVITY_START") {
            // server ne user ki awaaz pakdi: DK bol raha ho to turant chup (bache tukde bhi nahi), warna Listening
            if (isPlaying()) bargeIn("server ACTIVITY_START") else setMode(Mode.LISTENING)
        }
        if (t == "ACTIVITY_END") {
            val now = System.currentTimeMillis()
            // phone ne is baari "khatam" nahi pakda (dheemi awaaz) -> server ka pakadna hi endpoint
            if (trace.endpointAt == 0L || now - trace.endpointAt > 5000) {
                trace.reset(); trace.endpointAt = now; trace.speechEndAt = now
                beginTimeline(now, now, "server VAD")
            }
            if (eosAt == 0L || now - eosAt > 3000) eosAt = now
            firstAudioAt = 0; if (!isPlaying() && !muteTurn) setMode(Mode.THINKING)
        }
    }
    o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let {
        lastServerAt = System.currentTimeMillis(); lastAnyServerAt = lastServerAt; utt.clear(); onRecovered(); toolCallAt = lastServerAt
        // v3.66: किसी और की आवाज़ पर बना काम - कुछ मत करो (फ़ोन पर कोई काम नहीं), Gemini को बता दो
        if (muteTurn) { ignoreTools(it); return@let }
        // v3.67: tool से पहले बोला "कर दिया" = झूठ (काम अभी शुरू हुआ) - वह हिस्सा नहीं बजता; "ठीक है बॉस" बजता है
        // v3.90: काम से पहले की बातें ("ठीक है, खोल रहा हूँ…" / इधर-उधर की बात) नहीं - पहले काम, फिर नतीजे वाली बात
        if (actHold) {
            val cmd = !actAfterTool && actCommand
            // v3.7 (मालिक): हुक्म = काम से पहले कुछ नहीं (ठीक है भी नहीं) - काम के बाद "हो गया सर"
            val v = if (cmd) TaskFirst.beforeToolCommand(heldActText())
                    else DoneGate.beforeTool(heldActText())
            // v3.90: हुक्म = छोटा "ठीक है, कर रहा हूँ" बजे, उससे ज़्यादा बात हटे
            if (v == DoneGate.Verdict.DROP) dropAct(if (cmd) "काम से पहले की लंबी बात - पहले काम" else "tool से पहले") else releaseAct(if (cmd) "छोटा ठीक है" else "tool आया")
        }
        actEvidence = true
        timeline.event(lastServerAt); replyWatch.onServerAnswered(); lastToolAt = lastServerAt; runTools(it)
    }
    o.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { a ->
        for (i in 0 until a.length()) toolJobs.remove(a.optString(i))?.cancel()
    }
    val sc = o.optJSONObject("serverContent") ?: return
    lastServerAt = System.currentTimeMillis(); lastAnyServerAt = lastServerAt; onRecovered()
    // dropOld = user ne toka, yeh PURANE jawab ke bache tukde hain (phenke jaate hain) - inhe nayi baat ka
    // "jawab aaya" mat gino, warna nayi baat ka watchdog band ho jaata aur server na sune to DK chup rehta
    val oldReply = dropOld
    if (!oldReply || sc.has("inputTranscription")) timeline.event(lastServerAt)
    if (sc.has("inputTranscription")) replyWatch.onServerHeard()
    // docs: model jawab nahi bana raha kyunki use lagta hai user aage bolega (diagnostics - sirf log)
    if (sc.optBoolean("waitingForInput") && !waitingLogged) { waitingLogged = true; log("server: waitingForInput (मॉडल मान रहा है user आगे बोलेगा) • ${timeline.summary()}") }
    if (sc.optBoolean("generationComplete")) timeline.genDone(System.currentTimeMillis())
    if (!oldReply && (sc.has("modelTurn") || sc.has("outputTranscription"))) { replyWatch.onServerAnswered(); utt.clear() }
    sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() && !muteTurn }?.let { t ->
        if (!turnActive) startTurn()
        Ducker.duck(svc, "Live: user बोल रहा")
        suspectAt = 0L                     // user ki baat ka text aaya = rokna sahi tha (goonj nahi)
        val u = synchronized(turnLock) { userText.append(t); userText.toString().trim() }
        Lang.noteUser(u)                   // DK AI: "Auto" भाषा - यूज़र की भाषा याद (तय लाइनें उसी में)
        // phone ne "bolna khatam" pakda ho to deri wahin se ginti (der se aaye text se nahi)
        if (trace.speechEndAt == 0L || System.currentTimeMillis() - trace.speechEndAt > 5000) eosAt = System.currentTimeMillis()
        // caption chal raha hai to woh pehle hi turant likh chuka - yeh der se aaya text screen par mat daalo
        if (!captionOn()) DKState.user("$u…")
        checkMicOff(u)
    }
    // kuch Live model khud bhi bolte-bolte (interim) text dete hain - caption na ho to wahi dikhao
    sc.optJSONObject("interimInputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
        Ducker.duck(svc, "Live: user बोल रहा")
        if (!captionOn()) {
            val u = synchronized(turnLock) { userText.toString().trim() }
            DKState.user((if (turnActive) "$u $t" else t).trim() + "…")
        }
    }
    sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
        // user ne toka hai: purane jawab ke bache tukde - mat bajao (server "interrupted" bhejega)
        if (dropOld) { droppedOld++; return@let }
        // v3.66: किसी और की आवाज़ का जवाब - बजाओ मत
        if (muteTurn) { mutedParts++; generating = true; return@let }
        if (!turnActive) startTurn()
        generating = true
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            // v3.68: TEXT जवाब (बँटवारा) - जैसे-जैसे आए, आवाज़ इंजन को (पहला वाक्यांश बनते ही बोलना शुरू)
            val tx = if (p.optBoolean("thought")) "" else p.optString("text", "")
            if (tx.isNotEmpty() && connSplit) {
                replyPartArrived("पहला text")
                val noLeak = leakFilter.push(tx)               // v4.1: "phone_task{…}" न दिखे, न बोला जाए - काम सीधे
                val clean = if (noLeak.isEmpty()) "" else tagFilter?.push(noLeak) ?: noLeak   // v3.71: [खुश] जैसा टैग न बोला जाए, न दिखे
                if (clean.isNotEmpty()) synchronized(turnLock) { replyText.append(clean) }
                splitHoldCheck(); pumpSplit()
                continue
            }
            val inline = p.optJSONObject("inlineData") ?: continue
            val mime = inline.optString("mimeType")
            if (!mime.startsWith("audio/pcm")) { log("अनजान output: $mime"); continue }
            val pcm = try { Base64.decode(inline.optString("data"), Base64.DEFAULT) } catch (e: Exception) { log("audio decode गलती"); continue }
            if (pcm.isEmpty()) continue
            recvAudio += pcm.size
            replyPartArrived("पहला ऑडियो चंक")
            timeline.audio(System.currentTimeMillis())
            // v3.68: transcript वाला बँटवारा - Live की अपनी आवाज़ नहीं बजती. नवंबर 2026: पर रखी जाती है (SplitRescue)
            if (connSplit && keepSplitAudio(pcm)) continue
            if (leakMute) continue               // v4.1: "phone task request…" जैसी आवाज़ नहीं - फ़ोन चुपचाप काम कर रहा है
            if (actHold) { holdAct(pcm); continue }
            turnRecvBytes += pcm.size
            playOut(pcm)
        }
    }
    sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() && !dropOld && !muteTurn }?.let { t ->
        if (!turnActive) startTurn()
        generating = true
        // screen par abhi nahi - revealTick() awaaz ke saath-saath dikhata hai
        val tc = leakFilter.push(t)              // v4.1: लिखी हुई tool call हटाओ (और उसी पल असली काम)
        val soFar = synchronized(turnLock) { replyText.append(tc); if (replyText.length < 240) replyText.toString() else null }
        // v3.71: सीधी आवाज़ में टैग नहीं (बोल दिया जाता) - शुरू के शब्दों से अवतार का भाव
        soFar?.let { com.dk.assistant.avatar.EmotionGuess.of(it) }?.takeIf { it != com.dk.assistant.avatar.Mood.NORMAL }
            ?.let { com.dk.assistant.avatar.MoodState.set(it) }
        // v3.68: AUDIO + लिखावट वाला बँटवारा - यही text आवाज़ इंजन बोलता है
        if (connSplit) { replyPartArrived("पहली लिखावट"); splitHoldCheck(); pumpSplit() }
    }
    // v3.9: Gemini ne sach me Google Search kiya -> sources (title, url) is baari ke jawab ke saath
    (sc.optJSONObject("groundingMetadata") ?: o.optJSONObject("groundingMetadata"))?.let { gm ->
        val add = Grounding.sources(gm).also { WebSearch.tag(it, WebSearch.Provider.GEMINI) }
        if (add.isNotEmpty()) {
            synchronized(turnLock) { turnSources = Grounding.merge(turnSources, add) }
            if (toolJobs.isEmpty()) DKState.hint("")
            log("Google Search हुआ - ${add.size} स्रोत: ${add.joinToString { Grounding.host(it.second).ifBlank { it.first } }}")
        }
    }
    if (sc.optBoolean("interrupted") && muteTurn) {
        // किसी और की बात का (न बजा) जवाब रुका - बस छूट खत्म, बाकी कुछ नहीं बदला
        muteTurn = false; generating = false
        log("दूसरे की आवाज़ का (न बजाया) जवाब रुका")
    } else if (sc.optBoolean("interrupted")) {
        val now = System.currentTimeMillis()
        replyWatch.onServerHeard()    // server ne user ki awaaz pakdi tabhi roka - "nahi suna" rescue mat bhejo
        log("server: generation रुकी (interrupted) - आवाज़ रोकी" + if (localBargeAt > 0 && now - localBargeAt < 2000) " (फ़ोन ने ${now - localBargeAt} ms पहले ही रोक दी थी)" else "")
        // phone ne user ki awaaz nahi pakdi -> shayad DK ki apni goonj; 2 sec me user ka text na aaye to gino
        if (now - localBargeAt > 1500 && fullDuplex && aecOn) suspectAt = now
        dropOld = false               // purana generation khatam - ab aane wala audio NAYE jawab ka hai
        // sirf toka gaya (purana) turn band karo - toka-ke-boli nayi baat ki samay-rekha (barge ke baad shuru) chalti rahe
        val newAfterBarge = localBargeAt > 0 && now - localBargeAt < 10_000 && timeline.speechEndAt > localBargeAt
        if (timeline.active && !newAfterBarge) { timeline.done(now); log("turn (interrupted): ${timeline.summary()}") }
        if (trace.firstPlayAt == 0L) trace.firstChunkAt = 0L
        tagFilter = null; com.dk.assistant.avatar.MoodState.replyDone()
        leakFilter.finish(); leakFilter = newLeakFilter(); leakMute = false
        clearAct()
        flushPlayback()
        // close=false: user abhi bol raha hai (toka hai) - uski nayi baat band mat karo
        if (turnActive) finishTurn(close = false)
        commitPending(false)          // jitna bola utna hi likha rahe
        setMode(Mode.LISTENING)
    }
    if (sc.optBoolean("turnComplete")) lastReplyDoneAt = System.currentTimeMillis()   // नवंबर 2026: इसके बाद आया handle = ताज़ा (LiveSpare.handleFresh)
    if (sc.optBoolean("turnComplete") && muteTurn) {
        muteTurn = false; generating = false
        log("दूसरे की आवाज़ का जवाब नहीं बजाया ($mutedParts टुकड़े छोड़े)")
        Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
    } else if (sc.optBoolean("turnComplete")) {
        val wasDropping = dropOld
        dropOld = false
        if (actHold) endActHold()
        // v4.1: रुका हुआ बचा हिस्सा (अधूरी लिखी call भी हो तो अभी चलेगी)
        leakFilter.finish().takeIf { it.isNotEmpty() }?.let { r -> (tagFilter?.push(r) ?: r).takeIf { it.isNotEmpty() }?.let { c -> synchronized(turnLock) { replyText.append(c) } } }
        leakFilter = newLeakFilter(); leakMute = false
        tagFilter?.finish()?.takeIf { it.isNotEmpty() }?.let { r -> synchronized(turnLock) { replyText.append(r) } }
        tagFilter = null
        com.dk.assistant.avatar.MoodState.replyDone()         // v3.71: बोलना ख़त्म होने के कुछ सेकंड बाद चेहरा सामान्य
        startHold.turnComplete()                 // नवंबर 2026: रुका जवाब पूरा (आप चुप होने पर बजे या फेंका जाए)
        if (connSplit && !wasDropping && !startHold.active) { pumpSplit(); if (pipelineLazy.isInitialized()) pipeline.endTurn() }   // v3.68: बचा text आख़िरी टुकड़ा
        if (timeline.active) { timeline.done(System.currentTimeMillis()); LatencyBoard.timeline(timeline); log("turn पूरा: ${timeline.summary()}") }
        if (!wasDropping && !socialCheck() && !agentCheck()) promiseCheck()
        finishTurn()
        FriendTalk.touch(svc)   // v4.3: बातचीत का समय फ़ोन में सेव ("इतनी देर कहाँ थे?" के लिए)
        if (wasDropping) commitPending(false)   // toka hua jawab: sirf jitna bola utna hi chat me
        // jawab poora bana; jab DK ki awaaz bajna band ho uske ~1.2s baad peeche ki awaaz dheere-dheere wapas
        Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
    }
}
