package com.dk.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * नवंबर 2026 (मालिक): "स्क्रीन गार्ड" - "DK स्क्रीन गार्ड चालू करो" -> 5 सेकंड बाद फ़ोन लॉक और पहरा शुरू.
 * कोई फ़ोन छुए / उठाए / खिसकाए (accelerometer), PIN डालने की कोशिश करे, पावर मेनू या ऊपर का पैनल खोले,
 * चार्जर / सिम निकाले = फ़ोन लॉक + पूरी आवाज़ में सायरन + flashlight + vibrate + DK की चेतावनी, आगे वाले कैमरे से फ़ोटो,
 * भरोसेमंद नंबर पर SMS (लोकेशन के साथ).
 * बंद: फ़ोन असली तरीके से खुले (PIN / फ़िंगरप्रिंट / फ़ेस - [Intent.ACTION_USER_PRESENT]), मालिक का चेहरा ([GuardAlarmActivity]),
 * या गुप्त शब्द बोलें. गार्ड चालू रहते DK खुद PIN डालकर फ़ोन नहीं खोलता ([blocksUnlock]).
 * pure नियम [GuardRules] में.
 */
object Guard {
    // ------------------------------------------------------------------ सेटिंग्स (Prefs)
    const val P_ARMED = "guard_armed"
    const val P_UNTIL = "guard_until"
    const val P_CODE = "guard_code"
    const val P_SMS = "guard_sms_number"
    const val P_SMS_ON = "guard_sms_on"
    const val P_SENSE = "guard_sense"
    const val P_PHOTO = "guard_photo"
    const val P_FACE = "guard_face"
    const val P_BLOCK = "guard_block_menus"
    const val P_CHARGER = "guard_charger"
    const val P_SIM = "guard_sim"
    const val P_LOG = "guard_log"
    /** अलार्म हुआ और फ़ोन अभी मालिक के पास नहीं लौटा = लोकेशन बार-बार भेजते रहो (restart के बाद भी) */
    const val P_TRACK = "guard_track"

    fun untilOf(c: Context): Long = Prefs.str(c, P_UNTIL, "0").toLongOrNull() ?: 0L
    fun code(c: Context) = Prefs.str(c, P_CODE, "").trim()
    fun smsNumber(c: Context) = Prefs.str(c, P_SMS, "").trim()
    fun smsOn(c: Context) = Prefs.bool(c, P_SMS_ON, false)
    fun sense(c: Context) = GuardRules.Sense.of(Prefs.str(c, P_SENSE, GuardRules.Sense.MEDIUM.name))
    fun photoOn(c: Context) = Prefs.bool(c, P_PHOTO, true)
    fun faceOn(c: Context) = Prefs.bool(c, P_FACE, true)
    fun blockMenus(c: Context) = Prefs.bool(c, P_BLOCK, true)
    fun chargerOn(c: Context) = Prefs.bool(c, P_CHARGER, true)
    fun simOn(c: Context) = Prefs.bool(c, P_SIM, true)

    // ------------------------------------------------------------------ हालत
    /** पहरा चालू (5 सेकंड की गिनती के बाद) */
    @Volatile var armed = false
        private set
    /** 5 सेकंड की गिनती चल रही */
    @Volatile var arming = false
        private set
    /** सायरन बज रहा */
    @Volatile var alarming = false
        private set
    /** बोलकर बंद करने वाला गुप्त शब्द (LocalRouter को Context नहीं मिलता) */
    @Volatile var codeNow = ""
        private set
    @Volatile var alarmAt = 0L
        private set

    fun active() = armed || arming || alarming

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var armJob: Job? = null
    private var alarmJob: Job? = null
    private var timerJob: Job? = null
    private var motion: GuardRules.Motion? = null
    private var sensorL: SensorEventListener? = null
    private var recv: BroadcastReceiver? = null
    private var wake: PowerManager.WakeLock? = null
    private var appCtx: Context? = null
    private var wasCharging = false
    @Volatile private var smsSentFor = 0L
    /** बार-बार सटीक लोकेशन SMS ([trackLoop]) */
    private var locJob: Job? = null
    @Volatile private var locSent = 0

    /** फ़ोन में असली PIN / पैटर्न / पासवर्ड है (तभी "फ़ोन खुला" = मालिक) */
    fun secure(c: Context) = try { c.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true } catch (_: Exception) { false }

