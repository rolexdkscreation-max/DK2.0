package com.dk.assistant

import android.media.MediaRecorder
import android.widget.TextView
import com.dk.assistant.voice.VgRules
import com.dk.assistant.voice.VgStyle
import com.dk.assistant.voice.VoiceEnroll
import com.dk.assistant.voice.VoiceGuardian
import com.dk.assistant.voice.VoicePrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 16 नवंबर 2026 (मालिक): आवाज़ सिखाने का नया तरीका - हर सैंपल पर एक अंदाज़ (धीरे / ज़ोर से / उत्साह से / जल्दी /
 * धीमी / दूर से / फुसफुसाकर) और लिखा वाक्य सिर्फ़ सुझाव: "कुछ भी बोलिए". पहचान आवाज़ की है, शब्दों की नहीं.
 * वॉइस गार्डियन card और वेक वर्ड card दोनों यही इस्तेमाल करते हैं. आवाज़ सिर्फ़ मेमोरी में, पहचान बनते ही छोड़ दी जाती है.
 */
internal class StyledTake(val prompt: VgStyle.Prompt, val emb: FloatArray)

/** हर prompt: अंदाज़ + सुझाव दिखाओ -> रिकॉर्ड -> चुप्पी काटो -> पहचान. हर एक पर 2 कोशिश. (माइक पहले से DK से छुड़ाया हो) */
internal suspend fun SettingsActivity.styledTakes(
    prompts: List<VgStyle.Prompt>, say: TextView,
    source: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
): List<StyledTake> {
    val out = ArrayList<StyledTake>()
    for ((i, p) in prompts.withIndex()) {
        for (attempt in 0 until 2) {
            say.text = UiLang.s(VgStyle.line(i, prompts.size, p))
            val t = if (p.short) VoiceEnroll.record(source = source, endSilenceMs = 600, minSpeechMs = 250)
                else VoiceEnroll.record(source = source)
            if (t == null) { say.text = UiLang.s("आवाज़ नहीं आई — फिर से बोलिए"); continue }
            val min = if (p.short) VgStyle.WAKE_MIN_SPEECH_MS else VgRules.MIN_SPEECH_MS
            if (t.speechMs < min) { say.text = UiLang.s(if (p.short) "थोड़ा साफ़ बोलिए — फिर से" else "थोड़ा लंबा बोलिए (2-3 सेकंड) — फिर से"); continue }
            val e = withContext(Dispatchers.Default) { VoiceGuardian.embed(this@styledTakes, VgRules.trimSilence(t.pcm)) }
            if (e != null) { out += StyledTake(p, e); break }
        }
    }
    return out
}

/** आपस में कम मिलने वाले (शोर / कोई और बोला) छोड़ो: लंबे वाक्यों के औसत से मिलाकर */
internal fun consistentTakes(takes: List<StyledTake>, extraRef: List<FloatArray> = emptyList()): List<StyledTake> {
    val long = takes.filter { !it.prompt.short }
    val ref = VoicePrint.mean(extraRef + long.map { it.emb }) ?: return emptyList()
    return takes.filter { t ->
        val cos = VoicePrint.cosine(t.emb, ref)
        if (t.prompt.short) VgStyle.keepShort(cos) else VgStyle.keep(t.prompt, cos)
    }
}
