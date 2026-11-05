package com.dk.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dk.assistant.DKAccessibilityService.Element
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

// DKAccessibilityService का हिस्सा: स्क्रीन और ऐप-कंट्रोल: पढ़ना, खोज, टाइप, टैप/स्वाइप, अनलॉक, ऐप बंद करना.
// यह code पहले DKAccessibilityService.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state DKAccessibilityService में)।

internal fun DKAccessibilityService.exactClickable(n: AccessibilityNodeInfo?, words: Set<String>, d: Int): AccessibilityNodeInfo? {
    if (n == null || d > 25) return null
    val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
    if (t != null && t in words) {
        var c: AccessibilityNodeInfo? = n; var up = 0
        while (c != null && !c.isClickable && up < 3) { c = c.parent; up++ }
        if (c != null && c.isClickable && c.isEnabled) return c
    }
    for (i in 0 until n.childCount) exactClickable(n.getChild(i), words, d + 1)?.let { return it }
    return null
}

/** DK खुद बोल रहा है? (तब "गाना बज रहा" की जाँच भरोसे लायक नहीं) */
internal fun DKAccessibilityService.selfAudio(): Boolean = try { speaker.busy || gemLive.isPlaying() } catch (_: Exception) { false }

/**
 * v3.33: YouTube वीडियो सच में चला? - YouTube सामने हो और फ़ोन पर media आवाज़ चल रही हो (DK की अपनी आवाज़ नहीं).
 * "खोल दिया" को "चल रहा है" नहीं कहते.
 */
internal suspend fun DKAccessibilityService.verifyYoutube(r: String): String {
    if (!r.startsWith("PLAYING")) return r
    val t0 = System.currentTimeMillis()
    val am = getSystemService(android.media.AudioManager::class.java)
    var front = false
    while (System.currentTimeMillis() - t0 < 5000) {
        if (currentPackage() == LocalRouter.YT_PKG) front = true
        if (front && am?.isMusicActive == true && !selfAudio()) {
            DKLog.d("जाँच: YouTube वीडियो चल रहा है (${System.currentTimeMillis() - t0} ms)")
            return "$r (verified: playing)"
        }
        delay(200)
    }
    DKLog.d("जाँच: YouTube ${if (front) "सामने खुला, पर आवाज़ नहीं मिली" else "सामने नहीं दिखा"} (${System.currentTimeMillis() - t0} ms)")
    return if (front) "VIDEO_OPENED: YouTube पर वीडियो खोल दिया, पर चलना शुरू हुआ या नहीं - पक्का नहीं दिखा।"
    else "VIDEO_OPENED: वीडियो खोलने को कह दिया, पर YouTube सामने खुला नहीं दिखा।"
}

/** v3.75: ब्राउज़र खोज - ब्राउज़र सच में सामने आया? */
internal suspend fun DKAccessibilityService.verifySearch(r: String): String {
    if (!r.startsWith("SEARCHED:")) return r
    val pkg = AppLauncher.lastPkg
    if (pkg.isBlank()) return r
    val ok = waitForeground(3000) { it == pkg }
    DKLog.d("जाँच: ब्राउज़र ${if (ok) "सामने आ गया" else "सामने नहीं दिखा"}")
    return if (ok) r else "SEARCH_UNVERIFIED: " + r.removePrefix("SEARCHED:").trim() + " (पर ब्राउज़र सामने नहीं दिखा)"
}

/**
 * v3.75: "<ऐप> में X सर्च करो" - ऐप खोलो, फिर (1) ऐप का अपना search intent, नहीं तो
 * (2) स्क्रीन पर search बटन/बॉक्स ढूँढो -> लिखो -> Enter. सच्चा नतीजा (न मिले तो साफ़ बताओ).
 */
