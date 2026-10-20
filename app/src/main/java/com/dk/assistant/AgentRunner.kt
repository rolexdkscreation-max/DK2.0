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
    private val speaker: Speaker
) {
    private val brain = BrainManager(svc)
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    fun stop() {
        job?.cancel()
        speaker.stop()
        DKState.set(Mode.IDLE)
    }

    fun run(command: String): Job {
        job?.cancel()
        val j = scope.launch {
            try { execute(command) } finally { DKState.set(Mode.IDLE) }
        }
        job = j
        return j
    }

    private suspend fun say(text: String) {
        DKState.reply(text)
        speaker.speak(text)
    }

    private suspend fun execute(goal: String) {
        DKLog.d("Command: $goal")
        DKState.user(goal)
        val lower = goal.lowercase().trim()
        if (lower in listOf("stop", "रुको", "बस", "ruko", "cancel")) { speaker.stop(); return }
        if (lower.contains("नई बात") || lower.contains("new chat") || lower.contains("भूल जाओ")) {
            brain.reset(); say("ठीक है, नई बातचीत शुरू करते हैं।"); return
        }
        if (!brain.hasUsableProvider()) {
            say("पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।")
            return
        }

        var feedback = "USER GOAL: $goal"
        var wantShot = false
        // Normal baatcheet ho to screen padhe bina seedha jawab = bahut tez
        var chatMode = !looksLikePhoneTask(lower)
        var step = 0
        while (step < MAX_STEPS) {
            step++
            coroutineContext.ensureActive()
            DKState.set(Mode.THINKING)

            val prompt: String
            var shot: String? = null
            if (chatMode) {
                prompt = "CHAT MODE (no screen info).\nUSER SAYS: $goal"
            } else {
                val screen = svc.readScreen()
                if (Prefs.screenshot(svc) && (wantShot || screen.count < 4)) shot = svc.screenshotBase64()
                wantShot = false
                prompt = buildString {
                    append(feedback)
                    append("\n\nSTEP: $step\nFOREGROUND APP: ${screen.packageName}")
                    append("\nSCREEN SIZE: ${screen.width}x${screen.height}")
                    append("\nSCREEN ELEMENTS:\n")
                    append(screen.text.ifBlank { "(kuch nahi mila)" })
                }
            }

            val t0 = System.currentTimeMillis()
            val resp: JSONObject = try {
                brain.next(prompt, shot, memoryNote = if (chatMode) "USER SAYS: $goal" else feedback)
            } catch (e: Exception) {
                DKLog.d("AI error: ${e.javaClass.simpleName} ${e.message}")
                val msg = brain.explain(e)
                DKState.reply(msg + "\n(" + (e.message ?: e.javaClass.simpleName).take(160) + ")")
                speaker.speak(msg)
                return
            }
            DKLog.d("AI ${System.currentTimeMillis() - t0}ms (${brain.lastUsed}): $resp")

            if (chatMode && resp.optBoolean("need_screen", false)) {
                chatMode = false; step--
                continue
            }

            val sayText = resp.optString("say", "")
            val actions = resp.optJSONArray("actions")
            val hasActions = actions != null && actions.length() > 0
            var done = resp.optBoolean("done", !hasActions)

            // Bolna aur kaam saath-saath
            val speakJob = if (sayText.isNotBlank()) scope.launch { say(sayText) } else null

            val results = StringBuilder()
            if (hasActions) {
                DKState.set(Mode.WORKING)
                for (i in 0 until actions!!.length()) {
                    coroutineContext.ensureActive()
                    val a = actions.optJSONObject(i) ?: continue
                    if (a.optString("type") == "look") { wantShot = true; done = false; continue }
                    if (a.optString("type") == "ask") {
                        speakJob?.join()
                        say(a.optString("question", "क्या मैं आगे बढ़ूँ?"))
                        DKState.set(Mode.LISTENING)
                        val ans = svc.hear() ?: "(user ne kuch nahi bola)"
                        DKState.user(ans)
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

            if (done) { speakJob?.join(); return }
            if (chatMode) chatMode = false   // AI ne actions diye - ab screen dekh kar kaam karo
            delay(900) // screen ko badalne ka time do
            feedback = "USER GOAL (same task, continue): $goal\nRESULTS OF YOUR LAST ACTIONS:\n$results"
        }
        say("बहुत सारे स्टेप हो गए, मैंने काम यहीं रोक दिया।")
    }

    /** Kya yeh phone par kuch karne ki command hai? (nahi to normal baatcheet) */
    private fun looksLikePhoneTask(t: String): Boolean {
        val words = listOf(
            "खोल", "open", "चला", "play", "बजा", "सर्च", "search", "ढूंढ", "ढूँढ", "भेज", "send", "मैसेज", "message",
            "कॉल", "call", "फोन लगा", "डिलीट", "delete", "हटा", "मिटा", "पोस्ट", "post", "शेयर", "share", "टाइप", "type",
            "लिखो", "लिख दो", "क्लिक", "click", "दबा", "टैप", "tap", "स्क्रॉल", "scroll", "नीचे", "ऊपर", "वापस", "back",
            "होम", "home", "बंद कर", "चालू कर", "on कर", "off कर", "ऑन", "ऑफ", "वाईफाई", "वाई-फाई", "wifi", "wi-fi",
            "ब्लूटूथ", "bluetooth", "टॉर्च", "torch", "फ्लैश", "वॉल्यूम", "volume", "ब्राइटनेस", "brightness", "सेटिंग", "setting",
            "फोटो", "photo", "गैलरी", "gallery", "कैमरा", "camera", "वीडियो", "video", "गाना", "song", "यूट्यूब", "youtube",
            "फेसबुक", "facebook", "व्हाट्सएप", "whatsapp", "इंस्टा", "insta", "ऐप", "app", "स्क्रीन", "screen", "नोटिफिकेशन",
            "notification", "डाउनलोड", "download", "इंस्टॉल", "install", "अलार्म", "alarm", "रिमाइंडर", "मैप", "map",
            "रास्ता", "chrome", "क्रोम", "गूगल पर", "लाइक", "like", "सब्सक्राइब", "subscribe", "कमेंट", "comment",
            "रिप्लाई", "reply", "पढ़ो", "read", "दिखा", "show", "select", "सेलेक्ट", "कॉपी", "copy", "पेस्ट", "paste",
            "लाइट", "light", "फ्लैशलाइट", "लॉक", "lock", "अनलॉक", "unlock", "क्लोज", "close", "कार्ट", "cart", "खरीद",
            "buy", "ऑर्डर", "order", "फ्लिपकार्ट", "flipkart", "अमेज़न", "अमेजन", "amazon", "मीशो", "meesho", "ज़ेप्टो",
            "जेप्टो", "zepto", "ब्लिंक", "blinkit", "इंस्टामार्ट", "instamart", "स्विगी", "swiggy", "ज़ोमैटो", "zomato",
            "देख", "क्या लिखा", "क्या है स्क्रीन", "जाओ", "जा ", "रोक", "pause", "अगला", "next", "आवाज़ बढ़ा", "आवाज़ कम",
            "साइलेंट", "silent", "स्क्रीनशॉट", "screenshot", "हटाओ", "जोड़", "add"
        )
        return words.any { t.contains(it) }
    }

    companion object { const val MAX_STEPS = 20 }
}
