package com.dk.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

class DKRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, listener: Callback) {
        try {
            Log.d("DKAssist", "onStartListening called")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onStartListening", e)
        }
    }

    override fun onCancel(listener: Callback) {
        try {
            Log.d("DKAssist", "onCancel called")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onCancel", e)
        }
    }

    override fun onStopListening(listener: Callback) {
        try {
            Log.d("DKAssist", "onStopListening called")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onStopListening", e)
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            Log.d("DKAssist", "DKRecognitionService created")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onCreate", e)
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            Log.d("DKAssist", "DKRecognitionService destroyed")
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in onDestroy", e)
        }
    }
}