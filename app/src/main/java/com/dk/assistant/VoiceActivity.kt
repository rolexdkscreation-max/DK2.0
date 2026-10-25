package com.dk.assistant

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView

/**
 * Awaaz wali screen: khulte hi live baatcheet shuru (jaise insaan se baat karte hain).
 * Beech me bada orb / chehra, neeche live likha hua (aap kya bole, DK kya bola).
 *  ✕ = live band karke wapas.  Back = DK background me sunta rahega (chhota bubble).
 */
class VoiceActivity : AppCompatActivity() {

    private lateinit var orb: OrbView
    private lateinit var avatar: com.dk.assistant.avatar.AvatarView
    private lateinit var state: TextView
    private lateinit var you: TextView
    private lateinit var dk: TextView
    private lateinit var srcBox: FrameLayout
    private var srcShown: List<Pair<String, String>>? = null
    private lateinit var mic: ImageButton
    private lateinit var orbBox: FrameLayout
    private lateinit var minBtn: ImageButton
    // v3.40: DK लाइव को दिखाना - स्क्रीन शेयर / कैमरा (दोनों बंद से शुरू)
    private lateinit var shareBtn: ImageButton
    private lateinit var camBtn: ImageButton
    private lateinit var camBox: FrameLayout
    private lateinit var camView: PreviewView
    private var feed: CameraFeed? = null
    private var camAsked = false
    private val visionListener: () -> Unit = { renderVision(); syncCamera() }
    private val dp get() = resources.displayMetrics.density
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // आवाज़ स्क्रीन: पूरी काली - पीछे ऐप (aurora/चैट/बटन) नहीं, सिर्फ़ आवाज़ का चलता गोला + बाहर निकलने का बटन
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * dp).toInt(); setPadding(p, p, p, p)
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ---- upar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "${Prefs.assistantName(this@VoiceActivity)} • आवाज़"; textSize = 22f
            setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        })
        titles.addView(TextView(this).apply {
            text = "बोलिए, मैं सुन रहा हूँ — जैसे आमने-सामने बात"; textSize = 12f; setTextColor(0x99FFFFFF.toInt())
        })
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        fun topBtn(icon: Int, desc: String, click: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = desc
            setOnClickListener { click() }
        }.also { top.addView(it, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() }) }
        shareBtn = topBtn(R.drawable.ic_screen_share, "स्क्रीन शेयर चालू/बंद") { onShareTap() }
        camBtn = topBtn(R.drawable.ic_camera, "कैमरा चालू/बंद") { onCamTap() }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_face); setBackgroundResource(R.drawable.glass_circle)
            val p = (13 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "चेहरा दिखाओ"
            setOnClickListener { if (System.currentTimeMillis() < DKState.faceUntil) DKState.hideFace() else DKState.showFace() }
        }, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        col.addView(top)
        top.visibility = android.view.View.GONE     // बटन/शीर्षक नहीं (स्क्रीन शेयर/कैमरा आवाज़ से अब भी चलते हैं)

        // ---- beech me orb
        orb = OrbView(this).apply {
            contentDescription = "ओर्ब"
            setOnClickListener {
                if (System.currentTimeMillis() < DKState.faceUntil) DKState.hideFace() else toggleLive()
            }
        }
        // orb + kone me minimize button
        orbBox = FrameLayout(this)
        orbBox.addView(orb, FrameLayout.LayoutParams(-1, -1))
        minBtn = ImageButton(this).apply {
            setBackgroundResource(R.drawable.glass_circle)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener { setOrbMin(!Prefs.orbMin(this@VoiceActivity)) }
        }
        orbBox.addView(minBtn, FrameLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
            topMargin = (6 * dp).toInt()
        })
        minBtn.visibility = android.view.View.GONE
        // कैमरा चालू हो तो orb की जगह preview (जो DK देख रहा है वही आपको भी दिखे) + कैमरा बदलने का बटन
        camBox = FrameLayout(this).apply {
            visibility = android.view.View.GONE
            setBackgroundResource(R.drawable.glass_card); clipToOutline = true
        }
        camView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
        camBox.addView(camView, FrameLayout.LayoutParams(-1, -1))
        camBox.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_flip); setBackgroundResource(R.drawable.glass_circle)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "कैमरा बदलें (आगे/पीछे)"
            setOnClickListener { LiveVision.flipCamera(this@VoiceActivity) }
        }, FrameLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            val m = (10 * dp).toInt(); setMargins(m, m, m, m)
        })
        orbBox.addView(camBox, 1, FrameLayout.LayoutParams(-1, -1).apply { val m = (8 * dp).toInt(); setMargins(m, m, m, m) })
        // v3.71: लड़की अवतार - ORB की जगह (कैमरा चालू हो तो उसके नीचे)
        avatar = com.dk.assistant.avatar.AvatarView(this).apply {
            visibility = android.view.View.GONE
            setOnClickListener { toggleLive() }
        }
        orbBox.addView(avatar, 1, FrameLayout.LayoutParams(-1, -1))
        col.addView(orbBox, LinearLayout.LayoutParams(-1, 0, 1f))

        state = TextView(this).apply {
            setBackgroundResource(R.drawable.glass_pill); setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            val h = (18 * dp).toInt(); val v = (8 * dp).toInt(); setPadding(h, v, h, v)
        }
        // सुनना/सोचना/बोलना अब orb खुद दिखाता है - अलग पट्टी/लेबल नहीं
        state.visibility = android.view.View.GONE
        col.addView(state, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // ---- live likha hua
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }
        you = TextView(this).apply { textSize = 14f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; UiLang.keep(this) }
        dk = TextView(this).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END; UiLang.keep(this) }
        card.addView(you); card.addView(dk, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })
        // v3.9: jawab web search se aaya ho to caption ke neeche sources (bole nahi jaate, sirf dikhte/click hote hain)
        srcBox = FrameLayout(this)
        card.addView(srcBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        card.visibility = android.view.View.GONE    // पीछे चैट/लिखावट नहीं
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })

        // ---- neeche: mic + band
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic); val p = (20 * dp).toInt(); setPadding(p, p, p, p)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; contentDescription = "माइक चालू/बंद"
            setOnClickListener { toggleLive() }
        }
        mic.visibility = android.view.View.GONE     // orb छूने से ही Live चालू/बंद
        bar.addView(mic, LinearLayout.LayoutParams((76 * dp).toInt(), (76 * dp).toInt()))
        // DK AI: माइक म्यूट/चालू (होम वाले बटन जैसा ही - DKState.micMuted)
        val muteBtn = ImageButton(this).apply {
            fun show() {
                setImageResource(if (DKState.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
                contentDescription = UiLang.s(if (DKState.micMuted) "माइक चालू करें" else "माइक म्यूट करें")
            }
            show()
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x00000000); setStroke((1 * dp).toInt().coerceAtLeast(1), 0x66FFFFFF)
            }
            val p = (16 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                val muted = !DKState.micMuted
                DKState.setMicMuted(muted); show()
                UiLang.toast(this@VoiceActivity, if (muted) "माइक म्यूट - DK अभी कुछ नहीं सुनेगा" else "माइक चालू - DK फिर से सुन रहा है", Toast.LENGTH_SHORT).show()
            }
        }
        bar.addView(muteBtn, LinearLayout.LayoutParams((60 * dp).toInt(), (60 * dp).toInt()).apply { marginEnd = (16 * dp).toInt() })
        // बाहर निकलने का एक ही साफ़ बटन (काली स्क्रीन पर पतला सफ़ेद घेरा)
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x26FFFFFF); setStroke((1 * dp).toInt().coerceAtLeast(1), 0x66FFFFFF)
            }
            val p = (16 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "बंद करें"
            setOnClickListener { DKAccessibilityService.instance?.stopLive("✕ बटन"); finish() }
        }, LinearLayout.LayoutParams((60 * dp).toInt(), (60 * dp).toInt()))
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })

        setContentView(root)
        Glass.install(this)
        applyOrbMin()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (DKState.live) UiLang.toast(this@VoiceActivity,
                    "${Prefs.assistantName(this@VoiceActivity)} पीछे से सुनता रहेगा • बंद करने के लिए ✕ या \"माइक बंद\"", Toast.LENGTH_LONG).show()
                finish()
            }
        })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra("camera", false) == true) { syncCamera(); return }   // Live ने कैमरा माँगा (Live पहले से चालू)
        if (i?.getBooleanExtra("face", false) == true) {
            if (System.currentTimeMillis() >= DKState.faceUntil) DKState.showFace()
            return     // chehra dekhne aaye hain: mic apne aap chalu nahi
        }
        startLiveIfReady()
    }

    private fun startLiveIfReady() {
        val svc = DKAccessibilityService.instance
        when {
            !Setup.hasMic(this) -> UiLang.toast(this, "माइक की परमिशन दीजिए (सेटिंग्स → परमिशन)", Toast.LENGTH_LONG).show()
            svc == null -> {
                if (!Setup.selfEnableAccessibility(this)) {
                    UiLang.toast(this, "सेटिंग्स → Accessibility में ${Prefs.assistantName(this)} ON करें", Toast.LENGTH_LONG).show()
                }
            }
            else -> svc.startLive()     // wake word wala chhota Live chal raha ho to use pakka karo
        }
    }

    /** Orb chhota/bada. Chhota = upar chhota orb, neeche zyada likha hua dikhe. */
    private fun setOrbMin(min: Boolean) {
        Prefs.edit(this) { putBoolean("orb_min", min) }
        applyOrbMin()
    }

    private fun applyOrbMin() {
        // आवाज़ स्क्रीन पर orb हमेशा बड़ा (नीचे लिखावट का card नहीं दिखता)
        val min = false
        orbBox.layoutParams = (orbBox.layoutParams as LinearLayout.LayoutParams).apply {
            if (min) { height = (130 * dp).toInt(); weight = 0f } else { height = 0; weight = 1f }
        }
        // chhota orb ho to baatcheet ka card bada (bache hue jagah me)
        (you.parent as? android.view.View)?.let { card ->
            card.layoutParams = (card.layoutParams as LinearLayout.LayoutParams).apply {
                if (min) { height = 0; weight = 1f } else { height = -2; weight = 0f }
            }
        }
        you.maxLines = if (min) 4 else 2
        dk.maxLines = if (min) 200 else 5
        dk.ellipsize = if (min) null else android.text.TextUtils.TruncateAt.END
        dk.movementMethod = if (min) android.text.method.ScrollingMovementMethod.getInstance() else null
        dk.layoutParams = (dk.layoutParams as LinearLayout.LayoutParams).apply {
            if (min) { height = 0; weight = 1f } else { height = -2; weight = 0f }
        }
        minBtn.setImageResource(if (min) R.drawable.ic_maximize else R.drawable.ic_minimize)
        minBtn.contentDescription = if (min) "ओर्ब बड़ा करें" else "ओर्ब छोटा करें"
        orbBox.requestLayout()
    }

    /** v3.71: "लड़की अवतार" चुना और चित्र हैं = ORB की जगह अवतार (पूरी जगह, नीचे सबटाइटल वैसे ही) */
    private fun applyAvatar() {
        val girl = Prefs.avatarKind(this) == "girl" && com.dk.assistant.avatar.AvatarAssets.available(this)
        avatar.visibility = if (girl) android.view.View.VISIBLE else android.view.View.GONE
        orb.visibility = if (girl) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    private fun toggleLive() {
        if (DKState.live) DKAccessibilityService.instance?.stopLive("आवाज़ स्क्रीन का माइक बटन") else startLiveIfReady()
    }

    override fun onResume() { super.onResume(); applyAvatar(); DKState.listen(listener); LiveVision.listen(visionListener); render(); renderVision(); syncCamera() }
    override fun onPause() { DKState.unlisten(listener); LiveVision.unlisten(visionListener); super.onPause() }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() {
        visible = false
        // screen छोड़ी = कैमरा बंद (Android पीछे कैमरा नहीं देता). permission popup पर onStop नहीं आता.
        if (feed?.running == true || LiveVision.cameraWanted) { feed?.stop(); LiveVision.onCameraStopped("आवाज़ स्क्रीन छोड़ी") }
        super.onStop()
    }
    override fun onDestroy() { feed?.release(); feed = null; super.onDestroy() }

    // ---------------------------------------------------------------- v3.40: स्क्रीन शेयर / कैमरा
    private fun onShareTap() {
        if (!DKState.live || DKAccessibilityService.instance?.gemLive?.canTalk() != true) {
            UiLang.toast(this, "पहले DK लाइव चालू करें (माइक दबाएँ) — स्क्रीन शेयर DK लाइव के साथ चलता है", Toast.LENGTH_LONG).show(); return
        }
        val turningOn = !(LiveVision.screenOn || LiveVision.asking)
        val r = LiveVision.toggleScreen(this)
        if (turningOn && r.startsWith("This Live model")) UiLang.toast(this, "यह Gemini Live मॉडल तस्वीर नहीं लेता — सेटिंग्स में दूसरा मॉडल चुनें", Toast.LENGTH_LONG).show()
        else if (turningOn) UiLang.toast(this, "Android पूछेगा — \"Start / शुरू करें\" दबाते ही DK आपकी स्क्रीन देखेगा", Toast.LENGTH_SHORT).show()
    }

    private fun onCamTap() {
        if (!DKState.live || DKAccessibilityService.instance?.gemLive?.canTalk() != true) {
            UiLang.toast(this, "पहले DK लाइव चालू करें (माइक दबाएँ) — कैमरा DK लाइव के साथ चलता है", Toast.LENGTH_LONG).show(); return
        }
        camAsked = false
        val r = LiveVision.toggleCamera(this)
        if (r.startsWith("This Live model")) UiLang.toast(this, "यह Gemini Live मॉडल तस्वीर नहीं लेता — सेटिंग्स में दूसरा मॉडल चुनें", Toast.LENGTH_LONG).show()
    }

    private fun renderVision() {
        fun on(b: ImageButton, active: Boolean) {
            b.setBackgroundResource(if (active) R.drawable.btn_live_circle else R.drawable.glass_circle)
            b.imageTintList = ColorStateList.valueOf(if (active) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
            b.alpha = if (DKState.live) 1f else 0.55f
        }
        on(shareBtn, LiveVision.screenOn || LiveVision.asking)
        on(camBtn, LiveVision.cameraWanted || LiveVision.cameraOn)
        camBox.visibility = if (LiveVision.cameraWanted || LiveVision.cameraOn) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** LiveVision की चाह -> असली कैमरा */
    private fun syncCamera() {
        if (isFinishing || isDestroyed) return
        val f = feed
        if (!LiveVision.cameraWanted) {
            if (f?.running == true) { f.stop(); LiveVision.onCameraStopped("बंद किया") }
            renderVision(); return
        }
        if (!visible) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!camAsked) { camAsked = true; requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAM) }
            return
        }
        if (f?.running == true && f.front == LiveVision.cameraFront) return
        val feed2 = f ?: CameraFeed(this, camView).also { feed = it }
        val front = LiveVision.cameraFront
        feed2.start(front,
            onOk = { LiveVision.onCameraStarted(front) },
            onFail = { why -> UiLang.toast(this, "कैमरा नहीं खुला: $why", Toast.LENGTH_LONG).show(); LiveVision.onCameraStopped(why) })
        renderVision()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAM) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) syncCamera()
        else { UiLang.toast(this, "कैमरा की इजाज़त नहीं मिली", Toast.LENGTH_LONG).show(); LiveVision.onCameraStopped("कैमरा की इजाज़त नहीं") }
    }

    private fun render() {
        val name = Prefs.assistantName(this)
        val live = DKState.live
        mic.setBackgroundResource(if (live) R.drawable.btn_live_circle else R.drawable.btn_accent_circle)
        if (::shareBtn.isInitialized) renderVision()
        // laal (Live) kaanch par gulabi icon na dikhe - tab safed; warna apna rang
        mic.imageTintList = if (live) android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt()) else null
        UiLang.setText(state, when (DKState.mode) {
            Mode.IDLE -> if (live) "माइक चालू है • बोलिए" else "माइक बंद • ओर्ब या माइक दबाइए"
            Mode.LISTENING -> "Listening • सुन रहा हूँ…"
            Mode.THINKING -> "जवाब आ रहा है…"
            Mode.WORKING -> "काम कर रहा हूँ…"
            Mode.SPEAKING -> "Speaking • बोल रहा हूँ…"
        })
        // bolte-bolte naye shabd aakhir me judte hain - lamba ho to aakhri hissa dikhao (naye shabd chhupe nahi)
        val min = Prefs.orbMin(this)
        UiLang.setText(you, UiLang.s("आप:") + " " + tail(DKState.lastUser, if (min) 400 else 90).ifBlank { "—" })
        UiLang.setText(dk, "$name: " + tail(DKState.lastReply, if (min) 100_000 else 200).ifBlank { "—" })
        val src = DKState.lastSources
        if (src != srcShown) {
            srcShown = src
            srcBox.removeAllViews()
            SourcesView.build(this, src)?.let { srcBox.addView(it) }
            srcBox.visibility = if (src.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    companion object {
        private const val REQ_CAM = 4209
        /** आवाज़ स्क्रीन सामने है (कैमरा सिर्फ़ तभी) */
        @Volatile var visible = false
            private set
    }

    private fun tail(t: String, max: Int): String {
        if (t.length <= max) return t
        val cut = t.substring(t.length - max)
        val sp = cut.indexOf(' ')
        return "…" + if (sp in 0..20) cut.substring(sp + 1) else cut
    }
}
