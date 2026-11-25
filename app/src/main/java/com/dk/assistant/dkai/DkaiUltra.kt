package com.dk.assistant.dkai

/**
 * 25 नवंबर 2026 (मालिक): "Select AI Model" की सबसे ऊपर वाली Auto पंक्ति अब "DKAI Ultra" नाम से (DK का लोगो).
 * सिर्फ़ दिखने वाला नाम - पीछे वही Auto (कुछ न चुनना = [SelectedModel] null = काम के हिसाब से अपने-आप मॉडल).
 * कोई नया मॉडल नहीं, सेटिंग की वैल्यू / रीसेट वैसे ही. Android import नहीं -> JVM test.
 */
object DkaiUltra {
    const val NAME = "DKAI Ultra"
    const val SUB = "हर काम में मदद • All-rounded fast"
    /** बाक़ी पंक्तियों जैसा आख़िर में "• Free" */
    const val ROW_SUB = "$SUB • Free"

    /** पुराना अंदरूनी नाम - कुछ न चुना. सिर्फ़ दिखाते समय [NAME] बनता है */
    const val AUTO = "Auto"

    /** इनपुट बार का मॉडल चिप: "Auto" (कुछ न चुना) = DKAI Ultra, बाक़ी चुना हुआ मॉडल वैसा ही */
    fun chip(label: String): String = (if (label == AUTO) NAME else label) + " ▾"
}
