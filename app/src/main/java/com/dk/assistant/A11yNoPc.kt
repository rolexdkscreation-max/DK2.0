package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "मेरे पास PC नहीं है"): WRITE_SECURE_SETTINGS बिना कंप्यूटर के - Android 11+ का Wireless debugging
 * फ़ोन के अंदर ही adb चलाने देता है. Shizuku (उस adb से जुड़ता है) + aShell (उसमें कमांड). एक बार काफ़ी:
 * इजाज़त फ़ोन restart के बाद भी रहती है, बाद में Shizuku/aShell हटा भी सकते हैं.
 */
object A11yNoPc {
    const val SHIZUKU_URL = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
    const val ASHELL_URL = "https://play.google.com/store/apps/details?id=in.sunilpaulmathew.ashell"

    val STEPS = listOf(
        "1. Wi‑Fi चालू करें (किसी भी Wi‑Fi से जुड़ें — इंटरनेट ज़रूरी नहीं, मोबाइल hotspot भी चलेगा)।",
        "2. Settings → About phone → Build number पर 7 बार टैप → Developer options चालू।",
        "3. Play Store से Shizuku (मुफ़्त) लें → \"Start via Wireless debugging\" → Pairing: Developer options → Wireless debugging → \"Pair device with pairing code\" — जो code दिखे वह Shizuku की notification में डालें → फिर Shizuku में Start।",
        "4. Play Store से aShell (मुफ़्त) लें → Shizuku की इजाज़त Allow → नीचे वाली कमांड चिपकाकर चलाएँ।",
        "5. Xiaomi/Redmi/Poco: Developer options में \"USB debugging (Security settings)\" भी ON करें, वरना कमांड मना हो जाती है।",
        "हो गया — ऊपर \"✓ चालू\" दिखेगा। अब Android कभी DK की Accessibility रोके तो DK खुद (15 मिनट के अंदर या DK खुलते ही) ठीक कर लेगा। Shizuku/aShell बाद में हटा सकते हैं।",
    ).joinToString("\n")
}
