package com.dk.assistant.voice

import java.io.ByteArrayOutputStream

/**
 * v4.6 मालिक: "टेक्स्ट हटाकर 30 सेकंड की आवाज़ रिकॉर्डिंग - उसमें 5 बार बोला जाएगा"।
 *
 * एक ही लंबी रिकॉर्डिंग (30 सेकंड तक) में मालिक अपना वाक्य 5 बार बोलता है, बीच-बीच में रुककर।
 * [Cutter] हर रुकावट पर एक "बार" काट देता है - वही 5 टुकड़े [VoiceCmds.save] को जाते हैं (हर टुकड़े के
 * MFCC). Android/माइक यहाँ नहीं - सिर्फ़ PCM के टुकड़े अंदर, बोले हुए हिस्से बाहर, ताकि test हो सके।
 */
object VoiceTape {
    /** मालिक: "30 सेकंड का वॉइस रिकॉर्डिंग" */
    const val SECONDS = 30
    /** मालिक: "उसमें 5 बार बोला जाएगा" */
    const val TIMES = 5
    /** 5 में से कम से कम इतने साफ़ मिलें तो सीख लो (Model.build को 3 चाहिए) */
    const val MIN_TIMES = 3
    const val CHUNK_MS = 40
    /** इतनी चुप्पी = एक बार पूरा (वाक्य के अंदर छोटी साँस इससे छोटी होती है) */
    const val GAP_MS = 900
    /** बोलना शुरू होने से पहले का इतना हिस्सा भी रखो (पहला अक्षर न कटे) */
    const val PRE_CHUNKS = 6
    /** आख़िरी शब्द के बाद इतनी चुप्पी रखो, बाकी काट दो */
    const val TAIL_MS = 200
    /** 16 kHz, 16-bit mono */
    const val BYTES_PER_MS = 32

    fun ms(pcm: ByteArray) = pcm.size / BYTES_PER_MS

    /** UI: "⏱ 23 सेकंड बचे • ✓ 2/5 बार" */
    fun progress(leftMs: Long, got: Int, want: Int = TIMES): String =
        "⏱ ${((leftMs + 999) / 1000).coerceAtLeast(0)} सेकंड बचे • ✓ $got/$want बार"

    /** रिकॉर्डिंग ख़त्म होने के बाद क्या बताएँ - null = सब ठीक, सीख लो */
    fun verdict(got: Int, tooShort: Int, tooLong: Int): String? = when {
        got >= MIN_TIMES -> null
        tooLong > 0 && tooLong >= tooShort -> "वाक्य बहुत लंबा हो गया या बीच में रुके नहीं - हर बार के बाद 2 सेकंड रुकिए"
        tooShort > 0 -> "आवाज़ बहुत छोटी/धीमी थी - पूरा वाक्य साफ़ और थोड़ा ज़ोर से बोलिए"
        else -> "आवाज़ नहीं आई - माइक के पास बोलिए"
    }

    /**
     * लगातार आती आवाज़ को "बार" में काटता है।
     * [feed] हर 40 ms का टुकड़ा + उसकी ताक़त (RMS) - जब एक बार पूरा हो, वह PCM लौटाता है।
     */
    class Cutter(
        private val gapMs: Int = GAP_MS,
        private val maxMs: Int = VoiceCmdMatch.MAX_MS + 1000,
        /** पहले-बाद की चुप्पी (~440 ms) के अलावा कम से कम ~300 ms बोलना - खाँसी/टक नहीं */
        private val minMs: Int = VoiceCmdMatch.MIN_MS + PRE_CHUNKS * CHUNK_MS,
    ) {
        private val vad = WakeMatch.Vad(CHUNK_MS, endMs = gapMs)
        private val pre = ArrayDeque<ByteArray>()
        private val cur = ByteArrayOutputStream()
        var inSpeech = false; private set
        /** छोटा टुकड़ा (खाँसी/टक) - गिना नहीं */
        var tooShort = 0; private set
        /** बिना रुके बहुत लंबा बोला - गिना नहीं */
        var tooLong = 0; private set

        fun feed(piece: ByteArray, rms: Double): ByteArray? {
            val ev = vad.feed(rms)
            if (!inSpeech) {
                pre.addLast(piece)
                while (pre.size > PRE_CHUNKS) pre.removeFirst()
                if (ev == WakeMatch.Vad.Ev.START) {
                    inSpeech = true
                    cur.reset(); pre.forEach { cur.write(it) }; pre.clear()
                }
                return null
            }
            cur.write(piece)
            if (ev == WakeMatch.Vad.Ev.END) { inSpeech = false; return finish(gapMs - TAIL_MS) }
            if (cur.size() > maxMs * BYTES_PER_MS) {
                // बिना रुके बोलता ही गया - यह "एक बार" नहीं, छोड़ दो और फिर से सुनो
                inSpeech = false; cur.reset(); vad.reset(); tooLong++
            }
            return null
        }

        /** 30 सेकंड पूरे - बीच में चल रहा आख़िरी बार भी ले लो */
        fun flush(): ByteArray? = if (inSpeech) { inSpeech = false; finish(0) } else null

        private fun finish(cutMs: Int): ByteArray? {
            val all = cur.toByteArray(); cur.reset()
            val keep = ((all.size - cutMs.coerceAtLeast(0) * BYTES_PER_MS).coerceAtLeast(0)) and 1.inv()
            val out = all.copyOf(keep)
            if (ms(out) < minMs) { tooShort++; return null }
            return out
        }
    }
}
