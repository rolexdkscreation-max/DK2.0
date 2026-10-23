package com.dk.assistant

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/** v3.40: स्क्रीन शेयर की Android इजाज़त (बिना UI वाली पारदर्शी screen) */
class ScreenShareActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            // Android 14+: सिर्फ़ पूरी स्क्रीन (एक ऐप वाला चुनाव नहीं - तो DK सीधे "Start" दबा सकता है)
            val i = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent()
            @Suppress("DEPRECATION") startActivityForResult(i, REQ)
        } catch (e: Exception) {
            done = true
            LiveVision.onScreenDenied("Android ने popup नहीं खोला: ${e.message}")
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        done = true
        if (resultCode == RESULT_OK && data != null) ScreenShareService.start(this, resultCode, data)
        else LiveVision.onScreenDenied("user ने इजाज़त नहीं दी")
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!done && isFinishing) LiveVision.onScreenDenied("popup बंद")
        super.onDestroy()
    }

    companion object { private const val REQ = 4207 }
}

/**
 * v3.40: स्क्रीन शेयर चालू रहने तक foreground service (Android की शर्त: type mediaProjection + सूचना "बंद करें" बटन के साथ).
 * हर ~1 सेकंड स्क्रीन की छोटी (लंबी तरफ़ 1024px) JPEG -> DK लाइव; स्क्रीन न बदले तो हर 4 सेकंड.
 */
class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val gate = FrameGate()
    private var w = 0
    private var h = 0
    @Volatile private var running = false
    private var sentCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown("सूचना से बंद"); return START_NOT_STICKY }
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (data == null || running) { if (data == null) { LiveVision.onScreenDenied("इजाज़त का data नहीं"); stopSelf() }; return START_NOT_STICKY }
        try {
            // पहले foreground (Android 14: mediaProjection type की service चालू होने के बाद ही projection मिलता है)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIF_ID, notif())
        } catch (e: Exception) {
            DKLog.d("स्क्रीन शेयर: foreground नहीं हुआ: ${e.message}")
            LiveVision.onScreenDenied("Android ने स्क्रीन शेयर service नहीं चलने दी"); stopSelf(); return START_NOT_STICKY
        }
        instance = this
        try { begin(code, data) } catch (e: Exception) {
            DKLog.d("स्क्रीन शेयर शुरू नहीं हुआ: ${e.message}")
            LiveVision.onScreenDenied("स्क्रीन कैप्चर नहीं हुआ"); cleanup(); return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("projection null")
        projection = p
        val t = HandlerThread("dk-share").also { it.start() }; thread = t
        val hd = Handler(t.looper); handler = hd
        // Android 14: VirtualDisplay से पहले callback ज़रूरी
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { shutdown("Android / सूचना से रुका") }
        }, hd)
        val (sw, sh, dpi) = screenSize()
        val (fw, fh) = FrameGate.fit(sw, sh, MAX_SIDE)
        w = fw; h = fh
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2); reader = r
        display = p.createVirtualDisplay("DK-share", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, hd)
        running = true
        DKLog.d("स्क्रीन शेयर: ${sw}x$sh -> ${w}x$h")
        LiveVision.onScreenStarted()
        hd.postDelayed(tick, 600)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { grab() } catch (e: Exception) { DKLog.d("स्क्रीन फ़्रेम गड़बड़: ${e.message}") }
            handler?.postDelayed(this, 250)
        }
    }

    private fun grab() {
        val img = reader?.acquireLatestImage() ?: return
        val bmp: Bitmap
        try {
            val plane = img.planes[0]
            val ps = plane.pixelStride; val rs = plane.rowStride
            val full = Bitmap.createBitmap(rs / ps, h, Bitmap.Config.ARGB_8888)
            // कुछ फ़ोन पर आख़िरी लाइन की padding buffer में नहीं होती -> पूरा नाप बनाकर भरो
            val buf = plane.buffer; val need = rs * h
            full.copyPixelsFromBuffer(if (buf.remaining() >= need) buf else java.nio.ByteBuffer.allocate(need).also { it.put(buf); it.rewind() })
            bmp = if (full.width != w) Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() } else full
        } finally { img.close() }
        val now = System.currentTimeMillis()
        val sig = signature(bmp)
        if (!gate.shouldSend(now, sig)) { bmp.recycle(); return }
        val out = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out); bmp.recycle()
        if (LiveVision.frame(out.toByteArray())) {
            gate.sent(now, sig); sentCount++
            if (sentCount == 1 || sentCount % 60 == 0) DKLog.d("स्क्रीन फ़्रेम #$sentCount भेजा (${out.size() / 1024}KB)")
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val dm = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dm.densityDpi)
        } else {
            val m = DisplayMetrics(); wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, m.densityDpi)
        }
    }

    /** service बंद (कहीं से भी: बोलकर, बटन, सूचना, Live बंद, Android) */
    fun shutdown(why: String) {
        if (!running && projection == null) { cleanup(); LiveVision.onScreenStopped(why); return }
        running = false
        cleanup()
        LiveVision.onScreenStopped(why)
    }

    private fun cleanup() {
        running = false
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        val p = projection; projection = null
        try { p?.stop() } catch (_: Exception) {}
        thread?.quitSafely(); thread = null; handler = null
        if (instance === this) instance = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        if (running || projection != null) { running = false; cleanup(); LiveVision.onScreenStopped("service बंद") }
        super.onDestroy()
    }

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CH, UiLang.s("DK स्क्रीन शेयर"), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CH)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle(UiLang.s("${Prefs.assistantName(this)} आपकी स्क्रीन देख रहा है"))
            .setContentText(UiLang.s("DK लाइव को स्क्रीन शेयर चालू • बंद करने के लिए दबाएँ"))
            .setSmallIcon(R.drawable.ic_dk).setColor(Color.parseColor("#FF5A8CFF"))
            .setOngoing(true).setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, UiLang.s("बंद करें"), stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.dk.assistant.SCREEN_SHARE_STOP"
        private const val CH = "dk_share"
        private const val NOTIF_ID = 4208
        private const val MAX_SIDE = 1024
        @Volatile var instance: ScreenShareService? = null

        fun start(c: Context, code: Int, data: Intent) {
            val i = Intent(c, ScreenShareService::class.java).putExtra("code", code).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
        }

        /** 16x32 धूसर pixel - स्क्रीन बदली या नहीं */
        fun signature(b: Bitmap): IntArray {
            val s = Bitmap.createScaledBitmap(b, 16, 32, true)
            val px = IntArray(16 * 32); s.getPixels(px, 0, 16, 0, 0, 16, 32)
            if (s !== b) s.recycle()
            return IntArray(px.size) { i -> val p = px[i]; (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10 }
        }
    }
}
