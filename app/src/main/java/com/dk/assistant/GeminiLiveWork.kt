package com.dk.assistant

import com.dk.assistant.dkai.DkaiCore
import com.dk.assistant.dkai.DkaiLive
import com.dk.assistant.dkai.LiveWorkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 14 नवंबर 2026: DK लाइव का बड़ा काम जो मालिक की "हाँ" का इंतज़ार कर रहा है */
internal object LiveWork {
    @Volatile var pending: LiveWorkGate.Pending? = null
}

/** tool के args में काम का विवरण */
internal fun liveWorkText(name: String, args: JSONObject): String = when (name) {
    "story_task" -> args.optString("topic")
    "build_website" -> args.optString("prompt")
    "make_game" -> args.optString("description")
    "app_studio" -> args.optString("text")
    "social_insights" -> args.optString("request")
    "image_task" -> args.optString("prompt").ifBlank { args.optString("request") }
    else -> args.optString("task")
}.trim()

/**
 * 14 नवंबर 2026 (मालिक): DK लाइव का हर बड़ा काम दो जाँच से गुज़रे -
 *  1) DKAI पर चुना टैब ([DkaiCore.routeFor]): Broadcast = कहानी होम पर लाइव; Pipeline / Builder / Agent / Chat = उसी में.
 *  2) पक्की "हाँ" ([LiveWorkGate]): कहानी / ऐप / वेबसाइट / गेम / कोड / DK कंप्यूटर / इमेज / सोशल रिव्यू पहले पूछकर.
 * null = रुकावट नहीं, पुराना रास्ता चलाओ. कुछ लौटा = वही tool का जवाब.
 */
internal suspend fun GeminiLive.workGate(name: String, args: JSONObject): String? {
    val mode = withContext(Dispatchers.Main) { DkaiLive.liveMode() }
    val route = DkaiCore.routeFor(mode, name)
    if (route is DkaiCore.Route.Ask && mode != null) { log("मोड $mode: $name इस टैब का नहीं - ${route.suggest} बताया"); return DkaiCore.routeAskNote(mode, route.suggest) }
    val text = liveWorkText(name, args)
    // वेबसाइट का ब्रीफ़ अधूरा = Forge खुद सवाल पूछता है (कुछ शुरू नहीं होता) - उससे पहले हाँ नहीं माँगते
    val briefOpen = name == "build_website" && com.dk.assistant.forge.ForgeBrief.liveGate(args) != null
    if (!briefOpen && LiveWorkGate.needs(name, args.optString("action"), text)) {
        val now = System.currentTimeMillis()
        when (LiveWorkGate.check(name, LiveWork.pending, DKState.lastUserAt, DKState.lastUser, now)) {
            LiveWorkGate.Gate.Ask -> {
                LiveWork.pending = LiveWorkGate.Pending(name, text, now)
                log("बड़ा काम ($name) - पहले मालिक की हाँ माँगी")
                return LiveWorkGate.askNote(text.ifBlank { name })
            }
            LiveWorkGate.Gate.NotHeard -> { log("बड़ा काम ($name) - मालिक की साफ़ हाँ नहीं सुनी, शुरू नहीं किया"); return LiveWorkGate.NOT_HEARD }
            LiveWorkGate.Gate.Run -> { LiveWork.pending = null; log("बड़ा काम ($name) - मालिक ने हाँ कहा, शुरू") }
        }
    }
    val run = route as? DkaiCore.Route.Run ?: return null
    return when (run.target) {
        "agent" -> if (name == "computer_task") null else agentTool("computer_task", JSONObject().put("task", text))
        else -> withContext(Dispatchers.Main) {
            val s = DkaiLive.screen() ?: return@withContext null
            when (run.target) {
                "cast" -> s.castFromLive(text.ifBlank { DKState.lastUser })?.let { "NOT_STARTED: $it - tell the user this in one short line." }
                    ?: "STARTED on the DKAI screen (Broadcast): the script is being written live in front of the user, every character gets its own voice and tone, and the audio will be generated and played automatically when it is ready. Say ONE short line that it is being written - do NOT tell the story yourself and do not read the script."
                "pipeline" -> { s.pipelineFromLive(text); "STARTED in the DKAI Pipeline on screen: it plans the steps first and builds them one by one. Say one short line; it is NOT done yet." }
                "builder" -> com.dk.assistant.studio.StudioSession.start(s.activity, text, com.dk.assistant.studio.StudioSession.WHO_COMPUTER)
                    ?.let { "NOT_STARTED: $it" } ?: "STARTED in the DKAI Builder (App Studio). Say one short line; it is NOT done yet."
                "chat" -> if (s.chatFromLive("कहानी लिखो: $text")) "WRITING in the DKAI chat on screen. Say one short line; do not read the whole story aloud." else "NOT_STARTED: the chat could not take it right now."
                else -> null
            }
        }
    }
}
