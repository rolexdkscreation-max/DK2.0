package com.dk.assistant.agent.android

import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import kotlinx.coroutines.launch

/**
 * नवंबर 2026 (मालिक: "स्क्रीन लॉक रहे, फिर भी DK काम करे"). Android लॉक (PIN/पैटर्न) के ऊपर किसी ऐप की स्क्रीन नहीं चलाने देता -
 * इसलिए: कॉल / SMS / अलार्म / टॉर्च / आवाज़ / गाना जैसे काम बिना स्क्रीन खोले ही होते हैं ([needsScreen] = false). जिन कामों में
 * किसी ऐप की स्क्रीन चाहिए (ऐप खोलना, WhatsApp, स्क्रीन पर टैप...) उनसे पहले [WakeForWork] स्क्रीन जगाकर (PIN सेव हो तो) खुद खोलता है,
 * और WhatsApp का काम पूरा होते ही फ़ोन फिर से लॉक ([relock]) - ताकि फ़ोन लॉक ही रहे. PIN सेव नहीं = मालिक से अनलॉक करने को कहो.
 */
object LockedWork {
    /** Live में अनलॉक का इंतज़ार (WakeForWork का 45s Live के लिए लंबा) */
    const val LIVE_WAIT_MS = 20_000L

    const val LOCKED_NOTE = "NOT DONE: the phone is locked and DK could not open it (no saved PIN, or he did not unlock in time). " +
        "Tell him in one short Hindi line: unlock the phone and ask again - or save the PIN in DK Settings -> फ़ोन लॉक / अनलॉक so DK can open it itself."

    /** फ़ोन पर सीधे होने वाले वे काम जिनमें किसी ऐप की स्क्रीन चाहिए (बाकी - कॉल, SMS, अलार्म, टॉर्च, आवाज़... - लॉक पर भी) */
    private val SCREEN = setOf(
        "open_app", "youtube_search", "youtube_play", "wa_call", "gallery_latest", "steps", "web_search", "open_url", "browser_search",
        "app_search", "click", "long_click", "type", "enter", "scroll", "tap", "swipe", "back", "home", "recents", "notifications",
        "quick_settings", "shop_search", "open_settings", "panel", "directions", "navigate", "map_search", "screen_view",
        "open_dialer", "call_log", "close_app", "close_all_apps", "screenshot_save",
    )

    fun needsScreen(type: String): Boolean = type.trim().lowercase() in SCREEN

    /** whatsapp_task: किनमें स्क्रीन नहीं चाहिए (notification से / पीछे से) */
    private val WA_NO_SCREEN = setOf("auto_chat_on", "auto_chat", "auto_reply_on", "auto_chat_off", "auto_reply_off", "auto_chat_confirm", "auto_chat_cancel",
        "chat_mode_off", "chat_pick", "chat_self", "chat_boss", "chat_reply")
    /** whatsapp_task: कॉल - काम के बाद लॉक नहीं (कॉल की स्क्रीन चाहिए) */
    private val WA_NO_RELOCK = setOf("voice_call", "video_call", "call", "audio_call")

    /** whatsapp_task: पढ़ना - स्क्रीन न खुले तो notification से */
    val WA_READ = setOf("read_unread", "unread", "read")

    fun waNeedsScreen(action: String): Boolean = action.trim().lowercase() !in WA_NO_SCREEN
    fun waRelock(action: String): Boolean = waNeedsScreen(action) && action.trim().lowercase() !in WA_NO_RELOCK

    /** स्क्रीन बंद या लॉक है? */
    fun locked(svc: DKAccessibilityService): Boolean {
        val pm = svc.getSystemService(android.os.PowerManager::class.java)
        val km = svc.getSystemService(android.app.KeyguardManager::class.java)
        return WakeForWork.needWake(pm?.isInteractive != false, km?.isKeyguardLocked == true)
    }

    /** true = स्क्रीन खुली, काम हो सकता है */
    suspend fun open(svc: DKAccessibilityService): Boolean =
        WakeForWork.ensure(svc, LIVE_WAIT_MS) { s -> runCatching { com.dk.assistant.DKState.hint(s) } }

    /** फ़ोन फिर लॉक (Android 9+) */
    fun relock(svc: DKAccessibilityService) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        try { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); DKLog.d("काम पूरा - फ़ोन फिर लॉक किया") }
        catch (e: Exception) { DKLog.d("फ़ोन लॉक नहीं हुआ: ${e.message}") }
    }

    /** whatsapp_task: लॉक हो तो पहले खोलो, काम करो, फिर लॉक (पीछे चलने वाला भेजना = उसके पूरा होने के बाद) */
    suspend inline fun wa(svc: DKAccessibilityService, action: String, block: () -> String): String {
        if (!waNeedsScreen(action) || !locked(svc)) return block()
        // पढ़ना: स्क्रीन न खुले तो भी notification से पढ़ने वाला पुराना रास्ता चले
        if (!open(svc)) return if (action.trim().lowercase() in WA_READ) block() else LOCKED_NOTE
        val r = block()
        if (waRelock(action)) relockWhenIdle(svc)
        return r
    }

    /** SocialAgent का काम (भेजना, पुष्टि के इंतज़ार समेत) खत्म होने तक रुको (3 मिनट तक), फिर लॉक - अगर मालिक खुद फ़ोन नहीं चला रहा */
    fun relockWhenIdle(svc: DKAccessibilityService) {
        svc.scope.launch {
            kotlinx.coroutines.delay(1500)
            val until = System.currentTimeMillis() + 180_000
            while (SocialAgent.busy() && System.currentTimeMillis() < until) kotlinx.coroutines.delay(1000)
            kotlinx.coroutines.delay(1200)
            val fg = runCatching { svc.currentPackage() }.getOrNull().orEmpty()
            if (!locked(svc) && (fg.startsWith("com.whatsapp") || fg == svc.packageName)) relock(svc)
        }
    }
}