internal suspend fun DKAccessibilityService.appSearch(app: String, query: String): String {
    val q = query.trim()
    if (app.isBlank() || q.isEmpty()) return "ERROR: app ya query khali"
    val opened = AppLauncher.open(this, app)
    if (!opened.startsWith("opened")) return opened
    val pkg = AppLauncher.lastPkg
    val label = AppLauncher.labelOf(this, pkg).ifBlank { app }
    if (AppLauncher.searchIntent(this, pkg, q)) {
        if (waitForeground(3000) { it == pkg }) return "SEARCHED: $label में \"$q\" खोज दिया।"
    }
    if (!waitForeground(3500) { it == pkg }) return "ERROR_LAUNCH: $label सामने नहीं आया, खोज नहीं हो पाई।"
    delay(900)
    val box = findSearchNode()
    if (box == null) return "STEP_FAILED: $label खुल गया, पर उसमें खोज का बटन नहीं मिला।"
    if (!box.isEditable) { box.performAction(AccessibilityNodeInfo.ACTION_CLICK); delay(700) }
    val input = (if (box.isEditable) box else null) ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable()
        ?: return "STEP_FAILED: $label में खोज का बॉक्स नहीं खुला।"
    input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, q) }
    if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "STEP_FAILED: $label में \"$q\" लिख नहीं पाया।"
    delay(250)
    val entered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    DKLog.d("ऐप-खोज: $label • \"${q.take(30)}\" • enter=${entered}")
    return if (entered) "SEARCHED: $label में \"$q\" खोज दिया।"
    else "SEARCHED: $label में \"$q\" लिख दिया (नतीजे के लिए खोज वाला बटन दबाना पड़ सकता है)।"
}

internal fun DKAccessibilityService.isSearchy(n: AccessibilityNodeInfo): Boolean {
    val id = (n.viewIdResourceName ?: "").lowercase()
    val d = ((n.contentDescription ?: "").toString() + " " + (n.text ?: "").toString() + " " +
        (if (Build.VERSION.SDK_INT >= 26) (n.hintText ?: "").toString() else "")).lowercase()
    return id.contains("search") || listOf("search", "सर्च", "खोज", "ढूंढ", "ढूँढ").any { d.contains(it) }
}

/** स्क्रीन पर search बॉक्स (पहले) या search बटन */
internal fun DKAccessibilityService.findSearchNode(): AccessibilityNodeInfo? {
    var button: AccessibilityNodeInfo? = null
    for (root in allRoots()) {
        if (root.packageName?.toString() == packageName) continue
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>(); stack.add(root to 0)
        while (stack.isNotEmpty()) {
            val (n, d) = stack.removeLast()
            if (n.isVisibleToUser && isSearchy(n)) {
                if (n.isEditable) return n
                if (button == null) { var c: AccessibilityNodeInfo? = n; var k = 0
                    while (c != null && !c.isClickable && k < 3) { c = c.parent; k++ }
                    if (c != null && c.isClickable) button = c }
            }
            if (d < 30) for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it to d + 1) }
        }
    }
    return button
}

internal fun DKAccessibilityService.findEditable(): AccessibilityNodeInfo? {
    for (root in allRoots()) {
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
    }
    return null
}

/** v3.33: ऐप सच में सामने आया? (नतीजे पर निशान; न दिखे तो भी झूठा "खुल गया" नहीं) */
internal suspend fun DKAccessibilityService.verifyOpen(r: String): String {
    if (!r.startsWith("opened")) return r
    val pkg = AppLauncher.lastPkg
    if (pkg.isBlank()) return r
    val t0 = System.currentTimeMillis()
    val ok = waitForeground(2500) { it == pkg }
    DKLog.d("जाँच: ऐप ${if (ok) "सामने आ गया" else "सामने नहीं दिखा"} (${System.currentTimeMillis() - t0} ms)")
    return if (ok) "$r (verified)" else "$r (not confirmed on screen)"
}

/**
 * v3.33: कई कदम एक-एक करके; हर कदम के बाद जाँच, कोई कदम न हो / पूछना पड़े तो वहीं रुको (आगे झूठा "हो गया" नहीं).
 * सिर्फ़ तय action types (nested "steps" नहीं), ज़्यादा से ज़्यादा 6.
 */
