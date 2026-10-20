package com.dk.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Agent loop:  Screen dekho -> AI se pucho -> action karo -> phir screen dekho ... jab tak kaam pura na ho.
 */
class AgentRunner(
    private val svc: DKAccessibilityService,
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    private val listener: VoiceListener
) {
    private var brain: GeminiBrain? = null
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    fun stop() {
        job?.cancel()
        speaker.stop()
        svc.setBubbleState(BubbleState.IDLE)
    }

    fun run(command: String): Job {
        job?.cancel()
        val j = scope.launch {
            try {
                execute(command)
            } finally {
                svc.setBubbleState(BubbleState.IDLE)
            }
        }
        job = j
        return j
    }

    private fun getBrain(): GeminiBrain? {
        val key = Prefs.apiKey(svc)
        if (key.isBlank()) return null
        val model = Prefs.model(svc)
        val b = brain
        if (b == null || b.model != model) brain = GeminiBrain(key, model)
        return brain
    }

    private suspend fun execute(goal: String) {
        DKLog.d("Command: $goal")
        val lower = goal.lowercase().trim()
        if (lower in listOf("stop", "रुको", "बस", "ruko", "cancel")) { speaker.stop(); return }
        if (lower.contains("नई बात") || lower.contains("new chat") || lower.contains("bhool jao")) {
            brain?.reset(); speaker.speak("ठीक है, नई बातचीत शुरू करते हैं।"); return
        }

        val brain = getBrain() ?: run {
            speaker.speak("पहले DK ऐप में Gemini API key डालिए।")
            return
        }

        var feedback = "USER GOAL: $goal"
        for (step in 1..MAX_STEPS) {
            coroutineContext.ensureActive()
            svc.setBubbleState(BubbleState.THINKING)

            val screen = svc.readScreen()
            val shot = if (Prefs.screenshot(svc)) svc.screenshotBase64() else null
            val prompt = buildString {
                append(feedback)
                append("\n\nSTEP: $step\nFOREGROUND APP: ${screen.packageName}")
                append("\nSCREEN SIZE: ${screen.width}x${screen.height}")
                append("\nSCREEN ELEMENTS:\n")
                append(screen.text.ifBlank { "(kuch nahi mila)" })
            }

            val resp: JSONObject = try {
                brain.next(prompt, shot, memoryNote = feedback)
            } catch (e: Exception) {
                DKLog.d("AI error: ${e.message}")
                val msg = if (e.message?.startsWith("QUOTA") == true)
                    "आज की फ्री लिमिट खत्म हो गई है, थोड़ी देर बाद कोशिश कीजिए।"
                else "माफ़ कीजिए, AI से जवाब नहीं मिला। इंटरनेट और API key चेक कीजिए।"
                speaker.speak(msg)
                return
            }
            DKLog.d("AI: $resp")

            val say = resp.optString("say", "")
            val actions = resp.optJSONArray("actions")
            val hasActions = actions != null && actions.length() > 0
            var done = resp.optBoolean("done", !hasActions)

            // Bolna aur kaam saath-saath
            val speakJob = if (say.isNotBlank()) scope.launch { speaker.speak(say) } else null

            val results = StringBuilder()
            if (hasActions) {
                svc.setBubbleState(BubbleState.WORKING)
                for (i in 0 until actions!!.length()) {
                    coroutineContext.ensureActive()
                    val a = actions.optJSONObject(i) ?: continue
                    if (a.optString("type") == "ask") {
                        speakJob?.join()
                        speaker.speak(a.optString("question", "क्या मैं आगे बढ़ूँ?"))
                        svc.setBubbleState(BubbleState.LISTENING)
                        val ans = listener.listenOnce() ?: "(user ne kuch nahi bola)"
                        DKLog.d("User answer: $ans")
                        results.append("ask -> USER ANSWERED: \"$ans\"\n")
                        done = false
                        break
                    }
                    val r = svc.perform(a)
                    DKLog.d("Action ${a.optString("type")} -> $r")
                    results.append("${a.optString("type")} -> $r\n")
                    delay(400)
                }
            }

            if (done) {
                speakJob?.join()
                return
            }
            delay(1200) // screen ko badalne ka time do
            feedback = "USER GOAL (same task, continue): $goal\nRESULTS OF YOUR LAST ACTIONS:\n$results"
        }
        speaker.speak("बहुत सारे स्टेप हो गए, मैंने काम यहीं रोक दिया।")
    }

    companion object {
        const val MAX_STEPS = 20
    }
}
