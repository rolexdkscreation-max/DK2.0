package com.dk.assistant.voice.st3

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 20 नवंबर 2026 (मालिक: "स्टोरी डबिंग में डर के, चिल्ला के, रो के बोल रहा है - सुनने में पता ही नहीं चलता, सारी आवाज़ें
 * एकदम नॉर्मल बोले जा रही हैं"): जड़ - [St3Story.mood] सिर्फ़ रफ़्तार (±10%) और ऊँचाई (gain) बदलता था, और gain भी मॉडल
 * की कच्ची आवाज़ पर लगता था (हर लाइन का अपना स्तर, ऊपर से 0.85 की नरम सीमा = चीख दब जाती थी). सुर (pitch) और काँपना
 * कहीं नहीं. Supertonic 3 में "भाव" का बटन नहीं, इसलिए कहानी बनाते समय (जल्दी नहीं, आराम से) लाइन की आवाज़ पर असली
 * साधन:
 *  - [level]: हर लाइन पहले एक ही स्तर पर - फिर Mood का gain सचमुच ऊँचा/धीमा सुनाई देता है.
 *  - [pitchRatio] + [resample]: सुर ऊपर (डर, खुशी, चीख) / नीचे (दुख, गंभीर). मॉडल से पहले ही उतनी धीमी/तेज़ आवाज़
 *    बनवाते हैं (`speed / ratio`), फिर resample - लंबाई वही रहती है, सिर्फ़ सुर बदलता है (कोई कट-जोड़ नहीं = साफ़).
 *  - [shake]: काँपती आवाज़ - सुर हल्का हिलता (vibrato) + आवाज़ लहराती (tremolo), 5-7 बार प्रति सेकंड, हर बार थोड़ा अलग.
 *  - [strain]: चीख/गुस्सा - तीखी (ऊँची आवृत्ति ज़्यादा) और दबाकर भरी हुई (saturation).
 *  - [airy]: फुसफुसाहट - भारी हिस्सा कम, साँस वाली सरसराहट.
 * Android import नहीं -> JVM test ([St3EmotionTest]). सब तय (seed) - हर बार एक जैसा नतीजा.
 */
object St3Emotion {
    /** हर लाइन इसी RMS पर (फिर Mood का gain) */
    const val TARGET_RMS = 0.075f
    /** सुर इससे ज़्यादा नहीं बदलता (ज़्यादा = आवाज़ बदली-सी लगे) */
    const val MAX_SEMITONES = 3f

    fun pitchRatio(semitones: Float): Float = 2.0.pow(semitones.coerceIn(-MAX_SEMITONES, MAX_SEMITONES) / 12.0).toFloat()

    /** बोलते हिस्से का RMS (चुप्पी नहीं गिनते) */
    fun rms(w: FloatArray): Float {
        var s = 0.0; var n = 0
        for (x in w) if (abs(x) > 0.01f) { s += x * x; n++ }
        return if (n == 0) 0f else sqrt(s / n).toFloat()
    }

    /** लाइन को [target] RMS पर (बहुत धीमी/खाली लाइन को 8x से ज़्यादा नहीं बढ़ाते) */
    fun level(w: FloatArray, target: Float = TARGET_RMS): FloatArray {
        val r = rms(w)
        if (r <= 0f) return w
        val g = (target / r).coerceAtMost(8f)
        return FloatArray(w.size) { w[it] * g }
    }

    /** [ratio] > 1 = सुर ऊपर (और छोटी); < 1 = सुर नीचे (और लंबी). linear interpolation */
    fun resample(w: FloatArray, ratio: Float): FloatArray {
        if (ratio == 1f || w.size < 2) return w
        val n = ((w.size - 1) / ratio).toInt() + 1
        return FloatArray(n) { i ->
            val p = i * ratio
            val k = p.toInt().coerceAtMost(w.size - 2)
            val f = p - k
            w[k] * (1 - f) + w[k + 1] * f
        }
    }

    /**
     * काँपती आवाज़. [amount] 0..1: सुर का हिलना ~[amount] x 60 cents, आवाज़ का लहराना ~[amount] x 45%.
     * [rateHz] प्रति सेकंड इतनी बार (रफ़्तार धीरे-धीरे थोड़ी बदलती - मशीन जैसा एक-सा नहीं).
     */
    fun shake(w: FloatArray, amount: Float, rateHz: Float = 6.5f, rate: Int = St3Config.SAMPLE_RATE): FloatArray {
        val a = amount.coerceIn(0f, 1f)
        if (a == 0f || w.size < 2) return w
        val cents = 60f * a
        val dev = 2.0.pow(cents / 1200.0).toFloat() - 1f
        val depthSamples = dev * rate / (2f * PI.toFloat() * rateHz)
        val am = 0.45f * a
        val out = FloatArray(w.size)
        var phase = 0.0
        for (i in w.indices) {
            val t = i.toDouble() / rate
            val f = rateHz * (1.0 + 0.12 * sin(2 * PI * 0.7 * t) + 0.06 * sin(2 * PI * 1.9 * t + 1.3))
            phase += 2 * PI * f / rate
            val s = sin(phase)
            val d = depthSamples * (1 + s)                      // 0..2D पीछे से पढ़ो = सुर हिलता
            val p = (i - d).coerceAtLeast(0.0)
            val k = p.toInt().coerceAtMost(w.size - 2)
            val fr = (p - k).toFloat()
            val x = w[k] * (1 - fr) + w[k + 1] * fr
            out[i] = x * (1f - am * (0.5f + 0.5f * sin(phase + 0.8).toFloat()))
        }
        return out
    }

    /** चीख/गुस्सा: [amount] 0..1 - ऊँची आवृत्ति उभारो, फिर दबाकर भरो (tanh) */
    fun strain(w: FloatArray, amount: Float): FloatArray {
        val a = amount.coerceIn(0f, 1f)
        if (a == 0f || w.isEmpty()) return w
        val k = 0.9f * a
        val drive = 1f + 4f * a
        val norm = tanh(drive.toDouble()).toFloat()
        val peak = w.maxOf { abs(it) }.coerceAtLeast(1e-4f)
        var prev = 0f
        return FloatArray(w.size) { i ->
            val x = w[i] / peak
            val bright = x + k * (x - prev)
            prev = x
            (tanh((drive * bright).toDouble()).toFloat() / norm) * peak
        }
    }

    /** फुसफुसाहट: भारी (नीचे का) हिस्सा कम + आवाज़ के साथ चलती सरसराहट */
    fun airy(w: FloatArray, rate: Int = St3Config.SAMPLE_RATE): FloatArray {
        if (w.isEmpty()) return w
        val hp = (1.0 / (1.0 + 2 * PI * 500.0 / rate)).toFloat()
        val env = 1f - (1.0 / (1.0 + 2 * PI * 30.0 / rate)).toFloat()
        val rnd = java.util.Random(7L)
        var y = 0f; var px = 0f; var e = 0f
        return FloatArray(w.size) { i ->
            val x = w[i]
            y = hp * (y + x - px); px = x
            e += env * (abs(x) - e)
            0.55f * y + 0.9f * e * (rnd.nextFloat() * 2f - 1f)
        }
    }

    /** पूरी लाइन पर [St3Story.Mood] के साधन: स्तर -> सुर -> काँपना / चीख / फुसफुसाहट -> फिर स्तर -> gain */
    fun perform(w: FloatArray, m: St3Story.Mood, rate: Int = St3Config.SAMPLE_RATE): FloatArray {
        if (w.isEmpty()) return w
        var x = level(w)
        if (m.pitch != 0f) x = resample(x, pitchRatio(m.pitch))
        if (m.shake > 0f) x = shake(x, m.shake, m.shakeHz, rate)
        if (m.strain > 0f) x = strain(x, m.strain)
        if (m.airy) x = airy(x, rate)
        return St3Story.applyGain(level(x), m.gain)
    }
}
