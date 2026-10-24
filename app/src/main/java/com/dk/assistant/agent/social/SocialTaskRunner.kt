package com.dk.assistant.agent.social

import com.dk.assistant.agent.core.ActionResult
import com.dk.assistant.agent.core.Blocker
import com.dk.assistant.agent.core.BlockerDetector
import com.dk.assistant.agent.core.ExecPhase
import com.dk.assistant.agent.core.Expectation
import com.dk.assistant.agent.core.FailureReason
import com.dk.assistant.agent.core.ScreenDriver
import com.dk.assistant.agent.core.TaskStateMachine
import com.dk.assistant.agent.core.UiActionExecutor
import kotlinx.coroutines.channels.Channel

/**
 * एक social काम चलाओ: app खोलो -> हर कदम (देखो, ढूँढो, जाँचो, करो, पक्का करो) -> आख़िरी टैप से पहले "हाँ".
 * लॉग-इन / CAPTCHA / 2FA / इजाज़त = रुककर यूज़र का इंतज़ार (DK खुद नहीं भरता).
 * पक्का न हो पाए कि पोस्ट हुई = SUBMITTED_UNVERIFIED (कभी झूठा "हो गया" नहीं).
 * caption / संदेश कभी log नहीं होते.
 */
class SocialTaskRunner(
    private val driver: ScreenDriver,
    private val opener: suspend (OpenSpec) -> Boolean,
    private val onUpdate: (TaskReport) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val confirmWaitMs: Long = 180_000,
    private val userWaitMs: Long = 180_000,
    private val pollMs: Long = 500,
    settleMs: Long = 700,
) {
    enum class Control { CONFIRM, CANCEL, RESUME }

    val sm = TaskStateMachine()
    private val exec = UiActionExecutor(driver, settleMs)
    private val controls = Channel<Control>(Channel.UNLIMITED)

    @Volatile var last: TaskReport? = null
        private set
    /** "हाँ" कब माँगा (ConfirmGate के लिए) */
    @Volatile var askedAt = 0L
        private set

    private lateinit var task: SocialTask
    private var stepNo = 0
    private var cancelled = false

    fun control(c: Control) { controls.trySend(c) }

    private fun report(st: TaskStatus, msg: String, read: String = ""): TaskReport {
        val r = TaskReport(task.id, task.platform, task.action, st, msg, stepNo, task.steps.size, read)
        last = r; onUpdate(r)
        return r
    }

    private fun fail(msg: String): TaskReport { sm.go(ExecPhase.FAILED); return report(TaskStatus.FAILED, msg) }
    private fun cancel(msg: String = "रद्द कर दिया - कुछ पोस्ट/भेजा नहीं गया"): TaskReport { sm.go(ExecPhase.CANCELLED); return report(TaskStatus.CANCELLED, msg) }

    private fun cancelRequested(): Boolean {
        if (cancelled) return true
        while (true) {
            val c = controls.tryReceive().getOrNull() ?: return false
            if (c == Control.CANCEL) { cancelled = true; return true }
        }
    }

    suspend fun run(t: SocialTask): TaskReport {
        task = t
        report(TaskStatus.RUNNING, "${t.platform.label} पर काम शुरू")
        if (t.open != OpenSpec.None) {
            sm.go(ExecPhase.OPENING_APP)
            if (!opener(t.open)) return fail("${t.platform.label} नहीं खुल पाया (ऐप इंस्टॉल है?)")
            sm.go(ExecPhase.WAITING_FOR_UI)
            var reached = false
            repeat(12) {
                if (reached) return@repeat
                driver.pause(700)
                val o = driver.capture()
                if (o != null && (o.pkg == t.platform.pkg || o.pkg.startsWith(t.platform.pkg))) reached = true
                else if (o != null && BlockerDetector.detect(o) == Blocker.PERMISSION_PROMPT) reached = true
            }
            if (!reached) return fail("${t.platform.label} की स्क्रीन नहीं आई")
        }
        sm.go(ExecPhase.OBSERVING)
        val read = StringBuilder()
        for ((i, step) in t.steps.withIndex()) {
            stepNo = i + 1
            if (cancelRequested()) return cancel()
            if (sm.phase != ExecPhase.OBSERVING) sm.go(ExecPhase.OBSERVING)
            var tries = 0
            while (true) {
                val res = runStep(step, read)
                when (res) {
                    is StepOut.Done -> break
                    is StepOut.Finished -> return res.report
                    is StepOut.Skip -> break
                    is StepOut.Blocked -> {
                        if (++tries > 2) return fail("${res.b.hi} - काम रोक दिया")
                        val go = waitForUser(res.b)
                        if (go == false) return cancel()
                        if (go == null) return fail("${res.b.hi} - बहुत देर हो गई, काम रोक दिया")
                        sm.go(ExecPhase.OBSERVING)
                    }
                    is StepOut.Fail -> return fail(res.msg)
                }
            }
            sm.go(ExecPhase.NEXT_STEP)
        }
        if (sm.phase == ExecPhase.NEXT_STEP || sm.phase == ExecPhase.OBSERVING) {
            if (sm.phase == ExecPhase.OBSERVING) sm.go(ExecPhase.NEXT_STEP)
            sm.go(ExecPhase.COMPLETED)
        }
        return report(TaskStatus.COMPLETED, doneMessage(), read.toString().trim())
    }

    private sealed class StepOut {
        object Done : StepOut()
        object Skip : StepOut()
        data class Blocked(val b: Blocker) : StepOut()
        data class Fail(val msg: String) : StepOut()
        data class Finished(val report: TaskReport) : StepOut()
    }

    private fun mapResult(r: ActionResult, step: PlanStep): StepOut = when (r) {
        is ActionResult.Success -> { sm.go(ExecPhase.VERIFYING); StepOut.Done }
        is ActionResult.Blocked -> StepOut.Blocked(r.blocker)
        is ActionResult.Failed -> when {
            step.optional && r.reason == FailureReason.TARGET_NOT_FOUND -> StepOut.Skip
            r.reason == FailureReason.TARGET_NOT_FOUND -> StepOut.Fail("स्क्रीन पर \"${step.name}\" नहीं मिला - ऐप का डिज़ाइन अलग हो सकता है")
            r.reason == FailureReason.NOT_VERIFIED -> if (step.optional) StepOut.Skip else StepOut.Fail("\"${step.name}\" दबाया पर स्क्रीन नहीं बदली")
            else -> StepOut.Fail("\"${step.name}\" नहीं हो पाया")
        }
    }

    private suspend fun runStep(step: PlanStep, read: StringBuilder): StepOut {
        when (val o = exec.observe()) {
            is ActionResult.Blocked -> return StepOut.Blocked(o.blocker)
            is ActionResult.Failed -> return StepOut.Fail("स्क्रीन पढ़ी नहीं जा सकी")
            else -> {}
        }
        sm.go(ExecPhase.RESOLVING_TARGET)
        return when (step) {
            is PlanStep.Tap -> { sm.go(ExecPhase.VALIDATING_TARGET); sm.go(ExecPhase.EXECUTING_ACTION); mapResult(exec.tap(step.spec), step) }
            is PlanStep.Type -> { sm.go(ExecPhase.VALIDATING_TARGET); sm.go(ExecPhase.EXECUTING_ACTION); mapResult(exec.type(step.spec, step.text), step) }
            is PlanStep.AdvanceUntil -> {
                repeat(step.max) {
                    if (exec.find(step.until, 0, 1) != null) { sm.go(ExecPhase.VALIDATING_TARGET); sm.go(ExecPhase.EXECUTING_ACTION); sm.go(ExecPhase.VERIFYING); return StepOut.Done }
                    val r = exec.tap(step.tap)
                    if (r is ActionResult.Blocked) return StepOut.Blocked(r.blocker)
                }
                if (exec.find(step.until, 0, 2) != null) { sm.go(ExecPhase.VALIDATING_TARGET); sm.go(ExecPhase.EXECUTING_ACTION); sm.go(ExecPhase.VERIFYING); StepOut.Done }
                else if (step.optional) StepOut.Skip
                else StepOut.Fail("\"${step.until.name}\" वाली स्क्रीन तक नहीं पहुँच पाया")
            }
            is PlanStep.Read -> {
                val o = driver.capture() ?: return StepOut.Fail("स्क्रीन पढ़ी नहीं जा सकी")
                ChatText.visible(o.texts(), step.maxLines).takeIf { it.isNotEmpty() }?.let { read.append(it.joinToString("\n")).append('\n') }
                sm.go(ExecPhase.VALIDATING_TARGET); sm.go(ExecPhase.EXECUTING_ACTION); sm.go(ExecPhase.VERIFYING)
                StepOut.Done
            }
            is PlanStep.Final -> StepOut.Finished(final(step))
        }
    }

    /** आख़िरी, वापस न होने वाला टैप */
    private suspend fun final(step: PlanStep.Final): TaskReport {
        val found = exec.find(step.spec, 0, 3)
        if (found == null) {
            driver.capture()?.let { o -> BlockerDetector.detect(o)?.let { b -> return fail("${b.hi} - काम रोक दिया") } }
            return fail("\"${step.name}\" बटन नहीं मिला - कुछ पोस्ट/भेजा नहीं गया")
        }
        if (task.mode == TaskMode.DRAFT) {
            sm.go(ExecPhase.VALIDATING_TARGET); sm.go(ExecPhase.COMPLETED)
            return report(TaskStatus.DRAFT_READY, "सब तैयार है, पर पोस्ट नहीं किया (draft) - स्क्रीन पर देखकर आप खुद \"${step.name}\" दबा सकते हैं")
        }
        sm.go(ExecPhase.VALIDATING_TARGET); sm.go(ExecPhase.WAITING_FOR_CONFIRMATION)
        while (controls.tryReceive().getOrNull()?.also { if (it == Control.CANCEL) cancelled = true } != null) { /* पुराने संदेश हटाओ */ }
        if (cancelled) return cancel()
        askedAt = now()
        report(TaskStatus.WAITING_CONFIRMATION, task.confirmText)
        var yes = false
        var waited = 0L
        while (waited < confirmWaitMs) {
            when (controls.tryReceive().getOrNull()) {
                Control.CONFIRM -> { yes = true }
                Control.CANCEL -> return cancel()
                else -> {}
            }
            if (yes) break
            driver.pause(pollMs); waited += pollMs
        }
        askedAt = 0L
        if (!yes) return cancel("\"हाँ\" नहीं मिला - कुछ पोस्ट/भेजा नहीं गया")
        sm.go(ExecPhase.OBSERVING); sm.go(ExecPhase.RESOLVING_TARGET); sm.go(ExecPhase.VALIDATING_TARGET)
        // हाँ मिलने तक स्क्रीन बदल गई? तो दबाओ मत
        if (exec.find(step.spec, 0, 1) == null) return fail("स्क्रीन बदल गई, इसलिए \"${step.name}\" नहीं दबाया - कुछ पोस्ट/भेजा नहीं गया")
        sm.go(ExecPhase.EXECUTING_ACTION)
        val words = step.successWords.filter { it.isNotBlank() }
        val gone = Expectation.TargetGone(step.goneSpec)
        val want = when {
            words.isEmpty() -> gone
            step.requireBoth -> Expectation.Both(Expectation.TextAppears(words), gone)
            else -> Expectation.Either(Expectation.TextAppears(words), gone)
        }
        return when (val r = exec.tap(step.spec, want)) {
            is ActionResult.Success -> {
                sm.go(ExecPhase.VERIFYING)
                val sure = step.requireBoth || (words.isNotEmpty() && r.obs?.hasText(*words.toTypedArray()) == true)
                sm.go(ExecPhase.COMPLETED)
                if (sure) report(TaskStatus.COMPLETED, doneMessage())
                else report(TaskStatus.SUBMITTED_UNVERIFIED, "\"${step.name}\" दबा दिया, पर पक्का नहीं दिखा कि हो गया - कृपया ${task.platform.label} में एक बार देख लें")
            }
            is ActionResult.Failed -> if (r.reason == FailureReason.NOT_VERIFIED) {
                sm.go(ExecPhase.VERIFYING); sm.go(ExecPhase.COMPLETED)
                report(TaskStatus.SUBMITTED_UNVERIFIED, "\"${step.name}\" दबा दिया, पर पक्का नहीं हो पाया कि हो गया - कृपया ${task.platform.label} में देख लें")
            } else fail("\"${step.name}\" नहीं दब पाया - कुछ पोस्ट/भेजा नहीं गया")
            is ActionResult.Blocked -> fail("${r.blocker.hi} - काम रोक दिया")
        }
    }

    /** true = आगे बढ़ो, false = रद्द, null = समय खत्म */
    private suspend fun waitForUser(b: Blocker): Boolean? {
        sm.go(ExecPhase.WAITING_FOR_USER)
        report(TaskStatus.WAITING_USER, b.hi + "। हो जाए तो बताइए, मैं आगे बढ़ूँगा")
        var waited = 0L
        while (waited < userWaitMs) {
            when (controls.tryReceive().getOrNull()) {
                Control.CANCEL -> return false
                Control.RESUME -> return true
                else -> {}
            }
            driver.pause(pollMs * 4); waited += pollMs * 4
            val o = driver.capture()
            if (o != null && BlockerDetector.detect(o) == null) { report(TaskStatus.RUNNING, "ठीक है, आगे बढ़ रहा हूँ"); return true }
        }
        return null
    }

    private fun doneMessage(): String = when (task.action) {
        SocialAction.SEND_MESSAGE -> "संदेश भेज दिया"
        SocialAction.READ_CHAT, SocialAction.SEARCH_CHAT -> "स्क्रीन पर दिख रही बातें पढ़ लीं"
        SocialAction.MARK_READ -> "चैट खोल दी - अब वह पढ़ी हुई है"
        SocialAction.MUTE -> "चैट म्यूट कर दी"
        SocialAction.UNMUTE -> "चैट अनम्यूट कर दी"
        SocialAction.POST_STORY -> "स्टोरी में डाल दिया"
        else -> "${task.platform.label} पर पोस्ट हो गई"
    }
}

/** चैट स्क्रीन के text में से असली बातें (बटन/मेन्यू वाले शब्द हटाकर) */
object ChatText {
    private val CHROME = setOf("message", "type a message", "संदेश", "send", "भेजें", "camera", "attach", "emoji", "voice message",
        "video call", "voice call", "more options", "search", "chats", "updates", "communities", "calls", "status", "online",
        "today", "yesterday", "आज", "कल", "back", "navigate up", "पीछे")

    fun visible(texts: List<String>, max: Int): List<String> = texts.map { it.trim() }
        .filter { it.length >= 2 && it.lowercase() !in CHROME && !Regex("^\\d{1,2}:\\d{2}( ?[ap]m)?$", RegexOption.IGNORE_CASE).matches(it) }
        .distinct().takeLast(max)
}
