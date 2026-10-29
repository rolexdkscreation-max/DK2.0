package com.dk.assistant

/**
 * चैट स्क्रीन: कौन से messages अभी स्क्रीन पर नहीं आए.
 * पहले गिनती से तय होता था - पर DKState सिर्फ़ आख़िरी 200 रखता है; 200 पूरे होने के बाद
 * नया message आते ही सबसे पुराना हटता है, गिनती वही रहती है -> आपका भेजा message दिखता ही नहीं था.
 * अब: आख़िरी दिखाया message (वही object) याद, उसके बाद वाले सब नए.
 */
object ChatSync {
    /** नए messages कहाँ से शुरू: 0..size; -1 = आख़िरी दिखाया अब सूची में नहीं (साफ़ हुई/बहुत बदली) -> पूरी दोबारा */
    fun <T : Any> firstNew(msgs: List<T>, lastShown: T?): Int {
        if (lastShown == null) return 0
        val i = msgs.indexOfLast { it === lastShown }
        return if (i < 0) -1 else i + 1
    }
}
