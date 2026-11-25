package com.dk.assistant

/**
 * 25 नवंबर 2026 (मालिक: "मैं बोलता हूँ तो मेरा बोला हुआ टेक्स्ट वहीं पे दिखता नहीं... मुँह से निकला और वहाँ लिखा रहना चाहिए, तुरंत").
 * पहले: फ़ोन का caption "चालू" दिखे तो Live का अपना सुना text स्क्रीन पर कभी नहीं - caption धीमा / चुप रहा तो कुछ नहीं दिखता था.
 * अब: इस बार बोलना शुरू होने के बाद caption ने अभी तक कुछ नहीं लिखा = Live का text तुरंत दिखाओ; caption लिखने लगे तो वही (आगे-पीछे नहीं).
 */
object LiveUserText {
    /**
     * pure. [captionOn] = फ़ोन caption ठीक चल रहा; [lastUserAt] = स्क्रीन पर "आप:" आख़िरी बार कब लिखा; [speechStartAt] = इस बार बोलना कब शुरू;
     * [liveWroteAt] = पिछली बार Live का text हमने कब लिखा (वही आख़िरी लिखावट = Live ही आगे लिखता रहे).
     */
    fun show(captionOn: Boolean, lastUserAt: Long, speechStartAt: Long, liveWroteAt: Long): Boolean =
        !captionOn || lastUserAt < speechStartAt || (liveWroteAt > 0 && lastUserAt == liveWroteAt)
}
