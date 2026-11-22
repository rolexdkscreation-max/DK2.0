package com.dk.assistant.livewall

/**
 * एक लाइव-वॉलपेपर पैक का दृश्य-विन्यास (scene.json से). Android-free.
 * kind: "haze" (धूसर = दूरी, आसमान की धुंध से रंगा), "lit" (असली रंग x रोशनी), "near" (सबसे आगे, झील के ऊपर).
 */
data class SkyPack(
    val horizon: Float = 0.62f,
    val sunSize: Float = 0.042f,
    val moonSize: Float = 0.06f,
    val layers: List<Layer> = emptyList(),
    val moon: String = "moon.jpg",
    val stars: String = "stars.csv",
) {
    data class Layer(
        val id: String,
        val file: String,
        val kind: String,
        val width: Float,
        val bottom: Float,
        val height: Float = 0f,
        val parallax: Float = 0f,
        val haze: Double = 0.0,
        val reflect: Boolean = false,
    )
}
