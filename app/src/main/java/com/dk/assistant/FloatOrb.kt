package com.dk.assistant

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** ORB के आकार की सीमा (dp) - Settings slider और pinch दोनों यही मानते हैं */
object FloatOrbSize {
    /** पुराना किनारे वाला छोटा ORB 76dp का था - वही शुरुआती आकार */
    const val DEFAULT_DP = 76
    const val MIN_DP = 48
    const val MAX_DP = 160
    fun clamp(dp: Int) = dp.coerceIn(MIN_DP, MAX_DP)
    /** नीचे से कितनी दूरी (dp) - navigation bar / gesture पट्टी के ऊपर */
    const val BOTTOM_GAP_DP = 120
}

/**
 * मालिक (01 नवंबर 2026): "स्क्रीन पर दो ORB दिखते हैं - किनारे का छोटा और नीचे बीच का बड़ा (✕ वाला). सिर्फ़ एक चाहिए,
 * नीचे बीच में, पुराने छोटे जितना, छोटा-बड़ा हो सके (slider / pinch), ऐप खुला / minimize / दूसरे ऐप पर - हर हाल में यही एक.
 * ✕ = सिर्फ़ ORB छिपे, DK (माइक, Hey DK, सेवा) चलता रहे. खींचकर जगह बदले. बाकी स्क्रीन छूना पहले जैसा."
 *
 * Accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY) - हर ऐप के ऊपर, DK का अपना ऐप खुला हो तब भी.
 * खिड़की सिर्फ़ ORB जितनी बड़ी + FLAG_NOT_FOCUSABLE / NOT_TOUCH_MODAL = बाकी स्क्रीन का छूना आर-पार.
 * टैप = पहले वाले किनारे के ORB जैसा (कॉल चल रही हो तो कॉल काटो, वरना Live चालू/बंद).
 */
class FloatOrb(private val c: Context, private val onTap: () -> Unit) {
    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = c.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var orb: OrbView? = null
    private var close: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var curDp = FloatOrbSize.DEFAULT_DP.toFloat()

    private fun px(dp: Float) = (dp * density).roundToInt()
    private fun closePx() = px(26f)
    /** खिड़की = ORB + ✕ का आधा हिस्सा (✕ ORB के ऊपर-दाएँ कोने पर) */
    private fun winPx(orbPx: Int) = orbPx + closePx() / 2

    val showing: Boolean get() = root != null

