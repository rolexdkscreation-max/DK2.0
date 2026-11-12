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

    /** DKNotifListener से: नया notification आया (हर बार - बंद हो तो तुरंत लौटता है) */
    fun onPosted(c: Context, key: String, pkg: String, title: String, extras: Bundle, canReply: Boolean) {
        try {
            if (pkg == c.packageName) return
            val h = WaAutoChat.history(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
            if (canReply && AutoReplyCore.looksLikeMessenger(h.isNotEmpty(), canReply)) remember(c, pkg)
            // ढंग सीखना: मालिक के अपने भेजे मैसेज (बंद हो तब भी - ताकि चालू करते ही उसी ढंग में लिखे). DK के अपने भेजे जवाब नहीं.
            val mine = h.filter { it.first.isEmpty() }.map { it.second }.filter { m -> synchronized(dkSent) { m.trim() !in dkSent } }
            learn(c, mine)
            if (!on(c)) return
            val msgs = h.ifEmpty {
                val t = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                if (t.isBlank()) emptyList() else listOf(title to t)
            }
            if (msgs.isEmpty()) return
            val last = msgs.last()
            val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            val busy = pkg.startsWith("com.whatsapp") && WaAutoChat.active()
            val k = AutoReplyCore.key(pkg, title)
            val st = synchronized(chats) { chats[k] }
            val now = System.currentTimeMillis()
            val app = AutoReplyCore.known(pkg)?.name ?: try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            when (AutoReplyCore.decide(true, appOn(c, pkg), c.packageName, pkg, last.second, last.first.isEmpty(), isGroup, canReply, busy, st, now)) {
                is AutoReplyCore.Decision.Skip -> return
                AutoReplyCore.Decision.AskOwner -> {
                    DKLog.d("ऑटो रिप्लाई ($app): पैसे/OTP जैसी बात - खुद जवाब नहीं, आपको बताया")
                    tell(AutoReplyCore.askNote(app, title, last.second))
                    return
                }
                AutoReplyCore.Decision.Reply -> Unit
            }
            val my = synchronized(gen) { (gen[k] ?: 0).plus(1).also { gen[k] = it } }
            val incoming = last.second
            scope.launch {
                delay(AutoReplyCore.SETTLE_MS)          // और मैसेज आ रहे हों तो रुककर एक ही जवाब
                if (synchronized(gen) { gen[k] } != my || !on(c) || !appOn(c, pkg)) return@launch
                val conv = msgs.takeLast(14).map { AutoReplyCore.Msg(it.second, it.first.isEmpty()) }
                val text = AutoReplyCore.clean(BrainManager(c).quickText(AutoReplyCore.system(app, style(c)), AutoReplyCore.prompt(title, conv)))
                if (text == null || AutoReplyCore.unsafe(text)) { DKLog.d("ऑटो रिप्लाई ($app): जवाब नहीं भेजा (बना नहीं / निजी जानकारी)"); return@launch }
                if (synchronized(gen) { gen[k] } != my) return@launch
                synchronized(dkSent) { dkSent.addLast(text.trim()); while (dkSent.size > 60) dkSent.removeFirst() }
                val err = DKNotifListener.reply(c, key, text)
                if (err == null) {
                    synchronized(chats) { AutoReplyCore.sent(chats.getOrPut(k) { AutoReplyCore.ChatState() }, incoming, System.currentTimeMillis()) }
                    DKLog.d("ऑटो रिप्लाई ($app): ${title.take(24)} को जवाब भेजा")
                    tell(AutoReplyCore.sentNote(app, title, incoming, text))
                } else DKLog.d("ऑटो रिप्लाई ($app): जवाब नहीं गया - $err")
            }
        } catch (_: Exception) {}
    }


    private fun tell(note: String) {
        if (DKState.live) DKAccessibilityService.instance?.gemLive?.pushNote(note)
    }
}
