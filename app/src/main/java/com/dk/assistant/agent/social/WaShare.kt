package com.dk.assistant.agent.social

import java.util.Locale

/**
 * नवंबर 2026 (मालिक: "WhatsApp पे कैमरा से फ़ोटो खींचकर भेजना, करंट / लाइव लोकेशन भेजना, किसी का नंबर भेजना").
 * शुद्ध नियम (JUnit): भेजने वाला text + WhatsApp स्क्रीन पर कौन-कौन से बटन किस क्रम में दबाने हैं.
 * असली काम: [com.dk.assistant.agent.android.WaLook.uiSteps] / GeminiLiveTools (whatsapp_task).
 */
object WaShare {
    /** Google Maps लिंक - WhatsApp पर खुलते ही नक्शे में जगह */
    fun mapsLink(lat: Double, lon: Double): String = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)

    /** करंट लोकेशन का संदेश (Hinglish) */
    fun locationText(lat: Double, lon: Double, place: String): String {
        val p = place.trim()
        return (if (p.isEmpty()) "Meri current location" else "Meri current location: $p") + "\n" + mapsLink(lat, lon)
    }

    /** किसी का नंबर भेजना: "Rahul Sharma ka number: +91 98xxxxxx" */
    fun contactText(name: String, number: String): String = "${name.trim()} ka number: ${number.trim()}"

    /** एक कदम: इनमें से कोई भी बटन (viewId का आख़िरी हिस्सा / desc / text) */
    data class Step(val what: String, val ids: List<String>, val labels: List<String>)

    /** चैट के अंदर: 📷 → शटर → भेजें */
    val CAMERA_STEPS = listOf(
        Step("कैमरा", listOf("camera_btn", "camera_button"), listOf("camera", "कैमरा")),
        Step("फ़ोटो खींचना", listOf("shutter", "capture_button", "shutter_button"), listOf("take photo", "shutter", "capture", "फ़ोटो लें", "फोटो लें", "शटर")),
        Step("भेजें", listOf("send", "send_button"), listOf("send", "भेजें")),
    )

    /** चैट के अंदर: 📎 → Location → Share live location → भेजें */
    val LIVE_LOCATION_STEPS = listOf(
        Step("अटैच (📎)", listOf("input_attach_button", "attach_button"), listOf("attach", "अटैच करें", "अटैच")),
        Step("Location", listOf("pickfiletype_location", "pickfiletype_location_holder"), listOf("location", "लोकेशन", "स्थान")),
        Step("Share live location", listOf("live_location_btn", "live_location_row"), listOf("share live location", "लाइव लोकेशन शेयर करें", "लाइव लोकेशन")),
        Step("भेजें", listOf("send", "live_location_send_btn"), listOf("send", "भेजें")),
    )

    /** क्या यह स्क्रीन-तत्व इस कदम का बटन है? */
    fun matches(step: Step, viewId: String, desc: String, text: String): Boolean {
        if (step.ids.any { viewId.endsWith(":id/$it") }) return true
        val d = desc.trim().lowercase(); val t = text.trim().lowercase()
        return step.labels.any { l -> d == l || t == l || (l.length > 5 && (d.startsWith(l) || t.startsWith(l))) }
    }
}
