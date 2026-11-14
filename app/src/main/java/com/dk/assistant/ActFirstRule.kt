package com.dk.assistant

/**
 * 14 नवंबर 2026 (मालिक): "मैंने बोला कपड़े लेना है, पास की दुकान/मॉल ढूँढो, सबसे पास वाले का नाम बताओ और रास्ता लगा दो -
 * तो फटाफट करो. पहले 'हाँ ठीक है, कर रहा हूँ, और क्या चाहिए' मत बोलो. काम करो, उसके बाद ही बात करो."
 *
 * वजह (पहले): [TaskSense.PROMPT] कहता था "WHERE to go न हो तो पूछो" - "पास वाली दुकान" में जगह नहीं लिखी होती,
 * तो AI पहले पूछ लेता था ("कौन-सी दुकान?"), और local_info चलते समय पहले बोलता था. यह नियम उसके बाद जुड़ता है
 * (Live और चैट दोनों में, [BrainManager]) - "सबसे पास / नज़दीक" = जगह पता है, तुरंत काम.
 */
object ActFirstRule {
    const val TEXT = "DO FIRST, TALK AFTER (owner's rule): when the task is clear enough to start, call the tool AT ONCE - " +
        "no preamble like \"हाँ ठीक है\", \"कर रहा हूँ\", \"एक मिनट\", and never end with \"और क्या चाहिए?\". Speak only after the tool result, in one or two short lines. " +
        "\"सबसे पास / नज़दीक / आसपास की\" already answers WHERE - never ask which shop or which area. " +
        "SHOPPING NEAR HIM (\"कपड़े लेना है\", \"कुछ खरीदना है\", \"पास का मॉल / अच्छी दुकान ढूँढो\", \"रास्ता लगा दो\"): call local_info with find = the thing " +
        "('कपड़े', 'मॉल', 'शॉपिंग') and go=true when he wants to go there or asks for the route - DK finds the nearest one and starts Google Maps navigation itself; " +
        "then tell its name and distance. Ask a question only when a required detail truly cannot be guessed (like whom to message)."
}
