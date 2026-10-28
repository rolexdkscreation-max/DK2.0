package com.dk.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
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
import java.util.Locale

class TeacherActivity : AppCompatActivity() {

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
    private var tts: TextToSpeech? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var queryInput: EditText

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
                    Toast.makeText(this, "फोटो लोड करने में त्रुटि: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        brainManager = BrainManager(this)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
            }
        }

        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Navigation Bar
        val topBar = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F0F4F8"))
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = Button(this).apply {
            text = "← वापस"
            textSize = 14f
            setTextColor(Color.parseColor("#1E88E5"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }

        val titleText = TextView(this).apply {
            text = "डीके टीचर (DK Teacher)"
            textSize = 18f
            setTextColor(Color.parseColor("#1A237E"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(16, 0, 16, 0)
            }
        }

        val shareBtn = Button(this).apply {
            text = "शेयर"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            setOnClickListener { shareWhiteboard() }
        }

        topBar.addView(backBtn)
        topBar.addView(titleText)
        topBar.addView(shareBtn)

        val topBarParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        rootLayout.addView(topBar, topBarParams)

        // Whiteboard View
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var debounceRunnable: Runnable? = null

        whiteboardView = WhiteboardView(this).apply {
            id = View.generateViewId()
            onDrawingChanged = { bitmap ->
                debounceRunnable?.let { handler.removeCallbacks(it) }
                debounceRunnable = Runnable {
                    val b64 = jpegBase64(bitmap)
                    askTeacher("कृपया इस लिखे हुए सवाल या चित्र को समझकर हल करें।", useBoardImage = true, customImageB64 = b64)
                }
                handler.postDelayed(debounceRunnable!!, 1000)
            }
        }

        // Bottom Controls Container
        val bottomContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(12, 8, 12, 12)
        }

        // Toolbar Items (Scrollable)
        val toolbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val drawBtn = Button(this).apply { text = "✏️ पेंसिल"; setOnClickListener { whiteboardView.setMode(WhiteboardView.Mode.DRAW) } }
        val typeBtn = Button(this).apply { text = "🔤 टाइप"; setOnClickListener { 
            whiteboardView.setMode(WhiteboardView.Mode.TEXT) 
            queryInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(queryInput, InputMethodManager.SHOW_IMPLICIT)
        } }
        val eraseBtn = Button(this).apply { text = "🧹 रबर"; setOnClickListener { whiteboardView.setMode(WhiteboardView.Mode.ERASE) } }
        val undoBtn = Button(this).apply { text = "↩️ अन-डू"; setOnClickListener { whiteboardView.undo() } }
        val redoBtn = Button(this).apply { text = "↪️ री-डू"; setOnClickListener { whiteboardView.redo() } }
        val clearBtn = Button(this).apply { text = "🗑️ साफ़ करें"; setOnClickListener { whiteboardView.clear(); whiteboardView.clearLesson(); TeacherBoard.reset(); shownVersion = TeacherBoard.version } }

        // Color selection buttons
        val btnBlack = Button(this).apply { text = "●"; setTextColor(Color.BLACK); setOnClickListener { whiteboardView.setDrawColor(Color.BLACK) } }
        val btnBlue = Button(this).apply { text = "●"; setTextColor(Color.BLUE); setOnClickListener { whiteboardView.setDrawColor(Color.BLUE) } }
        val btnRed = Button(this).apply { text = "●"; setTextColor(Color.RED); setOnClickListener { whiteboardView.setDrawColor(Color.RED) } }
        val btnGreen = Button(this).apply { text = "●"; setTextColor(Color.parseColor("#2E7D32")); setOnClickListener { whiteboardView.setDrawColor(Color.parseColor("#2E7D32")) } }

        toolbar.addView(drawBtn)
        toolbar.addView(typeBtn)
        toolbar.addView(eraseBtn)
        toolbar.addView(btnBlack)
        toolbar.addView(btnBlue)
        toolbar.addView(btnRed)
        toolbar.addView(btnGreen)
        toolbar.addView(undoBtn)
        toolbar.addView(redoBtn)
        toolbar.addView(clearBtn)
        toolbarScroll.addView(toolbar)

        // Query input area
        val queryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }

        queryInput = EditText(this).apply {
            hint = "यहाँ सवाल लिखें या बोलें..."
            textSize = 14f
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        val cameraBtn = Button(this).apply {
            text = "📷"
            setOnClickListener {
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
                    Toast.makeText(this@TeacherActivity, "कैमरा शुरू करने में त्रुटि: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val micBtn = Button(this).apply {
            text = "🎙️"
            setOnClickListener { startVoiceInput() }
        }

        val askTextBtn = Button(this).apply {
            text = "पूछें"
            setOnClickListener {
                val text = queryInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    queryInput.text.clear()
                    askTeacher(text, useBoardImage = false, customImageB64 = null)
                }
            }
        }

        val solveBoardBtn = Button(this).apply {
            text = "📋 बोर्ड हल करें"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                askTeacher("कृपया बोर्ड पर लिखे या बनाए गए सवाल को समझकर कदम-दर-कदम हल करें।", useBoardImage = true, customImageB64 = null)
            }
        }

        queryBar.addView(queryInput)
        queryBar.addView(cameraBtn)
        queryBar.addView(micBtn)
        queryBar.addView(askTextBtn)
        queryBar.addView(solveBoardBtn)

        // Progress bar and status
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        statusText = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(4, 4, 4, 4)
        }

        bottomContainer.addView(toolbarScroll)
        bottomContainer.addView(queryBar)
        bottomContainer.addView(progressBar)
        bottomContainer.addView(statusText)

        val bottomParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        rootLayout.addView(bottomContainer, bottomParams)

        // Whiteboard Layout Params
        val boardParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.BELOW, topBar.id)
            addRule(RelativeLayout.ABOVE, bottomContainer.id)
        }
        rootLayout.addView(whiteboardView, boardParams)

        setContentView(rootLayout)

        // v3.87: DK जो पढ़ाए, वही इस पन्ने पर (DK Live / आम आवाज़ - दोनों)
        TeacherLink.attach(this)
        DKState.listen(boardListener)
        refreshLesson()
        if (intent?.getBooleanExtra(EXTRA_AUTO, false) == true) statusText.text = "DK जो समझाएगी, वह यहीं लिखा दिखेगा • बंद करने के लिए बोलें \"बंद करो\""
    }

    override fun onResume() { super.onResume(); TeacherLink.visible = true; refreshLesson() }
    override fun onPause() { TeacherLink.visible = false; super.onPause() }

    private fun startVoiceInput() {
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
        progressBar.visibility = View.VISIBLE
        statusText.text = "डीके टीचर सोच रही हैं..."

        activityScope.launch {
            try {
                val systemPrompt = """
                    आप 'डीके टीचर' हैं। आप प्राथमिक कक्षा से 12वीं, कॉलेज, प्रतियोगी परीक्षाओं, गणित, विज्ञान, मेडिकल, लॉ, भाषा और इंजीनियरिंग की स्नेही और बहुत धैर्यवान शिक्षिका हैं।
                    
                    दिशा-निर्देश:
                    1. सवाल को एकदम आसान भाषा में कदम-दर-कदम (step-by-step) समझाएं।
                    2. गणित या विज्ञान के सवाल में हर चरण लिखकर समझाएं कि कौन-सा सूत्र या नियम लगा।
                    3. मुख्य बिंदुओं या बोर्ड पर लिखे जाने योग्य चरणों को [WRITE] मुख्य बिंदु [/WRITE] टैग के अंदर लिखें ताकि वे व्हाइटबोर्ड पर दर्ज हो सकें।
                    4. यदि विषय मेडिकल या कानून का हो, तो अंत में यह अवश्य लिखें: "ध्यान दें: यह केवल शैक्षणिक जानकारी है, व्यक्तिगत मामले में विशेषज्ञ वकील या डॉक्टर से परामर्श लें।"
                    5. उत्तर देने के बाद पूछें: "क्या आपको यह समझ आया?"
                """.trimIndent()

                val prompt = "यूज़र का प्रश्न: $userQuestion"
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
                statusText.text = ""

                processTeacherResponse(response, userQuestion)

            } catch (e: Exception) {
                Log.e("TeacherActivity", "Error asking teacher", e)
                progressBar.visibility = View.GONE
                statusText.text = "त्रुटि: " + brainManager.explain(e)
            }
        }
    }

    private fun processTeacherResponse(response: String, question: String = "") {
        // [WRITE] वाले मुख्य बिंदु भी पूरे जवाब के साथ - पूरा समझाया हुआ पन्ने पर, चरण-दर-चरण (v3.87)
        val regex = Regex("\\[WRITE\\](.*?)\\[/WRITE\\]", RegexOption.DOT_MATCHES_ALL)
        val boardText = response.replace(regex) { it.groupValues[1].trim() + "\n" }.trim()
        val cleanText = response.replace(regex, "").trim()
        if (boardText.isNotEmpty()) TeacherBoard.addLocal(question.ifBlank { "सवाल" }, boardText)
        refreshLesson()

        // DK Live चल रहा हो तो फ़ोन की दूसरी आवाज़ नहीं (DK की अपनी आवाज़ ही)
        if (cleanText.isNotEmpty() && !DKState.live) {
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "TeacherTTS")
        }
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
            Toast.makeText(this, "शेयर करने में त्रुटि: ${e.message}", Toast.LENGTH_SHORT).show()
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
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}