package com.dk.assistant.plus

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.tap
import kotlinx.coroutines.delay

/**
 * 15 नवंबर 2026: [RetryLoop] का Accessibility जोड़. "बटन दबाओ और पक्का करो कि दबा":
 *  1) view-id से node क्लिक  2) text / contentDescription से क्लिक  3) उसी node के bounds के बीच में असली टैप
 *  4) (round बदलते समय) scroll करके फिर से - फिर हर बार स्क्रीन दोबारा पढ़कर [expect] जाँचा जाता है.
 */
object A11yRetry {

    /** @param expect क्लिक के बाद स्क्रीन पर यह text दिखे (null = जिस text पर क्लिक किया वह गायब हो / स्क्रीन बदले) */
    suspend fun click(svc: DKAccessibilityService, target: String, viewId: String? = null, expect: String? = null): RetryLoop.Result {
        val before = screenSig(svc)
        fun root() = svc.rootInActiveWindow
        fun byId(): AccessibilityNodeInfo? = viewId?.let { id -> root()?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() }
        fun byText(): AccessibilityNodeInfo? = root()?.let { find(it, target) }
        fun clickable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? { var x = n; while (x != null && !x.isClickable) x = x.parent; return x }

        val strategies = buildList {
            if (viewId != null) add(RetryLoop.Strategy("node-id") { clickable(byId())?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true })
            add(RetryLoop.Strategy("text") { clickable(byText())?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true })
            add(RetryLoop.Strategy("टैप") {
                val n = byId() ?: byText() ?: return@Strategy false
                val r = Rect(); n.getBoundsInScreen(r)
                !r.isEmpty && svc.tap(r.exactCenterX(), r.exactCenterY())
            })
        }
        return RetryLoop.run(
            strategies,
            verify = {
                val now = root()
                when {
                    now == null -> false
                    expect != null -> find(now, expect) != null
                    else -> screenSig(svc) != before
                }
            },
            rounds = 3,
            settle = { delay(700) },
            between = { scrollForward(svc); delay(600) },
        )
    }

    private fun find(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByText(text)?.firstOrNull()?.let { return it }
        // contentDescription वाले (icon) बटन findByText में हमेशा नहीं आते - खुद ढूँढो
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root); var seen = 0
        while (q.isNotEmpty() && seen < 800) {
            val n = q.removeFirst(); seen++
            if (n.contentDescription?.toString()?.contains(text, true) == true) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun scrollForward(svc: DKAccessibilityService) {
        val q = ArrayDeque<AccessibilityNodeInfo>(); svc.rootInActiveWindow?.let { q.add(it) }; var seen = 0
        while (q.isNotEmpty() && seen < 800) {
            val n = q.removeFirst(); seen++
            if (n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
    }

    // ---- 15 नवंबर 2026 (हिस्सा 1, टूल एजेंट): अलग-अलग तरीकों के लिए छोटे सार्वजनिक हिस्से (ऊपर का click वैसा ही)

    /** text / contentDescription वाले node के bounds के बीच में असली टैप (coordinates वाला तरीका) */
    suspend fun tapBounds(svc: DKAccessibilityService, text: String): Boolean {
        val root = svc.rootInActiveWindow ?: return false
        val n = find(root, text) ?: return false
        val r = Rect(); n.getBoundsInScreen(r)
        return !r.isEmpty && svc.tap(r.exactCenterX(), r.exactCenterY())
    }

    /** स्क्रीन पर पहला on/off स्विच (Wi-Fi / Bluetooth / एयरप्लेन पैनल) - [label] मिले तो उसी पंक्ति वाला */
    fun tapSwitch(svc: DKAccessibilityService, label: String = ""): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>(); svc.rootInActiveWindow?.let { q.add(it) }; var seen = 0
        var first: AccessibilityNodeInfo? = null
        while (q.isNotEmpty() && seen < 800) {
            val n = q.removeFirst(); seen++
            val cls = n.className?.toString().orEmpty()
            if (n.isCheckable && (cls.contains("Switch") || cls.contains("Toggle"))) {
                if (first == null) first = n
                val row = n.parent
                val txt = (row?.let { rowText(it) } ?: "") + " " + (n.text ?: "") + " " + (n.contentDescription ?: "")
                if (label.isBlank() || txt.contains(label, true)) { var x: AccessibilityNodeInfo? = n; while (x != null && !x.isClickable) x = x.parent; if (x?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        val f = first ?: return false
        var x: AccessibilityNodeInfo? = f; while (x != null && !x.isClickable) x = x.parent
        return x?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun rowText(n: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> c.text?.let { sb.append(it).append(' ') }; c.contentDescription?.let { sb.append(it).append(' ') } }
        return sb.toString()
    }

    /** स्क्रीन बदली या नहीं - जाँच के लिए */
    fun signature(svc: DKAccessibilityService): String = screenSig(svc)

    /** स्क्रीन की छोटी पहचान: package + पहले 40 text - बदली या नहीं */
    private fun screenSig(svc: DKAccessibilityService): String {
        val r = svc.rootInActiveWindow ?: return ""
        val sb = StringBuilder(r.packageName ?: ""); val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(r); var n = 0
        while (q.isNotEmpty() && n < 40) { val x = q.removeFirst(); x.text?.let { sb.append('|').append(it); n++ }; for (i in 0 until x.childCount) x.getChild(i)?.let { q.add(it) } }
        return sb.toString()
    }
}
