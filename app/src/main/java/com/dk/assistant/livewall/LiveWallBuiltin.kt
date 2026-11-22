package com.dk.assistant.livewall

/**
 * पहले वॉलपेपर की जानकारी (नाम + फ़ाइलों के पते + SHA-256) - repo के wallpapers/catalog.json जैसी ही.
 * इंटरनेट से सूची न आए तब भी सेटिंग में नाम दिखे और डाउनलोड हो सके. तस्वीरें ऐप में नहीं हैं, सिर्फ़ उनके पते.
 * LiveWallpaperTest जाँचता है कि यह repo वाली catalog.json और असली फ़ाइलों से मेल खाती है.
 */
object LiveWallBuiltin {
    const val FIRST_ID = "sun_moon_lake"

    val CATALOG_JSON = """
{
  "version": 1,
  "items": [
    {
      "id": "sun_moon_lake",
      "engine": "sky",
      "minEngine": 1,
      "version": 1,
      "title": "सूर्योदय-सूर्यास्त झील",
      "sub": "असली समय और आपकी जगह के हिसाब से सूरज, चाँद (असली कला) और तारे • झील में परछाईं • पेड़-पौधे",
      "credits": "चाँद: NASA SVS CGI Moon Kit (public domain) • तारे: Yale Bright Star Catalog (public domain) • पेड़-पौधे: AI से बने • आसमान: भौतिकी (Rayleigh/Mie/Ozone) से DK में",
      "files": [
        {
          "name": "scene.json",
          "url": "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/wallpapers/sun_moon_lake/v1/scene.json",
          "sha256": "0d6a2d8c72923526c5977186911a5bce6eeab9a741de0150e73d1f51fcf41405",
          "bytes": 487
        },
        {
          "name": "near.webp",
          "url": "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/wallpapers/sun_moon_lake/v1/near.webp",
          "sha256": "c7d419c0093c21124ddfc821e5fd7d64e0c06bd28f477b41a439be4b7b5e05aa",
          "bytes": 1531024
        },
        {
          "name": "mid.webp",
          "url": "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/wallpapers/sun_moon_lake/v1/mid.webp",
          "sha256": "a39f635d9d6d0a5f7cc32321c4af3c6b29b30cd60b4b7964b4bbc7097bffacc1",
          "bytes": 282682
        },
        {
          "name": "far.png",
          "url": "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/wallpapers/sun_moon_lake/v1/far.png",
          "sha256": "6ac2fe4495fd062f9ebbeff846bf72696904811777ee9a65921c7dbd2dc6588c",
          "bytes": 23271
        },
        {
          "name": "moon.jpg",
          "url": "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/wallpapers/sun_moon_lake/v1/moon.jpg",
          "sha256": "355625460250d4f8a1b278a5fab42c879fe74ec639eb6130244c5a4a4c7668cf",
          "bytes": 293215
        },
        {
          "name": "stars.csv",
          "url": "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/wallpapers/sun_moon_lake/v1/stars.csv",
          "sha256": "c5f4e69e4f394ff088bf2171bb385eddcddcd19fc3994a4c9501691e53f28fd5",
          "bytes": 103945
        }
      ]
    }
  ]
}
""".trimIndent()
}
