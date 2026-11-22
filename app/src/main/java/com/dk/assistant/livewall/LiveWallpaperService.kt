package com.dk.assistant.livewall

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.dk.assistant.Quiet
import java.util.concurrent.Executors

/**
 * 22 नवंबर 2026 (मालिक: "DK के अंदर लाइव वॉलपेपर, होम स्क्रीन और लॉक स्क्रीन दोनों पे"):
 * असली समय और जगह के हिसाब से सूरज/चाँद/तारे वाला दृश्य ([SkyScene]).
 * बैटरी ([LiveWallPower]): सिर्फ़ स्क्रीन पर दिखे तब. खुलते / पेज बदलते समय कुछ सेकंड चलता दृश्य, फिर रुकी तस्वीर
 * (हर 30 सेकंड नई). बैटरी सेवर / कम बैटरी = सिर्फ़ रुकी तस्वीर. आसमान का भारी हिस्सा दूसरे thread पर.
 */
class LiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = SkyEngine()

    inner class SkyEngine : Engine() {
        private val thread = HandlerThread("dk-livewall").apply { start() }
        private val h = Handler(thread.looper)
        private val worker = Executors.newSingleThreadExecutor()
        private var visible = false
        private var scene: SkyScene? = null
        private var gfx: CanvasGfx? = null
        private var loadedId = ""
        private var w = 0; private var hgt = 0
        private var xOff = 0.5f
        private var lastDrawAt = 0L
        @Volatile private var skyBusy = false
        /** स्क्रीन खुली / पेज बदला / छुआ - कब (चलता दृश्य इसके बाद कुछ सेकंड) */
        @Volatile private var kickAt = 0L
        /** रुकी तस्वीर में लहरें-तारे उसी पल पर रुके रहें (झटका नहीं) */
        private var animT = 0.0
        private var lastAnimAt = 0L
        private var battAt = 0L; private var battPct = 100; private var charging = false
        private var nextDelay = LiveWallPower.STILL_MS

        private val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val d = delayNow(now)
                val moving = d < LiveWallPower.STILL_MS
                if (moving) {
                    if (lastAnimAt > 0) animT += (now - lastAnimAt).coerceIn(0L, 200L) / 1000.0
                    lastAnimAt = now
                } else {
                    lastAnimAt = 0L
                }
                nextDelay = d
                lastDrawAt = now
                drawFrame()
                if (visible) h.postDelayed(this, d)
            }
        }

        private fun delayNow(now: Long): Long {
            val c = applicationContext
            if (now - battAt > 60_000L || battAt == 0L) {
                battAt = now
                try {
                    c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
                        val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        battPct = if (lvl >= 0 && sc > 0) lvl * 100 / sc else 100
                        val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                    }
                } catch (e: Exception) { Quiet.note(e) }
            }
            val ps = (getSystemService(POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
            return LiveWallPower.nextDelay(LiveWallStore.animMode(c), now - kickAt, ps, battPct, charging)
        }

        /** खुला / पेज बदला / छुआ: रुकी तस्वीर हो तो अभी से चलता दृश्य (चल रहा हो तो बस समय आगे) */
        private fun kick() {
            kickAt = SystemClock.elapsedRealtime()
            if (!visible) return
            h.post {
                if (visible && nextDelay >= LiveWallPower.STILL_MS) {
                    // बचत में पेज खिसकाते समय भी ज़्यादा से ज़्यादा ~3 फ्रेम/सेकंड
                    h.removeCallbacks(tick)
                    h.postDelayed(tick, (300L - (SystemClock.elapsedRealtime() - lastDrawAt)).coerceIn(0L, 300L))
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(true)
            setTouchEventsEnabled(true)
            if (!isPreview) h.post { LiveWallStore.onActivated(applicationContext) }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            h.removeCallbacks(tick)
            if (v) { kickAt = SystemClock.elapsedRealtime(); nextDelay = LiveWallPower.ANIM_MS; h.post(tick) }
        }

        override fun onTouchEvent(event: android.view.MotionEvent) {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) kick()
            super.onTouchEvent(event)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            h.post {
                w = width; hgt = height
                scene?.resize(w, hgt)
                drawFrame()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            h.removeCallbacks(tick)
            super.onSurfaceDestroyed(holder)
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPx: Int, yPx: Int) {
            val nx = if (xStep <= 0f || xStep >= 1f) 0.5f else xOffset
            if (kotlin.math.abs(nx - xOff) > 0.001f) { xOff = nx; kick() }
        }

        override fun onDestroy() {
            visible = false
            h.removeCallbacksAndMessages(null)
            h.post { gfx?.release(); gfx = null; scene = null; thread.quitSafely() }
            worker.shutdownNow()
            super.onDestroy()
        }

        private fun ensureScene() {
            val c = applicationContext
            val id = LiveWallStore.selectedId(c)
            if (scene != null && id == loadedId) return
            if (w <= 0) return
            val l = LiveWallStore.load(c, id, w) ?: return
            gfx?.release()
            val g = CanvasGfx(l.bitmaps)
            val s = SkyScene(l.pack, l.stars, l.moon, l.moonSize, { k -> l.bitmaps[k]?.let { intArrayOf(it.width, it.height) } })
            LiveWallStore.refreshLocation(c)
            s.lat = LiveWallStore.lat(c); s.lon = LiveWallStore.lon(c)
            s.resize(w, hgt)
            s.skyAsync = { bw, bh ->
                if (!skyBusy) {
                    skyBusy = true
                    worker.execute {
                        val px = try { s.renderBackdrop() } catch (_: Throwable) { null }
                        h.post { skyBusy = false; if (px != null && scene === s && px.size == bw * bh) g.upload(SkyScene.SKY, px, bw, bh) }
                    }
                }
            }
            scene = s; gfx = g; loadedId = id
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            if (w <= 0 || hgt <= 0) return
            ensureScene()
            val canvas = try { holder.lockHardwareCanvas() } catch (_: Throwable) { null } ?: return
            try {
                val s = scene; val g = gfx
                if (s == null || g == null) placeholder(canvas)
                else {
                    g.canvas = canvas
                    canvas.drawColor(Color.BLACK)
                    s.frame(g, System.currentTimeMillis(), animT, xOff)
                    g.canvas = null
                }
            } catch (e: Exception) {
                Quiet.note(e)
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) { Quiet.note(e) }
            }
        }

        /** वॉलपेपर अभी डाउनलोड नहीं हुआ - सादा आसमान + बताओ कहाँ से डाउनलोड करें */
        private fun placeholder(c: android.graphics.Canvas) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, hgt.toFloat(), intArrayOf(0xFF0B1A3A.toInt(), 0xFF3A5A8C.toInt(), 0xFFF2A65A.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), hgt.toFloat(), p)
            p.shader = null; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = w * 0.045f
            c.drawText("DK › सेटिंग › DK लाइव › लाइव वॉलपेपर", w / 2f, hgt * 0.45f, p)
            p.textSize = w * 0.038f; p.alpha = 200
            c.drawText("वहाँ वॉलपेपर डाउनलोड करें", w / 2f, hgt * 0.45f + w * 0.07f, p)
        }
    }
}
