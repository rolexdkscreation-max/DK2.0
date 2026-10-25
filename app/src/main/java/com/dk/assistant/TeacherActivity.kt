package com.dk.assistant

import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.dk.assistant.BrainManager

class TeacherActivity : AppCompatActivity() {
    private lateinit var whiteboard: WhiteboardView
    private lateinit var brain: BrainManager
    private lateinit var tvAiResponse: android.widget.TextView
    private lateinit var etQuery: android.widget.EditText
    private var attachedImageUri: android.net.Uri? = null

    private val imagePickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        uri?.let {
            attachedImageUri = it
            tvAiResponse.text = "छवि अपलोड हो गई। अब अपना सवाल पूछें।"
        }
    }

    private val speechLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                etQuery.setText(matches[0])
                askTeacher(matches[0])
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher)
        
        brain = BrainManager(this)
        whiteboard = findViewById(R.id.whiteboardView)
        tvAiResponse = findViewById(R.id.tvAiResponse)
        etQuery = findViewById(R.id.etQuery)

        findViewById<android.widget.ImageButton>(R.id.btnVoice).setOnClickListener {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "पढ़ाई से संबंधित अपना सवाल बोलें...")
            }
            try {
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "वॉइस रिकग्निशन उपलब्ध नहीं है", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<android.widget.ImageButton>(R.id.btnAttach).setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        findViewById<android.widget.Button>(R.id.btnSend).setOnClickListener {
            val query = etQuery.text.toString().trim()
            if (query.isNotEmpty()) {
                askTeacher(query)
            }
        }

        findViewById<android.widget.Button>(R.id.btnPencil).setOnClickListener {
            whiteboard.setMode(WhiteboardView.Mode.DRAW)
        }

        findViewById<android.widget.Button>(R.id.btnEraser).setOnClickListener {
            whiteboard.setMode(WhiteboardView.Mode.ERASE)
        }

        findViewById<android.widget.Button>(R.id.btnClear).setOnClickListener {
            whiteboard.clear()
        }
    }

    private fun askTeacher(query: String) {
        tvAiResponse.text = "डीके टीचर सोच रहे हैं और रिसर्च कर रहे हैं..."
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            try {
                val prompt = buildTeacherPrompt(query)
                val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    var finalRes: String? = null
                    brain.streamChat(ApiScopes.TEACHER, prompt, object : BrainManager.StreamCallback {
                        override fun onToken(token: String) {}
                        override fun onComplete(fullText: String) {
                            finalRes = fullText
                        }
                        override fun onError(err: Throwable) {
                            finalRes = "त्रुटि: ${err.localizedMessage}"
                        }
                    })
                    finalRes ?: "उत्तर तैयार किया जा रहा है..."
                }
                tvAiResponse.text = response
            } catch (e: Exception) {
                tvAiResponse.text = "त्रुटि: ${e.localizedMessage}"
            }
        }
    }

    private fun buildTeacherPrompt(userQuery: String): String {
        return """
            You are DK Teacher, an expert, patient, and step-by-step educational AI tutor.
            Your task is to explain everything strictly in a structured, step-by-step manner.
            If the topic requires 3D mental visualization, structural breakdown, or background research, explain it vividly step-by-step.
            User Query: $userQuery
        """.trimIndent()
    }
}

class WhiteboardView(context: android.content.Context, attrs: android.util.AttributeSet) : View(context, attrs) {
    enum class Mode { DRAW, ERASE }
    private var currentMode = Mode.DRAW

    private data class PathStyle(val path: Path, val paint: Paint)
    private val paths = java.util.ArrayList<PathStyle>()
    private var currentPath = Path()
    private var currentPaint = createPaint(Color.BLACK, 8f)

    private fun createPaint(color: Int, width: Float): Paint {
        return Paint().apply {
            this.color = color
            isAntiAlias = true
            strokeWidth = width
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    }

    fun setMode(mode: Mode) {
        currentMode = mode
        currentPaint = if (mode == Mode.ERASE) {
            createPaint(Color.WHITE, 30f)
        } else {
            createPaint(Color.BLACK, 8f)
        }
    }

    fun clear() {
        paths.clear()
        currentPath = Path()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        for (ps in paths) {
            canvas.drawPath(ps.path, ps.paint)
        }
        canvas.drawPath(currentPath, currentPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path()
                currentPath.moveTo(x, y)
                paths.add(PathStyle(currentPath, currentPaint))
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(x, y)
            }
            MotionEvent.ACTION_UP -> {
                currentPath = Path()
            }
        }
        invalidate()
        return true
    }
}