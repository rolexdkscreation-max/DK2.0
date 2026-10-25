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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher)
        
        brain = BrainManager(this)
        whiteboard = findViewById(R.id.whiteboardView)
        
        findViewById<android.widget.ImageButton>(R.id.btnVoice).setOnClickListener {
            // वॉइस इनपुट लॉजिक यहाँ आएगा
        }
    }
}

class WhiteboardView(context: android.content.Context, attrs: android.util.AttributeSet) : View(context, attrs) {
    private val path = Path()
    private val paint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> path.moveTo(event.x, event.y)
            MotionEvent.ACTION_MOVE -> path.lineTo(event.x, event.y)
        }
        invalidate()
        return true
    }
}