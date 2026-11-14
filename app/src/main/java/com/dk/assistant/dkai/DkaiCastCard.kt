package com.dk.assistant.dkai

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 14 नवंबर 2026: Broadcast की फ़ाइलें (Studio और होम कार्ड दोनों) - pure, JVM test से जाँचा */
object DkaiCast {
    fun wav(dir: File, n: Int) = File(dir, "broadcast_$n.wav")
    fun json(dir: File, n: Int) = File(dir, "broadcast_$n.json")

    /** एक Broadcast की सारी फ़ाइलें: आवाज़, स्क्रिप्ट, और शेयर के लिए बनी कॉपियाँ */
    fun files(dir: File, n: Int): List<File> {
        val share = File(dir, "share").listFiles()?.filter { it.name.startsWith("broadcast_${n}_") || it.name.startsWith("broadcast_$n.") }.orEmpty()
        return listOf(wav(dir, n), json(dir, n)) + share
    }

    /** मिटाओ - कितनी फ़ाइलें मिटीं */
    fun delete(dir: File, n: Int): Int = files(dir, n).count { it.isFile && it.delete() }

    /** सेव वाली स्क्रिप्ट: "Name (female, अंदाज़): बात" - [DkaiKit.parseScript] वापस पढ़ लेता है */
    fun scriptText(lines: List<DkaiKit.Line>): String = lines.joinToString("\n") { l ->
        val g = if (l.female) "female" else "male"
        "${l.speaker} (${if (l.tone.isBlank()) g else "$g, ${l.tone}"}): ${l.text}"
    }

    /** लिखी जा रही (अधूरी) स्क्रिप्ट: पूरी हो चुकी लाइनें + आख़िरी अधूरी लाइन अलग (स्क्रीन पर लिखती दिखे) */
    fun partial(raw: String): Pair<List<DkaiKit.Line>, String> {
        val cut = raw.lastIndexOf('\n')
        if (cut < 0) return emptyList<DkaiKit.Line>() to raw.trim()
        return DkaiKit.parseScript(raw.substring(0, cut)) to raw.substring(cut + 1).trim()
    }
}

/**
 * 14 नवंबर 2026 (मालिक: "ब्रॉडकास्ट सेलेक्ट करने के बाद जो स्टोरी बनेगी, वहीं पे वही स्क्रीन पे बनेगी… पूरा स्टोरी उसी टाइम
 * लाइव लिखेगा… लास्ट में ऑडियो जनरेट होगा, खुद ही"): होम स्क्रीन पर ही Broadcast का कार्ड.
 * रिसर्च (खबर / लेक्चर / पॉडकास्ट) -> स्क्रिप्ट लाइव लिखती दिखे -> हर किरदार की अलग आवाज़ + अंदाज़ -> अपने-आप आवाज़ -> अपने-आप बजे.
 * हर कार्ड पर: चलाओ / रोको, आगे-पीछे, शेयर, मिटाओ, Studio में खोलो.
 */
class DkaiCastCard(private val s: DkaiScreen, private val box: LinearLayout) {
    private val c = s.activity
    private val ui = Handler(Looper.getMainLooper())
    private fun d(v: Float) = DkaiUi.d(c, v)
    private val dir get() = DkaiBroadcast.dir(c)

    /** कोई Broadcast बन रहा है? (दूसरा शुरू करने से पहले) */
    var busy = false; private set
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var playingBtn: ImageView? = null
    private var playingTick: Runnable? = null

