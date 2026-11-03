package com.dk.assistant.agent.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.DriverResult
import com.dk.assistant.agent.core.NodePick
import com.dk.assistant.agent.core.ScreenDriver
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import kotlinx.coroutines.delay
import com.dk.assistant.tap

/**
 * DK AI Part 12: असली फ़ोन पर ScreenDriver - Accessibility से.
 * सिर्फ़ सबसे ऊपर वाली ऐप की खिड़की (DK का अपना बुलबुला नहीं). हर capture का नया id;
 * पुरानी observation पर click = STALE (गलत चीज़ पर टैप नहीं).
 */
class AccessibilityScreenDriver(private val svc: DKAccessibilityService) : ScreenDriver {
    private var seq = 0L
    @Volatile private var currentId = -1L
    private var nodes: List<AccessibilityNodeInfo> = emptyList()
    private companion object { const val CAP = 320 }

    override suspend fun capture(): ScreenObservation? {
        val root = appRoot() ?: return null
        val dm = svc.resources.displayMetrics
        val raw = ArrayList<UiElement>(); val rawN = ArrayList<AccessibilityNodeInfo>(); val cands = ArrayList<NodePick.Cand>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            // v3.156: पूरी स्क्रीन (पहले 220 चीज़ / गहराई 45 पर रुकता था - लंबी chat में "भेजें" बटन छूट जाता था)
            if (n == null || raw.size >= NodePick.RAW_MAX || depth > NodePick.DEPTH_MAX) return
            if (n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val vid = n.viewIdResourceName.orEmpty()
                // v3.152: बड़ी तस्वीर (ChatGPT/Gemini की बनी इमेज) - बिना नाम/click के भी दिखे
                val bigImage = n.className?.toString()?.contains("Image") == true && r.width() >= 200 && r.height() >= 200
                if (r.width() > 0 && r.height() > 0 && (text.isNotEmpty() || desc.isNotEmpty() || vid.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable || bigImage)) {
                    raw += UiElement(raw.size, text.take(300), desc.take(200), vid, n.className?.toString()?.substringAfterLast('.').orEmpty(),
                        n.isClickable, n.isEditable, n.isScrollable, n.isEnabled, Box(r.left, r.top, r.right, r.bottom))
                    rawN += n
                    cands += NodePick.Cand(n.isEditable, n.isClickable, text.isNotEmpty() || desc.isNotEmpty(), r.top, r.bottom)
                }
            }
            for (i in 0 until n.childCount) walk(try { n.getChild(i) } catch (_: Exception) { null }, depth + 1)
        }
        walk(root, 0)
        // ज़्यादा हों तो ज़रूरी पहले: लिखने का खाना, बटन, नीचे का हिस्सा
        val keep = NodePick.keep(cands, CAP, dm.heightPixels)
        val list = ArrayList<UiElement>(keep.size); val ns = ArrayList<AccessibilityNodeInfo>(keep.size)
        keep.forEach { i -> list += raw[i].copy(id = list.size); ns += rawN[i] }
        val id = ++seq
        nodes = ns; currentId = id
        return ScreenObservation(id, root.packageName?.toString().orEmpty(), list, dm.widthPixels, dm.heightPixels)
    }

    /**
     * v3.156: सही खिड़की - ऊपर वाली ऐप (ChatGPT/Flipkart...), DK का अपना बुलबुला/पर्दा नहीं।
     * पहले सिर्फ़ rootInActiveWindow - DK की अपनी खिड़की active हो तो गलत स्क्रीन पढ़ी जाती थी।
     */
    private fun appRoot(): AccessibilityNodeInfo? {
        val own = svc.packageName
        val active = try { svc.rootInActiveWindow } catch (_: Exception) { null }
        if (active != null && active.packageName?.toString() != own) return active
        val apps = try {
            svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { w -> w.root?.takeIf { r -> r.packageName?.toString() != own }?.let { w to it } }
        } catch (_: Exception) { emptyList() }
        return (apps.firstOrNull { it.first.isFocused } ?: apps.maxByOrNull { it.first.layer })?.second ?: active
    }

    private fun node(obs: ScreenObservation, e: UiElement): AccessibilityNodeInfo? {
        if (obs.observationId != currentId) return null
        val n = nodes.getOrNull(e.id) ?: return null
        return if (try { n.refresh() } catch (_: Exception) { false }) n else null
    }

    override suspend fun click(obs: ScreenObservation, e: UiElement): DriverResult {
        var n = node(obs, e) ?: return DriverResult.STALE
        // खुद clickable नहीं तो पास का clickable माता-पिता
        var guard = 0
        while (!n.isClickable && guard++ < 6) n = n.parent ?: return DriverResult.FAILED
        return if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) DriverResult.OK else DriverResult.FAILED
    }

    override suspend fun tapAt(x: Int, y: Int): DriverResult = if (svc.tap(x.toFloat(), y.toFloat())) DriverResult.OK else DriverResult.FAILED

    override suspend fun setText(obs: ScreenObservation, e: UiElement, text: String): DriverResult {
        val n = node(obs, e) ?: return DriverResult.STALE
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return DriverResult.OK
        // v3.156: कुछ ऐप SET_TEXT नहीं मानते - clipboard से चिपकाओ (Paste)
        return try {
            val cm = svc.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DK", text))
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) DriverResult.OK else DriverResult.FAILED
        } catch (_: Exception) { DriverResult.FAILED }
    }

    override suspend fun scroll(down: Boolean): DriverResult {
        val n = nodes.firstOrNull { try { it.isScrollable } catch (_: Exception) { false } } ?: return DriverResult.NOT_FOUND
        val a = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (n.performAction(a)) DriverResult.OK else DriverResult.FAILED
    }

    override suspend fun back(): DriverResult =
        if (svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) DriverResult.OK else DriverResult.FAILED

    override suspend fun pause(ms: Long) = delay(ms)
}
