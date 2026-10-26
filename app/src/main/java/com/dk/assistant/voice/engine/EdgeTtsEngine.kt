package com.dk.assistant.voice.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.widget.LinearLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * इंजन 2: Microsoft Edge TTS (मुफ़्त, बिना key). डिफ़ॉल्ट बंद.
 * आवाज़ें hi-IN-SwaraNeural (महिला) / hi-IN-MadhurNeural (पुरुष). सिर्फ़ गति और पिच बदलते हैं -
 * यह इंजन भावना/स्टाइल के निर्देश नहीं मानता.
 *
 * एक ही WebSocket खुला रहता है (गर्म) - हर टुकड़ा उसी पर (नया हाथ मिलाना नहीं). Edge सिर्फ़ MP3 देता है,
 * इसलिए आते ही फ़ोन के MediaCodec से PCM 24 kHz में बदलकर कतार में.
 * (यह पूरी फ़ाइल हटाओ तो Edge इंजन सेटिंग्स से गायब - बाकी ऐप वैसा ही.)
 */
object EdgeTtsEngine : VoiceEngine {
    override val id = VoiceEngines.EDGE_TTS
    override val title = "Microsoft Edge TTS"
    override val note = "यह इंजन भावना के निर्देश नहीं मानता — सिर्फ़ गति और पिच बदलती है • मुफ़्त, बिना key"
    override val split = true
    override val defaultOn = false

    private val VOICES = listOf(
        "hi-IN-SwaraNeural" to "Swara — हिंदी महिला",
        "hi-IN-MadhurNeural" to "Madhur — हिंदी पुरुष",
    )

    override fun voices() = VOICES
    override fun voice(c: Context): String =
        VoiceEngines.sp(c).getString("edge_voice", null)?.takeIf { v -> VOICES.any { it.first == v } } ?: VOICES[0].first
    override fun setVoice(c: Context, v: String) { VoiceEngines.sp(c).edit().putString("edge_voice", v).apply() }

    /** गति % (-50..+50), पिच Hz (-30..+30) */
    fun rate(c: Context) = VoiceEngines.sp(c).getInt("edge_rate", 0)
    fun pitch(c: Context) = VoiceEngines.sp(c).getInt("edge_pitch", 0)

    /** v3.69: Edge का अपना "भावना / स्टाइल" (शब्दों से सिर्फ़ गति-पिच, EdgeStyle) */
    fun style(c: Context): String = VoiceEngines.sp(c).getString("edge_style", "") ?: ""

    override fun settings(c: Context, box: LinearLayout, ui: VoiceUi) {
        ui.hint(box, "ध्यान दें: Edge TTS भावना/स्टाइल के निर्देश नहीं मानता। सिर्फ़ गति और पिच बदल सकते हैं।")
        ui.field(box, "भावना / स्टाइल (Edge के लिए)", style(c), "जैसे: धीमी, कोमल आवाज़ में", multiLine = true) { v ->
            VoiceEngines.sp(c).edit().putString("edge_style", v.trim()).apply()
        }
        ui.hint(box, "Edge में असर सिर्फ़ गति और पिच तक है: \"धीमी\", \"कोमल\" = गति कम, पिच नीचा; \"तेज़\", \"जोश\" = गति ज़्यादा। यह नीचे के स्लाइडर में जुड़ता है।")
        ui.slider(box, "गति", -50, 50, rate(c), { v -> (if (v > 0) "+" else "") + "$v%" }) { v -> VoiceEngines.sp(c).edit().putInt("edge_rate", v).apply() }
        ui.slider(box, "पिच (आवाज़ का ऊँचा-नीचापन)", -30, 30, pitch(c), { v -> (if (v > 0) "+" else "") + "$v Hz" }) { v -> VoiceEngines.sp(c).edit().putInt("edge_pitch", v).apply() }
    }

    // ------------------------------------------------------------------ WebSocket (गर्म)
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM = "143.0.3650.75"
    private const val GEC_VERSION = "2-$CHROMIUM"
    private const val WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TOKEN"
    private const val FORMAT = "audio-24khz-48kbitrate-mono-mp3"   // Edge PCM नहीं देता (जाँचा: raw-* = 1007)

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val lock = ReentrantLock()
    @Volatile private var conn: Conn? = null
    @Volatile private var skewSec = 0.0

    private class Req(val out: PcmOut) {
        val done = CountDownLatch(1)
        @Volatile var err: String? = null
        @Volatile var audioAt = 0L
        @Volatile var cancelled = false
        var dec: Mp3Decoder? = null
    }

