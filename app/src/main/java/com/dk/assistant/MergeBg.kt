package com.dk.assistant

/**
 * नवंबर 2026 (मालिक का screenshot: PR merge के बाद भी "काम चल रहा है • मैनेजर काम बाँट रहा है").
 * असल में तब APK release build (~14 मिनट) का इंतज़ार चल रहा होता था, और उस बीच भेजा नया काम कतार में रुका रहता था.
 * अब: merge होते ही वह काम ख़त्म ("PR merge करो" बटन/आवाज़ से), release की निगरानी पीछे - नतीजा आते ही चैट + आवाज़.
 * स्क्रीन पर merge के समय गिट/रिलीज़ का नाम, मैनेजर का नहीं. Pure -> JUnit: CoderFastTest.
 */
object MergeBg {
    const val MERGING = "🚀 PR की आख़िरी जाँच + merge"
    const val RELEASING = "🚀 APK release build देख रहा हूँ"

    fun note(base: String) = "🚀 Merge हो गया — $base पर नई APK release (करीब 10-15 मिनट) की निगरानी पीछे चल रही है। " +
        "आप अभी नया काम दे सकते हैं; release तैयार होते ही यहीं बताऊँगा।"
}