internal suspend fun DKAccessibilityService.performSteps(arr: org.json.JSONArray?): String {
    if (arr == null || arr.length() == 0) return "ERROR: steps khali"
    if (arr.length() > 6) return "ERROR: bahut saare steps"
    val done = mutableListOf<String>()
    var last = ""
    for (i in 0 until arr.length()) {
        val st = arr.optJSONObject(i) ?: return "ERROR: step ${i + 1} galat"
        val type = st.optString("type")
        if (type == "steps") return "ERROR: nested steps nahi"
        val prev = currentPackage()
        val r = perform(st)
        DKLog.d("कदम ${i + 1}/${arr.length()} $type -> ${r.take(80)}")
        if (r.startsWith("ASK:") || r.startsWith("ERROR")) {
            val why = LocalRouter.userText(r) ?: r.take(100)
            return if (r.startsWith("ASK:")) r
            else "STEP_FAILED: ${if (done.isEmpty()) "" else done.joinToString(", ") + " हो गया, पर "}कदम ${i + 1} ($type) नहीं हुआ: $why"
        }
        done.add(LiveFast.describe(st)); last = r
        if (i < arr.length() - 1 && type in listOf("open_app", "shop_search", "youtube_search", "open_url", "web_search", "browser_search", "app_search")) waitSettle(prev, true, 3500)
    }
    return "STEPS_DONE: " + done.joinToString(", ") + "। " + (LocalRouter.userText(last) ?: "")
}

/**
 * v3.156: पूरी स्क्रीन की काम की चीज़ें इकट्ठा करो (पहले 140 चीज़ / गहराई 40 पर रुक जाता था - लंबी chat या
 * लंबी product list में नीचे वाला "भेजें" / search / tab बटन DK को दिखता ही नहीं था, तो वह "टैप नहीं कर सकता" बोलता था).
 * यहाँ सिर्फ़ इकट्ठा; कौन-सी रखनी हैं और लिखना = readScreen (NodePick).
 */
internal fun DKAccessibilityService.walk(node: AccessibilityNodeInfo, out: MutableList<Element>, sb: StringBuilder, depth: Int) {
    if (out.size >= com.dk.assistant.agent.core.NodePick.RAW_MAX || depth > com.dk.assistant.agent.core.NodePick.DEPTH_MAX) return
    if (node.isVisibleToUser) {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val useful = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable ||
            node.isEditable || node.isScrollable || node.isLongClickable
        if (useful) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 0 && r.height() > 0) out.add(Element(out.size, node, r))
        }
    }
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        walk(child, out, sb, depth + 1)
    }
}

/** AI को दिखने वाली एक पंक्ति: [id] Class "text" (desc) {flags} @x,y */
internal fun screenLine(e: Element, sb: StringBuilder) {
    val node = e.node; val r = e.bounds
    val text = node.text?.toString()?.trim().orEmpty()
    val desc = node.contentDescription?.toString()?.trim().orEmpty()
    val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
    val flags = buildList {
        if (node.isClickable) add("click")
        if (node.isLongClickable) add("long")
        if (node.isEditable) add("edit")
        if (node.isScrollable) add("scroll")
        if (node.isChecked) add("checked")
        if (node.isSelected) add("selected")
        if (node.isFocused) add("focused")
    }.joinToString(",")
    sb.append("[").append(e.id).append("] ").append(cls)
    if (text.isNotEmpty()) sb.append(" \"").append(text.take(50)).append("\"")
    if (desc.isNotEmpty() && desc != text) sb.append(" (").append(desc.take(50)).append(")")
    if (flags.isNotEmpty()) sb.append(" {").append(flags).append("}")
    sb.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
}

internal fun DKAccessibilityService.element(id: Int): Element? = elements.getOrNull(id)

internal suspend fun DKAccessibilityService.longClick(id: Int): String {
    val e = element(id) ?: return "ERROR: id $id screen par nahi hai"
    var n: AccessibilityNodeInfo? = e.node
    while (n != null && !n.isLongClickable) n = n.parent
    if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "long clicked $id"
    val x = e.bounds.exactCenterX(); val y = e.bounds.exactCenterY()
    return if (swipe(x, y, x, y, 900)) "long pressed $id" else "ERROR long click $id"
}

internal suspend fun DKAccessibilityService.typeText(id: Int, text: String): String {
    var target: AccessibilityNodeInfo? = element(id)?.node
    if (target != null && !target.isEditable) {
        click(id); delay(500)
        target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: target
    }
    if (target == null) target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (target == null) return "ERROR: koi text box nahi mila"
    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val args = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed" else "ERROR type"
}

internal fun DKAccessibilityService.pressEnter(): String {
    val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "ERROR: focused box nahi"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val ok = f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (ok) return "enter pressed"
    }
    return "ERROR: enter support nahi, search button click karo"
}

