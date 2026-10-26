package com.dk.assistant

/**
 * v3.74: स्क्रीन सिर्फ़ user की इजाज़त से (pure - JUnit से जाँचा जाता है)
 *  - "स्क्रीन देखना" डिफ़ॉल्ट बंद।
 *  - चालू सिर्फ़ user खुद करे: सेटिंग का switch या user की अपनी बोली ("स्क्रीन देखना चालू करो")।
 *    AI/मॉडल अपने-आप इसे चालू नहीं कर सकता (बंद कर सकता है)।
 *  - Live स्क्रीन शेयर का Android popup ("Start") DK खुद नहीं दबाता - user दबाए।
 */
object ScreenGuard {
    const val PREF_KEY = "screenshot"
    /** user ने खुद (v3.74 के बाद) चालू किया - पुराना अपने-आप वाला true मान नहीं गिना जाता */
    const val USER_OK_KEY = "screen_user_ok"
    /** action में यह flag सिर्फ़ user की अपनी बोली/बटन से लगता है */
    const val BY_USER = "by_user"
    /** DK खुद Android का "Start" बटन दबाए? - कभी नहीं */
    const val AUTO_TAP_SHARE_START = false

    /** स्क्रीन देखना बदलने दें? बंद करना हमेशा ठीक; चालू सिर्फ़ user से */
    fun allowSet(on: Boolean, byUser: Boolean): Boolean = !on || byUser

    /** अभी स्क्रीनशॉट ले सकते हैं? दोनों सच हों तभी */
    fun canView(prefOn: Boolean, userOk: Boolean): Boolean = prefOn && userOk
}
