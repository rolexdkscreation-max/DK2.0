package com.dk.assistant

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Bundle

class DKVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onCreate() {
        super.onCreate()
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSessionService onCreate")
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSessionService onCreate", e)
        }
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSessionService onNewSession")
            return DKVoiceInteractionSession(this)
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSessionService onNewSession", e)
            return DKVoiceInteractionSession(this)
        }
    }
}

class DKVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {

    private var orbView: OrbView? = null
    private var closeButton: android.widget.ImageButton? = null
    private var micButton: android.widget.Button? = null

    override fun onCreateContentView(): android.view.View {
        val context = context
        val container = android.widget.FrameLayout(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }

        val innerLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = 100
            }
            isClickable = false
            isFocusable = false
        }

        val topRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        closeButton = android.widget.ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setOnClickListener {
                try {
                    hide()
                } catch (e: Exception) {
                    android.util.Log.e("DKAssist", "Error hiding session", e)
                }
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(100, 100)
        }
        topRow.addView(closeButton)
        innerLayout.addView(topRow)

        orbView = OrbView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(300, 300).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                try {
                    val intent = android.content.Intent(context, VoiceActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("DKAssist", "Error starting VoiceActivity from orb", e)
                }
            }
        }
        innerLayout.addView(orbView)

        micButton = android.widget.Button(context).apply {
            text = "Mic Permission"
            visibility = android.view.View.GONE
            setOnClickListener {
                try {
                    val intent = android.content.Intent(context, VoiceActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("DKAssist", "Error opening voice activity for mic", e)
                }
            }
        }
        innerLayout.addView(micButton)

        container.addView(innerLayout)
        return container
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSession onShow")
            val window = window?.window
            if (window != null) {
                window.setLayout(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                window.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                window.setBackgroundDrawableResource(android.R.color.transparent)
            }

            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasMic) {
                micButton?.visibility = android.view.View.GONE
            } else {
                micButton?.visibility = android.view.View.VISIBLE
            }
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSession onShow", e)
        }
    }

    override fun onHide() {
        super.onHide()
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSession onHide")
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSession onHide", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSession onDestroy")
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSession onDestroy", e)
        }
    }
}