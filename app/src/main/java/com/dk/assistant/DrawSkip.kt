package com.dk.assistant

/**
 * v3.40: UI भाषा बदलने के बाद का एक frame छोड़ना (Android import नहीं -> JVM test).
 * layout के बाद text बदला = उस layout के नाप पुराने (हिंदी) text के थे -> वह frame draw मत करो.
 * लगातार [MAX] बार से ज़्यादा नहीं छोड़ते (कोई text हर बार बदलता रहे तब भी screen कभी नहीं जमती).
 */
class DrawSkip {
    private var pending = false
    private var skipped = 0

    /** layout के बाद अनुवाद ने text बदला */
    fun changed() { pending = true }

    /** OnPreDrawListener: true = draw करो, false = यह frame छोड़ो (अगला layout + draw अपने-आप) */
    fun draw(): Boolean {
        if (pending && skipped < MAX) { pending = false; skipped++; return false }
        pending = false; skipped = 0
        return true
    }

    companion object { const val MAX = 2 }
}
