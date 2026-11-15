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
