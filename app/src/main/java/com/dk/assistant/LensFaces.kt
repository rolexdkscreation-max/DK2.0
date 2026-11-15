package com.dk.assistant

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.Executors

/**
 * 15 नवंबर 2026 (मालिक): "DK के लेंस में भी फ़्यूचरिस्टिक स्कैन डालो - मैं दिखूँ तो पहचाने कि मैं बॉस हूँ, और मेरे साथ दोस्त /
 * फ़ैमिली मेंबर आए तो पहचाने कि यह उसका दोस्त है, नाम क्या है, रिश्ता क्या है - एक साथ दो-चार आदमी भी".
 *
 * DK लेंस (DK लाइव का कैमरा) के हर ~0.33 s फ़्रेम पर (फ़ोन के अंदर, कुछ बाहर नहीं जाता):
 *  1. फ़्रेम के सारे चेहरे ([FaceAI.detectAll], ज़्यादा से ज़्यादा 5),
 *  2. हर चेहरे की पहचान -> Boss / सेव किए लोग ([FacePeople.whoAll], एक नाम एक ही चेहरे पर),
 *  3. AI anti-spoof ([FaceAI.realness]) - Boss की फ़ोटो / स्क्रीन दिखाई तो "DECLINED - फ़ोटो",
 *  4. ऊपर hologram ([HoloScanView]) - नाम + रिश्ता, Boss पर हरा "BOSS AUTHORIZED",
 *  5. सामने वाले लोग कुछ पल पक्के रहें और बदलें, तो DK लाइव को छोटा SYSTEM NOTE ([LiveVision.facesNote]) - DK बोल सके
 *     "ये राहुल हैं, आपके दोस्त". तस्वीर नहीं, सिर्फ़ नाम/रिश्ता.
 * Settings → चेहरा पहचान → "DK लेंस में चेहरा स्कैन" (पहले वाला [Prefs.faceHudOn]) से बंद.
 */
class LensFaces(private val c: Context, private val view: HoloScanView) {
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-lens-face").apply { isDaemon = true } }
    @Volatile private var busy = false
    @Volatile private var stopped = false
    private val votes = HashMap<String, FaceSpoof.Vote>()
    private var lastKey = ""; private var sameCount = 0; private var notedKey = ""; private var notedAt = 0L
    private var bossBannerUntil = 0L; private var bossShown = false
    private var spoofAsked = false

    /** कैमरे के thread से. [mirror] = आगे वाला कैमरा (preview शीशे जैसा) */
    fun frame(b: Bitmap, mirror: Boolean) {
        if (busy || stopped) return
        if (!Prefs.faceHudOn(c) || !FaceAI.ready(c)) { view.post { view.clear() }; return }
        busy = true
        val copy = try { small(b) } catch (_: Throwable) { busy = false; return }
        work.execute {
            try { scan(copy, mirror) } catch (_: Throwable) {} finally { copy.recycle(); busy = false }
        }
    }

    private fun small(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= MAX_SIDE) return b.copy(Bitmap.Config.ARGB_8888, false)
        val s = MAX_SIDE.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun scan(b: Bitmap, mirror: Boolean) {
        if (!spoofAsked && Prefs.faceSpoofOn(c) && !FaceAI.hasSpoof(c)) { spoofAsked = true; Thread { FaceAI.downloadSpoof(c.applicationContext) }.apply { isDaemon = true }.start() }
        val dets = FaceAI.detectAll(c, b)
        val now = System.currentTimeMillis()
        if (dets.isEmpty()) { view.show(HoloScanView.Mode.IDLE, emptyList()); remember(emptyList(), now); return }
        val prints = dets.map { d -> FaceAI.embed(c, b, d) }
        val whos = FacePeople.whoAll(c, prints)
        val spoofOn = FaceAI.spoofReady(c)
        val seen = HashSet<String>()
        val people = dets.indices.map { i ->
            val w = whos[i]
            val key = Rules.key(w, i); seen.add(key)
            val v = votes.getOrPut(key) { FaceSpoof.Vote() }
            if (spoofOn && (w.boss || w.name != null)) FaceAI.realness(c, b, dets[i])?.let { v.add(it) }
            Rules.Seen(w, fake = spoofOn && v.fake(), real = !spoofOn || v.real())
        }
        votes.keys.retainAll(seen)
        val bossReal = people.any { it.who.boss && it.who.sure && it.real && !it.fake }
        val bossFake = people.any { it.who.boss && it.fake }
        if (bossReal && !bossShown) { bossShown = true; bossBannerUntil = now + BANNER_MS }
        if (!people.any { it.who.boss }) bossShown = false
        val faces = dets.indices.mapNotNull { i ->
            val p = people[i]
            view.place(dets[i], b.width, b.height, mirror, Rules.title(p, Prefs.userName(c)), Rules.sub(p), Rules.tone(p))
        }
        val banner = when { bossFake -> HoloScanView.DECLINED; now < bossBannerUntil -> HoloScanView.AUTHORIZED; else -> "" }
        val reason = when { bossFake -> "Boss की फ़ोटो / स्क्रीन — असली इंसान नहीं"; banner.isNotBlank() -> "Boss पहचाने गए"; else -> "" }
        val mode = when { bossFake -> HoloScanView.Mode.DECLINED; banner.isNotBlank() -> HoloScanView.Mode.AUTHORIZED; else -> HoloScanView.Mode.SCAN }
        view.show(mode, faces, bars = if (faces.size == 1) Rules.bars(people[0], spoofOn, votes[Rules.key(people[0].who, 0)]) else emptyList(), banner = banner, reason = reason)
        remember(people, now)
    }