    /** नया Broadcast (लिखा या DK लाइव में बोला विषय). लौटाता है: शुरू हुआ (null) या कारण */
    fun start(topic: String): String? {
        val t = topic.trim()
        if (t.isEmpty()) return "विषय खाली है"
        if (busy) return "एक Broadcast अभी बन रहा है - पूरा होने दें"
        val keys = DkaiCastTts.keys(c)
        if (keys.isEmpty()) return "आवाज़ के लिए Gemini की key चाहिए (सेटिंग्स → DK लाइव)"
        // नया नंबर (पिछला बन चुका हो तो अगला)
        if (DkaiCast.wav(dir, DkaiBroadcast.no(c)).isFile) Prefs.edit(c) { putString(DkaiBroadcast.PREF_NO, (DkaiBroadcast.no(c) + 1).toString()) }
        val n = DkaiBroadcast.no(c)
        val format = DkaiKit.formatFor(t)
        val pick = Prefs.str(c, DkaiBroadcast.PREF_VOICE, DkaiKit.ALL_VOICES)
        val lang = DkaiKit.normLang(Prefs.str(c, DkaiBroadcast.PREF_LANG, DkaiKit.LANGS[0]))
        val multi = pick == DkaiKit.ALL_VOICES
        val v = Card(n, "$format · $t")
        box.addView(v.root, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(14f), d(8f), d(14f), d(8f)) })
        s.scrollHomeEnd()
        busy = true
        job = c.lifecycleScope.launch {
            try {
                var lines = DkaiKit.ownScript(t)
                var sources: List<Pair<String, String>> = emptyList()
                if (lines.isEmpty()) {
                    var facts = ""
                    if (format != "Story" && format != "Speech") {
                        v.state("RESEARCHING…", DkaiTheme.AMBER)
                        val found = withContext(Dispatchers.IO) { research(t, format) }
                        facts = found?.text.orEmpty(); sources = found?.sources.orEmpty()
                    }
                    v.state("WRITING LIVE…", DkaiTheme.VIOLET)
                    lines = DkaiKit.parseScript(write(DkaiKit.scriptSystem(format, lang, multi, facts), t, v, pick))
                    if (multi && lines.isNotEmpty() && !DkaiKit.isDialogue(lines)) {
                        v.state("REWRITING…", DkaiTheme.VIOLET)
                        val why = "Your previous script was a monologue. Write it again as a real back-and-forth conversation, every line with its speaker, gender and tone."
                        val again = try { DkaiKit.parseScript(write(DkaiKit.scriptSystem(format, lang, multi, facts, why), t, v, pick)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
                        if (DkaiKit.isDialogue(again)) lines = again
                    }
                }
                if (lines.isEmpty()) throw IllegalStateException("स्क्रिप्ट खाली आई")
                val cast = DkaiKit.studioCast(lines, pick)
                v.show(lines, cast, "")
                v.castInfo(lines, cast)
                // आवाज़ अपने-आप (मालिक: "ये नहीं कि हमें दबाना पड़े")
                val style = DkaiKit.studioStyle(format, lang)
                val (pcm, rate) = withContext(Dispatchers.IO) {
                    DkaiCastTts.render(DkaiCastTts.keys(c), lines, cast, style) { i, all -> ui.post { v.state("VOICE $i/$all…", DkaiTheme.CYAN) } }
                }
                val f = withContext(Dispatchers.IO) {
                    val w = DkaiCastTts.writeWav(DkaiCast.wav(dir, n), pcm, rate)
                    DkaiCast.json(dir, n).writeText(JSONObject().put("topic", t).put("format", format).put("lang", lang).put("voice", pick).put("speed", 100)
                        .put("sources", org.json.JSONArray().apply { sources.forEach { (a, u) -> put(JSONObject().put("t", a).put("u", u)) } })
                        .put("script", DkaiCast.scriptText(lines)).toString())
                    w
                }
                v.ready(f)
                v.state("READY", DkaiKit.EMERALD)
                DkaiLive.note("SYSTEM NOTE: The broadcast audio \"$t\" is ready and now playing on screen. Stay quiet while it plays unless the user speaks.")
                v.play()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                v.state("ERROR", DkaiTheme.RED)
                s.toast("Broadcast नहीं बना: ${e.message?.take(140)}")
            } finally { busy = false }
        }
        return null
    }

    private suspend fun research(topic: String, format: String): SearchResult? = try {
        val b = BrainManager(c)
        if (!b.anySearch()) null
        else b.search(DkaiKit.researchQuery(topic, format), deep = true).let { r -> r.copy(text = WebCite.strip(r.text)) }.takeIf { it.text.isNotBlank() }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { DKLog.d("Cast research: ${e.message?.take(80)}"); null }

    /** स्क्रिप्ट लिखते हुए स्क्रीन पर दिखाओ (हर ~250ms) */
    private suspend fun write(sys: String, topic: String, v: Card, pick: String): String {
        var last = 0L
        val r = BrainManager(c).quickStream(sys, topic) { full ->
            val now = System.currentTimeMillis()
            if (now - last < 250 && full.isNotEmpty()) return@quickStream
            last = now
            ui.post {
                val (done, tail) = DkaiCast.partial(full)
                v.show(done, DkaiKit.studioCast(done, pick), tail)
            }
        }
        return r?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("AI ने स्क्रिप्ट नहीं लिखी (AI की key / limit देखें)")
    }

    fun stopAll() {
        try { player?.release() } catch (_: Exception) {}; player = null
        playingTick?.let { ui.removeCallbacks(it) }
        playingBtn?.setImageDrawable(DkaiIcon(DkaiIcon.Kind.PLAY_FILL, DkaiTheme.ON)); playingBtn = null
    }

    /** एक Broadcast का कार्ड */
    private inner class Card(val n: Int, topic: String) {
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14f), d(12f), d(14f), d(12f)); background = DkaiTheme.round(c, DkaiTheme.FIELD, 18f, DkaiTheme.BORDER) }
        private val stateTv = DkaiTheme.text(c, "● STARTING", 11f, DkaiTheme.AMBER, DkaiTheme.MONO)
        private val castTv = DkaiTheme.text(c, "", 12f, DkaiTheme.GRAY).apply { visibility = android.view.View.GONE }
        private val scriptTv = DkaiTheme.text(c, "", 14f, DkaiTheme.WHITE).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }
        private val scroll = MaxScroll(c, d(360f)).apply { addView(scriptTv) }
        private val playerRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = android.view.View.GONE }
        private val playBtn = DkaiUi.iconBtn(c, DkaiIcon.Kind.PLAY_FILL, "चलाएँ", DkaiTheme.CYAN, 40f) { play() }
        private val seek = SeekBar(c).apply { max = 1000 }
        private val time = DkaiTheme.text(c, "00:00", 11f, DkaiTheme.GRAY, DkaiTheme.MONO)
        private var wav: File? = null
        private var mp: MediaPlayer? = null

        init {
            val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.BROADCAST, DkaiTheme.PINK)) }, LinearLayout.LayoutParams(d(18f), d(18f)))
            head.addView(DkaiTheme.text(c, "  Broadcast #$n", 14f, DkaiTheme.WHITE, DkaiTheme.SANS_MED), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(stateTv)
            head.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.TRASH, "मिटाएँ", DkaiTheme.RED, 36f) { askDelete() })
            root.addView(head)
            root.addView(DkaiTheme.text(c, topic, 12f, DkaiTheme.HINT).apply { maxLines = 2; setPadding(0, d(4f), 0, d(6f)) })
            root.addView(castTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6f) })
            root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, user: Boolean) { if (user) mp?.let { try { it.seekTo((it.duration * p / 1000f).toInt()); showTime() } catch (_: Exception) {} } }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
            playerRow.addView(playBtn)
            playerRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
            playerRow.addView(time)
            playerRow.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.SHARE, "शेयर", DkaiTheme.GREEN, 38f) { share() })
            playerRow.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.BROADCAST, "Studio में खोलें", DkaiTheme.VIOLET, 38f) { stopAll(); DkaiBroadcast(s).open() })
            root.addView(playerRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8f) })
        }

        fun state(label: String, col: Int) { stateTv.text = "● $label  "; stateTv.setTextColor(col) }

        /** [lines] पूरी लाइनें (किरदार · आवाज़ · अंदाज़) + [tail] अभी लिखी जा रही लाइन */
        fun show(lines: List<DkaiKit.Line>, cast: Map<String, DkaiKit.StudioVoice>, tail: String) {
            val sb = SpannableStringBuilder()
            for (l in lines) {
                val v = cast[l.speaker]
                val a = sb.length
                sb.append(l.speaker)
                sb.setSpan(ForegroundColorSpan(if (v?.female ?: l.female) DkaiTheme.PINK else DkaiTheme.CYAN), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (v != null) { val b = sb.length; sb.append(" · ${v.name}"); sb.setSpan(ForegroundColorSpan(DkaiTheme.HINT), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                if (l.tone.isNotBlank()) { val b = sb.length; sb.append(" · अंदाज़: ${l.tone}"); sb.setSpan(ForegroundColorSpan(DkaiTheme.AMBER), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                sb.append("\n").append(l.text).append("\n\n")
            }
            if (tail.isNotBlank()) { val a = sb.length; sb.append(tail).append(" ▍"); sb.setSpan(ForegroundColorSpan(DkaiTheme.GRAY), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            scriptTv.text = if (sb.isEmpty()) "लिख रहा हूँ…" else sb.trimEnd()
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }

        fun castInfo(lines: List<DkaiKit.Line>, cast: Map<String, DkaiKit.StudioVoice>) {
            val tones = lines.groupBy { it.speaker }.mapValues { (_, ls) -> ls.map { it.tone }.firstOrNull { it.isNotBlank() }.orEmpty() }
            castTv.text = "किरदार (${cast.size}):\n" + cast.entries.joinToString("\n") { (name, v) -> "• " + DkaiKit.castLine(name, v, tones[name].orEmpty()) }
            castTv.visibility = android.view.View.VISIBLE
        }

        fun ready(f: File) { wav = f; playerRow.visibility = android.view.View.VISIBLE; showTime() }

        private val tick = object : Runnable {
            override fun run() {
                showTime()
                if (try { mp?.isPlaying == true } catch (_: Exception) { false }) ui.postDelayed(this, 250)
            }
        }

        private fun showTime() {
            val p = mp
            val cur = try { p?.currentPosition ?: 0 } catch (_: Exception) { 0 }
            val dur = try { p?.duration ?: 0 } catch (_: Exception) { 0 }
            time.text = " " + DkaiKit.clock(cur.toLong()) + " / " + DkaiKit.clock(dur.toLong())
            if (!seek.isPressed) seek.progress = if (dur > 0) (cur * 1000L / dur).toInt() else 0
        }

        fun play() {
            val f = wav?.takeIf { it.isFile } ?: return
            val p = mp
            if (p != null && player === p) {
                if (p.isPlaying) { p.pause(); playBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.PLAY_FILL, DkaiTheme.ON)); return }
                p.start(); playBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.PAUSE, DkaiTheme.ON)); ui.post(tick); return
            }
            stopAll()     // दूसरा कार्ड बज रहा हो तो बंद
            try {
                val np = MediaPlayer().apply {
                    setDataSource(f.absolutePath); prepare()
                    setOnCompletionListener { playBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.PLAY_FILL, DkaiTheme.ON)); showTime() }
                }
                val sp = DkaiKit.clampSpeed(Prefs.str(c, DkaiBroadcast.PREF_SPEED, DkaiKit.DEFAULT_SPEED.toString()).toIntOrNull() ?: DkaiKit.DEFAULT_SPEED)
                try { np.playbackParams = np.playbackParams.setSpeed(sp / 100f) } catch (_: Exception) {}
                mp = np; player = np; playingBtn = playBtn; playingTick = tick
                np.start(); playBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.PAUSE, DkaiTheme.ON)); ui.post(tick)
            } catch (e: Exception) { s.toast("चल नहीं पाया: ${e.message?.take(80)}") }
        }

        private fun share() {
            val f = wav?.takeIf { it.isFile } ?: run { s.toast("आवाज़ अभी बन रही है"); return }
            s.toast("ऑडियो तैयार हो रहा है…")
            c.lifecycleScope.launch {
                val out = try { withContext(Dispatchers.IO) { DkaiCastTts.shareable(f, File(dir, "share")) } } catch (e: Exception) { s.toast("शेयर फ़ाइल नहीं बनी: ${e.message?.take(80)}"); return@launch }
                try { DkaiCastTts.share(c, out, "Broadcast #$n - DKAI") } catch (e: Exception) { s.toast("शेयर नहीं हुआ: ${e.message?.take(80)}") }
            }
        }

        private fun askDelete() {
            android.app.AlertDialog.Builder(c).setTitle("Broadcast #$n मिटाएँ?").setMessage("इसकी स्क्रिप्ट और आवाज़ हमेशा के लिए मिट जाएगी।")
                .setPositiveButton("मिटाएँ") { _, _ ->
                    if (busy && wav == null) job?.cancel()
                    if (player === mp) stopAll() else try { mp?.release() } catch (_: Exception) {}
                    mp = null
                    DkaiCast.delete(dir, n)
                    box.removeView(root)
                    s.toast("मिटा दिया")
                }.setNegativeButton("रद्द", null).show()
        }
    }
}
