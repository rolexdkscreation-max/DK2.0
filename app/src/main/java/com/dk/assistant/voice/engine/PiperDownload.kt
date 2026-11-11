package com.dk.assistant.voice.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dk.assistant.DKLog
import com.dk.assistant.NetFix
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * नवंबर 2026: Piper की फ़ाइलें पहली बार इंटरनेट से (APK में नहीं - APK का साइज़ वही).
 *
 * तीन चरण, सब पीछे (background) धागे पर:
 *  1. इंजन: sherpa-onnx की .aar में से सिर्फ़ इस फ़ोन की .so (arm64 पर ~9 MB) -> sha256 जाँच
 *  2. भाषा फ़ाइलें: espeak-ng-data (~1 MB, 4 एक साथ)
 *  3. आवाज़ मॉडल (~63 MB) - टूटे तो जहाँ रुका वहीं से (Range), अंत में sha256 जाँच
 * हर चरण नेटवर्क टूटने पर अपने-आप 3 बार (2 s, 4 s रुककर); फिर भी न हो = साफ़ हिंदी संदेश + "दोबारा कोशिश करें".
 * जो चरण पूरा हो गया उस पर निशान (.ok) - दोबारा कभी नहीं उतरता, जब तक यूज़र "मॉडल हटाएँ" न दबाए.
 */
object PiperDownload {
    enum class Kind { IDLE, RUNNING, FAILED, DONE }
    data class State(val kind: Kind, val voice: String = "", val pct: Int = 0, val line: String = "", val msg: String? = null)

    @Volatile var state = State(Kind.IDLE)
        private set
    @Volatile private var cancel = false
    @Volatile private var thread: Thread? = null