    private fun svcOf(c: Context): DKAccessibilityService? = (c as? DKAccessibilityService) ?: DKAccessibilityService.instance

    // ------------------------------------------------------------------ बोलकर / Settings से

    fun quick(cmd: GuardRules.Cmd): QuickCommands.Quick = QuickCommands.Quick(when (cmd) {
        is GuardRules.Cmd.On -> JSONObject().put("type", GuardRules.TYPE).put("on", true).put("minutes", cmd.minutes)
        is GuardRules.Cmd.Off -> JSONObject().put("type", GuardRules.TYPE).put("on", false).put("heard", cmd.heard)
    }, "")

    /** [DKAccessibilityService.perform] से. जवाब "SAID: ..." = फ़ोन की सच्ची बात (DK यही बोले) */
    fun run(svc: DKAccessibilityService, a: JSONObject): String =
        if (a.optBoolean("on", true)) "SAID: " + arm(svc, a.optInt("minutes", 0), "आवाज़")
        else "SAID: " + offByVoice(svc, a.optString("heard"))

    /**
     * DK लाइव का screen_guard tool: सिर्फ़ चालू (मालिक, नवंबर 2026). बंद करना tool से कभी नहीं -
     * बंद के तरीके वही: फ़ोन खोलना / चेहरा / गुप्त शब्द ([offByVoice], [heardStop]).
     */
    fun liveTool(svc: DKAccessibilityService, minutes: Int): String =
        "Phone says: \"" + arm(svc, minutes.coerceIn(0, 24 * 60), "DK लाइव") + "\" - tell the user exactly this in a few words. " +
            "Screen Guard can only be turned OFF by the owner (unlock the phone, face to the warning camera, or secret word) - never by you."

    /** बोलकर बंद: गुप्त शब्द ज़रूरी (वरना कोई भी "स्क्रीन गार्ड बंद करो" बोल दे) */
    fun offByVoice(c: Context, heard: String): String {
        if (!active()) return "स्क्रीन गार्ड पहले से बंद है।"
        val code = code(c)
        if (code.length < GuardRules.MIN_CODE) return "स्क्रीन गार्ड बोलकर बंद करने के लिए सेटिंग्स में गुप्त शब्द रखिए। अभी फ़ोन खुद खोलिए, गार्ड अपने-आप बंद हो जाएगा।"
        if (!GuardRules.codeOk(heard, code)) { note(c, "बोलकर बंद करने की कोशिश - गुप्त शब्द गलत / नहीं"); return "गुप्त शब्द के साथ बोलिए, या फ़ोन खुद खोलिए।" }
        disarm(c, "गुप्त शब्द बोला")
        return "ठीक है, स्क्रीन गार्ड बंद।"
    }

    /** सुनने वाले loop से: बजते / पहरे के समय सिर्फ़ गुप्त शब्द भी काफ़ी. true = बात यहीं खत्म */
    fun heardStop(c: Context, heard: String): Boolean {
        if (!active()) return false
        val code = code(c)
        if (!GuardRules.codeOk(heard, code)) return false
        disarm(c, "गुप्त शब्द बोला")
        DKState.reply("ठीक है, स्क्रीन गार्ड बंद।")
        return true
    }

    /** @return DK क्या बोले */
    fun arm(c: Context, minutes: Int, why: String): String {
        val svc = svcOf(c) ?: return "पहले सेटिंग्स में DK का Accessibility चालू कीजिए, उसके बिना स्क्रीन गार्ड नहीं चलेगा।"
        val app = svc.applicationContext
        if (active()) return "स्क्रीन गार्ड पहले से चालू है।"
        val hasCode = code(app).length >= GuardRules.MIN_CODE
        if (!secure(app) && !hasCode)
            return "स्क्रीन गार्ड के लिए फ़ोन में PIN या पैटर्न लगाइए, या सेटिंग्स में गुप्त शब्द रखिए - वरना कोई भी इसे बंद कर देगा।"
        val until = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        Prefs.edit(app) { putBoolean(P_ARMED, true); putString(P_UNTIL, until.toString()) }
        codeNow = code(app)
        arming = true
        note(app, "स्क्रीन गार्ड चालू ($why)" + if (minutes > 0) " - ${GuardRules.label(minutes)}" else "")
        armJob?.cancel()
        armJob = scope.launch {
            delay(GuardRules.ARM_DELAY_MS)
            if (!arming) return@launch
            lockNow(svc)
            startWatch(svc, until)
            arming = false
        }
        return GuardRules.armSay(minutes, hasCode)
    }

