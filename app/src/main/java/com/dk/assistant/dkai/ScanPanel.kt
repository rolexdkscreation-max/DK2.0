package com.dk.assistant.dkai

import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import com.dk.assistant.CameraFeed
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKState
import com.dk.assistant.HoloScanView
import com.dk.assistant.LensFaces
import com.dk.assistant.LiveVision
import com.dk.assistant.R
import com.dk.assistant.UiLang

/**
 * 23 नवंबर 2026 (मालिक: "स्कैन दबाओ तो स्कैन का हिस्सा वहीं खुले - कोई नई स्क्रीन / विंडो नहीं"):
 * होम के बीच वाली जगह में ही DK लेंस (वही [CameraFeed] + [HoloScanView] + [LensFaces] जो आवाज़ स्क्रीन पर हैं).
 * DK लाइव बंद हो तो चालू करके, जुड़ते ही कैमरा ([LiveVision]) - वही रास्ता, बस अलग विंडो नहीं.
 * टैब छोड़ा / ऐप पीछे गया = कैमरा बंद (Android पीछे कैमरा नहीं देता).
 */
class ScanPanel(private val act: AppCompatActivity, private val startLive: () -> Unit) {
    private val dp = act.resources.displayMetrics.density
    private fun d(v: Float) = (v * dp + 0.5f).toInt()

    val view: FrameLayout = FrameLayout(act).apply {
        background = com.dk.assistant.ui.Clay.card(act)
        clipToOutline = true
        val p = d(6f); setPadding(p, p, p, p)
    }
    private val cam = PreviewView(act).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
    private val holo = HoloScanView(act)
    private val status: TextView
    private val shareBtn: ImageView
    private var faces: LensFaces? = null
    private var feed: CameraFeed? = null
    private var wantJob: Runnable? = null
    private var asked = false
    private val listener: () -> Unit = { view.post { sync() } }

