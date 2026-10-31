package com.dk.assistant.inspector

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.dk.assistant.CoderTeam
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.GeminiLive
import com.dk.assistant.LatencyBoard
import com.dk.assistant.LiveReport
import com.dk.assistant.Mode
import com.dk.assistant.Setup
import com.dk.assistant.inspector.InspectorCore.Incident
import com.dk.assistant.inspector.InspectorCore.Status
import org.json.JSONObject

/**
 * DK Inspector का फ़ोन वाला हिस्सा।
 *  - पढ़ता है: DKLog.all(), GeminiLive.stats, LatencyBoard.all(), माइक परमिशन - सिर्फ़ पढ़ना, कुछ नहीं बदलता।
 *  - रखता है: SharedPreferences में bug की छोटी रिपोर्ट (≤8 छिपाई हुई लाइनें), पूरा लॉग नहीं।
 *  - हर चीज़ try/catch में - Inspector कभी DK Live को crash नहीं कर सकता।
 *  - app का source code कभी नहीं बदलता; Coding Agent को सिर्फ़ मालिक की मंज़ूरी के बाद रिपोर्ट जाती है।
 */
object Inspector {

    private const val PREF = "dk_inspector"
    private const val K_INC = "incidents"
    private const val K_SEEN = "seen"
    private const val K_LAST = "last"
    private const val K_NEXT = "next"
    private const val K_RUNS = "runs"
    private const val K_LAST_SIG = "lastSig"
    private const val ALARM_ID = 7311
    const val HOUR_MS = 3600_000L

    @Volatile var running = false; private set
    @Volatile var step = ""; private set

    data class Result(val ok: Boolean, val fresh: Int, val reopened: Int, val open: Int, val text: String, val changed: Boolean)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun incidents(c: Context): List<Incident> = try { InspectorCore.listFromJson(sp(c).getString(K_INC, null)) } catch (e: Exception) { emptyList() }
    private fun save(c: Context, xs: List<Incident>) { sp(c).edit().putString(K_INC, InspectorCore.listToJson(xs)).apply() }

    fun lastRun(c: Context): Long = sp(c).getLong(K_LAST, 0)
    fun nextRun(c: Context): Long = sp(c).getLong(K_NEXT, 0)
    fun runs(c: Context): Int = sp(c).getInt(K_RUNS, 0)

    private fun appVersion(c: Context): String = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        (pi.versionName ?: "") + "/" + (if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
    } catch (e: Exception) { "?" }

    /** अभी का असली डेटा (सिर्फ़ पढ़ना) */
    fun snapshot(c: Context): InspectorCore.Snapshot {
        val events = try { InspectorCore.parseLog(DKLog.all()) } catch (e: Exception) { emptyList() }
        val stats = try { InspectorCore.parseStats(GeminiLive.stats) } catch (e: Exception) { null }
        val mic = try { Setup.hasMic(c) } catch (e: Exception) { null }
        val lat = try {
            InspectorCore.latency(LatencyBoard.all().map { InspectorCore.Lat(it.engine, it.firstPiece, it.firstVoice, it.play) },
                InspectorCore.parseBreakdowns(events))
        } catch (e: Exception) { null }
        return InspectorCore.Snapshot(incidents(c), lat, stats, mic, events, lastRun(c))
    }

