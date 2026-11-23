package com.dk.assistant

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// DKAccessibilityService का हिस्सा: YouTube वीडियो चलने के बाद - ऐड आया तो स्किप, माँगा हो तो फुल स्क्रीन.
// 21 नवंबर 2026 (मालिक): "वहाँ रुक के देखेगा कि ऐड आ रहा है तो स्किप कर देगा, नहीं आ रहा होगा तो चला देगा और बता देगा".
// नियम (कौन सा बटन क्या है, नतीजे की लाइन) [YtAssist] में - JVM test.

private class YtSeen(val skip: AccessibilityNodeInfo?, val ad: Boolean, val full: AccessibilityNodeInfo?, val isFull: Boolean, val player: Rect?, val screen: Rect)

private val PLAYER_IDS = listOf("watch_player", "player_view", "player_fragment_container", "inline_player", "player_overlays", "youtube_player")

@Volatile private var ytWatch: Job? = null

/** YouTube सामने हो तो उसकी स्क्रीन एक बार पढ़ो (YouTube सामने नहीं = null) */
private fun DKAccessibilityService.ytSee(): YtSeen? {
    val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
    if (root.packageName?.toString() != LocalRouter.YT_PKG) return null
    val screen = Rect().also { root.getBoundsInScreen(it) }
    var skip: AccessibilityNodeInfo? = null
    var ad = false
    var full: AccessibilityNodeInfo? = null
    var isFull = false
    var player: Rect? = null
    val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    stack.add(root to 0)
    var seen = 0
    while (stack.isNotEmpty() && seen < 1500) {
        val (n, d) = stack.removeLast(); seen++
        val id = n.viewIdResourceName
        val tx = n.text?.toString()
        val ds = n.contentDescription?.toString()
        if (skip == null && n.isVisibleToUser && YtAssist.isSkip(id, tx, ds)) skip = n
        if (!ad && YtAssist.isAd(id, tx, ds)) ad = true
        if (YtAssist.isExitFull(ds)) isFull = true
        if (full == null && YtAssist.isFullBtn(id, ds)) full = n
        if (player == null && id != null && PLAYER_IDS.any { id.contains(it) }) {
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.width() > 100 && r.height() > 100) player = r
        }
        if (d < 40) for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it to d + 1) }
    }
    if (skip != null) ad = true
    return YtSeen(skip, ad, full, isFull, player, screen)
}

/** बटन दबाओ: पहले accessibility click (ख़ुद या ऊपर वाला), न हो तो उसकी जगह पर टैप */
private suspend fun DKAccessibilityService.ytPress(n: AccessibilityNodeInfo): Boolean {
    var c: AccessibilityNodeInfo? = n; var up = 0
    while (c != null && !c.isClickable && up < 3) { c = c.parent; up++ }
    if (c != null && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
    val r = Rect().also { n.getBoundsInScreen(it) }
    return r.width() > 0 && r.height() > 0 && tap(r.exactCenterX(), r.exactCenterY())
}

private fun YtSeen.landscape() = screen.width() > screen.height()

/**
 * वीडियो खुलने के बाद: थोड़ा रुककर देखो ऐड आया क्या. Skip बटन आते ही दबाओ (दो ऐड हों तो दोनों), फिर माँगा हो तो फुल स्क्रीन.
 * लौटता है नतीजे की लाइन ([YtAssist.report]) - Live यही सच बोलता है. बाद में आने वाले ऐड पर एक मिनट पीछे से नज़र.
 */
internal suspend fun DKAccessibilityService.ytAfterPlay(full: Boolean): String {
    val t0 = System.currentTimeMillis()
    var ad = YtAssist.Ad.NONE
    // 1) ऐड दिखा? (वीडियो खुलते ही ऐड का बैज / Skip गिनती दिखती है)
    var adNow = false
    while (System.currentTimeMillis() - t0 < YtAssist.AD_LOOK_MS) {
        val s = ytSee() ?: break
        if (s.ad) { adNow = true; break }
        delay(250)
    }
    // 2) ऐड है = Skip बटन का इंतज़ार, दबाओ; अगला ऐड (1 of 2) भी
    var rounds = 0
    while (adNow && rounds < 3) {
        rounds++
        val t1 = System.currentTimeMillis()
        var pressed = false
        var gone = false
        while (System.currentTimeMillis() - t1 < YtAssist.SKIP_WAIT_MS) {
            val s = ytSee() ?: break
            val sk = s.skip
            if (sk != null && ytPress(sk)) { pressed = true; DKLog.d("YouTube: ऐड स्किप किया (${System.currentTimeMillis() - t1} ms)"); break }
            if (!s.ad) { gone = true; break }
            delay(300)
        }
        if (pressed) ad = YtAssist.Ad.SKIPPED else if (ad == YtAssist.Ad.NONE) ad = YtAssist.Ad.UNSKIPPABLE
        if (gone || !pressed) break
        delay(900)
        adNow = ytSee()?.ad == true
    }
    // बिना स्किप वाला ऐड चल रहा हो तो फुल स्क्रीन के लिए उसके ख़त्म होने तक (20 s तक) - ऐड पर टैप = विज्ञापन वाली साइट खुल सकती है
    if (full && ad == YtAssist.Ad.UNSKIPPABLE) {
        val t2 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t2 < 20_000 && ytSee()?.ad == true) delay(500)
    }
    val fullDone = if (full) ytFullscreen() else false
    ytWatchLater()
    DKLog.d("YouTube बाद का काम: ऐड=$ad, फुल=${if (full) fullDone.toString() else "माँगा नहीं"} (${System.currentTimeMillis() - t0} ms)")
    return YtAssist.report(ad, full, fullDone)
}

