package com.dk.assistant

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * नवंबर 2026: लॉक स्क्रीन पर मालिक का सेव किया PIN / पैटर्न / पासवर्ड डालना (Settings → फ़ोन लॉक / अनलॉक)।
 *
 * - सिर्फ़ तब जब मालिक ने खुद \"आवाज़ से अनलॉक\" चालू करके PIN सेव किया हो ([PhoneLockStore.canEnter])।
 * - एक बार में एक ही कोशिश; न खुले तो दोबारा नहीं डालता। लगातार [PhoneLockStore.MAX_FAILS] बार न खुले = रुक जाता है
 *   (वरना Android फ़ोन को लंबे समय के लिए बंद कर देता है)। नया PIN सेव करने पर फिर चालू।
 * - PIN log / AI / स्क्रीन पर कहीं नहीं।
 */
internal object PhoneUnlocker {
    const val FAIL = PhoneLockVoice.UNLOCK_FAIL

    /**
     * नवंबर 2026: एक समय में एक ही अनलॉक। पहले Live में तुरंत वाला रास्ता और Gemini का phone_task दोनों साथ
     * PIN डालते थे - अंक आपस में मिल जाते (गलत PIN), गिनती बढ़ती और आवाज़ से अनलॉक रुक जाता।
     */
    val gate = Mutex()

    /**
     * PIN दबाने के बीच। v3.151: 45ms पर कई फ़ोन (Samsung / Xiaomi) बीच का अंक छोड़ देते थे = गलत PIN, गिनती बढ़ी और
     * दो बार में आवाज़ से अनलॉक रुक गया। 60ms (पहले 90, फिर 45) - 6 अंक का PIN भी आधे सेकंड से कम।
     */
    const val KEY_GAP_MS = 60L
    /** PIN वाला हिस्सा दिखते ही बटन अभी खिसक रहे होते हैं (animation) - पहला अंक छूट जाता था */
    const val SETTLE_MS = 180L
    /** PIN वाला हिस्सा दिखने तक देखने का अंतर */
    const val POLL_MS = 100L
    /** v4.8: PIN वाला हिस्सा न आए तो कितनी बार और ऊपर swipe */
    const val SWIPE_TRIES = 3

