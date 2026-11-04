package com.dk.assistant

import com.dk.assistant.voice.VoiceCmdMatch.Cmd
import com.dk.assistant.voice.VoiceCmds

/**
 * नवंबर 2026: बिना इंटरनेट वाली आवाज़-कमांड का काम (फ़ोन पर ही). तीनों सुनने वाले रास्ते यहीं भेजते हैं:
 * "Hey DK" loop, पुराना `hear()` loop, Gemini Live का माइक. एक ही बात दो बार नहीं ([VoiceCmds.claim]).
 * लॉक / अनलॉक = वही पुराना पक्का रास्ता ([PhoneLockVoice.quick] -> PIN [PhoneUnlocker] डालता है).
 */
internal fun DKAccessibilityService.runVoiceCmd(hit: VoiceCmds.Hit, from: String): Boolean {
    if (hit.cmd == Cmd.FOUND && !FindPhone.active) return false          // बज ही नहीं रहा - "मिल गया" आम बात है
    // v4.6: "डीके फोन उठा लो" सिर्फ़ घंटी के समय - वरना आम बात, आगे (STT/AI) जाने दो
    if ((hit.cmd == Cmd.ANSWER || hit.cmd == Cmd.ANSWER_SPEAKER) && !callRinging()) return false
    if (!VoiceCmds.claim(hit.cmd)) return true
    DKLog.d("आवाज़-कमांड चली ($from, बिना इंटरनेट): ${hit.cmd.hi}")
    LatencyBoard.count("आवाज़-कमांड बिना इंटरनेट: ${hit.cmd.id}")
    when (hit.cmd) {
        Cmd.LOCK -> agent.runQuick(PhoneLockVoice.quick(PhoneLockVoice.Act.LOCK))
        Cmd.UNLOCK -> agent.runQuick(PhoneLockVoice.quick(PhoneLockVoice.Act.UNLOCK))
        Cmd.FIND -> { DKState.reply(FindPhone.SAY); FindPhone.start(this, from) }
        Cmd.FOUND -> { FindPhone.stop("\"मिल गया\" ($from)"); DKState.reply("ठीक है बॉस") }
        Cmd.ANSWER -> answerByVoice(false, from)
        Cmd.ANSWER_SPEAKER -> answerByVoice(true, from)
    }
    return true
}
