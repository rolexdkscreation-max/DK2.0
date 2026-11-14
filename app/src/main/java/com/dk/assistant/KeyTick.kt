package com.dk.assistant

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 14 नवंबर 2026 (मालिक): "API key डालेगा तो डॉट-डॉट के रूप में दिखे. key सही हो तो वहीं हरे रंग का टिक आए और फिर हट जाए -
 * पता चले कि हाँ, key सही है और चल रही है. सिर्फ़ लिखा नहीं, आवाज़ में भी."
 * key डालते / पेस्ट करते ही (थोड़ा रुककर) अपने-आप जाँच: सही = हरा ✓ + "API key सही है, चल रही है" बोलकर, 3 सेकंड बाद टिक गायब
 * और key फिर डॉट-डॉट. गलत = लाल ✗ थोड़ी देर. पहचानी न जाने वाली key (जैसे खोज की) पर कुछ नहीं.
 */
object KeyTick {
    const val WAIT_MS = 1_200L
    const val SHOW_MS = 3_000L
    const val OK_TEXT = "✓ API key सही है — चल रही है"
    const val OK_SAY = "API key सही है, चल रही है"
    const val GREEN = 0xFF22C55E.toInt()
    const val RED = 0xFFF87171.toInt()

    /** जाँचने लायक key = उसकी कंपनी (Gemini / Groq / OpenRouter / NVIDIA / Z.ai), नहीं तो null. Pure */
    fun provider(text: String): String? = text.trim().takeIf { it.length >= 20 && !it.contains(' ') }?.let { ApiScopes.detect(it) }

    /** key डॉट-डॉट (छुपी) */
    fun masked(): Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
}

internal fun SettingsActivity.keyTick(box: LinearLayout, e: EditText) {
    val tick = TextView(this).apply {
        textSize = 13f; visibility = View.GONE
        setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
    }
    box.addView(tick)
    val ui = Handler(Looper.getMainLooper())
    var checked = e.text.toString().trim()        // पहले से सेव key = दोबारा नहीं (सिर्फ़ नई डाली)
    val hide = Runnable { tick.visibility = View.GONE }
    val run = Runnable {
        val k = e.text.toString().trim()
        val id = KeyTick.provider(k) ?: return@Runnable
        if (k == checked) return@Runnable
        checked = k
        val p = Providers.byId(id) ?: return@Runnable
        lifecycleScope.launch {
            val ok = try { withContext(Dispatchers.IO) { ModelLister.fetch(p, k, p.baseUrl) }.isNotEmpty() } catch (_: Exception) { false }
            if (e.text.toString().trim() != k) return@launch          // बीच में बदल दी
            ui.removeCallbacks(hide)
            tick.text = if (ok) KeyTick.OK_TEXT else "✗ यह ${p.name} key नहीं चली — दोबारा देखकर डालें"
            tick.setTextColor(if (ok) KeyTick.GREEN else KeyTick.RED)
            tick.visibility = View.VISIBLE
            if (ok) {
                // फिर से डॉट-डॉट ("दिखाओ" दबाया था तब भी)
                if (e.inputType != KeyTick.masked()) { e.inputType = KeyTick.masked(); e.setSelection(e.text.length) }
                launch { PhoneVoice.say(this@keyTick, KeyTick.OK_SAY) }
            }
            ui.postDelayed(hide, if (ok) KeyTick.SHOW_MS else KeyTick.SHOW_MS + 2_000L)
        }
    }
    e.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(run); ui.postDelayed(run, KeyTick.WAIT_MS) }
    })
}
