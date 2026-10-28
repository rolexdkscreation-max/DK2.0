package com.dk.assistant

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** ऑटोनोमस मोड का अभी का काम (null = आम DK, पहले जैसा) */
    @Volatile private var autoS: com.dk.assistant.auto.AutoRun? = null

    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    fun stop() {
        job?.cancel()
        speaker.stop()
        DKState.set(Mode.IDLE)
    }

    /** Chat screen se likha sawaal: jawab sirf likh kar (bolkar nahi), agar user ne aisa chuna ho */
    @Volatile private var silent = false

    /** Abhi kis sawaal par kaam chal raha hai + ab tak kya jawab bana (beech me tokne par kaam aata hai) */
    @Volatile var currentGoal: String? = null
        private set
    private val currentAnswer = StringBuilder()

    fun run(command: String, speak: Boolean = true, note: String = ""): Job {
        job?.cancel()
        silent = !speak
        Lang.noteUser(command)   // DK AI: "Auto" भाषा - यूज़र की इस बात की भाषा याद
        val j = scope.launch {
            try { execute(command, note) } finally {
                if (job === coroutineContext[Job]) {
                    autoS?.let { com.dk.assistant.auto.AutoLog.add(svc, "काम खत्म: ${it.plan.task.take(80)}") }
                    autoS = null; brain.override = null
                    DKState.set(Mode.IDLE); silent = false; currentGoal = null
                }
            }
        }
        job = j
        return j
    }

    /**
     * User ne DK ke bolte/sochte waqt beech me kuch kaha:
     * pehle uski baat suni (ho chuki), ab purana kaam roko aur dono baaton ko milakar naya jawab.
     */
    fun interrupt(newText: String): Job {
        val prev = currentGoal
        val wasActive = job?.isActive == true
        val said = speaker.spokenSoFar()
        val planned = synchronized(currentAnswer) { currentAnswer.toString().trim() }
        speaker.stop()
        val note = if (wasActive && !prev.isNullOrBlank()) {
            if (said.isBlank()) "NOTE: While you were still preparing the answer to the user's previous message \"$prev\", " +
                "the user added the message below. Treat both together as ONE request. If the new message is a separate question, " +
                "answer the earlier one briefly first, then the new one."
            else "NOTE: The user INTERRUPTED you while you were answering their previous question: \"$prev\". " +
                "You had already spoken: \"${said.takeLast(500)}\"." +
                (if (planned.length > said.length + 10) " Your full planned answer was: \"${planned.take(700)}\"." else "") +
                " Listen to the new message below. If it is about the SAME topic (a correction, addition or follow-up), give ONE fresh " +
                "combined answer. If it is a DIFFERENT question, first finish the earlier answer in 1-2 short sentences (only what was " +
                "not yet said), then answer the new question. Never repeat what you already said."
        } else ""
        DKLog.d("Beech me toka: \"$newText\" (pehle: $prev)")
        return run(newText, true, note)
    }

    private suspend fun say(text: String) {
        DKState.reply(text)
        if (!silent) speaker.speak(text)
    }

    /** काम नहीं हुआ / पुष्टि नहीं - गलती के रूप में (Live को "FAILED" जाता है, "हो गया" नहीं) */
    private suspend fun sayFailed(text: String) {
        DKState.replyFailed(text)
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
        // v3.33: कई कॉन्टैक्ट मिले -> पूछो (कॉल नहीं), जवाब सुनकर उसी नाम से फिर ढूँढो - AI के बिना
        if (type == "call_contact" && LocalRouter.needsUser(r) && !silent) {
            sayQuick(LocalRouter.userText(r) ?: "किसे कॉल करूँ?")
            DKState.set(Mode.LISTENING)
            val ans = svc.hearForAgent()?.trim().orEmpty()
            DKState.set(Mode.WORKING)
            if (ans.isBlank() || ComputerVoice.yesNo(ans) == false || LocalRouter.negated(" $ans ") ||
                ans.lowercase().trim('.', '।', ' ') in listOf("रहने दो", "कैंसल", "cancel", "stop", "रुको", "मत करो", "नहीं")) {
                sayQuick("ठीक है, कॉल नहीं किया।"); return true
            }
            DKState.user(ans)
            val name = (LocalRouter.single(LocalRouter.normalize(ans, Prefs.wakeWords(svc)), Prefs.wakeWords(svc))
                ?.action?.takeIf { it.optString("type") == "call_contact" }?.optString("name")) ?: ans
            val r2 = svc.perform(JSONObject().put("type", "call_contact").put("name", name))
            DKLog.d("TURANT call_contact (दूसरी बार) -> ${r2.substringBefore(':')}")
            sayQuick(LocalRouter.userText(r2) ?: if (r2.startsWith("ERROR")) "कॉल नहीं हो पाई।" else "ठीक है।")
            return true
        }
        // v3.33: फ़ोन ने खुद जाँचा हुआ सच्चा नतीजा (ऐप नहीं है / डायलर खुला / वीडियो खुला पर चलना पक्का नहीं ...) - AI को नहीं
        LocalRouter.userText(r)?.let { m ->
            if (LiveFast.ok(type, r) || LocalRouter.final(r)) {
                if (r.startsWith("ERROR") || r.startsWith("STEP_FAILED")) DKState.replyFailed(m) else DKState.reply(m)
                if (!silent) speaker.speakQuick(m)
                return true
            }
        }
        if (r.startsWith("ERROR")) return false
        // seedha video nahi chala (search page khula) -> AI pehla video click karega
        if (type == "youtube_play" && !r.startsWith("PLAYING")) return false
        val msg = when {
            type == "who_calls" -> if (r.startsWith("CALLER:")) r.removePrefix("CALLER:").trim() + " का कॉल है।" else "पता नहीं चला किसका कॉल है।"
            type == "unlock" -> if (r.contains("PIN")) "स्क्रीन चालू है, PIN या फिंगरप्रिंट से खोलिए।" else ""
            type == "open_app" -> ""
            type == "youtube_play" -> if (r.contains("verified: playing")) "वीडियो चल रहा है" else "वीडियो खोल दिया"
            type == "call_log" -> "कॉल लिस्ट खोल दी"
            else -> Lang.say(q.ack)
        }
        DKState.reply(msg.ifBlank { "✓ ${q.action.optString("name", type)}" })
        if (msg.isNotBlank() && !silent) speaker.speakQuick(msg)
        return true
    }

    /** Kya sawaal ko internet se taaza jawab chahiye? (sirf sach me badalne wali jaankari - baaki AI apne gyaan se) */
    private fun needsSearch(t: String): Boolean = SearchPolicy.needsSearch(t)

    /** User ne saaf kaha "सर्च/रिसर्च करो"? (YouTube/Amazon jaise app me search wali command nahi) */
    private fun explicitResearch(t: String): Boolean {
        val apps = listOf("यूट्यूब", "youtube", "अमेज़न", "अमेजन", "amazon", "फ्लिपकार्ट", "flipkart", "इंस्टाग्राम", "instagram",
            "व्हाट्सएप", "whatsapp", "मैप", "map", "स्पॉटिफाई", "spotify", "प्ले स्टोर", "play store", "playstore", "कॉन्टैक्ट", "contact",
            "गैलरी", "gallery", "फोटो", "photo", "गाना", "song", "वीडियो", "video", "मीशो", "meesho", "मिंत्रा", "myntra", "ज़ोमैटो", "zomato", "स्विगी", "swiggy")
        if (apps.any { t.contains(it) }) return false
        val strong = listOf("रिसर्च", "research", "डीप रिसर्च", "deep research", "खंगाल", "गहराई से", "गहराई में", "अच्छे से पता", "अच्छी तरह पता",
            "पूरी जानकारी निकाल", "पूरी जानकारी खोज", "रिसर्च करके", "डीटेल में पता", "डिटेल में पता")
        if (strong.any { t.contains(it) }) return true
        val ask = listOf("सर्च करो", "सर्च कर", "सर्च करके", "search करो", "search kar", "search karo", "खोजो", "खोज कर", "खोजकर", "खोज के",
            "ढूंढकर बताओ", "ढूँढकर बताओ", "ढूंढ कर बताओ", "ढूँढ कर बताओ", "पता करके बताओ", "पता करो", "पता लगाओ", "इंटरनेट पर देख", "गूगल पर देख", "गूगल करके")
        return ask.any { t.contains(it) }
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
            "who is your boss", "who owns you", "creator कौन", "डेवलपर कौन", "developer कौन", "tumhara boss", "tumhe kisne",
            // v3.59: "किसके लिए काम करते हो / किसने सिखाया / तुम्हारा डेवलपर"
            "किसके लिए काम", "किसके लिए कर", "kiske liye kaam", "किसने सिखाया", "किसने ट्रेन", "who trained you", "who taught you",
            "who do you work for", "तुम्हारा डेवलपर", "तुम्हारे डेवलपर", "आपका डेवलपर", "your developer", "किसकी ai", "किसका ai", "किसका एआई", "किसकी एआई")
        return boss.any { t.contains(it) }
    }

    /** "याद रखो ..." / "... भूल जाओ" / "क्या याद है" */
    private suspend fun memoryCommand(goal: String): Boolean {
        // v3.88: "मैंने क्या बोला था / कल क्या बात हुई थी" = फ़ोन की लोकल याददाश्त से (कोई API नहीं)
        if (LocalRecall.isRecall(goal)) { say(LocalMemory.answer(svc, goal)); return true }
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

    /** आवाज़ से DK कंप्यूटर. false = DK कंप्यूटर बंद, आम रास्ते से चलने दो */
    private suspend fun computerFromVoice(cmd: ComputerVoice.Cmd): Boolean {
        if (!Prefs.computerOn(svc)) return false
        if (cmd.open) {
            try { svc.startActivity(android.content.Intent(svc, ComputerActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            say("DK कंप्यूटर खोल दिया। बोलिए या लिखिए, क्या काम करना है।")
            return true
        }
        val wasBusy = ComputerSession.busy
        val err = ComputerSession.start(svc, cmd.task, voice = true)
        say(err ?: if (wasBusy) "ठीक है, यह काम DK कंप्यूटर की कतार में रख दिया। पहले वाला पूरा होते ही शुरू होगा।"
            else "ठीक है, DK कंप्यूटर काम शुरू कर रहा है। हर कदम DK कंप्यूटर स्क्रीन में दिखेगा, और पूरा होने पर मैं बताऊँगी।")
        return true
    }

    /** "कोडिंग एजेंट, X बनाओ" / "कोडर ..." / "coding agent ..." -> X ; warna null */
    private fun codingTask(goal: String): String? {
        val low = goal.lowercase()
        val keys = listOf("कोडिंग एजेंट", "कोडिंग टीम", "कोडिंग वाले", "कोडर", "coding agent", "coding team", "coder", "कोड एजेंट")
        val k = keys.firstOrNull { low.contains(it) } ?: return null
        val rest = goal.substring((low.indexOf(k) + k.length).coerceAtMost(goal.length))
            .trim(' ', ',', '.', '!', '।', ':', '-').removePrefix("से ").removePrefix("को ").trim()
        return rest.takeIf { it.length >= 4 }
    }

    /** v3.33: आवाज़ पहचान (STT) पूरी होने का समय - "सुनना पूरा -> काम" की देरी लॉग में */
    @Volatile var heardAt = 0L

    private suspend fun execute(goal: String, note0: String = "") {
        var note = note0
        autoS = null; brain.override = null
        DKLog.d("Command: $goal")
        heardAt.takeIf { it > 0 && System.currentTimeMillis() - it < 30_000 }?.let { DKLog.d("Deri: सुनना पूरा → रूटर ${System.currentTimeMillis() - it} ms"); heardAt = 0L }
        DKState.user(goal)
        // DK AI Part 13: access key चालू नहीं = कोई काम नहीं, साफ़ संदेश (लाइसेंस सेट नहीं = पहले जैसा)
        com.dk.assistant.license.AccessKeyManager.refusalOrNull(svc)?.let { say(it); return }
        currentGoal = goal
        synchronized(currentAnswer) { currentAnswer.clear() }
        val lower = goal.lowercase().trim()
        val bare = lower.trim(' ', '.', '!', '।', ',')
        // DK कंप्यूटर इजाज़त माँग रहा हो: "हाँ" / "नहीं"
        if (ComputerSession.ask != null) ComputerVoice.yesNo(goal)?.let { yes ->
            ComputerSession.answer(yes); say(if (yes) "ठीक है, कर रही हूँ।" else "ठीक है, नहीं करूँगी।"); return
        }
        if (bare in listOf("stop", "रुको", "बस", "ruko", "cancel", "चुप", "चुप हो जाओ", "बस करो", "रुक जाओ", "ठीक है बस", "बस बस", "रहने दो", "chup")) {
            speaker.stop(); DKState.reply("ठीक है।"); return
        }
        // ऑटोनोमस मोड: "इमरजेंसी स्टॉप" = सारा ऑटोनोमस काम (कोडिंग भी) तुरंत बंद
        if (com.dk.assistant.auto.AutoPrefs.on(svc) && bare in com.dk.assistant.auto.AutoSafety.STOP_WORDS) {
            com.dk.assistant.auto.AutoMode.emergencyStop(svc); say("ठीक है, सारा ऑटोनोमस काम रोक दिया।"); return
        }
        if (memoryCommand(goal)) return
        // DK AI Website Forge: "ek cafe ki website banao" -> लाइव 3D build स्क्रीन (कोडिंग एजेंट वाली बात यहाँ नहीं पकड़ी जाती)
        com.dk.assistant.forge.ForgeIntent.extract(goal)?.let { p ->
            say(com.dk.assistant.forge.ForgeLauncher.launch(svc, p).second); return
        }
        // कोडिंग टीम: "सुझाव जोड़ दो / नहीं" और "कोडिंग एजेंट, ... बनाओ"
        if (Prefs.coderOn(svc)) {
            CoderTeam.voiceDecision(svc, goal)?.let { say(it); return }
            codingTask(goal)?.let { task ->
                // 7-एजेंट सिस्टम चालू = पुराना "मल्टी-एजेंट टीम" switch ज़रूरी नहीं (CoderTeam.start खुद 7-एजेंट पर भेजता है)
                val seven = Prefs.coderSeven(svc)
                if (!seven && !Prefs.coderTeam(svc)) { say("कोडिंग टीम सेटिंग्स में बंद है। सेटिंग्स → कोडिंग एजेंट में टीम चालू करें।"); return }
                val err = CoderTeam.start(svc, task, fromVoice = true)
                say(err ?: if (seven) Lang.pick("ठीक है, 11 एजेंट वाली टीम पहले आपकी बात समझकर बताएगी, आपकी हाँ के बाद ही काम शुरू होगा।",
                    "Okay, the 11-agent team will first tell you what it understood; work starts only after your yes.")
                    else "ठीक है, कोडिंग टीम काम शुरू कर रही है। बिल्डर लिखेगा, रिव्यूअर जाँचेगा, और पूरा होने पर मैं बताऊँगी।")
                return
            }
        }
        // "डीके कंप्यूटर, ... करो" / "रिसर्च करके PDF बनाओ" -> DK कंप्यूटर (कदम-दर-कदम, फ़ाइलें, रिपोर्ट)
        ComputerVoice.parse(goal)?.let { cmd -> if (computerFromVoice(cmd)) return }
        // "tumhe kisne banaya?" - sirf poochhne par
        if (asksCreator(lower)) { say(CREATOR_ANSWER); return }
        // टेम्पलेट कमांड (साँचे): बिना API key / AI के - पहला मिला साँचा चलता है; न मिले तो नीचे पहले जैसा रास्ता
        if (!note.contains(LocalRouter.DONE_NOTE)) {
            var tg = goal.trim()
            for (w in Prefs.wakeWords(svc).sortedByDescending { it.length }) if (w.length >= 2 && tg.startsWith(w, ignoreCase = true)) tg = tg.substring(w.length).trimStart(' ', ',')
            com.dk.assistant.template.TemplateCommands.match(tg, com.dk.assistant.template.TemplateStore.all(svc))?.let { m ->
                DKState.set(Mode.WORKING)
                val r = com.dk.assistant.template.TemplateRunner.run(svc, m) { q ->
                    say(q); DKState.set(Mode.LISTENING)
                    svc.hearForAgent()?.also { DKState.user(it); DKState.set(Mode.WORKING) }
                }
                say(r); return
            }
        }
        // v3.33: Local-first - फ़ोन पर ही समझ आए ऐसा काम (ऐप खोलो, कॉल, YouTube/शॉपिंग सर्च, फ़ोटो, कई कदम) AI से पहले.
        // (पहले QuickCommands रिसर्च के बाद था - "zepto पर दूध सर्च करो" गहरी AI रिसर्च बन जाता था)
        if (!note.contains(LocalRouter.DONE_NOTE)) {
            val tc = System.nanoTime()
            val sp = LocalRouter.split(goal, Prefs.wakeWords(svc), svc.currentPackage())
            val ms = (System.nanoTime() - tc) / 1_000_000
            val local = sp.local
            DKLog.d("रूटर ($ms ms): " + when {
                local == null -> "फ़ोन पर सीधा नहीं - आगे (AI/बाकी)"
                sp.rest == null -> "पूरा फ़ोन पर: ${local.action.optString("type")} (AI नहीं)"
                else -> "पहला हिस्सा फ़ोन पर: ${local.action.optString("type")}, बाकी AI"
            })
            if (local != null && sp.rest == null) {
                if (doQuick(local)) return
                // open_app fail (app nahi mila) -> AI sambhalega
            } else if (local != null) {
                DKState.set(Mode.WORKING)
                val t0 = System.currentTimeMillis()
                val r = svc.perform(local.action)
                DKLog.d("TURANT (पहला हिस्सा) ${local.action.optString("type")} -> ${r.take(80)} (${System.currentTimeMillis() - t0}ms)")
                if (LocalRouter.final(r) && !LiveFast.ok(local.action.optString("type"), r)) {
                    val m = LocalRouter.userText(r) ?: "यह नहीं हो पाया।"
                    DKState.replyFailed(m); if (!silent) speaker.speakQuick(m); return
                }
                note = (if (note.isNotBlank()) "$note\n" else "") + LocalRouter.DONE_NOTE + " " + LiveFast.describe(local.action) +
                    " -> " + r.take(160) + "\nContinue the rest of the user's goal from the CURRENT screen."
                svc.waitSettle("", false, 2500)
            }
        }
        // ऑटोनोमस मोड (Settings में चालू हो तभी; बंद = यह हिस्सा कुछ नहीं करता): कोडिंग -> कोडिंग एजेंट, फ़ोन के काम -> नीचे स्क्रीन वाला रास्ता
        com.dk.assistant.auto.AutoMode.plan(svc, goal)?.let { plan ->
            if (plan.kind == com.dk.assistant.auto.AutoPlan.Kind.CODING) { say(com.dk.assistant.auto.AutoMode.startCoding(svc, plan.task)); return }
            val ovr = com.dk.assistant.auto.AutoMode.brainClient(svc)
            brain.override = ovr
            autoS = com.dk.assistant.auto.AutoRun(plan, com.dk.assistant.auto.AutoPrefs.confirm(svc))
            com.dk.assistant.auto.AutoLog.add(svc, "नया काम (${plan.kind.title}): $goal — AI: " + if (ovr != null) "ऑटोनोमस की अपनी key" else "DK की मुख्य key")
            note = (if (note.isNotBlank()) "$note\n" else "") + com.dk.assistant.auto.AutoPlan.phoneNote(plan)
        }
        // "सर्च करो / रिसर्च करो / डीप रिसर्च करो" -> poori gehri khoj, phir vistaar se pakka jawab
        searchFailed = false
        if (autoS == null && !note.contains(LocalRouter.DONE_NOTE) && explicitResearch(lower) && brain.canSearch()) {
            if (searchAndSay(goal, deep = true, thorough = true)) return
        }
        if (lower.contains("नई बात") || lower.contains("new chat") || lower.contains("भूल जाओ")) {
            brain.reset(); say("ठीक है, नई बातचीत शुरू करते हैं।"); return
        }
        // 1) Seedhi command -> upar LocalRouter (QuickCommands usi ke andar pehle chalta hai)
        // DK ka apna dimaag: samay, tareekh, battery, namaste, hisaab = ek pal me (bina internet)
        if (note.isBlank()) LocalBrain.instant(svc, goal)?.let { say(it); return }
        if (!brain.hasUsableProvider()) {
            LocalBrain.recall(svc, goal, exact = false)?.let { say(it); return }
            say("पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।")
            return
        }

        var feedback = (if (note.isNotBlank()) "$note\n" else "") + "USER GOAL: $goal"
        // v3.33: कार्ट - सही प्रोडक्ट पक्का करके ही "Add", और स्क्रीन पर पुष्टि दिखे तभी "जोड़ दिया"
        val cartGoal = LocalRouter.isCartGoal(goal)
        val deleteGoal = ActionCheck.isDeleteMessage(goal)
        if (deleteGoal) feedback += "\nDELETE RULE: Long-press the exact message, tap Delete, then choose 'Delete for everyone' if the user wants it gone for both. " +
            "Set done=true only after the screen shows 'This message was deleted' / 'You deleted this message'."
        if (cartGoal) feedback += "\nCART RULE: Before tapping Add to cart, check the open product matches the user's request " +
            "(name, size/variant, price limit). If unsure, use ask. Say it was added ONLY if the screen shows a confirmation " +
            "(e.g. 'Added to cart', 'Go to cart'). Never tap Buy now / Place order / Pay."
        var wantShot = false
        var shotWarned = false
        // Normal baatcheet ho to screen padhe bina seedha jawab = bahut tez
        var chatMode = autoS == null && !looksLikePhoneTask(lower)
        var step = 0

        // 2) Taaza jaankari (mausam/bhav/score/news) -> Google Search (tez). Baaki sab -> seedha AI = turant jawab.
        //    Gehri research sirf tab jab user khud bole "सर्च/रिसर्च करो" (upar).
        // v3.93: एक सवाल पर एक ही खोज - ऊपर (सर्च करो) खोज हो चुकी हो तो दोबारा नहीं
        if (chatMode && !searchFailed && brain.canSearch() && needsSearch(lower)) {
            if (searchAndSay(goal)) return
        }
        // v3.93: Tavily, Exa, Gemini तीनों न चलीं = DK अपनी जानकारी से जवाब दे, पर साफ़ बताए
        if (chatMode && searchFailed) {
            when (chatStream(goal, (if (note.isNotBlank()) "$note\n" else "") + SEARCH_FAILED_NOTE)) {
                ChatOut.SCREEN -> chatMode = false
                else -> return
            }
        }
        // 3) Normal baatcheet: pehle sikha hua (wahi sawaal pehle poocha tha) = turant; warna AI se TUKDON me jawab
        if (chatMode) {
            // file padhna main thread par nahi (v3.8) - Send ke baad request me deri na ho
            if (note.isBlank()) withContext(Dispatchers.IO) { LocalBrain.recall(svc, goal, exact = true) }?.let { DKLog.d("Seekha hua jawab"); say(it); return }
            when (chatStream(goal, note)) {
                ChatOut.DONE -> return
                ChatOut.SEARCH -> {
                    if (!searchFailed && brain.canSearch() && searchAndSay(goal)) return
                    if (searchFailed) { if (chatStream(goal, SEARCH_FAILED_NOTE) != ChatOut.SCREEN) return; chatMode = false }
                }
                ChatOut.THINK -> { if (brain.canThink() && thinkAndSay(goal)) return; if (chatStream(goal, "Deep thinking is unavailable right now: do NOT reply [THINK]. Answer it yourself, carefully and correctly.") != ChatOut.SCREEN) return; chatMode = false }
                ChatOut.SCREEN -> chatMode = false
            }
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
                // ऑटोनोमस: पासवर्ड / OTP / बैंक / भुगतान वाली स्क्रीन पर कुछ नहीं - रुककर बताओ
                autoS?.let { s ->
                    s.screen = screen.text; s.pkg = screen.packageName
                    com.dk.assistant.auto.AutoSafety.blockReason(screen.text, screen.packageName, svc.hasPasswordField())?.let { r ->
                        com.dk.assistant.auto.AutoLog.add(svc, "रुका: $r"); sayFailed(r); return
                    }
                    com.dk.assistant.auto.AutoLog.add(svc, "कदम $step: स्क्रीन पढ़ी (${screen.packageName})")
                }
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
            // Baatcheet me "सोचकर बताता हूँ" jaisa kuch nahi bolna - seedha jawab. Phone ke kaam me 1.5 sec baad sirf "ठीक है।"
            val filler = if (step == 1 && !chatMode) scope.launch { delay(1500); sayQuick("ठीक है।") } else null
            val resp: JSONObject = try {
                brain.next(prompt + if (chatMode && brain.canSearch()) "\nSEARCH AVAILABLE" else "", shot,
                    memoryNote = if (chatMode) "USER SAYS: $goal" else feedback, chat = chatMode)
            } catch (e: Exception) {
                filler?.cancel()
                DKLog.d("AI error: ${e.javaClass.simpleName} ${e.message}")
                // v3.88: फ़ोन पर इंटरनेट ही नहीं = काम कतार में (खोए नहीं), नेटवर्क आते ही अपने-आप आगे
                if (e !is kotlinx.coroutines.CancellationException && !NetProbe.online(svc) && OfflineQueue.add(svc, goal)) {
                    val q = "इंटरनेट नहीं है। आपका काम सँभाल कर रख लिया है — नेटवर्क आते ही अपने-आप आगे करूँगी।"
                    DKState.replyFailed(q)
                    if (!silent) speaker.speak(q)
                    return
                }
                val msg = brain.explain(e)
                DKState.replyFailed(msg + "\n(" + (e.message ?: e.javaClass.simpleName).take(160) + ")")
                if (!silent) speaker.speak(msg)
                return
            }
            DKLog.d("AI ${System.currentTimeMillis() - t0}ms (${brain.lastUsed}): $resp")
            // filler shuru nahi hua to rok do; bol raha ho to poora hone do (beech me na kate)
            if (filler != null) { if (filler.isActive && System.currentTimeMillis() - t0 < 1300) filler.cancel() else filler.join() }

            resp.optString("remember").takeIf { it.isNotBlank() }?.let { Memory.addFact(svc, it) }

            if (chatMode && (resp.optBoolean("need_research", false) || resp.optBoolean("need_search", false))) {
                // AI ko taaza jaankari chahiye -> tez search (gehri research nahi)
                if (searchAndSay(goal, fillerSaid = filler != null && !filler.isCancelled, deep = false)) return
            }

            if (chatMode && resp.optBoolean("need_screen", false)) {
                chatMode = false; step--
                continue
            }

            val sayText = resp.optString("say", "")
            autoS?.let { if (sayText.isNotBlank()) com.dk.assistant.auto.AutoLog.add(svc, "DK: " + sayText.take(160)) }
            val actions = resp.optJSONArray("actions")
            val hasActions = actions != null && actions.length() > 0
            var done = resp.optBoolean("done", !hasActions)

            // v3.33: कार्ट वाला काम "पूरा" बताने से पहले स्क्रीन पर पुष्टि देखो (बोलना जाँच के बाद)
            val cartCheck = cartGoal && done && !chatMode
            // v3.46: मैसेज डिलीट भी - स्क्रीन पर "This message was deleted" दिखे बिना "डिलीट हो गया" नहीं
            val delCheck = deleteGoal && done && !chatMode && !cartCheck
            // Bolna aur kaam saath-saath
            // v3.51: "कर दिया/भेज दिया" वाली बात कदम चलने से पहले ही बोल दी जाती थी - कदम फेल हो तब भी (= झूठ).
            // अब ऐसा दावा कदमों के नतीजे देखकर ही बोलो; "खोल रहा हूँ" जैसी बात पहले जैसी साथ-साथ.
            val holdSay = done && hasActions && !cartCheck && !delCheck && HonestGuard.claimsDone(sayText)
            val speakJob = if (sayText.isNotBlank() && !cartCheck && !delCheck && !holdSay) scope.launch { say(sayText) } else null

            val results = StringBuilder()
            val prevPkg = svc.currentPackage()
            var opensApp = false
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
                        val ans = svc.hearForAgent() ?: "(user ne kuch nahi bola)"
                        DKState.user(ans)
                        DKLog.d("User answer: $ans")
                        results.append("ask -> USER ANSWERED: \"$ans\"\n")
                        done = false
                        break
                    }
                    if (a.optString("type") in APP_OPENERS) opensApp = true
                    // ऑटोनोमस: भेजना / पोस्ट / डिलीट जैसा कदम = पुष्टि वाला switch चालू हो तो पहले एक बार पूछो
                    autoS?.let { s ->
                        if (s.confirm && com.dk.assistant.auto.AutoSafety.irreversible(a, s.screen, s.pkg)) {
                            speakJob?.join()
                            val what = com.dk.assistant.auto.AutoSafety.describe(a, s.screen)
                            say("यह कदम वापस नहीं होगा: $what। क्या मैं करूँ? हाँ या नहीं बोलिए।")
                            DKState.set(Mode.LISTENING)
                            val ans = svc.hearForAgent()
                            ans?.let { DKState.user(it) }
                            val yes = com.dk.assistant.auto.AutoSafety.yes(ans)
                            com.dk.assistant.auto.AutoLog.add(svc, "पुष्टि माँगी ($what): " + if (yes) "हाँ" else "नहीं / जवाब नहीं")
                            if (!yes) { say("ठीक है, यह कदम नहीं किया। काम यहीं रोक दिया।"); return }
                            DKState.set(Mode.WORKING)
                        }
                    }
                    val r = svc.perform(a)
                    DKLog.d("Action ${a.optString("type")} -> $r")
                    if (a.optString("type") == "youtube_play" && r.startsWith("PLAYING")) done = true
                    results.append("${a.optString("type")} -> $r\n")
                    // ऑटोनोमस: हर कदम लॉग में; बार-बार fail / वही कदम बार-बार = रुककर बताओ (अंतहीन कोशिश नहीं)
                    autoS?.let { s ->
                        com.dk.assistant.auto.AutoLog.add(svc, "कदम: " + com.dk.assistant.auto.AutoSafety.describe(a, s.screen) + " → " + r.take(120))
                        if (s.stuck.note(com.dk.assistant.auto.AutoSafety.sig(a, step), HonestGuard.actionFailed(r))) {
                            val m = "मैं इस काम में अटक गई — एक ही कदम बार-बार नहीं हो पा रहा, इसलिए रुक गई। आखिरी नतीजा: ${r.take(80)}"
                            com.dk.assistant.auto.AutoLog.add(svc, "रुका: अटक गया")
                            sayFailed(m); return
                        }
                    }
                    delay(250)
                }
            }

            if (done && cartCheck) {
                val tv = System.currentTimeMillis()
                if (hasActions) svc.waitSettle(prevPkg, false, 3000)
                val ok = LocalRouter.cartConfirmed(svc.readScreen().text)
                DKLog.d("जाँच: कार्ट ${if (ok) "में जुड़ना स्क्रीन पर दिखा" else "की पुष्टि स्क्रीन पर नहीं दिखी"} (${System.currentTimeMillis() - tv} ms)")
                val claims = Regex("(?i)cart|कार्ट|जोड़|डाल दिया|added").containsMatchIn(sayText)
                say(when {
                    ok -> sayText.ifBlank { "कार्ट में जोड़ दिया।" }
                    claims || sayText.isBlank() -> "मैंने कोशिश की, पर स्क्रीन पर कार्ट में जुड़ने की पुष्टि नहीं दिखी। एक बार कार्ट खोलकर देख लीजिए।"
                    else -> sayText
                })
                return
            }
            if (done && delCheck) {
                val tv = System.currentTimeMillis()
                if (hasActions) svc.waitSettle(prevPkg, false, 3000)
                var ok = ActionCheck.deleteConfirmed(svc.readScreen().text)
                if (!ok) { delay(700); ok = ActionCheck.deleteConfirmed(svc.readScreen().text) }   // WhatsApp कभी थोड़ी देर में बदलता है
                DKLog.d("जाँच: मैसेज ${if (ok) "डिलीट होना स्क्रीन पर दिखा" else "डिलीट होने की पुष्टि स्क्रीन पर नहीं दिखी"} (${System.currentTimeMillis() - tv} ms)")
                if (ok) say(sayText.ifBlank { "मैसेज डिलीट हो गया, स्क्रीन पर पक्का दिखा।" })
                else sayFailed("मैंने कोशिश की, पर स्क्रीन पर मैसेज डिलीट होने की पुष्टि नहीं दिखी। एक बार चैट खोलकर देख लीजिए।")
                return
            }
            if (holdSay && done) {
                val failed = results.lines().any { HonestGuard.actionFailed(it.substringAfter("-> ", "")) }
                if (!failed) { say(sayText); return }
                DKLog.d("सच-जाँच: कदम फेल हुआ - \"${sayText.take(40)}\" नहीं बोला, AI से फिर देखने को कहा")
                done = false
                results.append("NOTE: an action above FAILED, so the task is NOT done yet. Fix it, or tell the user honestly that it could not be done - never say it is done.\n")
            }
            if (done) { speakJob?.join(); return }
            if (chatMode) chatMode = false   // AI ne actions diye - ab screen dekh kar kaam karo
            svc.waitSettle(prevPkg, opensApp) // naya app khule + screen tham jaye, tab agla kadam
            feedback = "USER GOAL (same task, continue): $goal\nRESULTS OF YOUR LAST ACTIONS:\n$results"
        }
        // v3.51: यह "पूरा" नहीं - Live को FAILED जाए ताकि वह "कर दिया" न बोले
        sayFailed("बहुत सारे स्टेप हो गए, मैंने काम यहीं रोक दिया - काम पूरा नहीं हुआ।")
    }

    private enum class ChatOut { DONE, SCREEN, SEARCH, THINK }

    /**
     * Baatcheet ka jawab streaming se: AI jaise-jaise likhta hai, pehla vakya poora hote hi DK bolna shuru.
     * Kai keys ho to sabse tez/chalti hui (BrainManager race karata hai). Sab fail = DK khud bolkar kaaran batata hai.
     */
    private suspend fun chatStream(goal: String, note: String): ChatOut {
        DKState.set(Mode.THINKING)
        val prompt = (if (note.isNotBlank()) "$note\n\n" else "") + "USER SAYS: $goal" +
            (if (brain.canSearch()) "\nSEARCH AVAILABLE" else "") + (if (brain.canThink()) "\nTHINK AVAILABLE" else "")
        val ch = Channel<String>(Channel.UNLIMITED)
        var speakJob: Job? = null
        val buf = StringBuilder()
        val pend = StringBuilder()
        val shown = StringBuilder()
        val facts = mutableListOf<String>()
        var mode = 0            // 0 = abhi pata nahi, 1 = saada jawab, 2 = [SCREEN]/[SEARCH], 3 = JSON
        var tag = ""
        var sentCount = 0
        val t0 = System.currentTimeMillis()

        fun emit(raw: String) {
            var x = raw.trim()
            if (x.isEmpty()) return
            val yi = x.indexOf("[YAAD")
            if (yi >= 0) { x.substring(yi).substringAfter("]").trim().takeIf { it.isNotBlank() }?.let { facts.add(it) }; x = x.substring(0, yi).trim() }
            for (t in listOf("[SCREEN]", "[SEARCH]", "[THINK]")) if (x.contains(t)) { if (shown.isEmpty()) tag = t; x = x.replace(t, "").trim() }
            x = x.replace(Regex("[*#`]"), "").trim()
            if (x.isEmpty()) return
            shown.append(x).append(' ')
            synchronized(currentAnswer) { currentAnswer.append(x).append(' ') }
            // saada jawab (mode 1) screen par har shabd ke saath showLive() dikhata hai; yahan sirf baaki (JSON) ke liye
            if (mode != 1) DKState.replyLive(shown.toString().trim())
            if (sentCount == 0) DKLog.d("Deri: AI ka pehla vakya ${System.currentTimeMillis() - t0} ms")
            sentCount++
            if (!silent) {
                ch.trySend(x)
                if (speakJob == null) speakJob = scope.launch { speaker.speakStream(ch) }
            }
        }

        // v3.8: chat me jawab SHABD-SHABD (token) dikhe - poora vakya banne ka intezaar nahi.
        // (Awaaz ke liye vakya-tukde feed() hi banata hai.) YAAD / SCREEN jaise tag screen par na aayen.
        var lastShown = ""
        fun showLive() {
            var x = shown.toString() + pend.toString()
            x.indexOf("[YAAD").takeIf { it >= 0 }?.let { x = x.substring(0, it) }
            for (t in listOf("[SCREEN]", "[SEARCH]", "[THINK]")) x = x.replace(t, "")
            val ob = x.lastIndexOf('['); if (ob >= 0 && x.indexOf(']', ob) < 0) x = x.substring(0, ob)   // aadha tag
            x = x.replace(Regex("[*#`]"), "").replace(Regex("\\s+"), " ").trim()
            if (x.isNotEmpty() && x != lastShown) {
                if (lastShown.isEmpty()) DKLog.d("Deri: Send → स्क्रीन पर पहला शब्द ${System.currentTimeMillis() - (DKState.sentAt.takeIf { it > 0 && System.currentTimeMillis() - it < 120_000 } ?: t0)} ms")
                lastShown = x; DKState.replyLive(x)
            }
        }

        /** Tukdon ko vakya me todo: pehla vakya chhota (jaldi awaaz), baaki ~160 akshar tak */
        fun feed(d: String) {
            pend.append(d)
            while (true) {
                var cut = -1
                for (i in pend.indices) {
                    val c = pend[i]
                    if (c == '।' || c == '?' || c == '!' || c == '\n') { cut = i; break }
                    if (c == '.' && i + 1 < pend.length && pend[i + 1].isWhitespace()) { cut = i; break }
                }
                if (cut < 0) {
                    // v3.7: pehla tukda aur chhota (45) = awaaz jaldi shuru
                    val limit = if (sentCount == 0) 45 else 170
                    val minCut = if (sentCount == 0) 12 else 20
                    if (pend.length > limit) {
                        val c2 = pend.lastIndexOf(",").takeIf { it > minCut } ?: pend.lastIndexOf(" ").takeIf { it > minCut } ?: return
                        cut = c2
                    } else return
                }
                emit(pend.substring(0, cut + 1)); pend.delete(0, cut + 1)
            }
        }

        DKState.sentAt.takeIf { it > 0 && System.currentTimeMillis() - it < 120_000 }?.let {
            DKLog.d("Deri: Send → AI request ${System.currentTimeMillis() - it} ms (बीच की जाँचें)")
        }
        val full = try {
            brain.streamChat(prompt, "USER SAYS: $goal") { d ->
                buf.append(d)
                when (mode) {
                    0 -> {
                        val t = buf.toString().trimStart()
                        if (t.isEmpty()) return@streamChat
                        if (t[0] == '[') {
                            val close = t.indexOf(']')
                            if (close < 0 && t.length < 12) return@streamChat
                            val tg = if (close > 0) t.substring(0, close + 1).uppercase() else ""
                            if (tg == "[SCREEN]" || tg == "[SEARCH]" || tg == "[THINK]") { mode = 2; tag = tg } else { mode = 1; feed(t); showLive() }
                        } else if (t[0] == '{') mode = 3
                        else { mode = 1; feed(t); showLive() }
                    }
                    1 -> { feed(d); showLive() }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            ch.close(); throw e
        } catch (e: Exception) {
            DKLog.d("Chat stream fail: ${e.message}")
            if (mode == 1) feed("\n")
            ch.close()
            speakJob?.join()
            if (shown.isNotEmpty()) { DKState.reply(shown.toString().trim()); return ChatOut.DONE }
            // AI nahi chala: pehle seekha hua milta-julta jawab, warna DK khud bataye kya dikkat hai
            LocalBrain.recall(svc, goal, exact = false)?.let { say(it); return ChatOut.DONE }
            val why = brain.explainAll(e)
            DKState.replyFailed(why); if (!silent) speaker.speak(why)
            return ChatOut.DONE
        }

        if (mode == 3) {
            val o = try { JSONObject(full.substring(full.indexOf('{'), full.lastIndexOf('}') + 1)) } catch (_: Exception) { JSONObject().put("say", full) }
            when {
                o.optBoolean("need_screen") -> { ch.close(); return ChatOut.SCREEN }
                o.optBoolean("need_search") || o.optBoolean("need_research") -> { ch.close(); return ChatOut.SEARCH }
            }
            o.optString("remember").takeIf { it.isNotBlank() }?.let { facts.add(it) }
            speaker.sentences(o.optString("say")).forEach { emit(it) }
        } else if (mode == 1) feed("\n")
        if (mode == 0 && full.isNotBlank()) speaker.sentences(full).forEach { emit(it) }
        ch.close()
        facts.forEach { Memory.addFact(svc, it) }
        if (mode == 2 || (tag.isNotEmpty() && shown.isEmpty())) {
            speakJob?.join()
            return when (tag) { "[SCREEN]" -> ChatOut.SCREEN; "[THINK]" -> ChatOut.THINK; else -> ChatOut.SEARCH }
        }
        val answer = shown.toString().trim()
        if (answer.isEmpty()) {
            val m = "माफ़ कीजिए, जवाब खाली आया। एक बार फिर बोलिए।"
            DKState.reply(m); if (!silent) speaker.speak(m); return ChatOut.DONE
        }
        DKState.reply(answer)
        // roz seekho: agli baar yahi sawaal = turant jawab
        if (note.isBlank()) scope.launch(Dispatchers.IO) { LocalBrain.learn(svc, goal, answer) }
        speakJob?.join()
        return ChatOut.DONE
    }

    /** Mushkil sawaal: sabse taakatwar model se soch-samajhkar jawab (orb "soch raha" rang dikhata hai) */
    private suspend fun thinkAndSay(goal: String): Boolean {
        DKState.set(Mode.THINKING); DKState.hint("गहराई से सोच रहा है…")
        return try {
            val r = brain.think(goal)
            if (r.text.isBlank()) false else {
                // sources sirf screen par (click ho sakne wale), awaaz me sirf jawab. Search nahi hua = sources khali
                DKState.reply(r.text, r.sources)
                if (!silent) speaker.speak(r.text)
                // taaza (search wala) jawab yaad mat karo - kal purana ho jayega
                if (r.sources.isEmpty()) LocalBrain.learn(svc, goal, r.text)
                true
            }
        } catch (e: Exception) {
            DKLog.d("Gehri soch fail: ${e.message}")
            false
        }
    }

    private val SEARCH_FAILED_NOTE = "INTERNET SEARCH FAILED (Tavily, Exa and Google search were all tried). Do NOT reply [SEARCH]. " +
        "Start your reply with exactly \"${WebSearch.ALL_FAILED_LINE}।\" then answer from your own knowledge, and say it may not be the latest. " +
        "Never invent exact live figures (today's price, score, weather, results) - for those just say you could not check right now."

    private suspend fun searchAndSay(goal: String, fillerSaid: Boolean = false, deep: Boolean = false, thorough: Boolean = false): Boolean {
        // v3.29 (user ki maang): search karna pade to sach bolo - "एक सेकंड, सर्च करके बताता हूँ" (search saath-saath chalta hai, deri nahi)
        DKState.set(Mode.THINKING); DKState.hint(if (deep) "इंटरनेट पर गहराई से खोज रहा है…" else "इंटरनेट पर खोज रहा है…")
        return try {
            val r = kotlinx.coroutines.coroutineScope {
                val d = async(Dispatchers.IO) { brain.search(goal, deep, thorough) }
                if (!silent && !fillerSaid) {
                    val ack = Lang.pick("एक सेकंड, सर्च करके " + (if (Prefs.voiceGender(svc) != "male") "बताती हूँ।" else "बताता हूँ।"), "One second, let me search and tell you.")
                    try { speaker.speakQuick(ack) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                }
                d.await()
            }
            if (r.text.isBlank()) false else {
                // screen par jawab + kahan se mila (click ho sakne wale links); bolkar sirf jawab
                DKState.reply(r.text, r.sources)
                if (!silent) speaker.speak(r.text)
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            DKLog.d("Search fail: ${e.message?.take(120)}")
            // "no_search_needed" = खोज की ज़रूरत ही नहीं थी (सामान्य ज्ञान) - वह "खोज नहीं हो पाई" नहीं
            if (e.message != "no_search_needed") searchFailed = true
            false
        }
    }

    /** v3.93: इस सवाल पर इंटरनेट खोज (Tavily -> Exa -> Gemini) हुई पर तीनों न चलीं */
    @Volatile private var searchFailed = false

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
        /** Ye actions naya app/screen kholte hain - agle kadam se pehle uske khulne ka intezaar */
        val APP_OPENERS = setOf("open_app", "youtube_search", "youtube_play", "web_search", "open_url", "shop_search",
            "directions", "navigate", "map_search", "open_settings", "dial")
        const val CREATOR_ANSWER = AssistantBrand.CREATOR_ANSWER
    }
}
