package com.dk.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File

/** 16 नवंबर 2026: क्रैश रिपोर्ट का Android हिस्सा - सिर्फ़ मालिक के दबाने पर ([CrashReport]) */
object CrashUi {
    private fun body(c: Context, crash: String) =
        CrashReport.body(crash, Updater.currentName(c), Build.VERSION.SDK_INT, Build.MANUFACTURER + " " + Build.MODEL)

    fun openIssue(c: Context, crash: String) {
        try {
            val url = CrashReport.issueUrl(Updater.REPO, CrashReport.title(crash), body(c, crash))
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { DKBug.report("crash", e, "crash report issue खोलना") }
    }

    fun share(c: Context, crash: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body(c, crash))
            c.startActivity(Intent.createChooser(i, "क्रैश रिपोर्ट भेजें").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { DKBug.report("crash", e, "crash report शेयर") }
    }

    /** ऐप खुलते समय: मालिक ने "पूछो" चालू किया हो और नया crash हो तो एक बार पूछो */
    fun maybeAsk(a: Activity) {
        try {
            val f = File(a.filesDir, "last_crash.txt")
            val at = if (f.exists()) f.lastModified() else 0L
            if (!CrashReport.shouldAsk(Prefs.crashAsk(a), at, Prefs.crashAskedAt(a))) return
            Prefs.setCrashAskedAt(a, at)
            val crash = DKApp.crashHistory(a).ifBlank { DKApp.lastCrash(a) }
            android.app.AlertDialog.Builder(a)
                .setTitle("DK पिछली बार गड़बड़ी से बंद हुआ")
                .setMessage("क्रैश रिपोर्ट भेजें? (key / token / नंबर / OTP छिपाकर; GitHub पर पढ़कर आप खुद Submit करेंगे)")
                .setPositiveButton("भेजें") { _, _ -> openIssue(a, crash) }
                .setNeutralButton("शेयर") { _, _ -> share(a, crash) }
                .setNegativeButton("नहीं", null)
                .show()
        } catch (e: Exception) { DKBug.report("crash", e, "crash report पूछना") }
    }
}
