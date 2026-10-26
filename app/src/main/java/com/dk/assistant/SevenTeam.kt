package com.dk.assistant

import com.dk.assistant.Seven.Agent
import org.json.JSONObject

/** एजेंटों की AI - हर कॉल में उस एजेंट का अपना छोटा system prompt (कोडिंग एजेंट की अपनी API key से) */
interface TeamAi {
    suspend fun ask(agent: Agent, system: String, msgs: List<Pair<String, String>>): String
    /** user की फ़ोटो/वीडियो के साथ (मॉडल न देख सके तो बिना - लिखा विवरण फिर भी जाता है) */
    suspend fun askMedia(agent: Agent, system: String, msgs: List<Pair<String, String>>, media: List<CoderMedia>): String = ask(agent, system, msgs)
    /** एक request में यह मॉडल कितने अक्षर पढ़ सकता है - सब एजेंट इसी हिसाब से पूरी फ़ाइलें / पूरे log भेजते हैं */
    val budget: Int get() = CoderContext.DEFAULT_BUDGET
}

/** काम की फ़ाइलें: GitHub मोड = चुना रिपो/branch, नॉर्मल मोड = user के भेजे कोड + नई फ़ाइलें (फ़ोन की memory में) */
interface TeamFiles {
    suspend fun list(): List<String>
    /** अभी की हालत (लागू हो चुके बदलाव समेत). null = फ़ाइल नहीं */
    suspend fun read(path: String): String?
    /** काम शुरू होने से पहले की हालत */
    suspend fun original(path: String): String?
    suspend fun stage(path: String, content: String?)
    fun staged(): Map<String, String?>
    /** पूरा workspace (नाम-जाँच के लिए): path -> content, बदलाव समेत. null = नहीं दे सकते */
    suspend fun all(): Map<String, String>? = null
    /** all() पूरा रिपो है (GitHub मोड)? false = user की भेजी कुछ फ़ाइलें */
    val whole: Boolean get() = false
}

/** टेस्ट/बिल्ड का नतीजा. ok = null: नतीजा देख नहीं पाए (जैसे token में Actions की इजाज़त नहीं) */
data class TeamTest(val ok: Boolean?, val log: String, val url: String = "", val note: String = "", val stage: String = "") {
    companion object {
        /** PR से पहले, सिर्फ़ branch पर चला build (पास हो तभी PR) */
        const val BRANCH = "branch"
        /** PR का अपना चेक */
        const val PR = "pr"
    }
}

/** गिट एजेंट के हाथ (सिर्फ़ GitHub मोड) */
interface TeamGit {
    /** काम शुरू होने से पहले: backup branch (backup/dk-तारीख-समय) */
    suspend fun backup(task: String): String
    /** main से नई काम की branch (main पर कभी सीधे नहीं) */
    suspend fun branch(task: String): String
    /** बदलाव उसी branch पर push + (टेस्ट चलाने के लिए) draft PR + GitHub Actions का नतीजा */
    suspend fun pushAndTest(commit: String, title: String, summary: String, rootCause: String, onStep: (String) -> Unit): TeamTest
    /** टेस्ट पास: PR को review के लिए तैयार (draft हटाओ). merge कभी नहीं - वह सिर्फ़ user की मंज़ूरी से */
    suspend fun ready(): String
    val prUrl: String
    /** branch पर build पास होने के बाद PR खोलो (पहले नहीं). @return PR url, "" = नहीं बना (वजह prNote में) */
    suspend fun openPr(): String = prUrl
    /** PR क्यों नहीं बना / किस हाल में है */
    val prNote: String get() = ""
}

/**
 * 7 एजेंट, तय क्रम में. नया फ़ीचर: मैनेजर → गिट → प्लानर → (कोडर → रिव्यूअर → लागू)×कदम → टेस्टर → (डिबगर → प्लानर → …)* → गिट PR → रिपोर्ट.
 * बग: मैनेजर → गिट → डिबगर (जड़) → प्लानर (न्यूनतम सुधार) → कोडर/रिव्यूअर → टेस्टर → …
 * git = null → नॉर्मल मोड (गिट एजेंट बंद; बाकी क्रम वही).
 */
