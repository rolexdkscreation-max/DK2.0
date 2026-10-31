package com.dk.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class DKAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v5.1: assist बुलावा = Live तुरंत पीछे से (स्क्रीन खुलने के साथ-साथ)
        try { DKAccessibilityService.instance?.invokedNow("assist") } catch (_: Exception) {}
        val intent = Intent(this, VoiceActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }
}