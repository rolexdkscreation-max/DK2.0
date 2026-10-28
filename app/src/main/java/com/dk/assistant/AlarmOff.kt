package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.dk.assistant.template.TemplateTime
import org.json.JSONObject

/**
 * "अलार्म बंद करो" - बजता अलार्म रोकना / लगा हुआ अलार्म हटाना / स्नूज़.
 *
 * पहले: DK अलार्म लगा देता था, पर बंद करने का कोई सीधा तरीका नहीं था (बात AI को जाती, वह घड़ी की स्क्रीन पर अटकता).
 * अब (बिना AI, तुरंत):
 *  1. अलार्म बज रहा हो -> घड़ी ऐप के notification का "बंद करें / Dismiss / Stop" (या "Snooze") बटन दबाओ.
 *  2. नहीं बज रहा / बटन नहीं मिला -> Android का अपना तरीका (AlarmClock.ACTION_DISMISS_ALARM): अगला / उस समय का / सारे.
 * parse() और pick() Android-free हैं - JUnit से जाँचे जाते हैं.
 */
object AlarmOff {
    enum class Mode { NEXT, ALL, TIME }
    data class Cmd(val snooze: Boolean, val mode: Mode, val hour: Int = -1, val minute: Int = 0)

    private val ALARM = Regex("(अलार्म|अलार्म्स|अलार्मों|alarm|alarms|अलारम|आलम)")
    private val OFF = Regex("(बंद|ऑफ|off|हटा|हटाओ|डिसमिस|dismiss|स्टॉप|stop|रोको|रोक दो|कैंसल|cancel|डिलीट|delete|मिटा|बजना बंद|चुप)")
    private val SNOOZE = Regex("(स्नूज़|स्नूज|snooze|थोड़ी देर बाद|थोड़ी देर में|बाद में बजा|5 मिनट बाद|पांच मिनट बाद|पाँच मिनट बाद)")
    private val ALL = Regex("(सारे|सभी|सब|all|every|पूरे)")

    /** null = यह अलार्म बंद/स्नूज़ की बात नहीं */
    fun parse(raw: String, nowH: Int = 0, nowM: Int = 0): Cmd? {
        val t = raw.lowercase().trim().trim('.', '।', '!', '?', ',')
        if (!ALARM.containsMatchIn(t)) return null
        // "आलम" (बोलने में) सिर्फ़ तब, जब साथ में बंद/स्नूज़ हो
        val snooze = SNOOZE.containsMatchIn(t)
        if (!snooze && !OFF.containsMatchIn(t)) return null
        if (Regex("(मत|नहीं|नही|don't|do not|mat )").containsMatchIn(t)) return null
        if (snooze) return Cmd(true, Mode.NEXT)
        if (ALL.containsMatchIn(t)) return Cmd(false, Mode.ALL)
        // "7 बजे का अलार्म बंद करो" / "सुबह साढ़े छह वाला अलार्म हटाओ"
        ALARM.find(t)?.let { m ->
            var clock = t.substring(0, m.range.first).trim()
            repeat(3) { clock = clock.replace(Regex("\\s*(?:का|के|की|वाला|वाले|वाली|मेरा|मेरे)$"), "").trim() }
            if (Regex("\\d|बजे|baje|सुबह|शाम|रात|दोपहर|साढ़े|साढे|सवा|पौने").containsMatchIn(clock))
                TemplateTime.parseClock(clock, nowH, nowM)?.let { return Cmd(false, Mode.TIME, it.hour, it.minute) }
        }
        return Cmd(false, Mode.NEXT)
    }

    /** घड़ी ऐप का notification? (Google, Samsung, Xiaomi, OnePlus, Oppo, Vivo, Realme...) */
    fun isClockPkg(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("deskclock") || p.contains("clock") || p.contains("alarm")
    }

