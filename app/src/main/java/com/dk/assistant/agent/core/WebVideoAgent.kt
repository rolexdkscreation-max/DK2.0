package com.dk.assistant.agent.core

import org.json.JSONObject

/**
 * 28 नवंबर 2026 (मालिक: "Google Flow ही नहीं - कोई भी वीडियो वेबसाइट; मैं खोलकर लॉग-इन करूँगा, लिंक दूँगा, DK वहाँ वीडियो बनाए").
 * हर वेबसाइट अलग है - इसलिए नियम नहीं, DK की AI हर कदम पर स्क्रीन (चीज़ों की सूची + तस्वीर, अगर स्क्रीन देखना चालू हो) देखकर
 * तय करती है: दबाओ / लिखो / scroll / रुको / वापस / हो गया / लॉग-इन चाहिए. pure हिस्सा (JUnit से जाँचा जाता है).
 */
data class WebAct(
    val kind: String,
    val id: Int = -1,
    /** 0-1000 के पैमाने पर (स्क्रीन की तस्वीर से) */
    val x: Int = -1,
    val y: Int = -1,
    val text: String = "",
    val seconds: Int = 0,
    val why: String = "",
) {
    companion object {
        val KINDS = setOf("tap", "type", "enter", "scroll_down", "scroll_up", "wait", "back", "done", "login", "fail")
    }
}

data class WebRunResult(
    val ok: Boolean,
    val file: String? = null,
    /** लॉग-इन / CAPTCHA - मालिक खुद करे, फिर "आगे बढ़ो" */
    val needUser: Boolean = false,
    val limit: Boolean = false,
    val msg: String = "",
    val steps: Int = 0,
)

object WebAgentCore {
    /** pure: स्क्रीन की चीज़ें AI के लिए - "[id] Button* 'Generate' @x,y (0-1000)" */
    fun elements(o: ScreenObservation, max: Int = 140): String = o.elements
        .filter { it.label.isNotBlank() || it.clickable || it.editable }
        .take(max)
        .joinToString("\n") { e ->
            val fx = if (o.width > 0) e.bounds.centerX * 1000 / o.width else 0
            val fy = if (o.height > 0) e.bounds.centerY * 1000 / o.height else 0
            val flags = (if (e.clickable) "*" else "") + (if (e.editable) " INPUT" else "") + (if (!e.enabled) " disabled" else "")
            "[${e.id}] ${e.cls.ifBlank { "View" }}$flags '${e.label.take(80)}' @$fx,$fy"
        }

    /** pure: अगले कदम का सवाल */
    fun stepAsk(goal: String, o: ScreenObservation, history: List<String>, step: Int, maxSteps: Int, withShot: Boolean): String =
        """You are DK, an agent operating a real Android phone browser for the owner. GOAL: $goal
The owner is already logged in himself. You see the current screen as a list of UI elements${if (withShot) " and a screenshot" else ""}.
Coordinates are on a 0-1000 scale (x from left, y from top). Step ${step + 1} of at most $maxSteps.
Rules:
- Prefer element ids from the list. If the needed thing is not in the list but visible in the screenshot, give x,y (0-1000).
- To write the prompt: tap the prompt box first if needed, then "type" with the FULL prompt text (it replaces what is there).
- Close pop-ups / cookie banners / tours that block the way. Pick video (not image) mode, 16:9 and the longest length up to about 8-10 seconds if such options exist; keep other defaults.
- After pressing Generate/Create, use "wait" (10-30 seconds) again and again until the video is fully ready (no progress / percentage / spinner).
- Then download it (Download button, or ⋮ / ... / share menu -> Download, choose the best free quality). When the download has started or finished, answer "done".
- If a login / sign-up / CAPTCHA / OTP page appears, answer "login" (never type passwords). If the site says no credits / limit / upgrade needed to make a video, answer "fail" with that reason in "why" starting with LIMIT:.
- Never buy, pay, subscribe or delete anything.
Previous steps: ${history.takeLast(10).joinToString(" | ").ifBlank { "none" }}
Screen (package ${o.pkg}):
${elements(o)}
Return ONLY JSON: {"action":"tap|type|enter|scroll_down|scroll_up|wait|back|done|login|fail","id":12,"x":-1,"y":-1,"text":"","seconds":0,"why":"short reason"}"""

    /** pure: AI का जवाब -> कदम (टूटा = null) */
    fun parse(text: String?): WebAct? {
        val s = text?.trim().orEmpty().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val o = try { JSONObject(s.substring(a, b + 1)) } catch (_: Exception) { return null }
        val k = o.optString("action").trim().lowercase().replace(' ', '_').let {
            when (it) { "click", "press" -> "tap"; "input", "write" -> "type"; "scroll" -> "scroll_down"; "finish", "finished", "complete" -> "done"; else -> it }
        }
        if (k !in WebAct.KINDS) return null
        return WebAct(k, o.optInt("id", -1), o.optInt("x", -1), o.optInt("y", -1), o.optString("text"), o.optInt("seconds", 0), o.optString("why").take(200))
    }
}

