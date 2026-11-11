package com.dk.assistant.backup

import com.dk.assistant.LearnedWays
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * नवंबर 2026 (मालिक: "WhatsApp जैसा - user अपना ईमेल डाले, DK की याददाश्त उसके Google Drive में बैकअप हो;
 * ऐप हटाकर दोबारा डाले तो वही ईमेल डालकर सारी बातचीत वापस"):
 *
 * सिर्फ़ याददाश्त जाती है - और कुछ नहीं (API keys, सेटिंग, फ़ोटो, लाइसेंस कुछ नहीं):
 *  - turns / points / days / dropped  = फ़ोन की लोकल याददाश्त (हर बात, शुरू से) - [com.dk.assistant.LocalMemory]
 *  - facts   = "याद रखो" वाली पक्की बातें
 *  - history = AI का पिछला context, chat = चैट स्क्रीन के संदेश
 *  - learned = दूसरे AI से सीखे तरीके
 *
 * जगह: user के Google Drive का छिपा "appDataFolder" (WhatsApp जैसा) - सिर्फ़ DK पढ़-लिख सकता है,
 * user की दूसरी Drive फ़ाइलें DK को नहीं दिखतीं (scope drive.appdata).
 * रिस्टोर = जोड़ना (merge): फ़ोन में जो है वह नहीं मिटता, बैकअप की जो बात फ़ोन में नहीं - वह जुड़ती है.
 *
 * यह हिस्सा Android-free है (JUnit से पूरा जाँचा जाता है).
 */
object MemoryBackup {
    const val KIND = "dk_memory_backup"
    const val VERSION = 1
    const val FILE_NAME = "dk_memory_backup.json.gz"
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    const val MAX_FACTS = 60

    data class T(val who: String, val text: String, val at: Long, val src: String, val day: String)
    data class P(val day: String, val text: String, val at: Long)

    data class Snapshot(
        val at: Long,
        val facts: List<String> = emptyList(),
        val history: String = "[]",
        val chat: String = "[]",
        val learned: String = "[]",
        val turns: List<T> = emptyList(),
        val points: List<P> = emptyList(),
        val days: List<String> = emptyList(),
        val dropped: List<String> = emptyList(),
    ) {
        val talkCount get() = turns.size
        val keptCount get() = facts.size + points.size + LearnedWays.fromJson(learned).size
    }

    // ------------------------------------------------------------------ ईमेल
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    fun cleanEmail(s: String?): String = s.orEmpty().trim().lowercase()
    fun validEmail(s: String?): Boolean = EMAIL.matches(cleanEmail(s))

    // ------------------------------------------------------------------ पैक / खोलना
    fun toJson(s: Snapshot): String = JSONObject()
        .put("kind", KIND).put("v", VERSION).put("at", s.at)
        .put("facts", JSONArray(s.facts))
        .put("history", arr(s.history)).put("chat", arr(s.chat)).put("learned", arr(s.learned))
        .put("turns", JSONArray().apply { s.turns.forEach { put(JSONObject().put("w", it.who).put("t", it.text).put("a", it.at).put("s", it.src).put("d", it.day)) } })
        .put("points", JSONArray().apply { s.points.forEach { put(JSONObject().put("d", it.day).put("t", it.text).put("a", it.at)) } })
        .put("days", JSONArray(s.days)).put("dropped", JSONArray(s.dropped))
        .toString()

    /** ग़लत / किसी और ऐप की फ़ाइल = null */
    fun fromJson(text: String): Snapshot? = try {
        val o = JSONObject(text)
        if (o.optString("kind") != KIND) null else Snapshot(
            at = o.optLong("at"),
            facts = strs(o.optJSONArray("facts")),
            history = (o.optJSONArray("history") ?: JSONArray()).toString(),
            chat = (o.optJSONArray("chat") ?: JSONArray()).toString(),
            learned = (o.optJSONArray("learned") ?: JSONArray()).toString(),
            turns = objs(o.optJSONArray("turns")).mapNotNull { j ->
                T(j.optString("w"), j.optString("t"), j.optLong("a"), j.optString("s"), j.optString("d")).takeIf { it.text.isNotBlank() && it.who.isNotBlank() }
            },
            points = objs(o.optJSONArray("points")).mapNotNull { j -> P(j.optString("d"), j.optString("t"), j.optLong("a")).takeIf { it.text.isNotBlank() } },
            days = strs(o.optJSONArray("days")),
            dropped = strs(o.optJSONArray("dropped")),
        )
    } catch (_: Exception) { null }

    fun pack(s: Snapshot): ByteArray {
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(toJson(s).toByteArray(Charsets.UTF_8)) }
        return bo.toByteArray()
    }

    fun unpack(bytes: ByteArray): Snapshot? = try {
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
        fromJson(text)
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ रिस्टोर = जोड़ना (कुछ नहीं मिटता)
    data class Merged(
        val facts: List<String>,
        /** छोटी facts सूची में जगह नहीं - ये पक्की याद (points) में जाएँ, फेंकी नहीं */
        val factsOverflow: List<String>,
        val history: String,
        val chat: String,
        val learned: String,
        val newTurns: List<T>,
        val newPoints: List<P>,
        val days: List<String>,
        val dropped: List<String>,
    )

    fun turnKey(t: T) = t.who + "|" + t.at + "|" + t.text

    fun merge(local: Snapshot, backup: Snapshot): Merged {
        // facts: बैकअप वाली पुरानी पहले, फिर फ़ोन वाली (नई) - एक जैसी दोबारा नहीं
        val all = ArrayList<String>()
        for (f in backup.facts + local.facts) if (f.isNotBlank() && all.none { it.equals(f, true) }) all += f
        val overflow = if (all.size > MAX_FACTS) all.subList(0, all.size - MAX_FACTS).toList() else emptyList()
        val facts = all.takeLast(MAX_FACTS)

        val haveTurns = local.turns.mapTo(HashSet()) { turnKey(it) }
        val newTurns = backup.turns.filter { haveTurns.add(turnKey(it)) }
        val havePoints = local.points.mapTo(HashSet()) { it.text }
        val newPoints = backup.points.filter { havePoints.add(it.text) }

        val learned = LearnedWays.toJson(LearnedWays.merge(LearnedWays.fromJson(local.learned), LearnedWays.fromJson(backup.learned)))
        return Merged(
            facts = facts, factsOverflow = overflow,
            history = if (count(local.history) > 0) local.history else backup.history,
            chat = if (count(local.chat) > 0) local.chat else backup.chat,
            learned = learned,
            newTurns = newTurns, newPoints = newPoints,
            days = (local.days + backup.days).distinct(),
            dropped = (local.dropped + backup.dropped).distinct(),
        )
    }

    // ------------------------------------------------------------------ Drive (appDataFolder) - बाहर से दिया जाता है
    data class Remote(val id: String, val modified: String, val size: Long)

    /** असली = [DriveAppData] (OkHttp + Google token); test = नकली */
    interface Store {
        fun find(name: String): Remote?
        fun upload(existingId: String?, name: String, bytes: ByteArray): String
        fun download(id: String): ByteArray
    }

    /** Drive files.list का जवाब -> सबसे नई फ़ाइल */
    fun pickLatest(listJson: String, name: String = FILE_NAME): Remote? = try {
        objs(JSONObject(listJson).optJSONArray("files"))
            .filter { it.optString("name") == name && it.optString("id").isNotBlank() }
            .map { Remote(it.optString("id"), it.optString("modifiedTime"), it.optString("size").toLongOrNull() ?: 0L) }
            .maxByOrNull { it.modified }
    } catch (_: Exception) { null }

    /** needConfirm = Drive का पुराना बैकअप इस फ़ोन की याद से बड़ा - बिना पूछे नहीं बदलेंगे */
    data class Outcome(val ok: Boolean, val message: String, val talks: Int = 0, val kept: Int = 0, val needConfirm: Boolean = false)

    /**
     * नया फ़ोन / दोबारा install के बाद ग़लती से "बैकअप" दबा = Drive की पूरी पुरानी याद खाली फ़ाइल से बदल जाती.
     * इसलिए: फ़ोन में कुछ नहीं या Drive वाला बैकअप बहुत बड़ा = पहले पूछो (रिस्टोर का सुझाव).
     */
    fun warning(s: Snapshot, newBytes: Int, old: Remote?): String? {
        if (old == null) return null
        if (s.talkCount == 0 && s.keptCount == 0) return "इस फ़ोन में अभी DK की कोई बात नहीं है, पर Drive में पुराना बैकअप (${kb(old.size.toInt())}) है. अभी बैकअप किया तो वह खाली से बदल जाएगा - पहले \"रिस्टोर\" करें"
        if (old.size > 20 * 1024 && newBytes * 2 < old.size) return "Drive वाला पुराना बैकअप (${kb(old.size.toInt())}) इस फ़ोन की याद (${kb(newBytes)}) से काफ़ी बड़ा है. बैकअप करने पर पुराना बदल जाएगा - पहले \"रिस्टोर\" करना ठीक रहेगा"
        return null
    }

    /** बैकअप: एक ही फ़ाइल - हर बार नई से बदल जाती है (Drive में जगह नहीं भरती). force = चेतावनी के बाद भी */
    fun backup(store: Store, s: Snapshot, force: Boolean = false): Outcome {
        val bytes = pack(s)
        val old = store.find(FILE_NAME)
        if (!force) warning(s, bytes.size, old)?.let { return Outcome(false, it, needConfirm = true) }
        store.upload(old?.id, FILE_NAME, bytes)
        return Outcome(true, "बैकअप हो गया: ${s.talkCount} बातें, ${s.keptCount} याद रखी बातें (${kb(bytes.size)})", s.talkCount, s.keptCount)
    }

    /** रिस्टोर के लिए बैकअप लाना (जोड़ना फ़ोन वाला हिस्सा करता है) */
    fun fetch(store: Store): Pair<Snapshot?, String> {
        val f = store.find(FILE_NAME) ?: return null to "इस ईमेल के Google Drive में DK का कोई बैकअप नहीं मिला"
        val s = unpack(store.download(f.id)) ?: return null to "बैकअप फ़ाइल ख़राब है - पढ़ी नहीं जा सकी"
        return s to ""
    }

    fun kb(n: Int): String = if (n < 1024 * 1024) "${(n + 1023) / 1024} KB" else String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024.0))

    // ------------------------------------------------------------------ छोटे हिस्से
    private fun arr(s: String): JSONArray = try { JSONArray(s) } catch (_: Exception) { JSONArray() }
    private fun count(s: String): Int = try { JSONArray(s).length() } catch (_: Exception) { 0 }
    private fun strs(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optString(it).takeIf { s -> s.isNotBlank() } }
    private fun objs(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
}
