package com.dk.assistant

/**
 * 14 नवंबर 2026 (मालिक: "सिंगल key से Live तुरंत डिस्कनेक्ट हो जाता है"):
 * जड़ - एक ही key पर Live + "आप:" लिखने वाला (caption) + standby = Google की फ्री "एक साथ सत्र / प्रति मिनट" वाली
 * छोटी रोक (429 / RESOURCE_EXHAUSTED). पहले इसे "limit खत्म" मानकर मॉडल 30 मिनट बंद होता था; बैकअप key नहीं = कुछ ही
 * मॉडल के बाद Live पूरा बंद ("Gemini Live की फ्री लिमिट खत्म"). अब एक key पर ऐसी छोटी रोक = मॉडल बंद नहीं, लिखने वाला
 * (caption) बंद करके जगह खाली, थोड़ा रुककर उसी बातचीत से फिर जुड़ना. सिर्फ़ असली रोज़ की limit पर पुराना तरीका.
 * Pure - JVM test से जाँच.
 */
object LiveOneKey {
    /** इतनी बार तक छोटी रोक पर रुककर उसी मॉडल से फिर (उसके बाद पुराना तरीका) */
    const val SOFT_TRIES = 5

    /** रोज़ की असली limit (Pacific आधी रात तक) - इस पर रुकने से फ़ायदा नहीं */
    fun daily(reason: String): Boolean {
        val r = reason.lowercase()
        return r.contains("per day") || r.contains("perday") || r.contains("daily") || r.contains("per_day")
    }

    /** एक ही key है और रोक छोटी (प्रति मिनट / एक साथ सत्र) = मॉडल बंद मत करो, रुककर फिर */
    fun soft(keyCount: Int, reason: String, tries: Int): Boolean = keyCount <= 1 && !daily(reason) && tries < SOFT_TRIES

    /** रुकना: 1.5s, 3s, 5s, 8s, 8s */
    fun waitMs(tries: Int): Long = when (tries) { 0 -> 1_500L; 1 -> 3_000L; 2 -> 5_000L; else -> 8_000L }

    /** एक key = लिखने वाला (caption) उसी key पर दूसरा Live सत्र. रोक आई तो उसे बंद करके जगह खाली करो */
    fun dropCaption(keyCount: Int, captionOwnKey: Boolean): Boolean = keyCount <= 1 && !captionOwnKey
}
