package com.dk.assistant.plus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 15 नवंबर 2026: DK AI Plus की शुरुआत. ऐप शुरू होते ही ([com.dk.assistant.DKApp]) और फ़ोन चालू होने पर
 * ("Start on Boot" चालू हो तब ही) - जो सुविधा चालू है सिर्फ़ वही शुरू होती है; सब बंद = कुछ नहीं चलता.
 */
object PlusBoot {
    @Volatile private var hooked = false

    fun init(c: Context) {
        val app = c.applicationContext
        EventTriggers.sync(app)
        // 15 नवंबर 2026: हटाए गए 3D कैरेक्टर की पुरानी डाउनलोड फ़ाइलें चुपचाप मिटाओ (पीछे, ऐप खुलने में देर नहीं)
        Thread {
            try { com.dk.assistant.plus.models.ModelManager.cleanupRemoved(app) } catch (_: Throwable) {}
            // 15 नवंबर 2026 (मालिक): वॉलपेपर हटा - पुराना गैलरी वाला चित्र (filesDir/wallpaper) चुपचाप मिटाओ, crash नहीं
            try { java.io.File(app.filesDir, com.dk.assistant.ui.NeonTheme.LEGACY_WALL_DIR).deleteRecursively() } catch (_: Throwable) {}
        }.start()
        if (!hooked) {
            hooked = true
            FeatureFlags.listen { key, _ -> if (key == FeatureFlags.EVENTS_ON || key.startsWith("plus_ev_")) EventTriggers.sync(app) }
        }
    }

    /** Boot पर: "Start on Boot" बंद = कुछ नहीं (default) */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != "android.intent.action.QUICKBOOT_POWERON") return
            if (!FeatureFlags.on(c, FeatureFlags.START_ON_BOOT)) return
            init(c)
        }
    }
}
