package com.dk.assistant

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Bundle

class DKVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return DKVoiceInteractionSession(this)
    }
}

class DKVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {
}