    /**
     * एक जाँच। [auto]=true: हर घंटे वाली (कुछ नहीं बदला तो रिपोर्ट दोबारा नहीं बनती)।
     * [onStep] = UI पर प्रगति। कभी exception बाहर नहीं फेंकता।
     */
    @Synchronized
    fun inspect(c: Context, auto: Boolean, onStep: ((Int, String) -> Unit)? = null): Result {
        if (running) return Result(false, 0, 0, 0, "जाँच पहले से चल रही है", false)
        running = true
        fun p(n: Int, t: String) { step = t; try { onStep?.invoke(n, t) } catch (_: Exception) {} }
        try {
            val ctx = c.applicationContext
            val now = System.currentTimeMillis()
            p(10, "Gemini Live हाल पढ़ रहा हूँ…")
            val stats = try { InspectorCore.parseStats(GeminiLive.stats) } catch (e: Exception) { InspectorCore.LiveStats("", emptyMap()) }
            val mic = try { Setup.hasMic(ctx) } catch (e: Exception) { null }
            val key = try { GeminiLive.available(ctx) } catch (e: Exception) { null }
            p(35, "लॉग पढ़ रहा हूँ (क्रम और समय वैसे ही)…")
            val events = try { InspectorCore.parseLog(DKLog.all()) } catch (e: Exception) { emptyList() }
            val seen = sp(ctx).getString(K_SEEN, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
            p(55, "देरी के हिस्से जाँच रहा हूँ…")
            val lat = try {
                InspectorCore.latency(LatencyBoard.all().map { InspectorCore.Lat(it.engine, it.firstPiece, it.firstVoice, it.play) },
                    InspectorCore.parseBreakdowns(events))
            } catch (e: Exception) { null }
            p(70, "गड़बड़ियाँ ढूँढ रहा हूँ, वजह और असर जोड़ रहा हूँ…")
            val found = InspectorCore.analyze(events, seen) + InspectorCore.liveFindings(stats, mic, key) + listOfNotNull(lat?.warning)
            p(85, "पुरानी रिपोर्टों से मिला रहा हूँ (दोहराव नहीं)…")
            val old = incidents(ctx)
            val m = InspectorCore.merge(old, found, now, appVersion(ctx))
            // कुछ नहीं बदला (हर घंटे वाली जाँच) = वही रिपोर्ट दोबारा नहीं लिखनी
            val sig = InspectorCore.hash(m.incidents.joinToString("|") { it.id + it.status + it.count + it.severity })
            val changed = sig != sp(ctx).getString(K_LAST_SIG, "")
            sp(ctx).edit().apply {
                // quietChecks (हल की पुष्टि) हर बार बचाने हैं; रिपोर्ट का text वही रहता है, नया नहीं बनता
                putString(K_INC, InspectorCore.listToJson(m.incidents))
                putString(K_SEEN, events.map { InspectorCore.lineKey(it) }.takeLast(400).joinToString(","))
                putLong(K_LAST, now); putInt(K_RUNS, runs(ctx) + 1); putString(K_LAST_SIG, sig)
                if (auto) putLong(K_NEXT, now + HOUR_MS)
            }.apply()
            p(95, "ज़रूरी बातें DK Live को भेज रहा हूँ…")
            deliverPending(ctx)
            val open = m.incidents.count { it.status != Status.RESOLVED }
            val text = "जाँच पूरी: ${events.size} लॉग लाइनें, ${found.size} नतीजे • नए ${m.fresh.size} • फिर से आए ${m.reopened.size} • खुले कुल $open"
            p(100, text)
            DKLog.d("DK Inspector: " + (if (auto) "हर घंटे की" else "गहरी") + " जाँच पूरी - नए ${m.fresh.size}, खुले $open")
            return Result(true, m.fresh.size, m.reopened.size, open, text, changed)
        } catch (e: Throwable) {
            p(100, "जाँच पूरी नहीं हो सकी")
            return Result(false, 0, 0, 0, "जाँच पूरी नहीं हो सकी: ${InspectorCore.redact(e.javaClass.simpleName)}", false)
        } finally { running = false }
    }

    /** ज़रूरी नई गड़बड़ी: DK Live चालू और खाली हो तो आसान हिंदी में बताए; एक गड़बड़ी एक ही बार */
    fun deliverPending(c: Context) {
        try {
            if (!DKState.live) return
            if (DKState.mode == Mode.SPEAKING || DKState.mode == Mode.THINKING) return
            val xs = incidents(c)
            val pick = xs.filter { InspectorCore.shouldNotify(it) }.maxByOrNull { it.severity.rank } ?: return
            val now = System.currentTimeMillis()
            LiveReport.note(InspectorCore.liveSummary(pick) + " Explain this to the owner now in simple Hindi (क्या हुआ, क्यों, किस फ़ीचर पर असर, कैसे ठीक होगा). " +
                "Then ask: \"सर, क्या मैं पूरी रिपोर्ट Coding Agent को दे दूँ?\" Only if he clearly says yes (e.g. 'ठीक है, Coding Agent को दे दो') " +
                "call dk_inspector(action=send_to_coder, id=${pick.id}, approved=true). For follow-up questions use dk_inspector(action=report, id=${pick.id}).")
            save(c, xs.map {
                if (it.id != pick.id) it else it.copy(notifiedAt = now, notifiedSeverity = it.severity.rank,
                    status = if (it.status == Status.FIXING) it.status else Status.AWAITING_APPROVAL)
            })
        } catch (_: Throwable) {}
    }

    fun setStatus(c: Context, id: String, st: Status, fixVersion: String? = null) {
        try { save(c, incidents(c).map { if (it.id != id) it else it.copy(status = st, fixVersion = fixVersion ?: it.fixVersion, quietChecks = 0) }) } catch (_: Throwable) {}
    }

    fun clearResolved(c: Context) { try { save(c, incidents(c).filter { it.status != Status.RESOLVED }) } catch (_: Throwable) {} }

    /**
     * मंज़ूरी के बाद ही Coding Agent। [approved] = मालिक ने साफ़ हाँ कहा ("ठीक है, Coding Agent को दे दो")।
     * Inspector खुद कोड नहीं बदलता; Coding Agent अपनी समझ → मालिक की हाँ → debugger से वजह पक्की करता है।
     */
    fun sendToCoder(c: Context, id: String, approved: Boolean): String {
        return try {
            val i = incidents(c).firstOrNull { it.id.equals(id, true) } ?: return "No DK Inspector report with id $id. Call dk_inspector(action=list)."
            if (!approved) return "NOT SENT. Ask the owner first: \"सर, क्या मैं ${i.id} की पूरी रिपोर्ट Coding Agent को दे दूँ?\" and call again with approved=true only after his clear yes."
            if (i.status == Status.FIXING) return "Already with the Coding Agent (${i.id}). Tell the owner it is being fixed; it will be marked Resolved only after verification."
            val err = CoderTeam.start(c, InspectorCore.coderTask(i), fromVoice = true)
            if (err != null) return "Coding Agent could not start: $err"
            setStatus(c, i.id, Status.FIXING, fixVersion = appVersion(c))
            "SENT ${i.id} to the Coding Agent. Tell the owner: रिपोर्ट Coding Agent को दे दी है; वह पहले खुद वजह पक्की करेगा, फिर ठीक करके test और build करेगा। Merge आपकी मंज़ूरी के बिना नहीं होगा। हल तभी माना जाएगा जब नए version में जाँच से गड़बड़ी दोबारा न दिखे।"
        } catch (e: Throwable) { "Coding Agent को नहीं भेज पाया: ${e.javaClass.simpleName}" }
    }

    /** DK Live का dk_inspector tool */
    fun liveTool(c: Context, a: JSONObject): String = try {
        when (a.optString("action", "list")) {
            "inspect" -> inspect(c, auto = false).text + "\n" + listText(c)
            "report" -> incidents(c).firstOrNull { it.id.equals(a.optString("id"), true) }?.let {
                if (it.status == Status.NEW || it.status == Status.INVESTIGATING || it.status == Status.REOPENED) setStatus(c, it.id, Status.AWAITING_APPROVAL)
                "Explain this in simple Hindi. Do not invent anything beyond it; suspected causes stay 'शायद'.\n" + InspectorCore.fullReport(it)
            } ?: "No report with that id. " + listText(c)
            "ask" -> "Answer the owner from this real DK Inspector data only (Hindi):\n" + InspectorCore.answer(a.optString("question"), snapshot(c))
            "send_to_coder" -> sendToCoder(c, a.optString("id"), a.optBoolean("approved", false))
            else -> listText(c)
        }
    } catch (e: Throwable) { "DK Inspector अभी नहीं चल पाया (${e.javaClass.simpleName})। Settings → Debug → DK Inspector देखें।" }

    fun listText(c: Context): String {
        val xs = incidents(c)
        if (xs.isEmpty()) return "DK Inspector: अभी कोई गड़बड़ी दर्ज नहीं" + (if (lastRun(c) == 0L) " (अभी तक जाँच नहीं हुई - action=inspect)" else "")
        return "DK Inspector reports:\n" + xs.take(10).joinToString("\n") { "${it.id} [${it.severity.name}/${it.status.name}] ${it.title} (${it.count} बार)" }
    }

    // ------------------------------------------------------------------ हर घंटे (best-effort, बैटरी-हल्का)

    /** AlarmManager inexact + ELAPSED_REALTIME (फ़ोन को जगाता नहीं) - वही तरीका जो LocalMemory.scheduleDaily में है */
    fun schedule(c: Context) {
        try {
            val ctx = c.applicationContext
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(ctx, ALARM_ID, Intent(ctx, InspectorReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR, pi)
            if (nextRun(ctx) < System.currentTimeMillis()) sp(ctx).edit().putLong(K_NEXT, System.currentTimeMillis() + HOUR_MS).apply()
        } catch (_: Throwable) {}
    }
}

/** हर घंटे वाली जाँच - अलग thread पर, ताकि DK Live/मुख्य thread न रुके */
class InspectorReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = goAsync()
        Thread {
            try { Inspector.inspect(c, auto = true) } catch (_: Throwable) {} finally { try { p.finish() } catch (_: Throwable) {} }
        }.start()
    }
}
