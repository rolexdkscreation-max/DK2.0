package com.dk.assistant

import kotlinx.coroutines.CancellationException

/**
 * 28 नवंबर 2026 (मालिक): खोज / स्क्रॉल का सीधा काम हुआ = DK खुद स्क्रीन पर आए नतीजे पढ़कर Gemini को दे ([SearchSee]),
 * ताकि वह बताए "क्या-क्या आया" और अगला कदम पूछे. निजी ऐप (चैट / गैलरी) सामने = कुछ नहीं पढ़ता. कोई गड़बड़ = पुराना जवाब ही.
 */
internal suspend fun GeminiLive.withSeen(q: QuickCommands.Quick, r: String, res: String): String {
    val type = q.action.optString("type")
    if (!LiveFast.ok(type, r)) return res
    return try {
        val fg0 = svc.currentPackage()
        val k = SearchSee.kind(q.action, fg0) ?: return res
        svc.waitSettle(fg0, false, SearchSee.SETTLE_MS)
        val fg = svc.currentPackage()
        if (fg.isBlank() || fg == svc.packageName || SearchSee.privateApp(fg)) return res
        val seen = SearchSee.lines(svc.readScreen().text)
        log("खोज के बाद स्क्रीन पढ़ी ($type, ${seen.size} लाइनें) - नतीजे बताकर अगला कदम पूछेगा")
        res + SearchSee.note(k, q.action.optString("query"), seen)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Quiet.note(e); res
    }
}
