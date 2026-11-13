package com.dk.assistant

import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 13 नवंबर 2026 (मालिक): "island को छोटा-बड़ा करने का, ऊपर से कितनी दूर रखना है उसका option दिखाओ."
 * Settings → ओर्ब और चेहरा → DK Island → दिखावट: आकार (80-130%), ऊपर से दूरी (0-80 dp), बाएँ / दाएँ.
 * पट्टी खिसकाते ही चलता island नए नाप में ([DKIsland.relayout]); छोड़ते ही कुछ सेकंड नमूना ([DKIsland.preview]).
 */
internal fun SettingsActivity.islandLook(box: LinearLayout) {
    // 13 नवंबर 2026 (मालिक): island छोटा-बड़ा, ऊपर से कितनी दूर, बाएँ-दाएँ (कैमरे के छेद के हिसाब से)
    label(box, "दिखावट (खिसकाते ही चलता island बदलता है)")
    val size = islandSlider(box, IslandCore.SIZE_MIN, IslandCore.SIZE_MAX, DKIsland.sizePct(this),
        { v -> UiLang.s("आकार") + ": ${UiLang.s(IslandCore.sizeLabel(v))} ($v%)" }) { DKIsland.setSizePct(this, it) }
    val top = islandSlider(box, IslandCore.TOP_MIN, IslandCore.TOP_MAX, DKIsland.topDp(this),
        { v -> UiLang.s("ऊपर से दूरी") + ": $v dp" }) { DKIsland.setTopDp(this, it) }
    val side = islandSlider(box, IslandCore.SIDE_MIN, IslandCore.SIDE_MAX, DKIsland.sideDp(this),
        { v -> UiLang.s("बाएँ / दाएँ") + ": " + (if (v == 0) UiLang.s("बीच में") else "$v dp") }) { DKIsland.setSideDp(this, it) }
    row(box, button("नमूना देखें") { DKIsland.preview() }, button("पहले जैसा") {
        DKIsland.resetLook(this)
        size.progress = IslandCore.SIZE_DEF - IslandCore.SIZE_MIN; top.progress = IslandCore.TOP_DEF - IslandCore.TOP_MIN; side.progress = 0 - IslandCore.SIDE_MIN
    })
}

/** island की पट्टी: ऊपर लिखा मान, खिसकाते ही [onSet] (उसी समय चलता island बदले) */
private fun SettingsActivity.islandSlider(box: LinearLayout, min: Int, max: Int, cur: Int, text: (Int) -> String, onSet: (Int) -> Unit): SeekBar {
    val t = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0); this.text = text(cur) }
    box.addView(t)
    val bar = SeekBar(this).apply {
        this.max = max - min; progress = (cur - min).coerceIn(0, max - min)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { val v = p + min; t.text = text(v); if (fromUser) onSet(v) }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) { DKIsland.preview() }
        })
    }
    box.addView(bar)
    return bar
}