    private fun nodes(svc: DKAccessibilityService): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40) return
            out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        svc.allRoots().forEach { walk(it, 0) }
        return out
    }

    private class Screen(
        val digits: Map<Char, AccessibilityNodeInfo>,
        val enter: AccessibilityNodeInfo?,
        val pattern: AccessibilityNodeInfo?,
        val edit: AccessibilityNodeInfo?,
    )

    private fun look(svc: DKAccessibilityService): Screen {
        val digits = HashMap<Char, AccessibilityNodeInfo>()
        val byId = HashMap<Char, AccessibilityNodeInfo>()      // "…:id/key5" वाले पक्के - लिखावट वाले (घड़ी, गिनती) से ऊपर
        var enter: AccessibilityNodeInfo? = null; var pattern: AccessibilityNodeInfo? = null; var edit: AccessibilityNodeInfo? = null
        for (n in nodes(svc)) {
            if (!n.isVisibleToUser) continue
            val id = n.viewIdResourceName
            if (pattern == null && PhoneLockVoice.patternView(n.className, id)) pattern = n
            if (edit == null && n.isEditable) edit = n
            if (enter == null && PhoneLockVoice.enterKey(id, n.text, n.contentDescription)) enter = n
            PhoneLockVoice.digitKey(id, n.text, n.contentDescription)?.let { d ->
                if (PhoneLockVoice.digitKey(id, null, null) != null) { if (d !in byId) byId[d] = n }
                else if (d !in digits && (n.isClickable || n.parent?.isClickable == true)) digits[d] = n
            }
        }
        return Screen(digits + byId, enter, pattern, edit)
    }

    private suspend fun press(svc: DKAccessibilityService, n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n; var depth = 0
        while (c != null && !c.isClickable && depth < 3) { c = c.parent; depth++ }
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); n.getBoundsInScreen(r)
        return !r.isEmpty && svc.tap(r.exactCenterX(), r.exactCenterY())
    }

    private suspend fun stroke(svc: DKAccessibilityService, pts: List<Pair<Float, Float>>): Boolean {
        if (pts.size < 2) return false
        val p = Path().apply { moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { lineTo(it.first, it.second) } }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 140L * pts.size)).build()
        return suspendCancellableCoroutine { cont ->
            val ok = svc.dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    private fun ready(kind: PhoneLockVoice.Kind, s: Screen) = when (kind) {
        PhoneLockVoice.Kind.PIN -> s.digits.size >= 10 || s.edit != null
        PhoneLockVoice.Kind.PATTERN -> s.pattern != null
        PhoneLockVoice.Kind.PASSWORD -> s.edit != null
    }

    /** v4.8: PIN / पैटर्न / पासवर्ड डालने की जगह अभी स्क्रीन पर है? (तब ऊपर swipe नहीं - वरना बंद हो सकती है) */
    fun bouncerUp(svc: DKAccessibilityService): Boolean = try {
        val s = look(svc)
        val kind = PhoneLockStore.kind(svc)
        if (kind != null) ready(kind, s) else s.digits.size >= 10 || s.pattern != null || s.edit != null
    } catch (_: Exception) { false }

    /** v4.8: फ़ेस / फ़िंगरप्रिंट वाली स्क्रीन पर "PIN इस्तेमाल करें" जैसा बटन - दबाओ ताकि डालने की जगह आए */
    private fun tapUseSecret(svc: DKAccessibilityService): Boolean =
        svc.clickByText(*PhoneLockVoice.USE_SECRET_WORDS.toTypedArray())

    /** PIN वाला हिस्सा / पैटर्न / पासवर्ड का खाना आने तक (ऊपर swipe के बाद) */
    private suspend fun waitBouncer(svc: DKAccessibilityService, kind: PhoneLockVoice.Kind, tries: Int = 25): Screen? {
        repeat(tries) { i ->
            val s = look(svc)
            if (ready(kind, s)) return s
            if (i == 8) tapUseSecret(svc)
            delay(POLL_MS)
        }
        return null
    }

    private suspend fun opened(km: KeyguardManager): Boolean {
        repeat(30) { if (!km.isKeyguardLocked) return true; delay(POLL_MS) }
        return !km.isKeyguardLocked
    }

    /**
     * लॉक स्क्रीन ऊपर swipe हो चुकी / Android ने PIN वाला हिस्सा खोल दिया ([UnlockWakeActivity]), फ़ोन अब भी बंद →
     * सेव किया PIN डालो। asked = Android से PIN वाला हिस्सा माँगा जा चुका (तब पहले थोड़ा कम इंतज़ार, फिर swipe)।
     */
    suspend fun enter(svc: DKAccessibilityService, km: KeyguardManager, asked: Boolean = false): String {
        val kind = PhoneLockStore.kind(svc) ?: return "$FAIL PIN सेव नहीं है। Settings → फ़ोन लॉक / अनलॉक में डालिए।"
        if (PhoneLockStore.fails(svc) >= PhoneLockStore.MAX_FAILS)
            return "$FAIL पिछली ${PhoneLockStore.MAX_FAILS} बार फ़ोन नहीं खुला, इसलिए मैंने ${kind.hi} डालना रोक दिया है। Settings में ${kind.hi} दोबारा सेव कीजिए।"
        val secret = PhoneLockStore.secret(svc) ?: return "$FAIL सेव किया ${kind.hi} खुल नहीं पाया। Settings में दोबारा सेव कीजिए।"
        var s = waitBouncer(svc, kind, if (asked) 15 else 25)
        if (s == null && !km.isKeyguardLocked) return "phone unlock ho gaya"
        // कुछ फ़ोन: पहली swipe से सिर्फ़ घड़ी हटती है / PIN वाला हिस्सा खुद नहीं खुला.
        // v4.8: एक नहीं, [SWIPE_TRIES] बार ऊपर swipe (हर बार डालने की जगह आने तक रुककर)
        var tries = 0
        while (s == null && km.isKeyguardLocked && tries++ < SWIPE_TRIES) {
            val dm = svc.resources.displayMetrics
            svc.swipe(dm.widthPixels / 2f, dm.heightPixels * 0.85f, dm.widthPixels / 2f, dm.heightPixels * 0.25f, 250)
            s = waitBouncer(svc, kind)
        }
        if (s == null && !km.isKeyguardLocked) return "phone unlock ho gaya"
        // PIN डलना शुरू = पूरा डलेगा (बीच में रद्द होकर आधा PIN न छूटे - अगली कोशिश में अंक जुड़कर गलत हो जाते)
        val first: Screen = s ?: return "$FAIL लॉक स्क्रीन पर ${kind.hi} डालने की जगह नहीं मिली। फ़ोन खुद खोलिए।"
        // v3.151: animation रुकने दो, फिर बटन ताज़ा पढ़ो (पहला अंक छूटकर गलत PIN न बने)
        delay(SETTLE_MS)
        if (!km.isKeyguardLocked) return "phone unlock ho gaya"
        val screen: Screen = look(svc).takeIf { ready(kind, it) } ?: first
        return withContext(NonCancellable) { type(svc, km, kind, secret, screen) }
    }

    private suspend fun type(svc: DKAccessibilityService, km: KeyguardManager, kind: PhoneLockVoice.Kind, secret: String, s: Screen): String {
        var typed = false
        when (kind) {
            PhoneLockVoice.Kind.PIN -> {
                if (s.digits.size >= 10) {
                    typed = true
                    for (d in secret) { val n = s.digits[d]; if (n == null || !press(svc, n)) { typed = false; break }; delay(KEY_GAP_MS) }
                } else s.edit?.let { typed = setText(it, secret) }
            }
            PhoneLockVoice.Kind.PASSWORD -> s.edit?.let { typed = setText(it, secret) }
            PhoneLockVoice.Kind.PATTERN -> s.pattern?.let { pv ->
                val r = Rect(); pv.getBoundsInScreen(r)
                if (!r.isEmpty) typed = stroke(svc, secret.map { PhoneLockVoice.cell(it, r.left, r.top, r.right, r.bottom) })
            }
        }
        if (!typed) return "$FAIL ${kind.hi} पूरा नहीं डल पाया। फ़ोन खुद खोलिए।"
        // कई फ़ोन PIN सही होते ही खुद खुलते हैं; न खुले तो Enter / OK
        if (kind != PhoneLockVoice.Kind.PATTERN && km.isKeyguardLocked) {
            delay(200)
            if (km.isKeyguardLocked) {
                val en = look(svc).enter
                val ime = if (Build.VERSION.SDK_INT >= 30) AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id else 0
                if (en != null) press(svc, en)
                else if (ime != 0) look(svc).edit?.performAction(ime)
            }
        }
        if (opened(km)) { PhoneLockStore.worked(svc); DKLog.d("फ़ोन लॉक: आवाज़ से खुला (${kind.id})"); return "phone unlock ho gaya" }
        PhoneLockStore.failed(svc)
        DKLog.d("फ़ोन लॉक: ${kind.id} डाला पर फ़ोन नहीं खुला (${PhoneLockStore.fails(svc)}/${PhoneLockStore.MAX_FAILS})")
        return "$FAIL ${kind.hi} डालने पर भी फ़ोन नहीं खुला। हो सकता है फ़ोन का ${kind.hi} बदल गया हो, Settings में नया सेव कीजिए।"
    }

    private fun setText(n: AccessibilityNodeInfo, t: String): Boolean {
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }
}
