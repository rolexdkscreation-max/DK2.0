package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "ऑफलाइन मोड में बार-बार माइक ऑन-ऑफ का साउंड"):
 * Live मोड + इंटरनेट नहीं = बैकअप मोड पहले हर बात के लिए फ़ोन का SpeechRecognizer खोलता था
 * (online-पहले, नेट नहीं तो तुरंत गलती) -> हर चालू/बंद पर Android की बीप, लगातार.
 * अब: बिना नेट DK का अपना माइक (AudioRecord - कोई बीप नहीं) लगातार सुनता है और
 * सिर्फ़ सिखाई आवाज़-कमांड (लॉक / अनलॉक - मालिक की आवाज़, "किधर हो", "मिल गया") पर काम करता है;
 * बाकी हर बात चुपचाप अनसुनी (न text, न जवाब, न आवाज़).
 */
object OfflineListen {
    /** नेट वापस आया / माइक छोड़ना है - कितनी देर में जाँचें */
    const val WATCH_MS = 1500L

    /** बैकअप मोड (Live नहीं जुड़ा) में सुनना: नेट नहीं = सिर्फ़ आवाज़-कमांड, बिना बीप वाला अपना माइक */
    fun cmdsOnly(backup: Boolean, liveOn: Boolean, online: Boolean): Boolean = backup && liveOn && !online

    /**
     * चलता सुनना कब छोड़ें: नेट लौटा (Google/Live वाला रास्ता), Live बंद, म्यूट या कॉल.
     * आप बोल रहे हों (capturing) तो बीच में नहीं काटते - बात पूरी होने दो.
     */
    fun stopNow(online: Boolean, liveOn: Boolean, micMuted: Boolean, inCall: Boolean, capturing: Boolean): Boolean =
        !capturing && (online || !liveOn || micMuted || inCall)
}
