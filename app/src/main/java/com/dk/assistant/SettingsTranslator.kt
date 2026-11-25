package com.dk.assistant

import com.dk.assistant.translate.TrOfflineMt
import com.dk.assistant.translate.TranslatorActivity

/**
 * 25 नवंबर 2026 (मालिक: "DK ट्रांसलेटर - Live Interpreter"): Settings → DK लाइव में एक कार्ड.
 * बाय डिफ़ॉल्ट बंद - यहाँ से या बोलकर ("DK ट्रांसलेटर चालू करो") खुलता है.
 */
internal fun SettingsActivity.translatorCard() {
    card("DK ट्रांसलेटर", "सीधा दुभाषिया: सामने वाला किसी भी भाषा में बोले = आपको हिंदी में सुनाई दे; आप हिंदी बोलें = उसे उसकी भाषा में") { box ->
        row(box, button("DK ट्रांसलेटर खोलें") {
            val r = TranslatorActivity.open(this); if (r.startsWith("ERROR")) toast(r)
        }, button("ऑफ़लाइन मॉडल डाउनलोड") {
            if (TrOfflineMt.downloaded(this)) toast("ऑफ़लाइन मॉडल पहले से तैयार") else { TrOfflineMt.download(this); toast("Wi-Fi पर डाउनलोड शुरू (~374 MB)") }
        })
        hint(box, "बोलकर: \"DK ट्रांसलेटर चालू करो\" = DK लाइव का माइक बंद, ट्रांसलेटर का चालू (एक बार में एक ही). \"ट्रांसलेटर बंद करो\" = वापस DK लाइव. " +
            "हिंदी अनुवाद ईयरफोन / Bluetooth में (न हो तो फ़ोन स्पीकर), सामने वाले की भाषा हमेशा फ़ोन स्पीकर से. सुनना सिर्फ़ फ़ोन के माइक से.")
        hint(box, "ऑनलाइन = Gemini Live (DK लाइव वाली key). ऑफ़लाइन = फ़ोन की बोली-पहचान + यह मॉडल (भारतीय भाषाएँ ↔ हिंदी; हिंदी → अंग्रेज़ी ऑफ़लाइन नहीं).")
    }
}