/** Screen jagao; swipe-lock ho to khol do. PIN/pattern: सिर्फ़ तब जब मालिक ने आवाज़ से अनलॉक चालू करके सेव किया हो, वरना user खुद (suraksha). */
internal suspend fun DKAccessibilityService.unlock(): String =
    // नवंबर 2026: एक समय में एक ही अनलॉक, और शुरू हुआ तो पूरा (Live में बीच में रद्द होकर आधा PIN नहीं)
    PhoneUnlocker.gate.withLock { withContext(NonCancellable) { unlockNow() } }

@Suppress("DEPRECATION")
private suspend fun DKAccessibilityService.unlockNow(): String {
    val t0 = System.currentTimeMillis()
    val pm = getSystemService(android.os.PowerManager::class.java)
    val km = getSystemService(android.app.KeyguardManager::class.java)
    val wasOff = pm != null && !pm.isInteractive
    if (wasOff) {
        try {
            val wl = pm!!.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "dk:wake")
            wl.acquire(4000)
        } catch (e: Exception) { DKLog.d("wake: ${e.message}") }
    }
    if (km == null || !km.isKeyguardLocked) {
        if (wasOff) waitScreenOn(pm, 800)
        return "screen on hai, phone pehle se unlocked hai"
    }
    // नवंबर 2026: पहले स्क्रीन जगाकर अंदाज़े से swipe (700ms + 700ms रुककर) - Android 14 पर कई फ़ोन में स्क्रीन जगती ही नहीं थी,
    // अंधेरी स्क्रीन पर swipe बेकार और PIN वाला हिस्सा कभी नहीं खुलता। अब छोटी पारदर्शी स्क्रीन: स्क्रीन तुरंत जगे और
    // Android खुद PIN वाला हिस्सा खोले (requestDismissKeyguard); swipe सिर्फ़ अगर यह न चले
    val asked = UnlockWakeActivity.open(this)
    waitScreenOn(pm, 1200)
    val canPin = km.isKeyguardSecure && PhoneLockStore.unlockOn(this) && PhoneLockStore.hasSecret(this)
    // v4.8 मालिक: "पहले स्क्रीन ऑन करेगा, फिर स्वाइप अप करेगा, जैसे ही PIN / पैटर्न डालने का ऑप्शन आएगा, डालना शुरू".
    // पहले swipe सिर्फ़ तब होती थी जब Android वाला तरीका (asked) न चले - कई फ़ोन पर घड़ी वाली स्क्रीन ही रह जाती थी।
    // अब: स्क्रीन जगी -> थोड़ा रुको -> PIN वाला हिस्सा पहले से न दिखे तो ऊपर swipe (हर हाल में)
    if (km.isKeyguardLocked) {
        delay(if (asked) 350 else 150)
        if (km.isKeyguardLocked && !PhoneUnlocker.bouncerUp(this)) {
            val dm = resources.displayMetrics
            swipe(dm.widthPixels / 2f, dm.heightPixels * 0.88f, dm.widthPixels / 2f, dm.heightPixels * 0.3f, 300)
            delay(400)
        }
    }
    if (!km.isKeyguardSecure && km.isKeyguardLocked) {
        // सिर्फ़ swipe वाला ताला: Android खुद खोल देता है - न खुले तो एक और swipe
        var n = 0
        while (km.isKeyguardLocked && n++ < 8) delay(PhoneUnlocker.POLL_MS)
        if (km.isKeyguardLocked) {
            val dm = resources.displayMetrics
            swipe(dm.widthPixels / 2f, dm.heightPixels * 0.88f, dm.widthPixels / 2f, dm.heightPixels * 0.3f, 300)
            delay(600)
        }
    }
    val r = when {
        !km.isKeyguardLocked -> "phone unlock ho gaya"
        // नवंबर 2026: मालिक ने Settings → फ़ोन लॉक / अनलॉक में PIN / पैटर्न सेव किया हो तभी DK खुद डालता है
        // (गलत की गिनती पूरी हो तो enter खुद बताता है कि क्यों रुका - PIN नहीं डालता)
        canPin -> PhoneUnlocker.enter(this, km, asked)
        // v3.151: पहले यह "हो गया" गिना जाता था (LiveFast.ok) - DK चुप या "खुल गया" बोलता, फ़ोन बंद ही रहता।
        // अब सच्चा कारण + क्या करना है (Settings में PIN सेव)
        km.isKeyguardSecure -> PhoneLockVoice.noPinReason(PhoneLockStore.unlockOn(this), PhoneLockStore.hasSecret(this))
        // v4.8: swipe के बाद भी बंद = साफ़ बताओ (पहले "khuli" मान लिया जाता था)
        else -> "${PhoneLockVoice.UNLOCK_FAIL} ऊपर स्वाइप करने पर भी लॉक स्क्रीन नहीं हटी। फ़ोन खुद खोलिए।"
    }
    DKLog.d("फ़ोन अनलॉक: ${r.take(50)} (${System.currentTimeMillis() - t0} ms, स्क्रीन ${if (wasOff) "बंद थी" else "चालू थी"}, PIN हिस्सा माँगा=$asked)")
    return r
}

