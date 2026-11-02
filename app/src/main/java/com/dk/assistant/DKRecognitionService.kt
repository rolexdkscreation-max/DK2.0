package com.dk.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

class DKRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, listener: Callback) {
        try {
            Log.d("DKAssist", "onStartListening called")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onStartListening", e); com.dk.assistant.DKBug.report("assistant", e, "Error in onStartListening", alsoLog = true)
        }
    }

    override fun onCancel(listener: Callback) {
        try {
            Log.d("DKAssist", "onCancel called")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onCancel", e); com.dk.assistant.DKBug.report("assistant", e, "Error in onCancel", alsoLog = true)
        }
    }

    override fun onStopListening(listener: Callback) {
        try {
            Log.d("DKAssist", "onStopListening called")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onStopListening", e); com.dk.assistant.DKBug.report("assistant", e, "Error in onStopListening", alsoLog = true)
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            Log.d("DKAssist", "DKRecognitionService created")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onCreate", e); com.dk.assistant.DKBug.report("assistant", e, "Error in onCreate", alsoLog = true)
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            Log.d("DKAssist", "DKRecognitionService destroyed")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onDestroy", e); com.dk.assistant.DKBug.report("assistant", e, "Error in onDestroy", alsoLog = true)
        }
    }
}