    private class Conn {
        lateinit var ws: WebSocket
        val opened = CountDownLatch(1)
        @Volatile var alive = false
        @Volatile var fail: String? = null
        @Volatile var failCode = 0
        @Volatile var failDate: String? = null
        @Volatile var cur: Req? = null
        @Volatile var lastUse = System.currentTimeMillis()
    }

    override fun warm(c: Context) { try { lock.withLock { ensure() } } catch (_: Exception) {} }

    private fun ensure(): Conn {
        conn?.let { if (it.alive && System.currentTimeMillis() - it.lastUse < 120_000) return it; close(it) }
        var last: String? = null
        for (attempt in 0..1) {
            val k = open()
            k.opened.await(6, TimeUnit.SECONDS)
            if (k.alive) { conn = k; return k }
            last = k.fail ?: "Edge से जुड़ नहीं पाया"
            close(k)
            // 403 = घड़ी का फ़र्क (टोकन 5 मिनट का) - server की Date से ठीक करो, एक बार फिर
            if (k.failCode == 403 && attempt == 0) { adjustSkew(k.failDate); continue }
            break
        }
        throw IOException(last)
    }

    private fun open(): Conn {
        val k = Conn()
        val url = "$WSS&ConnectionId=${uuid()}&Sec-MS-GEC=${EdgeDrm.token(System.currentTimeMillis() / 1000.0 + skewSec)}&Sec-MS-GEC-Version=$GEC_VERSION"
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROMIUM.substringBefore('.')}.0.0.0 Safari/537.36 Edg/${CHROMIUM.substringBefore('.')}.0.0.0")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${uuid().uppercase(Locale.US)};")
            .build()
        k.ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { k.alive = true; k.opened.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) k.cur?.done?.countDown()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val r = k.cur ?: return
                if (r.cancelled) return
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val hl = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                if (2 + hl > b.size) return
                val head = String(b, 2, hl, Charsets.UTF_8)
                if (!head.contains("Path:audio")) return
                val data = b.copyOfRange(2 + hl, b.size)
                if (data.isEmpty()) return
                if (r.audioAt == 0L) r.audioAt = System.currentTimeMillis()
                try { r.dec?.feed(data) } catch (e: Exception) { r.err = "MP3 बदलने में गलती: ${e.message}"; r.done.countDown() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { gone("Edge ने बंद किया ($code ${reason.take(60)})") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { gone("Edge ने बंद किया ($code)") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                k.failCode = response?.code ?: 0; k.failDate = response?.header("Date")
                gone("Edge: " + (response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName))
            }
            private fun gone(why: String) {
                k.alive = false; if (k.fail == null) k.fail = why
                k.opened.countDown()
                k.cur?.let { if (it.err == null) it.err = why; it.done.countDown() }
            }
        })
        return k
    }

    private fun close(k: Conn) { k.alive = false; try { k.ws.close(1000, "bye") } catch (_: Exception) {}; if (conn === k) conn = null }

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        lock.withLock {
            val r = Req(out)
            current = r
            var k = ensure()
            r.dec = Mp3Decoder(out)
            try {
                k.cur = r
                if (!send(k, text, voice, c)) {
                    // पुराना (गर्म) socket चुपचाप मर चुका था - एक बार नया
                    close(k); k = ensure(); k.cur = r
                    if (!send(k, text, voice, c)) throw IOException("Edge को भेज नहीं पाया")
                }
                val t0 = System.currentTimeMillis()
                // पहली आवाज़ 5 s में नहीं = टूटा मानो (कतार Gemini TTS पर लौटेगी)
                while (!r.done.await(50, TimeUnit.MILLISECONDS)) {
                    if (r.cancelled) return
                    val now = System.currentTimeMillis()
                    if (r.audioAt == 0L && now - t0 > 5_000) throw IOException("Edge ने 5 s में आवाज़ नहीं दी")
                    if (now - t0 > 20_000) throw IOException("Edge 20 s में पूरा नहीं हुआ")
                }
                if (r.cancelled) return
                r.err?.let { if (r.audioAt == 0L) throw IOException(it) }
                r.dec?.finish()
                k.lastUse = System.currentTimeMillis()
            } finally {
                if (k.cur === r) k.cur = null
                r.dec?.release()
                if (current === r) current = null
            }
        }
    }

    @Volatile private var current: Req? = null

    /** रोको: बचा हुआ MP3 न आए - socket बंद (अगली बार warm() फिर खोल देगा) */
    override fun stop() {
        val r = current ?: return
        r.cancelled = true; r.done.countDown()
        conn?.let { close(it) }
    }

    private fun send(k: Conn, text: String, voice: String, c: Context): Boolean {
        val ts = jsDate()
        val cfg = "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"$FORMAT\"}}}}\r\n"
        val (r, p) = EdgeStyle.apply(com.dk.assistant.CalmPersona.edgeRate(rate(c)), pitch(c), com.dk.assistant.avatar.MoodState.voiceStyle(style(c)))     // v3.69: बॉक्स के शब्द -> गति/पिच
        val ssml = EdgeDrm.ssml(text, voice, r, p)
        val msg = "X-RequestId:${uuid()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml"
        return k.ws.send(cfg) && k.ws.send(msg)
    }

    private fun adjustSkew(date: String?) {
        val d = date ?: return
        try {
            val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            val server = (f.parse(d)?.time ?: return) / 1000.0
            skewSec += server - (System.currentTimeMillis() / 1000.0 + skewSec)
        } catch (_: Exception) {}
    }

    private fun uuid() = UUID.randomUUID().toString().replace("-", "")
    private fun jsDate(): String {
        val f = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date())
    }

    // ------------------------------------------------------------------ MP3 -> PCM (फ़ोन का अपना decoder)
    private class Mp3Decoder(private val out: PcmOut) {
        private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
        private val frames = Mp3Frames()
        private val info = MediaCodec.BufferInfo()
        private var rate = VoiceEngine.RATE
        private var channels = 1
        private var pts = 0L
        private var released = false

        init {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, VoiceEngine.RATE, 1), null, null, 0)
            codec.start()
        }

        fun feed(b: ByteArray) { for (f in frames.push(b)) queue(f.data, f.samples, f.rate); drain(false) }

        fun finish() {
            if (released) return
            var idx = codec.dequeueInputBuffer(20_000)
            var tries = 0
            while (idx < 0 && tries++ < 50) { drain(false); idx = codec.dequeueInputBuffer(20_000) }
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        }

        fun release() { if (released) return; released = true; try { codec.stop() } catch (_: Exception) {}; try { codec.release() } catch (_: Exception) {} }

        private fun queue(f: ByteArray, samples: Int, r: Int) {
            var idx = codec.dequeueInputBuffer(10_000)
            var tries = 0
            while (idx < 0) { drain(false); if (tries++ > 100) return; idx = codec.dequeueInputBuffer(10_000) }
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear(); buf.put(f)
            codec.queueInputBuffer(idx, 0, f.size, pts, 0)
            pts += samples * 1_000_000L / r.coerceAtLeast(1)
        }

        private fun drain(eos: Boolean) {
            val until = System.currentTimeMillis() + 1500
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    idx >= 0 -> {
                        val ob = codec.getOutputBuffer(idx)
                        if (ob != null && info.size > 0) {
                            val pcm = ByteArray(info.size)
                            ob.position(info.offset); ob.get(pcm, 0, info.size)
                            val mono = if (channels == 2) Mp3Frames.downmix(pcm) else pcm
                            out.write(if (rate == VoiceEngine.RATE) mono else Resample.to24k(mono, rate))
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (!eos || System.currentTimeMillis() > until) return
                }
            }
        }
    }
}

