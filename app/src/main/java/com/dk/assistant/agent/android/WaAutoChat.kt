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
import com.dk.assistant.agent.social.WaChatMode
import com.dk.assistant.agent.social.WaScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * नवंबर 2026 (मालिक: "जिसका भी मैसेज आ रहा है, उससे बातें करो - उधर से जैसा सवाल, इधर से उसी हिसाब से जवाब").
 * चालू = WhatsApp का नया मैसेज (notification) आते ही उसकी बातचीत पढ़कर मालिक की ओर से जवाब, notification के reply से
 * (स्क्रीन नहीं छीनता). नियम ([WaScreen.decide]): तय समय/गिनती तक, ग्रुप नहीं (नाम से कहा हो तो ही), पैसे/OTP वाली बात = खुद नहीं, मालिक को बताओ.
 */
object WaAutoChat {
    data class PendingConfirm(val target: String, val minutes: Int, val hint: String)

    @Volatile var pendingConfirm: PendingConfirm? = null
    @Volatile private var session: WaScreen.Session? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** हर चैट की आख़िरी notification - कई मैसेज जल्दी-जल्दी आएँ तो एक ही जवाब */
    private val gen = HashMap<String, Int>()
    private const val SETTLE_MS = 3_500L

    fun askConfirm(target: String): String {
        val t = target.trim()
        val who = if (t.isBlank()) "सामने वाले" else t
        return "$who को मैसेज करना है, क्या इसी को मैसेज करना है?"
    }

    fun startWithConfirm(target: String, minutes: Int, hint: String): String {
        // नवंबर 2026 (PR #281 की कमी): किसी का नाम नहीं = "जिसका भी आए" - पूछने को कुछ नहीं, सीधे चालू
        if (target.isBlank()) return on("", minutes, hint)
        pendingConfirm = PendingConfirm(target.trim(), minutes, hint.trim())
        return "WAITING_CONFIRMATION: Ask the user exactly: '" + askConfirm(target) + "' Only after a clear yes call whatsapp_task " +
            "action=auto_chat_confirm; if he says no / another name, call action=auto_chat_cancel (then start again with the right contact)."
    }

    /** Live से: मालिक ने "हाँ" कहा (पहले यह कहीं से बुलाया ही नहीं जाता था - अपने-आप बात कभी चालू नहीं होती थी) */
    fun confirmTool(): String = confirmOk() ?: "Nothing is waiting for confirmation. If the user wants auto chat, call action=auto_chat_on again."

    fun cancelTool(): String { val had = pendingConfirm != null; cancelPending(); return if (had) "AUTO CHAT not started (user said no). Ask whom to chat with." else "Nothing was waiting." }

    fun confirmOk(): String? {
        val p = pendingConfirm ?: return null
        pendingConfirm = null
        return on(p.target, p.minutes, p.hint)
    }

    fun cancelPending() {
        pendingConfirm = null
    }

    fun on(target: String, minutes: Int, hint: String): String {
        pendingConfirm = null
        val m = WaScreen.minutes(minutes)
        session = WaScreen.Session(target.trim(), System.currentTimeMillis() + m * 60_000L, hint = hint.trim())
        DKLog.d("WhatsApp अपने-आप बात: चालू ($m मिनट, ${if (target.isBlank()) "सब" else "एक व्यक्ति"})")
        val who = target.trim().ifBlank { "जिसका भी (ग्रुप छोड़कर)" }
        return "AUTO CHAT ON: अगले $m मिनट तक $who का नया WhatsApp मैसेज आते ही DK आपकी ओर से उसी बात के हिसाब से जवाब देगा " +
            "(ज़्यादा से ज़्यादा ${WaScreen.MAX_REPLIES} जवाब; पैसे/OTP/बैंक वाली बात पर खुद नहीं - आपको बताएगा). " +
            "Needs DK notification access. Tell the user this in short Hindi; 'बंद करो' = action auto_chat_off."
    }

