package com.dk.assistant.agent.android

import com.dk.assistant.CoderAi
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.LearnedWays
import com.dk.assistant.LocalMemory
import com.dk.assistant.Prefs
import com.dk.assistant.TaskStatus
import com.dk.assistant.agent.core.AiTalk
import com.dk.assistant.agent.core.AiTalkPlan
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.TalkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): "जाओ Claude / ChatGPT / Gemini / Perplexity (या कोई भी AI ऐप) से बात करो, मुकाबला करो,
 * सवाल-जवाब करो, उससे कुछ सीखो या काम करवाओ" - DK लाइव का ai_talk tool.
 *
 * असली ऐप फ़ोन की स्क्रीन पर खुलता है (मालिक के सामने). DK खुद लिखता-भेजता है, जवाब पढ़ता है, अगला संदेश
 * अपनी AI से सोचकर लिखता है, सामने वाले के सवाल का जवाब खुद देता है ([AiTalk]). कुछ मिनट चलता है; हर कदम
 * [TaskStatus] में (task_status पर सच), खत्म होने पर DK लाइव को SYSTEM NOTE ([liveNote]) - वह बोलकर सार बताता है.
 * पूरी बातचीत लोकल याददाश्त में; सीखे तरीके [LearnedWays] में (हमेशा) - आगे हर काम में DK उन्हें इस्तेमाल करता है.
 */
object AiTalkAgent {
    const val KEY = "aitalk"

    @Volatile private var job: Job? = null
    @Volatile private var stopAsked = false
    @Volatile private var spoken: String? = null
    /** आख़िरी बार जिस AI ऐप से बात हुई - "क्या जवाब आया / उससे यह भी पूछो" में ऐप का नाम न बोले तो वही */
    @Volatile private var lastApp: com.dk.assistant.agent.core.ChatTarget? = null
    val busy get() = job?.isActive == true

    fun stop(): Boolean {
        val j = job ?: return false
        if (!j.isActive) return false
        stopAsked = true
        j.cancel()
        return true
    }

