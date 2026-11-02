package com.dk.assistant

import com.dk.assistant.GeminiLive.Companion.IN_RATE
import com.dk.assistant.GeminiLive.OwnerResult

// GeminiLive का हिस्सा: मालिक की आवाज़ / पास की आवाज़ की जाँच.
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

/** v4.1: रोकी हुई आवाज़ मालिक की है? (मिलान फ़ेल / आवाज़ बहुत छोटी = हाँ, पहले जैसा) */
internal fun GeminiLive.ownerSaysOwner(held: Collection<ByteArray>): Boolean {
    return try {
        val out = java.io.ByteArrayOutputStream(); held.forEach { out.write(it) }
        val audio = out.toByteArray()
        if (audio.size < IN_RATE * 2 / 2) return true
        val sc = com.dk.assistant.voice.OwnerVoice.score(svc, audio) ?: return true
        sc >= ownerTh
    } catch (_: Throwable) { true }
}

// ================================================================ v3.66: सिर्फ़ मालिक की आवाज़
internal fun GeminiLive.refreshOwnerCfg(now: Long) {
    ownerCfgAt = now
    ownerOn = com.dk.assistant.voice.OwnerVoice.active(svc)
    ownerTh = com.dk.assistant.voice.VoicePrint.threshold(Prefs.ownerVoiceStrict(svc))
    // v3.6: "दूर की आवाज़ अनसुनी" सिर्फ़ जब "सिर्फ़ मेरी आवाज़" चालू हो - वरना धीमी बोली हुई बात भी फेंक देता था
    turnDet.startFloor = LiveHear.farFloor(ownerOn, com.dk.assistant.voice.OwnerVoice.farFloor(svc, btOn))
    // v4.7: बिना आवाज़ सिखाए भी दूर की आवाज़ अनसुनी - आपकी पास वाली आवाज़ से सीखा (Bluetooth पर बंद: माइक मुँह के पास)
    if (!ownerOn && !btOn && Prefs.ownerFarOn(svc)) turnDet.startFloor = near.floor()
    val fb = turnDet.farBlocked
    if (fb > 0 && fb % 10 == 1 && fb != farLogged) { farLogged = fb; log("दूर की / धीमी आवाज़ अनसुनी (#$fb)") }
}

/**
 * v4.7: असली (शब्दों वाली) बात खत्म - उसका स्तर सीखो (दूर की आवाज़ की रोक इसी से). Bluetooth / बिना शब्द / बहुत छोटी = नहीं.
 * दूर की आवाज़ शुरू ही नहीं होती तो सीखी भी नहीं जाती - सीखा स्तर आपकी पास वाली आवाज़ का ही रहता है.
 */
internal fun GeminiLive.learnNear() {
    val lv = NearVoice.utterLevel(nearRms, turnDet.noise)
    nearRms.clear()
    if (btOn || lv <= 0 || turnDet.lastSpeechMs < 500 || !BargeGate.realWords(caption?.text().orEmpty())) return
    val before = near.floor()
    near.learn(lv)
    if (++nearLearned % 5 == 1) try { Prefs.edit(svc) { putString("near_levels", near.save()) } } catch (_: Exception) {}
    if (before <= 0 && near.floor() > 0) log("आपकी पास वाली आवाज़ सीख ली (स्तर ${near.level.toInt()}) - इससे बहुत धीमी (दूर की) आवाज़ अब अनसुनी")
}

/** पहली ~1 सेकंड आवाज़ का मिलान अलग thread पर - mic और Gemini को भेजना रुकता नहीं */
internal fun GeminiLive.checkOwnerAsync() {
    val gen = ownerGen
    val audio = owner.takeAudio()
    val ms = audio.size * 1000 / (IN_RATE * 2)
    try {
        com.dk.assistant.voice.OwnerVoice.exec.execute {
            val t0 = System.currentTimeMillis()
            val sc = try { com.dk.assistant.voice.OwnerVoice.score(svc, audio) } catch (_: Throwable) { null }
            ownerResult = OwnerResult(gen, sc, ms, System.currentTimeMillis() - t0)
        }
    } catch (_: Exception) { owner.onFailed(System.currentTimeMillis()) }
}