/** YouTube का वीडियो फुल स्क्रीन (बटन दिखे तो दबाओ; छुपे controls = वीडियो पर एक टैप; आख़िर में ऊपर की ओर स्वाइप) */
internal suspend fun DKAccessibilityService.ytFullscreen(): Boolean {
    repeat(3) { attempt ->
        val s = ytSee() ?: return false
        if (s.isFull || s.landscape()) return true
        val b = s.full
        if (b != null && ytPress(b)) {
            delay(900)
            val a = ytSee()
            if (a == null || a.isFull || a.landscape() || a.full == null) return true
        } else if (attempt < 2 && !s.ad) {
            // controls छुपे हैं - वीडियो पर एक टैप से दिखते हैं (बीच का play/pause नहीं दबता)
            val p = s.player ?: Rect(0, s.screen.top, s.screen.width(), s.screen.top + s.screen.width() * 9 / 16)
            tap(p.exactCenterX(), p.top + p.height() * 0.3f)
            delay(450)
        }
    }
    // YouTube में वीडियो पर ऊपर की ओर स्वाइप = फुल स्क्रीन
    val s = ytSee() ?: return false
    if (s.isFull || s.landscape()) return true
    if (s.ad) return false
    val p = s.player ?: Rect(0, s.screen.top, s.screen.width(), s.screen.top + s.screen.width() * 9 / 16)
    swipe(p.exactCenterX(), p.top + p.height() * 0.8f, p.exactCenterX(), p.top + p.height() * 0.2f, 220)
    delay(900)
    val a = ytSee() ?: return false
    return a.isFull || a.landscape()
}

/** अभी स्क्रीन पर ऐड है तो Skip आते ही दबाओ ("ऐड स्किप करो" अकेले बोला) */
internal suspend fun DKAccessibilityService.ytSkipNow(): String {
    val s0 = ytSee() ?: return "STEP_FAILED: YouTube सामने नहीं है।"
    if (!s0.ad) return "SAID: अभी कोई ऐड नहीं चल रहा।"
    val t0 = System.currentTimeMillis()
    while (System.currentTimeMillis() - t0 < YtAssist.SKIP_WAIT_MS) {
        val s = ytSee() ?: break
        val sk = s.skip
        if (sk != null && ytPress(sk)) return "SAID: ऐड स्किप कर दिया।"
        if (!s.ad) return "SAID: ऐड ख़त्म हो गया, वीडियो चल रहा है।"
        delay(300)
    }
    return "SAID: यह ऐड स्किप नहीं हो सकता, ख़त्म होते ही वीडियो चलेगा।"
}

/** बाद में (एक मिनट तक) बीच में आने वाला ऐड भी स्किप - YouTube से बाहर जाते ही रुकता है */
private fun DKAccessibilityService.ytWatchLater() {
    ytWatch?.cancel()
    ytWatch = scope.launch {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < YtAssist.WATCH_MS) {
            delay(800)
            val s = ytSee() ?: break
            val sk = s.skip ?: continue
            if (ytPress(sk)) DKLog.d("YouTube: बीच वाला ऐड भी स्किप किया")
        }
    }
}

/**
 * 21 नवंबर 2026 (मालिक): "DK स्कैन खोलो" = DK लेंस. Live जुड़ी हो = सीधे कैमरा (बिना बोले पीछे वाला);
 * नहीं = आवाज़ वाली स्क्रीन खोलो, वह Live जोड़कर लेंस खोलती है (होम के "स्कैन" बटन वाला ही रास्ता).
 */
internal fun DKAccessibilityService.openLens(on: Boolean, front: Boolean): String {
    val facing = if (front) "front" else "back"
    if (!on) { LiveVision.setCamera(this, false); return "SAID: DK स्कैन बंद कर दिया।" }
    // 23 नवंबर 2026 (मालिक: निजता): कैमरा खोलने से पहले "क्या मैं ये देखूँ?" - हाँ (कार्ड / आवाज़) के बाद ही
    if (!PrivacyGate.consumeGrant(PrivacyGate.Kind.CAMERA)) {
        val asked = PrivacyGate.askThen(PrivacyGate.Kind.CAMERA) { openLens(true, front) }
        return if (asked) "SAID: ${PrivacyGate.QUESTION} (कैमरा) - स्क्रीन पर हाँ या नहीं दबाइए।"
        else "SAID: कैमरा खोलने से पहले मुझे आपसे पूछना है - DK ऐप खोलकर फिर कहिए।"
    }
    if (DKState.live && gemLive.canTalk()) {
        val r = LiveVision.setCamera(this, true, facing)
        return if (r.startsWith("Turning") || r.startsWith("Camera is already ON")) r else "ERROR: $r"
    }
    // 23 नवंबर 2026 (मालिक): होम का स्कैन टैब खुला = लेंस वहीं (DK लाइव जोड़कर), पुरानी आवाज़ स्क्रीन नहीं
    if (com.dk.assistant.dkai.ScanPanel.again(front)) {
        if (!DKState.live) startLive()
        return "SAID: DK स्कैन खोल रहा हूँ (${if (front) "सामने वाला" else "पीछे वाला"} कैमरा)।"
    }
    return try {
        startActivity(android.content.Intent(this, VoiceActivity::class.java)
            .putExtra(VoiceActivity.EXTRA_WANT, VoiceActivity.WANT_CAMERA)
            .putExtra(VoiceActivity.EXTRA_FRONT, front)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        "SAID: DK स्कैन खोल रहा हूँ (${if (front) "सामने वाला" else "पीछे वाला"} कैमरा)।"
    } catch (e: Exception) { "ERROR: DK स्कैन नहीं खुला: ${e.message}" }
}
