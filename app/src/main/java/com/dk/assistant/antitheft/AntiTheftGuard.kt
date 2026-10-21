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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private const val NOTIF_ID = 9999

    fun isOn(c: Context): Boolean = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getBoolean(PREF_GUARD_ON, false)

    fun setOn(c: Context, on: Boolean) {
        Prefs.edit(c) { putBoolean(PREF_GUARD_ON, on) }
        if (on) {
            startServiceWithDelay(c)
        } else {
            stopService(c)
        }
    }

    private fun startServiceWithDelay(c: Context) {
        Toast.makeText(c, "एंटी-थिफ्ट गार्ड 10 सेकंड में चालू हो रहा है...", Toast.LENGTH_LONG).show()
        val appContext = c.applicationContext
        Thread {
            try {
                Thread.sleep(10000)
            } catch (_: InterruptedException) {}
            if (isOn(appContext)) {
                val intent = Intent(appContext, GuardService::class.java)
                ContextCompat.startForegroundService(appContext, intent)
            }
        }.start()
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
    private var wakeLock: PowerManager.WakeLock? = null
    private val receiver = GuardReceiver()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wrongPinCount = 0

    companion object {
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
        val notif = buildNotification("एंटी-थिफ्ट गार्ड सक्रिय है (रक्षक चालू)")
        startForeground(NOTIF_ID, notif)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DK::GuardWakeLock").apply {
            acquire(10 * 60 * 1000L)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(receiver, filter)
        isRunning = true
        DKLog.d("GuardService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        activeInstance = null
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    fun resetAll() {
        level = 0
        wrongPinCount = 0
        handler.removeCallbacksAndMessages(null)
    }

    fun triggerWrongPinAttempt() {
        if (!isRunning) return
        wrongPinCount++
        DKLog.d("Guard: wrong PIN attempt count = $wrongPinCount")
        if (wrongPinCount >= 2 && level < 2) {
            escalateToLevel2()
        }
    }

    fun onPhonePickedUpOrScreenOn() {
        if (!isRunning || level > 0) return
        escalateToLevel1()
    }

    private fun escalateToLevel1() {
        level = 1
        DKLog.d("Guard: Level 1 triggered")
        takeIntruderPhoto("level1")
        val text = "This is Boss's phone, please put it down."
        DKAccessibilityService.instance?.let { svc ->
            svc.scope.launch {
                try { svc.speaker.speak(text) } catch (_: Exception) {}
            }
        }
        Toast.makeText(this, "⚠️ एंटी-थिफ्ट गार्ड: किसी ने फ़ोन छुआ!", Toast.LENGTH_LONG).show()

        handler.postDelayed({
            if (isRunning && level == 1) {
                escalateToLevel2()
            }
        }, 5000)
    }

    private fun escalateToLevel2() {
        level = 2
        DKLog.d("Guard: Level 2 triggered")
        val text = "चेतावनी! यह बॉस का फ़ोन है, तुरंत नीचे रख दो वरना पुलिस को सूचित किया जाएगा।"
        DKAccessibilityService.instance?.let { svc ->
            svc.scope.launch {
                try { svc.speaker.speak(text) } catch (_: Exception) {}
            }
        }
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000)
        } catch (_: Exception) {}

        handler.postDelayed({
            if (isRunning && level == 2) {
                escalateToLevel3()
            }
        }, 5000)
    }

    private fun escalateToLevel3() {
        level = 3
        DKLog.d("Guard: Level 3 triggered")
        takeIntruderPhoto("level3")

        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 10000)
        } catch (_: Exception) {}

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

    private fun takeIntruderPhoto(tag: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            DKLog.d("Guard: Camera permission not granted for photo")
            return
        }
        try {
            val camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT)
            camera.parameters = camera.parameters.apply {
                pictureFormat = android.graphics.ImageFormat.JPEG
            }
            camera.startPreview()
            camera.takePicture(null, null) { data, _ ->
                try {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    val dir = File(filesDir, "guard_photos")
                    if (!dir.exists()) dir.mkdirs()
                    val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val file = File(dir, "intruder_${tag}_$timeStr.jpg")
                    file.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    DKLog.d("Guard photo saved: ${file.absolutePath}")
                } catch (e: Exception) {
                    DKLog.d("Guard photo save error: ${e.message}")
                } finally {
                    try {
                        camera.stopPreview()
                        camera.release()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            DKLog.d("Guard camera open error: ${e.message}")
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
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("DK Anti-Theft Guard")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_dk)
                .setContentIntent(pi)
                .build()
        }
    }

    inner class GuardReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    onPhonePickedUpOrScreenOn()
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (level > 0) {
                        stopAllWarnings(context)
                        AntiTheftGuard.setOn(context, false)
                        val prompt = "वेलकम बॉस, किसी ने फ़ोन छुआ था। फ़ोटो देखनी है?"
                        DKAccessibilityService.instance?.let { svc ->
                            svc.scope.launch {
                                try { svc.speaker.speak(prompt) } catch (_: Exception) {}
                            }
                        }
                        Toast.makeText(context, prompt, Toast.LENGTH_LONG).show()
                    }
                }
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