    /** सामने के लोग [STABLE] बार लगातार वही = पक्का; बदला तो DK लाइव को बताओ ([NOTE_GAP_MS] में एक बार से ज़्यादा नहीं) */
    private fun remember(people: List<Rules.Seen>, now: Long) {
        val key = Rules.noteKey(people)
        if (key == lastKey) sameCount++ else { lastKey = key; sameCount = 1 }
        if (sameCount < STABLE || key == notedKey || key.isBlank() || now - notedAt < NOTE_GAP_MS) {
            if (key.isBlank() && sameCount >= STABLE) notedKey = ""
            return
        }
        notedKey = key; notedAt = now
        Rules.note(people, Prefs.userName(c))?.let { LiveVision.facesNote(it) }
    }

    fun stop() { stopped = true; work.shutdown(); view.post { view.clear() } }

    /** Pure - JVM test */
    object Rules {
        class Seen(val who: FacePeople.Who, val fake: Boolean, val real: Boolean)

        fun key(w: FacePeople.Who, i: Int) = when { w.boss -> FacePeople.BOSS; w.name != null -> "p:" + w.name; else -> "?$i" }

        fun title(p: Seen, userName: String): String = when {
            p.who.boss -> "BOSS" + userName.trim().let { if (it.isBlank()) "" else " · $it" }
            p.who.name != null -> p.who.name + if (p.who.sure) "" else " ?"
            else -> "UNKNOWN"
        }

        fun sub(p: Seen): String = when {
            p.fake -> "फ़ोटो / स्क्रीन — असली नहीं"
            p.who.boss -> "मिलान ${(p.who.score * 100).toInt()}%"
            p.who.name != null -> listOf(p.who.relation().let { if (it.isBlank()) "" else it }, "${(p.who.score * 100).toInt()}%").filter { it.isNotBlank() }.joinToString(" · ")
            else -> "अनजान चेहरा"
        }

        fun tone(p: Seen): Int = when {
            p.fake -> HoloScanView.BAD
            p.who.boss -> HoloScanView.OK
            p.who.name != null -> HoloScanView.SCAN
            else -> HoloScanView.WARN
        }

        fun bars(p: Seen, spoofOn: Boolean, v: FaceSpoof.Vote?): List<Pair<String, Float>> = buildList {
            if (p.who.score > 0f) add("MATCH" to p.who.score)
            if (spoofOn && v != null && v.count() > 0) add("AI ANTI-SPOOF" to v.mean())
        }

        /** किसका नोट बने (पक्के नाम + नकली निशान + अनजान गिनती) */
        fun noteKey(people: List<Seen>): String = people.map {
            when {
                it.who.boss && it.who.sure -> "B" + if (it.fake) "!" else ""
                it.who.name != null && it.who.sure -> "P:" + it.who.name + if (it.fake) "!" else ""
                else -> "?"
            }
        }.sorted().joinToString(",")

        /** DK लाइव के लिए छोटा अंग्रेज़ी नोट (नाम/रिश्ते Boss की भाषा में). null = कुछ ख़ास नहीं (सिर्फ़ अनजान) */
        fun note(people: List<Seen>, userName: String): String? {
            val parts = ArrayList<String>()
            for (p in people) {
                val w = p.who
                when {
                    w.boss && w.sure && p.fake -> parts.add("a PHOTO/SCREEN of the user (Boss) - NOT the real person (anti-spoof)")
                    w.boss && w.sure -> parts.add("the user himself (Boss${if (userName.isBlank()) "" else ", $userName"})")
                    w.name != null && w.sure -> {
                        val rel = FacePeople.relationPhrase(w.kind, w.rel)
                        parts.add("${w.name}${if (rel.isBlank()) "" else " ($rel)"}${if (p.fake) " - but only a PHOTO/SCREEN, not real" else ""}")
                    }
                }
            }
            val unknown = people.count { !(it.who.sure && (it.who.boss || it.who.name != null)) }
            if (parts.isEmpty()) return null
            if (unknown > 0) parts.add("$unknown unknown person(s)")
            return "SYSTEM NOTE: DK Lens on-device face scan - in front of the camera now: " + parts.joinToString("; ") + ". " +
                "If it fits the moment, mention it in ONE short natural sentence in the user's language (e.g. \"ये राहुल हैं — आपके दोस्त\"). " +
                "Do not repeat it again unless the people change. Never claim more certainty than this."
        }
    }

    companion object {
        const val MAX_SIDE = 720
        const val STABLE = 3
        const val NOTE_GAP_MS = 8_000L
        const val BANNER_MS = 2_500L
    }
}
