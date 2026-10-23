package com.dk.assistant

import java.time.LocalDate

/**
 * v3.45: त्योहार / खास दिन (Android import नहीं -> JVM unit test).
 * DK खुद बात शुरू करते समय: आज त्योहार = पहले शुभकामना; user के पिछली बार आने के बाद कोई त्योहार
 * निकल गया = "दशहरा कैसा बीता?". पंचांग के हिसाब से कई त्योहार 1 दिन आगे-पीछे होते हैं, इसलिए ±1 दिन.
 * तारीखें: 2026 astroaum.in, 2027 samvat.in (drikpanchang आधारित). 2027 के बाद: तय तारीख वाले ही.
 */
object Festivals {
    data class Fest(val name: String, val start: LocalDate, val days: Int = 1) {
        val end: LocalDate get() = start.plusDays((days - 1).toLong())
    }

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    /** साल बदलने वाले (पंचांग) त्योहार */
    private val LUNAR = listOf(
        // 2026
        Fest("मकर संक्रांति", d(2026, 1, 14)), Fest("वसंत पंचमी", d(2026, 1, 23)), Fest("महाशिवरात्रि", d(2026, 2, 15)),
        Fest("होली", d(2026, 3, 3), 2), Fest("गुड़ी पड़वा", d(2026, 3, 19)), Fest("राम नवमी", d(2026, 3, 26)),
        Fest("हनुमान जयंती", d(2026, 4, 2)), Fest("अक्षय तृतीया", d(2026, 4, 19)), Fest("गुरु पूर्णिमा", d(2026, 7, 29)),
        Fest("रक्षाबंधन", d(2026, 8, 28)), Fest("जन्माष्टमी", d(2026, 9, 4)), Fest("गणेश चतुर्थी", d(2026, 9, 14)),
        Fest("नवरात्रि", d(2026, 10, 11), 9), Fest("दशहरा", d(2026, 10, 20)), Fest("करवा चौथ", d(2026, 10, 29)),
        Fest("दिवाली", d(2026, 11, 6), 6), Fest("छठ पूजा", d(2026, 11, 15)), Fest("गुरु नानक जयंती", d(2026, 11, 24)),
        // 2027
        Fest("मकर संक्रांति", d(2027, 1, 14)), Fest("वसंत पंचमी", d(2027, 2, 11)), Fest("महाशिवरात्रि", d(2027, 3, 6)),
        Fest("होली", d(2027, 3, 22), 2), Fest("गुड़ी पड़वा", d(2027, 4, 7)), Fest("राम नवमी", d(2027, 4, 15)),
        Fest("हनुमान जयंती", d(2027, 4, 20)), Fest("अक्षय तृतीया", d(2027, 5, 8)), Fest("गुरु पूर्णिमा", d(2027, 7, 18)),
        Fest("रक्षाबंधन", d(2027, 8, 17)), Fest("जन्माष्टमी", d(2027, 8, 24)), Fest("गणेश चतुर्थी", d(2027, 9, 4)),
        Fest("नवरात्रि", d(2027, 9, 30), 9), Fest("दशहरा", d(2027, 10, 9)), Fest("करवा चौथ", d(2027, 10, 18)),
        Fest("दिवाली", d(2027, 10, 27), 5), Fest("छठ पूजा", d(2027, 11, 4)),
    )

    /** हर साल उसी तारीख */
    private val FIXED = listOf(
        "नया साल" to (1 to 1), "गणतंत्र दिवस" to (1 to 26), "शिवाजी जयंती" to (2 to 19), "महाराष्ट्र दिवस" to (5 to 1),
        "स्वतंत्रता दिवस" to (8 to 15), "गांधी जयंती" to (10 to 2), "क्रिसमस" to (12 to 25),
    )

    fun all(year: Int): List<Fest> =
        LUNAR.filter { it.start.year == year || it.end.year == year } + FIXED.map { (n, md) -> Fest(n, d(year, md.first, md.second)) }

    /** आज (±1 दिन) चल रहा त्योहार */
    fun today(day: LocalDate): Fest? =
        (all(day.year) + all(day.year - 1)).firstOrNull { !day.isBefore(it.start.minusDays(1)) && !day.isAfter(it.end.plusDays(1)) && (it.days > 1 || near(it.start, day)) }

    private fun near(a: LocalDate, b: LocalDate) = kotlin.math.abs(a.toEpochDay() - b.toEpochDay()) <= 1

    /** user के पिछली बार आने [from] और आज [to] के बीच जो त्योहार निकल गया (आज वाला नहीं) - सबसे हाल का */
    fun missed(from: LocalDate, to: LocalDate): Fest? {
        if (!from.isBefore(to)) return null
        val t = today(to)
        return (all(to.year) + all(to.year - 1))
            .filter { it != t && it.end.isAfter(from) && it.end.isBefore(to) && to.toEpochDay() - it.end.toEpochDay() <= 10 }
            .maxByOrNull { it.end }
    }
}
