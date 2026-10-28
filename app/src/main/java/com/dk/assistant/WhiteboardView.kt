package com.dk.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.os.Handler
import android.os.Looper
import androidx.core.view.GestureDetectorCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Stack

class WhiteboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        DRAW, ERASE, TEXT, PAN_ZOOM
    }

    data class TextItem(
        var text: String,
        var x: Float,
        var y: Float,
        val color: Int,
        val textSize: Float
    )

    sealed class Action {
        data class DrawAction(val path: Path, val paint: Paint) : Action()
        data class TextAction(val textItem: TextItem) : Action()
    }

    private val undoStack = Stack<Action>()
    private val redoStack = Stack<Action>()

    var onDrawingChanged: ((Bitmap) -> Unit)? = null
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private var lastSnapshotHash = 0

    private var currentPath: Path? = null
    private var currentPaint: Paint? = null

    var currentMode: Mode = Mode.DRAW
    var currentColor: Int = Color.BLACK
    var currentStrokeWidth: Float = 8f
    var currentTextSize: Float = 42f

    private val transformMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var scaleFactor = 1.0f

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetectorCompat

    var onTextTapListener: ((x: Float, y: Float) -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                scaleFactor *= scale
                scaleFactor = scaleFactor.coerceIn(0.5f, 5.0f)
                transformMatrix.postScale(scale, scale, detector.focusX, detector.focusY)
                invalidate()
                return true
            }
        })

        gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (currentMode == Mode.PAN_ZOOM || e2.pointerCount > 1) {
                    transformMatrix.postTranslate(-distanceX, -distanceY)
                    userPanned = true
                    invalidate()
                    return true
                }
                return false
            }
        })
    }

    fun setMode(mode: Mode) {
        this.currentMode = mode
    }

    fun setDrawColor(color: Int) {
        this.currentColor = color
    }

    fun setStrokeWidth(width: Float) {
        this.currentStrokeWidth = width
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val action = undoStack.pop()
            redoStack.push(action)
            invalidate()
            scheduleSnapshot()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val action = redoStack.pop()
            undoStack.push(action)
            invalidate()
            scheduleSnapshot()
        }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        invalidate()
        scheduleSnapshot()
    }

    fun addBoardText(text: String, x: Float = 60f, y: Float = 120f + (undoStack.size * 55f) % 800f) {
        val item = TextItem(text, x, y, currentColor, currentTextSize)
        val action = Action.TextAction(item)
        undoStack.push(action)
        redoStack.clear()
        invalidate()
        scheduleSnapshot()
    }

    // ---------------- v3.87: DK का पाठ (अपने-आप लिखा जाने वाला हिस्सा) ----------------
    // पेन, रबर, टाइप, अन-डू - सब पहले जैसे; पाठ उनके नीचे अलग परत में, टीचर जैसा धीरे-धीरे लिखता है।

    private data class LessonLine(val text: String, val kind: TeacherBoard.Kind)
    private var lesson: List<LessonLine> = emptyList()
    private var lessonChars = 0          // कुल अक्षर
    private var shownChars = 0           // अब तक लिखे गए
    private var lessonHeight = 0f
    private var lastQuestionY = 0f
    private var questionCount = 0
    private var userPanned = false
    private val qPaint = TextPaint().apply { isAntiAlias = true; color = Color.parseColor("#1A237E"); textSize = 46f; typeface = Typeface.DEFAULT_BOLD }
    private val hPaint = TextPaint().apply { isAntiAlias = true; color = Color.parseColor("#0D47A1"); textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
    private val sPaint = TextPaint().apply { isAntiAlias = true; color = Color.parseColor("#1B5E20"); textSize = 42f }
    private val aPaint = TextPaint().apply { isAntiAlias = true; color = Color.parseColor("#B71C1C"); textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
    private val pPaint = TextPaint().apply { isAntiAlias = true; color = Color.parseColor("#212121"); textSize = 40f }
    private val rulePaint = Paint().apply { color = Color.parseColor("#E3F2FD"); strokeWidth = 2f }

    private val typer = object : Runnable {
        override fun run() {
            if (shownChars < lessonChars) {
                // पीछे छूटे तो तेज़ (आवाज़ के साथ तालमेल), वरना टीचर की रफ़्तार
                val behind = lessonChars - shownChars
                shownChars = (shownChars + when { behind > 240 -> 12; behind > 80 -> 5; else -> 2 }).coerceAtMost(lessonChars)
                invalidate()
                postDelayed(this, 28)
            }
        }
    }

    /** पूरा पाठ (सवाल + DK का जवाब) - पहले लिखा हुआ वैसा ही रहता है, नया हिस्सा धीरे-धीरे आता है */
    fun setLesson(lines: List<TeacherBoard.Line>) {
        val next = lines.map { LessonLine(it.text, it.kind) }

        // नया सवाल आने पर ऑटो-स्क्रॉल रीसेट करें
        val newQCount = next.count { it.kind == TeacherBoard.Kind.QUESTION }
        if (newQCount > questionCount) {
            questionCount = newQCount
            userPanned = false
            // नए सवाल को ऊपर लाने के लिए मैट्रिक्स रीसेट (अगर बहुत नीचे चले गए हों)
            if (lessonHeight > 0) {
                transformMatrix.reset()
                transformMatrix.postTranslate(0f, -lessonHeight + 40f)
                scaleFactor = 1.0f
            }
        }

        // पहले जितना लिखा जा चुका, उतना साझा हिस्सा दोबारा नहीं लिखते
        val oldText = lesson.joinToString("\n") { it.text }
        val newText = next.joinToString("\n") { it.text }
        var common = 0
        while (common < oldText.length && common < newText.length && oldText[common] == newText[common]) common++
        lesson = next
        lessonChars = next.sumOf { it.text.length }
        shownChars = shownChars.coerceAtMost(common.coerceAtMost(lessonChars))
        removeCallbacks(typer); post(typer)
        invalidate()
    }

    fun clearLesson() { lesson = emptyList(); lessonChars = 0; shownChars = 0; lessonHeight = 0f; lastQuestionY = 0f; questionCount = 0; userPanned = false; invalidate() }

    private fun paintFor(k: TeacherBoard.Kind) = when (k) {
        TeacherBoard.Kind.QUESTION -> qPaint
        TeacherBoard.Kind.HEADING -> hPaint
        TeacherBoard.Kind.STEP -> sPaint
        TeacherBoard.Kind.ANSWER -> aPaint
        TeacherBoard.Kind.POINT -> pPaint
    }

    /** पाठ लिखो (कटे बिना, स्क्रीन की चौड़ाई में लपेटकर); लौटाता है = कुल ऊँचाई */
    private fun drawLesson(canvas: Canvas, w: Int, all: Boolean): Float {
        if (lesson.isEmpty()) return 0f
        val left = 48f
        val width = (w - 96).coerceAtLeast(200)
        var y = 40f
        var budget = if (all) Int.MAX_VALUE else shownChars
        for (line in lesson) {
            if (budget <= 0) break
            val prefix = if (line.kind == TeacherBoard.Kind.POINT) "• " else ""
            val visible = line.text.take(budget)
            budget -= line.text.length
            if (line.kind == TeacherBoard.Kind.QUESTION) {
                if (y > 60f) { y += 18f; canvas.drawLine(left, y, left + width, y, rulePaint); y += 22f }
                lastQuestionY = y
            }
            val p = paintFor(line.kind)
            val sl = StaticLayout.Builder.obtain(prefix + visible, 0, prefix.length + visible.length, p, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(6f, 1f).build()
            canvas.save(); canvas.translate(if (line.kind == TeacherBoard.Kind.STEP) left + 16f else left, y); sl.draw(canvas); canvas.restore()
            y += sl.height + 14f
        }
        return y
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (event.pointerCount > 1) {
            return true
        }

        transformMatrix.invert(inverseMatrix)
        val pts = floatArrayOf(event.x, event.y)
        inverseMatrix.mapPoints(pts)
        val touchX = pts[0]
        val touchY = pts[1]

        when (currentMode) {
            Mode.DRAW, Mode.ERASE -> {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val path = Path()
                        path.moveTo(touchX, touchY)
                        val paint = Paint().apply {
                            isAntiAlias = true
                            style = Paint.Style.STROKE
                            strokeJoin = Paint.Join.ROUND
                            strokeCap = Paint.Cap.ROUND
                            if (currentMode == Mode.ERASE) {
                                color = Color.WHITE
                                strokeWidth = currentStrokeWidth * 4f
                            } else {
                                color = currentColor
                                strokeWidth = currentStrokeWidth
                            }
                        }
                        currentPath = path
                        currentPaint = paint
                        undoStack.push(Action.DrawAction(path, paint))
                        redoStack.clear()
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        currentPath?.lineTo(touchX, touchY)
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        currentPath = null
                        currentPaint = null
                        scheduleSnapshot()
                    }
                }
            }
            Mode.TEXT -> {
                if (event.action == MotionEvent.ACTION_UP) {
                    onTextTapListener?.invoke(touchX, touchY)
                }
            }
            Mode.PAN_ZOOM -> {
                // Handled by GestureDetector / ScaleGestureDetector
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        canvas.save()
        canvas.concat(transformMatrix)

        // v3.87 / नियम 1: ऑटो-स्क्रॉल और नया सवाल
        val h = drawLesson(canvas, width, all = false)
        if (h > 0f && h != lessonHeight) {
            val grew = h > lessonHeight
            lessonHeight = h
            if (grew && !userPanned && height > 0) {
                transformMatrix.reset()
                transformMatrix.postTranslate(0f, -lastQuestionY + 40f)
                scaleFactor = 1.0f
                post { invalidate() }
            }
        }

        for (action in undoStack) {
            when (action) {
                is Action.DrawAction -> {
                    canvas.drawPath(action.path, action.paint)
                }
                is Action.TextAction -> {
                    val p = Paint().apply {
                        color = action.textItem.color
                        textSize = action.textItem.textSize
                        isAntiAlias = true
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val lines = action.textItem.text.split("\n")
                    var currentY = action.textItem.y
                    for (line in lines) {
                        canvas.drawText(line, action.textItem.x, currentY, p)
                        currentY += p.textSize * 1.3f
                    }
                }
            }
        }

        canvas.restore()
    }

    private fun scheduleSnapshot() {
        snapshotHandler.removeCallbacksAndMessages(null)
        snapshotHandler.postDelayed({
            val hash = undoStack.hashCode() + lesson.hashCode()
            if (hash != lastSnapshotHash) {
                lastSnapshotHash = hash
                CoroutineScope(Dispatchers.IO).launch {
                    val bitmap = getBitmap()
                    onDrawingChanged?.invoke(bitmap)
                }
            }
        }, 1000)
    }

    fun getBitmap(): Bitmap {
        val w = if (width > 0) width else 1
        val h = if (height > 0) height else 1
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawLesson(canvas, w, all = true)

        for (action in undoStack) {
            when (action) {
                is Action.DrawAction -> {
                    canvas.drawPath(action.path, action.paint)
                }
                is Action.TextAction -> {
                    val p = Paint().apply {
                        color = action.textItem.color
                        textSize = action.textItem.textSize
                        isAntiAlias = true
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val lines = action.textItem.text.split("\n")
                    var currentY = action.textItem.y
                    for (line in lines) {
                        canvas.drawText(line, action.textItem.x, currentY, p)
                        currentY += p.textSize * 1.3f
                    }
                }
            }
        }
        return bitmap
    }
}