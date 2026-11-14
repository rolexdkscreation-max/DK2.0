package com.dk.assistant

/**
 * 14 नवंबर 2026 (मालिक: "स्क्रीन देखो कहते ही तुरंत देखकर बताओ, पहले शेयर की पूछताछ मत करो").
 *
 * DK लाइव के लिए स्क्रीन देखने का एक ही नियम (BrainManager के system prompt में जाता है):
 *  - "स्क्रीन देखो / क्या दिख रहा है / ये पढ़ो" = look_screen तुरंत (Accessibility screenshot + स्क्रीन का text, कोई popup नहीं).
 *  - ऐप खोलकर उसमें काम = phone_task (वह खुद स्क्रीन पढ़कर दबाता है).
 *  - screen_share (हर सेकंड तस्वीर, Android का popup) सिर्फ़ तब जब मालिक खुद "स्क्रीन शेयर करो / लगातार देखते रहो" कहे.
 *  - पेमेंट / पासवर्ड वाली स्क्रीन पर कुछ नहीं दबाना (ScreenLook / PayGuard पहले से रोकते हैं).
 */
object ScreenSeeRule {
    const val TEXT = "SEEING: when the user says स्क्रीन देखो / देखो स्क्रीन पर क्या है / ये पढ़ो / look at my screen, call look_screen IMMEDIATELY " +
        "(it takes a screenshot and reads the on-screen text right now, no popup) and tell him what it returns. NEVER ask \"स्क्रीन शेयर चालू करूँ?\" first " +
        "and never tell him to share the screen for this. To open an app and work inside it use phone_task - it reads the screen and taps by itself. " +
        "Use screen_share on=true only when he himself asks to share the screen or to keep watching it continuously. " +
        "For \"कैमरा से देखो\" call camera on=true. Describe the screen only from what look_screen returns or after the SYSTEM NOTE that sharing is ON; never pretend to see. " +
        "Never tap anything on payment / UPI / password screens."
}
