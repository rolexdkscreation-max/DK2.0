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

    /** Chat screen se likha sawaal: jawab sirf likh kar (bolkar nahi), agar user ne aisa chuna ho */
    @Volatile private var silent = false

    fun run(command: String, speak: Boolean = true): Job {
        job?.cancel()
        val j = scope.launch {
            silent = !speak
            try { execute(command) } finally { DKState.set(Mode.IDLE); silent = false }
        }
        job = j
        return j
    }

    private suspend fun say(text: String) {
        DKState.reply(text)
        if (!silent) speaker.speak(text)
    }

    private suspend fun sayQuick(text: String) {
        DKState.reply(text)
        if (!silent) speaker.speakQuick(text)
    }

    /** Seedhi command turant (AI ke bina). */
    fun runQuick(q: QuickCommands.Quick): Job {
        job?.cancel()
        val j = scope.launch { try { doQuick(q) } finally { DKState.set(Mode.IDLE) } }
        job = j
        return j
    }

    private suspend fun doQuick(q: QuickCommands.Quick): Boolean {
        DKState.set(Mode.WORKING)
        val t0 = System.currentTimeMillis()
        val r = svc.perform(q.action)
        DKLog.d("TURANT ${q.action} -> $r (${System.currentTimeMillis() - t0}ms)")
        val type = q.action.optString("type")
        if (type == "answer_call" || type == "end_call") {
            if (r == "ERROR_CALL_PERM") { sayQuick("कॉल संभालने की परमिशन नहीं है। सेटिंग्स में \"कॉल/कॉन्टैक्ट परमिशन दें\" दबाइए।"); return true }
            if (r.startsWith("ERROR")) { sayQuick(if (type == "answer_call") "कॉल नहीं उठ पाया, स्क्रीन पर खुद उठा लीजिए।" else "कॉल नहीं कटा।"); return true }
        }
        if (type == "distance") {
            val to = q.action.optString("to"); val from = q.action.optString("from")
            val m = if (r.startsWith("DISTANCE:")) {
                val d = r.removePrefix("DISTANCE:").trim()
                if (from.isBlank()) "$to यहाँ से $d दूर है।" else "$from से $to $d दूर है।"
            } else if (r.startsWith("ERROR")) "मैप नहीं खुल पाया।"
            else if (brain.canSearch()) try {
                brain.searchAnswer(if (from.isBlank()) "$to कितनी दूर है (सड़क से दूरी और समय)" else "$from से $to सड़क से कितनी दूर है और कितना समय लगता है")
            } catch (_: Exception) { "रास्ता मैप पर खोल दिया है, दूरी वहीं दिख रही है।" }
            else "रास्ता मैप पर खोल दिया है, दूरी वहीं दिख रही है।"
            DKState.reply(m); if (!silent) speaker.speak(m)
            return true
        }
        if (r.startsWith("ERROR")) return false
        val msg = when {
            type == "who_calls" -> if (r.startsWith("CALLER:")) r.removePrefix("CALLER:").trim() + " का कॉल है।" else "पता नहीं चला किसका कॉल है।"
            type == "unlock" -> if (r.contains("PIN")) "स्क्रीन चालू है, PIN या फिंगरप्रिंट से खोलिए।" else ""
            type == "open_app" -> ""
            else -> q.ack
        }
        DKState.reply(msg.ifBlank { "✓ ${q.action.optString("name", type)}" })
        if (msg.isNotBlank() && !silent) speaker.speakQuick(msg)
        return true
    }

    /** Kya sawaal ko internet se taaza jawab chahiye? */
    private fun needsSearch(t: String): Boolean {
        val w = listOf("आज", "अभी का", "ताज़ा", "ताजा", "लेटेस्ट", "latest", "न्यूज़", "न्यूज", "news", "खबर", "ख़बर", "मौसम", "weather",
            "तापमान", "भाव", "रेट", "rate", "price", "कीमत", "दाम", "स्कोर", "score", "मैच", "match", "किसने जीता", "कौन जीता",
            "पता करो", "पता करके", "सर्च करके", "सही बताओ", "सही जानकारी", "करेक्ट", "correct", "शेयर प्राइस", "stock", "सोने का",
            "चांदी", "पेट्रोल", "डीज़ल", "रिजल्ट", "result", "चुनाव", "election", "कब है", "कब रिलीज", "release", "अपडेट क्या", "2025", "2026")
        return w.any { t.contains(it) }
    }

    /** Mushkil / vistaar wala sawaal? -> internet par kai sources se jaanch (research) */
    private fun needsResearch(t: String): Boolean {
        val w = listOf("विस्तार से", "पूरी जानकारी", "पूरी डिटेल", "डिटेल में", "detail", "रिसर्च", "research", "जांच कर", "जाँच कर",
            "समझाओ", "समझा दो", "तुलना", "compare", "फर्क", "फ़र्क", "अंतर", "difference", "फायदे", "फ़ायदे", "नुकसान",
            "कौन सा बेहतर", "कौनसा बेहतर", "कौन सा अच्छा", "कौनसा अच्छा", "सबसे अच्छा", "best", "इलाज", "बीमारी", "दवा", "दवाई",
            "लक्षण", "कानून", "धारा", "नियम", "योजना", "scheme", "टैक्स", "tax", "निवेश", "invest", "लोन", "loan", "ब्याज",
            "इतिहास", "history", "कैसे काम", "क्यों होता", "क्यों होती", "कैसे बनता", "कैसे बनती", "प्रक्रिया", "process", "आवेदन", "apply")
        if (w.any { t.contains(it) }) return true
        val q = listOf("क्या", "क्यों", "कैसे", "कौन", "कब", "कहाँ", "कितना", "कितने", "what", "why", "how")
        return t.split(' ').size >= 14 && q.any { t.contains(it) }
    }

    /** Kisne banaya / boss kaun - sirf tab jab koi poochhe */
    private fun asksCreator(t: String): Boolean {
        val who = listOf("किसने", "किस ने", "कौन", "who", "kisne", "kaun")
        val made = listOf("बनाया", "बनाई", "बनाए", "बनाये", "डेवलप", "develop", "made", "create", "built", "build", "banaya", "invent")
        if (who.any { t.contains(it) } && made.any { t.contains(it) } &&
            (listOf("तुम्हें", "तुम्हे", "तुमको", "तुझे", "तुझको", "आपको", "इस ऐप", "इस असिस्टेंट").any { t.contains(it) } ||
                Regex("\\b(you|tumhe|tumko|aapko)\\b").containsMatchIn(t))) return true
        val boss = listOf("तुम्हारा बॉस", "तुम्हारे बॉस", "आपका बॉस", "आपके बॉस", "तेरा बॉस", "बॉस कौन", "मालिक कौन", "तुम्हारा मालिक",
            "आपका मालिक", "बनाने वाला", "बनाने वाले", "निर्माता", "तुम्हारा निर्माता", "your boss", "your owner", "your creator",
            "who is your boss", "who owns you", "creator कौन", "डेवलपर कौन", "developer कौन", "tumhara boss", "tumhe kisne")
        return boss.any { t.contains(it) }
    }

    /** "याद रखो ..." / "... भूल जाओ" / "क्या याद है" */
    private suspend fun memoryCommand(goal: String): Boolean {
        val m = Memory.parse(goal) ?: return false
        when (m.kind) {
            "remember" -> { Memory.addFact(svc, m.text); say("ठीक है, याद रख लिया।") }
            "forget" -> {
                val n = Memory.forget(svc, m.text)
                say(if (n > 0) "ठीक है, वो बात भूल गया।" else "इस बारे में मुझे कुछ याद नहीं था।")
            }
            "forget_all" -> { Memory.clearFacts(svc); brain.reset(); say("ठीक है, सारी यादें मिटा दीं।") }
            "recall" -> {
                val f = Memory.facts(svc)
                if (f.isEmpty()) say("अभी आपने मुझे कुछ याद रखने को नहीं कहा है। बोलिए, याद रखो, और बात बताइए।")
                else {
                    DKState.reply("मुझे याद है:\n" + f.joinToString("\n") { "• $it" })
                    if (!silent) speaker.speak("मुझे ${f.size} बातें याद हैं। " + f.takeLast(5).joinToString("। "))
                }
            }
        }
        return true
    }

    private suspend fun execute(goal: String) {
        DKLog.d("Command: $goal")
        DKState.user(goal)
        val lower = goal.lowercase().trim()
        if (lower in listOf("stop", "रुको", "बस", "ruko", "cancel")) { speaker.stop(); return }
        if (memoryCommand(goal)) return
        // "tumhe kisne banaya?" - sirf poochhne par
        if (asksCreator(lower)) { say(CREATOR_ANSWER); return }
        if (lower.contains("नई बात") || lower.contains("new chat") || lower.contains("भूल जाओ")) {
            brain.reset(); say("ठीक है, नई बातचीत शुरू करते हैं।"); return
        }
        // 1) Seedhi command? -> turant, bina AI
        QuickCommands.parse(goal, Prefs.wakeWords(svc))?.let { q ->
            if (doQuick(q)) return
            // open_app fail (app nahi mila) -> AI sambhalega
        }
        if (!brain.hasUsableProvider()) {
            say("पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।")
            return
        }

        var feedback = "USER GOAL: $goal"
        var wantShot = false
        var shotWarned = false
        // Normal baatcheet ho to screen padhe bina seedha jawab = bahut tez
        var chatMode = !looksLikePhoneTask(lower)
        var step = 0

        // 2) Mushkil sawaal -> research (kai sources se jaanch). Taaza jawab -> Google Search. Baaki -> seedha AI (turant)
        if (chatMode && brain.canSearch() && needsResearch(lower)) {
            if (searchAndSay(goal, deep = true)) return
        }
        if (chatMode && brain.canSearch() && needsSearch(lower)) {
            if (searchAndSay(goal)) return
        }
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
                if (wantShot && !Prefs.screenshot(svc) && !shotWarned) {
                    shotWarned = true
                    DKState.reply("स्क्रीन देखना बंद है। चालू करने के लिए बोलिए \"स्क्रीन देखना चालू करो\" या सेटिंग्स → परमिशन में ऑन करें।")
                }
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
            // jawab 1.3 sec me na aaye to bolo "सोचकर बताता हूँ" / "हाँ, देख रहा हूँ" (sirf pehle step par)
            val filler = if (step == 1) scope.launch {
                delay(1300)
                val f = if (chatMode) listOf("सोचकर बताता हूँ।", "एक सेकंड, सोच रहा हूँ।").random()
                        else listOf("हाँ, देख रहा हूँ।", "ठीक है, कर रहा हूँ।").random()
                sayQuick(f)
            } else null
            val resp: JSONObject = try {
                brain.next(prompt + if (chatMode && brain.canSearch()) "\nSEARCH AVAILABLE" else "", shot,
                    memoryNote = if (chatMode) "USER SAYS: $goal" else feedback, chat = chatMode)
            } catch (e: Exception) {
                filler?.cancel()
                DKLog.d("AI error: ${e.javaClass.simpleName} ${e.message}")
                val msg = brain.explain(e)
                DKState.reply(msg + "\n(" + (e.message ?: e.javaClass.simpleName).take(160) + ")")
                if (!silent) speaker.speak(msg)
                return
            }
            DKLog.d("AI ${System.currentTimeMillis() - t0}ms (${brain.lastUsed}): $resp")
            // filler shuru nahi hua to rok do; bol raha ho to poora hone do (beech me na kate)
            if (filler != null) { if (filler.isActive && System.currentTimeMillis() - t0 < 1300) filler.cancel() else filler.join() }

            resp.optString("remember").takeIf { it.isNotBlank() }?.let { Memory.addFact(svc, it) }

            if (chatMode && (resp.optBoolean("need_research", false) || resp.optBoolean("need_search", false))) {
                if (searchAndSay(goal, fillerSaid = filler != null && !filler.isCancelled,
                        deep = resp.optBoolean("need_research", false))) return
            }

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
                    delay(250)
                }
            }

            if (done) { speakJob?.join(); return }
            if (chatMode) chatMode = false   // AI ne actions diye - ab screen dekh kar kaam karo
            delay(650) // screen ko badalne ka time do
            feedback = "USER GOAL (same task, continue): $goal\nRESULTS OF YOUR LAST ACTIONS:\n$results"
        }
        say("बहुत सारे स्टेप हो गए, मैंने काम यहीं रोक दिया।")
    }

    private suspend fun searchAndSay(goal: String, fillerSaid: Boolean = false, deep: Boolean = false): Boolean {
        DKState.set(Mode.THINKING)
        val f = if (!fillerSaid) scope.launch {
            val t = if (deep) listOf("अच्छा सवाल है, सही जानकारी जाँचकर बताता हूँ।", "एक मिनट, अलग-अलग जगह से पक्का करके बताता हूँ।").random()
                    else listOf("पता करके बताता हूँ।", "एक सेकंड, देखकर बताता हूँ।").random()
            sayQuick(t)
        } else null
        return try {
            val r = brain.search(goal, deep)
            f?.join()
            if (r.text.isBlank()) false else {
                // screen par jawab + kahan se mila; bolkar sirf jawab
                val src = if (r.sources.isNotEmpty()) "\n\nस्रोत: " + r.sources.joinToString(", ") { it.first } else ""
                DKState.reply(r.text + src)
                if (!silent) speaker.speak(r.text)
                true
            }
        } catch (e: Exception) {
            DKLog.d("Search fail: ${e.message}")
            f?.join(); false
        }
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

    companion object {
        const val MAX_STEPS = 20
        const val CREATOR_ANSWER = "मुझे धीरज कुमार सिंह जी ने बनाया है। मैं धीरज सर के लिए काम करता हूँ, वही मेरे बॉस हैं।"
    }
}
