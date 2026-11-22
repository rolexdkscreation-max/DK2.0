package com.dk.assistant

/**
 * 22 नवंबर 2026 (मालिक: "एक साथ 10, 12 काम बोले तो DK ख़ुद सोचे कि पहले क्या करना है - YouTube वीडियो पीछे नहीं
 * चलता तो उसे बाद में; जितना बोला उतना ही, एक्स्ट्रा नहीं").
 *
 * जड़: (1) बात ख़त्म होते ही फ़ोन का तेज़ रास्ता (Gemini से पहले, LocalRouter "steps") कई कामों वाली बात को बोले गए
 * क्रम में ही चला देता था - "गाना चलाओ, फिर टॉर्च, फिर राहुल को मैसेज" = गाना पहले, मैसेज खोलते ही गाना बंद.
 * (2) Gemini ने कई काम एक phone_task में न भेजकर अलग-अलग (या साथ-साथ) भेजे = कतार / समझदार क्रम बना ही नहीं, और बाद
 * वाली call चल रही कतार में दोबारा जुड़ जाती (वही काम दो बार = एक्स्ट्रा).
 *
 * अब: आपकी बात (caption) में दो या ज़्यादा अलग काम = तेज़ रास्ता नहीं; Gemini का पहला काम वाला tool = पूरी बात की कतार
 * ([TaskQueue.plan] वाला क्रम), उसी बात के बाक़ी tool = "कतार में पहले से है" (कुछ नहीं चलता).
 * यह फ़ाइल Android के बिना है (JVM test).
 */
object MultiGate {
    /** उसी बात से आई दोबारा call इतनी देर तक "पहले से कतार में" */
    const val DUP_MS = 90_000L
    /** इस नाम से runTools में "पहले से कतार में" वाला जवाब */
    const val DUP_TOOL = "__queue_dup"

    private val WA_WORK = setOf("send_message", "voice_call", "video_call", "send_location", "send_live_location", "send_file", "send_camera_photo", "send_contact")

    /** फ़ोन पर काम करने वाला tool (कतार इन्हीं से बनती है) */
    fun work(name: String, action: String = ""): Boolean =
        name == "phone_task" || (name == "whatsapp_task" && action.trim().lowercase() in WA_WORK)

    /** आपकी बात में अलग-अलग काम (कम से कम दो असली काम) - नहीं तो खाली */
    fun userItems(user: String): List<TaskQueue.Item> {
        val u = user.trim()
        if (u.length < 6 || TaskQueue.isStop(u)) return emptyList()
        val l = TaskQueue.split(u)
        return if (l.size >= 2 && l.count { it.domain != "other" } >= 2) l else emptyList()
    }

    fun isMulti(user: String) = userItems(user).isNotEmpty()

    enum class Act { PASS, LEAD, DUP }
    data class Pick(val act: Act, val request: String = "")

    /**
     * [claimedUser] / [claimedAt] = पिछली बार किस बात पर कतार बनी. LEAD = यह call पूरी बात की कतार चलाए ([Pick.request]).
     * Gemini ने ख़ुद पूरी बात भेजी (दो+ काम, आपकी बात से ज़्यादा नहीं) = उसी के शब्द; वरना आपकी बात के शब्द.
     */
    fun decide(name: String, action: String, req: String, user: String, claimedUser: String, claimedAt: Long, now: Long): Pick {
        if (!work(name, action)) return Pick(Act.PASS)
        if (name == "phone_task" && TaskQueue.isStop(req)) return Pick(Act.PASS)
        val u = user.trim()
        if (u.isNotBlank() && u == claimedUser.trim() && now - claimedAt in 0 until DUP_MS) return Pick(Act.DUP)
        val ui = userItems(u)
        if (ui.isEmpty()) return Pick(Act.PASS)
        val gi = if (name == "phone_task") TaskQueue.split(req) else emptyList()
        return Pick(Act.LEAD, if (gi.size >= 2 && gi.size <= ui.size) req.trim() else u)
    }

    fun dupNote(): String =
        "QUEUE_OK: this task is ALREADY in the task list the phone is running from the user's own words (all his tasks, a smart order, one by one). " +
            "Do NOT call any tool for it again and do not add anything he did not ask. Say nothing more (or only \"ठीक है\")."

    const val FAST_SKIP_LOG = "कई काम एक साथ - तुरंत वाला रास्ता नहीं, समझदार क्रम वाली कतार से"
}
