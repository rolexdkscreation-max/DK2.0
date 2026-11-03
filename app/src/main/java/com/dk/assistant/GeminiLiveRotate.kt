package com.dk.assistant

// GeminiLive का हिस्सा (नवंबर 2026, मालिक: "एक सेशन खत्म होने से पहले ही दूसरा तैयार रखो, बात न रुके,
// यूज़र को पता भी न चले"). नियम LiveSpare.kt (4. थामो और बदलो) में - unit test; यहाँ सिर्फ़ जोड़ना.

/**
 * शांत पल में थामो: पुराने connection पर अब कुछ नया नहीं (ready=false = माइक की आवाज़ फ़ोन में जमा, text/सूचना रुकी)
 * ताकि server का handle पक्का रहे, और उसी ताज़ा handle से दूसरा connection. [freezeTick] आगे देखता है.
 */
internal fun GeminiLive.beginFreeze(why: String, now: Long) {
    synchronized(sendLock) { ready = false }
    rotFreezeAt = now; freezeReal = false; rotFreezes++
    // पहले से उसी ताज़ा handle की तैयारी चल रही = दोबारा नहीं; वरना (पुराना standby/पुरानी दौड़) नई ताज़ा तैयारी
    val already = spareWanted && raceBusy() && parkHandle == handle
    val started = already || prepareSpare("$why (थामा)", idle = true, force = true)
    log("$why: शांत पल - पुराने connection पर अब कुछ नया नहीं, आवाज़ फ़ोन में जमा; उसी ताज़ा बातचीत का दूसरा connection" +
        if (started) "" else " नहीं बन सका - पुराने पर ही")
    if (!started) resumeOld("दूसरा connection शुरू नहीं हुआ", now)
}

/** थामना छोड़कर पुराने connection पर लौटो - जमा आवाज़ उसी पर (बात नहीं रुकती) */
internal fun GeminiLive.resumeOld(why: String, now: Long) {
    val held = now - rotFreezeAt
    rotFreezeAt = 0L; freezeEndAt = now; rotFallbacks++
    synchronized(sendLock) { ready = true; flushPending(); sendFrozenEnd() }
    log("थामना छोड़ा ($why) ${held} ms बाद - पुराने connection पर ही, जमा आवाज़ भेज दी; ${LiveSpare.FREEZE_RETRY_MS / 1000}s बाद फिर कोशिश")
    LatencyBoard.count("Live बदलना: पुराने पर लौटा")
}

/**
 * थामे समय आप बोले और बात पूरी हो गई (बात-खत्म का संकेत उस समय नहीं गया) = जमा आवाज़ भेजने के बाद अब संकेत.
 * sendLock के अंदर, flushPending के बाद.
 */
internal fun GeminiLive.sendFrozenEnd() {
    if (!freezeReal) return
    freezeReal = false
    if (turnDet.inSpeech) return               // अभी भी बोल रहे = संकेत अपने समय पर (माइक वाला रास्ता)
    try { ws?.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}
    log("थामे समय कही बात: जमा आवाज़ के बाद बात-खत्म का संकेत भेजा")
}

/** Live loop: थामे हुए हो तो अगला कदम. लौटाता है: loop तोड़ना है? (स्विच / सामान्य नया connection) */
internal fun GeminiLive.freezeTick(now: Long): Boolean {
    val step = LiveSpare.freezeStep(spareReady(), handleSame = parkHandle == handle, preparing = spareWanted && raceBusy() || spareReady(),
        frozenMs = now - rotFreezeAt, goAway = goAway, msLeft = goAwayDeadline - now)
    return when (step) {
        LiveSpare.Freeze.WAIT -> false
        LiveSpare.Freeze.SWITCH -> {
            log("दूसरा connection तैयार (${now - rotFreezeAt} ms थामा) - उस पर बदल रहा हूँ, बातचीत वहीं से")
            goAway = true; true
        }
        LiveSpare.Freeze.RESUME_OLD -> { resumeOld(if (parkHandle != handle) "बीच में बातचीत आगे बढ़ी" else "दूसरा connection समय पर नहीं बना", now); false }
        LiveSpare.Freeze.BREAK -> {
            log("थामे हुए: goAway का समय खत्म - सामान्य नया connection (जमा आवाज़ उस पर)")
            rotFallbacks++; goAway = true; true
        }
    }
}
