package com.dk.assistant.inspector

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.R
import com.dk.assistant.inspector.InspectorCore.Incident
import com.dk.assistant.inspector.InspectorCore.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Debug में "DK Inspector" कॉलम (बाकी Debug हिस्से वैसे ही रहते हैं)।
 * सारा भारी काम background thread पर; हर हिस्सा try/catch में।
 */
object InspectorCard {

    private fun fmt(t: Long) = if (t <= 0) "अभी नहीं" else
        java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

    private fun sevColor(i: Incident) = when {
        i.status == Status.RESOLVED -> 0xFF4ADE80.toInt()
        i.severity == InspectorCore.Severity.CRITICAL -> 0xFFFF4D6D.toInt()
        i.severity == InspectorCore.Severity.HIGH -> 0xFFFF9F43.toInt()
        i.severity == InspectorCore.Severity.MEDIUM -> 0xFFFFD166.toInt()
        else -> 0xFF8AB4F8.toInt()
    }

    fun build(a: AppCompatActivity, box: LinearLayout, dp: Float,
              button: (String, (View) -> Unit) -> Button, row: (LinearLayout, Array<View>) -> Unit) {
        fun tv(size: Float, color: Int, mono: Boolean = false) = TextView(a).apply {
            textSize = size; setTextColor(color); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
            if (mono) typeface = Typeface.MONOSPACE
        }
        val status = tv(13f, 0xFFFFFFFF.toInt()); box.addView(status)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (10 * dp).toInt()))
        val stepTv = tv(12f, 0xB3FFFFFF.toInt()).apply { visibility = View.GONE }; box.addView(stepTv)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        fun refresh() {
            try {
                val xs = Inspector.incidents(a)
                status.text = "Inspector Status: " + (if (Inspector.running) "जाँच चल रही है…" else "तैयार (सिर्फ़ पढ़ता है, कोड नहीं बदलता)") +
                    "\nLast Inspection: " + fmt(Inspector.lastRun(a)) +
                    "\nNext Scheduled Inspection: " + fmt(Inspector.nextRun(a)) + " (लगभग हर घंटे, बैटरी बचाने के लिए फ़ोन तय करता है)" +
                    "\nTotal Detected Issues: ${xs.size} • Critical: ${xs.count { it.severity == InspectorCore.Severity.CRITICAL && it.status != Status.RESOLVED }}" +
                    " • Resolved: ${xs.count { it.status == Status.RESOLVED }}"
                list.removeAllViews()
                if (xs.isEmpty()) list.addView(tv(12f, 0x80FFFFFF.toInt()).apply { text = "Bug Reports: अभी कोई नहीं" })
                xs.forEach { i -> list.addView(item(a, i, dp, button, row) { refresh() }) }
            } catch (_: Throwable) { status.text = "Inspector Status: पढ़ नहीं पाया" }
        }

        row(box, arrayOf(button("Run Deep Inspection") { v ->
            if (Inspector.running) return@button
            v.isEnabled = false; bar.visibility = View.VISIBLE; stepTv.visibility = View.VISIBLE; bar.progress = 0
            a.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    Inspector.inspect(a, auto = false) { n, t -> a.runOnUiThread { bar.progress = n; stepTv.text = t } }
                }
                v.isEnabled = true; bar.visibility = View.GONE; stepTv.text = r.text; refresh()
            }
        }, button("ताज़ा करें") { refresh() }))

        // Ask DK Inspector
        box.addView(tv(13f, 0xB3FFFFFF.toInt()).apply { text = "Ask DK Inspector" })
        val q = EditText(a).apply {
            hint = "जैसे: DK देर से क्यों बोलता है? / माइक में दिक्कत? / DKI-XXXXXX"
            setBackgroundResource(R.drawable.glass_input); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt())
            textSize = 14f; val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; isSingleLine = true
        }
        box.addView(q, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val ans = tv(12f, 0xE6FFFFFF.toInt(), mono = true).apply { setTextIsSelectable(true); visibility = View.GONE }
        row(box, arrayOf(button("पूछें") {
            val question = q.text.toString().trim()
            if (question.isEmpty()) return@button
            a.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { try { InspectorCore.answer(question, Inspector.snapshot(a)) } catch (e: Throwable) { "जवाब नहीं बन पाया" } }
                ans.visibility = View.VISIBLE; ans.text = r
            }
        }, button("हल हुई हटाएँ") { Inspector.clearResolved(a); refresh() }))
        box.addView(ans)
        box.addView(tv(13f, 0xB3FFFFFF.toInt()).apply { text = "Bug Reports" })
        box.addView(list)
        refresh()
    }

    private fun item(a: AppCompatActivity, i: Incident, dp: Float, button: (String, (View) -> Unit) -> Button,
                     row: (LinearLayout, Array<View>) -> Unit, refresh: () -> Unit): View {
        val v = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_input)
            val pd = (10 * dp).toInt(); setPadding(pd, pd, pd, pd)
        }
        v.addView(TextView(a).apply {
            text = "● ${i.id} • ${i.title}"; textSize = 14f; setTextColor(sevColor(i))
        })
        v.addView(TextView(a).apply {
            text = "${i.severity.hi} • ${i.status.hi} • ${i.component.hi} • ${i.count} बार • ${i.certainty.name}"
            textSize = 11f; setTextColor(0xB3FFFFFF.toInt())
        })
        val full = TextView(a).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(0xE6FFFFFF.toInt()); setTextIsSelectable(true); visibility = View.GONE
        }
        v.addView(full)
        v.setOnClickListener {
            full.visibility = if (full.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (full.visibility == View.VISIBLE) full.text = InspectorCore.fullReport(i)
        }
        val btns = mutableListOf<View>(button("Copy Full Bug Report") {
            val cm = a.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("DK bug ${i.id}", InspectorCore.fullReport(i)))
            Toast.makeText(a, "पूरी रिपोर्ट कॉपी हो गई", Toast.LENGTH_SHORT).show()
        })
        if (i.status != Status.FIXING && i.status != Status.RESOLVED) btns += button("Coding Agent को दें") {
            AlertDialog.Builder(a).setTitle("सर, क्या ${i.id} Coding Agent को दे दूँ?")
                .setMessage("Coding Agent पहले खुद वजह पक्की करेगा, फिर ठीक करके test + build करेगा। Merge आपकी मंज़ूरी के बिना नहीं होगा।")
                .setPositiveButton("हाँ, दे दो") { _, _ ->
                    val r = Inspector.sendToCoder(a, i.id, approved = true)
                    Toast.makeText(a, if (r.startsWith("SENT")) "रिपोर्ट Coding Agent को दे दी" else r.take(120), Toast.LENGTH_LONG).show()
                    refresh()
                }.setNegativeButton("नहीं", null).show()
        }
        row(v, btns.toTypedArray())
        val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, 0) }
        wrap.addView(v)
        return wrap
    }
}
