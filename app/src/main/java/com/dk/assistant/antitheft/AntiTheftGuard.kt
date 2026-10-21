package com.dk.assistant.antitheft

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.app.KeyguardManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import com.dk.assistant.R
import com.dk.assistant.SettingsActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

object AntiTheftGuard {

    const val PREF_GUARD_ON = "anti_theft_guard_on"
    const val CHANNEL_ID = "anti_theft_guard_channel"
    const val NOTIF_ID = 9999
    const val ARM_DELAY_MS = 10_000L

    fun isOn(c: Context): Boolean = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getBoolean(PREF_GUARD_ON, false)

    fun setOn(c: Context, on: Boolean) {
        Prefs.edit(c) { putBoolean(PREF_GUARD_ON, on) }
        if (on) {
            startServiceWithDelay(c)
        } else {
            stopService(c)
        }
    }

    /**
     * Service turant start karo (jab app saamne hai tabhi Android foreground service start karne deta hai),
     * guard service ke andar 10 second baad "armed" hota hai. Pehle yahan Thread.sleep ke baad start hota tha -
     * tab tak app background me ho to Android 12+ par ForegroundServiceStartNotAllowedException se crash.
     */
    private fun startServiceWithDelay(c: Context) {
        Toast.makeText(c, "एंटी-थिफ्ट गार्ड 10 सेकंड में चालू हो रहा है...", Toast.LENGTH_LONG).show()
        val appContext = c.applicationContext
        try {
            val intent = Intent(appContext, GuardService::class.java).putExtra(GuardService.EXTRA_ARM_DELAY_MS, ARM_DELAY_MS)
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            DKLog.d("Guard: service start nahi hua: ${e.message}")
            Prefs.edit(appContext) { putBoolean(PREF_GUARD_ON, false) }
            Toast.makeText(appContext, "गार्ड चालू नहीं हो सका — DK ऐप खोलकर दोबारा चालू करें", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopService(c: Context) {
        val intent = Intent(c, GuardService::class.java)
        c.stopService(intent)
    }

    fun handleVoiceCommand(c: Context, cmd: String): Boolean {
        val low = cmd.lowercase(Locale.ROOT)
        if (low.contains("guard on") || low.contains("गार्ड ऑन") || low.contains("गार्ड चालू") || low.contains("anti theft on")) {
            setOn(c, true)
            DKAccessibilityService.instance?.let { svc ->
                svc.scope.launch {
                    try { svc.speaker.speak("गार्ड मोड चालू कर दिया गया है। 10 सेकंड में एक्टिव हो जाएगा।") } catch (_: Exception) {}
                }
            }
            return true
        }
        if (low.contains("guard off") || low.contains("गार्ड ऑफ") || low.contains("गार्ड बंद") || low.contains("anti theft off")) {
            setOn(c, false)
            GuardService.stopAllWarnings(c)
            DKAccessibilityService.instance?.let { svc ->
                svc.scope.launch {
                    try { svc.speaker.speak("गार्ड मोड बंद कर दिया गया है।") } catch (_: Exception) {}
                }
            }
            return true
        }
        if (low.contains("कौन फ़ोन छू रहा था") || low.contains("who touched") || low.contains("intruder photos") || low.contains("फ़ोन किसने छुआ")) {
            showIntruderSummary(c)
            return true
        }
        return false
    }

    private fun showIntruderSummary(c: Context) {
        val photos = listPhotos(c)
        val count = photos.size
        val msg = if (count == 0) "वेलकम बॉस, किसी ने फ़ोन नहीं छुआ।" else "वेलकम बॉस, किसी ने फ़ोन छुआ था। $count फ़ोटो ली गई हैं।"
        DKAccessibilityService.instance?.let { svc ->
            svc.scope.launch {
                try { svc.speaker.speak(msg) } catch (_: Exception) {}
            }
        }
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
        val intent = Intent(c, SettingsActivity::class.java).putExtra("section", "guard_photos")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            c.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun listPhotos(c: Context): List<File> {
        val dir = File(c.filesDir, "guard_photos")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}

class GuardService : Service() {

    private var level = 0
    private var isRunning = false
    private var armed = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val receiver = GuardReceiver()
    /** Chetavni ke timers - resetAll() inhe hata deta hai */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Camera ke timers alag - taaki resetAll() se camera khula na reh jaaye */
    private val camHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wrongPinCount = 0
    private var tone: ToneGenerator? = null
    private var savedAlarmVolume: Int? = null
    private var cameraBusy = false
    /** Is baar guard ke dauran kuch hua (chetavni/photo) - unlock par malik ko batana hai */
    private var alertHappened = false

    companion object {
        const val EXTRA_ARM_DELAY_MS = "arm_delay_ms"
        /** Screen on hone ke baad itni der malik ko unlock karne ka mauka, phir Level 1 */
        const val UNLOCK_GRACE_MS = 5_000L
        const val LEVEL_STEP_MS = 5_000L
        const val WAKE_MS = 60_000L

        @Volatile var activeInstance: GuardService? = null

        fun stopAllWarnings(c: Context) {
            activeInstance?.resetAll()
            try {
                c.stopService(Intent(c, GuardService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
        if (!startForegroundSafely(buildNotification("एंटी-थिफ्ट गार्ड सक्रिय है (रक्षक चालू)"))) {
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, filter)
        isRunning = true
        DKLog.d("GuardService started")
    }

    /**
     * Android 14 (targetSdk 34) par type ke bina startForeground() crash karta hai.
     * Camera type tabhi milta hai jab camera permission ho aur app saamne ho; warna sirf specialUse.
     */
    private fun startForegroundSafely(notif: Notification): Boolean {
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                try {
                    startForeground(AntiTheftGuard.NOTIF_ID, notif, if (camOk) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else base)
                } catch (e: Exception) {
                    DKLog.d("Guard: camera type nahi mila (${e.message}) - bina camera ke chalu")
                    startForeground(AntiTheftGuard.NOTIF_ID, notif, base)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && camOk) {
                startForeground(AntiTheftGuard.NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(AntiTheftGuard.NOTIF_ID, notif)
            }
            true
        } catch (e: Exception) {
            DKLog.d("Guard: startForeground fail: ${e.message}")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AntiTheftGuard.isOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!armed) {
            // intent null = Android ne service dobara chalayi (START_STICKY) -> turant armed
            val delay = intent?.getLongExtra(EXTRA_ARM_DELAY_MS, 0L) ?: 0L
            handler.removeCallbacks(armRunnable)
            if (delay > 0) handler.postDelayed(armRunnable, delay) else armRunnable.run()
        }
        return START_STICKY
    }

    private val armRunnable = Runnable {
        if (isRunning && AntiTheftGuard.isOn(this)) {
            armed = true
            DKLog.d("Guard: armed")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        armed = false
        resetAll()
        activeInstance = null
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Saari chetavni band: timers, alarm, volume wapas, wakelock chhodo. Guard armed rehta hai. */
    fun resetAll() {
        level = 0
        wrongPinCount = 0
        handler.removeCallbacksAndMessages(null)
        if (isRunning && AntiTheftGuard.isOn(this) && !armed) handler.postDelayed(armRunnable, AntiTheftGuard.ARM_DELAY_MS)
        releaseTone()
        restoreAlarmVolume()
        releaseWake()
    }

    private fun isLocked(): Boolean = try {
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (_: Exception) { true }

    fun triggerWrongPinAttempt() {
        if (!isRunning || !armed) return
        wrongPinCount++
        DKLog.d("Guard: wrong PIN attempt count = $wrongPinCount")
        if (wrongPinCount >= 2 && level < 2) {
            if (level == 0) takeIntruderPhoto("wrongpin")
            escalateToLevel2()
        }
    }

    fun onPhonePickedUpOrScreenOn() {
        if (!isRunning || !armed || level > 0) return
        // Phone lock nahi (ya lock-timeout ke andar) = malik ke haath me
        if (!isLocked()) return
        handler.postDelayed({
            if (isRunning && armed && level == 0 && isLocked()) escalateToLevel1()
        }, UNLOCK_GRACE_MS)
    }

    /** Sahi PIN / fingerprint se khula = malik. Chetavni band, guard chalu rehta hai. */
    private fun onOwnerUnlocked() {
        val hadAlert = alertHappened || level > 0
        resetAll()
        alertHappened = false
        if (hadAlert) {
            val prompt = "वेलकम बॉस, किसी ने फ़ोन छुआ था। ${AntiTheftGuard.listPhotos(this).size} फ़ोटो ली गई हैं, सेटिंग्स में गार्ड फ़ोटो देखें।"
            say(prompt)
            Toast.makeText(this, prompt, Toast.LENGTH_LONG).show()
        }
    }

    private fun say(text: String) {
        DKAccessibilityService.instance?.let { svc ->
            svc.scope.launch {
                try { svc.speaker.speak(text) } catch (_: Exception) {}
            }
        }
    }

    private fun holdWake() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DK::GuardWakeLock").apply { setReferenceCounted(false) }
            }
            // Sirf chetavni ke dauran, timeout ke saath - battery bachao
            wakeLock?.acquire(WAKE_MS)
        } catch (_: Exception) {}
    }

    private fun releaseWake() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    private fun playTone(type: Int, ms: Int) {
        releaseTone()
        try {
            tone = ToneGenerator(AudioManager.STREAM_ALARM, 100).also { it.startTone(type, ms) }
            handler.postDelayed({ releaseTone() }, ms + 300L)
        } catch (e: Exception) {
            DKLog.d("Guard tone error: ${e.message}")
        }
    }

    private fun releaseTone() {
        try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}
        tone = null
    }

    private fun restoreAlarmVolume() {
        val v = savedAlarmVolume ?: return
        savedAlarmVolume = null
        try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, v, 0)
        } catch (_: Exception) {}
    }

    private fun escalateToLevel1() {
        level = 1
        alertHappened = true
        holdWake()
        DKLog.d("Guard: Level 1 triggered")
        takeIntruderPhoto("level1")
        say("This is Boss's phone, please put it down.")
        Toast.makeText(this, "⚠️ एंटी-थिफ्ट गार्ड: किसी ने फ़ोन छुआ!", Toast.LENGTH_LONG).show()

        handler.postDelayed({
            if (isRunning && level == 1) {
                if (isLocked()) escalateToLevel2() else onOwnerUnlocked()
            }
        }, LEVEL_STEP_MS)
    }

    private fun escalateToLevel2() {
        level = 2
        alertHappened = true
        holdWake()
        DKLog.d("Guard: Level 2 triggered")
        say("चेतावनी! यह बॉस का फ़ोन है, तुरंत नीचे रख दो वरना पुलिस को सूचित किया जाएगा।")
        playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000)

        handler.postDelayed({
            if (isRunning && level == 2) {
                if (isLocked()) escalateToLevel3() else onOwnerUnlocked()
            }
        }, LEVEL_STEP_MS)
    }

    private fun escalateToLevel3() {
        level = 3
        alertHappened = true
        holdWake()
        DKLog.d("Guard: Level 3 triggered")
        takeIntruderPhoto("level3")

        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (savedAlarmVolume == null) savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
        playTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 10000)
        // Alarm khatam -> volume wapas (malik unlock kare to resetAll() bhi wapas karta hai)
        handler.postDelayed({ restoreAlarmVolume() }, 10_500L)

        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = ComponentName(this, GuardDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(comp)) {
                dpm.lockNow()
            } else {
                DKLog.d("Guard: Device Admin not active for lockNow")
            }
        } catch (e: Exception) {
            DKLog.d("Guard lockNow error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun frontCameraId(): Int? {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i
        }
        return null
    }

    /**
     * Purana Camera API bina preview surface ke kai phones par takePicture fail karta hai.
     * Isliye chhupa SurfaceTexture preview + thodi der (exposure) ke baad photo; JPEG seedha save.
     */
    @Suppress("DEPRECATION")
    private fun takeIntruderPhoto(tag: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            DKLog.d("Guard: Camera permission not granted for photo")
            return
        }
        if (cameraBusy) return
        val id = frontCameraId() ?: run { DKLog.d("Guard: front camera nahi mila"); return }
        cameraBusy = true
        var camera: Camera? = null
        val tex = SurfaceTexture(10)
        fun release(c: Camera?) {
            try { c?.stopPreview() } catch (_: Exception) {}
            try { c?.release() } catch (_: Exception) {}
            try { tex.release() } catch (_: Exception) {}
            cameraBusy = false
        }
        try {
            val cam = Camera.open(id)
            camera = cam
            cam.parameters = cam.parameters.apply { pictureFormat = android.graphics.ImageFormat.JPEG }
            cam.setPreviewTexture(tex)
            cam.startPreview()
            camHandler.postDelayed({
                try {
                    cam.takePicture(null, null) { data, _ ->
                        try {
                            val dir = File(filesDir, "guard_photos")
                            if (!dir.exists()) dir.mkdirs()
                            val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val file = File(dir, "intruder_${tag}_$timeStr.jpg")
                            file.outputStream().use { it.write(data) }
                            DKLog.d("Guard photo saved: ${file.absolutePath}")
                        } catch (e: Exception) {
                            DKLog.d("Guard photo save error: ${e.message}")
                        } finally {
                            release(cam)
                        }
                    }
                } catch (e: Exception) {
                    DKLog.d("Guard takePicture error: ${e.message}")
                    release(cam)
                }
            }, 700)
            // Safety: kisi wajah se callback na aaye to 5s me camera chhodo
            camHandler.postDelayed({ if (cameraBusy) release(cam) }, 5_000)
        } catch (e: Exception) {
            DKLog.d("Guard camera open error: ${e.message}")
            release(camera)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AntiTheftGuard.CHANNEL_ID,
                "Anti-Theft Guard Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, SettingsActivity::class.java).putExtra("section", "perms")
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, AntiTheftGuard.CHANNEL_ID)
                .setContentTitle("DK Anti-Theft Guard")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_dk)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("DK Anti-Theft Guard")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_dk)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    inner class GuardReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onPhonePickedUpOrScreenOn()
                Intent.ACTION_USER_PRESENT -> onOwnerUnlocked()
            }
        }
    }
}

class GuardDeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)
        GuardService.activeInstance?.triggerWrongPinAttempt()
    }
}