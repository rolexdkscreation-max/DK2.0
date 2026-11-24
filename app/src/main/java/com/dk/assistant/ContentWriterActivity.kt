package com.dk.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 24 नवंबर 2026 (मालिक: "DK CONTENT WRITING" - Story Writer इसी में मिला). Settings → "DK कंटेंट राइटिंग" (स्विच चालू हो तो).
 * "कहानी" चुनें = पुराना Story Writer पन्ना, बिल्कुल वैसा ही ([StoryWriterActivity] - तरीका नहीं बदला).
 * बाकी प्रकार यहीं: विषय + लंबाई -> साफ़ स्क्रिप्ट + अंदाज़न समय -> "सेव / बदलो / कैप्शन और हैशटैग बनाओ".
 */
class ContentWriterActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(UiFit.tablet(newBase)) }

    private val dp get() = resources.displayMetrics.density
    private var type = ContentPlan.Type.FACTS
    private var sec = ContentPlan.Type.FACTS.defSec
    private var image: Uri? = null
    private var result = ""
    /** सिर्फ़ स्क्रिप्ट (कैप्शन जुड़ने से पहले) - समय इसी का */
    private var script = ""
    private var history = mutableListOf<Pair<String, String>>()
    private lateinit var typeRow: LinearLayout
    private lateinit var lenRow: LinearLayout
    private lateinit var lenBox: LinearLayout
    private lateinit var edt: EditText
    private lateinit var btnImage: Button
    private lateinit var busy: LinearLayout
    private lateinit var out: TextView
    private lateinit var dur: TextView
    private lateinit var outCard: LinearLayout
    private lateinit var ask: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { image = u; btnImage.text = UiLang.s("✓ फ़ोटो चुनी गई (बदलें)") }
    }
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.openOutputStream(uri)?.use { it.write(result.toByteArray()) }; toast("सेव हो गया") }
        catch (e: Exception) { DKLog.d("Content: सेव गलती ${e.message?.take(120)}"); toast("सेव नहीं हुआ, फिर से कोशिश करें") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (14 * dp).toInt(); setPadding(p, p, p, p) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "वापस"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        titles.addView(TextView(this).apply { text = UiLang.s("DK कंटेंट राइटिंग"); textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
        titles.addView(TextView(this).apply { text = UiLang.s("कहानी • फैक्ट्स • स्क्रिप्ट • कैप्शन • सुधार • फ़ोटो से"); textSize = 12f; setTextColor(0x99FFFFFF.toInt()) })
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(top)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt()) }
        scroll.addView(body)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (10 * dp).toInt() })

        val form = card(body)
        form.addView(label("क्या लिखूँ?"))
        typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ContentPlan.Type.values().forEach { t ->
            typeRow.addView(pill(t.hi) { pickType(t) }.apply { tag = t },
                LinearLayout.LayoutParams(-2, (40 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        }
        form.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(typeRow) })
        form.addView(label("विषय / स्क्रिप्ट"))
        edt = EditText(this).apply {
            setBackgroundResource(R.drawable.glass_input)
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF); textSize = 15f
            val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 10; gravity = Gravity.TOP or Gravity.START
        }
        form.addView(edt, LinearLayout.LayoutParams(-1, -2))
        btnImage = pill("फ़ोटो चुनें") { pickImage.launch("image/*") }
        form.addView(btnImage, LinearLayout.LayoutParams(-2, (42 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        lenBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lenBox.addView(label("कितना लंबा?"))
        lenRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ContentPlan.LENGTHS.forEach { s ->
            lenRow.addView(pill("$s सेकंड") { sec = s; paint() }.apply { tag = s },
                LinearLayout.LayoutParams(-2, (40 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        }
        lenBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(lenRow) })
        form.addView(lenBox)
        ask = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFB74D.toInt()); visibility = View.GONE; setPadding(0, (8 * dp).toInt(), 0, 0) }
        form.addView(ask)
        form.addView(Button(this).apply {
            text = UiLang.s("लिखो"); isAllCaps = false; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.btn_accent); setOnClickListener { onWrite() }
        }, LinearLayout.LayoutParams(-1, (50 * dp).toInt()).apply { topMargin = (16 * dp).toInt() })

        busy = card(body).apply { visibility = View.GONE; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        busy.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
        busy.addView(TextView(this).apply { text = UiLang.s("लिख रहा हूँ…"); textSize = 14f; setTextColor(0xFFFFFFFF.toInt()) })

        outCard = card(body).apply { visibility = View.GONE }
        dur = TextView(this).apply { textSize = 13f; setTextColor(0xFF9FD3FF.toInt()) }
        outCard.addView(dur)
        out = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setLineSpacing(0f, 1.25f); setTextIsSelectable(true); setPadding(0, (8 * dp).toInt(), 0, 0) }
        outCard.addView(out)
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (12 * dp).toInt(), 0, 0) }
        acts.addView(pill("सेव") { createDoc.launch("DK Content ${System.currentTimeMillis() / 1000}.txt") }, LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply { marginEnd = (6 * dp).toInt() })
        acts.addView(pill("बदलो") { onModify() }, LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply { marginEnd = (6 * dp).toInt() })
        acts.addView(pill("कैप्शन + हैशटैग") { onCaption() }, LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1.4f))
        outCard.addView(acts)
        outCard.addView(pill("कॉपी") {
            (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("DK", result)); toast("कॉपी हो गया")
        }, LinearLayout.LayoutParams(-2, (40 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })

        setContentView(root)
        PlainLook.install(this)
        pickType(type)
    }

    private fun pickType(t: ContentPlan.Type) {
        // कहानी = पुराना Story Writer, बिल्कुल वैसा ही
        if (t == ContentPlan.Type.STORY) { startActivity(Intent(this, StoryWriterActivity::class.java)); return }
        type = t
        sec = t.defSec
        edt.hint = UiLang.s(when (t) {
            ContentPlan.Type.FIX -> "अपनी स्क्रिप्ट यहाँ डालिए"
            ContentPlan.Type.CAPTION -> "किस वीडियो / पोस्ट का कैप्शन? जैसे: गाँव की सुबह वाली रील"
            ContentPlan.Type.IMAGE -> "(चाहें तो) फ़ोटो के बारे में एक लाइन"
            else -> "जैसे: समुद्र के 5 अजीब राज़"
        })
        btnImage.visibility = if (t == ContentPlan.Type.IMAGE) View.VISIBLE else View.GONE
        lenBox.visibility = if (t.defSec > 0) View.VISIBLE else View.GONE
        ask.visibility = View.GONE
        paint()
    }

    private fun paint() {
        for (i in 0 until typeRow.childCount) typeRow.getChildAt(i).alpha = if (typeRow.getChildAt(i).tag == type) 1f else 0.5f
        for (i in 0 until lenRow.childCount) lenRow.getChildAt(i).alpha = if (lenRow.getChildAt(i).tag == sec) 1f else 0.5f
    }

    private fun onWrite() {
        val topic = edt.text.toString()
        ContentPlan.missing(type, topic, image != null)?.let { ask.text = UiLang.s(it); ask.visibility = View.VISIBLE; return }
        ask.visibility = View.GONE
        val req = ContentPlan.request(type, topic, sec, ContentMemory.recent(this))
        history = mutableListOf("user" to req)
        run(type == ContentPlan.Type.IMAGE)
    }

    private fun onModify() {
        val e = EditText(this).apply { hint = UiLang.s("क्या बदलूँ? जैसे: और छोटा करो, हुक और तगड़ा"); setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt()) }
        UiDialog(this).setTitle("स्क्रिप्ट बदलो").setView(e)
            .setPositiveButton("बदलो") { _, _ ->
                val how = e.text.toString().trim()
                if (how.isNotBlank() && script.isNotBlank()) { history = mutableListOf("user" to ContentPlan.modify(script, how)); run(false) }
            }.setNegativeButton("रद्द", null).show()
    }

    private fun onCaption() {
        if (script.isBlank()) return
        history = mutableListOf("user" to ContentPlan.captionFor(script)); run(false, caption = true)
    }

    private fun run(withImage: Boolean, caption: Boolean = false) {
        busy.visibility = View.VISIBLE
        val img = image
        val msgs = history.toList()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    Result.success(if (withImage && img != null) ContentAi.fromImage(this@ContentWriterActivity, img, msgs.last().second) else ContentAi.write(this@ContentWriterActivity, msgs))
                } catch (e: Exception) { Result.failure(e) }
            }
            busy.visibility = View.GONE
            r.onSuccess { t ->
                if (caption) result = script + "\n\n" + t
                else { script = t; result = t; ContentMemory.add(this@ContentWriterActivity, t) }
                out.text = result
                dur.text = UiLang.s("बोलने में: " + ContentPlan.durationHi(ContentPlan.estimateSec(script)))
                outCard.visibility = View.VISIBLE
            }.onFailure { e -> toast(e.message ?: "गलती हुई, फिर से कोशिश करें") }
        }
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card); val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
        return box
    }

    private fun label(t: String) = TextView(this).apply {
        text = UiLang.s(t); textSize = 14f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun pill(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = UiLang.s(t); isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.glass_pill); minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        val ph = (14 * dp).toInt(); setPadding(ph, (6 * dp).toInt(), ph, (6 * dp).toInt())
        setOnClickListener(onClick)
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()
}
