package com.dk.assistant.plus

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.AuroraView
import com.dk.assistant.R
import com.dk.assistant.UiDialog
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 15 नवंबर 2026 (मालिक: "DK Home"): उपकरण जोड़ो/बदलो, webhook से चालू/बंद, और महीने का बिजली बिल अनुमान.
 * कोई paid cloud नहीं - आपके अपने relay/ESP/Home Assistant/IFTTT के URL. "DK Home" स्विच बंद = कुछ नहीं भेजता.
 */
class SmartHomeActivity : AppCompatActivity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var col: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var bill: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        scroll.addView(col); root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        com.dk.assistant.PlainLook.install(this)

        col.addView(TextView(this).apply { text = "DK Home"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
        col.addView(SwitchCompat(this).apply {
            text = "DK Home चालू"; isChecked = FeatureFlags.on(this@SmartHomeActivity, FeatureFlags.SMART_HOME); setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, (10 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { b, v -> if (b.isPressed) FeatureFlags.set(this@SmartHomeActivity, FeatureFlags.SMART_HOME, v) }
        })
        bill = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFD27A.toInt()); setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        col.addView(bill)
        col.addView(button("बिजली का रेट (₹ प्रति यूनिट) बदलें") { editTariff() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        col.addView(button("+ उपकरण जोड़ें") { editItem(null) })
        col.addView(TextView(this).apply {
            textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, (10 * dp).toInt(), 0, 0)
            text = "ON/OFF URL: घर के relay का http://192.168.x.x/... (सिर्फ़ लोकल नेटवर्क) या https:// webhook. " +
                "बिल = वॉट × घंटे ÷ 1000 × रेट. शुरुआत में आपका बताया \"रोज़ कितने घंटे\", फिर असली चलने के समय से।"
        })
        refresh()
    }

    private fun refresh() {
        val items = SmartHome.load(this); val now = System.currentTimeMillis(); val t = SmartHome.tariff(this)
        bill.text = String.format(Locale.US, "महीने का अनुमान: ₹%.0f  (रेट ₹%.2f/यूनिट)", SmartHome.Bill.monthlyCost(items, t, now), t)
        list.removeAllViews()
        if (items.isEmpty()) list.addView(TextView(this).apply { text = "अभी कोई उपकरण नहीं।"; setTextColor(0x99FFFFFF.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0) })
        for (i in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
                val p = (14 * dp).toInt(); setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() }
            }
            val kwh = SmartHome.Bill.monthlyKwh(i, now)
            row.addView(SwitchCompat(this).apply {
                text = String.format(Locale.US, "%s • %.0f W • ~%.1f यूनिट/महीना", i.name, i.watts, kwh)
                isChecked = i.isOn; setTextColor(0xFFFFFFFF.toInt())
                setOnCheckedChangeListener { b, v ->
                    if (!b.isPressed) return@setOnCheckedChangeListener
                    lifecycleScope.launch {
                        val err = SmartHome.switch(this@SmartHomeActivity, i.id, v)
                        if (err != null) { toast(err); b.isChecked = !v } else refresh()
                    }
                }
            })
            row.addView(TextView(this).apply {
                text = "बदलें / हटाएँ"; textSize = 12f; setTextColor(0xFF7AB8FF.toInt()); setPadding(0, (6 * dp).toInt(), 0, 0)
                setOnClickListener { editItem(i) }
            })
            list.addView(row)
        }
    }

    private fun editTariff() {
        val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(SmartHome.tariff(this@SmartHomeActivity).toString()) }
        UiDialog(this).setTitle("₹ प्रति यूनिट (kWh)").setView(e).setPositiveButton("सेव") { _, _ ->
            val v = e.text.toString().toDoubleOrNull()
            if (v == null || v < 0 || v > 100) { toast("सही रेट लिखें (0-100)"); return@setPositiveButton }
            FeatureFlags.putStr(this, SmartHome.TARIFF, v.toString()); refresh()
        }.setNegativeButton("रद्द", null).show()
    }

    private fun editItem(old: SmartHome.Item?) {
        val f = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        val n = EditText(this).apply { hint = "नाम (जैसे: पंखा)"; setText(old?.name.orEmpty()) }
        val w = EditText(this).apply { hint = "वॉट (जैसे: 75)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(old?.watts?.takeIf { it > 0 }?.toString().orEmpty()) }
        val h = EditText(this).apply { hint = "रोज़ कितने घंटे (अनुमान)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(old?.hoursPerDay?.takeIf { it > 0 }?.toString().orEmpty()) }
        val on = EditText(this).apply { hint = "ON URL"; setText(old?.onUrl.orEmpty()) }
        val off = EditText(this).apply { hint = "OFF URL"; setText(old?.offUrl.orEmpty()) }
        val post = SwitchCompat(this).apply { text = "POST भेजें (बंद = GET)"; isChecked = old?.post == true }
        listOf(n, w, h, on, off, post).forEach { f.addView(it) }
        val d = UiDialog(this).setTitle(if (old == null) "नया उपकरण" else "उपकरण बदलें").setView(f)
            .setPositiveButton("सेव") { _, _ ->
                val name = n.text.toString().trim()
                if (name.length < 2) { toast("नाम लिखें"); return@setPositiveButton }
                val all = SmartHome.load(this)
                val now = System.currentTimeMillis()
                val item = (old ?: SmartHome.Item("h$now", name, 0.0, "", "", since = now)).copy(
                    name = name, watts = w.text.toString().toDoubleOrNull() ?: 0.0, hoursPerDay = h.text.toString().toDoubleOrNull() ?: 0.0,
                    onUrl = on.text.toString().trim(), offUrl = off.text.toString().trim(), post = post.isChecked)
                SmartHome.save(this, if (old == null) all + item else all.map { if (it.id == old.id) item else it })
                refresh()
            }.setNegativeButton("रद्द", null)
        if (old != null) d.setNeutralButton("हटाएँ") { _, _ -> SmartHome.save(this, SmartHome.load(this).filter { it.id != old.id }); refresh() }
        d.show()
    }

    private fun button(t: String, on: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
        setBackgroundResource(R.drawable.glass_pill)
        val p = (13 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() }
        setOnClickListener { on() }
    }

    private fun toast(t: String) = com.dk.assistant.UiLang.toast(this, t, android.widget.Toast.LENGTH_SHORT).show()
}
