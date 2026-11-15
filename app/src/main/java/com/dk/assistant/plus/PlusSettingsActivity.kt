package com.dk.assistant.plus

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.AuroraView
import com.dk.assistant.Prefs
import com.dk.assistant.R
import com.dk.assistant.SettingsActivity
import com.dk.assistant.UiDialog
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 15 नवंबर 2026 (मालिक: DK AI मास्टर अपग्रेड): Settings → "DK AI Plus". मुख्य + एडवांस्ड हिस्से [FeatureFlags.Section] से.
 * हर स्विच शुरू में बंद. जो सुविधा इस चरण में अभी काम से नहीं जुड़ी, उसका स्विच बंद (disabled) और "अगला चरण" लिखा -
 * दबाने पर कुछ न करने वाला झूठा स्विच नहीं. पुरानी सुविधाएँ (ओर्ब, टच मोड, फ़ोन लॉक, ऑटो रिप्लाई ...) अपनी पुरानी जगह ही -
 * यहाँ सिर्फ़ वहाँ जाने का रास्ता, दोबारा नहीं बनाईं. कोई license / paywall नहीं.
 */
class PlusSettingsActivity : AppCompatActivity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var col: LinearLayout
    private val watchers = mutableListOf<Job>()

    /** पुरानी (पहले से बनी) सुविधा कहाँ है - [SettingsActivity] का section id */
    private val oldHome = mapOf(
        FeatureFlags.Section.MEMORY to ("voice" to "आवाज़ और याददाश्त (पुरानी सेटिंग)"),
        FeatureFlags.Section.SECRET to ("voice" to "DK का स्वभाव (पुरानी सेटिंग)"),
        FeatureFlags.Section.APPEARANCE to ("orb" to "ओर्ब और चेहरा (पुरानी सेटिंग)"),
        FeatureFlags.Section.BEHAVIOR to ("ui" to "दिखावट (पुरानी सेटिंग)"),
        FeatureFlags.Section.VOICE_GUARDIAN to ("voice" to "मालिक की आवाज़ (पुरानी सेटिंग)"),
        FeatureFlags.Section.TOUCH_GUARD to ("guard" to "टच मोड (पुरानी सेटिंग)"),
        FeatureFlags.Section.SCREEN_LOCK to ("phonelock" to "फ़ोन लॉक / अनलॉक (पुरानी सेटिंग)"),
        FeatureFlags.Section.WA_REPLY to ("autoreply" to "ऑटो रिप्लाई (पुरानी सेटिंग)"),
        FeatureFlags.Section.BATTERY to ("access" to "Accessibility और बैटरी (पुरानी सेटिंग)"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        com.dk.assistant.PlainLook.install(this)
        build()
    }

    override fun onDestroy() { watchers.forEach { it.cancel() }; super.onDestroy() }

    private fun build() {
        header()
        note("यहाँ की हर नई सुविधा शुरू में बंद है। जो चाहिए वही चालू करें। कोई license key, trial या paywall नहीं। " +
            "भारी मॉडल (आवाज़/चेहरा/3D) ऐप में नहीं, ज़रूरत पड़ने पर इंटरनेट से डाउनलोड होते हैं।")

        group("मुख्य सेटिंग्स")
        FeatureFlags.Section.values().filter { !it.advanced }.forEach { section(it) }
        imageCard()

        group("एडवांस्ड सेटिंग्स")
        FeatureFlags.Section.values().filter { it.advanced }.forEach { section(it) }
        modelsCard()
        termuxCard()
        col.addView(button("DK Home (स्मार्ट होम) खोलें") { startActivity(Intent(this, SmartHomeActivity::class.java)) })
    }

    // ---------------------------------------------------------------- हिस्से
    private fun section(s: FeatureFlags.Section) {
        val box = box(s.title, null)
        for (f in FeatureFlags.inSection(s)) {
            val ready = FeatureFlags.ready(f.key)
            val sw = switch(box, if (ready) f.title else "${f.title} (अगला चरण)", ready && FeatureFlags.on(this, f.key)) { FeatureFlags.set(this, f.key, it) }
            sw.isEnabled = ready
            if (f.sub.isNotBlank()) hint(box, f.sub)
        }
        when (s) {
            FeatureFlags.Section.CALL_DRIVE -> {
                val ed = edit(box, "ड्राइविंग SMS ({user_name} = आपका नाम)", FeatureFlags.str(this, DrivingMode.MSG, DrivingMode.DEFAULT_MSG))
                box.addView(button("संदेश सेव करें") { FeatureFlags.putStr(this, DrivingMode.MSG, ed.text.toString().trim().ifBlank { DrivingMode.DEFAULT_MSG }); toast("सेव हो गया") })
                hint(box, "बोलकर: \"ड्राइविंग मोड चालू करो\" / \"ड्राइविंग मोड बंद करो\"")
            }
            FeatureFlags.Section.SOS -> {
                val ed = edit(box, "SOS नंबर (कॉमा से अलग, ज़्यादा से ज़्यादा ${Sos.MAX_CONTACTS})", FeatureFlags.str(this, Sos.CONTACTS))
                ed.inputType = InputType.TYPE_CLASS_PHONE
                box.addView(button("नंबर सेव करें") {
                    val list = Sos.Rules.parse(ed.text.toString())
                    FeatureFlags.putStr(this, Sos.CONTACTS, list.joinToString(", ")); ed.setText(list.joinToString(", "))
                    toast("${list.size} नंबर सेव")
                })
                hint(box, "बोलकर: \"DK, activate SOS\" / \"SOS बंद करो\". SMS + WhatsApp पर लोकेशन, फिर हर नंबर पर 5 बार तक कॉल। SMS, कॉल और लोकेशन की इजाज़त चाहिए।")
            }
            FeatureFlags.Section.SUB_AGENT -> keyPoolEditor(box)
            FeatureFlags.Section.SECRET -> hint(box, "18+ शैली उम्र की पुष्टि के बाद ही खुलेगी और उसमें भी अश्लील सामग्री नहीं बनेगी।")
            else -> {}
        }
        oldHome[s]?.let { (id, t) -> box.addView(button(t) { startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", id)) }) }
    }

    private fun keyPoolEditor(box: LinearLayout) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        fun refresh() {
            list.removeAllViews()
            val all = KeyPool.load(this)
            if (all.isEmpty()) hint(list, "अभी कोई key नहीं।")
            all.forEachIndexed { i, e ->
                list.addView(small("${i + 1}. ${e.name.ifBlank { "key" }} • ${e.model.ifBlank { "-" }}  ✕") {
                    KeyPool.save(this, all.filterIndexed { j, _ -> j != i }); refresh()
                })
            }
        }
        refresh()
        box.addView(button("+ key जोड़ें") {
            val f = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
            val n = EditText(this).apply { hint = "नाम (जैसे: Gemini 2)" }
            val b = EditText(this).apply { hint = "Base URL (https://… OpenAI-compatible)" }
            val k = EditText(this).apply { hint = "API key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val m = EditText(this).apply { hint = "Model" }
            listOf(n, b, k, m).forEach { f.addView(it) }
            UiDialog(this).setTitle("नई key").setView(f).setPositiveButton("सेव") { _, _ ->
                val e = KeyPool.Entry(n.text.toString().trim(), b.text.toString().trim().trimEnd('/'), k.text.toString().trim(), m.text.toString().trim())
                if (!e.usable) { toast("https:// वाला पता और key दोनों चाहिए"); return@setPositiveButton }
                KeyPool.save(this, KeyPool.load(this) + e); refresh()
            }.setNegativeButton("रद्द", null).show()
        })
        hint(box, "Keys फ़ोन के सुरक्षित हिस्से (Android Keystore) में बंद रहती हैं, कभी लॉग या GitHub पर नहीं जातीं।")
    }

    private fun imageCard() {
        val box = box("इमेज बनाना (वैकल्पिक)", "खाली = मुफ़्त Pollinations (बिना key)")
        val b = edit(box, "Base URL (OpenAI-compatible, वैकल्पिक)", Prefs.str(this, ImageGen.BASE, ""))
        val k = edit(box, "API key (वैकल्पिक)", Prefs.str(this, ImageGen.KEY, ""), secret = true)
        val m = edit(box, "Model (वैकल्पिक)", Prefs.str(this, ImageGen.MODEL, ""))
        box.addView(button("सेव करें") {
            Prefs.edit(this) { putString(ImageGen.BASE, b.text.toString().trim()); putString(ImageGen.KEY, k.text.toString().trim()); putString(ImageGen.MODEL, m.text.toString().trim()) }
            toast("सेव हो गया")
        })
        val p = edit(box, "जाँच के लिए prompt", "a cute robot reading a book, digital art")
        val out = hint(box, "")
        box.addView(button("जाँच: एक इमेज बनाओ") {
            out.text = "बन रही है…"
            lifecycleScope.launch {
                val r = ImageGen.generate(this@PlusSettingsActivity, p.text.toString())
                out.text = r.error ?: "बन गई और सेव हो गई ✓"
            }
        })
    }

    private fun modelsCard() {
        val box = box("मॉडल डाउनलोड", "ऐप का साइज़ नहीं बढ़ता - ज़रूरत हो तब ही डाउनलोड")
        ModelManager.refresh(this)
        val used = hint(box, "")
        fun usedText() { used.text = String.format(java.util.Locale.US, "फ़ोन में अभी: %.1f MB", ModelManager.usedBytes(this) / 1_048_576.0) }
        usedText()
        for (a in ModelCatalog.ALL) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, 0) }
            row.addView(TextView(this).apply { text = "${a.title} • ${a.mb}"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD) })
            if (a.sub.isNotBlank()) hint(row, a.sub)
            val st = hint(row, "")
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(small("डाउनलोड") { ModelManager.enqueue(this, a) })
            btns.addView(small("हटाएँ") { ModelManager.delete(this, a); usedText() })
            row.addView(btns)
            box.addView(row)
            watchers += lifecycleScope.launch {
                ModelManager.state(a.id).collect { s ->
                    st.text = when (s) {
                        is ModelManager.St.None -> "डाउनलोड नहीं"
                        is ModelManager.St.Running -> "डाउनलोड हो रहा है… ${s.pct}%"
                        is ModelManager.St.Ready -> "तैयार ✓ (SHA-256 जाँचा)"
                        is ModelManager.St.Failed -> "नहीं हुआ: ${s.why}"
                    }
                    if (s is ModelManager.St.Ready) usedText()
                }
            }
        }
    }

    private fun termuxCard() {
        val box = box("Termux सहायक", "Termux (F-Droid) में एक बार सेटिंग चाहिए")
        hint(box, "Termux में चलाएँ:\nmkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties\nफिर Android Settings → DK AI → अनुमतियाँ → \"Run commands in Termux\" चालू करें।")
        val st = hint(box, "")
        fun status() {
            st.text = "Termux: " + (if (Termux.installed(this)) "इंस्टॉल ✓" else "इंस्टॉल नहीं") + " • इजाज़त: " + (if (Termux.permitted(this)) "मिली ✓" else "नहीं")
        }
        status()
        box.addView(button("इजाज़त माँगें") { requestPermissions(arrayOf(Termux.PERMISSION), 41); status() })
        box.addView(button("जाँच कमांड चलाएँ") {
            if (!FeatureFlags.on(this, FeatureFlags.TERMUX)) { toast("पहले \"Termux ऑटोमेशन\" चालू करें"); return@button }
            toast(Termux.run(this, "echo DK AI > ~/dk_ai_test.txt", background = true) ?: "भेज दिया - Termux में ~/dk_ai_test.txt देखें")
        })
    }

    // ---------------------------------------------------------------- छोटे UI हिस्से
    private fun header() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.glass_card); val hp = (10 * dp).toInt(); setPadding(hp, hp, (14 * dp).toInt(), (12 * dp).toInt())
        }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "वापस"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
        top.addView(TextView(this).apply {
            text = "DK AI Plus"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); setPadding((12 * dp).toInt(), 0, 0, 0)
        })
        col.addView(top)
    }

    private fun group(t: String) = col.addView(TextView(this).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFD27A.toInt())
        setPadding((4 * dp).toInt(), (18 * dp).toInt(), 0, (4 * dp).toInt())
    })

    private fun note(t: String) = col.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), 0)
    })

    private fun box(title: String, sub: String?): LinearLayout =
        com.dk.assistant.ui.CollapsibleSection.add(this, col, title, sub, startOpen = false) {}.body

    private fun switch(box: LinearLayout, t: String, on: Boolean, change: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(this).apply {
            text = t; isChecked = on; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (2 * dp).toInt())
            setOnCheckedChangeListener { b, v -> if (b.isPressed) change(v) }
        }.also { box.addView(it) }

    private fun hint(box: LinearLayout, t: String): TextView =
        TextView(this).apply { text = t; textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding((4 * dp).toInt(), (2 * dp).toInt(), 0, (6 * dp).toInt()) }.also { box.addView(it) }

    private fun edit(box: LinearLayout, label: String, value: String, secret: Boolean = false): EditText {
        hint(box, label)
        return EditText(this).apply {
            setText(value); textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF)
            setBackgroundResource(R.drawable.glass_input); val p = (12 * dp).toInt(); setPadding(p, p, p, p)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }.also { box.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun button(t: String, on: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.glass_pill)
        val p = (13 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() }
        setOnClickListener { on() }
    }

    private fun small(t: String, on: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.glass_pill)
        val p = (9 * dp).toInt(); setPadding(p + p / 2, p, p + p / 2, p)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt(); marginEnd = (6 * dp).toInt() }
        setOnClickListener { on() }
    }

    private fun toast(t: String) = com.dk.assistant.UiLang.toast(this, t, android.widget.Toast.LENGTH_SHORT).show()
}
