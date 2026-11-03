package com.dk.assistant
class NewPolicy(override val serverSilenceMs: Int = 1200, val capWait: Int = TurnEnd.CAPTION_WAIT_MS, val cont: Int = TurnEnd.CONT_END_MS,
                val settle: Int = -1, val tag: String = "") : Policy {
    override val name = "AFTER $tag cw=$capWait cont=$cont settle=$settle"
    override fun newDet() = OldPolicy().newDet()
    var lastCap = ""; var capChangedAt = 0L
    override fun before(det: TurnDetector, cap: String, capAt: Long, now: Long) {
        if (!det.inSpeech) { det.endNeedMs = 0; return }
        if (settle < 0) { det.endNeedMs = TurnEnd.adaptiveEndMs(cap, capAt, det.lastTalkAt, det.speechMsNow, det.typicalPauseMs, true, 500, now); return }
        // variant: caption "settled" = बदला आख़िरी आवाज़ के बाद + settle ms से स्थिर
        val long = det.speechMsNow >= TurnEnd.LONG_SPEECH_MS
        val learned = if (det.typicalPauseMs > 0) (det.typicalPauseMs * 1.25 + 60).toInt() else 0
        val neutral = maxOf(if (long) 800 else 500, learned).coerceAtMost(900)
        det.endNeedMs = when {
            cap.isBlank() -> neutral
            TurnEnd.continues(cap) -> cont
            capAt >= det.lastVoiceAt && TurnEnd.complete(cap) -> if (long) 500 else if (TurnEnd.earlyOk(cap)) 360 else 500
            capAt >= det.lastVoiceAt && now - capAt >= settle -> neutral
            else -> maxOf(neutral, capWait)
        }
    }
    override fun early(det: TurnDetector, cap: String, capStableMs: Long, now: Long, capAt: Long): Boolean =
        det.silentMs >= TurnEnd.EARLY_END_MS && det.speechMsNow < TurnEnd.LONG_SPEECH_MS && capStableMs >= 200 &&
            capAt >= det.lastTalkAt && TurnEnd.earlyOk(cap) && det.forceEnd() ||
            (System.getenv("NOISE") != "0" && TurnEnd.noiseTail(now, det.lastTalkAt, capAt, det.endNeedMs, true) && det.forceEnd())
    override fun hold(cap: String, capAt: Long, lastVoiceAt: Long) = TurnEnd.continues(cap) && TurnEnd.captionFresh(capAt, lastVoiceAt)
}
