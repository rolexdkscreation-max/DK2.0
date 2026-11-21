package com.dk.assistant

import com.dk.assistant.voice.Awareness
import com.dk.assistant.voice.VoiceFamily

// GeminiLive का हिस्सा (21 नवंबर 2026, मालिक - अवेयरनेस मोड): हर बात के बाद आवाज़ से कौन बोला (बॉस / मम्मी / पापा ...),
// बोलने वाला बदला = Live को चुपचाप बताओ ([quietNote], turnComplete=false - जवाब नहीं बनता). बंद = कुछ नहीं बदलता.
// "सिर्फ़ Boss की आवाज़" चालू हो (ownerOn) तो वहाँ दूसरी आवाज़ें वैसे ही अनसुनी - तब यह नहीं चलता.

@Volatile private var awareCache = false
@Volatile private var awareAt = 0L

/** अवेयरनेस चालू? (हर 3 सेकंड में एक बार असली जाँच - mic thread पर हल्का) */
internal fun GeminiLive.awareNow(): Boolean {
    if (ownerOn) return false
    val now = System.currentTimeMillis()
    if (now - awareAt > 3_000L) {
        awareAt = now
        val was = awareCache
        awareCache = try { com.dk.assistant.voice.VoiceGuardian.awareOn(svc) } catch (_: Throwable) { false }
        if (awareCache && !was) { com.dk.assistant.voice.VoiceGuardian.prime(svc); log("अवेयरनेस मोड चालू - हर बात के बाद आवाज़ से पहचान (बॉस / परिवार)") }
    }
    return awareCache
}

/** एक बात की पहचान आई (VoiceExec thread पर) */
internal fun GeminiLive.onAwareWho(p: VoiceFamily.Pick, now: Long) {
    val sc = if (p.score.isFinite()) String.format(java.util.Locale.US, "%.2f", p.score) else "-"
    if (p.name == null) { log("अवेयरनेस: आवाज़ पक्की नहीं पहचानी (सबसे ज़्यादा $sc) - नाम नहीं लिया"); return }
    Awareness.global.heard(p, now)
    if (!Awareness.global.shouldNote(p, now)) return
    Awareness.global.noted(p, now)
    log("अवेयरनेस: बोलने वाले ${p.name} (मिलान $sc) - Live को चुपचाप बताया")
    quietNote(Awareness.note(p, Awareness.global.recent(now)))
}