    /** Prefs के हिसाब से दिखाओ / हटाओ / आकार ठीक करो (Settings, notification, ऐप खुलना - सब यहीं से) */
    fun sync() {
        if (!Prefs.floatOrbOn(c)) { remove(); return }
        val want = Prefs.floatOrbDp(c).toFloat()
        if (root == null) show() else if (abs(want - curDp) >= 1f) applySize(want, save = false)
        OrbNotify.cancel(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        curDp = Prefs.floatOrbDp(c).toFloat()
        val o = px(curDp); val w = winPx(o)
        val dm = c.resources.displayMetrics
        val p = WindowManager.LayoutParams(
            w, w,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // नीचे बीच में (हर बार दिखने पर यहीं से; खींचने पर जहाँ छोड़ो वहीं)
            x = (dm.widthPixels - w) / 2
            y = dm.heightPixels - w - px(FloatOrbSize.BOTTOM_GAP_DP.toFloat())
        }
        val box = FrameLayout(c)
        val ov = OrbView(c).apply { mini = true; contentDescription = "DK ORB - दबाएँ: बोलें / रोकें" }
        box.addView(ov, FrameLayout.LayoutParams(o, o, Gravity.BOTTOM or Gravity.START))
        val x = TextView(c).apply {
            text = "✕"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC1B1B2A.toInt()); setStroke(px(1f), 0x66FFFFFF) }
            contentDescription = "ORB छिपाएँ (DK चालू रहेगा)"
            setOnClickListener { hideByUser() }
        }
        box.addView(x, FrameLayout.LayoutParams(closePx(), closePx(), Gravity.TOP or Gravity.END))
        attachTouch(ov, p)
        try { wm.addView(box, p) } catch (e: Exception) { DKLog.d("ORB नहीं लगा: ${e.javaClass.simpleName}"); return }
        root = box; orb = ov; close = x; lp = p
    }

    /** ✕ = सिर्फ़ ORB छिपे. Live / माइक / Hey DK / सेवा को हाथ नहीं. वापस लाने का बटन notification में */
    private fun hideByUser() {
        Prefs.setFloatOrbOn(c, false)
        remove()
        OrbNotify.showHidden(c)
        DKLog.d("ORB छिपाया (✕) - DK चालू है; वापस: ऐप खोलें / Settings / notification")
    }

    fun remove() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null; orb = null; close = null; lp = null
    }

    /** नया आकार - ORB का बीच वहीं रहे */
    private fun applySize(dp: Float, save: Boolean) {
        val r = root ?: return; val p = lp ?: return; val ov = orb ?: return
        val nd = dp.coerceIn(FloatOrbSize.MIN_DP.toFloat(), FloatOrbSize.MAX_DP.toFloat())
        val oldW = p.width
        val o = px(nd); val w = winPx(o)
        val cx = p.x + oldW / 2; val cy = p.y + oldW / 2
        p.width = w; p.height = w; p.x = cx - w / 2; p.y = cy - w / 2
        ov.layoutParams = (ov.layoutParams as FrameLayout.LayoutParams).apply { width = o; height = o }
        curDp = nd
        try { wm.updateViewLayout(r, p) } catch (_: Exception) {}
        if (save) Prefs.setFloatOrbDp(c, nd.roundToInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(v: View, p: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var scaling = false
        // दो उंगलियों से फैलाओ / सिकोड़ो = आकार; छोड़ते ही सेव
        val scale = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean { applySize(curDp * d.scaleFactor, save = false); return true }
            override fun onScaleEnd(d: ScaleGestureDetector) { Prefs.setFloatOrbDp(c, curDp.roundToInt()) }
        })
        v.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = p.x; startY = p.y; moved = false; scaling = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> scaling = true
                MotionEvent.ACTION_MOVE -> if (!scaling && ev.pointerCount == 1) {
                    // पहले जैसा: पकड़कर खींचो = जगह बदले
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                        root?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) {} }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved && !scaling) onTap()
            }
            true
        }
    }
}

/** ✕ के बाद "ORB दिखाओ" वाली notification (DK चालू है, सिर्फ़ ORB छिपा है) */
object OrbNotify {
    private const val CH = "dk_orb"
    private const val ID = 7411
    const val ACTION_SHOW = "com.dk.assistant.SHOW_ORB"

    fun showHidden(c: Context) = try {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "DK ORB", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getBroadcast(c, ID, Intent(c, OrbShowReceiver::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val name = Prefs.assistantName(c)
        @Suppress("DEPRECATION")
        val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CH) else android.app.Notification.Builder(c))
            .setSmallIcon(R.drawable.ic_dk)
            .setContentTitle(UiLang.s("$name का ORB छिपा है"))
            .setContentText(UiLang.s("$name चालू है और सुन रहा है। ORB वापस लाने के लिए दबाएँ।"))
            .setContentIntent(pi).setOnlyAlertOnce(true).setAutoCancel(true)
            .addAction(android.app.Notification.Action.Builder(null as android.graphics.drawable.Icon?, UiLang.s("ORB दिखाओ"), pi).build())
        nm.notify(ID, b.build())
    } catch (e: Exception) { DKLog.d("ORB notification नहीं लगी: ${e.message}") }

    fun cancel(c: Context) {
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } catch (_: Exception) {}
    }
}

/** notification का "ORB दिखाओ" बटन */
class OrbShowReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        Prefs.setFloatOrbOn(c, true)
        OrbNotify.cancel(c)
        DKAccessibilityService.instance?.refreshOrb()
    }
}
