package com.dk.assistant

/**
 * v3.88: नेटवर्क अपने-आप पहचानो (कोई सेटिंग नहीं). धीमा = छोटे जवाब + एक ही मॉडल से खोज.
 * तेज़ = पहले जैसा.
 * नियम का हिस्सा pure है ([classify]) ताकि JVM test हो सके; Android का हिस्सा [NetProbe] में.
 */
object NetSpeed {
    enum class Level { FAST, SLOW }

    @Volatile var level: Level = Level.FAST
        private set
    @Volatile private var setupMs: Long = 0L
    @Volatile private var bwKbps: Int = -1

    /** downKbps < 0 = पता नहीं. setupMs = Live/सर्वर से जुड़ने में लगा समय (0 = पता नहीं) */
    fun classify(downKbps: Int, setupMs: Long): Level = when {
        downKbps in 0 until 600 -> Level.SLOW          // ~2G / बहुत कमज़ोर 3G
        setupMs > 2_500 -> Level.SLOW                   // जुड़ने में ही 2.5s+ = रास्ता धीमा
        else -> Level.FAST
    }

    fun onBandwidth(kbps: Int) { bwKbps = kbps; level = classify(bwKbps, setupMs) }
    fun onSetup(ms: Long) { setupMs = ms; level = classify(bwKbps, setupMs) }

    val slow: Boolean get() = level == Level.SLOW

    /** Live/चैट के निर्देश में जोड़ने वाली छोटी पंक्ति (तेज़ नेटवर्क पर कुछ नहीं) */
    fun promptNote(): String = if (slow)
        "\nNETWORK IS SLOW right now: keep every reply short (1-2 sentences) unless the user asks for detail. Do not mention the network."
    else ""
}
