package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * v3.87: [TeacherBoard] का Android हिस्सा - पढ़ाई की बात सुनते ही टीचर वाला पन्ना अपने-आप खोलो,
 * "बंद करो / वापस जाओ" पर बंद करो। DKState.user() से बुलाया जाता है (DK Live और आम आवाज़ - दोनों)।
 */
object TeacherLink {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var activity: WeakReference<TeacherActivity>? = null
    /** पन्ना सामने दिख रहा है (कोई काम दूसरा ऐप खोल दे तो false) */
    @Volatile var visible = false
    @Volatile private var lastOpenAt = 0L

    fun attach(a: TeacherActivity) { activity = WeakReference(a); TeacherBoard.open = true }
    fun detach(a: TeacherActivity) {
        if (activity?.get() === a) { activity = null; TeacherBoard.open = false; visible = false }
    }

    fun onUser(ctx: Context?, text: String) {
        val c = ctx ?: return
        val t = text.trim()
        if (t.isBlank()) return
        try {
            when (TeacherBoard.onUser(t)) {
                TeacherBoard.Action.OPEN -> open(c, "पढ़ाई का सवाल")
                TeacherBoard.Action.CLOSE -> main.post { activity?.get()?.closeFromVoice() }
                TeacherBoard.Action.NONE -> {
                    // पन्ना खुला है पर पीछे चला गया (बीच में अलार्म/मैसेज) - नया पढ़ाई का सवाल = फिर सामने
                    if (TeacherBoard.open && !visible && TeacherBoard.isStudy(t)) open(c, "पढ़ाई पर वापस")
                }
            }
        } catch (e: Exception) { DKLog.d("टीचर: ${e.javaClass.simpleName} ${e.message}") }
    }

    private fun open(c: Context, why: String) {
        val now = System.currentTimeMillis()
        if (now - lastOpenAt < 1500) return
        lastOpenAt = now
        DKLog.d("टीचर मोड अपने-आप खुला ($why)")
        main.post {
            try {
                c.startActivity(Intent(c, TeacherActivity::class.java)
                    .putExtra(TeacherActivity.EXTRA_AUTO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) { DKLog.d("टीचर नहीं खुला: ${e.message}") }
        }
    }
}
