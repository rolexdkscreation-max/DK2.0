package com.dk.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.GestureDetectorCompat
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
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val action = redoStack.pop()
            undoStack.push(action)
            invalidate()
        }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        invalidate()
    }

    fun addBoardText(text: String, x: Float = 60f, y: Float = 120f + (undoStack.size * 55f) % 800f) {
        val item = TextItem(text, x, y, currentColor, currentTextSize)
        val action = Action.TextAction(item)
        undoStack.push(action)
        redoStack.clear()
        invalidate()
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

    fun getBitmap(): Bitmap {
        val w = if (width > 0) width else 1080
        val h = if (height > 0) height else 1920
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

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