class SevenTeam(
    private val ai: TeamAi,
    private val files: TeamFiles,
    private val git: TeamGit?,
    /** true = 11-एजेंट (प्रॉम्प्ट इंजीनियर, नक्शा+खोज, जगह-जाँच, परफॉरमेंस, QA). false = पुराना 7-एजेंट क्रम जैसा का तैसा */
    private val full: Boolean = false,
    private val ev: (Agent, String) -> Unit
) {
    data class Outcome(val report: String, val files: Map<String, String?>, val testOk: Boolean?, val mode: Seven.Mode, val answerOnly: Boolean = false)

    val mode get() = if (git != null) Seven.Mode.GITHUB else Seven.Mode.NORMAL
    private val changes = linkedMapOf<String, String>()
    private val bugs = mutableListOf<Seven.Bug>()
    private var backup = ""
    private var lastBlock = ""
    private var task = ""
    private var brief = ""
    private var acceptance = listOf<String>()
    private var passNote = ""
    private var lastTest: TeamTest? = null
    private var noCi = false
    private var commitMsg: Pair<String, String>? = null
    // 11-एजेंट
    private var spec = ""
    private var notTouch = listOf<String>()
    private var area = listOf<String>()
    private var repoMap: String? = null
    private var qaOk = false
    private var qaRounds = 0
    private var qaFix = ""
    private var qaChecks = listOf<String>()
    private val perfNotes = mutableListOf<String>()
    /** user की भेजी फ़ोटो/वीडियो (प्रॉम्प्ट इंजीनियर, प्लानर, डिबगर, QA इन्हें खुद देखते हैं) */
    var media: List<CoderMedia> = emptyList()
    /** फ़ोटो/वीडियो में क्या दिखा - लिखा हुआ, हर एजेंट को */
    private var mediaNotes = ""
    /** अभी चल रहा पूरा प्लान (कोडर को पूरी तस्वीर) */
    private var curPlan: Seven.Plan? = null

    // ---- पढ़ने की क्षमता: मॉडल जितना पढ़ सकता है उसी हिसाब से (पहले हर मॉडल पर एक जैसी छोटी सीमा थी)
    private val big get() = ai.budget.coerceAtLeast(CoderContext.MIN_BUDGET)
    /** एक फ़ाइल इतनी तक पूरी (बड़े मॉडल पर पूरी बड़ी फ़ाइल) */
    private val fileChars get() = maxOf(FILE_CHARS, big / 4)
    private val readBudget get() = maxOf(READ_BUDGET, big * 6 / 10)
    private val debugBudget get() = maxOf(SevenDebug.DEBUG_READ_BUDGET, big / 2)
    private val listMax get() = maxOf(LIST_MAX, 2_000)
    private val mapChars get() = maxOf(24_000, big / 8)
    /** पुराने पढ़े नतीजे कितने बड़े तक वैसे ही रहें */
    private fun compactOld(msgs: MutableList<Pair<String, String>>) =
        SevenDebug.compact(msgs, keepLast = if (big >= 300_000) 8 else 4, bigChars = maxOf(3_000, big / 40))

    /** हर एजेंट की AI कॉल: टीम के नियम (सावधानी, पूरा पढ़ना, पूरा समझाकर आगे देना) + फ़ोटो/वीडियो का विवरण; देखने वाले एजेंटों को असली फ़ोटो भी */
    private suspend fun aiAsk(a: Agent, system: String, msgs: List<Pair<String, String>>): String {
        val sys = system + "\n\n" + (if (a == Agent.CODER) Seven.Prompts.RULES_CODER else Seven.Prompts.RULES) +
            if (mediaNotes.isNotBlank()) "\n\nUSER'S PHOTOS / VIDEO - what the prompt engineer saw in them (the user attached them to this task):\n$mediaNotes" else ""
        return if (media.isNotEmpty() && a in SEES_MEDIA) ai.askMedia(a, sys, msgs, media) else ai.ask(a, sys, msgs)
    }

    companion object {
        const val FILE_CHARS = 40_000          // एक फ़ाइल इससे बड़ी = anchor के आस-पास का हिस्सा
        const val READ_BUDGET = 150_000        // प्लानर/डिबगर एक बार में इतना पढ़ें
        const val LIST_MAX = 800
        /** गिट: रिपो में GitHub Actions का कोई workflow नहीं */
        const val NO_CI = "NO_CI"
        /** QA कितनी बार काम वापस भेज सकता है (फिर रुककर user को रिपोर्ट) */
        const val QA_ROUNDS = 2
        /** 11-एजेंट डिबगर: कुल राउंड, और आख़िरी कितने राउंड में खोज/पढ़ाई बंद (सिर्फ़ फ़ैसला) */
        const val DEBUG_ROUNDS = 14
        const val FORCE_ROUNDS = 3
        /** ये एजेंट user की फ़ोटो/वीडियो खुद देखते हैं (बाकी को लिखा विवरण) */
        val SEES_MEDIA = setOf(Agent.PROMPT, Agent.PLANNER, Agent.DEBUGGER, Agent.QA)
        /** run() में मैनेजर ने कहा: यह रिपो/PR का सवाल है - जाँचकर्ता जवाब दे */
        const val STATUS_ASK = "\u0000STATUS_ASK"
    }

    // ------------------------------------------------------------------ समझ (मैनेजर + प्रॉम्प्ट इंजीनियर) - code छूने से पहले
    /**
     * मैनेजर काम का प्रकार पहचाने, प्रॉम्प्ट इंजीनियर user की बात को साफ़ spec में बदले (नक्शा + खोज से जगह ढूँढकर).
     * यहाँ कोई branch / बदलाव नहीं. नतीजा user को दिखाकर "हाँ" का इंतज़ार - फिर run(task, समझ).
     */
    suspend fun understand(userTask: String): SevenPlus.Understanding {
        task = userTask
        if (media.isNotEmpty()) seeMedia()
        ev(Agent.MANAGER, "काम समझ रहा हूँ…")
        val fileList = files.list()
        val m = askJson(Agent.MANAGER, "USER REQUEST:\n$task\n" + (if (media.isNotEmpty()) CoderMedia.describe(media) + "\n" else "") +
            "\nMODE: $mode\nFILES IN WORKSPACE (${fileList.size}):\n" + fileList.take(listMax).joinToString("\n"))
        val kind = Seven.kindOf(m?.optString("kind"), task)
        val brief0 = m?.optString("brief").orEmpty().trim()
        val acc0 = Seven.strings(m?.optJSONArray("acceptance"))
        if (kind == Seven.Kind.STATUS) {
            ev(Agent.MANAGER, "यह रिपो / PR / काम की हालत का सवाल है → ${Seven.INSPECTOR_HI} को सौंप रहा हूँ (वह GitHub खुद जाँचकर बताएगा)")
            return SevenPlus.Understanding(kind, brief0, "", "", emptyList(), emptyList(), emptyList(), emptyList(), mediaNotes = mediaNotes)
        }
        if (kind == Seven.Kind.QUESTION) {
            val ans = m?.optString("answer").orEmpty().trim()
            if (ans.isNotBlank()) {
                ev(Agent.MANAGER, "यह सवाल है — कोड बदलने की ज़रूरत नहीं")
                return SevenPlus.Understanding(kind, brief0, "", "", emptyList(), emptyList(), emptyList(), emptyList(), ans, mediaNotes = mediaNotes)
            }
        }
        val k = if (kind == Seven.Kind.QUESTION) Seven.Kind.FEATURE else kind
        ev(Agent.MANAGER, if (k == Seven.Kind.BUG) "यह बग ठीक करने का काम लगता है → प्रॉम्प्ट इंजीनियर साफ़ करेगा, फिर डिबगर जड़ ढूँढेगा"
            else "यह नया फ़ीचर/बदलाव लगता है → प्रॉम्प्ट इंजीनियर साफ़ करेगा कि ठीक-ठीक क्या और कहाँ")
        ev(Agent.PROMPT, "आपकी बात को साफ़ काम में बदल रहा हूँ — रिपो का नक्शा देखकर जगह भी ढूँढ रहा हूँ…")
        val ws = try { workspace() } catch (_: Exception) { emptyMap() }
        val msgs = mutableListOf("user" to ("USER REQUEST (user's own words):\n$task\n" + (if (media.isNotEmpty()) CoderMedia.describe(media) + "\n" else "") +
            "\nMANAGER: kind=${k.name.lowercase()}" +
            (if (brief0.isNotBlank()) "; brief=$brief0" else "") + "\nMODE: $mode\n" +
            "REPO MAP (file: classes / functions / ids):\n" + map(ws) + "\nFILE LIST (${fileList.size}):\n" + fileList.take(listMax).joinToString("\n")))
        for (round in 0..4) {
            val reply = aiAsk(Agent.PROMPT, Seven.Prompts.PROMPT, msgs)
            val terms = SevenPlus.RepoSearch.terms(reply)
            val reads = Seven.planReads(reply)
            if (round < 4 && terms != null) {
                ev(Agent.PROMPT, "रिपो में खोज रहा हूँ: " + terms.joinToString())
                msgs.add("model" to reply); msgs.add("user" to SevenPlus.RepoSearch.search(ws, terms) + "\nNow reply with the JSON, or search/read more.")
                continue
            }
            if (round < 4 && reads != null) {
                ev(Agent.PROMPT, "पढ़ रहा हूँ: " + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to readFiles(reads) + "\nNow reply with the JSON, or search/read more.")
                continue
            }
            val o = Seven.json(reply)
            if (o != null && (o.has("understood") || o.has("spec"))) {
                val u = SevenPlus.Understanding.parse(o, k, brief0, acc0).copy(mediaNotes = mediaNotes)
                ev(Agent.PROMPT, if (u.needsAnswer) "कुछ बातें साफ़ नहीं — आपसे पूछ रहा हूँ" else "समझ तैयार — आपकी मंज़ूरी का इंतज़ार")
                return u
            }
            msgs.add("model" to reply); msgs.add("user" to "Reply ONLY the JSON {\"understood\":…,\"spec\":…,\"acceptance\":[…],\"not_touch\":[…],\"area\":[…],\"questions\":[…]}")
        }
        ev(Agent.PROMPT, "पूरा spec नहीं बना — मैनेजर की समझ दिखा रहा हूँ")
        return SevenPlus.Understanding(k, brief0, brief0.ifBlank { "मैं आपकी बात को पूरी तरह स्पष्ट नहीं कर पाया" }, brief0, acc0, emptyList(), listOf("मैं आपकी माँग को ठीक से समझकर पूरा spec नहीं बना पाया। कृपया एक-दो वाक्यों में बताइए कि क्या बदलना है और कौन-सी मौजूदा चीज़ें बिल्कुल नहीं बदलनी चाहिए।"), emptyList(), mediaNotes = mediaNotes)
    }

    /** प्रॉम्प्ट इंजीनियर पहले फ़ोटो/वीडियो ध्यान से देखे - जो दिखा वह लिखकर पूरी टीम को (जिनका मॉडल फ़ोटो न देख सके उन्हें भी) */
    private suspend fun seeMedia() {
        ev(Agent.PROMPT, "आपकी ${CoderMedia.chip(media).removePrefix("📎 ")} ध्यान से देख रहा हूँ" + if (media.any { it.isVideo }) " (वीडियो frame-दर-frame)…" else "…")
        mediaNotes = try {
            ai.askMedia(Agent.PROMPT, Seven.Prompts.MEDIA + "\n\n" + Seven.Prompts.RULES,
                listOf("user" to "USER REQUEST:\n$task\n\n" + CoderMedia.describe(media) + "\nDescribe them completely now."), media).trim()
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; "" }
        if (mediaNotes.isBlank()) { mediaNotes = CoderMedia.describe(media) + " (the model could not look at them)"; ev(Agent.PROMPT, "⚠ फ़ोटो/वीडियो नहीं देख पाया — सिर्फ़ आपके शब्दों से समझूँगा") }
        else ev(Agent.PROMPT, "फ़ोटो/वीडियो में देखा:\n" + mediaNotes.take(600))
    }

    private fun map(ws: Map<String, String>): String = repoMap ?: SevenPlus.RepoMap.build(ws, mapChars).also { repoMap = it }

    /** @param pre user की मंज़ूर की हुई समझ (11-एजेंट). null = मैनेजर यहीं समझेगा (पुराना क्रम) */
    suspend fun run(userTask: String, pre: SevenPlus.Understanding? = null): Outcome {
        task = userTask
        val kind: Seven.Kind
        if (pre != null) {
            // 1. मैनेजर: समझ user ने मंज़ूर कर दी - वही spec सबको
            kind = pre.kind
            spec = pre.spec; brief = pre.spec.ifBlank { pre.brief }; acceptance = pre.acceptance; notTouch = pre.notTouch; area = pre.area
            if (pre.mediaNotes.isNotBlank()) mediaNotes = pre.mediaNotes
            ev(Agent.MANAGER, "आपकी मंज़ूरी मिली — मंज़ूर की हुई समझ के हिसाब से काम सौंप रहा हूँ")
        } else {
            // 1. मैनेजर: काम समझो
            ev(Agent.MANAGER, "काम समझ रहा हूँ…")
            val fileList = files.list()
            val m = askJson(Agent.MANAGER, "USER REQUEST:\n$task\n\nMODE: $mode\nFILES IN WORKSPACE (${fileList.size}):\n" + fileList.take(listMax).joinToString("\n"))
            kind = Seven.kindOf(m?.optString("kind"), task)
            if (kind == Seven.Kind.STATUS) return Outcome(STATUS_ASK, emptyMap(), null, mode, answerOnly = true)
            if (kind == Seven.Kind.QUESTION) {
                val ans = m?.optString("answer").orEmpty().trim()
                if (ans.isNotBlank()) {
                    ev(Agent.MANAGER, "यह सवाल है — कोड बदलने की ज़रूरत नहीं")
                    return Outcome(ans, emptyMap(), null, mode, answerOnly = true)
                }
            }
            brief = m?.optString("brief").orEmpty().trim()
            acceptance = Seven.strings(m?.optJSONArray("acceptance"))
        }
        val bug = kind == Seven.Kind.BUG
        ev(Agent.MANAGER, (if (bug) "यह बग ठीक करने का काम है → डिबगर पहले जड़ ढूँढेगा" else "यह नया फ़ीचर/बदलाव है → प्लानर पहले फ़ाइलें पढ़ेगा") +
            if (acceptance.isNotEmpty()) "\nपूरा होने की शर्तें:\n" + acceptance.joinToString("\n") { "• $it" } else "")

        // 2. गिट: backup + नई branch (सिर्फ़ GitHub मोड)
        git?.let { g ->
            backup = g.backup(task)
            ev(Agent.GIT, if (backup.isNotBlank()) "backup branch बनाई: $backup" else "backup branch नहीं बन पाई — फिर भी main को हाथ नहीं लगेगा")
            ev(Agent.GIT, "नई branch: ${g.branch(task)} — सारा काम इसी में, main अछूता")
        }

        // 3. प्लान (बग: पहले डिबगर)
        var rootCause = ""
        val plan = if (bug) {
            val d = debug("USER BUG REPORT:\n$task" + if (spec.isNotBlank()) "\nSPEC (approved by the user): $spec" else "", repeat = false)
            if (d == null && !full) return finish(false, "डिबगर बग की जड़ नहीं ढूँढ पाया — थोड़ी और जानकारी दें (कब होता है, क्या एरर दिखता है)")
            if (d == null) {
                // मैनेजर: डिबगर अटका - रुककर user से पूछने की जगह दूसरा रास्ता: प्लानर डिबगर की पढ़ी फ़ाइलों से खुद जगह ढूँढकर सुधार बनाए
                val readByDebugger = debugSeen?.paths().orEmpty().toList()
                ev(Agent.MANAGER, "डिबगर एक पक्की लाइन तक नहीं पहुँचा — रणनीति बदल रहा हूँ: प्लानर खुद खोजकर/पढ़कर सुधार की जगह ढूँढेगा" +
                    if (readByDebugger.isNotEmpty()) " (डिबगर की पढ़ी फ़ाइलें: ${readByDebugger.joinToString()})" else "")
                bugs.add(Seven.Bug("जड़ की एक पक्की लाइन नहीं मिली", "-", "डिबगर ने ${readByDebugger.size} फ़ाइलें पढ़ीं, पर एक पक्की लाइन तय नहीं कर पाया", "प्लानर ने खोजकर सुधार बनाया"))
                makePlan("FIX THIS USER PROBLEM (the debugger could not pin one exact line - the needed code may be MISSING, not broken).\n" +
                    "USER PROBLEM: $task\n" + (if (spec.isNotBlank()) "SPEC: $spec\n" else "") +
                    "Search and read to find where the behaviour is (or must be) implemented, then plan the smallest complete change.", readByDebugger)
            } else {
                rootCause = d.second
                makePlan((if (d.first.missing) "ADD THE MISSING CODE for this user problem (it is not implemented anywhere yet) - smallest complete change, wired to the place given.\n"
                    else "MINIMAL FIX for this bug.\n") + "ROOT CAUSE: ${d.second}\nFIX: ${d.third}", d.first.files)
            }
        } else makePlan(task + if (brief.isNotBlank()) "\n(Manager brief: $brief)" else "", emptyList())
        plan ?: return finish(false, "प्लानर प्लान नहीं बना पाया: $lastBlock")
        if (!execute(plan, emptySet())) return finish(false, lastBlock)
        // 3b. परफॉरमेंस: बदले code में UI जाम / leak / बेवजह का काम
        if (full) perf()

        // 4. टेस्टर ⇄ डिबगर चक्र
        val sigs = mutableListOf<String>()
        while (true) {
            val t = test(rootCause)
            lastTest = t
            if (t.ok == true) {
                // QA: build पास - पर क्या काम सच में और पूरा हुआ? (PR से पहले)
                if (full && !qaOk) {
                    val miss = qa()
                    if (miss.isNotEmpty()) {
                        qaRounds++
                        if (qaRounds > QA_ROUNDS) return finish(false, "QA ने $QA_ROUNDS बार सुधार के बाद भी काम पूरा नहीं माना: " + miss.joinToString("; ").take(300))
                        ev(Agent.MANAGER, "QA ने कमी बताई — प्लानर से सुधार करवा रहा हूँ (राउंड $qaRounds/$QA_ROUNDS)")
                        val fix = makePlan("QA FOUND THE TASK INCOMPLETE OR WRONG.\nMISSING:\n" + miss.joinToString("\n") { "- $it" } +
                            (if (qaFix.isNotBlank()) "\nQA SUGGESTED FIX: $qaFix" else ""), files.staged().keys.toList())
                            ?: return finish(false, "QA की बताई कमी का प्लान नहीं बना: $lastBlock")
                        if (!execute(fix, emptySet())) return finish(false, lastBlock)
                        continue
                    }
                    qaOk = true
                }
                passNote = t.note
                ev(Agent.TESTER, (if (t.stage == TeamTest.BRANCH) "✓ PR से पहले branch पर build + tests पास" else if (t.stage == TeamTest.PR) "✓ PR का चेक पास" else "✓ टेस्ट + बिल्ड पास") +
                    if (t.url.isNotBlank()) "\n${t.url}" else "")
                // गिट: build पास होने के बाद ही PR (पहले नहीं); फिर उसी PR का अपना चेक भी देखो
                val g = git
                if (g != null && g.prUrl.isBlank()) {
                    val url = g.openPr()
                    if (url.isBlank()) return finish(null, "build पास है पर PR नहीं बन पाया" + if (g.prNote.isNotBlank()) ": ${g.prNote}" else "", "")
                    ev(Agent.GIT, "build पास होने के बाद PR बनाया: $url")
                    if (t.stage == TeamTest.BRANCH) { ev(Agent.TESTER, "अब PR का अपना चेक देख रहा हूँ…"); continue }
                }
                break
            }
            if (t.ok == null) return finish(null, t.note.ifBlank { "टेस्ट का नतीजा नहीं मिला" })
            val errs = errorLines(t.log)
            ev(Agent.TESTER, "✗ फ़ेल — log पढ़कर असली एरर डिबगर को दिया" + (if (errs.isNotBlank()) "\nअसली एरर:\n$errs" else "") + if (t.url.isNotBlank()) "\n${t.url}" else "")
            sigs.add(FixLoop.signature(t.log))
            when (FixLoop.next(sigs)) {
                FixLoop.Next.STOP_CAP -> return finish(false, "${sigs.size} बार जड़ ढूँढकर ठीक करने के बाद भी टेस्ट फ़ेल: ${sigs.last().take(200)}")
                FixLoop.Next.STOP_STUCK -> return finish(false, "वही गलती ${FixLoop.SAME_LIMIT} बार लौटी, रणनीति बदलकर भी नहीं गई: ${sigs.last().take(200)}")
                FixLoop.Next.FIX_AGAIN -> {}
            }
            val repeat = sigs.size >= 2 && sigs[sigs.size - 1] == sigs[sigs.size - 2]
            if (repeat) ev(Agent.MANAGER, "वही गलती दोबारा आई — रणनीति बदल रहा हूँ: डिबगर दूसरी जड़ देखेगा, कोडर पूरी फ़ाइल दोबारा लिखेगा")
            val d = debug("TEST/BUILD FAILED.\n" + (if (errs.isNotBlank()) "EXACT ERROR LINES FROM THE LOG:\n$errs\n\n" else "") + "FULL LOG (important part):\n${t.log.take(maxOf(12_000, big / 8))}", repeat)
                ?: return finish(false, "डिबगर टेस्ट फ़ेल होने की जड़ नहीं ढूँढ पाया")
            rootCause = d.second
            val fix = makePlan("MINIMAL FIX for the failed test/build.\nROOT CAUSE: ${d.second}\nFIX: ${d.third}", d.first.files)
                ?: return finish(false, "प्लानर सुधार का प्लान नहीं बना पाया: $lastBlock")
            if (!execute(fix, if (repeat) fix.steps.map { it.file }.toSet() else emptySet())) return finish(false, lastBlock)
        }

        // 5. गिट: PR तैयार (merge नहीं)
        var pr = ""
        git?.let { g -> pr = g.ready(); ev(Agent.GIT, "PR तैयार: $pr — merge सिर्फ़ आपकी मंज़ूरी से") }
        return finish(true, "", pr)
    }

    // ------------------------------------------------------------------ प्लानर
    private suspend fun makePlan(what: String, hint: List<String>): Seven.Plan? {
        for (tryNo in 0..1) {
            val p = planOnce(what + if (tryNo == 1) "\nMANAGER: the previous planning failed ($lastBlock). Make the SMALLEST possible plan (1-3 steps)." else "", hint)
            if (p != null) return p
            if (tryNo == 0) ev(Agent.MANAGER, "प्लानर अटका — रणनीति बदलकर छोटा प्लान माँग रहा हूँ")
        }
        return null
    }

    private suspend fun planOnce(what: String, hint: List<String>): Seven.Plan? {
        ev(Agent.PLANNER, "फ़ाइलें देखकर प्लान बना रहा हूँ…")
        val list = files.list()
        val msgs = mutableListOf("user" to ("TASK:\n$what\n" +
            (if (acceptance.isNotEmpty()) "ACCEPTANCE:\n" + acceptance.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (hint.isNotEmpty()) "DEBUGGER SAYS THESE FILES: ${hint.joinToString()}\n" else "") +
            (if (changes.isNotEmpty()) "ALREADY CHANGED IN THIS TASK: ${changes.keys.joinToString()}\n" else "") +
            (if (full && notTouch.isNotEmpty()) "DO NOT CHANGE (user's existing features): " + notTouch.joinToString("; ") + "\n" else "") +
            (if (full && area.isNotEmpty()) "PROMPT ENGINEER - LIKELY AREA (verify by reading):\n" + area.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (full) "REPO MAP (file: classes / functions / ids):\n" + map(ws()) + "\n" else "") +
            "FILE LIST (${list.size}):\n" + list.take(listMax).joinToString("\n") + if (list.isEmpty()) "(empty - create new files)" else ""))
        var usesFixed = false
        var whereFixed = 0
        val last = if (full) 8 else 3
        val sys = Seven.Prompts.PLANNER + if (full) "\n" + SevenDebug.PLANNER_EXTRA else ""
        val seen = SevenDebug.Seen()
        for (round in 0..last) {
            if (full) compactOld(msgs)
            val reply = aiAsk(Agent.PLANNER, sys, msgs)
            val terms = SevenPlus.RepoSearch.terms(reply)
            if (terms != null && round < last) {
                ev(Agent.PLANNER, "रिपो में खोज रहा हूँ (कहाँ लिखना है): " + terms.joinToString())
                msgs.add("model" to reply); msgs.add("user" to SevenPlus.RepoSearch.search(ws(), terms) + "\nNow READ the right files ({\"read\":[…]}) or reply with the JSON plan.")
                continue
            }
            val reads = Seven.planReads(reply)
            if (reads != null && round < last) {
                ev(Agent.PLANNER, (if (full) "फ़ाइलें / हिस्से पढ़ रहा हूँ: " else "पूरी फ़ाइलें पढ़ रहा हूँ: ") + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to (if (full) readParts(reads, seen) else readFiles(reads)) + "\nNow reply with the JSON plan, or read more files.")
                continue
            }
            val plan = Seven.parsePlan(reply)
            // प्लानर ने जो नाम (uses) बताए - उनकी फ़ाइल खोलकर पक्का: सच में हैं?
            if (plan != null && round < last && !usesFixed) {
                val bad = badUses(plan)
                if (bad.isNotEmpty()) {
                    usesFixed = true
                    ev(Agent.PLANNER, "प्लान के ये नाम फ़ाइल में नहीं मिले — फ़ाइल पढ़कर प्लान ठीक कर रहा हूँ:\n" + bad.joinToString("\n") { "• $it" })
                    msgs.add("model" to reply)
                    msgs.add("user" to "These \"uses\" entries are WRONG (the name is not in that file):\n" + bad.joinToString("\n") { "- $it" } +
                        "\nRead the right files ({\"read\":[…]}) and send the corrected JSON plan with only names that really exist.")
                    continue
                }
            }
            // जगह-जाँच (11-एजेंट): हर कदम की anchor फ़ाइल में सच में है? नई फ़ाइल पहले से तो नहीं?
            if (full && plan != null && round < last && whereFixed < 3) {
                val m = readMap(plan.steps.map { it.file }.distinct())
                val bad = SevenPlus.Where.problems(plan) { m[it] }.ifEmpty { SevenDebug.anchorsSeen(plan, { m[it] }, seen, fileChars) }
                if (bad.isNotEmpty()) {
                    whereFixed++
                    ev(Agent.PLANNER, "प्लान की जगह गलत निकली — खोजकर/पढ़कर सही जगह ढूँढ रहा हूँ:\n" + bad.joinToString("\n") { "• $it" })
                    msgs.add("model" to reply)
                    msgs.add("user" to "WHERE CHECK FAILED (the plan points to the wrong place):\n" + bad.joinToString("\n") { "- $it" } +
                        "\nUse {\"search\":[…]} and {\"read\":[…]} to find the exact place, then send the corrected JSON plan. The anchor must be copied from the file.")
                    continue
                }
            }
            if (plan != null) {
                ev(Agent.PLANNER, "प्लान: ${plan.steps.size} कदम\n" + plan.steps.mapIndexed { i, s -> "${i + 1}. ${s.file} — ${s.action}: ${s.change.take(120)}" +
                    if (s.why.isNotBlank()) "\n   ↳ यहाँ क्यों: ${s.why.take(140)}" else "" }.joinToString("\n") +
                    if (plan.risks.isNotEmpty()) "\n⚠ पुराने फ़ीचर का ख़तरा:\n" + plan.risks.joinToString("\n") { "• $it" } else "")
                return plan
            }
            msgs.add("model" to reply); msgs.add("user" to "Reply ONLY the JSON plan: {\"steps\":[{\"file\":…,\"action\":…,\"change\":…,\"anchor\":…}],\"risks\":[…]}")
        }
        lastBlock = "प्लानर का जवाब JSON प्लान नहीं था"
        return null
    }

    /**
     * 11-एजेंट डिबगर: पूरे रिपो में खोज + रिपो का नक्शा + प्रॉम्प्ट इंजीनियर की जगहें + इस काम का diff,
     * फ़ाइलें लाइन नंबर के साथ (बड़ी फ़ाइल का कोई भी हिस्सा), और जड़ की पक्की जाँच (पढ़ी फ़ाइल / सही लाइन).
     */
    private suspend fun debugFull(problem: String, repeat: Boolean): Triple<Diag, String, String>? {
        ev(Agent.DEBUGGER, if (repeat) "पिछला सुधार असली जड़ नहीं था — दूसरी वजह ढूँढ रहा हूँ…" else "असली जड़ ढूँढ रहा हूँ — पहले रिपो में खोजूँगा, फिर कोड पढ़कर पक्का करूँगा…")
        val list = files.list()
        val w = ws()
        val staged = files.staged()
        val msgs = mutableListOf("user" to ("TASK: $task\n$problem\n" +
            (if (repeat) "NOTE FROM MANAGER: the SAME error came back after the previous fix. That fix did NOT address the real cause. Look for a different cause.\n" else "") +
            (if (area.isNotEmpty()) "PROMPT ENGINEER - LIKELY AREA (verify by searching/reading):\n" + area.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (notTouch.isNotEmpty()) "DO NOT CHANGE (user's existing features): " + notTouch.joinToString("; ") + "\n" else "") +
            (if (staged.isNotEmpty()) "DIFF OF THIS TASK (- removed, + added):\n" + diffText(staged, maxOf(12_000, big / 10)) + "\n" else "") +
            "REPO MAP (file: classes / functions / ids):\n" + map(w) + "\n" +
            "FILE LIST (${list.size}):\n" + list.take(listMax).joinToString("\n")))
        val sys = Seven.Prompts.DEBUGGER + "\n" + SevenDebug.EXTRA
        val seen = SevenDebug.Seen()
        debugSeen = seen
        val keys = (w.keys + list).toSet()
        val resolve: (String) -> String? = { SevenDebug.resolve(it, keys) }
        var rejected = 0
        var nudged = 0
        val last = DEBUG_ROUNDS - 1
        for (round in 0..last) {
            val left = last - round
            compactOld(msgs)
            val reply = aiAsk(Agent.DEBUGGER, sys, msgs)
            val terms = SevenPlus.RepoSearch.terms(reply)
            val reads = Seven.planReads(reply)
            if ((terms != null || reads != null) && left <= FORCE_ROUNDS) {
                // खोज/पढ़ाई का हिस्सा ख़त्म: अब जो पढ़ा उसी से फ़ैसला (वरना अंतहीन खोज और आख़िर में कुछ नहीं)
                ev(Agent.MANAGER, "डिबगर बहुत देर से खोज रहा है — अब जो पढ़ा उसी से जड़ बताने को कह रहा हूँ")
                msgs.add("model" to reply)
                msgs.add("user" to "NO MORE SEARCH / READ - the reading budget is used. Decide NOW from what you have read. If the needed behaviour is not implemented anywhere, that IS the root cause (\"kind\":\"missing\"). Reply ONLY the final root cause JSON." + SevenDebug.roundsLeft(left))
                continue
            }
            if (terms != null) {
                ev(Agent.DEBUGGER, "रिपो में खोज रहा हूँ: " + terms.joinToString())
                msgs.add("model" to reply); msgs.add("user" to SevenPlus.RepoSearch.search(ws(), terms) + "\nNow READ the right files/parts ({\"read\":[…]}) or search more." + SevenDebug.roundsLeft(left))
                continue
            }
            if (reads != null) {
                ev(Agent.DEBUGGER, "पढ़ रहा हूँ (लाइन नंबर के साथ): " + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to readNumbered(reads, seen, keys) + "\nReply with the root cause JSON, or search/read more." + SevenDebug.roundsLeft(left))
                continue
            }
            val o = Seven.json(reply)
            val root = o?.optString("root_cause").orEmpty().trim()
            if (o != null && root.length >= 8) {
                val probs = SevenDebug.verify(o, seen, resolve) { p -> w[p]?.lines()?.size }
                if (probs.isNotEmpty() && rejected < SevenDebug.VERIFY_ROUNDS && left > FORCE_ROUNDS) {
                    rejected++
                    ev(Agent.DEBUGGER, "बताई जड़ पक्की नहीं — कोड पढ़कर दोबारा जाँच रहा हूँ:\n" + probs.joinToString("\n") { "• ${it.take(200)}" })
                    msgs.add("model" to reply)
                    msgs.add("user" to "ROOT CAUSE NOT VERIFIED (checked against the real files):\n" + probs.joinToString("\n") { "- $it" } +
                        "\nSearch/read what is needed, then reply with the corrected root cause JSON." + SevenDebug.roundsLeft(left))
                    continue
                }
                val missing = o.optString("kind").trim().equals("missing", true)
                val where = o.optString("where").trim().let { wh -> SevenDebug.location(wh)?.let { (p, l) -> resolve(p)?.let { rp -> if (l != null) "$rp:$l" else rp } } ?: wh }
                val fix = o.optString("fix").trim()
                if (probs.isNotEmpty()) ev(Agent.DEBUGGER, "⚠ जड़ पूरी तरह पक्की नहीं हो पाई (${probs.first().take(150)}) — फिर भी आगे बढ़ रहा हूँ, टेस्ट से पता चलेगा")
                bugs.add(Seven.Bug(o.optString("bug").trim().ifBlank { root.take(150) }, where, root, fix.ifBlank { "-" }))
                ev(Agent.DEBUGGER, (if (missing) "जड़ मिली (यह काम कोड में कहीं लिखा ही नहीं है): " else "जड़ मिली: ") + "${where.ifBlank { "-" }}\n$root\nसुधार: ${fix.take(200)}")
                val fl = Seven.strings(o.optJSONArray("files")).map { resolve(it) ?: it }
                return Triple(Diag(fl, missing), root, fix)
            }
            if (nudged >= 3) break
            nudged++
            msgs.add("model" to reply); msgs.add("user" to "ROOT CAUSE REQUIRED. Reply ONLY JSON {\"kind\":\"bug|missing\",\"root_cause\":\"file:line - what and why\",\"bug\":…,\"where\":\"file:line\",\"fix\":…,\"files\":[…]}" +
                (if (left > FORCE_ROUNDS) " or {\"search\":[…]} or {\"read\":[…]}." else ".") + SevenDebug.roundsLeft(left))
        }
        return null
    }

    /** डिबगर ने जो फ़ाइलें पढ़ीं (डिबगर अटके तो प्लानर को यही दी जाती हैं) */
    private var debugSeen: SevenDebug.Seen? = null

    /** प्लानर के लिए (11-एजेंट): बिना लाइन नंबर (anchor सीधे कॉपी हो), "path:START-END" वाले हिस्से भी, बड़ी फ़ाइल के साथ रूपरेखा; जो दिखा वह seen में */
    private suspend fun readParts(entries: List<String>, seen: SevenDebug.Seen): String {
        var budget = readBudget
        val out = mutableListOf<String>()
        val keys = files.list()
        for (e in entries) {
            val r0 = SevenDebug.parseRead(e)
            val r = SevenDebug.resolve(r0.path, keys)?.let { r0.copy(path = it) } ?: r0
            val b = SevenDebug.block(r, try { files.read(r.path) } catch (_: Exception) { null }, budget, fileChars, numbers = false)
            budget -= b.chars
            seen.add(b.path, b.from, b.to)
            out.add(b.text)
        }
        return out.joinToString("\n\n")
    }

    /** डिबगर के लिए: लाइन नंबर के साथ, "path:START-END" वाले हिस्से भी; जो दिखा वह seen में */
    private suspend fun readNumbered(entries: List<String>, seen: SevenDebug.Seen, keys: Collection<String>): String {
        var budget = debugBudget
        val out = mutableListOf<String>()
        for (e in entries) {
            val r0 = SevenDebug.parseRead(e)
            val r = SevenDebug.resolve(r0.path, keys)?.let { r0.copy(path = it) } ?: r0
            val b = SevenDebug.block(r, try { files.read(r.path) } catch (_: Exception) { null }, budget, fileChars)
            budget -= b.chars
            seen.add(b.path, b.from, b.to)
            out.add(b.text)
        }
        return out.joinToString("\n\n")
    }

    private suspend fun readFiles(paths: List<String>): String {
        var budget = readBudget
        val out = mutableListOf<String>()
        for (p in paths) {
            val c = files.read(p)
            out.add(when {
                c == null -> "----- $p : FILE NOT FOUND -----"
                budget <= 0 -> "----- $p : (skipped, read budget used) -----"
                else -> { val part = c.take(minOf(budget, fileChars)); budget -= part.length
                    "----- BEGIN $p (${c.lines().size} lines)${if (part.length < c.length) " TRUNCATED" else ""} -----\n$part\n----- END $p -----" }
            })
        }
        return out.joinToString("\n\n")
    }

    // ------------------------------------------------------------------ डिबगर
    /** @param missing डिबगर: यह काम कोड में कहीं लिखा ही नहीं (टूटी लाइन नहीं, गायब हिस्सा) */
    data class Diag(val files: List<String>, val missing: Boolean = false)
    /** @return (फ़ाइलें, root cause, fix) */
    private suspend fun debug(problem: String, repeat: Boolean): Triple<Diag, String, String>? {
        if (full) return debugFull(problem, repeat)
        ev(Agent.DEBUGGER, if (repeat) "पिछला सुधार असली जड़ नहीं था — दूसरी वजह ढूँढ रहा हूँ…" else "असली जड़ ढूँढ रहा हूँ…")
        val list = files.list()
        val msgs = mutableListOf("user" to ("TASK: $task\n$problem\n" +
            (if (repeat) "NOTE FROM MANAGER: the SAME error came back after the previous fix. That fix did NOT address the real cause. Look for a different cause.\n" else "") +
            (if (changes.isNotEmpty()) "FILES CHANGED IN THIS TASK: ${changes.keys.joinToString()}\n" else "") +
            "FILE LIST (${list.size}):\n" + list.take(listMax).joinToString("\n")))
        var warned = false
        for (round in 0..4) {
            val reply = aiAsk(Agent.DEBUGGER, Seven.Prompts.DEBUGGER, msgs)
            val terms = if (full) SevenPlus.RepoSearch.terms(reply) else null
            if (terms != null && round < 4) {
                ev(Agent.DEBUGGER, "रिपो में खोज रहा हूँ: " + terms.joinToString())
                msgs.add("model" to reply); msgs.add("user" to SevenPlus.RepoSearch.search(ws(), terms) + "\nRead the files ({\"read\":[…]}) or reply with the root cause JSON.")
                continue
            }
            val reads = Seven.planReads(reply)
            if (reads != null && round < 4) {
                ev(Agent.DEBUGGER, "पढ़ रहा हूँ: " + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to readFiles(reads) + "\nReply with the root cause JSON, or read more files.")
                continue
            }
            val o = Seven.json(reply)
            val root = o?.optString("root_cause").orEmpty().trim()
            if (o != null && root.length >= 8) {
                val where = o.optString("where").trim()
                val fix = o.optString("fix").trim()
                bugs.add(Seven.Bug(o.optString("bug").trim().ifBlank { root.take(150) }, where, root, fix.ifBlank { "-" }))
                ev(Agent.DEBUGGER, "जड़ मिली: ${where.ifBlank { "-" }}\n$root\nसुधार: ${fix.take(200)}")
                return Triple(Diag(Seven.strings(o.optJSONArray("files"))), root, fix)
            }
            if (warned && round >= 3) break
            warned = true
            msgs.add("model" to reply); msgs.add("user" to "ROOT CAUSE REQUIRED. Reply ONLY JSON {\"root_cause\":\"file:line - what and why\",\"bug\":…,\"where\":…,\"fix\":…,\"files\":[…]} or {\"read\":[…]}.")
        }
        return null
    }

    // ------------------------------------------------------------------ कोडर ⇄ रिव्यूअर, कदम-दर-कदम
    private suspend fun execute(plan: Seven.Plan, rewrite: Set<String>): Boolean {
        curPlan = plan
        plan.steps.forEachIndexed { i, s ->
            ev(Agent.CODER, "कदम ${i + 1}/${plan.steps.size}: ${s.file} — ${s.change.take(140)}")
            if (step(s, s.file in rewrite)) return@forEachIndexed
            // मैनेजर: अटके कदम पर दूसरा रास्ता - प्लानर से इसी हिस्से का नया प्लान
            ev(Agent.MANAGER, "कदम ${i + 1} अटका (${lastBlock.take(160)}) — रोककर दूसरा रास्ता: प्लानर से इस हिस्से का नया प्लान")
            val alt = planOnce("ONLY THIS PART of the task: ${s.change}\nFILE: ${s.file}\nThe previous attempt failed: $lastBlock\n" +
                "Plan a DIFFERENT approach (e.g. smaller steps, different location, or rewriting a small file).", listOf(s.file))
                ?: return false
            for ((j, a) in alt.steps.withIndex()) {
                ev(Agent.CODER, "नया रास्ता ${j + 1}/${alt.steps.size}: ${a.file} — ${a.change.take(140)}")
                if (!step(a, forceRewrite = false)) {
                    lastBlock = "कदम \"${s.change.take(100)}\" दो तरीक़ों से भी लागू नहीं हुआ: $lastBlock"
                    return false
                }
            }
        }
        return true
    }

    private suspend fun step(s: Seven.Step, forceRewrite: Boolean): Boolean {
        val cur = files.read(s.file)
        if (s.action == "edit" && cur == null) { lastBlock = "${s.file} मौजूद नहीं"; return false }
        if (s.action == "delete") return deleteStep(s, cur)
        val errors = mutableListOf<String>()
        var misses = 0
        var strat = if (forceRewrite && cur != null && cur.length <= Seven.REWRITE_MAX_CHARS) Seven.Strategy.REWRITE else Seven.Strategy.FIND
        if (strat == Seven.Strategy.REWRITE) ev(Agent.MANAGER, "रणनीति: ${s.file} पूरी पढ़कर पूरी दोबारा लिखवा रहा हूँ" + if (backup.isNotBlank()) " (backup: $backup)" else " (पुरानी फ़ाइल याद रखी है)")
        val defs = definitions(s)
        var msgs = mutableListOf("user" to coderBrief(s, cur, strat, defs))
        for (attempt in 1..Seven.STEP_ATTEMPTS) {
            val sys = Seven.Prompts.CODER + if (strat == Seven.Strategy.REWRITE) "\n\n" + Seven.Prompts.CODER_REWRITE else ""
            val reply = aiAsk(Agent.CODER, sys, msgs)
            val edit = Seven.parseEdit(reply, s.file)
            val chk = Seven.check(edit, s, cur, allowRewrite = strat == Seven.Strategy.REWRITE || cur == null)
            var problems = chk.problems
            var forCoder = ""
            // रिव्यूअर की पक्की नाम-जाँच: नया code जो function/class/variable बुलाता है, वह सच में है?
            val miss = if (chk.ok && chk.content != null) names(s.file, cur, chk.content) else emptyList()
            val sure = miss.filter { it.sure }
            if (sure.isNotEmpty()) {
                problems = sure.map { "नाम मौजूद नहीं: " + it.hindi() }
                forCoder = "\nNAME CHECK FAILED (these names do not exist - read the definitions and use the real names):\n" + sure.joinToString("\n") { "- " + it.english() }
            } else if (chk.ok) {
                // रिव्यूअर (AI): पुराना फ़ीचर / compile / कदम पूरा? (+ जो नाम पक्के नहीं, उनका सबूत)
                val r = review(s, cur, edit, chk.content, miss)
                if (r.isEmpty()) {
                    files.stage(s.file, chk.content)
                    changes[s.file] = (changes[s.file]?.let { "$it; " } ?: "") + s.change.take(160)
                    ev(Agent.REVIEWER, "✓ ${s.file}: जाँच पास — बदलाव लागू")
                    return true
                }
                problems = r
            }
            if (chk.findMiss) misses++
            errors.add(problems.joinToString(" | "))
            ev(Agent.REVIEWER, "✗ ${s.file}: वापस कोडर को (कोशिश $attempt/${Seven.STEP_ATTEMPTS})\n" + problems.joinToString("\n") { "• ${it.take(200)}" })
            lastBlock = problems.joinToString("; ").take(300)
            val next = Seven.strategy(errors, misses, cur?.length ?: 0, cur != null)
            if (next != strat) {
                strat = next
                ev(Agent.MANAGER, "एक ही गलती बार-बार / FIND नहीं बन रहा — तरीका बदला: ${s.file} पूरी पढ़कर पूरी दोबारा लिखवा रहा हूँ" +
                    if (backup.isNotBlank()) " (backup: $backup)" else "")
                msgs = mutableListOf("user" to coderBrief(s, cur, strat, defs) + forCoder)
            } else {
                msgs.add("model" to reply)
                msgs.add("user" to "REVIEWER REJECTED (the change was NOT applied):\n" + problems.joinToString("\n") { "- $it" } + forCoder +
                    (if (chk.findMiss && cur != null) "\nHere is the exact current file again - copy FIND character-for-character:\n" + fileBlock(s, cur) else "") +
                    "\nSend the corrected change in the same format.")
            }
        }
        return false
    }

    private suspend fun deleteStep(s: Seven.Step, cur: String?): Boolean {
        if (cur == null) { lastBlock = "${s.file} मौजूद नहीं"; return false }
        val o = askJson(Agent.REVIEWER, "TASK: $task\nSTEP: delete file ${s.file} — ${s.change}\nFILE LIST:\n" + files.list().take(listMax).joinToString("\n") +
            "\nCould deleting it break an existing feature (is it still used)?")
        val probs = Seven.strings(o?.optJSONArray("problems"))
        if (o != null && !o.optBoolean("pass", false) && probs.isNotEmpty()) { lastBlock = probs.joinToString("; "); ev(Agent.REVIEWER, "✗ ${s.file} हटाना रोका: $lastBlock"); return false }
        files.stage(s.file, null); changes[s.file] = "हटाई: ${s.change.take(120)}"
        ev(Agent.REVIEWER, "✓ ${s.file} हटाने की जाँच पास")
        return true
    }

    private fun fileBlock(s: Seven.Step, cur: String): String {
        val body = if (cur.length <= fileChars) cur else Seven.window(cur, s.anchor)
        return "----- BEGIN ${s.file}${if (body.length < cur.length) " (PART around the change)" else ""} -----\n$body\n----- END ${s.file} -----"
    }

    private fun coderBrief(s: Seven.Step, cur: String?, strat: Seven.Strategy, defs: String = "") = "TASK: $task\nSTEP (${s.action}): ${s.change}\nFILE: ${s.file}\n" +
        handOff(s) +
        (if (defs.isNotBlank()) "DEFINITIONS (real, read from the files - use exactly these names/signatures):\n$defs\n" else "") +
        (if (cur == null) "(new file - send FILE: ${s.file} and <<<FILE … FILE>>>)" else if (strat == Seven.Strategy.REWRITE)
            "FULL CURRENT FILE (rewrite it completely, keep everything else exactly):\n----- BEGIN ${s.file} -----\n$cur\n----- END ${s.file} -----"
        else fileBlock(s, cur))

    /** पूरा समझाकर आगे देना: कोडर/रिव्यूअर को मंज़ूर spec, यह कदम क्यों, पूरा प्लान, शर्तें, क्या नहीं छेड़ना */
    private fun handOff(s: Seven.Step): String = buildString {
        if (s.why.isNotBlank()) append("WHY THIS STEP (planner): ").append(s.why).append('\n')
        if (s.anchor.isNotBlank()) append("WHERE (anchor text near the change): ").append(s.anchor).append('\n')
        if (s.uses.isNotEmpty()) append("USES (from other files): ").append(s.uses.joinToString("; ")).append('\n')
        if (spec.isNotBlank()) append("SPEC (approved by the user):\n").append(spec).append('\n')
        else if (brief.isNotBlank()) append("BRIEF: ").append(brief).append('\n')
        curPlan?.takeIf { it.steps.size > 1 }?.let { p ->
            append("WHOLE PLAN (for context - you do ONLY the STEP above):\n")
            p.steps.forEachIndexed { i, x -> append(if (x === s) "-> " else "   ").append(i + 1).append(". ").append(x.file).append(" - ").append(x.change).append('\n') }
        }
        if (acceptance.isNotEmpty()) append("ACCEPTANCE (the whole task):\n").append(acceptance.joinToString("\n") { "- $it" }).append('\n')
        if (notTouch.isNotEmpty()) append("DO NOT CHANGE (user's existing features): ").append(notTouch.joinToString("; ")).append('\n')
    }

    /** @return समस्याएँ (खाली = पास) */
    private suspend fun review(s: Seven.Step, cur: String?, e: Seven.Edit, after: String?, unsure: List<SevenSymbols.Miss> = emptyList()): List<String> {
        val change = when {
            e.full != null && cur == null -> "NEW FILE ${s.file}:\n" + e.full.take(maxOf(20_000, fileChars))
            e.full != null -> "FULL REWRITE of ${s.file}. Lines before: ${cur!!.lines().size}, after: ${after?.lines()?.size}.\nREMOVED LINES:\n" +
                (cur.lines().toSet() - (after ?: "").lines().toSet()).take(400).joinToString("\n") + "\nADDED LINES:\n" +
                ((after ?: "").lines().toSet() - cur.lines().toSet()).take(600).joinToString("\n")
            else -> e.pairs.mapIndexed { i, (f, r) -> "--- FIND #${i + 1}\n$f\n+++ REPLACE #${i + 1}\n$r" }.joinToString("\n")
        }
        val ctx = if (cur != null && e.full == null) "\nFILE CONTEXT:\n" + (if (cur.length <= fileChars) cur else Seven.window(cur, e.pairs.first().first.lines().first(), 60)) else ""
        for (t in 0..1) {
            val nameCheck = if (unsure.isEmpty()) "" else "\nNAME CHECK (not found in this repository - only valid if it really exists in the external parent class / SDK):\n" +
                unsure.joinToString("\n") { "- " + it.english() }
            val o = askJson(Agent.REVIEWER, "TASK: $task\nSTEP: ${s.change}\nFILE: ${s.file}\n" + handOff(s) + "CHANGE:\n$change$ctx$nameCheck") ?: continue
            if (o.optBoolean("pass", false)) return emptyList()
            return Seven.strings(o.optJSONArray("problems")).ifEmpty { listOf("रिव्यूअर ने बदलाव ठीक नहीं माना (वजह नहीं बताई)") }
        }
        // रिव्यूअर का जवाब पढ़ा नहीं जा सका: पक्की (बिना AI) जाँच पास हो चुकी है - वही मान्य
        ev(Agent.REVIEWER, "AI रिव्यू का जवाब नहीं मिला — पक्की जाँच (FIND/REPLACE + syntax) पास है, उसी पर लागू")
        return emptyList()
    }

    // ------------------------------------------------------------------ टेस्टर
    private suspend fun test(rootCause: String): TeamTest {
        ev(Agent.TESTER, "टेस्ट चला रहा हूँ…")
        val staged = files.staged()
        // पहले पक्की जाँच: हर बदली फ़ाइल की syntax + पुराने tests हटे/कमज़ोर तो नहीं
        val stat = mutableListOf<String>()
        staged.forEach { (p, c) -> if (c != null) Syntax.check(p, c)?.let { stat.add("$p: $it") } }
        val orig = staged.keys.associateWith { files.original(it) }
        stat.addAll(SafePr.testGuard(staged, orig, SafePr.removesOldFeature(task)))
        if (stat.isNotEmpty()) return TeamTest(false, "STATIC CHECK FAILED:\n" + stat.joinToString("\n"))
        if (staged.isEmpty()) return TeamTest(false, "NOTHING CHANGED: no file was changed for the task")
        val g = git
        if (g != null) {
            val cm = commitMsg ?: run {
                val o = askJson(Agent.GIT, "TASK: $task\nCHANGES:\n" + changes.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                (o?.optString("commit").orEmpty().ifBlank { "DK: " + task.take(60) } to o?.optString("title").orEmpty().ifBlank { task.take(80) })
                    .also { commitMsg = it }
            }
            val commit = cm.first; val title = cm.second
            ev(Agent.GIT, if (g.prUrl.isBlank()) "branch पर push — PR अभी नहीं, पहले build/tests" else "उसी PR की branch पर push, PR का चेक…")
            val r = g.pushAndTest(commit, title, changes.entries.joinToString("\n") { "• ${it.key} — ${it.value}" }, rootCause) { ev(Agent.TESTER, it) }
            if (r.ok == null && r.log == NO_CI) {
                noCi = true
                ev(Agent.TESTER, "इस रिपो में GitHub Actions का कोई test/build नहीं — syntax जाँच + ड्राई-रन से जाँच रहा हूँ")
                return dryRun(staged, "रिपो में CI नहीं: syntax जाँच + ड्राई-रन")
            }
            return r
        }
        return dryRun(staged, "syntax जाँच + ड्राई-रन (फ़ोन पर build नहीं चलता)")
    }

    /** फ़ोन पर build नहीं चलता: पक्की जाँच (हो चुकी) + टेस्टर का कोड को मन में चलाकर देखना */
    private suspend fun dryRun(staged: Map<String, String?>, note: String): TeamTest {
        val body = staged.entries.joinToString("\n\n") { (p, c) -> "----- $p -----\n" + (c ?: "(deleted)").take(fileChars) }
        val o = askJson(Agent.TESTER, "TASK: $task\nACCEPTANCE:\n" + acceptance.joinToString("\n") + "\nCHANGED FILES:\n$body")
            ?: return TeamTest(true, "", note = "syntax जाँच पास; AI ड्राई-रन का जवाब नहीं मिला")
        val cases = Seven.strings(o.optJSONArray("cases"))
        if (cases.isNotEmpty()) ev(Agent.TESTER, "जाँचे गए हालात:\n" + cases.take(6).joinToString("\n") { "• $it" })
        return if (o.optBoolean("pass", false)) TeamTest(true, "", note = note)
        else TeamTest(false, "DRY-RUN TEST FAILED: " + o.optString("error").ifBlank { "tester found a failing case" })
    }

    // ------------------------------------------------------------------ परफॉरमेंस एजेंट
    private suspend fun perf() {
        val staged = files.staged()
        if (staged.isEmpty()) return
        ev(Agent.PERF, "बदले code में UI जाम / leak / बेवजह का काम देख रहा हूँ…")
        val hard = mutableListOf<String>()
        staged.forEach { (p, c) -> if (c != null) hard.addAll(SevenPlus.Perf.scan(p, files.original(p), c)) }
        val o = askJson(Agent.PERF, "TASK: $task\nCHANGED CODE (only new/changed lines matter):\n" + diffText(staged, maxOf(30_000, big / 4)))
        val must = (hard + Seven.strings(o?.optJSONArray("must"))).distinct()
        perfNotes.addAll(Seven.strings(o?.optJSONArray("should")).take(5))
        if (must.isEmpty()) { ev(Agent.PERF, "✓ परफॉरमेंस ठीक" + if (perfNotes.isNotEmpty()) " (छोटे सुझाव रिपोर्ट में)" else ""); return }
        ev(Agent.PERF, "✗ ठीक करना ज़रूरी:\n" + must.joinToString("\n") { "• ${it.take(200)}" })
        val fix = makePlan("PERFORMANCE FIX - keep the behaviour exactly the same, change only this:\n" + must.joinToString("\n") { "- $it" }, staged.keys.toList())
        if (fix == null || !execute(fix, emptySet())) {
            perfNotes.addAll(0, must.map { "(ठीक नहीं हो पाया) $it" })
            ev(Agent.PERF, "⚠ परफॉरमेंस सुधार लागू नहीं हुआ — रिपोर्ट में लिखा, बाकी काम जारी")
            return
        }
        val left = mutableListOf<String>()
        files.staged().forEach { (p, c) -> if (c != null) left.addAll(SevenPlus.Perf.scan(p, files.original(p), c)) }
        if (left.isEmpty()) ev(Agent.PERF, "✓ परफॉरमेंस सुधार लागू") else { perfNotes.addAll(0, left); ev(Agent.PERF, "⚠ कुछ बाकी — रिपोर्ट में") }
    }

    // ------------------------------------------------------------------ QA एजेंट
    /** @return कमियाँ (खाली = काम पूरा और सही) */
    private suspend fun qa(): List<String> {
        val staged = files.staged()
        ev(Agent.QA, "build पास — अब जाँच रहा हूँ कि हर शर्त सच में पूरी हुई, कुछ नकली/अधूरा तो नहीं, पुराने फ़ीचर सलामत हैं…")
        val hard = mutableListOf<String>()
        staged.forEach { (p, c) -> if (c != null) hard.addAll(SevenPlus.Qa.scan(p, files.original(p), c)) }
        val o = askJson(Agent.QA, "USER REQUEST:\n$task\n" + (if (spec.isNotBlank()) "SPEC (approved by the user):\n$spec\n" else if (brief.isNotBlank()) "BRIEF: $brief\n" else "") +
            "ACCEPTANCE:\n" + acceptance.joinToString("\n") { "- $it" } + "\n" +
            (if (notTouch.isNotEmpty()) "MUST STAY UNCHANGED:\n" + notTouch.joinToString("\n") { "- $it" } + "\n" else "") +
            "DIFF:\n" + diffText(staged, maxOf(25_000, big / 5)) + "\n\nCHANGED FILES NOW:\n" +
            staged.entries.joinToString("\n\n") { (p, c) -> "----- $p -----\n" + (c ?: "(deleted)").take(maxOf(15_000, big / 8)) }.take(maxOf(45_000, big / 3)))
        qaChecks = Seven.strings(o?.optJSONArray("checks"))
        qaFix = o?.optString("fix").orEmpty().trim()
        if (qaChecks.isNotEmpty()) ev(Agent.QA, qaChecks.take(8).joinToString("\n") { "• $it" })
        val miss = hard.toMutableList()
        if (o != null && !o.optBoolean("pass", false)) miss.addAll(Seven.strings(o.optJSONArray("missing")).ifEmpty { listOf("QA ने काम अधूरा माना (वजह नहीं बताई)") })
        if (o == null && hard.isEmpty()) ev(Agent.QA, "AI QA का जवाब नहीं मिला — पक्की जाँच (अधूरा/नकली code नहीं) पास")
        if (miss.isEmpty()) ev(Agent.QA, "✓ QA पास — काम पूरा और सही") else ev(Agent.QA, "✗ कमी:\n" + miss.joinToString("\n") { "• ${it.take(200)}" })
        return miss.distinct()
    }

    /** हर बदली फ़ाइल: हटाई / जोड़ी लाइनें */
    private suspend fun diffText(staged: Map<String, String?>, max: Int): String = buildString {
        for ((p, c) in staged) {
            val before = files.original(p)
            append("=== ").append(p).append(if (before == null) " (NEW)" else if (c == null) " (DELETED)" else "").append(" ===\n")
            val b = before?.lines().orEmpty(); val a = c?.lines().orEmpty()
            val bs = b.toHashSet(); val asSet = a.toHashSet()
            b.filter { it !in asSet && it.isNotBlank() }.take(400).forEach { append("- ").append(it).append('\n') }
            a.filter { it !in bs && it.isNotBlank() }.take(1000).forEach { append("+ ").append(it).append('\n') }
            if (length > max) break
        }
    }.take(max)

    private var wsCache: Map<String, String>? = null
    /** पूरा workspace (खोज/नक्शे के लिए) - इस काम के बदलाव समेत */
    private suspend fun ws(): Map<String, String> {
        val base = wsCache ?: (try { workspace() } catch (_: Exception) { emptyMap() }).also { wsCache = it }
        val st = files.staged()
        if (st.isEmpty()) return base
        return HashMap(base).apply { st.forEach { (p, c) -> if (c == null) remove(p) else put(p, c) } }
    }

    // ------------------------------------------------------------------ नाम-जाँच (फ़ाइल पढ़कर, अंदाज़े से नहीं)
    private suspend fun workspace(): Map<String, String> = files.all() ?: run {
        val m = LinkedHashMap<String, String>()
        for (p in files.list().take(listMax)) files.read(p)?.let { m[p] = it }
        m
    }

    private suspend fun names(path: String, before: String?, after: String): List<SevenSymbols.Miss> = try {
        val ws = HashMap(workspace()); ws[path] = after
        SevenSymbols.check(path, before, after, ws, files.whole)
    } catch (e: Exception) { emptyList() }

    private suspend fun readMap(paths: Collection<String>): Map<String, String?> {
        val m = HashMap<String, String?>()
        for (p in paths) m[p] = try { files.read(p) } catch (_: Exception) { null }
        return m
    }

    private suspend fun badUses(plan: Seven.Plan): List<String> {
        val uses = plan.steps.flatMap { it.uses }.distinct()
        if (uses.isEmpty()) return emptyList()
        val m = readMap(uses.map { it.substringBefore(':').trim() }.distinct())
        return SevenSymbols.badUses(uses) { m[it] }
    }

    private suspend fun definitions(s: Seven.Step): String {
        if (s.uses.isEmpty()) return ""
        val m = readMap(s.uses.map { it.substringBefore(':').trim() }.distinct())
        return SevenSymbols.signatures(s.uses) { m[it] }
    }

    /** log से असली एरर लाइनें (Kotlin "e: file:line", error:, FAILED, Exception) */
    private fun errorLines(log: String): String = log.lines().map { it.trim() }.filter { l ->
        l.startsWith("e: ") || l.contains("error:", true) || l.contains("Unresolved reference") || l.contains("FAILED") ||
            l.contains("AssertionError") || l.contains("Exception:") || l.startsWith("STATIC CHECK") || l.startsWith("DRY-RUN")
    }.distinct().take(8).joinToString("\n") { it.take(240) }

    // ------------------------------------------------------------------ मदद
    private suspend fun askJson(a: Agent, user: String): JSONObject? {
        val msgs = mutableListOf("user" to user)
        for (t in 0..1) {
            val reply = aiAsk(a, Seven.Prompts.of(a), msgs)
            Seven.json(reply)?.let { return it }
            msgs.add("model" to reply); msgs.add("user" to "Reply ONLY valid JSON in the required format.")
        }
        return null
    }

    private fun finish(testOk: Boolean?, problem: String, pr: String = git?.prUrl.orEmpty()): Outcome {
        if (problem.isNotBlank()) ev(Agent.MANAGER, (if (testOk == null) "⚠ " else "✗ ") + problem)
        val lt = lastTest
        val chk = when {
            git == null -> null
            noCi -> Seven.CheckState.NONE
            testOk == true -> Seven.CheckState.PASS
            lt == null -> Seven.CheckState.NONE
            lt.ok == false -> Seven.CheckState.FAIL
            else -> Seven.CheckState.RUNNING
        }
        var rep = Seven.report(task, mode, changes.entries.map { it.key to it.value }, bugs, testOk,
            if (testOk == true) passNote else problem.take(300), pr, backup, chk, lt?.url.orEmpty(),
            if (changes.isEmpty() && pr.isBlank()) "कोई code नहीं बदला, इसलिए PR नहीं बना" else git?.prNote.orEmpty())
        if (full) {
            if (qaChecks.isNotEmpty()) rep += "\n\n✅ QA की जाँच:\n" + qaChecks.take(8).joinToString("\n") { "• $it" }
            if (perfNotes.isNotEmpty()) rep += "\n\n⚡ परफॉरमेंस के सुझाव (ज़रूरी नहीं):\n" + perfNotes.take(5).joinToString("\n") { "• $it" }
            if (testOk == true && git != null) rep += "\n\n🚀 डिप्लॉयमेंट: \"हाँ, merge करो\" / \"Merge करें\" कहने पर ही — PR दोबारा जाँच → merge → APK build → " +
                "release में APK सच में है, यह पक्का करके version बताऊँगा। आपकी हाँ के बिना कुछ merge या release नहीं होगा।"
        }
        if (testOk != true && problem.isNotBlank()) rep += "\n\n• रुकने की वजह: $problem" +
            if (mode == Seven.Mode.GITHUB) "\n• main अछूता है${if (backup.isNotBlank()) ", backup: $backup" else ""}। आगे क्या करूँ, बताइए।" else ""
        return Outcome(rep, files.staged(), testOk, mode)
    }
}

/** नॉर्मल मोड की फ़ाइलें: user के भेजे कोड (मूल) + इस काम में बदली/नई (फ़ोन की memory में) */
class MemoryFiles(initial: Map<String, String>) : TeamFiles {
    private val orig = LinkedHashMap(initial)
    private val stagedMap = linkedMapOf<String, String?>()
    override suspend fun list() = (orig.keys + stagedMap.filterValues { it != null }.keys).distinct().filter { stagedMap[it] != null || it !in stagedMap }
    override suspend fun read(path: String) = if (path in stagedMap) stagedMap[path] else orig[path]
    override suspend fun original(path: String) = orig[path]
    override suspend fun stage(path: String, content: String?) { stagedMap[path] = content }
    override fun staged(): Map<String, String?> = LinkedHashMap(stagedMap)
    override suspend fun all(): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(orig); stagedMap.forEach { (p, c) -> if (c == null) remove(p) else put(p, c) }
    }
}
