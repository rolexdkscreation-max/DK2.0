package com.dk.assistant.backend

import android.app.Activity
import android.content.Context
import com.dk.assistant.Prefs

/**
 * 18 नवंबर 2026 (मालिक): "मालिक वाले ईमेल से लॉगिन करूँ तो पहला संदेश Welcome Boss का आना चाहिए - जिससे पता चले
 * हाँ, मैं लॉगिन कर चुका हूँ". सर्वर ने पहली बार मालिक माना (/license/status) = एक बार "Welcome Boss".
 * घर की स्क्रीन पर भी मालिक के लिए यही अभिवादन. बाकी किसी को "मालिक/बॉस" वाली कोई बात नहीं दिखती.
 */
object WelcomeBoss {
    const val TEXT = "Welcome Boss"
    private const val PENDING = "welcome_boss_pending"

    /** अभी मालिक बना (पहले नहीं था) = दिखाओ */
    fun due(wasOwner: Boolean, nowOwner: Boolean): Boolean = nowOwner && !wasOwner

    /** /license/status के बाद: पहली बार मालिक = याद रखो (स्क्रीन खुलते ही दिखे) */
    fun noteStatus(c: Context, wasOwner: Boolean, nowOwner: Boolean) {
        if (due(wasOwner, nowOwner)) Prefs.edit(c) { putString(PENDING, "1") }
        else if (!nowOwner) Prefs.edit(c) { remove(PENDING) }
    }

    /** बाकी हो तो एक बार दिखाओ */
    fun showIfDue(a: Activity) {
        if (Prefs.str(a, PENDING, "") != "1" || !BackendSession.owner(a)) return
        show(a)
    }

    fun show(a: Activity, then: () -> Unit = {}) {
        Prefs.edit(a) { remove(PENDING) }
        if (a.isFinishing || a.isDestroyed) return
        try {
            com.dk.assistant.UiDialog(a, com.dk.assistant.Glass.DIALOG).setTitle(TEXT)
                .setMessage(BackendSession.email(a)).setPositiveButton("OK") { _, _ -> then() }
                .setOnCancelListener { then() }.show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(a, TEXT, android.widget.Toast.LENGTH_LONG).show(); then()
        }
    }
}
