package com.dk.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class TeacherActivity : AppCompatActivity() {
    /** 23 नवंबर 2026 (मालिक): टैबलेट पर पूरा UI स्क्रीन के अनुपात में बड़ा; फ़ोन पर कुछ नहीं बदलता ([UiFit.tablet]) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(com.dk.assistant.UiFit.tablet(newBase)) }

    companion object {
        /** v3.87: पढ़ाई की बात सुनकर अपने-आप खुला (बटन से नहीं) */
        const val EXTRA_AUTO = "teacher_auto"
    }

    /** DK का पाठ (TeacherBoard) बदला = पन्ने पर लिखो */
    private var shownVersion = -1
    private val boardListener: () -> Unit = { runOnUiThread { refreshLesson() } }

    private fun refreshLesson() {
        if (!::whiteboardView.isInitialized) return
        val v = TeacherBoard.version
        if (v == shownVersion) return
        shownVersion = v
        whiteboardView.setLesson(TeacherBoard.boardLines())
    }

    /** "बंद करो" / "वापस जाओ" (आवाज़ से) */
    fun closeFromVoice() { if (!isFinishing) finish() }

    private lateinit var whiteboardView: WhiteboardView
    private lateinit var brainManager: BrainManager
    /** अभी बोल रहा जवाब (नया सवाल / स्क्रीन बंद = चुप) */
    private var speakJob: kotlinx.coroutines.Job? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var queryInput: EditText
    private lateinit var toolsRow: HorizontalScrollView
    private lateinit var pencilBtn: android.widget.ImageView
    private lateinit var eraseBtn: TextView
    /** 23 नवंबर 2026 (मालिक): पेंसिल बाय डिफ़ॉल्ट बंद - चुनने पर ही बोर्ड पर लिखना */
    private var pencilOn = false

    private var capturedImageUri: Uri? = null

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                askTeacher(spokenText, useBoardImage = false, customImageB64 = null)
            }
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            capturedImageUri?.let { uri ->
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        activityScope.launch(Dispatchers.Default) {
                            val b64 = jpegBase64(bitmap)
                            withContext(Dispatchers.Main) {
                                askTeacher("इस फोटो में दिए गए सवाल को समझाएं और हल करें।", useBoardImage = false, customImageB64 = b64)
                            }
                        }
                    }
                } catch (e: Exception) {
                    DKLog.d("Teacher: फोटो लोड गलती ${e.javaClass.simpleName}: ${e.message?.take(160)}"); Toast.makeText(this, "फोटो लोड नहीं हो पाई, फिर से कोशिश करें", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        brainManager = BrainManager(this)

        // 23 नवंबर 2026 (मालिक: "टीचर मोड का UI भी बाकी ऐप जैसा"): वही साझा Clay पन्ना - ← शीर्षक [शेयर] [साफ़],
        // बीच में गोल कोनों वाले कार्ड में सफ़ेद बोर्ड, नीचे DKAI जैसा लिखने का डिब्बा. सारे पुराने काम (पेंसिल, रबर, रंग,
        // अन-डू / री-डू, साफ़, कैमरा, माइक, बोर्ड हल करो, शेयर) वही - बस नए रूप में.
        com.dk.assistant.dkai.DkaiTheme.load(this)
        val c = this
        fun d(v: Float) = com.dk.assistant.dkai.DkaiUi.d(c, v)
        val (rootLayout, body, right) = com.dk.assistant.dkai.DkaiUi.page(c, "डीके टीचर (DK Teacher)") { finish() }
        right.addView(com.dk.assistant.dkai.DkaiUi.iconBtn(c, com.dk.assistant.dkai.DkaiIcon.Kind.SHARE, "बोर्ड शेयर करें", com.dk.assistant.dkai.DkaiTheme.WHITE, 42f) { shareWhiteboard() })
        right.addView(com.dk.assistant.dkai.DkaiUi.iconBtn(c, com.dk.assistant.dkai.DkaiIcon.Kind.TRASH, "बोर्ड साफ़ करें", com.dk.assistant.dkai.DkaiTheme.WHITE, 42f) { clearBoard() }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = d(8f)
        })

        // हालत (सोच रही हैं… / गलती) + पतली चलती पट्टी
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        statusText = com.dk.assistant.dkai.DkaiTheme.text(c, "", 13f, com.dk.assistant.dkai.DkaiTheme.GRAY).apply {
            setPadding(d(16f), 0, d(16f), d(4f)); visibility = View.GONE
        }
        body.addView(progressBar, LinearLayout.LayoutParams(-1, d(4f)).apply { setMargins(d(16f), 0, d(16f), d(4f)) })
        body.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        // Whiteboard View
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var debounceRunnable: Runnable? = null

        whiteboardView = WhiteboardView(this).apply {
            id = View.generateViewId()
            onDrawingChanged = { bitmap ->
                debounceRunnable?.let { handler.removeCallbacks(it) }
                debounceRunnable = Runnable {
                    // 23 नवंबर 2026: सिर्फ़ पेंसिल से लिखा हो तभी अपने-आप पूछो (साफ़ करने / अन-डू से खाली बोर्ड पर नहीं)
                    if (!pencilOn || !hasInk()) return@Runnable
                    val b64 = jpegBase64(bitmap)
                    askTeacher("कृपया इस लिखे हुए सवाल या चित्र को समझकर हल करें।", useBoardImage = true, customImageB64 = b64)
                }
                handler.postDelayed(debounceRunnable!!, 1000)
            }
            // गोल कोने (कार्ड के अंदर कागज़)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, d(22f).toFloat()) }
            }
            clipToOutline = true
        }
        val boardCard = android.widget.FrameLayout(c).apply {
            background = com.dk.assistant.ui.Clay.card(c); val p = d(6f); setPadding(p, p, p, p)
            clipChildren = false
        }
        boardCard.addView(whiteboardView, android.widget.FrameLayout.LayoutParams(-1, -1))
        body.addView(boardCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(d(12f), d(4f), d(12f), d(8f)) })

        // पेंसिल के औज़ार - पेंसिल चुनने पर ही दिखें
        toolsRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; clipToPadding = false }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12f), d(2f), d(12f), d(6f))
        }
        fun chip(label: String, desc: String, go: (TextView) -> Unit) = com.dk.assistant.dkai.DkaiTheme.text(c, label, 13f, com.dk.assistant.dkai.DkaiTheme.WHITE, com.dk.assistant.dkai.DkaiTheme.SANS_MED).apply {
            gravity = Gravity.CENTER; minHeight = d(38f); setPadding(d(14f), 0, d(14f), 0)
            background = com.dk.assistant.ui.Clay.chip(c, selectable = true); isClickable = true; isFocusable = true
            contentDescription = desc; setOnClickListener { go(this) }
        }
        val lp = { LinearLayout.LayoutParams(-2, d(38f)).apply { rightMargin = d(8f) } }
        eraseBtn = chip("रबर", "रबर (मिटाना)") { v ->
            v.isSelected = !v.isSelected
            whiteboardView.setMode(if (v.isSelected) WhiteboardView.Mode.ERASE else WhiteboardView.Mode.DRAW)
        }
        toolbar.addView(eraseBtn, lp())
        // रंग: छोटे गोल; चुना = किनारे पर घेरा
        val inks = listOf(Color.BLACK to "काला", Color.BLUE to "नीला", Color.RED to "लाल", Color.parseColor("#2E7D32") to "हरा")
        val dots = ArrayList<View>()
        fun paintDot(v: View, col: Int, on: Boolean) {
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(col)
                setStroke(d(if (on) 3f else 1.5f), if (on) com.dk.assistant.ui.ClayTheme.t.accent else 0x55FFFFFF)
            }
        }
        for ((col, name) in inks) {
            val dot = View(c).apply {
                contentDescription = "$name रंग"; isClickable = true
                setTag(PlainLook.SKIP, true)
                setOnClickListener {
                    whiteboardView.setDrawColor(col)
                    eraseBtn.isSelected = false; whiteboardView.setMode(WhiteboardView.Mode.DRAW)
                    dots.forEachIndexed { i, v -> paintDot(v, inks[i].first, v === this) }
                }
            }
            paintDot(dot, col, col == Color.BLACK)
            dots += dot
            toolbar.addView(dot, LinearLayout.LayoutParams(d(30f), d(30f)).apply { rightMargin = d(8f) })
        }
        toolbar.addView(chip("अन-डू", "अन-डू") { whiteboardView.undo() }, lp())
        toolbar.addView(chip("री-डू", "री-डू") { whiteboardView.redo() }, lp())
        toolbar.addView(com.dk.assistant.dkai.DkaiTheme.text(c, "बोर्ड हल करें", 13f, com.dk.assistant.dkai.DkaiTheme.ON, com.dk.assistant.dkai.DkaiTheme.SANS_BOLD).apply {
            gravity = Gravity.CENTER; minHeight = d(38f); setPadding(d(16f), 0, d(16f), 0)
            background = com.dk.assistant.ui.Clay.accent(c); isClickable = true; isFocusable = true
            contentDescription = "बोर्ड पर लिखा सवाल हल करें"
            setOnClickListener {
                askTeacher("कृपया बोर्ड पर लिखे या बनाए गए सवाल को समझकर कदम-दर-कदम हल करें।", useBoardImage = true, customImageB64 = null)
            }
        }, LinearLayout.LayoutParams(-2, d(38f)))
        toolsRow.addView(toolbar)
        body.addView(toolsRow, LinearLayout.LayoutParams(-1, -2))

        // नीचे का लिखने वाला डिब्बा (DKAI जैसा) - बाय डिफ़ॉल्ट यही (टाइप)
        val composer = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = com.dk.assistant.ui.Clay.card(c); setPadding(d(14f), d(10f), d(10f), d(10f))
        }
        queryInput = EditText(this).apply {
            hint = "यहाँ सवाल लिखें या बोलें..."
            textSize = 15f
            setTextColor(com.dk.assistant.dkai.DkaiTheme.WHITE); setHintTextColor(com.dk.assistant.dkai.DkaiTheme.HINT)
            background = null   // डिब्बा ही कार्ड है (DKAI के "Ask anything" जैसा)
            setPadding(d(4f), d(6f), d(4f), d(8f))
            minLines = 1; maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        composer.addView(queryInput, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(6f), 0, 0) }
        fun round(kind: com.dk.assistant.dkai.DkaiIcon.Kind, desc: String, go: () -> Unit) = android.widget.ImageView(c).apply {
            setImageDrawable(com.dk.assistant.dkai.DkaiIcon(kind, com.dk.assistant.dkai.DkaiTheme.WHITE)); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val p = d(10f); setPadding(p, p, p, p); contentDescription = desc
            background = com.dk.assistant.ui.Clay.circle(c, 3f); isClickable = true; isFocusable = true
            setOnClickListener { go() }
        }
        // ✏ पेंसिल: बंद (डिफ़ॉल्ट) = टाइप; दबाओ = बोर्ड पर लिखना चालू + औज़ार
        pencilBtn = round(com.dk.assistant.dkai.DkaiIcon.Kind.EDIT, "पेंसिल (बोर्ड पर लिखना) - बंद") { setPencil(!pencilOn) }
        row.addView(pencilBtn, LinearLayout.LayoutParams(d(42f), d(42f)))
        val cameraBtn = round(com.dk.assistant.dkai.DkaiIcon.Kind.CAMERA, "सवाल की फोटो लें") {
            try {
                val photoFile = File(cacheDir, "teacher_camera_${System.currentTimeMillis()}.jpg")
                capturedImageUri = FileProvider.getUriForFile(
                    this@TeacherActivity,
                    "$packageName.files",
                    photoFile
                )
                capturedImageUri?.let { uri ->
                    cameraLauncher.launch(uri)
                }
            } catch (e: Exception) {
                DKLog.d("Teacher: कैमरा गलती ${e.javaClass.simpleName}: ${e.message?.take(160)}"); Toast.makeText(this@TeacherActivity, "कैमरा शुरू नहीं हो पाया, फिर से कोशिश करें", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(cameraBtn, LinearLayout.LayoutParams(d(42f), d(42f)).apply { leftMargin = d(8f) })
        row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        val micBtn = round(com.dk.assistant.dkai.DkaiIcon.Kind.MIC, "बोलकर पूछें") { startVoiceInput() }
        row.addView(micBtn, LinearLayout.LayoutParams(d(42f), d(42f)).apply { rightMargin = d(8f) })
        val askTextBtn = android.widget.ImageView(c).apply {
            setImageDrawable(com.dk.assistant.dkai.DkaiIcon(com.dk.assistant.dkai.DkaiIcon.Kind.SEND, com.dk.assistant.dkai.DkaiTheme.ON)); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val p = d(11f); setPadding(p, p, p, p); contentDescription = "पूछें"
            background = com.dk.assistant.ui.Clay.circle(c, 3f, accent = true); isClickable = true; isFocusable = true
            setOnClickListener {
                val text = queryInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    queryInput.text.clear()
                    askTeacher(text, useBoardImage = false, customImageB64 = null)
                }
            }
        }
        row.addView(askTextBtn, LinearLayout.LayoutParams(d(44f), d(44f)))
        composer.addView(row, LinearLayout.LayoutParams(-1, -2))
        body.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(12f)) })

        setContentView(rootLayout)
        setPencil(false)

        // v3.87: DK जो पढ़ाए, वही इस पन्ने पर (DK Live / आम आवाज़ - दोनों)
        TeacherLink.attach(this)
        DKState.listen(boardListener)
        refreshLesson()
        if (intent?.getBooleanExtra(EXTRA_AUTO, false) == true) showStatus("DK जो समझाएगी, वह यहीं लिखा दिखेगा • बंद करने के लिए बोलें \"बंद करो\"")
    }

    /** पेंसिल चालू = बोर्ड पर लिखना + औज़ार; बंद = टाइप (छूने से बोर्ड बस ऊपर-नीचे होता है) */
    private fun setPencil(on: Boolean) {
        pencilOn = on
        whiteboardView.setMode(if (on) WhiteboardView.Mode.DRAW else WhiteboardView.Mode.TEXT)
        if (::eraseBtn.isInitialized) eraseBtn.isSelected = false
        toolsRow.visibility = if (on) View.VISIBLE else View.GONE
        pencilBtn.background = com.dk.assistant.ui.Clay.circle(this, 3f, accent = on)
        pencilBtn.setImageDrawable(com.dk.assistant.dkai.DkaiIcon(com.dk.assistant.dkai.DkaiIcon.Kind.EDIT,
            if (on) com.dk.assistant.dkai.DkaiTheme.ON else com.dk.assistant.dkai.DkaiTheme.WHITE))
        pencilBtn.contentDescription = if (on) "पेंसिल चालू - बंद करके टाइप करें" else "पेंसिल (बोर्ड पर लिखना) - बंद"
        if (on) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(queryInput.windowToken, 0)
            showStatus("पेंसिल चालू - बोर्ड पर लिखिए या चित्र बनाइए")
        } else if (statusText.text.startsWith("पेंसिल")) showStatus("")
    }

    private fun showStatus(t: String) { statusText.text = t; statusText.visibility = if (t.isBlank()) View.GONE else View.VISIBLE }

    private fun clearBoard() { stopSpeaking(); whiteboardView.clear(); whiteboardView.clearLesson(); TeacherBoard.reset(); shownVersion = TeacherBoard.version }

    override fun onResume() { super.onResume(); TeacherLink.visible = true; refreshLesson() }
    override fun onPause() { TeacherLink.visible = false; super.onPause() }

    private fun startVoiceInput() {
        stopSpeaking()                                   // बोलकर पूछना = DK का चल रहा जवाब चुप (अपनी ही आवाज़ न सुने)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "डीके टीचर से सवाल बोलें...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "वॉयस इनपुट उपलब्ध नहीं है", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askTeacher(userQuestion: String, useBoardImage: Boolean, customImageB64: String?) {
        stopSpeaking()
        progressBar.visibility = View.VISIBLE
        showStatus("डीके टीचर सोच रही हैं...")

        activityScope.launch {
            try {
                val systemPrompt = """
                    आप 'डीके टीचर' हैं। आप प्राथमिक कक्षा से 12वीं, कॉलेज, प्रतियोगी परीक्षाओं, गणित, विज्ञान, मेडिकल, लॉ, भाषा और इंजीनियरिंग की स्नेही और बहुत धैर्यवान शिक्षिका हैं।
                    आप पूरी तरह प्रोफेशनल टीचर हैं - यूज़र जो भी सीखना चाहे (कोई भी विषय, कोडिंग, कोई भी हुनर) वह सब सिखाती हैं।
                    कोई भी भाषा सीखनी हो (इंग्लिश, मराठी, हिंदी, संस्कृत, उर्दू, गुजराती, तमिल, फ्रेंच, जर्मन, जापानी...): यूज़र के स्तर से शुरू करें -
                    शब्द + मतलब + उच्चारण, रोज़ के वाक्य, आसान व्याकरण, फिर छोटा अभ्यास/क्विज़ दें और गलती प्यार से सुधारें।
                    
                    दिशा-निर्देश:
                    1. सवाल को एकदम आसान भाषा में कदम-दर-कदम (step-by-step) समझाएं।
                    2. गणित या विज्ञान के सवाल में हर चरण लिखकर समझाएं कि कौन-सा सूत्र या नियम लगा।
                    3. मुख्य बिंदुओं या बोर्ड पर लिखे जाने योग्य चरणों को [WRITE] मुख्य बिंदु [/WRITE] टैग के अंदर लिखें ताकि वे व्हाइटबोर्ड पर दर्ज हो सकें।
                    4. यदि विषय मेडिकल या कानून का हो, तो अंत में यह अवश्य लिखें: "ध्यान दें: यह केवल शैक्षणिक जानकारी है, व्यक्तिगत मामले में विशेषज्ञ वकील या डॉक्टर से परामर्श लें।"
                    5. उत्तर देने के बाद पूछें: "क्या आपको यह समझ आया?"
                    6. एकदम बारीकी से, असली टीचर की तरह पढ़ाएँ: वह क्या है, कैसे और क्यों होता है, कहाँ होता / काम आता है - हर बात के साथ रोज़ की ज़िंदगी का एक उदाहरण। हर चरण में बताएँ कि क्या हुआ और क्यों हुआ।
                    7. यूज़र कहे "समझ नहीं आया" / "फिर से समझाओ" तो वही शब्द न दोहराएँ - और आसान, और छोटे चरणों में, नए उदाहरण या चित्र से दोबारा समझाएँ; जितनी बार ज़रूरत हो, प्यार और धैर्य से।
                    8. आख़िर में एक छोटा अभ्यास-सवाल दें ताकि पक्का हो कि समझ आया।
                    9. ${TeacherDraw.WRITTEN_RULE}
                """.trimIndent()

                // 23 नवंबर 2026 (मालिक: "नहीं समझ आए तो बार-बार समझाए"): पिछले सवाल-जवाब साथ भेजो - "फिर से समझाओ" किस बारे में, यह पता रहे
                val earlier = TeacherBoard.sections().takeLast(3).joinToString("\n") { sec ->
                    "प्रश्न: " + sec.question.take(200) + "\nजवाब: " + TeacherDraw.strip(sec.answer).take(700)
                }
                val prompt = (if (earlier.isNotBlank()) "पिछली पढ़ाई (इसी पन्ने पर):\n$earlier\n\n" else "") + "यूज़र का प्रश्न: $userQuestion"
                val imageB64 = when {
                    customImageB64 != null -> customImageB64
                    useBoardImage -> {
                        val board = whiteboardView.getBitmap()
                        withContext(Dispatchers.Default) { jpegBase64(board) }
                    }
                    else -> null
                }

                val response = brainManager.teach(systemPrompt, prompt, imageB64)

                progressBar.visibility = View.GONE
                showStatus("")

                processTeacherResponse(response, userQuestion)

            } catch (e: Exception) {
                Log.e("TeacherActivity", "Error asking teacher", e)
                DKBug.report("app", e, "Teacher से पूछना", alsoLog = true)
                progressBar.visibility = View.GONE
                showStatus("त्रुटि: " + brainManager.explain(e))
            }
        }
    }

    private fun processTeacherResponse(raw: String, question: String = "") {
        // 24 नवंबर 2026 (मालिक: "ब्रैकेट, response लिखा आता है"): मॉडल का JSON / [RESPONSE] लपेट हटाकर ही बोर्ड और आवाज़
        val response = TeacherReply.clean(raw)
        // [WRITE] वाले मुख्य बिंदु भी पूरे जवाब के साथ - पूरा समझाया हुआ पन्ने पर, चरण-दर-चरण (v3.87)
        val regex = Regex("\\[WRITE\\](.*?)\\[/WRITE\\]", RegexOption.DOT_MATCHES_ALL)
        val boardText = response.replace(regex) { it.groupValues[1].trim() + "\n" }.trim()
        // 23 नवंबर 2026: चित्र की लिखाई ([DRAW]…[/DRAW]) बोर्ड पर बनती है, बोली नहीं जाती
        val cleanText = TeacherDraw.strip(response.replace(regex, "")).trim()
        if (boardText.isNotEmpty()) TeacherBoard.addLocal(question.ifBlank { "सवाल" }, boardText)
        refreshLesson()

        // DK Live चल रहा हो तो फ़ोन की दूसरी आवाज़ नहीं (DK की अपनी आवाज़ ही)
        // 24 नवंबर 2026 (मालिक: "रोबोटिक आवाज़ - फ़ोन की TTS; ऑफ़लाइन वाली TTS से आए"): पहले DK की ऑफ़लाइन आवाज़
        // (Supertonic, [com.dk.assistant.voice.st3.OfflineVoice]); वह डाउनलोड न हो / न चले तभी फ़ोन की आवाज़ ([PhoneVoice.say]).
        val say = com.dk.assistant.voice.engine.HinglishText.toSpeakable(TeacherReply.spoken(if (cleanText.isNotEmpty()) cleanText else response))
        if (say.isNotBlank() && !DKState.live) {
            stopSpeaking()
            speakJob = activityScope.launch(Dispatchers.IO) {
                try { PhoneVoice.say(applicationContext, say) } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { Quiet.note(e) }
            }
        }
    }

    private fun stopSpeaking() {
        speakJob?.cancel(); speakJob = null
        try { PhoneVoice.stop() } catch (e: Exception) { Quiet.note(e) }
    }

    private fun shareWhiteboard() {
        try {
            val bitmap = whiteboardView.getBitmap()
            val cachePath = File(cacheDir, "images")
            cachePath.mkdirs()
            val file = File(cachePath, "dk_teacher_board.png")
            val stream: OutputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.close()

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                    this@TeacherActivity,
                    "$packageName.files",
                    file
                ))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "व्हाइटबोर्ड शेयर करें"))
        } catch (e: Exception) {
            DKLog.d("Teacher: शेयर गलती ${e.javaClass.simpleName}: ${e.message?.take(160)}"); Toast.makeText(this, "शेयर नहीं हो पाया, फिर से कोशिश करें", Toast.LENGTH_SHORT).show()
        }
    }

    /** व्हाइटबोर्ड -> छोटी JPEG (base64) ताकि AI को भेजना हल्का रहे */
    private fun jpegBase64(full: Bitmap): String {
        val maxSide = 1280
        val scale = minOf(1f, maxSide.toFloat() / maxOf(full.width, full.height).coerceAtLeast(1))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true) else full
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    override fun onDestroy() {
        DKState.unlisten(boardListener)
        TeacherLink.detach(this)
        activityScope.cancel()
        stopSpeaking()
        super.onDestroy()
    }
}