package com.dk.assistant

import android.content.Intent
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

/**
 * Awaaz wali screen: khulte hi live baatcheet shuru (jaise insaan se baat karte hain).
 * Beech me bada orb / chehra, neeche live likha hua (aap kya bole, DK kya bola).
 *  ✕ = live band karke wapas.  Back = DK background me sunta rahega (chhota bubble).
 */
class VoiceActivity : AppCompatActivity() {

    private lateinit var orb: OrbView
    private lateinit var state: TextView
    private lateinit var you: TextView
    private lateinit var dk: TextView
    private lateinit var mic: ImageButton
    private lateinit var orbBox: FrameLayout
    private lateinit var minBtn: ImageButton
    private val dp get() = resources.displayMetrics.density
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
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
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_face); setBackgroundResource(R.drawable.glass_circle)
            val p = (13 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "चेहरा दिखाओ"
            setOnClickListener { if (System.currentTimeMillis() < DKState.faceUntil) DKState.hideFace() else DKState.showFace() }
        }, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        col.addView(top)

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
        col.addView(orbBox, LinearLayout.LayoutParams(-1, 0, 1f))

        state = TextView(this).apply {
            setBackgroundResource(R.drawable.glass_pill); setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            val h = (18 * dp).toInt(); val v = (8 * dp).toInt(); setPadding(h, v, h, v)
        }
        col.addView(state, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // ---- live likha hua
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }
        you = TextView(this).apply { textSize = 14f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        dk = TextView(this).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END }
        card.addView(you); card.addView(dk, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })

        // ---- neeche: mic + band
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic); val p = (20 * dp).toInt(); setPadding(p, p, p, p)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; contentDescription = "माइक चालू/बंद"
            setOnClickListener { toggleLive() }
        }
        bar.addView(mic, LinearLayout.LayoutParams((76 * dp).toInt(), (76 * dp).toInt()))
        bar.addView(TextView(this).apply {
            text = "✕"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.glass_circle); contentDescription = "बंद करें"
            setOnClickListener { DKAccessibilityService.instance?.stopLive(); finish() }
        }, LinearLayout.LayoutParams((60 * dp).toInt(), (60 * dp).toInt()).apply { marginStart = (28 * dp).toInt() })
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })

        setContentView(root)
        applyOrbMin()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (DKState.live) Toast.makeText(this@VoiceActivity,
                    "${Prefs.assistantName(this@VoiceActivity)} पीछे से सुनता रहेगा • बंद करने के लिए ✕ या \"माइक बंद\"", Toast.LENGTH_LONG).show()
                finish()
            }
        })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra("face", false) == true) {
            if (System.currentTimeMillis() >= DKState.faceUntil) DKState.showFace()
            return     // chehra dekhne aaye hain: mic apne aap chalu nahi
        }
        startLiveIfReady()
    }

    private fun startLiveIfReady() {
        val svc = DKAccessibilityService.instance
        when {
            !Setup.hasMic(this) -> Toast.makeText(this, "माइक की परमिशन दीजिए (सेटिंग्स → परमिशन)", Toast.LENGTH_LONG).show()
            svc == null -> {
                if (!Setup.selfEnableAccessibility(this)) {
                    Toast.makeText(this, "सेटिंग्स → Accessibility में ${Prefs.assistantName(this)} ON करें", Toast.LENGTH_LONG).show()
                }
            }
            !DKState.live -> svc.startLive()
        }
    }

    /** Orb chhota/bada. Chhota = upar chhota orb, neeche zyada likha hua dikhe. */
    private fun setOrbMin(min: Boolean) {
        Prefs.edit(this) { putBoolean("orb_min", min) }
        applyOrbMin()
    }

    private fun applyOrbMin() {
        val min = Prefs.orbMin(this)
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

    private fun toggleLive() {
        if (DKState.live) DKAccessibilityService.instance?.stopLive() else startLiveIfReady()
    }

    override fun onResume() { super.onResume(); DKState.listen(listener); render() }
    override fun onPause() { DKState.unlisten(listener); super.onPause() }

    private fun render() {
        val name = Prefs.assistantName(this)
        val live = DKState.live
        mic.setBackgroundResource(if (live) R.drawable.btn_live_circle else R.drawable.btn_accent_circle)
        state.text = when (DKState.mode) {
            Mode.IDLE -> if (live) "माइक चालू है • बोलिए" else "माइक बंद • ओर्ब या माइक दबाइए"
            Mode.LISTENING -> "Listening • सुन रहा हूँ…"
            Mode.THINKING -> "जवाब आ रहा है…"
            Mode.WORKING -> "काम कर रहा हूँ…"
            Mode.SPEAKING -> "Speaking • बोल रहा हूँ…"
        }
        // bolte-bolte naye shabd aakhir me judte hain - lamba ho to aakhri hissa dikhao (naye shabd chhupe nahi)
        val min = Prefs.orbMin(this)
        you.text = "आप: " + tail(DKState.lastUser, if (min) 400 else 90).ifBlank { "—" }
        dk.text = "$name: " + tail(DKState.lastReply, if (min) 100_000 else 200).ifBlank { "—" }
    }

    private fun tail(t: String, max: Int): String {
        if (t.length <= max) return t
        val cut = t.substring(t.length - max)
        val sp = cut.indexOf(' ')
        return "…" + if (sp in 0..20) cut.substring(sp + 1) else cut
    }
}
