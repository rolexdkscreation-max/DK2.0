package com.dk.assistant

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** DK ki yaadein: jo baatein "याद रखो" bolkar sikhayi, unhe dekho / jodo / hatao. Chat aur baatcheet bhi mita sakte hain. */
class MemoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var info: TextView
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // ---- upar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        titles.addView(TextView(this).apply {
            text = "मेमोरी"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        })
        info = TextView(this).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()) }
        titles.addView(info)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(top)

        // ---- nayi baat jodo
        val addCard = card(col, "नई बात याद कराएँ", "या बोलिए: \"याद रखो मेरी गाड़ी का नंबर …\"")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val edt = EditText(this).apply {
            hint = "जैसे: मेरी बहन का नाम प्रिया है"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF)
            textSize = 15f; setBackgroundResource(R.drawable.glass_input)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
        }
        row.addView(edt, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "जोड़ें"; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_accent)
            val p = (14 * dp).toInt(); setPadding(p, p, p, p)
            setOnClickListener {
                val t = edt.text.toString().trim()
                if (Memory.addFact(this@MemoryActivity, t)) { edt.setText(""); toast("याद रख लिया"); refresh() }
                else toast("थोड़ा और लिखिए")
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * dp).toInt() })
        addCard.addView(row)

        // ---- yaadon ki list
        val listCard = card(col, "मुझे ये बातें याद हैं", "किसी बात को हटाने के लिए ✕ दबाइए")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(list)

        // ---- saaf karna
        val clean = card(col, "साफ़ करें", "")
        clean.addView(button("सारी यादें मिटाएँ") {
            confirm("सारी याद रखी बातें मिटा दें?") { Memory.clearFacts(this); refresh(); toast("सारी यादें मिटा दीं") }
        })
        clean.addView(button("चैट की बातचीत मिटाएँ") {
            confirm("चैट स्क्रीन की पूरी बातचीत मिटा दें?") { DKState.clearChat(); refresh(); toast("चैट साफ़") }
        })
        clean.addView(button("नई बातचीत (AI पिछली बातें भूले)") {
            Memory.clearHistory(this); toast("ठीक है, अब नई बातचीत शुरू")
        })

        setContentView(root)
        Glass.install(this)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val facts = Memory.facts(this)
        info.text = "${facts.size} बातें याद • ${DKState.messages.size} चैट मैसेज"
        if (facts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "अभी कुछ याद नहीं है। ऊपर लिखकर जोड़िए या बोलिए \"याद रखो …\""
                textSize = 14f; setTextColor(0x99FFFFFF.toInt())
            })
            return
        }
        facts.reversed().forEach { f ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.glass_row)
                val p = (12 * dp).toInt(); setPadding(p + (4 * dp).toInt(), p / 2, p / 2, p / 2)
            }
            r.addView(TextView(this).apply { text = f; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); UiLang.keep(this) },
                LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(TextView(this).apply {
                text = "✕"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(0xCCFFFFFF.toInt())
                contentDescription = "हटाएँ"
                setOnClickListener { Memory.removeFact(this@MemoryActivity, f); refresh() }
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
            list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        }
    }

    private fun card(parent: LinearLayout, title: String, sub: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }
        c.addView(TextView(this).apply { text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
        if (sub.isNotBlank()) c.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, (2 * dp).toInt(), 0, (10 * dp).toInt()) })
        else c.addView(android.view.View(this), LinearLayout.LayoutParams(1, (8 * dp).toInt()))
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })
        return c
    }

    private fun button(t: String, on: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.glass_pill)
        val p = (13 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() }
        setOnClickListener { on() }
    }

    private fun confirm(q: String, yes: () -> Unit) {
        UiDialog(this).setMessage(q).setPositiveButton("हाँ") { _, _ -> yes() }.setNegativeButton("नहीं", null).show()
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()
}
