package com.dk.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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

/**
 * Story Writer Agent ki apni screen (sirf text - koi awaaz/mic nahi).
 * Vishay + lambai (10-30 min) -> "कहानी लिखो" -> kahani bhaag-dar-bhaag (Part 3 of 9) neeche aati hai.
 * Copy (poori / yeh bhaag), Save (phone: Download/DK Stories), galti par "यह भाग फिर से लिखो".
 * Kaam StorySession me chalta hai - screen band karke wapas aao to bhi kahani wahin milegi.
 */
class StoryWriterActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var sub: TextView
    private lateinit var edtTopic: EditText
    private lateinit var btnWrite: Button
    private lateinit var lengthRow: LinearLayout
    private lateinit var status: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var txtStatus: TextView
    private lateinit var btnRetry: Button
    private lateinit var titleView: TextView
    private lateinit var partsBox: LinearLayout
    private lateinit var actions: LinearLayout
    private var minutes = 10
    private var shown = 0
    private val dp get() = resources.displayMetrics.density

    // Android 9 ya purana: user khud jagah chune (bina permission)
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(StorySession.fullText().toByteArray()) }
            toast("कहानी सेव हो गई")
        } catch (e: Exception) { toast("सेव नहीं हुई: ${e.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        minutes = StorySession.minutes
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (14 * dp).toInt(); setPadding(p, p, p, p) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // upar: wapas + naam
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "वापस"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        titles.addView(TextView(this).apply { text = "स्टोरी राइटर एजेंट"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
        sub = TextView(this).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()); maxLines = 2 }
        titles.addView(sub)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(top)

        scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt()) }
        scroll.addView(body)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (10 * dp).toInt() })

        // vishay + lambai + button
        val form = card(body)
        form.addView(label("किस बारे में कहानी लिखूँ?"))
        edtTopic = EditText(this).apply {
            setText(StorySession.topic)
            hint = "जैसे: बूढ़े पिता और शहर गए बेटे की कहानी (खाली छोड़ें तो एजेंट खुद विषय चुनेगा)"
            setBackgroundResource(R.drawable.glass_input)
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF); textSize = 15f
            val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START
        }
        form.addView(edtTopic, LinearLayout.LayoutParams(-1, -2))
        form.addView(label("कहानी कितनी लंबी हो?"))
        lengthRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        StoryPlan.LENGTHS.forEach { m ->
            lengthRow.addView(pill("$m मिनट") { minutes = m; paintLengths() }.apply { tag = m },
                LinearLayout.LayoutParams(-2, (40 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        }
        form.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(lengthRow) })
        btnWrite = Button(this).apply {
            isAllCaps = false; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.btn_accent)
            setOnClickListener { onWrite() }
        }
        form.addView(btnWrite, LinearLayout.LayoutParams(-1, (50 * dp).toInt()).apply { topMargin = (16 * dp).toInt() })

        // haal: loading + "भाग 3 / 9" + galti + retry
        status = card(body).apply { visibility = View.GONE }
        val sRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        sRow.addView(progress, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
        txtStatus = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()) }
        sRow.addView(txtStatus, LinearLayout.LayoutParams(0, -2, 1f))
        status.addView(sRow)
        btnRetry = pill("यह भाग फिर से लिखो") { StorySession.resume(this) }.apply { visibility = View.GONE }
        status.addView(btnRetry, LinearLayout.LayoutParams(-2, (42 * dp).toInt()).apply { topMargin = (10 * dp).toInt() })

        titleView = TextView(this).apply {
            textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding((8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt(), 0); setTextIsSelectable(true); visibility = View.GONE
        }
        body.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        partsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(partsBox, LinearLayout.LayoutParams(-1, -2))

        // poori kahani: copy + save + delete
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(0, (12 * dp).toInt(), 0, 0) }
        actions.addView(pill("पूरी कहानी कॉपी") { copy(StorySession.fullText(), "पूरी कहानी कॉपी हो गई") }, LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply { marginEnd = (6 * dp).toInt() })
        actions.addView(pill("सेव करें") { save() }, LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply { marginEnd = (6 * dp).toInt() })
        actions.addView(pill("डिलीट") {
            android.app.AlertDialog.Builder(this)
                .setTitle("कहानी डिलीट करें?")
                .setMessage("क्या आप इस कहानी को हटाना चाहते हैं?")
                .setPositiveButton("हाँ, डिलीट करें") { _, _ ->
                    StorySession.parts.clear()
                    StorySession.title = ""
                    StorySession.total = 0
                    StorySession.error = null
                    shown = 0
                    partsBox.removeAllViews()
                    titleView.visibility = View.GONE
                    status.visibility = View.GONE
                    actions.visibility = View.GONE
                    toast("कहानी डिलीट कर दी गई")
                }
                .setNegativeButton("रुको", null)
                .show()
        }, LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f))
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        paintLengths()
        render()
    }

    override fun onResume() { super.onResume(); StorySession.listener = { runOnUiThread { render() } }; render() }
    override fun onPause() { super.onPause(); StorySession.listener = null }

    private fun onWrite() {
        val s = StorySession
        if (s.busy) { s.stop(); return }
        if (StoryAi.fromPrefs(this) == null) {
            toast("पहले सेटिंग्स → AI दिमाग में AI key डालें")
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "brain")); return
        }
        val go = { shown = 0; partsBox.removeAllViews(); s.start(this, edtTopic.text.toString(), minutes) }
        if (s.parts.isNotEmpty()) {
            android.app.AlertDialog.Builder(this).setTitle("नई कहानी लिखें?")
                .setMessage("अभी वाली कहानी स्क्रीन से हट जाएगी। चाहिए तो पहले 'सेव करें' दबाएँ।")
                .setPositiveButton("हाँ, नई लिखो") { _, _ -> go() }.setNegativeButton("रुको", null).show()
        } else go()
    }

    private fun render() {
        val s = StorySession
        sub.text = StoryAi.fromPrefs(this)?.let { "सिर्फ़ text • ${it.label}" } ?: "AI key नहीं — सेटिंग्स → AI दिमाग"
        btnWrite.text = if (s.busy) "रोकें" else "कहानी लिखो"
        edtTopic.isEnabled = !s.busy
        for (i in 0 until lengthRow.childCount) lengthRow.getChildAt(i).isEnabled = !s.busy

        // naye bhaag jodo (pehle wale waise hi rahein)
        if (shown > s.parts.size) { shown = 0; partsBox.removeAllViews() }
        while (shown < s.parts.size) { addPart(shown); shown++ }
        titleView.text = s.title
        titleView.visibility = if (s.title.isNotBlank()) View.VISIBLE else View.GONE

        val next = s.parts.size + 1
        when {
            s.busy -> {
                status.visibility = View.VISIBLE; progress.visibility = View.VISIBLE; btnRetry.visibility = View.GONE
                txtStatus.text = "भाग $next / ${s.total} लिख रहा हूँ… (${s.minutes} मिनट की कहानी)"
            }
            s.error != null -> {
                status.visibility = View.VISIBLE; progress.visibility = View.GONE; btnRetry.visibility = View.VISIBLE
                btnRetry.text = if (s.stopped) "भाग $next से आगे लिखो" else "यह भाग फिर से लिखो"
                txtStatus.text = s.error + if (s.parts.isNotEmpty()) "\nपहले के ${s.parts.size} भाग सुरक्षित हैं।" else ""
            }
            s.done -> {
                status.visibility = View.VISIBLE; progress.visibility = View.GONE; btnRetry.visibility = View.GONE
                val w = s.parts.sumOf { StoryPlan.wordCount(it) }
                txtStatus.text = "✓ कहानी पूरी — ${s.total} भाग, लगभग $w शब्द (~${(w + StoryPlan.WORDS_PER_MIN / 2) / StoryPlan.WORDS_PER_MIN} मिनट)"
            }
            else -> status.visibility = View.GONE
        }
        actions.visibility = if (s.parts.isNotEmpty() && !s.busy) View.VISIBLE else View.GONE
    }

    private fun addPart(i: Int) {
        val s = StorySession
        val text = s.parts[i]
        val box = card(partsBox)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            this.text = "भाग ${i + 1} / ${s.total}"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF9FD3FF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill("यह भाग कॉपी करें") { copy(text, "भाग ${i + 1} कॉपी हो गया") }, LinearLayout.LayoutParams(-2, (36 * dp).toInt()))
        box.addView(head)
        box.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(0xF2FFFFFF.toInt()); setLineSpacing(0f, 1.3f)
            setTextIsSelectable(true); setPadding(0, (10 * dp).toInt(), 0, 0)
        })
        if (i == s.parts.size - 1 && i > 0) scroll.post { scroll.smoothScrollTo(0, box.top) }
    }

    private fun paintLengths() {
        for (i in 0 until lengthRow.childCount) {
            val b = lengthRow.getChildAt(i) as Button
            val on = b.tag == minutes
            b.setBackgroundResource(if (on) R.drawable.btn_accent else R.drawable.glass_pill)
            b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun copy(t: String, msg: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("कहानी", t))
        toast(msg)
    }

    /** Phone me: Android 10+ -> Download/DK Stories/<naam>.txt (bina permission); purane me user jagah chune */
    private fun save() {
        val s = StorySession
        if (s.parts.isEmpty()) return
        val name = (s.title.ifBlank { "कहानी" }.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().take(60)) +
            " " + java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(java.util.Date()) + ".txt"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { createDoc.launch(name); return }
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DK Stories")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("फ़ाइल नहीं बनी")
            contentResolver.openOutputStream(uri)?.use { it.write(s.fullText().toByteArray()) } ?: throw IllegalStateException("फ़ाइल नहीं खुली")
            Toast.makeText(this, "सेव हो गई: Download/DK Stories/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            DKLog.d("Story सेव गलती: ${e.message}")
            createDoc.launch(name)          // MediaStore na chale to user khud jagah chune
        }
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
        return box
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun pill(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.glass_pill); minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        val ph = (14 * dp).toInt(); setPadding(ph, (6 * dp).toInt(), ph, (6 * dp).toInt())
        setOnClickListener(onClick)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
