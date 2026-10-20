package com.dk.assistant

import android.accessibilityservice.AccessibilityService
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * DK ka "haath aur aankh":
 *  - screen ke saare buttons/text padhta hai (aankh)
 *  - click, long press, type, scroll, swipe, back/home karta hai (haath)
 *  - screenshot leta hai (Android 11+)
 *  - floating "DK" bubble dikhata hai: tap = bolo, dobara tap = roko
 */
class DKAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: DKAccessibilityService? = null
            private set
    }

    data class Element(val id: Int, val node: AccessibilityNodeInfo, val bounds: Rect)
    data class Screen(val text: String, val packageName: String, val width: Int, val height: Int, val count: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var elements: List<Element> = emptyList()

    lateinit var speaker: Speaker
        private set
    lateinit var listener: VoiceListener
        private set
    lateinit var agent: AgentRunner
        private set
    lateinit var ear: Ear
        private set

    private var bubble: OrbView? = null
    private var wakeLoopRunning = false

    // ---------------------------------------------------------------- lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        speaker = Speaker(this)
        listener = VoiceListener(this)
        ear = Ear(this)
        agent = AgentRunner(this, scope, speaker)
        showBubble()
        DKLog.d("DK accessibility service chalu ho gaya")
        val user = Prefs.userName(this)
        scope.launch {
            val hello = "${Prefs.assistantName(this@DKAccessibilityService)} तैयार है" + (if (user.isNotBlank()) ", $user" else "") + "।"
            DKState.reply(hello)
            speaker.speak(hello)
        }
        if (Prefs.liveMode(this)) startLive() else ensureLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* zarurat nahi, hum on-demand padhte hain */ }

    override fun onInterrupt() { agent.stop() }

    override fun onDestroy() {
        instance = null
        removeBubble()
        DKState.setLive(false)
        listener.release()
        ear.release()
        speaker.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- voice entry points
    /**
     * Orb / mic tap = LIVE MODE ON/OFF.
     * ON hone par DK lagatar sunta rehta hai: aap bolo -> kaam/jawab -> phir se sunna... jab tak aap band na karo.
     */
    fun toggleLive() {
        if (DKState.live) stopLive() else startLive()
    }

    fun startLive() {
        if (DKState.live) return
        DKState.setLive(true)
        Prefs.edit(this) { putBoolean("live_mode", true) }
        ensureLoop()
    }

    fun stopLive() {
        DKState.setLive(false)
        Prefs.edit(this) { putBoolean("live_mode", false) }
        agent.stop()
        loopJob?.cancel(); loopJob = null
        listener.release()
        ear.release()
        DKState.set(Mode.IDLE)
        scope.launch { speaker.speak("ठीक है, माइक बंद।") }
    }

    /** Purana naam - ab live mode toggle karta hai. */
    fun listenAndRun() = toggleLive()

    /** Text command (app ki likhne wali bar se). */
    fun runCommand(text: String) { agent.run(text) }

    /** Settings save hone par: "hamesha suno (wake word)" mode chalu/band. */
    fun startWakeLoopIfEnabled() { ensureLoop() }

    private var loopJob: kotlinx.coroutines.Job? = null

    /** Bina beep wala tareeka chalega? (Groq ya Gemini key chahiye) */
    fun silentEar(): Boolean = Prefs.listenEngine(this) != "google" && Transcriber.available(this)

    /** Ek baat suno -> text. Bina beep (apna mic + Groq/Gemini) ya Google mic. */
    suspend fun hear(): String? {
        if (!silentEar()) {
            listener.lastSpokeAt = speaker.lastSpokeAt
            return listener.listenOnce()
        }
        // DK ki apni awaaz ki goonj na sune
        val since = System.currentTimeMillis() - speaker.lastSpokeAt
        if (since < 350) delay(350 - since)
        val pcm = ear.nextUtterance() ?: run { delay(800); return null }
        DKState.set(Mode.THINKING)
        DKState.user("…")
        val text = Transcriber.transcribe(this, pcm)
        if (text != null) DKState.user(text)
        return text
    }

    /** Ek hi sunne wala loop: live mode me har baat command hai, wake mode me sirf wake word ke baad. */
    private fun ensureLoop() {
        val wanted = DKState.live || Prefs.wakeMode(this)
        if (!wanted || loopJob?.isActive == true) return
        loopJob = scope.launch {
            DKLog.d("Sunna shuru (live=${DKState.live})")
            listener.muteBeep = true
            var misses = 0
            while (isActive && (DKState.live || Prefs.wakeMode(this@DKAccessibilityService))) {
                if (agent.isBusy || DKState.mode == Mode.SPEAKING) { delay(250); continue }
                DKState.set(Mode.LISTENING)
                val heard = hear()
                if (DKState.mode == Mode.LISTENING || DKState.mode == Mode.THINKING) DKState.set(Mode.IDLE)
                if (listener.lastError == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    DKState.reply("माइक की परमिशन नहीं है। ऐप खोलकर माइक की इजाज़त दीजिए।")
                    break
                }
                if (heard == null) {
                    misses++
                    delay(if (misses > 20) 600 else 150)   // chup hai to thoda aaram
                    continue
                }
                misses = 0
                val low = heard.lowercase().trim()

                if (DKState.live) {
                    if (low.trim(' ', '.', '।').length < 2) continue
                    if (low.contains("माइक बंद") || low.contains("सुनना बंद") || low.contains("mic off") ||
                        low.contains("stop listening") || low == "बंद करो") {
                        stopLive(); break
                    }
                    // gaana/video chal raha ho to sirf naam ("DK ...") ke saath boli baat command maano
                    val am = getSystemService(android.media.AudioManager::class.java)
                    val othersPlaying = am?.isMusicActive == true && System.currentTimeMillis() - speaker.lastSpokeAt > 2500
                    var cmdText = heard
                    if (othersPlaying) {
                        val w = Prefs.wakeWords(this@DKAccessibilityService).firstOrNull { low.contains(it) } ?: continue
                        cmdText = heard.substring((low.indexOf(w) + w.length).coerceAtMost(heard.length)).trim(' ', ',', '.', '!')
                        if (cmdText.isBlank()) continue
                    }
                    agent.run(cmdText).join()
                } else {
                    // wake word mode
                    val wake = Prefs.wakeWords(this@DKAccessibilityService).firstOrNull { low.contains(it) } ?: continue
                    var cmd = heard.substring((low.indexOf(wake) + wake.length).coerceAtMost(heard.length))
                        .trim(' ', ',', '.', '!')
                    if (cmd.isBlank()) {
                        speaker.speak("हाँ, बोलिए।")
                        DKState.set(Mode.LISTENING)
                        cmd = hear() ?: ""
                        DKState.set(Mode.IDLE)
                    }
                    if (cmd.isNotBlank()) agent.run(cmd).join()
                }
            }
            listener.muteBeep = false
            DKLog.d("Sunna band")
        }
    }

    // ---------------------------------------------------------------- screen reading (aankh)
    fun readScreen(): Screen {
        val list = mutableListOf<Element>()
        val sb = StringBuilder()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM
            }.forEach { w -> w.root?.let { roots.add(it) } }
        } catch (_: Exception) { }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }

        for (root in roots) walk(root, list, sb, 0)
        elements = list

        val dm = resources.displayMetrics
        val pkg = rootInActiveWindow?.packageName?.toString() ?: "unknown"
        return Screen(sb.toString(), pkg, dm.widthPixels, dm.heightPixels, list.size)
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<Element>, sb: StringBuilder, depth: Int) {
        if (out.size >= 140 || depth > 40) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val useful = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable ||
                node.isEditable || node.isScrollable || node.isLongClickable
            if (useful) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) {
                    val id = out.size
                    out.add(Element(id, node, r))
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
                    sb.append("[").append(id).append("] ").append(cls)
                    if (text.isNotEmpty()) sb.append(" \"").append(text.take(50)).append("\"")
                    if (desc.isNotEmpty() && desc != text) sb.append(" (").append(desc.take(50)).append(")")
                    if (flags.isNotEmpty()) sb.append(" {").append(flags).append("}")
                    sb.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, sb, depth + 1)
        }
    }

    /** Screenshot (Android 11+), chhota JPEG base64 me - AI ko "dikhane" ke liye. */
    suspend fun screenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val out = try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (bmp == null) null else {
                                val w = 540
                                val h = (bmp.height * (w.toFloat() / bmp.width)).toInt()
                                val small = Bitmap.createScaledBitmap(bmp, w, h, true)
                                val bos = ByteArrayOutputStream()
                                small.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                            }
                        } catch (e: Exception) { null }
                        if (cont.isActive) cont.resume(out)
                    }
                    override fun onFailure(errorCode: Int) {
                        DKLog.d("Screenshot fail: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    // ---------------------------------------------------------------- actions (haath)
    suspend fun perform(a: JSONObject): String {
        return try {
            when (a.optString("type")) {
                "open_app" -> AppLauncher.open(this, a.optString("name"))
                "youtube_search" -> AppLauncher.youtubeSearch(this, a.optString("query"))
                "web_search" -> AppLauncher.webSearch(this, a.optString("query"))
                "open_url" -> AppLauncher.openUrl(this, a.optString("url"))
                "dial" -> AppLauncher.dial(this, a.optString("number"))
                "click" -> click(a.optInt("id", -1))
                "long_click" -> longClick(a.optInt("id", -1))
                "type" -> typeText(if (a.has("id")) a.optInt("id", -1) else -1, a.optString("text"))
                "enter" -> pressEnter()
                "scroll" -> scroll(a.optString("direction", "down"))
                "tap" -> if (tap(a.optInt("x").toFloat(), a.optInt("y").toFloat())) "ok" else "ERROR tap"
                "swipe" -> if (swipe(
                        a.optInt("x1").toFloat(), a.optInt("y1").toFloat(),
                        a.optInt("x2").toFloat(), a.optInt("y2").toFloat(), 350
                    )) "ok" else "ERROR swipe"
                "back" -> global(GLOBAL_ACTION_BACK)
                "home" -> global(GLOBAL_ACTION_HOME)
                "recents" -> global(GLOBAL_ACTION_RECENTS)
                "notifications" -> global(GLOBAL_ACTION_NOTIFICATIONS)
                "quick_settings" -> global(GLOBAL_ACTION_QUICK_SETTINGS)
                "shop_search" -> AppLauncher.shopSearch(this, a.optString("app"), a.optString("query"))
                "torch" -> DeviceActions.torch(this, if (a.has("on")) a.optBoolean("on") else null)
                "open_settings" -> DeviceActions.openSettings(this, a.optString("page"))
                "panel" -> DeviceActions.panel(this, a.optString("name", "internet"))
                "volume" -> DeviceActions.volume(this, a.optString("direction", "up"), a.optInt("level", -1))
                "media" -> DeviceActions.media(this, a.optString("key", "play_pause"))
                "lock" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN) else "ERROR: Android 9+ chahiye"
                "screenshot_save" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT) else "ERROR"
                "power_menu" -> global(GLOBAL_ACTION_POWER_DIALOG)
                "unlock", "wake" -> unlock()
                "close_app" -> closeApp()
                "close_all_apps" -> closeAllApps()
                "wait" -> { delay(a.optLong("ms", 1000).coerceIn(100, 8000)); "waited" }
                else -> "ERROR: unknown action ${a.optString("type")}"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun element(id: Int): Element? = elements.getOrNull(id)

    private suspend fun click(id: Int): String {
        val e = element(id) ?: return "ERROR: id $id screen par nahi hai"
        var n: AccessibilityNodeInfo? = e.node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "clicked $id"
        return if (tap(e.bounds.exactCenterX(), e.bounds.exactCenterY())) "tapped $id" else "ERROR click $id"
    }

    private suspend fun longClick(id: Int): String {
        val e = element(id) ?: return "ERROR: id $id screen par nahi hai"
        var n: AccessibilityNodeInfo? = e.node
        while (n != null && !n.isLongClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "long clicked $id"
        val x = e.bounds.exactCenterX(); val y = e.bounds.exactCenterY()
        return if (swipe(x, y, x, y, 900)) "long pressed $id" else "ERROR long click $id"
    }

    private suspend fun typeText(id: Int, text: String): String {
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

    private fun pressEnter(): String {
        val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "ERROR: focused box nahi"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (ok) return "enter pressed"
        }
        return "ERROR: enter support nahi, search button click karo"
    }

    private suspend fun scroll(dir: String): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val ok = when (dir) {
            "down" -> swipe(w / 2, h * 0.72f, w / 2, h * 0.28f, 400)
            "up" -> swipe(w / 2, h * 0.28f, w / 2, h * 0.72f, 400)
            "left" -> swipe(w * 0.85f, h / 2, w * 0.15f, h / 2, 300)
            "right" -> swipe(w * 0.15f, h / 2, w * 0.85f, h / 2, 300)
            else -> false
        }
        return if (ok) "scrolled $dir" else "ERROR scroll"
    }

    /** Screen jagao; swipe-lock ho to khol do. PIN/pattern/fingerprint user khud dalega (suraksha). */
    @Suppress("DEPRECATION")
    private suspend fun unlock(): String {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) {
            try {
                val wl = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "dk:wake")
                wl.acquire(4000)
            } catch (e: Exception) { DKLog.d("wake: ${e.message}") }
            delay(700)
        }
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) return "screen on hai, phone pehle se unlocked hai"
        val dm = resources.displayMetrics
        swipe(dm.widthPixels / 2f, dm.heightPixels * 0.88f, dm.widthPixels / 2f, dm.heightPixels * 0.3f, 300)
        delay(700)
        return when {
            !km.isKeyguardLocked -> "phone unlock ho gaya"
            km.isKeyguardSecure -> "screen on, lock screen PIN/pattern/fingerprint maang rahi hai - user ko bolo ki khud khole (DK PIN nahi daalta)"
            else -> "lock screen khuli"
        }
    }

    /** Abhi wala app band karo: recents me card upar fenko. */
    private suspend fun closeApp(): String {
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
    private suspend fun closeAllApps(): String {
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

    private fun global(action: Int): String = if (performGlobalAction(action)) "ok" else "ERROR"

    private suspend fun tap(x: Float, y: Float): Boolean = swipe(x, y, x, y, 60)

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
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

    // ---------------------------------------------------------------- floating bubble
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (76 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - 20
            y = resources.displayMetrics.heightPixels / 3
        }
        val v = OrbView(this).apply { mini = true }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                    wm.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> if (!moved) toggleLive()
            }
            true
        }
        wm.addView(v, params)
        bubble = v
    }

    private fun removeBubble() {
        bubble?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        bubble = null
    }
}