    init {
        view.addView(cam, FrameLayout.LayoutParams(-1, -1))
        view.addView(holo, FrameLayout.LayoutParams(-1, -1))
        val k = com.dk.assistant.ui.ClayTheme.t
        status = TextView(act).apply {
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(k.text)
            background = com.dk.assistant.ui.Clay.chip(act); setPadding(d(14f), d(8f), d(14f), d(8f))
        }
        // 23 नवंबर 2026 (मालिक: "ऊपर दो बटन ज़रूर दिखें और काम करें"): ऊपर-बाएँ स्क्रीन शेयर, ऊपर-दाएँ कैमरा आगे/पीछे.
        // हालत वाली पट्टी इनके बीच (दोनों तरफ़ बटन जितनी जगह छोड़कर)
        view.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = d(12f); leftMargin = d(TOP_BTN_DP + 18f); rightMargin = d(TOP_BTN_DP + 18f)
        })
        shareBtn = topBtn(R.drawable.ic_screen_share, "स्क्रीन शेयर (चालू / बंद)") { shareTap() }
        view.addView(shareBtn, FrameLayout.LayoutParams(d(TOP_BTN_DP), d(TOP_BTN_DP), Gravity.TOP or Gravity.START).apply { val m = d(12f); setMargins(m, m, m, m) })
        view.addView(topBtn(R.drawable.ic_flip, "DK लेंस बदलें (आगे/पीछे)") { flipTap() },
            FrameLayout.LayoutParams(d(TOP_BTN_DP), d(TOP_BTN_DP), Gravity.TOP or Gravity.END).apply { val m = d(12f); setMargins(m, m, m, m) })
    }

    /** ऊपर का गोल बटन (वही उभरा गोल [com.dk.assistant.ui.Clay.circle] जो पहले नीचे वाले पलटने के बटन पर था) */
    private fun topBtn(icon: Int, desc: String, onTap: () -> Unit) = ImageView(act).apply {
        val k = com.dk.assistant.ui.ClayTheme.t
        setImageResource(icon); scaleType = ImageView.ScaleType.FIT_CENTER
        background = com.dk.assistant.ui.Clay.circle(act, 3f); val p = d(10f); setPadding(p, p, p, p)
        imageTintList = android.content.res.ColorStateList.valueOf(k.text)
        contentDescription = desc; isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
    }

    /** कैमरा आगे / पीछे - DK लाइव जुड़ी न हो तो बताओ (चुपचाप कुछ न करना नहीं) */
    private fun flipTap() {
        if (liveReady()) { LiveVision.flipCamera(act); return }
        UiLang.toast(act, "DK लाइव जुड़ते ही कैमरा पलटेगा — थोड़ा रुकिए", Toast.LENGTH_SHORT).show()
        if (!DKState.live) startLive()
    }

    /** स्क्रीन शेयर चालू / बंद - वही रास्ता जो आवाज़ स्क्रीन पर ([LiveVision.toggleScreen]) */
    private fun shareTap() {
        if (!liveReady()) {
            UiLang.toast(act, "DK लाइव जुड़ते ही स्क्रीन शेयर होगी — थोड़ा रुकिए", Toast.LENGTH_SHORT).show()
            if (!DKState.live) { startLive() }
            return
        }
        val on = !(LiveVision.screenOn || LiveVision.asking)
        val r = LiveVision.toggleScreen(act)
        val msg = when {
            on && r.startsWith("This Live model") -> "यह Gemini Live मॉडल तस्वीर नहीं लेता — सेटिंग्स में दूसरा मॉडल चुनें"
            on -> "Android पूछेगा — \"Start / शुरू करें\" दबाते ही DK आपकी स्क्रीन देखेगा"
            else -> "स्क्रीन शेयर बंद"
        }
        UiLang.toast(act, msg, Toast.LENGTH_SHORT).show()
        paintShare()
    }

    /** स्क्रीन शेयर चालू = बटन accent रंग में */
    private fun paintShare() {
        val k = com.dk.assistant.ui.ClayTheme.t
        val on = LiveVision.screenOn || LiveVision.asking
        shareBtn.imageTintList = android.content.res.ColorStateList.valueOf(if (on) k.accent else k.text)
        shareBtn.contentDescription = if (on) "स्क्रीन शेयर बंद करें" else "स्क्रीन शेयर (चालू / बंद)"
    }

    /** अभी होम पर स्कैन दिख रहा है (तब [LiveVision] आवाज़ स्क्रीन नहीं खोलता) */
    var shown = false; private set

    private fun liveReady() = DKState.live && DKAccessibilityService.instance?.gemLive?.canTalk() == true

    private fun say(t: String) { UiLang.setText(status, t); status.visibility = if (t.isBlank()) View.GONE else View.VISIBLE }

    /** टैब खुला: DK लाइव चालू (न हो तो) -> जुड़ते ही पीछे वाला लेंस */
    fun show(front: Boolean = false, userTap: Boolean = false) {
        if (userTap) asked = false         // ऐप लौटने पर (इजाज़त मना की हो) बार-बार न पूछे - टैब फिर दबाने पर ही
        shown = true; showing = true
        tabAct = java.lang.ref.WeakReference(act); current = java.lang.ref.WeakReference(this)
        LiveVision.listen(listener)
        if (faces == null) faces = LensFaces(act.applicationContext, holo)
        want(front)
        sync()
    }

    private fun want(front: Boolean) {
        wantJob?.let { view.removeCallbacks(it) }; wantJob = null
        if (liveReady()) { open(front); return }
        say("DK लाइव चालू हो रही है… जुड़ते ही DK लेंस")
        if (!DKState.live) startLive()
        val until = System.currentTimeMillis() + 20_000
        val r = object : Runnable {
            override fun run() {
                if (!shown) return
                when {
                    liveReady() -> { wantJob = null; open(front) }
                    System.currentTimeMillis() < until -> view.postDelayed(this, 500)
                    else -> { wantJob = null; say("DK लाइव नहीं जुड़ी — माइक दबाकर फिर कोशिश करें") }
                }
            }
        }
        wantJob = r; view.postDelayed(r, 500)
    }

    private fun open(front: Boolean) {
        val r = LiveVision.setCamera(act, true, if (front) "front" else "back")
        if (r.startsWith("This Live model")) say("यह Gemini Live मॉडल तस्वीर नहीं लेता — सेटिंग्स में दूसरा मॉडल चुनें")
        sync()
    }

    /** LiveVision की चाह -> असली कैमरा (आवाज़ स्क्रीन वाला ही तरीका) */
    fun sync() {
        if (!shown || act.isFinishing || act.isDestroyed) return
        paintShare()
        val f = feed
        if (!LiveVision.cameraWanted) {
            if (f?.running == true) { f.stop(); LiveVision.onCameraStopped("स्कैन बंद") }
            holo.clear()
            if (wantJob == null && liveReady()) say("कैमरा बंद है — फिर से स्कैन दबाएँ")
            return
        }
        if (act.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            say("कैमरा की इजाज़त दीजिए")
            if (!asked) { asked = true; act.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAM) }
            return
        }
        if (f?.running == true && f.front == LiveVision.cameraFront) { say(""); return }
        val feed2 = f ?: CameraFeed(act, cam).also { feed = it }
        feed2.onFrameAnalyzed = null
        feed2.onFace = { b -> faces?.frame(b, LiveVision.cameraFront) }
        val front = LiveVision.cameraFront
        feed2.start(front,
            onOk = { LiveVision.onCameraStarted(front); view.post { say("") } },
            onFail = { why -> UiLang.toast(act, "DK लेंस नहीं खुला: $why", Toast.LENGTH_LONG).show(); LiveVision.onCameraStopped(why) })
    }

    /** टैब छोड़ा / ऐप पीछे: कैमरा बंद */
    fun hide() {
        if (!shown) return
        shown = false; showing = false
        wantJob?.let { view.removeCallbacks(it) }; wantJob = null
        LiveVision.unlisten(listener)
        if (feed?.running == true || LiveVision.cameraWanted) { feed?.stop(); LiveVision.onCameraStopped("स्कैन छोड़ा") }
        holo.clear()
    }

    /** स्कैन टैब छोड़ा (दूसरा टैब / स्क्रीन बंद) - अब से कैमरा माँगा जाए तो पहले वाली आवाज़ स्क्रीन ही */
    fun leaveTab() {
        hide()
        if (tabAct?.get() === act) { tabAct = null; current = null }
    }

    fun release() { leaveTab(); faces?.stop(); faces = null; feed?.release(); feed = null }

    companion object {
        const val REQ_CAM = 4219
        /** ऊपर के दो बटन (स्क्रीन शेयर, कैमरा पलटना) का आकार dp */
        const val TOP_BTN_DP = 44f
        /** होम का स्कैन सामने है - [LiveVision.setCamera] तब अलग आवाज़ स्क्रीन नहीं खोलता */
        @Volatile var showing = false
            private set
        @Volatile private var tabAct: java.lang.ref.WeakReference<AppCompatActivity>? = null
        @Volatile private var current: java.lang.ref.WeakReference<ScanPanel>? = null

        /**
         * 23 नवंबर 2026 (मालिक: "DK जुड़ा न हो तब स्कैन दबाओ तो पुरानी विंडो भी खुलती है"): होम का स्कैन टैब चुना है और
         * DK स्क्रीन सामने है - कैमरा / इजाज़त की खिड़की के पीछे पल भर रुकी हो तब भी. तब [LiveVision] पुरानी आवाज़
         * स्क्रीन (VoiceActivity) कभी नहीं खोलता; लेंस इसी टैब में खुलता है.
         */
        fun onScreen(): Boolean {
            if (showing) return true
            val a = tabAct?.get() ?: return false
            return !a.isFinishing && !a.isDestroyed && a.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }

        /**
         * 23 नवंबर 2026 (मालिक: "DK लेंस ओपन करो" बोलने पर भी पुरानी विंडो नहीं): DK होम का स्कैन टैब खोलो - लेंस उसी में
         * (टैब खुद DK लाइव जोड़ता है). पुरानी (क्लासिक) होम पर MainActivity पहले वाली आवाज़ स्क्रीन ही खोलती है.
         */
        fun openHome(c: android.content.Context, front: Boolean): Boolean = try {
            c.startActivity(homeIntent(c, front)); true
        } catch (e: Exception) { com.dk.assistant.DKLog.d("स्कैन: होम नहीं खुला: ${e.message}"); false }

        fun homeIntent(c: android.content.Context, front: Boolean): android.content.Intent =
            android.content.Intent(c, com.dk.assistant.MainActivity::class.java)
                .putExtra(com.dk.assistant.MainActivity.EXTRA_TAB, com.dk.assistant.MainActivity.TAB_SCAN)
                .putExtra(com.dk.assistant.MainActivity.EXTRA_FRONT, front)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** स्कैन टैब खुला है तो वहीं फिर से (DK लाइव जोड़कर) लेंस - true = टैब ने सँभाल लिया */
        fun again(front: Boolean): Boolean {
            if (!onScreen()) return false
            val p = current?.get() ?: return false
            p.view.post { if (p.shown) p.show(front) }
            return true
        }
    }
}
