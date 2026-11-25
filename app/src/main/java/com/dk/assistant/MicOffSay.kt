package com.dk.assistant

/**
 * 25 नवंबर 2026 (मालिक): Live बंद होने पर क्या बोलें. बटन / ✕ / island / सूचना से आपने खुद काटा = कुछ नहीं (चुपचाप).
 * बोलकर बंद कराया ("माइक बंद करो" - why "बोला: …" / "user ने कहा: …") = छोटा, साफ़ [SAY]. Android import नहीं -> JVM test.
 */
object MicOffSay {
    const val SAY = "ठीक है बॉस।"

    fun line(why: String): String? = if (why.startsWith("बोला:") || why.startsWith("user ने कहा:")) SAY else null
}
