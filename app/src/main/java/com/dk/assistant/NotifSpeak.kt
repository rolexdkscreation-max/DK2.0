package com.dk.assistant

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 22 नवंबर 2026 (मालिक): "Live में notification आता है तो DK बोलकर बताता है - एक setting जोड़ो कि DK लाइव
 * जुड़ा न हो तब भी नया notification आए तो बोलकर बताए."
 *
 * सेटिंग → आवाज़ → "Notification बोलकर बताओ (DK लाइव बंद हो तब भी)". शुरू में बंद.
 * Live चालू हो तो कुछ नहीं (वहाँ Gemini खुद बताता है - [GeminiLive.pushNotif]); यह सिर्फ़ Live बंद होने पर,
 * फ़ोन की DK आवाज़ ([Speaker]) से. पढ़ने के नियम [NotifSpeakRules] में (JVM test).
 */
object NotifSpeakRules {
    const val PREF_ON = "notif_speak_on"
    /** सिर्फ़ मैसेज / चैट (WhatsApp, SMS, Telegram, Instagram DM…) - "Updating…" जैसे बेकार नहीं */
    const val PREF_MSG_ONLY = "notif_speak_msg_only"
    /** मैसेज के शब्द भी पढ़ो (बंद = सिर्फ़ "राहुल का WhatsApp आया है") */
    const val PREF_READ_TEXT = "notif_speak_text"
    /** फ़ोन साइलेंट / वाइब्रेट / Do Not Disturb = चुप */
    const val PREF_RESPECT_SILENT = "notif_speak_silent"
    /** रात 11 से सुबह 7 तक चुप */
    const val PREF_NIGHT_QUIET = "notif_speak_night"

    const val NIGHT_FROM = 23
    const val NIGHT_TO = 7
    /** कई notification साथ आएँ = एक बार में बताओ */
    const val BATCH_MS = 2_500L
    /** एक ही ऐप + भेजने वाला इतनी देर में दोबारा = दोबारा नहीं बोलना (लगातार मैसेज पर बार-बार नहीं) */
    const val SAME_GAP_MS = 30_000L
    /** DK पहले से बोल रहा / काम कर रहा = इतनी देर तक रुको, फिर छोड़ दो */
    const val WAIT_QUIET_MS = 20_000L
    const val MAX_TEXT = 120

    data class Item(val app: String, val title: String, val text: String)

    /** रात का चुप समय? (23..6 बजे) */
    fun night(hour: Int, from: Int = NIGHT_FROM, to: Int = NIGHT_TO): Boolean =
        if (from > to) hour >= from || hour < to else hour in from until to

    /**
     * बोलना है? null = हाँ; वरना कारण (log के लिए).
     * live = DK लाइव चालू (वहाँ Gemini बताता है), call = कॉल बज रही / चल रही.
     */
    fun skipWhy(on: Boolean, live: Boolean, call: Boolean, msgOnly: Boolean, isMsg: Boolean,
                silentPhone: Boolean, respectSilent: Boolean, nightNow: Boolean, nightQuiet: Boolean, hidden: Boolean): String? = when {
        !on -> "सेटिंग बंद"
        live -> "DK लाइव चालू - वहीं बताया जाता है"
        call -> "कॉल चल रही"
        hidden -> "DK Privacy में छिपा ऐप"
        msgOnly && !isMsg -> "मैसेज वाला नहीं"
        respectSilent && silentPhone -> "फ़ोन साइलेंट / DND"
        nightQuiet && nightNow -> "रात का चुप समय"
        else -> null
    }

    /** वही ऐप + भेजने वाला अभी-अभी बोला गया? */
    fun recentlySaid(app: String, title: String, now: Long, said: Map<String, Long>): Boolean =
        said["${app.lowercase()}|${title.lowercase()}"]?.let { now - it < SAME_GAP_MS } == true

    fun sayKey(app: String, title: String) = "${app.lowercase()}|${title.lowercase()}"

    private val OTP = Regex("(?i)\\b(otp|one.time|verification code|passcode)\\b|ओटीपी|\\b\\d{4,8}\\b.*(code|कोड)")

    /** OTP / पासवर्ड / निजी = शब्द कभी ज़ोर से नहीं */
    fun isPrivate(text: String): Boolean = Sensitive.isSensitive(text) || OTP.containsMatchIn(text)