    fun off(): String {
        pendingConfirm = null
        if (chat != null) return chatOff()
        val s = session; session = null
        return if (s == null) "AUTO CHAT was already off." else "AUTO CHAT OFF: ${s.replies} जवाब भेजे थे। Tell the user."
    }

    fun active(): Boolean = session?.let { System.currentTimeMillis() <= it.until } == true || chatActive()

    // ---------- नवंबर 2026 (मालिक): चैटिंग मोड - पहले बॉस से पूछो, बॉस बोले वही टाइप करो, "खुद कर दो" = खुद ----------

    @Volatile private var chat: WaChatMode.State? = null
    /** हर चैट का सबसे नया notification key + बातचीत (बॉस के बोले जवाब को उसी notification के reply से भेजने के लिए) */
    private val notifKey = HashMap<String, String>()
    private val chatHist = HashMap<String, List<Pair<String, String>>>()

    fun chatActive(): Boolean = chat?.let { System.currentTimeMillis() <= it.until } == true

    /** चालू करो; chats = WhatsApp सूची (स्क्रीन / notification से पढ़ी) */
    fun chatOn(minutes: Int, chats: String): String {
        pendingConfirm = null; session = null
        val m = WaChatMode.minutes(minutes)
        chat = WaChatMode.State(System.currentTimeMillis() + m * 60_000L)
        synchronized(notifKey) { notifKey.clear(); chatHist.clear() }
        synchronized(handled) { handled.clear() }
        DKLog.d("WhatsApp चैटिंग मोड: चालू ($m मिनट)")
        return WaChatMode.onNote(m, chats)
    }

    fun chatOff(): String {
        val s = chat; chat = null
        watchJob?.cancel(); watchJob = null
        DKLog.d("WhatsApp चैटिंग मोड: बंद")
        return if (s == null) "CHAT MODE was already off." else "CHAT MODE OFF: ${s.replies} जवाब भेजे थे। Say in short Hindi: 'ठीक है बॉस, चैटिंग मोड बंद कर दिया।'"
    }

    fun chatPick(contacts: String): String {
        val s = chat ?: return "CHAT MODE is off. If the user wants it, call action=chat_mode_on."
        val names = WaChatMode.pick(s, contacts)
        val waiting = s.waiting.filterKeys { s.all || it in s.picked }.values
        return "PICKED: अब सिर्फ़ ${names.joinToString(", ")} से बात होगी (बाकी किसी को जवाब नहीं)। " +
            (if (waiting.isEmpty()) "Tell the boss in one short Hindi line."
            else "Their latest messages:\n" + waiting.joinToString("\n") { "- ${it.chat}: \"${it.text.take(160)}\"" } +
                "\nTell the boss what each wrote and ask 'बॉस, क्या जवाब दूँ? या मैं खुद कर दूँ?'")
    }

    fun chatBoss(contact: String): String {
        val s = chat ?: return "CHAT MODE is off."
        WaChatMode.bossOn(s, contact)
        return "OK: ${contact.ifBlank { "सब" }} - अब हर मैसेज पर पहले बॉस से पूछूँगा। Tell him in one short line."
    }

    /** बॉस: "तुम खुद कर दो" - अभी वाले बिना-जवाब मैसेज का भी तुरंत जवाब */
    fun chatSelf(c: Context, contact: String): String {
        val s = chat ?: return "CHAT MODE is off. If the user wants DK to reply by itself, call action=chat_mode_on first."
        val who = WaChatMode.selfOn(s, contact)
        val now = s.waiting.filterKeys { s.selfAll || it in s.self }.keys.toList()
        now.forEach { k -> autoReply(c, s, k) }
        watch()                         // जिस चैट में DK है, वहीं रुककर आगे की बात भी खुद
        return "OK: $who के मैसेज का जवाब अब DK खुद देगा (पैसे/OTP/बैंक वाली बात पर फिर भी बॉस से पूछेगा)" +
            (if (now.isNotEmpty()) " - अभी ${now.size} चैट का जवाब बना रहा हूँ।" else "।") + " Tell the boss in one short Hindi line."
    }

