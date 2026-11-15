package com.dk.assistant

import android.app.Activity
import android.content.Intent

/**
 * 15 नवंबर 2026 (मालिक: "जब भी अपडेट आएगा, तो होम स्क्रीन पे हाईलाइट होते रहेगा, पॉप अप आ जाएगा ... लोग अपडेट कर लेगा").
 * होम पर: अपडेट वाली पट्टी हल्के-हल्के चमकती रहती है ([pulse]) + एक popup "नया अपडेट आया है" (अभी अपडेट करें / बाद में).
 * popup हर नए version पर एक बार, \"बाद में\" दबाया तो [AGAIN_MS] बाद फिर - हर बार ऐप खोलते ही नहीं (परेशान न करे).
 * Settings में अपडेट की जगह: मुख्य पन्ने पर सबसे नीचे.
 */
object UpdatePrompt {
    const val AGAIN_MS = 12L * 60 * 60 * 1000
    private const val P_CODE = "upd_pop_code"
    private const val P_AT = "upd_pop_at"

    /** popup दिखाएँ? नया version है और (यह version पहली बार, या पिछली बार को [AGAIN_MS] हो गए). Pure */
    fun shouldPop(code: Int, current: Int, lastCode: Int, lastAt: Long, now: Long): Boolean =
        code > current && (code != lastCode || now - lastAt >= AGAIN_MS || now < lastAt)

    /** होम की पट्टी अपडेट वाली है? (उसी को चमकाना है, key वाली को नहीं). Pure */
    fun isUpdateAlert(text: String): Boolean = text.trimStart().startsWith("⬆")

    /** popup का लिखा. Pure */
    fun message(typeLabel: String, version: String, size: String, notes: String, ready: Boolean): String = buildString {
        append("DK $version का $typeLabel आया है")
        if (size.isNotBlank() && !size.startsWith("0.0")) append(" ($size)")
        append(if (ready) "। डाउनलोड हो चुका है — बस इंस्टॉल दबाइए।" else "। अपडेट करने पर नए फ़ीचर और सुधार मिलेंगे।")
        val n = notes.trim()
        if (n.isNotBlank()) append("\n\nक्या नया है:\n").append(n.take(600))
    }

    fun maybeShow(a: Activity, rel: Updater.Release) {
        if (a.isFinishing || a.isDestroyed) return
        val now = System.currentTimeMillis()
        val sp = a.getSharedPreferences("dk_prefs", Activity.MODE_PRIVATE)
        if (!shouldPop(rel.code, Updater.currentCode(a), sp.getInt(P_CODE, 0), sp.getLong(P_AT, 0L), now)) return
        sp.edit().putInt(P_CODE, rel.code).putLong(P_AT, now).apply()
        val ready = try { Updater.downloaded(a, rel) != null } catch (_: Exception) { false }
        try {
            UiDialog(a, Glass.DIALOG)
                .setTitle("नया अपडेट आया है • DK ${rel.version}")
                .setMessage(message(rel.typeLabel, rel.version, rel.sizeMb, rel.notes, ready))
                .setPositiveButton(if (ready) "इंस्टॉल करें" else "अभी अपडेट करें") { _, _ ->
                    a.startActivity(Intent(a, SettingsActivity::class.java).putExtra("section", "update"))
                }
                .setNegativeButton("बाद में", null)
                .show()
        } catch (e: Exception) { DKLog.d("update popup: ${e.message}") }
    }

    /** होम पर अपडेट की पट्टी: धीरे-धीरे चमकती रहे (जब तक दिखे) */
    fun pulse(v: android.view.View) {
        if (v.getTag(R.id.chipUpdate) == true) return
        v.setTag(R.id.chipUpdate, true)
        android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
            android.animation.PropertyValuesHolder.ofFloat(android.view.View.ALPHA, 1f, 0.55f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_X, 1f, 1.03f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_Y, 1f, 1.03f, 1f)).apply {
            duration = 1400; repeatCount = android.animation.ValueAnimator.INFINITE
            v.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(x: android.view.View) {}
                override fun onViewDetachedFromWindow(x: android.view.View) { cancel() }
            })
            start()
        }
    }
}
