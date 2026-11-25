package com.dk.assistant

/**
 * 25 नवंबर 2026 (मालिक: "DK लेंस कभी-कभी नहीं खुलता"): लेंस दोबारा कोशिश का हिसाब - pure (JVM test).
 * कैमरा की गलती (अंग्रेज़ी, लंबी) -> सीधी हिंदी वजह जो toast में दिखे.
 */
object LensRetry {
    /** n = कितनी बार फेल हो चुका (0 से) -> अगली कोशिश से पहले कितना रुकें */
    fun delayMs(n: Int): Long = when (n) { 0 -> 500L; 1 -> 900L; else -> 1500L }

    fun reason(raw: String?): String {
        val t = raw.orEmpty().lowercase()
        return when {
            t.contains("in use") || t.contains("camera_in_use") || t.contains("max_cameras") || t.contains("busy") ->
                "कैमरा अभी कोई दूसरा ऐप चला रहा है — उसे बंद करके फिर दबाएँ"
            t.contains("disabled") || t.contains("policy") -> "फ़ोन ने कैमरा बंद कर रखा है (सेटिंग्स → निजता → कैमरा चालू करें)"
            t.contains("permission") || t.contains("security") -> "कैमरा की इजाज़त नहीं मिली — इजाज़त देकर फिर दबाएँ"
            t.contains("सामने नहीं") || t.contains("lifecycle") || t.contains("destroyed") -> "स्क्रीन बदल गई — स्कैन फिर दबाएँ"
            t.isBlank() -> "कैमरा नहीं खुला — स्कैन फिर दबाएँ"
            else -> raw.orEmpty().take(120)
        }
    }
}
