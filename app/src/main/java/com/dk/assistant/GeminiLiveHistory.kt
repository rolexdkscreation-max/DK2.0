package com.dk.assistant

// GeminiLive का हिस्सा (20 नवंबर 2026, मालिक: "Gemini को सिर्फ़ मेरा नया सवाल जाए"): हर turn का context लॉग,
// स्थिर हिस्से की server वाली असली गिनती, तरीका A (तंग compression) / B (सत्र चुपचाप नया) का फ़ैसला. नियम [HistoryOnDemand] में.

/** server का usageMetadata (promptTokenCount) आया - हर turn पर एक बार आता है */
internal fun GeminiLive.histUsage(t: Long) {
    if (!histOn) { log("इस turn पर context $t token (~${t / 1000}k)"); return }
    // नए (बिना handle वाले) सत्र की पहली गिनती = स्थिर हिस्सा (निर्देश + tools + ज़रूरी बातें + पूँछ) + पहला सवाल
    if (!histFixedReal && !connResumed && turnsSinceFresh <= 1) {
        histFixedReal = true
        log("इतिहास: स्थिर हिस्सा (server की असली गिनती) = $t token [$model] - setup में अंदाज़ा $histFixed था")
        Prefs.setLiveFixedTokens(svc, model, t)
        histFixed = t
    }
    val before = histMethod
    if (!compressLimits) histMethod = HistoryOnDemand.Method.B_ROTATE
    val recent = synchronized(histCtx) { histCtx.addLast(t); while (histCtx.size > 6) histCtx.removeFirst(); histCtx.toList() }
    if (histMethod == HistoryOnDemand.Method.A_COMPRESSION && histTrigger > 0 &&
        HistoryOnDemand.ineffective(recent, histTrigger)) {
        histMethod = HistoryOnDemand.Method.B_ROTATE
        log("इतिहास: तरीका A की छँटाई असर नहीं कर रही (लगातार ${HistoryOnDemand.OVER_TURNS} बार ${histTrigger + 2_500} token से ऊपर) - अब तरीका B")
    }
    val label = histMethod.label
    if (label != histMethodLogged || before != histMethod) {
        histMethodLogged = label
        log("इतिहास: सेक्शन 2 का तरीका = $label - " + when {
            !compressLimits -> "यह मॉडल context की सीमा (triggerTokens) नहीं लेता, इसलिए भारी होने पर सत्र चुपचाप नया (पहले से तैयार connection)"
            histMethod == HistoryOnDemand.Method.B_ROTATE -> "server की छँटाई से context नीचे नहीं आया"
            else -> "मॉडल ने छोटी सीमा मान ली (${histTrigger} token पर छँटाई) - बात बिना रुके, सत्र नहीं बदलता"
        })
    }
    log("इस turn पर context $t token (स्थिर ~$histFixed${if (histFixedReal) " असली" else " अंदाज़ा"}, सीमा ${if (compressLimits) histTrigger else 0}) [तरीका ${if (histMethod == HistoryOnDemand.Method.A_COMPRESSION) "A" else "B"}]")
    if (!freshRotateDue && HistoryOnDemand.rotateDue(histMethod, t, turnsSinceFresh, histFixed)) {
        freshRotateDue = true
        log("इतिहास: तरीका B - context $t token / $turnsSinceFresh बारियाँ: अगले शांत पल में सत्र चुपचाप नया")
    }
}
