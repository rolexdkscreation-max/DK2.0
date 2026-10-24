package com.dk.assistant.agent.core

/** फ़ोन की स्क्रीन पर काम - असली (Accessibility) या test वाला (FakeScreenDriver) */
interface ScreenDriver {
    /** अभी की स्क्रीन; हर बार नया observationId */
    suspend fun capture(): ScreenObservation?
    /** obs पुरानी हो तो STALE */
    suspend fun click(obs: ScreenObservation, e: UiElement): DriverResult
    suspend fun tapAt(x: Int, y: Int): DriverResult
    suspend fun setText(obs: ScreenObservation, e: UiElement, text: String): DriverResult
    suspend fun scroll(down: Boolean): DriverResult
    suspend fun back(): DriverResult
    suspend fun pause(ms: Long)
}

/** काम के बाद क्या दिखना चाहिए */
sealed class Expectation {
    object Anything : Expectation()
    data class TextAppears(val words: List<String>) : Expectation()
    data class TargetGone(val spec: TargetSpec) : Expectation()
    data class ScreenChanged(val beforeSignature: String) : Expectation()
    data class Either(val a: Expectation, val b: Expectation) : Expectation()
    data class Both(val a: Expectation, val b: Expectation) : Expectation()

    fun met(obs: ScreenObservation): Boolean = when (this) {
        Anything -> true
        is TextAppears -> obs.hasText(*words.toTypedArray())
        is TargetGone -> TargetResolver.resolve(obs, spec) == null
        is ScreenChanged -> signature(obs) != beforeSignature
        is Either -> a.met(obs) || b.met(obs)
        is Both -> a.met(obs) && b.met(obs)
    }

    companion object {
        fun signature(obs: ScreenObservation): String =
            obs.pkg + "|" + obs.elements.take(60).joinToString("|") { it.label + "@" + it.bounds.top / 40 }
    }
}

/**
 * एक टैप: ढूँढो -> जाँचो -> accessibility click -> (नहीं हुआ तो) element की जगह पर gesture -> इंतज़ार -> पक्का करो.
 * हर कदम से पहले blocker (लॉग-इन/CAPTCHA/2FA/इजाज़त) देखो - मिला तो रुक जाओ.
 */
class UiActionExecutor(private val d: ScreenDriver, private val settleMs: Long = 700, private val verifyTries: Int = 4) {

    suspend fun observe(): ActionResult {
        val o = d.capture() ?: return ActionResult.Failed(FailureReason.NO_SCREEN)
        BlockerDetector.detect(o)?.let { return ActionResult.Blocked(it) }
        return ActionResult.Success(ActionMethod.ACCESSIBILITY_CLICK, o)
    }

    /** target मिलने तक थोड़ा रुको / नीचे scroll करो */
    suspend fun find(spec: TargetSpec, scrolls: Int = 0, waits: Int = 3): Pair<ScreenObservation, UiElement>? {
        var left = scrolls
        repeat(waits + scrolls + 1) {
            val o = d.capture() ?: return null
            if (BlockerDetector.detect(o) != null) return null
            TargetResolver.resolve(o, spec)?.let { return o to it }
            if (left > 0) { left--; d.scroll(true) }
            d.pause(settleMs)
        }
        return null
    }

    suspend fun tap(spec: TargetSpec, expect: Expectation = Expectation.Anything, scrolls: Int = 0): ActionResult {
        when (val pre = observe()) { is ActionResult.Blocked, is ActionResult.Failed -> return pre; else -> {} }
        var (obs, el) = find(spec, scrolls) ?: return blockedOr(ActionResult.Failed(FailureReason.TARGET_NOT_FOUND, spec.name))
        val sig = Expectation.signature(obs)
        var r = d.click(obs, el)
        var method = ActionMethod.ACCESSIBILITY_CLICK
        if (r == DriverResult.STALE) {
            // स्क्रीन बदल गई - एक बार नए सिरे से ढूँढो
            val again = find(spec, 0, 1) ?: return ActionResult.Failed(FailureReason.TARGET_NOT_FOUND, spec.name)
            obs = again.first; el = again.second
            r = d.click(obs, el)
        }
        if (r != DriverResult.OK) {
            method = ActionMethod.GESTURE_ON_ELEMENT_BOUNDS
            r = d.tapAt(el.bounds.centerX, el.bounds.centerY)
        }
        if (r != DriverResult.OK) return ActionResult.Failed(FailureReason.ACTION_FAILED, spec.name)
        val want = if (expect is Expectation.Anything) Expectation.ScreenChanged(sig) else expect
        return verify(want, method)
    }

    suspend fun type(spec: TargetSpec, text: String): ActionResult {
        when (val pre = observe()) { is ActionResult.Blocked, is ActionResult.Failed -> return pre; else -> {} }
        val (obs, el) = find(spec.copy(editable = true)) ?: return blockedOr(ActionResult.Failed(FailureReason.TARGET_NOT_FOUND, spec.name))
        var r = d.setText(obs, el, text)
        if (r == DriverResult.STALE) {
            val again = find(spec.copy(editable = true), 0, 1) ?: return ActionResult.Failed(FailureReason.TARGET_NOT_FOUND, spec.name)
            r = d.setText(again.first, again.second, text)
        }
        if (r != DriverResult.OK) return ActionResult.Failed(FailureReason.ACTION_FAILED, spec.name)
        // पक्का करो कि लिखा हुआ दिख रहा है (लंबा हो तो शुरू का हिस्सा)
        val probe = text.trim().take(24)
        return verify(if (probe.isEmpty()) Expectation.Anything else Expectation.TextAppears(listOf(probe)), ActionMethod.SET_TEXT)
    }

    suspend fun back(): ActionResult =
        if (d.back() == DriverResult.OK) { d.pause(settleMs); ActionResult.Success(ActionMethod.GLOBAL_BACK, d.capture()) }
        else ActionResult.Failed(FailureReason.ACTION_FAILED, "back")

    suspend fun verify(expect: Expectation, method: ActionMethod): ActionResult {
        repeat(verifyTries) {
            d.pause(settleMs)
            val o = d.capture() ?: return@repeat
            BlockerDetector.detect(o)?.let { b -> return ActionResult.Blocked(b) }
            if (expect.met(o)) return ActionResult.Success(method, o)
        }
        return ActionResult.Failed(FailureReason.NOT_VERIFIED)
    }

    private suspend fun blockedOr(r: ActionResult): ActionResult {
        val o = d.capture() ?: return r
        return BlockerDetector.detect(o)?.let { ActionResult.Blocked(it) } ?: r
    }
}