    suspend fun start(svc: DKAccessibilityService, args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        if (action in setOf("stop", "cancel", "रोको")) return if (stop()) "STOPPED: DK stopped the AI-app conversation. Tell the user in one short line." else "Nothing was running. Tell the user no AI-app conversation is running."
        if (action == "status") return TaskStatus.summary()
        // नवंबर 2026 (मालिक: "ऐप में क्या हो रहा है, DK को दिखे; मैं सवाल बोलूँ तो वह वहीं पूछे और जवाब बताए")
        if (action in setOf("read", "see", "देखो", "पढ़ो") || action == "ask") {
            val h = ImageGenAgent.Host(svc)
            val words = args.optString("goal").ifBlank { args.optString("question") }.ifBlank { DKState.lastUser.trimEnd('…') }.trim()
            val t = AiTalkPlan.target(args.optString("app"), words) { h.findApp(it) } ?: lastApp
                ?: return "ERROR: which AI app is not clear. Ask the user in one short Hindi line which app."
            if (action != "ask") return ImageGenAgent.read(svc, t)
            if (busy) return "BUSY: DK is in the middle of its own conversation with ${t.hi}. Ask the user if he wants to stop it first (ai_talk action=stop), then ask."
            val q = args.optString("question").ifBlank { args.optString("goal") }.trim()
            if (q.isBlank()) return "ERROR: what to ask is not clear. Ask the user in one short Hindi line what to ask ${t.hi}."
            lastApp = t
            if (!WakeForWork.ensure(svc) { DKState.hint(it) }) return "ERROR: फ़ोन लॉक है - ${t.hi} नहीं खोल पाया. Tell the user to unlock the phone, then ask again."
            DKState.set(com.dk.assistant.Mode.WORKING)          // काम के बीच स्क्रीन जगी (WorkAwake)
            DKState.hint("${t.hi} से पूछ रही हूँ…")
            val flow = ImageGenFlow(AccessibilityScreenDriver(svc), h)
            // वही chat (सामने हो तो) - New chat नहीं; ऐप सामने न हो तो खोलकर उसकी आख़िरी chat में
            val r = flow.askIn(t, q, fresh = false)
            LocalMemory.record(svc, "d", "मैंने ${t.hi} से पूछा: $q", "ai_talk")
            if (!r.ok) return "ERROR: " + r.error.ifBlank { "भेज नहीं पाया" } + ". Tell the user exactly this in one short Hindi line."
            if (r.appReply.isBlank()) return "SENT: DK typed and sent the question in ${t.hi}, but could not read the answer yet. Tell the user it is sent and visible on the screen; he can say 'क्या जवाब आया' (ai_talk action=read)."
            LocalMemory.record(svc, "d", "${t.hi} ने कहा: ${r.appReply}", "ai_talk")
            return "ASKED_AND_ANSWERED: DK typed the user's question in ${t.hi} (same chat), pressed Send, and ${t.hi} replied: \"${r.appReply}\". " +
                "Tell the user this reply in short Hindi without adding anything. ${t.hi} stays open on the screen - ask the next question the same way (ai_talk action=ask)."
        }
        if (busy) return "BUSY: DK is already talking with an AI app (${TaskStatus.get(KEY)?.task ?: "-"}). Ask the user if he wants to stop it first (ai_talk action=stop)."
        val goal = args.optString("goal").ifBlank { args.optString("request") }.ifBlank { DKState.lastUser.trimEnd('…') }.trim()
        val host = ImageGenAgent.Host(svc)
        val app = AiTalkPlan.target(args.optString("app"), goal) { host.findApp(it) }
            ?: return "ERROR: which AI app is not clear. Ask the user in one short Hindi line which app (ChatGPT, Claude, Gemini, Perplexity, Copilot, DeepSeek, Grok, Meta AI या कोई और)."
        if (!host.installed(app.pkg)) return "ERROR: ${app.hi} ऐप इस फ़ोन में नहीं है. Tell the user to install it from Play Store and log in once, then ask again."
        lastApp = app
        val mode = TalkMode.parse(args.optString("mode"), goal)
        val max = AiTalkPlan.rounds(args.optInt("rounds", 0), mode)
        val flow = ImageGenFlow(AccessibilityScreenDriver(svc), host)
        val learned = try { LearnedWays.all(svc).filter { it.from == app.hi }.map { it.text } } catch (_: Exception) { emptyList() }
        val lines = mutableListOf<AiTalk.Line>()
        stopAsked = false
        TaskStatus.start(KEY, "DK की AI से बातचीत", "DK's conversation with ${app.hi} (on screen)", "${app.hi} • ${mode.hi}: $goal")
        DKLog.d("AiTalk: ${app.hi} (${app.pkg}) • ${mode.name} • $max बारी • \"${goal.take(80)}\"")
        LocalMemory.record(svc, "d", "${app.hi} से ${mode.hi} शुरू की: $goal", "ai_talk")
        job = svc.scope.launch {
            var state = TaskStatus.State.FAILED
            var result = ""
            try {
                // नवंबर 2026: स्क्रीन बंद / लॉक = पहले जगाओ, वरना ऐप खुलता ही नहीं (बीच में स्क्रीन WorkAwake जगाए रखता है)
                if (!WakeForWork.ensure(svc) { TaskStatus.step(KEY, it) }) {
                    result = "फ़ोन लॉक रहा - ${app.hi} नहीं खोल पाया (अनलॉक करके फिर बोलिए)"
                    spoken = result
                    return@launch
                }
                val talk = AiTalk(
                    app = app.hi, mode = mode, goal = goal, max = max,
                    send = { text, fresh -> flow.askIn(app, text, fresh) },
                    brain = { sys, user -> think(svc, sys, user) },
                    user = Prefs.userName(svc), learnedBefore = learned,
                    onLine = { l ->
                        lines += l
                        val who = if (l.dk) "DK" else app.hi
                        TaskStatus.step(KEY, "$who: ${l.text.take(160)}")
                        DKState.hint("$who: ${l.text.take(80)}")
                        LocalMemory.record(svc, "d", (if (l.dk) "मैंने ${app.hi} से कहा: " else "${app.hi} ने कहा: ") + l.text, "ai_talk")
                    },
                    stopped = { stopAsked },
                )
                val r = talk.run()
                val added = if (r.lessons.isNotEmpty()) LearnedWays.add(svc, r.lessons, app.hi, goal) else 0
                DKLog.d("AiTalk: खत्म - ${r.turns} जवाब, ${r.lessons.size} सबक ($added नए), गलती: ${r.error.ifBlank { "-" }}")
                LocalMemory.record(svc, "d", "${app.hi} से ${mode.hi} का सार: ${r.summary}", "ai_talk")
                state = when {
                    r.stoppedByUser -> TaskStatus.State.CANCELLED
                    r.ok && r.error.isBlank() -> TaskStatus.State.DONE
                    r.ok -> TaskStatus.State.NOT_DONE
                    else -> TaskStatus.State.FAILED
                }
                spoken = liveText(app.hi, mode, r, added)
                result = (if (r.ok) r.summary else r.error.ifBlank { r.summary }) + (if (added > 0) " • $added नए तरीके सीखे (हमेशा याद)" else "")
            } catch (_: CancellationException) {
                state = TaskStatus.State.CANCELLED
                result = "मालिक के कहने पर रोका - ${lines.count { !it.dk }} जवाब तक बात हुई"
                spoken = null
            } catch (e: Exception) {
                DKLog.d("AiTalk गड़बड़: ${e.javaClass.simpleName} ${e.message}")
                result = "${app.hi} से बातचीत बीच में रुकी (${e.javaClass.simpleName})"
            } finally {
                TaskStatus.end(KEY, state, result)
            }
        }
        return "STARTED: DK opened ${app.hi} ON THE SCREEN and is now having ${mode.en} with it by itself (up to $max turns, a few minutes). " +
            "Say ONE short Hindi line like 'ठीक है बॉस, ${app.hi} से बात कर रहा हूँ - आप स्क्रीन पर देखिए' and then stay quiet. " +
            "You will get a SYSTEM NOTE with the real summary when it ends - never invent what ${app.hi} said before that. The user can say रोको to stop (ai_talk action=stop)."
    }

