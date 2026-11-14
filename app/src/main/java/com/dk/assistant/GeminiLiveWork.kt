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
 * 14 नवंबर 2026 (मालिक): DK लाइव का हर बड़ा काम इस जाँच से गुज़रे -
 *  पक्की "हाँ" ([LiveWorkGate]): कहानी / ऐप / वेबसाइट / गेम / कोड / DK कंप्यूटर / इमेज / सोशल रिव्यू पहले पूछकर.
 * null = रुकावट नहीं, पुराना रास्ता चलाओ. कुछ लौटा = वही tool का जवाब.
 */
internal suspend fun GeminiLive.workGate(name: String, args: JSONObject): String? {
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
    // 14 नवंबर 2026 (मालिक): DKAI के मोड टैब (Broadcast / Pipeline / Builder / Agent) हटे - हाँ के बाद हर काम अपने पुराने रास्ते से
    return null
}
