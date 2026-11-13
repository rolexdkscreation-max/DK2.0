package com.dk.assistant.autoreply

import android.app.Notification
import android.content.Context
import android.os.Bundle
import com.dk.assistant.BrainManager
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKNotifListener
import com.dk.assistant.DKState
import com.dk.assistant.agent.android.WaAutoChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): ऑटो रिप्लाई - Android हिस्सा (सेटिंग, फ़ोन में कौन-से मैसेज ऐप हैं, notification आते ही जवाब).
 * फ़ैसले और AI के नियम [AutoReplyCore] में. जवाब notification के "जवाब दें" बटन से जाता है - स्क्रीन नहीं छीनता, कोई ऐप नहीं खुलता,
 * इसलिए DK के बाकी काम (फ़ॉर्म भरना, WhatsApp चैटिंग मोड...) से टकराव नहीं. WhatsApp का "अपने-आप बात" / चैटिंग मोड चालू हो
 * तो WhatsApp वही सँभालता है, यह नहीं (दो जवाब कभी नहीं).
 */
object AutoReply {
    private const val PREFS = "dk_auto_reply"
    private const val K_ON = "on"
    private const val K_STYLE = "style"
    private const val K_SEEN = "seen_apps"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gen = HashMap<String, Int>()
    private val chats = HashMap<String, AutoReplyCore.ChatState>()
    /** DK के अपने भेजे जवाब - इन्हें मालिक का ढंग समझकर नहीं सीखना */
    private val dkSent = ArrayDeque<String>()

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun on(c: Context): Boolean = sp(c).getBoolean(K_ON, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_ON, v).apply(); DKLog.d("ऑटो रिप्लाई: " + if (v) "चालू" else "बंद") }

    /** हर ऐप का अपना स्विच - शुरू में बंद */
    fun appOn(c: Context, pkg: String): Boolean = sp(c).getBoolean("app_$pkg", false)
    fun setApp(c: Context, pkg: String, v: Boolean) { sp(c).edit().putBoolean("app_$pkg", v).apply() }

    fun installed(c: Context, pkg: String): Boolean = try {
        c.packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (_: Exception) { false }

    /** फ़ोन में जो मैसेज वाले ऐप हैं (जाने-माने + DK ने notification से पहचाने) */
    fun apps(c: Context): List<AutoReplyCore.App> = AutoReplyCore.available({ installed(c, it) }, seen(c), c.packageName)

    fun onCount(c: Context): Int = apps(c).count { appOn(c, it.pkg) }

    private fun seen(c: Context): List<AutoReplyCore.App> = try {
        val a = JSONArray(sp(c).getString(K_SEEN, "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { AutoReplyCore.App(it.optString("p"), it.optString("n")) } }
    } catch (_: Exception) { emptyList() }

    private fun remember(c: Context, pkg: String) {
        if (AutoReplyCore.known(pkg) != null || seen(c).any { it.pkg == pkg }) return
        val name = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { return }
        val list = (seen(c) + AutoReplyCore.App(pkg, name)).takeLast(30)
        sp(c).edit().putString(K_SEEN, JSONArray().apply { list.forEach { put(JSONObject().put("p", it.pkg).put("n", it.name)) } }.toString()).apply()
    }

    fun style(c: Context): List<String> = try {
        val a = JSONArray(sp(c).getString(K_STYLE, "[]")); (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    /** सेटिंग से: मालिक ने खुद अपने जैसे मैसेज लिखे (हर लाइन एक) - लौटाता है कुल नमूने */
    fun addSamples(c: Context, lines: String): Int {
        learn(c, lines.lines().map { it.trim() }.filter { it.isNotEmpty() })
        return style(c).size
    }

    fun clearStyle(c: Context) { sp(c).edit().remove(K_STYLE).apply() }

    private fun learn(c: Context, mine: List<String>) {
        if (mine.isEmpty()) return
        val old = style(c)
        val next = AutoReplyCore.addStyle(old, mine)
        if (next != old) sp(c).edit().putString(K_STYLE, JSONArray(next).toString()).apply()
    }

    /** 13 नवंबर 2026: हर बातचीत का सबसे नया notification (जवाब हमेशा सबसे नए हाल पर, पुराने पर नहीं) */
    private data class Snap(val key: String, val title: String, val msgs: List<AutoReplyCore.In>, val self: String)
    private val latest = HashMap<String, Snap>()
    /** एक बातचीत में एक समय पर एक ही जवाब बनता/जाता है - दो साथ-साथ कभी नहीं (डबल मैसेज की जड़) */
    private val locks = HashMap<String, Mutex>()
    private fun lock(k: String) = synchronized(locks) { locks.getOrPut(k) { Mutex() } }

    /** जिस मैसेज का आख़िरी जवाब दिया - फ़ोन में सहेजा (DK बंद-चालू हो / notification फिर आए तब भी दोबारा जवाब नहीं) */
    private fun answered(c: Context, k: String): String = sp(c).getString("ans_$k", "").orEmpty()
    private fun setAnswered(c: Context, k: String, fp: String) { sp(c).edit().putString("ans_$k", fp).apply() }

    /** notification के MessagingStyle मैसेज: भेजने वाला, text, समय */
    fun parse(arr: Array<android.os.Parcelable>?): List<AutoReplyCore.In> = arr.orEmpty().mapNotNull { p ->
        val b = p as? Bundle ?: return@mapNotNull null
        val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return@mapNotNull null
        val sender = b.getCharSequence("sender")?.toString()
            ?: (if (android.os.Build.VERSION.SDK_INT >= 28) (b.get("sender_person") as? android.app.Person)?.name?.toString() else null)
        val who = sender.orEmpty().trim()
        AutoReplyCore.In(who, text, b.getLong("time", 0L), who.isEmpty())
    }

    private fun selfName(extras: Bundle): String =
        extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
            ?: (if (android.os.Build.VERSION.SDK_INT >= 28) (extras.get(Notification.EXTRA_MESSAGING_PERSON) as? android.app.Person)?.name?.toString() else null)
            ?: ""

    private fun sentList(): List<String> = synchronized(dkSent) { dkSent.toList() }

    /** DKNotifListener से: नया notification आया (हर बार - बंद हो तो तुरंत लौटता है) */
    fun onPosted(c: Context, nKey: String, pkg: String, title: String, extras: Bundle, canReply: Boolean) {
        try {
            if (pkg == c.packageName) return
            val h = WaAutoChat.history(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
            if (canReply && AutoReplyCore.looksLikeMessenger(h.isNotEmpty(), canReply)) remember(c, pkg)
            val self = selfName(extras)
            val parsed = AutoReplyCore.markMine(parse(extras.getParcelableArray(Notification.EXTRA_MESSAGES)), self, emptyList())
            // ढंग सीखना: मालिक के अपने भेजे मैसेज (बंद हो तब भी - ताकि चालू करते ही उसी ढंग में लिखे). DK के अपने भेजे जवाब नहीं.
            val sentNow = sentList().map { it.trim() }.toHashSet()
            val mine = parsed.filter { it.mine }.map { it.text }.filter { m -> m.trim() !in sentNow }
            learn(c, mine)
            if (!on(c)) return
            val raw = parsed.ifEmpty {
                val t = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                if (t.isBlank()) emptyList() else listOf(AutoReplyCore.In(title, t.trim(), 0L, false))
            }
            if (raw.isEmpty()) return
            val msgs = AutoReplyCore.markMine(raw, self, sentList())
            val k = AutoReplyCore.chatKey(pkg, title)
            val pending = AutoReplyCore.unanswered(msgs)
            AutoReplyCore.pendingSkip(pending, answered(c, k))?.let { return }
            val last = pending.last()
            val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            val busy = pkg.startsWith("com.whatsapp") && WaAutoChat.active()
            val st = synchronized(chats) { chats[k]?.copy(lastAnswered = "") }     // वही मैसेज = ऊपर pendingSkip (समय + text) से
            val now = System.currentTimeMillis()
            val app = AutoReplyCore.known(pkg)?.name ?: try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            var wait = AutoReplyCore.SETTLE_MS
            when (val d = AutoReplyCore.decide(true, appOn(c, pkg), c.packageName, pkg, last.text, false, isGroup, canReply, busy, st, now)) {
                is AutoReplyCore.Decision.Skip -> {
                    // अभी-अभी जवाब दिया था और उन्होंने फिर लिखा = छोड़ना नहीं, थोड़ा रुककर सब का एक ही जवाब
                    if (d.why != "too soon" || st == null) return
                    wait += (AutoReplyCore.GAP_MS - (now - st.lastAt)).coerceAtLeast(0L)
                }
                AutoReplyCore.Decision.AskOwner -> {
                    setAnswered(c, k, AutoReplyCore.fp(last))       // एक बार बताया - वही बात दोबारा नहीं
                    DKLog.d("ऑटो रिप्लाई ($app): पैसे/OTP जैसी बात - खुद जवाब नहीं, आपको बताया")
                    tell(AutoReplyCore.askNote(app, title, last.text))
                    return
                }
                AutoReplyCore.Decision.Reply -> Unit
            }
            synchronized(latest) { latest[k] = Snap(nKey, title, raw, self) }
            val my = synchronized(gen) { (gen[k] ?: 0).plus(1).also { gen[k] = it } }
            scope.launch {
                delay(wait)          // और मैसेज आ रहे हों तो रुककर एक ही जवाब
                if (synchronized(gen) { gen[k] } != my || !on(c) || !appOn(c, pkg)) return@launch
                lock(k).withLock {
                    // सबसे नया हाल दोबारा देखो: बीच में जवाब जा चुका हो (या DK का अपना जवाब आया हो) तो कुछ नहीं
                    val snap = synchronized(latest) { latest[k] } ?: return@withLock
                    val now2 = AutoReplyCore.markMine(snap.msgs, snap.self, sentList())
                    val ans = answered(c, k)
                    val pend = AutoReplyCore.unanswered(now2)
                    if (AutoReplyCore.pendingSkip(pend, ans) != null) return@withLock
                    val fresh = AutoReplyCore.afterAnswered(pend, ans).ifEmpty { pend }
                    val text = AutoReplyCore.clean(BrainManager(c).quickText(AutoReplyCore.system(app, style(c)), AutoReplyCore.promptAll(snap.title, now2, fresh)))
                    if (text == null || AutoReplyCore.unsafe(text)) {
                        setAnswered(c, k, AutoReplyCore.fp(pend.last()))      // "ok/👍" जैसा - इस पर फिर कोशिश नहीं
                        DKLog.d("ऑटो रिप्लाई ($app): जवाब नहीं भेजा (ज़रूरत नहीं / बना नहीं / निजी जानकारी)"); return@withLock
                    }
                    if (synchronized(gen) { gen[k] } != my) return@withLock       // बीच में नया मैसेज - अगला काम सब का एक जवाब देगा
                    // वही text अभी-अभी भेजा था? (AI ने दोहराया) - दोबारा नहीं
                    if (now2.lastOrNull { it.mine }?.text?.trim()?.equals(text.trim(), true) == true) { setAnswered(c, k, AutoReplyCore.fp(pend.last())); return@withLock }
                    synchronized(dkSent) { dkSent.addLast(text.trim()); while (dkSent.size > 60) dkSent.removeFirst() }
                    setAnswered(c, k, AutoReplyCore.fp(pend.last()))              // भेजने से पहले - notification तुरंत फिर आए तब भी दूसरा नहीं
                    val key = snap.key                                             // इस बातचीत का सबसे नया notification
                    val err = DKNotifListener.reply(c, key, text)
                    if (err == null) {
                        synchronized(chats) { AutoReplyCore.sent(chats.getOrPut(k) { AutoReplyCore.ChatState() }, pend.last().text, System.currentTimeMillis()) }
                        DKLog.d("ऑटो रिप्लाई ($app): ${snap.title.take(24)} को एक जवाब भेजा")
                        tell(AutoReplyCore.sentNote(app, snap.title, fresh.joinToString(" / ") { it.text }, text))
                    } else {
                        setAnswered(c, k, ans)                                     // नहीं गया - अगली बार फिर कोशिश हो सके
                        DKLog.d("ऑटो रिप्लाई ($app): जवाब नहीं गया - $err")
                    }
                }
            }
        } catch (_: Exception) {}
    }


    private fun tell(note: String) {
        if (DKState.live) DKAccessibilityService.instance?.gemLive?.pushNote(note)
    }
}