    private val DISMISS_W = listOf("dismiss", "stop", "turn off", "बंद", "ख़ारिज", "खारिज", "रोकें", "रोको", "हटाएं", "हटाएँ", "off")
    private val SNOOZE_W = listOf("snooze", "स्नूज़", "स्नूज")

    /** notification के बटनों में से कौन दबाना है (index) - नहीं मिला = -1 */
    fun pick(titles: List<String>, snooze: Boolean): Int {
        val w = if (snooze) SNOOZE_W else DISMISS_W
        titles.forEachIndexed { i, t -> val l = t.lowercase(); if (w.any { l.contains(it) } && (snooze || SNOOZE_W.none { l.contains(it) })) return i }
        return -1
    }

    fun ack(c: Cmd): String = when {
        c.snooze -> "अलार्म स्नूज़ कर दिया"
        c.mode == Mode.ALL -> "सारे अलार्म बंद कर दिए"
        c.mode == Mode.TIME -> "%d:%02d का अलार्म बंद कर दिया".format(c.hour, c.minute)
        else -> "अलार्म बंद कर दिया"
    }

    fun toJson(c: Cmd): JSONObject = JSONObject().put("type", "alarm_off").put("snooze", c.snooze).put("mode", c.mode.name)
        .put("hour", c.hour).put("minute", c.minute)

    fun fromJson(a: JSONObject) = Cmd(a.optBoolean("snooze"), runCatching { Mode.valueOf(a.optString("mode", "NEXT")) }.getOrDefault(Mode.NEXT),
        a.optInt("hour", -1), a.optInt("minute", 0))

    // ------------------------------------------------------------ Android हिस्सा
    /** 1) बजते अलार्म का notification बटन. true = दबा दिया */
    private fun pressRinging(cmd: Cmd): Boolean {
        val l = DKNotifListener.instance ?: return false
        val list = try { l.activeNotifications?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        for (sbn in list.filter { isClockPkg(it.packageName) }) {
            val acts = sbn.notification?.actions ?: continue
            val i = pick(acts.map { it.title?.toString().orEmpty() }, cmd.snooze)
            if (i < 0) continue
            try { acts[i].actionIntent?.send(); DKLog.d("Alarm: notification बटन '${acts[i].title}' दबाया (${sbn.packageName})"); return true }
            catch (e: Exception) { DKLog.d("Alarm: बटन नहीं दबा - ${e.message}") }
        }
        return false
    }

    /** 2) Android का अपना dismiss / snooze intent (घड़ी ऐप खुद संभालता है) */
    private fun intentOff(c: Context, cmd: Cmd): Boolean = try {
        val i = if (cmd.snooze) Intent(AlarmClock.ACTION_SNOOZE_ALARM)
        else Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            when (cmd.mode) {
                Mode.ALL -> putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
                Mode.TIME -> {
                    putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                    putExtra(AlarmClock.EXTRA_HOUR, cmd.hour); putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
                    putExtra(AlarmClock.EXTRA_IS_PM, cmd.hour >= 12)
                }
                Mode.NEXT -> putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(c.packageManager) == null) false else { c.startActivity(i); true }
    } catch (e: Exception) { DKLog.d("Alarm: dismiss intent नहीं चला - ${e.message}"); false }

    /** काम करो; नतीजा (सच): "OK: ..." या "ERROR: ..." */
    fun run(c: Context, cmd: Cmd): String {
        if (cmd.mode == Mode.NEXT && pressRinging(cmd)) return "OK: " + ack(cmd)
        if (intentOff(c, cmd)) return "OK: " + ack(cmd) + " (घड़ी ऐप से)"
        return if (DKNotifListener.instance == null) "ERROR: अलार्म बंद नहीं हुआ — DK को Notification access दें, या घड़ी ऐप में खुद बंद करें"
        else "ERROR: इस फ़ोन की घड़ी ऐप ने अलार्म बंद करने की बात नहीं मानी — घड़ी ऐप खोलकर बंद करें"
    }
}
