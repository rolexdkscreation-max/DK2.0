package com.dk.assistant.wake

/**
 * 16 नवंबर 2026: openWakeWord का streaming हिस्सा, Python वाले `AudioFeatures._streaming_features` जैसा ही -
 * pure Kotlin (मॉडल चलाना बाहर से [mel] / [emb] / [heads]), ताकि JVM test में नकली मॉडल से जाँच हो सके.
 *
 * हर 80 ms (1280 sample):
 *  1. पिछले 480 + नए 1280 sample -> melspectrogram (8 frame x 32), x/10 + 2
 *  2. आख़िरी 76 mel frame -> embedding (96 अंक)
 *  3. आख़िरी 16 embedding -> हर शब्द का classifier (0..1)
 */
class OwwStream(
    private val mel: (FloatArray) -> Array<FloatArray>,           // 1760 sample (int16 मान float में) -> N x 32
    private val emb: (Array<FloatArray>) -> FloatArray,           // 76 x 32 -> 96
    private val heads: Map<String, (Array<FloatArray>) -> Float>, // 16 x 96 -> score
) {
    private val raw = FloatArray(WakeRules.STEP + WakeRules.MEL_CTX)
    private val melBuf = ArrayDeque<FloatArray>()
    private val featBuf = ArrayDeque<FloatArray>()
    /** reset के बाद कितने असली embedding बने (16 से कम = score भरोसेमंद नहीं) */
    var realFeats = 0; private set

    init { reset() }

    fun reset() {
        java.util.Arrays.fill(raw, 0f)
        melBuf.clear(); repeat(WakeRules.EMB_WIN) { melBuf.addLast(FloatArray(WakeRules.MEL_BINS) { 1f }) }   // openWakeWord जैसा: ones
        featBuf.clear(); realFeats = 0
    }

    /** एक 80 ms टुकड़ा. score तभी जब 16 असली embedding हों, वरना खाली map */
    fun feed(chunk: ShortArray, score: Boolean = true): Map<String, Float> {
        require(chunk.size == WakeRules.STEP)
        System.arraycopy(raw, WakeRules.STEP, raw, 0, WakeRules.MEL_CTX)
        for (i in 0 until WakeRules.STEP) raw[WakeRules.MEL_CTX + i] = chunk[i].toFloat()
        for (row in mel(raw)) {
            melBuf.addLast(FloatArray(WakeRules.MEL_BINS) { j -> row[j] / 10f + 2f })
        }
        while (melBuf.size > WakeRules.EMB_WIN) melBuf.removeFirst()
        featBuf.addLast(emb(melBuf.toTypedArray()))
        while (featBuf.size > WakeRules.FEAT_FRAMES) featBuf.removeFirst()
        realFeats++
        if (!score || realFeats < WakeRules.FEAT_FRAMES) return emptyMap()
        val win = featBuf.toTypedArray()
        return heads.mapValues { (_, h) -> h(win) }
    }
}