    /** एक notification की बोलने वाली लाइन */
    fun line(i: Item, readText: Boolean): String {
        val app = i.app.ifBlank { "किसी ऐप" }
        val who = i.title.trim().takeIf { it.isNotBlank() && !it.equals(i.app, ignoreCase = true) }
        val head = if (who != null) "$who का $app पर मैसेज आया है" else "$app का notification आया है"
        val t = i.text.trim().replace(Regex("\\s+"), " ")
        return when {
            t.isBlank() || !readText -> "$head।"
            isPrivate(t) -> "$head — इसमें कोड या निजी बात है, इसलिए नहीं पढ़ रही।"
            else -> "$head — ${if (t.length > MAX_TEXT) t.take(MAX_TEXT).substringBeforeLast(' ') + "…" else t}"
        }
    }

    /** कई एक साथ = पहले 3 बताओ, बाकी गिनती */
    fun speech(items: List<Item>, readText: Boolean): String = when {
        items.isEmpty() -> ""
        items.size == 1 -> line(items[0], readText)
        else -> buildString {
            append("${items.size} नए notification। ")
            items.take(3).forEach { append(line(it, readText)).append(' ') }
            if (items.size > 3) append("और ${items.size - 3} बाकी हैं।")
        }.trim()
    }
}

object NotifSpeak {
    private val queue = ArrayList<NotifSpeakRules.Item>()
    @Volatile private var flushing = false
    private val said = HashMap<String, Long>()

    private fun b(c: Context, k: String, def: Boolean) = Prefs.bool(c, k, def)
    fun on(c: Context) = b(c, NotifSpeakRules.PREF_ON, false)
    fun msgOnly(c: Context) = b(c, NotifSpeakRules.PREF_MSG_ONLY, true)
    fun readText(c: Context) = b(c, NotifSpeakRules.PREF_READ_TEXT, true)
    fun respectSilent(c: Context) = b(c, NotifSpeakRules.PREF_RESPECT_SILENT, true)
    fun nightQuiet(c: Context) = b(c, NotifSpeakRules.PREF_NIGHT_QUIET, true)

    /** फ़ोन साइलेंट / वाइब्रेट / Do Not Disturb? */
    private fun silentPhone(c: Context): Boolean = try {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
    } catch (_: Exception) { false }

    /** [DKNotifListener] से: नया (पहले न देखा) notification. isMsg = मैसेज / चैट वाला */
    fun onPosted(c: Context, pkg: String, app: String, title: String, text: String, isMsg: Boolean) {
        if (!on(c)) return
        val svc = DKAccessibilityService.instance ?: return
        val call = try { svc.callBusy() } catch (_: Exception) { false }
        val hidden = try { PrivacyScreen.on(c) && PrivacyScreen.appOn(c, pkg) } catch (_: Exception) { false }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val why = NotifSpeakRules.skipWhy(true, DKState.live, call, msgOnly(c), isMsg,
            silentPhone(c), respectSilent(c), NotifSpeakRules.night(hour), nightQuiet(c), hidden)
        if (why != null) {
            if (why != "मैसेज वाला नहीं") {
                DKLog.d("🔔 बोलकर नहीं बताया ($why): [$app]")
            }
            return
        }
        val now = System.currentTimeMillis()
        synchronized(queue) {
            if (NotifSpeakRules.recentlySaid(app, title, now, said)) return
            said[NotifSpeakRules.sayKey(app, title)] = now
            if (said.size > 100) said.entries.removeAll { now - it.value > NotifSpeakRules.SAME_GAP_MS }
            queue.add(NotifSpeakRules.Item(app, title, text))
            if (flushing) return
            flushing = true
        }
        svc.scope.launch {
            try {
                delay(NotifSpeakRules.BATCH_MS)
                // DK बोल रहा / काम कर रहा = थोड़ा रुको (बीच में नहीं)
                val until = System.currentTimeMillis() + NotifSpeakRules.WAIT_QUIET_MS
                while (busy(svc) && System.currentTimeMillis() < until) delay(500)
                val items = synchronized(queue) { queue.toList().also { queue.clear() } }
                if (items.isEmpty() || busy(svc) || DKState.live || svc.callBusy()) {
                    if (items.isNotEmpty()) DKLog.d("🔔 बोलकर नहीं बताया (DK व्यस्त / Live / कॉल): ${items.size}")
                    return@launch
                }
                val say = NotifSpeakRules.speech(items, readText(c))
                DKLog.d("🔔 Live बंद - notification बोलकर बताया (${items.size})")
                svc.speaker.speak(say)
            } catch (e: Exception) { Quiet.note(e) } finally { synchronized(queue) { flushing = false } }
        }
    }

    private fun busy(svc: DKAccessibilityService): Boolean = try {
        svc.speaker.busy || svc.agent.isBusy || DKState.mode == Mode.SPEAKING || DKState.mode == Mode.THINKING
    } catch (_: Exception) { false }
}