    /** सुनने वाले (Settings card). कमज़ोर पकड़ - card बंद हुआ तो अपने-आप छूटे (view ही इन्हें पकड़े रखता है) */
    private val listeners = CopyOnWriteArrayList<java.lang.ref.WeakReference<(State) -> Unit>>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: (State) -> Unit) { listeners.add(java.lang.ref.WeakReference(l)) }

    private fun emit(s: State) {
        state = s
        main.post {
            for (w in listeners) { val l = w.get(); if (l == null) listeners.remove(w) else try { l(s) } catch (_: Throwable) {} }
        }
    }

    val running: Boolean get() = thread?.isAlive == true

    /** डाउनलोड शुरू (पहले से चल रहा = कुछ नहीं). [voice] = कौन-सी आवाज़ */
    @Synchronized fun start(c: Context, voice: String = PiperStore.voice(c)) {
        if (running) return
        val app = c.applicationContext
        cancel = false
        emit(State(Kind.RUNNING, voice, 0, "शुरू हो रहा है…"))
        thread = Thread({ run(app, voice) }, "dk-piper-download").apply { isDaemon = true; start() }
    }

    /** रोको (आधा उतरा मॉडल रहता है - अगली बार वहीं से) */
    fun stop() { cancel = true; thread?.interrupt() }

    /** "मॉडल हटाएँ" के बाद: पुरानी स्थिति साफ़ */
    fun reset() { if (!running) emit(State(Kind.IDLE)) }

    private class Cancelled : IOException("रोका गया")
    private class Fatal(msg: String) : IOException(msg)

    private val http: OkHttpClient by lazy {
        NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
    }

    // ------------------------------------------------------------------ चलाना
    private fun run(c: Context, voiceId: String) {
        val v = PiperStore.voiceOf(voiceId)
        val t0 = System.currentTimeMillis()
        try {
            val lib = PiperStore.abiFor(c.applicationInfo?.nativeLibraryDir, android.os.Build.SUPPORTED_ABIS.toList())
                ?: throw Fatal("यह फ़ोन (प्रोसेसर) Piper आवाज़ नहीं चला सकता — DK पहले की तरह Gemini Live की आवाज़ में बोलता रहेगा।")
            val need = PiperStore.needBytes(c, v.id)
            val free = PiperStore.freeBytes(c)
            if (free < need) throw Fatal("फ़ोन में जगह कम है — Piper के लिए करीब ${need shr 20} MB खाली जगह चाहिए, अभी ${free shr 20} MB है। थोड़ी जगह खाली करके \"दोबारा कोशिश करें\" दबाएँ।")
            PiperStore.root(c).mkdirs()
            // कुल बाइट (प्रतिशत के लिए): इंजन का .aar हिस्सा + भाषा फ़ाइलें + मॉडल
            val total = (if (PiperStore.libOk(c)) 0L else lib.zipEnd) + (if (PiperStore.espeakOk(c)) 0L else 1_000_000L) +
                (if (PiperStore.voiceOk(c, v.id)) 0L else v.onnxSize)
            val done = AtomicLong(0)
            fun tick(step: String) {
                val pct = if (total <= 0) 100 else (done.get() * 100 / total).toInt().coerceIn(0, 99)
                emit(State(Kind.RUNNING, v.id, pct, "$step • ${mb(done.get())} / ${mb(total)} MB"))
            }
            if (!PiperStore.libOk(c)) retry { val from = done.get(); try { fetchLib(c, lib, done) { tick("इंजन (1/3)") } } catch (e: IOException) { done.set(from); throw e } }
            if (!PiperStore.espeakOk(c)) retry { val from = done.get(); try { fetchEspeak(c, done) { tick("भाषा फ़ाइलें (2/3)") } } catch (e: IOException) { done.set(from); throw e } }
            if (!PiperStore.voiceOk(c, v.id)) retry { val from = done.get(); try { fetchVoice(c, v, done) { tick("आवाज़ मॉडल (3/3)") } } catch (e: IOException) { done.set(from); throw e } }
            // इंजन सच में इस फ़ोन पर खुलता है? (मॉडल लोड नहीं - सिर्फ़ .so)
            PiperTtsEngine.loadLib(c)
            DKLog.d("Piper: डाउनलोड पूरा (${v.id}) ${(System.currentTimeMillis() - t0) / 1000} s में, फ़ोन में ${mb(PiperStore.diskBytes(c))} MB")
            emit(State(Kind.DONE, v.id, 100, "डाउनलोड पूरा"))
        } catch (e: Cancelled) {
            emit(State(Kind.IDLE, v.id))
        } catch (e: Throwable) {
            val msg = when (e) {
                is Fatal -> e.message!!
                is UnsatisfiedLinkError -> { PiperStore.libFile(c).delete(); File(PiperStore.libDir(c), ".ok").delete()
                    "Piper का इंजन इस फ़ोन पर नहीं खुला — DK पहले की तरह Gemini Live की आवाज़ में बोलता रहेगा।" }
                is IOException -> if (cancel) null else "इंटरनेट टूट गया या बहुत धीमा है — डाउनलोड पूरा नहीं हुआ। \"दोबारा कोशिश करें\" दबाएँ (जितना आ चुका है, वह दोबारा नहीं उतरेगा)।"
                else -> "डाउनलोड में गड़बड़ी (${e.javaClass.simpleName}) — \"दोबारा कोशिश करें\" दबाएँ।"
            }
            DKLog.d("Piper: डाउनलोड नहीं हुआ - ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            emit(if (msg == null) State(Kind.IDLE, v.id) else State(Kind.FAILED, v.id, 0, "", msg))
        } finally {
            thread = null
        }
    }

    /** नेटवर्क की गड़बड़ी पर 3 कोशिश (2 s, 4 s रुककर). जगह / फ़ोन वाली गड़बड़ी पर दोबारा नहीं */
    private fun retry(block: () -> Unit) {
        var wait = 2_000L
        for (i in 1..3) {
            if (cancel) throw Cancelled()
            try { block(); return } catch (e: Cancelled) { throw e } catch (e: Fatal) { throw e } catch (e: IOException) {
                if (cancel) throw Cancelled()
                if (i == 3) throw e
                DKLog.d("Piper: नेटवर्क गड़बड़ (${e.message?.take(80)}) - ${wait / 1000} s बाद फिर ($i/3)")
                try { Thread.sleep(wait) } catch (_: InterruptedException) { throw Cancelled() }
                wait *= 2
            }
        }
    }

    private fun get(url: String, from: Long = 0L, until: Long = 0L): okhttp3.Response {
        val b = Request.Builder().url(url).header("User-Agent", "DK-Piper/1")
        if (from > 0 || until > 0) b.header("Range", "bytes=$from-" + (if (until > 0) "${until - 1}" else ""))
        val r = http.newCall(b.build()).execute()
        if (!r.isSuccessful) { val code = r.code; r.close()
            if (code == 416 || code in 500..599 || code == 429) throw IOException("HTTP $code")
            throw Fatal("सर्वर से फ़ाइल नहीं मिली (HTTP $code) — थोड़ी देर बाद \"दोबारा कोशिश करें\" दबाएँ।") }
        return r
    }

    /** पढ़े गए बाइट गिनो (प्रतिशत) + रोकने पर तुरंत रुको */
    private class Counting(inp: InputStream, val n: AtomicLong, val onTick: () -> Unit) : FilterInputStream(inp) {
        private var last = 0L
        private fun add(k: Int) {
            if (k > 0) { n.addAndGet(k.toLong()); val now = System.currentTimeMillis(); if (now - last > 250) { last = now; onTick() } }
        }
        override fun read(): Int { if (cancel) throw Cancelled(); val r = super.read(); if (r >= 0) add(1); return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { if (cancel) throw Cancelled(); val r = super.read(b, off, len); add(r); return r }
    }

    // ------------------------------------------------------------------ 1. इंजन (.so)
    private fun fetchLib(c: Context, lib: PiperStore.Lib, done: AtomicLong, tick: () -> Unit) {
        val dir = PiperStore.libDir(c).apply { mkdirs() }
        val part = File(dir, PiperStore.LIB_NAME + ".part")
        val want = "jni/${lib.abi}/${PiperStore.LIB_NAME}"
        get(PiperStore.AAR_URL, 0L, lib.zipEnd).use { r ->
            ZipInputStream(Counting(r.body!!.byteStream(), done, tick)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: throw IOException("इंजन फ़ाइल .aar में नहीं मिली")
                    if (e.name != want) continue
                    val md = MessageDigest.getInstance("SHA-256")
                    part.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val k = z.read(buf); if (k < 0) break; o.write(buf, 0, k); md.update(buf, 0, k) }
                    }
                    if (part.length() != lib.size || hex(md.digest()) != lib.sha256) { part.delete(); throw IOException("इंजन फ़ाइल अधूरी/ख़राब") }
                    break
                }
            }
        }
        val f = PiperStore.libFile(c)
        f.delete()
        if (!part.renameTo(f)) throw IOException("इंजन फ़ाइल रखी नहीं जा सकी")
        f.setReadOnly()          // Android 14+: System.load सिर्फ़ read-only फ़ाइल
        File(dir, ".ok").writeText(lib.abi)
    }

    // ------------------------------------------------------------------ 2. espeak-ng-data
    private fun fetchEspeak(c: Context, done: AtomicLong, tick: () -> Unit) {
        val src = PiperStore.VOICES.first()          // सारी आवाज़ों में यही फ़ाइलें - एक जगह से
        val list = get(PiperStore.treeUrl(src)).use { r -> JSONArray(r.body!!.string()) }
        val files = ArrayList<Pair<String, Long>>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val p = o.optString("path")
            if (o.optString("type") == "file" && PiperStore.keepEspeak(p)) files.add(p to o.optLong("size", -1))
        }
        if (files.size < 50) throw IOException("भाषा फ़ाइलों की सूची अधूरी (${files.size})")
        val tmp = File(PiperStore.root(c), PiperStore.ESPEAK + ".part").apply { mkdirs() }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = files.map { (p, size) ->
                pool.submit<Unit> {
                    val out = File(tmp, p.removePrefix(PiperStore.ESPEAK + "/"))
                    if (out.exists() && out.length() == size) { done.addAndGet(size); return@submit }
                    out.parentFile?.mkdirs()
                    get(PiperStore.fileUrl(src, p)).use { r ->
                        Counting(r.body!!.byteStream(), done, tick).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    }
                    if (size >= 0 && out.length() != size) { out.delete(); throw IOException("अधूरी फ़ाइल $p") }
                }
            }
            for (j in jobs) try { j.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? IOException) ?: IOException(e.cause) }
        } finally { pool.shutdownNow() }
        val dir = PiperStore.espeakDir(c)
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("भाषा फ़ाइलें रखी नहीं जा सकीं")
        File(PiperStore.root(c), ".espeak_ok").writeText(files.size.toString())
    }

    // ------------------------------------------------------------------ 3. आवाज़ मॉडल
    private fun fetchVoice(c: Context, v: PiperStore.Voice, done: AtomicLong, tick: () -> Unit) {
        val dir = PiperStore.voiceDir(c, v.id).apply { mkdirs() }
        val tokens = File(dir, "tokens.txt")
        if (!tokens.exists() || tokens.length() == 0L) {
            val t = File(dir, "tokens.txt.part")
            get(PiperStore.fileUrl(v, "tokens.txt")).use { r -> r.body!!.byteStream().use { i -> t.outputStream().use { o -> i.copyTo(o) } } }
            if (t.length() < 100 || !t.renameTo(tokens)) throw IOException("tokens.txt अधूरी")
        }
        val model = File(dir, v.onnxName)
        val part = File(dir, v.onnxName + ".part")
        if (!model.exists()) {
            var have = if (part.exists()) part.length() else 0L
            if (have > v.onnxSize) { part.delete(); have = 0L }
            done.addAndGet(have)
            if (have < v.onnxSize) {
                get(PiperStore.fileUrl(v, v.onnxName), have).use { r ->
                    val append = have > 0 && r.code == 206
                    if (have > 0 && !append) { done.addAndGet(-have); have = 0L }       // सर्वर ने Range नहीं माना - शुरू से
                    Counting(r.body!!.byteStream(), done, tick).use { i ->
                        java.io.FileOutputStream(part, append).use { o -> i.copyTo(o, 256 * 1024) }
                    }
                }
            }
            if (part.length() != v.onnxSize) throw IOException("मॉडल अधूरा (${part.length()} / ${v.onnxSize})")
            tick()
            if (sha256(part) != v.onnxSha256) { part.delete(); throw IOException("मॉडल फ़ाइल ख़राब निकली") }
            if (!part.renameTo(model)) throw IOException("मॉडल रखा नहीं जा सका")
        } else done.addAndGet(v.onnxSize)
        File(dir, ".ok").writeText(v.rev)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(256 * 1024); while (true) { if (cancel) throw Cancelled(); val k = i.read(b); if (k < 0) break; md.update(b, 0, k) } }
        return hex(md.digest())
    }

    fun hex(b: ByteArray): String = b.joinToString("") { String.format("%02x", it) }
    fun mb(n: Long): String = String.format(java.util.Locale.US, "%.1f", n / 1_048_576.0)
}
