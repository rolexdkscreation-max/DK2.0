package com.dk.assistant.voice.engine

/**
 * Gemini TTS (Cloudflare Worker "dk-brain") क्यों नहीं बोला - साफ़ हिंदी कारण. Pure logic, JVM test.
 * worker/dk-brain/src/index.js के जवाब: 401 = APP_TOKEN मेल नहीं, 500 "GEMINI_API_KEY secret नहीं",
 * 400 = text/voice गलत, 502 = कोई TTS मॉडल नहीं चला, Google का 401/403/429 वैसे ही आगे.
 */
object WorkerWhy {
    fun http(code: Int, body: String?): String {
        val b = (body ?: "").lowercase()
        return when {
            code == 401 && b.contains("unauthorized") ->
                "Worker token मेल नहीं खाता - ऐप के \"Worker token\" और Worker के APP_TOKEN में एक ही चीज़ डालें"
            code == 500 && b.contains("app_token") ->
                "Worker में APP_TOKEN secret नहीं डाली (npx wrangler secret put APP_TOKEN) - वही ऐप के \"Worker token\" में भी डालें"
            code == 500 && b.contains("gemini_api_key") ->
                "Worker में GEMINI_API_KEY secret नहीं डाली (npx wrangler secret put GEMINI_API_KEY)"
            code == 401 || code == 403 -> "Worker की Gemini चाबी गलत है या उस पर TTS की इजाज़त नहीं"
            code == 429 -> "Worker की Gemini चाबी की TTS लिमिट खत्म है"
            code == 404 -> "Worker का पता गलत है (/tts नहीं मिला) - पता https://dk-brain.<नाम>.workers.dev जैसा हो"
            code == 400 -> "Worker को भेजी सेटिंग गलत है (आवाज़ या text)"
            code == 502 || code == 503 || code == 504 -> "Worker से कोई Gemini TTS मॉडल नहीं चला (मॉडल उपलब्ध नहीं)"
            else -> "Worker ने गलती दी (code $code)"
        }
    }

    /** जुड़ ही नहीं पाया / समय सीमा */
    fun network(msg: String?): String {
        val m = (msg ?: "").lowercase()
        return when {
            m.contains("unable to resolve") || m.contains("unknownhost") -> "Worker का पता नहीं मिला - पता या इंटरनेट देखें"
            m.contains("timeout") || m.contains("timed out") -> "वर्कर जवाब नहीं दे रहा (समय सीमा खत्म)"
            else -> "वर्कर जवाब नहीं दे रहा (इंटरनेट या Worker देखें)"
        }
    }
}
