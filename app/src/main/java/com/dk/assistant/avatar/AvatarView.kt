package com.dk.assistant.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.dk.assistant.DKState
import com.dk.assistant.Mode
import com.dk.assistant.voice.engine.VoiceLevel

/**
 * v3.71: 2D लड़की अवतार (ऐप का चेहरा).
 *  - avatar_body पर drawBitmapMesh: बालों में हवा (चेहरा/कंधे स्थिर), साथ में हल्की साँस
 *  - ऊपर धुंधले किनारों वाला चेहरा: पलक (3-5 s), लिप-सिंक (आवाज़ का ज़ोर हर 40 ms), भाव (normal/sad/angry)
 *  - भाव/मुँह बदलना = हल्का cross-fade, झटका नहीं
 *  - चित्र पूरा, बीच में (Fit), बाकी काला
 *  - Choreographer से 60 fps, सिर्फ़ तब जब दिख रहा हो (स्क्रीन बंद / ऐप पीछे = रुका, बैटरी नहीं)
 *  - सारा समय घड़ी से - फ़ोन घुमाने पर नया View बने तब भी हवा/साँस वहीं से चलती दिखे
 */
class AvatarView @JvmOverloads constructor(c: Context, attrs: AttributeSet? = null) : View(c, attrs) {

    /** खाली जगह काली (फ़ुल-स्क्रीन). false = पीछे का ग्लास दिखे */
    var blackFill = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val faceDst = RectF(AvatarGeometry.FACE_L.toFloat(), AvatarGeometry.FACE_T.toFloat(),
        AvatarGeometry.FACE_R.toFloat(), AvatarGeometry.FACE_B.toFloat())

    private val mw = 18; private val mh = 28
    private val verts = FloatArray((mw + 1) * (mh + 1) * 2)

    private val mouthMap = MouthMapper()
    private val blink = BlinkClock()
    private var lastFrame = 0L
    private var lastSample = 0L
    private var running = false

    // अभी दिख रहा चेहरा + पिछला (cross-fade)
    private var curName: String? = null
    private var prevName: String? = null
    private var fadeStart = 0L
    private var fadeMs = 0L
    private var shownFace = Face.NORMAL

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        contentDescription = "DK अवतार"
        AvatarAssets.onLoaded = { post { invalidate() } }
    }

    private fun shouldRun() = isAttachedToWindow && windowVisibility == VISIBLE && isShown && width > 0

    private fun syncRun() {
        val want = shouldRun()
        if (want == running) return
        running = want
        if (want) { lastFrame = 0L; Choreographer.getInstance().postFrameCallback(frame) }
        else Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); AvatarAssets.onLoaded = { post { invalidate() } }; syncRun() }
    override fun onDetachedFromWindow() { running = false; Choreographer.getInstance().removeFrameCallback(frame); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); syncRun() }
    override fun onVisibilityChanged(changed: View, v: Int) { super.onVisibilityChanged(changed, v); syncRun() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); syncRun() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); syncRun() }

    /** सबसे पास वाला मौजूद चित्र: wide→half→base, blink→base, sad/angry न हों तो normal */
    private fun pick(face: Face, form: String): String? {
        val order = buildList {
            for (f in listOf(face, Face.NORMAL).distinct()) {
                when (form) {
                    "wide" -> { add("face_${f.key}_wide"); add("face_${f.key}_half"); add("face_${f.key}_base") }
                    "half" -> { add("face_${f.key}_half"); add("face_${f.key}_base") }
                    "blink" -> { add("face_${f.key}_blink"); add("face_${f.key}_base") }
                    else -> add("face_${f.key}_base")
                }
            }
        }
        for (n in order) {
            if (AvatarAssets.isMissing(n)) continue
            if (AvatarAssets.get(context, n) != null) return n
            return curName      // अभी खुल रहा है - तब तक पुराना ही
        }
        return null
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1, 100)
        lastFrame = now
        if (blackFill) c.drawColor(Color.BLACK)
        val body: Bitmap = AvatarAssets.get(context, AvatarAssets.BODY) ?: return

        val wall = System.currentTimeMillis()
        WindClock.step(wall)

        // ---- भाव, पलक, मुँह
        if (DKState.mode == Mode.SPEAKING) MoodState.stillSpeaking(wall)
        val face = MoodState.face(wall)
        if (face != shownFace) { shownFace = face; AvatarAssets.preload(context, face) }
        if (now - lastSample >= 40) {           // आवाज़ का ज़ोर हर 40 ms (30-50 के बीच)
            val raw = if (DKState.mode == Mode.SPEAKING) VoiceLevel.mouth(wall) else null
            mouthMap.update(raw, if (lastSample == 0L) 40 else now - lastSample, face)
            lastSample = now
        }
        val form = if (blink.closed(now)) "blink" else mouthMap.mouth.key
        val want = pick(face, form)
        if (want != null && want != curName) {
            // भाव बदला = धीमा fade; मुँह/पलक = बहुत छोटा (चिकना पर तुरंत)
            val faceChanged = curName != null && !curName!!.startsWith("face_${face.key}_")
            fadeMs = when { curName == null -> 0L; faceChanged -> 380L; form == "blink" || curName!!.endsWith("_blink") -> 0L; else -> 70L }
            prevName = curName; curName = want; fadeStart = now
        }

        // ---- Fit: पूरा चित्र, बीच में
        val (s, left, top) = AvatarGeometry.fit(width, height)
        if (s <= 0f) return
        c.save()
        c.translate(left, top)
        c.scale(s, s)
        c.translate(0f, AvatarGeometry.breath(wall))

        // ---- बालों में हवा (mesh)
        var i = 0
        val ph = WindClock.wind.phase; val st = WindClock.wind.strength
        for (y in 0..mh) {
            val v = y / mh.toFloat()
            for (x in 0..mw) {
                val u = x / mw.toFloat()
                val (dx, dy) = AvatarGeometry.windOffset(u, v, ph, st)
                verts[i++] = u * AvatarGeometry.IMG_W + dx
                verts[i++] = v * AvatarGeometry.IMG_H + dy
            }
        }
        c.drawBitmapMesh(body, mw, mh, verts, 0, null, 0, paint)

        // ---- चेहरा (पिछला पूरा, नया ऊपर धीरे-धीरे)
        val p = if (fadeMs <= 0) 1f else ((now - fadeStart) / fadeMs.toFloat()).coerceIn(0f, 1f)
        if (p < 1f) prevName?.let { AvatarAssets.get(context, it) }?.let { facePaint.alpha = 255; c.drawBitmap(it, null, faceDst, facePaint) }
        curName?.let { AvatarAssets.get(context, it) }?.let { facePaint.alpha = (p * 255).toInt(); c.drawBitmap(it, null, faceDst, facePaint) }
        if (p >= 1f) prevName = null
        c.restore()
    }

    /** पूरे ऐप में एक हवा - नया View (फ़ोन घुमाना / स्क्रीन बदलना) बने तब भी लहर वहीं से */
    private object WindClock {
        val wind = Wind()
        private var last = 0L
        @Synchronized fun step(now: Long) {
            val dt = if (last == 0L) 16L else (now - last).coerceIn(0, 100)
            if (dt > 0) wind.step(now, dt)
            last = now
        }
    }
}
