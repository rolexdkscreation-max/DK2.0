package com.dk.assistant.agent.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.DriverResult
import com.dk.assistant.agent.core.ScreenDriver
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import kotlinx.coroutines.delay

/**
 * DK AI Part 12: असली फ़ोन पर ScreenDriver - Accessibility से.
 * सिर्फ़ सबसे ऊपर वाली ऐप की खिड़की (DK का अपना बुलबुला नहीं). हर capture का नया id;
 * पुरानी observation पर click = STALE (गलत चीज़ पर टैप नहीं).
 */
class AccessibilityScreenDriver(private val svc: DKAccessibilityService) : ScreenDriver {
    private var seq = 0L
    @Volatile private var currentId = -1L
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override suspend fun capture(): ScreenObservation? {
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return null
        val list = ArrayList<UiElement>(); val ns = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || list.size >= 220 || depth > 45) return
            if (n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val vid = n.viewIdResourceName.orEmpty()
                if (r.width() > 0 && r.height() > 0 && (text.isNotEmpty() || desc.isNotEmpty() || vid.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable)) {
                    list += UiElement(list.size, text.take(300), desc.take(200), vid, n.className?.toString()?.substringAfterLast('.').orEmpty(),
                        n.isClickable, n.isEditable, n.isScrollable, n.isEnabled, Box(r.left, r.top, r.right, r.bottom))
                    ns += n
                }
            }
            for (i in 0 until n.childCount) walk(try { n.getChild(i) } catch (_: Exception) { null }, depth + 1)
        }
        walk(root, 0)
        val dm = svc.resources.displayMetrics
        val id = ++seq
        nodes = ns; currentId = id
        return ScreenObservation(id, root.packageName?.toString().orEmpty(), list, dm.widthPixels, dm.heightPixels)
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
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) DriverResult.OK else DriverResult.FAILED
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
