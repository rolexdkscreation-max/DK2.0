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
