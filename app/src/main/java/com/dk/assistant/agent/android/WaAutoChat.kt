package com.dk.assistant.agent.android

import android.app.Notification
import android.content.Context
import android.os.Bundle
import com.dk.assistant.BrainManager
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKNotifListener
import com.dk.assistant.DKState
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.WaScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * नवंबर 2026 (मालिक: "जिसका भी मैसेज आ रहा है, उससे बातें करो - उधर से जैसा सवाल, इधर से उसी हिसाब से जवाब").
 * चालू = WhatsApp का नया मैसेज (notification) आते ही उसकी बातचीत पढ़कर मालिक की ओर से जवाब, notification के reply से
 * (स्क्रीन नहीं छीनता). नियम ([WaScreen.decide]): तय समय/गिनती तक, ग्रुप नहीं (नाम से कहा हो तो ही), पैसे/OTP वाली बात = खुद नहीं, मालिक को बताओ.
 */
object WaAutoChat {
    @Volatile private var session: WaScreen.Session? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** हर चैट की आख़िरी notification - कई मैसेज जल्दी-जल्दी आएँ तो एक ही जवाब */
    private val gen = HashMap<String, Int>()
    private const val SETTLE_MS = 3_500L

    fun on(target: String, minutes: Int, hint: String): String {
        val m = WaScreen.minutes(minutes)
        session = WaScreen.Session(target.trim(), System.currentTimeMillis() + m * 60_000L, hint = hint.trim())
        DKLog.d("WhatsApp अपने-आप बात: चालू ($m मिनट, ${if (target.isBlank()) "सब" else "एक व्यक्ति"})")
        val who = target.trim().ifBlank { "जिसका भी (ग्रुप छोड़कर)" }
        return "AUTO CHAT ON: अगले $m मिनट तक $who का नया WhatsApp मैसेज आते ही DK आपकी ओर से उसी बात के हिसाब से जवाब देगा " +
            "(ज़्यादा से ज़्यादा ${WaScreen.MAX_REPLIES} जवाब; पैसे/OTP/बैंक वाली बात पर खुद नहीं - आपको बताएगा). " +
            "Needs DK notification access. Tell the user this in short Hindi; 'बंद करो' = action auto_chat_off."
    }

    fun off(): String {
        val s = session; session = null
        return if (s == null) "AUTO CHAT was already off." else "AUTO CHAT OFF: ${s.replies} जवाब भेजे थे। Tell the user."
    }

    fun active(): Boolean = session?.let { System.currentTimeMillis() <= it.until } == true

    /** EXTRA_MESSAGES -> (भेजने वाला खाली = मालिक खुद, text) */
    fun history(msgs: Array<android.os.Parcelable>?): List<Pair<String, String>> =
        msgs.orEmpty().mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val sender = b.getCharSequence("sender")?.toString()
                ?: (if (android.os.Build.VERSION.SDK_INT >= 28) (b.get("sender_person") as? android.app.Person)?.name?.toString() else null)
            sender.orEmpty().trim() to text
        }

    /** DKNotifListener से: नया notification आया */
    fun onPosted(c: Context, key: String, pkg: String, title: String, extras: Bundle, canReply: Boolean) {
        val s = session ?: return
        if (!pkg.startsWith(Platform.WHATSAPP.pkg)) return
        val h = history(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        if (h.isEmpty()) return
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val last = h.last()
        val lastMine = last.first.isEmpty()
        val now = System.currentTimeMillis()
        when (val d = WaScreen.decide(s, title, last.second, lastMine, isGroup, canReply, now)) {
            is WaScreen.Decision.Skip -> { if (d.why == "time over") { session = null; DKLog.d("WhatsApp अपने-आप बात: समय पूरा, बंद") }; return }
            is WaScreen.Decision.AskOwner -> {
                DKLog.d("WhatsApp अपने-आप बात: पैसे/OTP जैसी बात - खुद जवाब नहीं, मालिक को बताया")
                if (DKState.live) DKAccessibilityService.instance?.gemLive?.pushNotif("WhatsApp", title,
                    "(DK ने अपने-आप जवाब नहीं दिया - पैसे/OTP/बैंक जैसी बात है, आप बताइए क्या कहना है) " + last.second)
                return
            }
            WaScreen.Decision.Reply -> Unit
        }
        val k = WaScreen.key(title)
        val my = synchronized(gen) { (gen[k] ?: 0).plus(1).also { gen[k] = it } }
        scope.launch {
            delay(SETTLE_MS)   // और मैसेज आ रहे हों तो रुककर एक साथ जवाब
            if (synchronized(gen) { gen[k] } != my || session !== s) return@launch
            val msgs = h.takeLast(14).map { WaScreen.Msg(it.second, it.first.isEmpty()) }
            val text = WaScreen.clean(BrainManager(c).quickText(WaScreen.SYSTEM, WaScreen.replyPrompt(title, msgs, s.hint)))
            if (text == null || WaScreen.unsafe(text)) { DKLog.d("WhatsApp अपने-आप बात: जवाब नहीं भेजा (बना नहीं / निजी जानकारी)"); return@launch }
            val err = DKNotifListener.reply(c, key, text)
            if (err == null) {
                WaScreen.sent(s, title, text, System.currentTimeMillis())
                DKLog.d("WhatsApp अपने-आप बात: जवाब भेजा (${s.replies}/${s.maxReplies})")
            } else DKLog.d("WhatsApp अपने-आप बात: जवाब नहीं गया - $err")
        }
    }
}