/** मिलान का नतीजा आया हो तो लागू (mic thread पर) */
internal fun GeminiLive.applyOwnerResult(now: Long) {
    val r = ownerResult ?: return
    ownerResult = null
    if (r.gen != ownerGen || !owner.active) return
    val before = owner.verdict
    if (r.score == null) owner.onFailed(now) else owner.onScore(r.score, ownerTh, now, r.ms, atEnd = false)
    if (owner.verdict != before) onOwnerVerdict(now, r.score, r.ms, r.tookMs)
}

internal fun GeminiLive.onOwnerVerdict(now: Long, score: Float?, ms: Int, tookMs: Long) {
    val sc = score?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "-"
    when (owner.verdict) {
        com.dk.assistant.voice.OwnerGate.Verdict.OWNER -> {
            if (owner.checks <= 1 && owner.lastScore.isFinite()) DKLog.d("Live: आवाज़ मिलान $sc - मालिक (${ms} ms आवाज़, $tookMs ms)")
            if (muteTurn && !generating) muteTurn = false
            if (ownerHolding) {
                ownerHolding = false
                synchronized(sendLock) { ownerHold.forEach { sendAudio(it) } }
                ownerHold.forEach { capSend(it) }; ownerHold.clear()
            }
        }
        com.dk.assistant.voice.OwnerGate.Verdict.OTHER -> {
            othersIgnored++
            log("किसी और की आवाज़ (मिलान $sc, ${ms} ms) - अनसुनी, जवाब नहीं (#$othersIgnored)")
            val held = ownerHolding
            ownerHolding = false; ownerHold.clear()
            caption?.discard()
            DKState.user("")
            replyWatch.reset()
            // पहले से Gemini को गई (रोकी नहीं गई) आवाज़ का जवाब आएगा - उसे न बजाओ, न काम
            if (!held) { muteTurn = true; muteAt = now }
            if (DKState.mode == Mode.LISTENING && !generating) setMode(Mode.IDLE)
            Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
        }
        else -> {}
    }
}

/** बोलना खत्म: फ़ैसला बाकी हो तो अभी (इसी thread पर, ~15-60 ms - जवाब आने से पहले) */
internal fun GeminiLive.ownerEnd(now: Long) {
    applyOwnerResult(now)
    if (owner.verdict == com.dk.assistant.voice.OwnerGate.Verdict.PENDING) {
        val before = owner.verdict
        if (owner.needFinalCheck(now)) {
            val audio = owner.takeAudio(); val ms = audio.size * 1000 / (IN_RATE * 2)
            val t0 = System.currentTimeMillis()
            val sc = try { com.dk.assistant.voice.OwnerVoice.score(svc, audio) } catch (_: Throwable) { null }
            if (sc == null) owner.onFailed(now) else owner.onScore(sc, ownerTh, now, ms, atEnd = true)
            ownerGen++; ownerResult = null           // बीच वाला (अगर चल रहा था) अब बेकार
            if (owner.verdict != before) onOwnerVerdict(now, sc, ms, System.currentTimeMillis() - t0)
        } else if (owner.verdict != before) onOwnerVerdict(now, null, owner.audioMs(), 0)
    }
    val isOwner = owner.verdict == com.dk.assistant.voice.OwnerGate.Verdict.OWNER
    if (isOwner) {
        val lv = com.dk.assistant.voice.VoicePrint.speechLevel(owner.rms(), turnDet.noise)
        if (lv > 0) try { com.dk.assistant.voice.OwnerVoice.learnLevel(svc, lv) } catch (_: Exception) {}
    }
    owner.finish()
    if (isOwner) onLocalEndOfSpeech(now)
}