    /** service फिर जुड़ी (फ़ोन restart / DK अपडेट) - पहरा चालू था तो फिर से (बिना गिनती) */
    fun resume(svc: DKAccessibilityService) {
        val app = svc.applicationContext
        if (!Prefs.bool(app, P_ARMED, false) || active()) return
        val until = untilOf(app)
        if (GuardRules.expired(until, System.currentTimeMillis())) { Prefs.edit(app) { putBoolean(P_ARMED, false) }; return }
        codeNow = code(app)
        note(app, "स्क्रीन गार्ड फिर से चालू (DK दोबारा शुरू हुआ)")
        startWatch(svc, until)
        // अलार्म के बाद फ़ोन बंद-चालू (restart) किया गया = चोर के पास है, लोकेशन भेजना जारी
        if (Prefs.bool(app, P_TRACK, false)) startTracking(app, "फ़ोन दोबारा चालू हुआ (स्क्रीन गार्ड अलार्म के बाद)")
    }

    fun disarm(c: Context, why: String) {
        val app = c.applicationContext
        if (!active() && !Prefs.bool(app, P_ARMED, false)) return
        Prefs.edit(app) { putBoolean(P_ARMED, false); putString(P_UNTIL, "0"); putBoolean(P_TRACK, false) }
        arming = false; armed = false
        armJob?.cancel(); timerJob?.cancel()
        if (locJob?.isActive == true) note(app, "लोकेशन भेजना बंद ($locSent SMS गए)")
        locJob?.cancel(); locJob = null; locSent = 0
        stopAlarm()
        stopWatch(app)
        note(app, "स्क्रीन गार्ड बंद ($why)")
        DKLog.d("स्क्रीन गार्ड बंद: $why")
    }

    /** गार्ड चालू = DK खुद PIN डालकर फ़ोन नहीं खोलेगा (कोई "DK फोन अनलॉक करो" बोलकर न खुलवा ले) */
    fun blocksUnlock(): String? = if (active())
        PhoneLockVoice.UNLOCK_FAIL + " स्क्रीन गार्ड चालू है, इसलिए DK फ़ोन नहीं खोलेगा। पहले स्क्रीन गार्ड बंद कीजिए - गुप्त शब्द बोलकर या फ़ोन खुद खोलकर।"
    else null

    // ------------------------------------------------------------------ पहरा

    private fun lockNow(svc: DKAccessibilityService) {
        try {
            if (Build.VERSION.SDK_INT >= 28) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            else NativeActionEngine.lockOld()
        } catch (e: Exception) { DKLog.d("गार्ड: लॉक नहीं हुआ (${e.message})") }
    }

