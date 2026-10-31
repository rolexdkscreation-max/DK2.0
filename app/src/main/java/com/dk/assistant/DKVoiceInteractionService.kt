package com.dk.assistant

import android.service.voice.VoiceInteractionService

import android.util.Log

class DKVoiceInteractionService : VoiceInteractionService() {
    override fun onCreate() {
        try {
            super.onCreate()
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in DKVoiceInteractionService.onCreate", e)
        }
    }

    override fun onReady() {
        try {
            super.onReady()
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in DKVoiceInteractionService.onReady", e)
        }
    }
}