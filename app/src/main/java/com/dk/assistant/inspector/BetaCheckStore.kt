package com.dk.assistant.inspector

import android.content.Context
import com.dk.assistant.BuildConfig
import com.dk.assistant.DKLog
import com.dk.assistant.ReleaseNotes
import com.dk.assistant.Updater

/**
 * नवंबर 2026: [BetaCheck] का फ़ोन वाला हिस्सा - इस version की गिनती SharedPreferences ("dk_beta_check") में।
 * हर चीज़ try/catch में - बीटा जाँच कभी Inspector या DK Live को नहीं रोक सकती।
 */
object BetaCheckStore {

    private const val PREF = "dk_beta_check"
    private const val K_SESSION = "session"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** APK में छपा चैनल (GitHub build.yml का CHANNEL); अपने कंप्यूटर वाला build = लोकल */
    fun channel(): BetaCheck.Channel = BetaCheck.Channel.of(BuildConfig.DK_CHANNEL)

    fun channelName(): String = channel().name.lowercase()

    private fun installedAt(c: Context): Long = try {
        c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime
    } catch (e: Exception) { System.currentTimeMillis() }

    fun session(c: Context): BetaCheck.Session {
        val old = BetaCheck.fromJson(sp(c).getString(K_SESSION, null))
        val s = BetaCheck.ensure(old, Updater.currentName(c), Updater.currentCode(c).toLong(), channel(), installedAt(c))
        if (s != old) save(c, s)
        return s
    }

    private fun save(c: Context, s: BetaCheck.Session) { sp(c).edit().putString(K_SESSION, BetaCheck.toJson(s)).apply() }

    /** Inspector.inspect के merge के बाद */
    fun record(c: Context, m: InspectorCore.Merge, now: Long, appVersion: String) {
        try {
            val touched = m.incidents.filter { it.lastSeen == now }
            if (touched.isEmpty()) return
            val s = BetaCheck.record(session(c), touched, m.reopened.map { it.fingerprint }.toSet(), appVersion, now)
            save(c, s)
        } catch (e: Throwable) { DKLog.d("बीटा जाँच: गिनती नहीं जुड़ी (${e.javaClass.simpleName})") }
    }

    fun toggle(c: Context, index: Int, on: Boolean) {
        try { save(c, BetaCheck.toggle(session(c), index, on)) } catch (e: Throwable) { DKLog.d("बीटा जाँच: टिक नहीं बचा") }
    }

    fun checklist(c: Context): List<String> = try {
        val code = Updater.currentCode(c)
        val raw = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getString("upd_notes_$code", "").orEmpty()
        BetaCheck.checklist(ReleaseNotes.clean(raw))
    } catch (e: Throwable) { BetaCheck.checklist(null) }
}