    /** बॉस ने जवाब बोला - वही (Hinglish में) भेजो: पहले notification के reply से, न हो तो WhatsApp खोलकर */
    suspend fun chatReply(svc: DKAccessibilityService, contact: String, message: String): String {
        val text = message.trim()
        if (text.isEmpty()) return "FAILED: क्या जवाब भेजना है? Ask the boss what to reply."
        val s = chat
        val k = s?.let { WaChatMode.target(it, contact) } ?: WaScreen.key(contact)
        if (k.isBlank()) return "NEEDS USER CHOICE: किसे जवाब भेजना है? Ask the boss whose chat (name)."
        val name = s?.let { WaChatMode.nameOf(it, k) } ?: contact
        val key = synchronized(notifKey) { notifKey[k] }
        val err = key?.let { DKNotifListener.reply(svc, it, text) } ?: "no notification"
        if (err == null) {
            s?.let { WaChatMode.answered(it, k, text) }
            DKLog.d("WhatsApp चैटिंग मोड: बॉस का जवाब भेजा (notification से)")
            return "SENT to $name: \"$text\". Tell the boss in a few words: 'भेज दिया'. Then wait for the next message."
        }
        val r = WaLook.exactReply(svc, name, text)
        if (r.startsWith("SENT")) s?.let { WaChatMode.answered(it, k, text); claim(k, it.waiting[k]?.text ?: "") }
        return r
    }

    // ---------- नवंबर 2026 (मालिक: "एक बार उस व्यक्ति की चैट में जाए तो वहीं रुककर, जो भी आए वहीं टाइप करके बात
    // करता रहे - बार-बार बैक / इधर-उधर नहीं") ----------
    // WhatsApp खुली चैट का notification नहीं देता - इसलिए "खुद कर दो" के बाद DK खुली चैट पर नज़र रखता है (हर 2 सेकंड,
    // सिर्फ़ देखना). नया मैसेज = उसी चैट में लिखकर भेजो और बॉस को बताओ. कभी बैक / सर्च / दूसरी चैट नहीं खोलता.

    @Volatile private var watchJob: Job? = null
    /** हर चैट की जिस बात का जवाब दिया / दिया जा रहा (notification और स्क्रीन - दोनों से एक ही बार) */
    private val handled = HashMap<String, String>()
    private const val WATCH_MS = 2_000L

    /** true = यह बात अब मेरी (पहले किसी ने नहीं ली) */
    private fun claim(k: String, text: String): Boolean = synchronized(handled) {
        val t = text.trim()
        if (t.isEmpty() || handled[k] == t) false else { handled[k] = t; true }
    }

