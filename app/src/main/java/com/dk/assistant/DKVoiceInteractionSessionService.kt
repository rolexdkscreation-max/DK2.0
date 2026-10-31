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
}