private suspend fun waitScreenOn(pm: android.os.PowerManager?, maxMs: Long) {
    if (pm == null) return
    val until = System.currentTimeMillis() + maxMs
    while (!pm.isInteractive && System.currentTimeMillis() < until) delay(50)
}

/** Saari windows (notification popup, call screen) me button dhoondo aur dabao. */
internal fun DKAccessibilityService.allRoots(): List<AccessibilityNodeInfo> {
    val out = mutableListOf<AccessibilityNodeInfo>()
    try { windows.forEach { w -> w.root?.let { out.add(it) } } } catch (_: Exception) {}
    rootInActiveWindow?.let { if (out.none { r -> r == it }) out.add(0, it) }
    return out
}

internal fun DKAccessibilityService.clickByText(vararg words: String): Boolean {
    for (root in allRoots()) for (w in words) {
        for (n in root.findAccessibilityNodeInfosByText(w)) {
            var c: AccessibilityNodeInfo? = n; var depth = 0
            while (c != null && !c.isClickable && depth < 4) { c = c.parent; depth++ }
            if (c != null && c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
    }
    return false
}

/** View-id se (jaise "answer_button") button dabao. */
internal fun DKAccessibilityService.clickById(vararg parts: String): Boolean {
    fun walk(n: AccessibilityNodeInfo?, d: Int): Boolean {
        if (n == null || d > 30) return false
        val id = n.viewIdResourceName?.lowercase() ?: ""
        val desc = n.contentDescription?.toString()?.lowercase() ?: ""
        if (parts.any { id.contains(it) || desc.contains(it) }) {
            var c: AccessibilityNodeInfo? = n; var k = 0
            while (c != null && !c.isClickable && k < 3) { c = c.parent; k++ }
            if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        for (i in 0 until n.childCount) if (walk(n.getChild(i), d + 1)) return true
        return false
    }
    return allRoots().any { walk(it, 0) }
}

/** Abhi wala app band karo: recents me card upar fenko. */
internal suspend fun DKAccessibilityService.closeApp(): String {
    val pkg = rootInActiveWindow?.packageName?.toString()
    if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) return "ERROR recents"
    delay(900)
    val dm = resources.displayMetrics
    swipe(dm.widthPixels / 2f, dm.heightPixels * 0.5f, dm.widthPixels / 2f, dm.heightPixels * 0.05f, 220)
    delay(500)
    performGlobalAction(GLOBAL_ACTION_HOME)
    return "closed recent app ${pkg ?: ""}"
}

/** Sab apps band: recents me "Clear all / सभी बंद करें" dabao. */
internal suspend fun DKAccessibilityService.closeAllApps(): String {
    if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) return "ERROR recents"
    delay(1000)
    val root = rootInActiveWindow ?: return "ERROR recents screen"
    val words = listOf("clear all", "close all", "सभी साफ़", "सब हटाएँ", "सभी बंद", "सभी हटाएं", "सभी साफ करें", "x")
    for (w in words) {
        val n = root.findAccessibilityNodeInfosByText(w).firstOrNull { it.isClickable || it.parent?.isClickable == true }
        if (n != null) {
            (if (n.isClickable) n else n.parent).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return "cleared all recent apps"
        }
    }
    return "recents khula hai, 'Clear all' button nahi mila - screen dekh kar dabao"
}

internal fun DKAccessibilityService.global(action: Int): String = if (performGlobalAction(action)) "ok" else "ERROR"

internal suspend fun DKAccessibilityService.tap(x: Float, y: Float): Boolean = swipe(x, y, x, y, 60)

internal suspend fun DKAccessibilityService.swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
    val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        .build()
    return suspendCancellableCoroutine { cont ->
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }
}