    /** DK की अपनी AI: DK कंप्यूटर की keys (chain), न हों तो Live वाली Gemini key */
    private suspend fun think(svc: DKAccessibilityService, sys: String, user: String): String? {
        val ai = try { CoderAi.forComputer(svc) { p, f -> DKLog.d("AiTalk AI: $p → $f") } } catch (_: Exception) { null }
        if (ai != null) {
            val t = try { runInterruptible(Dispatchers.IO) { ai.chat(sys, listOf("user" to user)) } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { DKLog.d("AiTalk AI गलती: ${e.message}"); null }
            if (!t.isNullOrBlank()) return t
        }
        return runInterruptible(Dispatchers.IO) { ImageGenAgent.textAsk(svc, sys + "\n\n" + user) }
    }

    private fun liveText(app: String, mode: TalkMode, r: AiTalk.Result, added: Int): String = buildString {
        append(r.summary)
        if (r.lessons.isNotEmpty()) {
            append(" सीखी बातें: ")
            append(r.lessons.take(4).joinToString("; "))
            append(if (added > 0) " ($added नए तरीके हमेशा के लिए याद)" else " (ये पहले से याद थे)")
        }
        if (r.error.isNotBlank()) append(" रुकने की वजह: ${r.error}")
    }

    /** DK लाइव को काम खत्म होने पर (पूरा सार, ताकि बोलकर बताए) */
    fun liveNote(j: TaskStatus.Job): String? {
        if (j.key != KEY) return null
        val s = spoken?.takeIf { it.isNotBlank() } ?: return null
        spoken = null
        return "SYSTEM NOTE: DK's conversation with the AI app (\"${j.task}\") has REALLY ended (state: ${j.state.en}). " +
            "Speak this to the user now, naturally and briefly, in the reply language, without adding anything that is not here: \"$s\". " +
            "The whole conversation is open on the screen and saved in DK's memory on the phone."
    }
}
