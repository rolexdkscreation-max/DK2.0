package com.dk.assistant

import android.content.Context
import android.content.Intent

/**
 * v3.102: [LiveConnect] का Android हिस्सा - DK लाइव से स्टोरी/टीचर चलाना, और
 * स्टोरी / वेबसाइट का काम खत्म होते ही DK लाइव को सच्चा नतीजा (वही बोलकर बताए).
 * (DK कंप्यूटर और कोडिंग टीम का नतीजा पहले से TaskStatus से DK लाइव तक जाता है.)
 */
object LiveReport {

    /** DK लाइव चल रही हो तो उसे SYSTEM NOTE (बंद हो तो कुछ नहीं - notification/स्क्रीन पर नतीजा रहता ही है) */
    fun note(t: String) {
        if (!DKState.live || t.isBlank()) return
        try { DKAccessibilityService.instance?.gemLive?.visionNote(t, evidence = true) } catch (e: Exception) { DKLog.d("Live नतीजा नहीं गया: ${e.message}") }
    }

    fun startStory(c: Context, topic: String, minutes: Int): String {
        if (StorySession.busy) return LiveConnect.STORY_BUSY
        val m = LiveConnect.storyMinutes(minutes)
        val t = topic.trim()
        StorySession.start(c, t, m)
        DKLog.d("स्टोरी मोड: DK लाइव से शुरू ($m मिनट)")
        try {
            c.startActivity(Intent(c, StoryWriterActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { DKLog.d("स्टोरी स्क्रीन नहीं खुली: ${e.message}") }
        return LiveConnect.storyStarted(t, m)
    }

    fun teacher(c: Context, on: Boolean): String {
        if (on) { TeacherLink.openByVoice(c); return LiveConnect.teacherResult(true, false) }
        return LiveConnect.teacherResult(false, TeacherLink.closeByVoice())
    }
}
