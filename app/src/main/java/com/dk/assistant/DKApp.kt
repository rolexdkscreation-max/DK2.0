package com.dk.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.io.File
import kotlinx.coroutines.launch

/**
 * Poore app ki shuruaat:
 *  - koi crash ho to uski report file me save (Settings → गड़बड़ी रिपोर्ट me dikhti hai)
 *  - DK ki apni screen khuli hai ya nahi (tab chhota orb bubble chhupa rehta hai)
 */
class DKApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DKLog.toLogcat = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        DKBug.init(this)      // नवंबर 2026: DK Inspector पूरे ऐप से जुड़ा - लॉग + हर हिस्से की गलती फ़ाइल में (सबसे पहले)
        DKState.init(this)
        NameRule.personaCall = { DkPersona.callWord(this) }   // 15 नवंबर 2026: हर स्वभाव का अपना संबोधन (बाबू / बॉस / सर)
        ModeTrack.sink = { DKLog.d(it) }   // नवंबर 2026: [LIVE] / [TEXT] / [PROBE] की कॉल और गलतियाँ अलग-अलग लॉग में
        NetFix.install(this)  // नवंबर 2026: एक नेटवर्क पर न चलना - IPv4 पहले + DoH, नेटवर्क बदले तो याद साफ़
        Prefs.loadLang(this)
        try { Prefs.oneKeyOn(this) } catch (e: Exception) { Quiet.note(e) }   // 15 नवंबर 2026: एक-key की मॉडल-लेन ([OneKeyLanes]) शुरू से
        UiLang.load(this)     // स्क्रीनों की भाषा (शुरुआती English)
        com.dk.assistant.ui.NeonTheme.load(this)          // v4: दिखावट (रंग/काँच/चमक/एनिमेशन) - सिर्फ़ UI
        registerActivityLifecycleCallbacks(com.dk.assistant.ui.NeonRefresh)
        com.dk.assistant.ui.ClayTheme.load(this)          // 23 नवंबर 2026: Soft Clay थीम (8 में से चुनी) - सिर्फ़ UI
        registerActivityLifecycleCallbacks(com.dk.assistant.ui.Clay.Refresh)
        registerActivityLifecycleCallbacks(ScreenAwake)   // v4.1: DK सामने = स्क्रीन जगी (wake lock नहीं)
        StatusNotify.install(this)
        // v3.88: लोकल याददाश्त - ऐप खुलते ही सार, और रोज़ एक बार (नवंबर 2026: कुछ भी अपने-आप नहीं मिटता)
        try { LocalMemory.maintain(this, "ऐप खुला"); LocalMemory.scheduleDaily(this) } catch (e: Exception) { Quiet.note(e) }
        // v5.1: DK Inspector - हर 20 मिनट जाँच (inexact, फ़ोन नहीं जगाता; Accessibility चालू हो तो पक्का timer भी)
        try { com.dk.assistant.inspector.Inspector.schedule(this) } catch (_: Throwable) {}
        // नवंबर 2026: Active apps से Stop के बाद रुका फ़ोन कंट्रोल - हर ~15 मिनट जाँच + ऐप शुरू होते ही (A11yWatch)
        A11yWatchJob.schedule(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            kotlinx.coroutines.delay(6_000)
            try { A11yWatch.check(this@DKApp, "ऐप शुरू") } catch (e: Exception) { Quiet.note(e) }
            // 20 नवंबर 2026 (मालिक: पहली आवाज़ 8.5 s): ऑफ़लाइन आवाज़ चुनी है = ऐप खुलते ही मॉडल खोलकर गरम (पीछे के धागे पर)
            try { com.dk.assistant.voice.engine.St3VoiceEngine.warmIfChosen(this@DKApp) } catch (e: Exception) { Quiet.note(e) }
            com.dk.assistant.voice.OwnerLevel.cleanupOldEngine(this@DKApp)   // 16 नवंबर 2026: पुराना CAM++ इंजन हटा - उसका ~28 MB मॉडल भी
            // 18 नवंबर 2026: लाइसेंस का स्तर (मुफ़्त / बेसिक / प्रीमियम / मालिक) सर्वर से ताज़ा - कौन सी सुविधा खुले ([Plan])
            if (com.dk.assistant.backend.BackendConfig.ready() && NetProbe.online(this@DKApp))
                try { com.dk.assistant.backend.BackendApi.status(this@DKApp) } catch (e: Exception) { Quiet.note(e) }
        }
        // 15 नवंबर 2026: DK AI Plus - ऑटो इवेंट ट्रिगर (default बंद = कोई receiver नहीं)
        try { com.dk.assistant.plus.PlusBoot.init(this) } catch (_: Throwable) {}
        UpdateJob.schedule(this)        // v3.6: हर 6 घंटे अपडेट जाँच + नोटिफ़िकेशन (ऐप बंद हो तब भी)
        OfflineQueue.install(this)
        AiRouter.install(this)          // 17 नवंबर 2026: DK सर्वर (keys के बाद) - ऐप का context
        TaskSync.install(this)          // 17 नवंबर 2026: कोडिंग/कंप्यूटर के काम सर्वर पर (premium/मालिक) - History में, बाद में वहीं से
        TaskHistory.install(this)       // v3.88: हर काम और उसकी हालत का इतिहास (फ़ोन में)      // v3.88: नेटवर्क टूटे तो काम कतार में, आते ही आगे
        removeLegacyAntiTheftState()
        // कोडिंग एजेंट: फ़ोन/Android ने ऐप बीच काम में बंद कर दी थी = थोड़ी देर बाद वहीं से अपने-आप आगे (बार-बार crash हो तो 3 बार बाद रुकता है)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { if (Prefs.coderOn(this)) SevenRunner.reopen(this) } catch (e: Exception) { DKLog.d("coder resume: ${e.message}") }
        }, 6000)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            saveCrash("[${t.name}]", e)
            // Background thread ki galti se poora app (aur Accessibility) band na ho.
            // Baar-baar crash hone par Android khud Accessibility OFF kar deta tha.
            if (t !== android.os.Looper.getMainLooper().thread) {
                DKLog.d("Background galti sambhali: ${e.message}")
                return@setDefaultUncaughtExceptionHandler
            }
            prev?.uncaughtException(t, e)
        }
        // Main thread par galti: crash ki jagah log karke chalte raho (max 20 baar)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            var saved = 0
            while (true) {
                try { android.os.Looper.loop() } catch (e: Throwable) {
                    saved++
                    saveCrash("[main, sambhala gaya]", e)
                    if (saved > 20) throw e
                    // नवंबर 2026 ("स्क्रीन डूबकर फिर चालू"): गलती बीच frame में हो तो स्क्रीन अधूरी/काली रह जाती थी -
                    // अब सामने वाली स्क्रीन को फिर से नाप + draw करवाओ (data/काम नहीं छूता)
                    try { top?.get()?.window?.decorView?.let { v -> v.post { v.requestLayout(); v.invalidate() } } } catch (_: Throwable) {}
                }
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = java.lang.ref.WeakReference(a); AppVisible.set(true); try { UiLang.onResumed(a) } catch (e: Exception) { Quiet.note(e) } }
            override fun onActivityPaused(a: Activity) { AppVisible.set(false) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** सामने वाली स्क्रीन (गलती के बाद दोबारा draw के लिए) */
    @Volatile private var top: java.lang.ref.WeakReference<Activity>? = null

    /**
     * नवंबर 2026: गलती की रिपोर्ट - last_crash.txt (Settings → गड़बड़ी रिपोर्ट, पहले जैसा) + crash_history.txt में
     * आख़िरी [CrashLog.KEEP] गलतियाँ (पहले हर नई गलती पुरानी को मिटा देती थी, तो असली जड़ छूट जाती थी)
     */
    private fun saveCrash(where: String, e: Throwable) {
        try {
            val head = java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                " $where version ${Updater.currentName(this)}"
            val entry = head + "\n" + e.stackTraceToString().take(4000)
            File(filesDir, "last_crash.txt").writeText(entry)
            val h = File(filesDir, "crash_history.txt")
            val old = try { if (h.exists()) h.readText() else "" } catch (_: Exception) { "" }
            h.writeText(CrashLog.push(old, entry))
        } catch (_: Throwable) {}
    }

    /** Existing installs may still have the old guard's device-admin flag and notification channel. */
    private fun removeLegacyAntiTheftState() {
        getSharedPreferences("dk_prefs", MODE_PRIVATE).edit().remove("anti_theft_guard_on").apply()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                getSystemService(android.app.NotificationManager::class.java)
                    ?.deleteNotificationChannel("anti_theft_guard_channel")
            }
        } catch (e: Exception) { Quiet.note(e) }
        try {
            val oldAdmin = android.content.ComponentName(
                packageName, "$packageName.antitheft.GuardDeviceAdminReceiver"
            )
            val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
            if (dpm?.isAdminActive(oldAdmin) == true) dpm.removeActiveAdmin(oldAdmin)
        } catch (e: Exception) { Quiet.note(e) }
    }

    companion object {
        fun lastCrash(c: android.content.Context): String =
            try { File(c.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText() ?: "" } catch (e: Exception) { "" }
        fun clearCrash(c: android.content.Context) { try { File(c.filesDir, "last_crash.txt").delete(); File(c.filesDir, "crash_history.txt").delete() } catch (e: Exception) { Quiet.note(e) } }
        /** पिछली कई गलतियाँ (नई सबसे ऊपर) */
        fun crashHistory(c: android.content.Context): String =
            try { File(c.filesDir, "crash_history.txt").takeIf { it.exists() }?.readText() ?: "" } catch (e: Exception) { "" }
    }
}

/** DK ki koi screen saamne hai? (ek screen se dusri par jaate waqt jhapak na ho, isliye thoda ruk kar) */
object AppVisible {
    @Volatile var visible = false
        private set
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    var onChange: ((Boolean) -> Unit)? = null
    private var pending: Runnable? = null

    fun set(v: Boolean) {
        pending?.let { h.removeCallbacks(it) }
        val r = Runnable { if (visible != v) { visible = v; onChange?.invoke(v) } }
        pending = r
        if (v) h.post(r) else h.postDelayed(r, 350)
    }
}
