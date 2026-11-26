package com.dk.assistant.dkai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import java.util.Locale
import kotlin.math.abs

/**
 * 26 नवंबर 2026 (मालिक): DK का होलोग्राफ़िक ORB - काले आसमान में धुएँ / रेशम जैसी पारदर्शी रोशनी से बने
 * बिना चेहरे वाले महिला / पुरुष AI कैरेक्टर ([assets/orb/orb_avatar.html], Three.js + GLSL).
 * ORB की जगह यही दिखता है (होम का बीच, DK Live) - पुराने डॉट वाले "पुरुष / महिला चेहरा" की जगह.
 *
 * - हार्डवेयर से चलने वाला WebView, अपने आप 280dp का गोल ORB डिब्बा (बाहर से साइज़ दिया तो वही)
 * - [switchToFemale] / [switchToMale] = धुएँ में धीरे-धीरे नया कैरेक्टर; [updateVoiceAmplitude] = आवाज़ 0..1
 * - [level] दिया हो तो ख़ुद हर ~33ms आवाज़ पढ़कर भेजता है (सिर्फ़ बदलने पर)
 * - Three.js ऐप में पहले से (assets/forge) - APK में कुछ भारी नहीं; कोई network नहीं
 * - छिपते ही रुकता (बैटरी), दिखते ही वहीं से; WebView बंद हो जाए तो ऐप नहीं गिरता
 */
class CharacterOrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** आवाज़ का स्रोत (0..1) - जैसे होम का homeLevel; null = सिर्फ़ [updateVoiceAmplitude] से */
    var level: (() -> Float)? = null
        set(v) { field = v; schedule() }

    /** अभी कौन: [FEMALE] या [MALE] */
    var character: String = FEMALE
        private set

    private var web: WebView? = null
    private var ready = false
    private var sentLevel = -1f
    private var sentChar = ""
    private var paused = false
    private var looping = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun switchToFemale() = switchTo(FEMALE)
    fun switchToMale() = switchTo(MALE)

    fun switchTo(type: String) {
        character = norm(type)
        push()
    }

    /** आवाज़ का ज़ोर 0.0..1.0 - चेहरे की चमक साँस की तरह धीरे-धीरे बढ़ती, धुंध में धीमी लहरें */
    fun updateVoiceAmplitude(amplitude: Float) {
        val a = clampLevel(amplitude)
        if (!ready) { pendingLevel = a; return }
        if (!changed(sentLevel, a)) return
        sentLevel = a
        js(levelJs(a))
    }
    private var pendingLevel = 0f

    // ------------------------------------------------------------------ WebView
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb() {
        if (web != null) return
        val loader = WebViewAssetLoader.Builder().setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        val w = try { WebView(context) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); return }
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isVerticalScrollBarEnabled = false; w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isClickable = false; w.isFocusable = false; w.isLongClickable = false
        w.isHapticFeedbackEnabled = false
        w.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false); builtInZoomControls = false; displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != HOST) return WebResourceResponse("text/plain", "utf-8", null)   // कोई network नहीं
                return loader.shouldInterceptRequest(request.url)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String) {
                ready = true; sentChar = ""; sentLevel = -1f
                push(); updateVoiceAmplitude(pendingLevel)
                if (paused) js("window.setPaused&&window.setPaused(true)")
            }
            // WebView का अलग process गिरा = ऐप नहीं गिरे; अगली बार दिखते ही नया बनेगा
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (web === view) { removeView(view); web = null; ready = false }
                try { view.destroy() } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) }
                post { if (isAttachedToWindow && isShown) ensureWeb() }
                return true
            }
        }
        web = w
        ready = false
        addView(w, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        w.loadUrl(url(character))
    }

    private fun push() {
        if (!ready) return
        if (sentChar == character) return
        sentChar = character
        js(switchJs(character))
    }

    private fun js(code: String) {
        try { web?.evaluateJavascript(code, null) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) }
    }

    /** दिख रहा हो तभी आवाज़ पढ़ना (~30 बार/सेकंड) */
    private val tick = object : Runnable {
        override fun run() {
            looping = false
            val src = level ?: return
            if (!isAttachedToWindow || !isShown || paused) return
            updateVoiceAmplitude(try { src() } catch (_: Exception) { 0f })
            looping = true
            postDelayed(this, TICK_MS)
        }
    }
    private fun schedule() {
        if (looping || level == null || !isAttachedToWindow || !isShown || paused) return
        looping = true
        postDelayed(tick, TICK_MS)
    }

    private fun setRunning(on: Boolean) {
        val p = !on
        if (p == paused && web != null) {
            if (on) schedule()
            return
        }
        paused = p
        if (on) {
            ensureWeb()
            web?.onResume()
            js("window.setPaused&&window.setPaused(false)")
            schedule()
        } else {
            js("window.setPaused&&window.setPaused(true)")
            web?.onPause()
            removeCallbacks(tick); looping = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        paused = !isShown
        if (isShown) { ensureWeb(); schedule() }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isAttachedToWindow) setRunning(isVisible)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick); looping = false
        release()
        super.onDetachedFromWindow()
    }

    /** WebView पूरी तरह बंद (स्क्रीन बंद होते समय अपने आप) - फिर दिखे तो नया बनता है */
    fun release() {
        val w = web ?: return
        web = null; ready = false; sentChar = ""; sentLevel = -1f
        try { removeView(w); w.stopLoading(); w.destroy() } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) }
    }

    // ORB सिर्फ़ दिखता है: छूना / टैप पीछे वाले डिब्बे (होम का ORB बटन) तक जाए, WebView न ले
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    /** बाहर से साइज़ न मिले (wrap_content) तो 280dp का गोल ORB डिब्बा */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val def = (DEFAULT_DP * resources.displayMetrics.density).toInt()
        val w = pick(widthMeasureSpec, def)
        val h = pick(heightMeasureSpec, def)
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    private fun pick(spec: Int, def: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> minOf(def, MeasureSpec.getSize(spec))
        else -> def
    }

    companion object {
        const val FEMALE = "female"
        const val MALE = "male"
        const val DEFAULT_DP = 280f
        const val HOST = "appassets.androidplatform.net"
        const val PAGE = "https://$HOST/assets/orb/orb_avatar.html"
        private const val TICK_MS = 33L

        fun norm(type: String?): String = if (type?.trim()?.lowercase(Locale.US) == MALE) MALE else FEMALE
        fun url(type: String?): String = PAGE + "?c=" + norm(type)
        fun clampLevel(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
        /** आवाज़ थोड़ी भी बदली हो (या चुप हो गई) तभी भेजो */
        fun changed(old: Float, new: Float): Boolean = old < 0f || abs(old - new) >= 0.01f || (new == 0f && old != 0f)
        fun levelJs(v: Float): String = String.format(Locale.US, "window.setAudioLevel&&window.setAudioLevel(%.3f)", clampLevel(v))
        fun switchJs(type: String?): String = "window.switchCharacter&&window.switchCharacter('" + norm(type) + "')"

        /** Settings की टाइल के लिए छोटी झलक: काले गोले में चमकता अंडाकार चेहरा + कंधे (बिना WebView) */
        fun thumb(sizePx: Int, female: Boolean): Bitmap {
            val s = sizePx.coerceAtLeast(16)
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val f = s.toFloat()
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(f / 2, f / 2, f / 2, intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(f / 2, f / 2, f / 2, p)
            val cyan = 0xFF8CE8F5.toInt(); val ice = 0xFFE0FAFF.toInt()
            // कंधे
            val sw = if (female) 0.30f else 0.40f
            p.shader = RadialGradient(f / 2, f * 0.74f, f * sw * 1.3f, intArrayOf(0x668CE8F5, 0x228CE8F5, 0x008CE8F5), null, Shader.TileMode.CLAMP)
            c.drawOval(RectF(f * (0.5f - sw), f * 0.66f, f * (0.5f + sw), f * 1.15f), p)
            // बाल (महिला: लंबी लटें; पुरुष: छोटे)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            p.color = 0x668CE8F5; p.strokeWidth = f * 0.035f
            if (female) {
                c.drawLine(f * 0.38f, f * 0.30f, f * 0.33f, f * 0.70f, p)
                c.drawLine(f * 0.62f, f * 0.30f, f * 0.67f, f * 0.70f, p)
            } else c.drawArc(RectF(f * 0.385f, f * 0.215f, f * 0.615f, f * 0.50f), 195f, 150f, false, p)
            // चेहरा: चमकता अंडाकार, अंदर रोशनी
            p.style = Paint.Style.FILL; p.color = Color.WHITE   // shader पर paint का alpha न चढ़े
            val fw = if (female) 0.10f else 0.11f
            p.shader = RadialGradient(f / 2, f * 0.37f, f * 0.16f, intArrayOf(ice, cyan and 0x99FFFFFF.toInt(), cyan and 0x11FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawOval(RectF(f * (0.5f - fw), f * 0.235f, f * (0.5f + fw), f * 0.505f), p)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = f * 0.012f; p.color = ice and 0xCCFFFFFF.toInt()
            c.drawOval(RectF(f * (0.5f - fw), f * 0.235f, f * (0.5f + fw), f * 0.505f), p)
            return b
        }
    }
}
