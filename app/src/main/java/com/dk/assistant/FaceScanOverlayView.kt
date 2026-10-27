package com.dk.assistant

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

class FaceScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var matchResult: MatchResult = MatchResult.NO_FACE
    private var faceRect: RectF? = null
    private var scanProgress = 0f
    private var rotationAngle = 0f
    private var lastUpdateTime = System.currentTimeMillis()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    fun updateResult(result: MatchResult, rect: RectF?) {
        this.matchResult = result
        this.faceRect = rect
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentTime = System.currentTimeMillis()
        val deltaTime = (currentTime - lastUpdateTime) / 1000f
        lastUpdateTime = currentTime

        updateAnimations(deltaTime)

        val rect = faceRect ?: return
        if (matchResult == MatchResult.NO_FACE) return

        drawReticle(canvas, rect)
        
        when (matchResult) {
            MatchResult.SCANNING -> {
                drawScanningHUD(canvas, rect)
            }
            MatchResult.ACCESS_GRANTED -> {
                drawAccessBadge(canvas, rect, "ACCESS GRANTED", Color.GREEN)
            }
            MatchResult.ACCESS_DENIED -> {
                drawAccessBadge(canvas, rect, "ACCESS DENIED", Color.RED)
            }
            else -> {}
        }
        
        postInvalidateOnAnimation()
    }

    private fun updateAnimations(deltaTime: Float) {
        rotationAngle = (rotationAngle + 240f * deltaTime) % 360f
        scanProgress = (scanProgress + deltaTime * 0.6f) % 1.0f
    }

    private fun drawReticle(canvas: Canvas, rect: RectF) {
        val color = when (matchResult) {
            MatchResult.ACCESS_GRANTED -> Color.GREEN
            MatchResult.ACCESS_DENIED -> Color.RED
            else -> Color.CYAN
        }
        
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        
        val cornerLen = rect.width() * 0.15f
        
        // Corners
        canvas.drawLine(rect.left, rect.top, rect.left + cornerLen, rect.top, paint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLen, paint)
        
        canvas.drawLine(rect.right, rect.top, rect.right - cornerLen, rect.top, paint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLen, paint)
        
        canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLen, rect.bottom, paint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLen, paint)
        
        canvas.drawLine(rect.right, rect.bottom, rect.right - cornerLen, rect.bottom, paint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLen, paint)

        // Glow
        paint.alpha = 50
        paint.strokeWidth = 16f
        canvas.drawRect(rect, paint)
    }

    private fun drawScanningHUD(canvas: Canvas, rect: RectF) {
        // Vertical Scanning Beam
        val y = rect.top + (rect.height() * scanProgress)
        scanLinePaint.shader = LinearGradient(
            rect.left, y, rect.right, y,
            intArrayOf(Color.TRANSPARENT, Color.CYAN, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawLine(rect.left, y, rect.right, y, scanLinePaint)
        
        // Rotating Progress Ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.CYAN
        paint.alpha = 200
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val radius = min(rect.width(), rect.height()) * 0.45f
        
        val oval = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(oval, rotationAngle, 60f, false, paint)
        canvas.drawArc(oval, rotationAngle + 120f, 60f, false, paint)
        canvas.drawArc(oval, rotationAngle + 240f, 60f, false, paint)
    }

    private fun drawAccessBadge(canvas: Canvas, rect: RectF, text: String, color: Int) {
        val pulse = (sin(System.currentTimeMillis() / 150.0) * 0.5 + 0.5).toFloat()
        
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (160 + 40 * pulse).toInt()
        
        val badgeHeight = 80f
        val badgeWidth = rect.width() * 0.85f
        val badgeRect = RectF(
            rect.centerX() - badgeWidth / 2,
            rect.bottom + 40f,
            rect.centerX() + badgeWidth / 2,
            rect.bottom + 40f + badgeHeight
        )
        canvas.drawRoundRect(badgeRect, 12f, 12f, paint)
        
        textPaint.color = Color.WHITE
        textPaint.textSize = 44f
        canvas.drawText(text, badgeRect.centerX(), badgeRect.centerY() + 16f, textPaint)
        
        // Outer glow pulse
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 10f * pulse
        paint.alpha = (100 * pulse).toInt()
        canvas.drawRect(rect, paint)
    }
}