    private fun watch() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            DKLog.d("WhatsApp चैटिंग मोड: खुली चैट पर नज़र चालू (वहीं लिखेगा, बैक/सर्च नहीं)")
            while (true) {
                delay(WATCH_MS)
                val s = chat ?: break
                val svc = DKAccessibilityService.instance ?: continue
                val open = (try { WaLook.openNow(svc) } catch (_: Throwable) { null }) ?: continue
                val (title, msgs) = open
                val k = WaScreen.key(title)
                when (val h = WaChatMode.here(s, title, msgs, synchronized(handled) { handled[k] }, false, System.currentTimeMillis())) {
                    WaChatMode.Here.Stop -> { if (chat === s) chat = null; break }
                    is WaChatMode.Here.Wait -> Unit
                    is WaChatMode.Here.Ask -> if (claim(k, h.msg.text)) tell(WaChatMode.askNote(h.msg, true))
                    is WaChatMode.Here.Reply -> {
                        if (!claim(k, h.msg.text)) continue
                        synchronized(gen) { gen[k] = (gen[k] ?: 0) + 1 }        // notification वाला पुराना जवाब (अगर कतार में) रद्द
                        val text = WaScreen.clean(BrainManager(svc).quickText(WaScreen.SYSTEM, WaScreen.replyPrompt(title, msgs, "")))
                        if (text == null || WaScreen.unsafe(text)) { tell(WaChatMode.askNote(h.msg, false)); continue }
                        val r = WaLook.sendHere(svc, title, text)
                        if (r == null || r.startsWith("SUBMITTED")) {
                            WaChatMode.answered(s, k, text)
                            DKLog.d("WhatsApp चैटिंग मोड: खुली चैट में ही जवाब भेजा (${s.replies})")
                            tell(WaChatMode.autoNote(h.msg, text))
                        } else {
                            synchronized(handled) { if (handled[k] == h.msg.text) handled.remove(k) }   // अगली बार फिर
                            DKLog.d("WhatsApp चैटिंग मोड: खुली चैट में नहीं भेज पाया - ${r.take(80)}")
                        }
                    }
                }
            }
            DKLog.d("WhatsApp चैटिंग मोड: खुली चैट पर नज़र बंद")
        }
    }

    private fun tell(note: String) {
        if (DKState.live) DKAccessibilityService.instance?.gemLive?.pushNote(note)
    }

    private fun autoReply(c: Context, s: WaChatMode.State, k: String) {
        val inc = s.waiting[k] ?: return
        val key = synchronized(notifKey) { notifKey[k] } ?: return
        watch()
        val h = synchronized(notifKey) { chatHist[k] }.orEmpty()
        val my = synchronized(gen) { (gen[k] ?: 0).plus(1).also { gen[k] = it } }
        scope.launch {
            delay(SETTLE_MS)
            if (synchronized(gen) { gen[k] } != my || chat !== s) return@launch
            if (!claim(k, inc.text)) return@launch                 // खुली चैट वाले ने यह बात पहले ही ले ली
            val msgs = h.takeLast(14).map { WaScreen.Msg(it.second, it.first.isEmpty()) }.ifEmpty { listOf(WaScreen.Msg(inc.text, false)) }
            val text = WaScreen.clean(BrainManager(c).quickText(WaScreen.SYSTEM, WaScreen.replyPrompt(inc.chat, msgs, "")))
            if (text == null || WaScreen.unsafe(text)) { tell(WaChatMode.askNote(inc, false)); return@launch }
            if (DKNotifListener.reply(c, key, text) == null) {
                WaChatMode.answered(s, k, text)
                DKLog.d("WhatsApp चैटिंग मोड: खुद जवाब भेजा (${s.replies})")
                tell(WaChatMode.autoNote(inc, text))
            } else tell(WaChatMode.askNote(inc, false))
        }
    }

    private fun onChat(c: Context, s: WaChatMode.State, key: String, title: String, h: List<Pair<String, String>>, isGroup: Boolean) {
        val last = h.last()
        val k = WaScreen.key(title)
        synchronized(notifKey) { notifKey[k] = key; chatHist[k] = h }
        when (val r = WaChatMode.route(s, title, last.second, last.first.isEmpty(), isGroup, System.currentTimeMillis())) {
            is WaChatMode.Route.Skip -> if (r.why == "time over") { chat = null; DKLog.d("WhatsApp चैटिंग मोड: समय पूरा, बंद") }
            is WaChatMode.Route.AskPick -> tell(WaChatMode.pickNote(r.people))
            is WaChatMode.Route.AskBoss -> tell(WaChatMode.askNote(r.msg, r.sensitive))
            is WaChatMode.Route.TellNew -> tell(WaChatMode.tellNote(r.msg))
            is WaChatMode.Route.Auto -> autoReply(c, s, k)
        }
    }

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
        if (session == null && chat == null) return
        if (!pkg.startsWith(Platform.WHATSAPP.pkg)) return
        val h = history(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        if (h.isEmpty()) return
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        chat?.let { if (canReply) onChat(c, it, key, title, h, isGroup); return }
        val s = session ?: return
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