/**
 * Edge का Sec-MS-GEC v2 टोकन (edge-tts 7.x / drm.py का सीधा अनुवाद, test: EdgeTtsTest).
 * "<CS64 16 hex>-<SHA256 64 hex>", हर 5 मिनट नया. Android के बिना चलता है.
 */
object EdgeDrm {
    private const val WIN_EPOCH = 11644473600L
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private val INITIAL = intArrayOf(0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(), 0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19)
    private val STATE = intArrayOf(0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(), 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2)
    private val SCHEDULE = longArrayOf(0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
        0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564).map { it.toInt() }.toIntArray()
    private val K = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    ).map { it.toInt() }.toIntArray()
    private const val ADJ = 0xC9DC06A7.toInt()
    private val KB = K.copyOf().also { it[14] = ADJ }
    private const val SIG0_ADJ = 0x059C0B80
    private const val SIG1_ADJ = 0x000B92C0
    private val CS64 = longArrayOf(
        0xE82E91B1, 0xB32700D0, 0x44DE24C6, 0x88DFF51D, 0x3BEB65BC, 0x29514038, 0xC89C5933, 0xB328CBDF,
        0x8D1EC211, 0xD405B4D5, 0x314EF5D5, 0x26E55F3C, 0xE92491B1, 0x607801C6, 0xF0A5B9D5, 0x82B2C1FB,
        0x06064CF3, 0xFA51A3DA, 0xDA79FA60, 0x207CC464, 0xBED20560, 0x3C3C74F3, 0x89F9C9F8, 0xD2D14B61,
        0x35FA38C6, 0xBB04A2C1, 0xCB49E3C6, 0xC5626167,
    ).map { it.toInt() }.toIntArray()
    private const val CS64_FACTOR = 0x82893933.toInt()
    private const val CS64_ADJ0 = 0xF2F90B1A.toInt()
    private const val CS64_ADJ1 = 0x0B1A0000

    /** unix समय (सेकंड) -> टोकन */
    fun token(unix: Double): String {
        var t = unix + WIN_EPOCH
        t -= t % 300
        val ticks = Math.round(t).toLong() * 10_000_000L
        val data = (ticks.toString() + TOKEN).toByteArray(Charsets.US_ASCII)
        val dig = sha(data)
        val (x, y) = cs64(data, dig)
        val cs = ByteArray(8).also { le(it, 0, x); le(it, 4, y) }
        return hex(cs) + "-" + hex(dig)
    }

    private fun s0(v: Int) = Integer.rotateRight(v, 7) xor Integer.rotateRight(v, 18) xor (v ushr 3)
    private fun s1(v: Int) = Integer.rotateRight(v, 17) xor Integer.rotateRight(v, 19) xor (v ushr 10)

    private fun expand(w: IntArray, from: Int) {
        for (i in from until 64) w[i] = s1(w[i - 2]) + w[i - 7] + s0(w[i - 15]) + w[i - 16]
    }

    private fun rounds(state: IntArray, w: IntArray, first: Int): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (first == 14) KB else K
        for (i in first until 64) {
            val t1 = h + (Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)) +
                (g xor (e and (f xor g))) + k[i] + w[i]
            val t2 = (Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)) +
                ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun init(d: ByteArray): IntArray {
        val w = IntArray(64)
        w[14] = ((d[0].toInt() and 0xff) shl 16) or ((d[1].toInt() and 0xff) shl 8) or (d[2].toInt() and 0xff)
        w[15] = be(d, 3)
        for (i in 16 until 30) {
            var v = SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) (s1(w[i - 2]) xor SIG1_ADJ) else s1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) (s0(w[i - 15]) xor SIG0_ADJ) else s0(w[i - 15])
            w[i] = v
        }
        w[30] = s1(w[28]) + w[23] + s0(w[15]) + w[14] + ADJ - K[14]
        expand(w, 31)
        val r = rounds(STATE, w, 14)
        return IntArray(8) { INITIAL[it] + r[it] }
    }

    fun sha(data: ByteArray): ByteArray {
        require(data.size >= 7)
        val length = 57 + data.size
        val padLen = Math.floorMod(55 - length, 64)
        val p = java.io.ByteArrayOutputStream()
        p.write(data, 7, data.size - 7); p.write(0x80); repeat(padLen) { p.write(0) }
        val bits = length.toLong() * 8
        for (i in 7 downTo 0) p.write(((bits ushr (i * 8)) and 0xff).toInt())
        val padded = p.toByteArray()
        var st = init(data.copyOf(7))
        var off = 0
        while (off < padded.size) {
            val w = IntArray(64); for (i in 0 until 16) w[i] = be(padded, off + i * 4)
            expand(w, 16)
            val r = rounds(st, w, 0)
            st = IntArray(8) { st[it] + r[it] }
            off += 64
        }
        val out = ByteArray(32); for (i in 0 until 8) { out[i * 4] = (st[i] ushr 24).toByte(); out[i * 4 + 1] = (st[i] ushr 16).toByte(); out[i * 4 + 2] = (st[i] ushr 8).toByte(); out[i * 4 + 3] = st[i].toByte() }
        return out
    }

    private fun rol16(v: Int) = Integer.rotateLeft(v, 16)

    private fun cs64(data: ByteArray, dig: ByteArray): Pair<Int, Int> {
        val keyA = leInt(dig, 0) or 1
        val keyB = leInt(dig, 4) or 1
        val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
        val terms = ArrayList<Int>(); val offsets = ArrayList<Int>()
        for (i in 0 until 14) { terms.add(CS64[i * 2] * factors[i % 2]); offsets.add(CS64[i * 2 + 1]) }
        // "\u0000" + data, little-endian 32-bit words, सम संख्या तक 0
        val src = ByteArray(1 + data.size).also { System.arraycopy(data, 0, it, 1, data.size) }
        val padded = src.copyOf(src.size + Math.floorMod(-src.size, 4))
        val words = ArrayList<Int>(); var o = 0
        while (o < padded.size) { words.add(leInt(padded, o)); o += 4 }
        if (words.size % 2 == 1) words.add(0)
        for ((i, word) in words.withIndex()) {
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var off = rol16(word) * (if (i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (i == 0) { term += CS64_ADJ0 * factors[0]; off += CS64_ADJ1 }
            terms.add(term); offsets.add(off)
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16(offsets[i] + x)
            var q = t * 0x76F50F34
            swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
            t = rol16(offsets[i + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rol16(q) * 0x35BD1EC9 + q
            swapAcc += swapLast
            state = state * keyA + x
            state = rol16(state) * 0xCF98B111.toInt()
            state = rol16(state) * 0x87085B9F.toInt()
            state = rol16(state) * 0x12CEB96D
            state = rol16(state) * 0x257E1D83
            acc += state
            t = state * keyB + y
            t = rol16(t) * 0xA27416F5.toInt()
            t = rol16(t) * 0xD38396FF.toInt()
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state
            i += 2
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    private fun be(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
    private fun leInt(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    private fun le(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { String.format(Locale.US, "%02X", it.toInt() and 0xff) }

    /** SSML (text का &, <, > सुरक्षित) */
    fun ssml(text: String, voice: String, ratePct: Int, pitchHz: Int): String {
        val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val r = (if (ratePct >= 0) "+" else "") + "$ratePct%"
        val p = (if (pitchHz >= 0) "+" else "") + "${pitchHz}Hz"
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='$voice'>" +
            "<prosody pitch='$p' rate='$r' volume='+0%'>$esc</prosody></voice></speak>"
    }
}

/**
 * बहते MP3 में से पूरे frame निकालो (MediaCodec को एक-एक frame). Android के बिना - test हो सकता है.
 */
class Mp3Frames {
    class Frame(val data: ByteArray, val samples: Int, val rate: Int)
    private var buf = ByteArray(0)

    fun push(b: ByteArray): List<Frame> {
        buf = if (buf.isEmpty()) b else buf + b
        val out = ArrayList<Frame>()
        var i = 0
        while (i + 4 <= buf.size) {
            val h = parse(buf, i)
            if (h == null) { i++; continue }
            if (i + h.first > buf.size) break                    // पूरा frame अभी नहीं आया
            out.add(Frame(buf.copyOfRange(i, i + h.first), h.second, h.third))
            i += h.first
        }
        buf = if (i >= buf.size) ByteArray(0) else buf.copyOfRange(i, buf.size)
        return out
    }

    companion object {
        private val BR1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BR2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val SR1 = intArrayOf(44100, 48000, 32000)
        private val SR2 = intArrayOf(22050, 24000, 16000)
        private val SR25 = intArrayOf(11025, 12000, 8000)

        /** (frame लंबाई, samples, sample-rate) या null (यहाँ frame नहीं) - सिर्फ़ Layer III */
        fun parse(b: ByteArray, i: Int): Triple<Int, Int, Int>? {
            val b0 = b[i].toInt() and 0xff; val b1 = b[i + 1].toInt() and 0xff; val b2 = b[i + 2].toInt() and 0xff
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
            val ver = (b1 shr 3) and 3; val layer = (b1 shr 1) and 3
            if (ver == 1 || layer != 1) return null
            val bi = (b2 shr 4) and 0xF; val si = (b2 shr 2) and 3; val pad = (b2 shr 1) and 1
            if (bi == 0 || bi == 15 || si == 3) return null
            val mpeg1 = ver == 3
            val br = (if (mpeg1) BR1 else BR2)[bi] * 1000
            val sr = when (ver) { 3 -> SR1; 2 -> SR2; else -> SR25 }[si]
            val len = (if (mpeg1) 144 else 72) * br / sr + pad
            if (len < 4) return null
            return Triple(len, if (mpeg1) 1152 else 576, sr)
        }

        /** stereo -> mono */
        fun downmix(p: ByteArray): ByteArray {
            val o = ByteArray(p.size / 2); var i = 0; var j = 0
            while (i + 3 < p.size) {
                val l = ((p[i].toInt() and 0xff) or (p[i + 1].toInt() shl 8)).toShort().toInt()
                val r = ((p[i + 2].toInt() and 0xff) or (p[i + 3].toInt() shl 8)).toShort().toInt()
                val m = (l + r) / 2
                o[j] = m.toByte(); o[j + 1] = (m shr 8).toByte(); i += 4; j += 2
            }
            return o
        }
    }
}
