package com.dk.assistant

import android.widget.SeekBar

/**
 * नवंबर 2026 (मालिक, संदर्भ चित्र "CONTROL CENTER"): मुख्य मेनू के कार्ड पर ही असली स्विच / slider.
 * कोई नया नियम नहीं - वही pref जो अंदर वाले हिस्से लिखते हैं.
 */
internal object MenuQuick {

    /** इस कार्ड पर स्विच हो तो (अभी की हालत, बदलने पर क्या करें); नहीं तो null (कार्ड पर ›) */
    fun switchFor(id: String, a: SettingsActivity): Pair<Boolean, (Boolean) -> Unit>? = when (id) {
        "computer" -> Prefs.computerOn(a) to { on: Boolean -> DkSettingsApply.setFromUi(a, "computer", on); Unit }
        "coder" -> Prefs.coderOn(a) to { on: Boolean -> DkSettingsApply.setFromUi(a, "coder", on); Unit }
        "autoreply" -> com.dk.assistant.autoreply.AutoReply.on(a) to { on: Boolean -> com.dk.assistant.autoreply.AutoReply.setOn(a, on); Unit }
        else -> null
    }

    /** "दिखावट" कार्ड पर काँच की पारदर्शिता का slider (वही जो दिखावट → काँच और चमक में है) */
    fun glassSlider(a: SettingsActivity): SeekBar {
        val T = com.dk.assistant.ui.NeonTheme
        return SeekBar(a).apply {
            max = 100; progress = T.look.glass
            contentDescription = UiLang.s("काँच की पारदर्शिता")
            val tint = android.content.res.ColorStateList.valueOf(T.mix(T.look.main, T.CYAN, 0.35f))
            progressTintList = tint; thumbTintList = tint
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {
                    T.save(a, T.look.copy(glass = progress))
                    com.dk.assistant.ui.NeonRefresh.bars(a)
                    com.dk.assistant.ui.NeonRefresh.invalidateAll(a.window.decorView)
                }
            })
        }
    }

}
