package com.dk.assistant

/**
 * DK लाइव क्यों नहीं जुड़ा - साफ़ हिंदी कारण (कभी "कुछ गलत हो गया" नहीं). Pure logic, JVM test.
 * close code / HTTP code / server का कारण -> [Kind]. key कभी संदेश में नहीं.
 */
object LiveFailWhy {
    enum class Kind(val short: String, val msg: String, val retryMs: Long) {
        NO_KEY("चाबी नहीं डाली", "Gemini की चाबी नहीं डाली है। Settings → AI दिमाग़ → DK लाइव में Gemini key डालें।", 10 * 60_000L),
        KEY("चाबी गलत है", "चाबी गलत है या उस पर Gemini Live की इजाज़त नहीं। Settings → AI दिमाग़ → DK लाइव में key जाँचें।", 10 * 60_000L),
        QUOTA("लिमिट खत्म", "इस चाबी की Gemini Live की फ्री लिमिट अभी खत्म है। थोड़ी देर बाद या दूसरी key से चलेगा।", 10 * 60_000L),
        MODEL("मॉडल उपलब्ध नहीं है", "मॉडल उपलब्ध नहीं है - इस चाबी पर Gemini Live का कोई मॉडल नहीं चला।", 10 * 60_000L),
        CONFIG("सेटिंग में गलती है", "सेटिंग में गलती है - Gemini ने DK लाइव की शुरुआती सेटिंग नहीं मानी।", 10 * 60_000L),
        NET("इंटरनेट की दिक्कत है", "इंटरनेट की दिक्कत है - Gemini Live का server नहीं मिल रहा।", 60_000L),
        SLOW("इंटरनेट बहुत धीमा है", "इंटरनेट बहुत धीमा है - ${LiveRace.CONNECT_WAIT_MS / 1000} सेकंड में Gemini Live नहीं जुड़ा।", 60_000L),
        /** नवंबर 2026 (log 11:09): नेट चालू (Groq/OpenRouter चले) पर Google के server तक connection खुला ही नहीं */
        UNREACHABLE("इंटरनेट की दिक्कत है - Google तक नहीं पहुँच रहा", "इंटरनेट की दिक्कत है - फ़ोन का नेट चल रहा है, पर Gemini (Google) के server तक connection खुल ही नहीं रहा। VPN, Private DNS या ad-blocker बंद करके, या Wi-Fi और mobile data बदलकर देखें।", 60_000L),
        SERVER("Gemini server जवाब नहीं दे रहा", "Gemini Live का server अभी जवाब नहीं दे रहा।", 60_000L),
        MIC("माइक की अनुमति नहीं मिली", "माइक की अनुमति नहीं मिली। फ़ोन की Settings → Apps → DK → Permissions → Microphone चालू करें।", 5 * 60_000L),
        MIC_BUSY("माइक व्यस्त है", "माइक कोई दूसरा ऐप इस्तेमाल कर रहा है।", 60_000L),
    }

    /** server/OkHttp की गलती (code + कारण) से */
    fun classify(code: Int, reason: String?, online: Boolean = true): Kind {
        val r = (reason ?: "").lowercase()
        if (!online) return Kind.NET
        return when {
            r.contains("माइक की परमिशन") || r.contains("record_audio") -> Kind.MIC
            r.contains("माइक") || r.contains("audiorecord") -> Kind.MIC_BUSY
            r.contains("key नहीं") || r.contains("key नहीं है") -> Kind.NO_KEY
            code == 429 || r.contains("quota") || r.contains("resource_exhausted") || r.contains("rate limit") ||
                r.contains("limit") || r.contains("लिमिट") -> Kind.QUOTA
            code == 401 || code == 403 || r.contains("api key") || r.contains("api_key") || r.contains("permission_denied") ||
                r.contains("unauthenticated") || r.contains("key गलत") -> Kind.KEY
            code == 404 || r.contains("not found") || r.contains("is not supported for") || r.contains("मॉडल नहीं") ||
                r.contains("models/") -> Kind.MODEL
            code == 1007 || code == 400 || r.contains("invalid") || r.contains("unknown name") || r.contains("modalit") ||
                r.contains("cannot find field") -> Kind.CONFIG
            r.contains("server तक नहीं पहुँचा") || r.contains("connection खुला ही नहीं") -> Kind.UNREACHABLE
            r.contains("धीमा") || r.contains("सेकंड में नहीं") -> Kind.SLOW
            r.contains("unknownhost") || r.contains("unable to resolve") || r.contains("failed to connect") ||
                r.contains("timeout") || r.contains("timed out") || r.contains("network") || r.contains("नेट") ||
                r.contains("connection reset") || r.contains("eof") || r.contains("software caused") -> Kind.NET
            else -> Kind.SERVER
        }
    }

    /** स्क्रीन के ऊपर वाली पट्टी */
    fun link(k: Kind): String = "बैकअप मोड • ${k.short}"

    /** स्क्रीन + बोलकर (एक बार) */
    fun backupLine(k: Kind): String =
        "सर, DK लाइव नहीं जुड़ा: ${k.msg} तब तक बैकअप मोड में बात कर रही हूँ - बोलिए।"

    /** तकनीकी हिस्सा (debug log के लिए; key नहीं) */
    fun detail(code: Int, reason: String?): String =
        "code=$code" + (reason?.takeIf { it.isNotBlank() }?.let { " • ${it.take(100)}" } ?: "")
}