    @Synchronized
    private fun startWatch(svc: DKAccessibilityService, until: Long) {
        val app = svc.applicationContext
        appCtx = app
        armed = true
        motion = GuardRules.Motion(sense(app))
        // स्क्रीन बंद हो तब भी हिलना पता चले
        try {
            wake?.let { if (it.isHeld) it.release() }
            wake = app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dk:guard")?.apply {
                setReferenceCounted(false); acquire(if (until > 0) (until - System.currentTimeMillis()).coerceAtLeast(60_000L) else 24 * 3_600_000L)
            }
        } catch (_: Exception) {}
        try {
            val sm = app.getSystemService(SensorManager::class.java)
            val s = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sm != null && s != null) {
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        if (!armed || alarming) return
                        val m = motion ?: return
                        if (m.feed(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble(), System.currentTimeMillis()))
                            trigger(app, "किसी ने फ़ोन छुआ / उठाया (${m.lastWhy})")
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
                sensorL = l
            } else DKLog.d("गार्ड: accelerometer नहीं मिला - सिर्फ़ PIN / पैनल / चार्जर / सिम से पता चलेगा")
        } catch (e: Exception) { DKLog.d("गार्ड: sensor नहीं चला (${e.message})") }
        wasCharging = try {
            val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        } catch (_: Exception) { false }
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, i: Intent?) {
                    when (i?.action) {
                        // फ़ोन असली PIN / फ़िंगरप्रिंट / फ़ेस से खुला = मालिक
                        Intent.ACTION_USER_PRESENT -> if (secure(app)) disarm(app, "फ़ोन मालिक ने खोला")
                        Intent.ACTION_POWER_DISCONNECTED -> if (wasCharging && chargerOn(app)) trigger(app, "चार्जर निकाला")
                        Intent.ACTION_SCREEN_OFF -> if (alarming) scope.launch { delay(400); GuardAlarmActivity.show(app) }
                        "android.intent.action.SIM_STATE_CHANGED" ->
                            if (simOn(app) && i.getStringExtra("ss") == "ABSENT") trigger(app, "सिम निकाली")
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_SCREEN_OFF)
                addAction("android.intent.action.SIM_STATE_CHANGED")
            }
            ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            recv = r
        } catch (e: Exception) { DKLog.d("गार्ड: receiver नहीं लगा (${e.message})") }
        timerJob?.cancel()
        if (until > 0) timerJob = scope.launch {
            while (isActive && !GuardRules.expired(until, System.currentTimeMillis())) delay(15_000)
            // अलार्म बज रहा हो या लोकेशन भेजी जा रही हो (फ़ोन किसी और के पास) = समय पूरा होने पर भी बंद नहीं
            if (isActive && !alarming && locJob?.isActive != true) disarm(app, "समय पूरा") else if (isActive) Prefs.edit(app) { putString(P_UNTIL, "0") }
        }
        DKLog.d("स्क्रीन गार्ड: पहरा शुरू (संवेदनशीलता ${sense(app).hi})")
    }

    @Synchronized
    private fun stopWatch(app: Context) {
        try { sensorL?.let { app.getSystemService(SensorManager::class.java)?.unregisterListener(it) } } catch (_: Exception) {}
        sensorL = null; motion = null
        try { recv?.let { app.unregisterReceiver(it) } } catch (_: Exception) {}
        recv = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
    }

    /** accessibility event: लॉक स्क्रीन पर PIN का खाना / पावर मेनू / ऊपर का पैनल */
    fun onEvent(svc: DKAccessibilityService, e: AccessibilityEvent) {
        if (!armed && !alarming) return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg == svc.packageName || (pkg != "android" && pkg != "com.android.systemui" && !pkg.contains("systemui"))) return
        val texts = ArrayList<String>()
        e.text?.forEach { it?.toString()?.let(texts::add) }
        try { (e.source ?: svc.rootInActiveWindow)?.let { collect(it, texts, 0) } } catch (_: Exception) {}
        if (texts.isEmpty()) return
        val app = svc.applicationContext
        when {
            GuardRules.isPowerMenu(texts) -> {
                if (blockMenus(app)) closeMenus(svc)
                trigger(app, "पावर मेनू खोला")
            }
            GuardRules.isQuickSettings(texts) && locked(app) -> {
                if (blockMenus(app)) closeMenus(svc)
                trigger(app, "ऊपर का पैनल खोला")
            }
            GuardRules.isBouncer(texts) && !alarming -> trigger(app, "PIN / पैटर्न डालने की कोशिश")
        }
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (out.size > 150 || depth > 12) return
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    private fun closeMenus(svc: DKAccessibilityService) {
        try {
            if (Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (_: Exception) {}
    }

    private fun locked(c: Context) = try { c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true } catch (_: Exception) { true }

    // ------------------------------------------------------------------ अलार्म

    /** Settings → "अभी आज़माएँ": 10 सेकंड सायरन, फिर अपने-आप बंद (लॉक नहीं, SMS नहीं) */
    fun test(c: Context) {
        if (alarming) return
        val app = c.applicationContext
        appCtx = app
        startAlarm(app, "आज़माना", test = true)
    }

    fun trigger(c: Context, why: String) {
        if (alarming || !armed) return
        val app = c.applicationContext
        DKLog.d("स्क्रीन गार्ड: अलार्म - $why")
        note(app, "⚠ $why")
        svcOf(app)?.let { lockNow(it) }
        startAlarm(app, why, test = false)
        if (smsOn(app)) startTracking(app, why)
    }

    /**
     * मालिक (नवंबर 2026): अलार्म के बाद सटीक (GPS) लोकेशन SMS, फिर जब तक फ़ोन मालिक के पास न लौटे
     * (फ़ोन खोले / चेहरा / गुप्त शब्द = [disarm]) थोड़ी-थोड़ी देर पर अपने-आप नई लोकेशन ([GuardRules.nextLocDelayMs]).
     * पहले: एक ही SMS, और उसमें जो लोकेशन सबसे पहले आए (अक्सर मोबाइल टावर वाली, 1-3 किमी दूर) - सटीक नहीं.
     */
    @Synchronized
    private fun startTracking(app: Context, why: String) {
        if (locJob?.isActive == true) return
        Prefs.edit(app) { putBoolean(P_TRACK, true) }
        locSent = 0
        note(app, "सटीक लोकेशन भेजना शुरू (हर कुछ मिनट, जब तक मालिक फ़ोन न खोले)")
        locJob = scope.launch {
            var reason = why
            while (isActive && Prefs.bool(app, P_ARMED, false) && locSent < GuardRules.LOC_MAX_SMS) {
                if (System.currentTimeMillis() - smsSentFor >= GuardRules.LOC_MIN_GAP_MS) {
                    smsSentFor = System.currentTimeMillis()
                    if (sendSms(app, reason, locSent)) locSent++
                    else if (!smsReady(app)) break          // नंबर / इजाज़त ही नहीं - बार-बार कोशिश बेकार
                }
                reason = "अपडेट"
                delay(GuardRules.nextLocDelayMs(locSent.coerceAtLeast(1)))
            }
        }
    }

    private fun smsReady(c: Context) = GuardRules.validNumber(smsNumber(c)) &&
        ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * सटीक लोकेशन: GPS + नेटवर्क (+ fused) तीनों से लगातार माँगो, [GuardRules.LOC_WAIT_MS] तक सबसे सटीक रखो,
     * GPS से [GuardRules.LOC_GOOD_M] मीटर के अंदर मिलते ही रुको. फ़ोन की याद वाली पुरानी लोकेशन (getLastKnown) नहीं.
     * GPS बंद हो और DK के पास WRITE_SECURE_SETTINGS हो तो लोकेशन (high accuracy) खुद चालू करता है.
     */
    suspend fun preciseFix(c: Context, waitMs: Long = GuardRules.LOC_WAIT_MS): GuardRules.Fix? {
        if (!LocalInfo.hasPermission(c)) { note(c, "लोकेशन नहीं - लोकेशन की इजाज़त नहीं"); return null }
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            note(c, "सिर्फ़ अंदाज़न लोकेशन की इजाज़त है - सटीक के लिए Settings → स्क्रीन गार्ड → परमिशन दें (\"सटीक\" चुनें)")
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        fun on(p: String) = try { lm.isProviderEnabled(p) } catch (_: Exception) { false }
        if (!on(LocationManager.GPS_PROVIDER)) {
            try {
                @Suppress("DEPRECATION")
                android.provider.Settings.Secure.putInt(c.contentResolver, android.provider.Settings.Secure.LOCATION_MODE, android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
                delay(1_500)
            } catch (_: Exception) {}
        }
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }.filter { on(it) }
        if (providers.isEmpty()) { note(c, "लोकेशन नहीं - फ़ोन की लोकेशन (GPS) बंद है"); return null }
        val got = java.util.Collections.synchronizedList(ArrayList<GuardRules.Fix>())
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun toFix(l: Location) = GuardRules.Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 9_999f,
            gps = l.provider == LocationManager.GPS_PROVIDER || (l.provider == "fused" && l.hasAccuracy() && l.accuracy <= GuardRules.LOC_GOOD_M * 2),
            ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L)
        val listener = LocationListener { l -> val f = toFix(l); got.add(f); if (GuardRules.goodEnough(f)) done.complete(Unit) }
        withContext(Dispatchers.Main) {
            for (p in providers) try {
                @Suppress("MissingPermission") lm.requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper())
            } catch (e: Exception) { DKLog.d("स्क्रीन गार्ड: $p लोकेशन नहीं (${e.message})") }
        }
        try { kotlinx.coroutines.withTimeoutOrNull(waitMs) { done.await() } }
        finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { try { lm.removeUpdates(listener) } catch (_: Exception) {} } }
        return GuardRules.pickBest(synchronized(got) { got.toList() })
    }

    private class State {
        var alarmVol = -1
        var filter = -1
        var track: AudioTrack? = null
        var voice: AudioTrack? = null
        var tts: TextToSpeech? = null
        var torchId: String? = null
        var vib: Vibrator? = null
    }

    private val ALARM: AudioAttributes by lazy {
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    }

    @Synchronized
    private fun startAlarm(app: Context, why: String, test: Boolean) {
        if (alarming) return
        alarming = true; alarmAt = System.currentTimeMillis()
        alarmJob = scope.launch {
            val st = State()
            try { ring(app, st, if (test) 10_000L else GuardRules.ALARM_MAX_MS) }
            catch (e: Exception) { if (e !is kotlinx.coroutines.CancellationException) DKLog.d("गार्ड सायरन: ${e.javaClass.simpleName} ${e.message}") }
            finally {
                restore(app, st); alarming = false; GuardAlarmActivity.closeAll()
                motion = if (armed) GuardRules.Motion(sense(app)) else null      // फिर हिला = फिर बजे
                DKLog.d("गार्ड सायरन बंद")
            }
        }
    }

    private fun stopAlarm() { alarmJob?.cancel(); alarmJob = null }

    private suspend fun ring(c: Context, st: State, maxMs: Long) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        try { st.alarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM); am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0) } catch (_: Exception) {}
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                st.filter = nm.currentInterruptionFilter; nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: Exception) {}
        GuardAlarmActivity.show(c)
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 300), 0), ALARM); st.vib = v
        } catch (_: Exception) {}
        val cm = c.getSystemService(CameraManager::class.java)
        st.torchId = try { cm?.cameraIdList?.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } } catch (_: Exception) { null }
        val blink = st.torchId?.let { id ->
            scope.launch { var on = false; while (isActive) { on = !on; try { cm?.setTorchMode(id, on) } catch (_: Exception) {}; delay(250) } }
        }
        // सायरन: एक चक्कर (1.2 s) बार-बार
        val rate = 22_050
        val pcm = GuardRules.sirenPcm(rate, 1_200)
        st.track = try {
            AudioTrack.Builder().setAudioAttributes(ALARM)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build().apply {
                    write(pcm, 0, pcm.size); setLoopPoints(0, pcm.size, -1)
                }
        } catch (e: Exception) { DKLog.d("गार्ड: सायरन track नहीं बना (${e.message})"); null }
        val clip = try { com.dk.assistant.voice.HeyDk.clipPcm(c, GuardRules.SAY) } catch (_: Exception) { null }
        if (clip == null) st.tts = phoneTts(c)
        val end = System.currentTimeMillis() + maxMs
        try {
            while (System.currentTimeMillis() < end) {
                try { if (am.getStreamVolume(AudioManager.STREAM_ALARM) < maxVol) am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0) } catch (_: Exception) {}
                try { st.track?.let { it.reloadStaticData(); it.play() } } catch (_: Exception) {}
                delay(GuardRules.SIREN_MS)
                try { st.track?.pause() } catch (_: Exception) {}
                say(c, st, clip)
                delay(GuardRules.LISTEN_GAP_MS)            // थोड़ा चुप: मालिक का गुप्त शब्द सुनाई दे
                GuardAlarmActivity.show(c)                 // किसी ने home / back दबाकर हटाया हो
            }
        } finally { blink?.cancel() }
    }

    private suspend fun say(c: Context, st: State, clip: ByteArray?) {
        if (clip != null && clip.size > 1000) {
            val rate = com.dk.assistant.voice.engine.VoiceEngine.RATE
            val tr = try {
                AudioTrack.Builder().setAudioAttributes(ALARM)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(clip.size).build()
            } catch (_: Exception) { null }
            if (tr != null) {
                st.voice?.release(); st.voice = tr
                try { tr.write(clip, 0, clip.size); tr.play(); delay(clip.size * 1000L / (rate * 2) + 200); tr.stop() } catch (_: Exception) {}
                return
            }
        }
        val t = st.tts ?: return
        try { t.speak(GuardRules.SAY, TextToSpeech.QUEUE_FLUSH, null, "dk_guard"); delay(5_000) } catch (_: Exception) {}
    }

    private fun phoneTts(c: Context): TextToSpeech? = try {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(c) { ok -> if (ok == TextToSpeech.SUCCESS) { tts?.setAudioAttributes(ALARM); tts?.language = Locale("hi", "IN") } }
        tts
    } catch (_: Exception) { null }

    private fun restore(c: Context, st: State) {
        try { st.track?.stop() } catch (_: Exception) {}
        try { st.track?.release() } catch (_: Exception) {}
        try { st.voice?.release() } catch (_: Exception) {}
        try { st.tts?.stop(); st.tts?.shutdown() } catch (_: Exception) {}
        try { st.vib?.cancel() } catch (_: Exception) {}
        st.torchId?.let { id -> try { c.getSystemService(CameraManager::class.java)?.setTorchMode(id, false) } catch (_: Exception) {} }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (st.alarmVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_ALARM, st.alarmVol, 0) } catch (_: Exception) {}
        if (st.filter >= 0) try { c.getSystemService(NotificationManager::class.java)?.setInterruptionFilter(st.filter) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ SMS / रिकॉर्ड

    /** @param n 0 = पहला SMS, 1.. = लोकेशन अपडेट. @return true = SMS गया */
    private suspend fun sendSms(c: Context, why: String, n: Int = 0): Boolean {
        val num = smsNumber(c)
        if (!GuardRules.validNumber(num)) { note(c, "SMS नहीं भेजा - भरोसेमंद नंबर ठीक नहीं"); return false }
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { note(c, "SMS नहीं भेजा - SMS की इजाज़त नहीं"); return false }
        val loc = try { preciseFix(c) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
        if (!Prefs.bool(c, P_ARMED, false) && n > 0) return false      // इंतज़ार के बीच मालिक ने खोल लिया
        val msg = GuardRules.smsText(why, now(), loc?.lat, loc?.lon, loc?.accM, n, loc?.gps ?: false)
        try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(android.telephony.SmsManager::class.java) else android.telephony.SmsManager.getDefault()
            sms.sendMultipartTextMessage(num, null, sms.divideMessage(msg), null, null)
            note(c, "SMS भेजा: $num" + (loc?.let { " • लोकेशन ±${GuardRules.accLabel(it.accM)}" + if (it.gps) " (GPS)" else " (नेटवर्क)" } ?: " • लोकेशन नहीं मिली") + if (n > 0) " • अपडेट #$n" else "")
            return true
        } catch (e: Exception) { note(c, "SMS नहीं गया (${e.message?.take(40)})"); return false }
    }

    private fun now(): String = SimpleDateFormat("dd MMM, hh:mm a", Locale("hi", "IN")).format(Date())

    /** घटनाओं की लिस्ट (Settings → घुसपैठ लिस्ट) - आख़िरी 40 */
    fun note(c: Context, what: String) {
        try {
            val arr = try { JSONArray(Prefs.str(c, P_LOG, "[]")) } catch (_: Exception) { JSONArray() }
            arr.put("${now()} • $what")
            val keep = JSONArray(); val from = maxOf(0, arr.length() - 40)
            for (i in from until arr.length()) keep.put(arr.getString(i))
            Prefs.edit(c) { putString(P_LOG, keep.toString()) }
        } catch (_: Exception) {}
    }

    fun log(c: Context): List<String> = try {
        val arr = JSONArray(Prefs.str(c, P_LOG, "[]")); (0 until arr.length()).map { arr.getString(it) }.reversed()
    } catch (_: Exception) { emptyList() }

    fun photoDir(c: Context) = File(c.filesDir, "guard")

    fun photos(c: Context): List<File> = photoDir(c).listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** घुसपैठिए की फ़ोटो सेव (सबसे पुरानी हटती है) */
    fun savePhoto(c: Context, bmp: android.graphics.Bitmap): File? = try {
        val d = photoDir(c).apply { mkdirs() }
        val f = File(d, "intruder_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        photos(c).drop(GuardRules.MAX_PHOTOS).forEach { it.delete() }
        note(c, "फ़ोटो ली (${f.name})")
        f
    } catch (_: Exception) { null }

    fun clearRecords(c: Context) {
        photos(c).forEach { it.delete() }
        Prefs.edit(c) { putString(P_LOG, "[]") }
    }

    fun status(c: Context): String = when {
        alarming -> "⚠ अलार्म बज रहा है"
        arming -> "⏳ 5 सेकंड में चालू हो रहा है"
        armed && locJob?.isActive == true -> "⚠ अलार्म हुआ था - सटीक लोकेशन हर कुछ मिनट भेजी जा रही है ($locSent SMS)"
        armed -> "✓ चालू - फ़ोन पर पहरा है" + (untilOf(c).takeIf { it > 0 }?.let { " (" + SimpleDateFormat("hh:mm a", Locale("hi", "IN")).format(Date(it)) + " तक)" } ?: "")
        else -> "✗ बंद"
    }
}
