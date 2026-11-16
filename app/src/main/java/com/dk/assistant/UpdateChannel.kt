package com.dk.assistant

/**
 * 16 नवंबर 2026 (मालिक: "बीटा और स्टेबल अपडेट जो लगाने हो, लगाओ").
 *
 * - (मालिक: "बीटा नहीं, सीधे स्टेबल") main में हर merge = **स्टेबल** release ("Latest", body में `CHANNEL: stable`) - सबके फ़ोन पर.
 * - बीटा अलग: Actions → build workflow → Run → branch + "beta" ✓ = GitHub "prerelease", body में `CHANNEL: beta`.
 * - ऐप: Settings → अपडेट → "बीटा अपडेट" बंद (शुरू से) = सिर्फ़ स्टेबल; चालू = बीटा भी (जो सबसे नया).
 * - हर release में `SHA256: <hash>` - download के बाद मिलान ([Updater]).
 * Pure (JVM test: UpdateChannelTest).
 */
object UpdateChannel {
    /** release body की ऊपर वाली लाइनें (--- से पहले) में CHANNEL: beta */
    fun isBetaBody(head: List<String>): Boolean =
        head.firstOrNull { it.startsWith("CHANNEL:") }?.substringAfter(":")?.trim()?.lowercase()?.startsWith("beta") == true

    fun sha(head: List<String>): String =
        head.firstOrNull { it.startsWith("SHA256:") }?.substringAfter(":")?.trim()?.lowercase()
            ?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }.orEmpty()

    fun shaMatches(expected: String, actual: String) = expected.isNotBlank() && expected.trim().equals(actual.trim(), ignoreCase = true)
}