/**
 * वेबसाइट पर एक वीडियो: [think] = DK की AI (स्क्रीन की तस्वीर Android हिस्सा खुद जोड़ता है), [newVideo] = फ़ोन में start के बाद आया वीडियो.
 */
class WebVideoRun(
    private val d: ScreenDriver,
    private val think: suspend (String) -> String?,
    private val newVideo: suspend (Long) -> String?,
    private val now: () -> Long,
    private val withShot: () -> Boolean = { false },
    private val enter: suspend () -> Boolean = { false },
    private val maxSteps: Int = 70,
    private val maxMs: Long = 15 * 60_000L,
    private val onStep: (String) -> Unit = {},
) {
    suspend fun run(goal: String): WebRunResult {
        val start = now()
        val history = mutableListOf<String>()
        var bad = 0
        for (step in 0 until maxSteps) {
            if (now() - start > maxMs) return WebRunResult(false, msg = "${maxMs / 60_000} मिनट में वीडियो नहीं बना", steps = step)
            newVideo(start)?.let { return WebRunResult(true, file = it, msg = "वीडियो डाउनलोड हो गया", steps = step) }
            val o = d.capture()
            if (o == null) { d.pause(1_500); continue }
            when (BlockerDetector.detect(o)) {
                Blocker.LOGIN, Blocker.CAPTCHA, Blocker.TWO_FACTOR, Blocker.SECURITY_CHECK ->
                    return WebRunResult(false, needUser = true, msg = "वेबसाइट पर लॉग-इन / जाँच चाहिए", steps = step)
                else -> Unit
            }
            val act = WebAgentCore.parse(think(WebAgentCore.stepAsk(goal, o, history, step, maxSteps, withShot())))
            if (act == null) {
                bad++
                if (bad >= 4) return WebRunResult(false, msg = "DK की AI से जवाब नहीं मिला", steps = step)
                d.pause(2_000)
                continue
            }
            bad = 0
            history += "${act.kind}${if (act.id >= 0) "#${act.id}" else ""}${if (act.text.isNotBlank()) " '${act.text.take(30)}'" else ""} (${act.why.take(60)})"
            onStep(act.why.ifBlank { act.kind })
            when (act.kind) {
                "done" -> {
                    for (i in 0 until 30) { newVideo(start)?.let { return WebRunResult(true, file = it, msg = "वीडियो डाउनलोड हो गया", steps = step) }; d.pause(1_000) }
                    return WebRunResult(true, file = ImageGenFlow.UNVERIFIED, msg = "डाउनलोड दबाया, फ़ोन में पक्का नहीं दिखा", steps = step)
                }
                "login" -> return WebRunResult(false, needUser = true, msg = act.why.ifBlank { "लॉग-इन चाहिए" }, steps = step)
                "fail" -> return WebRunResult(false, limit = act.why.startsWith("LIMIT", true), msg = act.why.removePrefix("LIMIT:").trim().ifBlank { "वेबसाइट पर काम नहीं हो पाया" }, steps = step)
                "wait" -> d.pause(act.seconds.coerceIn(3, 30) * 1_000L)
                "back" -> { d.back(); d.pause(1_200) }
                "scroll_down", "scroll_up" -> { d.scroll(act.kind == "scroll_down"); d.pause(900) }
                "enter" -> { enter(); d.pause(1_200) }
                "tap" -> { tap(o, act); d.pause(1_500) }
                "type" -> {
                    val el = o.elements.getOrNull(act.id)?.takeIf { it.id == act.id } ?: o.elements.firstOrNull { it.editable }
                    if (el == null || act.text.isBlank()) { tap(o, act) } else {
                        if (!el.editable) { d.click(o, el); d.pause(800) }
                        val o2 = if (el.editable) o else d.capture() ?: o
                        val box = if (el.editable) el else o2.elements.firstOrNull { it.editable } ?: el
                        d.setText(o2, box, act.text)
                    }
                    d.pause(900)
                }
            }
        }
        return WebRunResult(false, msg = "$maxSteps कदम में वीडियो नहीं बना", steps = maxSteps)
    }

    private suspend fun tap(o: ScreenObservation, a: WebAct) {
        val el = o.elements.getOrNull(a.id)?.takeIf { a.id >= 0 && it.id == a.id }
        if (el != null) {
            if (d.click(o, el) != DriverResult.OK) d.tapAt(el.bounds.centerX, el.bounds.centerY)
            return
        }
        if (a.x in 0..1000 && a.y in 0..1000) d.tapAt(a.x * o.width / 1000, a.y * o.height / 1000)
    }
}
