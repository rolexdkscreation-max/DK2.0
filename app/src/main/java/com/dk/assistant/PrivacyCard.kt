package com.dk.assistant

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper

/**
 * 23 नवंबर 2026 (मालिक: निजता): [PrivacyGate] का हाँ / नहीं कार्ड - सामने खुली DK स्क्रीन पर ([UiDialog], पूरे ऐप वाला
 * ठोस Clay पॉपअप). DK सामने नहीं (फ़ोन लॉक / दूसरा ऐप) = कार्ड नहीं, सिर्फ़ आवाज़ में पूछना (Live) - बिना हाँ कुछ नहीं देखा जाता.
 * बाहर टैप / back = "नहीं".
 */
object PrivacyCard {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var open: AlertDialog? = null

    fun install(top: () -> Activity?) {
        PrivacyGate.showCard = show@{ kind, answer ->
            val a = top()?.takeIf { AppVisible.visible && !it.isFinishing && !it.isDestroyed } ?: return@show
            main.post { show(a, kind, answer) }
        }
    }

    private fun show(a: Activity, kind: PrivacyGate.Kind, answer: (Boolean) -> Unit) {
        if (a.isFinishing || a.isDestroyed) return
        try { open?.dismiss() } catch (e: Exception) { Quiet.note(e) }
        var done = false
        fun reply(yes: Boolean) { if (!done) { done = true; answer(yes) } }
        val d = UiDialog(a, Glass.DIALOG)
            .setTitle(PrivacyGate.QUESTION)
            .setMessage("DK ${kind.hi} देखना चाहता है - सिर्फ़ इसी एक काम के लिए। आप \"हाँ\" दबाएँगे तभी देखेगा।")
            .setPositiveButton("हाँ") { _, _ -> reply(true) }
            .setNegativeButton("नहीं") { _, _ -> reply(false) }
            .setOnCancelListener { reply(false) }
            .create()
        d.setOnDismissListener {
            if (open === d) { open = null }
            reply(false)
        }
        open = d
        d.show()
    }
}
