package com.dk.assistant.dkai

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import com.dk.assistant.*
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 13 नवंबर 2026 (मालिक - M7 Dev Tools, 🔧 बटन): Local Sandbox / Server 8080, GitHub Project Studio,
 * Package Manager Store (NPM • PIP - असली registry खोज), Server & Process Monitor, Dual Split Screen, Creator & Community.
 * सब user-space में (कोई root नहीं).
 */
class DkaiDevTools(private val s: DkaiScreen) {
    private val c = s.activity
    private fun d(v: Float) = DkaiUi.d(c, v)
    private lateinit var layer: DkaiUi.Layer
    private lateinit var sandbox: TextView
    private lateinit var server: TextView
    private lateinit var monitor: LinearLayout
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { paintMonitor(); ticker.postDelayed(this, 2000) } }
    private val onServer: () -> Unit = { ticker.post { paintMonitor() } }

    companion object {
        const val PREF_SPLIT = "dkai_dual_split"
        const val COMMUNITY = "https://github.com/rolexdkscreation-max/DK2.0"
        private val http by lazy { NetFix.builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }
    }

    fun open() {
        val (page, body, _) = DkaiUi.page(c, "Dev Tools") { DkaiUi.close(s.root, layer) }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16f), d(12f), d(16f), d(24f)) }
        body.addView(ScrollView(c).apply { addView(col) }, LinearLayout.LayoutParams(-1, -1))

        val pills = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        sandbox = DkaiUi.status(c, "Local Sandbox: Active", DkaiKit.EMERALD)
        server = DkaiUi.status(c, "Server: ${DkaiKit.PORT}", DkaiTheme.GRAY)
        pills.addView(sandbox); pills.addView(server, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })
        col.addView(pills)

        // GitHub Project Studio
        col.addView(card(DkaiIcon.Kind.GITHUB, "GitHub Project Studio", "Clone, Root Project, ${DkaiKit.COPILOT} और PR - सब एक जगह", DkaiTheme.CYAN) {
            DkaiUi.close(s.root, layer); s.showTab(DkaiTab.GITHUB)
        })

        // Package Manager Store
        col.addView(DkaiUi.label(c, "Package Manager Store (NPM • PIP)"))
        col.addView(packages())

        // Server & Process Monitor
        col.addView(DkaiUi.label(c, "Server & Process Monitor"))
        monitor = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(monitor)

        // Dual Split Screen
        col.addView(DkaiUi.gap(c, 12f))
        col.addView(DkaiUi.switch(c, "Dual Split Screen", "Android की split-screen में Preview + Editor साथ (ऐप दो हिस्सों में)", Prefs.bool(c, PREF_SPLIT, false)) { on ->
            Prefs.edit(c) { putBoolean(PREF_SPLIT, on) }
            if (on) split()
        })

        // Creator & Community
        col.addView(DkaiUi.label(c, "Creator & Community"))
        col.addView(card(DkaiIcon.Kind.USERS, "DK • ${DkaiKit.BRAND}", "OFFICIAL • GitHub पर code, issues और updates", DkaiTheme.VIOLET, "OFFICIAL") {
            try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COMMUNITY))) } catch (_: Exception) { s.toast("ब्राउज़र नहीं खुला") }
        })

        layer = DkaiUi.open(s.root, page, DkaiUi.Place.FULL, onClose = {
            ticker.removeCallbacks(tick); DkaiServer.listeners.remove(onServer)
        })
        DkaiServer.listeners.add(onServer)
        ticker.post(tick)
    }

    private fun card(k: DkaiIcon.Kind, title: String, sub: String, tint: Int, tag: String? = null, go: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(64f)
        setPadding(d(14f), d(12f), d(10f), d(12f))
        background = DkaiTheme.press(DkaiTheme.round(c, DkaiTheme.CARD, 16f, DkaiTheme.BORDER)); isClickable = true; isFocusable = true
        contentDescription = "$title, $sub"
        addView(ImageView(c).apply { setImageDrawable(DkaiIcon(k, tint)) }, LinearLayout.LayoutParams(d(26f), d(26f)))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(8f), 0) }
        val t = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        t.addView(DkaiTheme.text(c, title, 15f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD))
        if (tag != null) t.addView(DkaiUi.tag(c, tag, DkaiKit.EMERALD), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })
        col.addView(t)
        col.addView(DkaiTheme.text(c, sub, 12f, DkaiTheme.GRAY).apply { setPadding(0, d(3f), 0, 0) })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.CHEVRON_RIGHT, DkaiTheme.GRAY)) }, LinearLayout.LayoutParams(d(18f), d(18f)))
        setOnClickListener { go() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(12f) }
    }

    // ================================================================== Package Manager
    private fun packages(): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), d(12f), d(12f), d(12f)); background = DkaiTheme.round(c, DkaiTheme.CARD, 16f, DkaiTheme.BORDER) }
        var npm = true
        val q = DkaiUi.field(c, "पैकेज खोजें (जैसे chart.js, axios, requests)")
        val results = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val status = DkaiTheme.text(c, "", 12f, DkaiTheme.GRAY).apply { setPadding(d(2f), d(8f), 0, d(4f)) }
        box.addView(DkaiUi.segmented(c, listOf("NPM", "PIP"), 0) { npm = it == 0; results.removeAllViews(); status.text = "" })
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(8f), 0, 0) }
        row.addView(q, LinearLayout.LayoutParams(0, -2, 1f))
        val go = {
            val t = q.text.toString().trim()
            if (t.isEmpty()) status.text = "नाम लिखें"
            else { s.hideKeyboard(); search(npm, t, results, status) }
        }
        row.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.SEARCH, "खोजें", DkaiTheme.ON, 44f, DkaiTheme.BLUE) { go() }, LinearLayout.LayoutParams(d(44f), d(44f)).apply { leftMargin = d(8f) })
        q.setOnEditorActionListener { _, _, _ -> go(); true }
        box.addView(row); box.addView(status); box.addView(results)
        return box.apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
    }

    private data class Pkg(val name: String, val version: String, val desc: String)

    private fun search(npm: Boolean, q: String, out: LinearLayout, status: TextView) {
        status.text = "खोज रहा हूँ…"; status.setTextColor(DkaiTheme.CYAN); out.removeAllViews()
        Thread {
            val r = try { Result.success(if (npm) npmSearch(q) else pipSearch(q)) } catch (e: Throwable) { Result.failure(e) }
            c.runOnUiThread {
                if (c.isDestroyed) return@runOnUiThread
                r.onSuccess { list ->
                    status.setTextColor(DkaiTheme.GRAY)
                    status.text = if (list.isEmpty()) "कुछ नहीं मिला" else "${list.size} पैकेज • Install = अभी के प्रोजेक्ट में जोड़ें"
                    for (p in list) out.addView(pkgRow(npm, p))
                }.onFailure { e -> status.setTextColor(DkaiTheme.RED); status.text = "खोज नहीं हुई: ${e.message?.take(100)}" }
            }
        }.start()
    }

    private fun get(url: String): String = http.newCall(Request.Builder().url(url).header("User-Agent", "DKAI").build()).execute().use { r ->
        if (r.code == 404) return ""
        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
        r.body?.string().orEmpty()
    }

    private fun npmSearch(q: String): List<Pkg> {
        val o = JSONObject(get("https://registry.npmjs.org/-/v1/search?size=8&text=" + Uri.encode(q)))
        val a = o.optJSONArray("objects") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optJSONObject("package")?.let { p -> Pkg(p.optString("name"), p.optString("version"), p.optString("description")) } }
            .filter { it.name.isNotBlank() }
    }

    private fun pipSearch(q: String): List<Pkg> {
        val name = q.trim().replace(' ', '-')
        if (!DkaiKit.validPkg(name)) return emptyList()
        val raw = get("https://pypi.org/pypi/" + Uri.encode(name) + "/json")
        if (raw.isBlank()) return emptyList()
        val info = JSONObject(raw).optJSONObject("info") ?: return emptyList()
        return listOf(Pkg(info.optString("name", name), info.optString("version"), info.optString("summary")))
    }

    private fun pkgRow(npm: Boolean, p: Pkg): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(10f), d(8f), d(6f), d(8f))
        background = DkaiTheme.round(c, DkaiTheme.FIELD, 12f, DkaiTheme.BORDER)
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(DkaiTheme.text(c, p.name + "  " + p.version, 13.5f, DkaiTheme.WHITE, DkaiTheme.MONO).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (p.desc.isNotBlank()) col.addView(DkaiTheme.text(c, p.desc, 11.5f, DkaiTheme.GRAY).apply { maxLines = 2; setPadding(0, d(2f), 0, 0) })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        addView(DkaiTheme.pill(c, "Install", DkaiTheme.BLUE, DkaiTheme.ON, 12f).apply { minHeight = d(36f); contentDescription = "Install ${p.name}"; setOnClickListener { install(npm, p) } })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(6f) }
    }

    /** npm = index.html में CDN script + package.json; pip = requirements.txt */
    private fun install(npm: Boolean, p: Pkg) {
        val proj = DkaiWorkspace.activeProject(c) ?: run { s.toast("पहले प्रोजेक्ट चुनें"); DkaiModals.projects(s) { install(npm, p) }; return }
        try {
            if (npm) {
                val pj = File(proj, "package.json")
                val o = if (pj.isFile) try { JSONObject(pj.readText()) } catch (_: Exception) { JSONObject() } else JSONObject().put("name", proj.name.lowercase()).put("version", "1.0.0").put("private", true)
                val deps = o.optJSONObject("dependencies") ?: JSONObject().also { o.put("dependencies", it) }
                deps.put(p.name, "^" + p.version.ifBlank { "latest" })
                pj.writeText(o.toString(2))
                val idx = DkaiWorkspace.entryOf(proj)
                if (idx != null) { val h = idx.readText(); val n = DkaiKit.addScript(h, p.name); if (n != h) idx.writeText(n) }
                s.toast("✓ ${p.name} जोड़ा" + if (idx != null) " (index.html + package.json)" else " (package.json)")
            } else {
                val rq = File(proj, "requirements.txt")
                rq.writeText(DkaiKit.addRequirement(if (rq.isFile) rq.readText() else "", p.name, p.version.ifBlank { null }))
                s.toast("✓ ${p.name} → requirements.txt")
            }
            DkaiCopilot.say("📦 ${if (npm) "npm" else "pip"}: ${p.name}@${p.version} -> ${proj.name}")
        } catch (e: Exception) { s.toast("नहीं जुड़ा: ${e.message?.take(100)}") }
    }

    // ================================================================== Server monitor
    private var pings = HashMap<Int, Long>()

    private fun paintMonitor() {
        if (!::monitor.isInitialized) return
        val list = DkaiServer.list()
        val on = list.any { it.port == DkaiKit.PORT }
        server.text = "● Server: ${DkaiKit.PORT} (" + (if (on) "Online" else "Offline") + ")"
        server.setTextColor(if (on) DkaiKit.EMERALD else DkaiTheme.GRAY)
        monitor.removeAllViews()
        if (list.isEmpty()) {
            monitor.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(14f), d(12f), d(10f), d(12f))
                background = DkaiTheme.round(c, DkaiTheme.CARD, 16f, DkaiTheme.BORDER)
                addView(DkaiTheme.text(c, "कोई server नहीं चल रहा", 13f, DkaiTheme.GRAY), LinearLayout.LayoutParams(0, -2, 1f))
                addView(DkaiTheme.pill(c, "▶ Start ${DkaiKit.PORT}", DkaiKit.EMERALD, DkaiTheme.ON, 12f).apply { minHeight = d(36f); setOnClickListener {
                    val p = DkaiWorkspace.activeProject(c) ?: run { DkaiModals.projects(s) { f -> DkaiServer.start(f)?.let { s.toast(it) } }; return@setOnClickListener }
                    DkaiServer.start(p)?.let { s.toast(it) }
                } })
            })
            return
        }
        for (r in list) {
            val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14f), d(12f), d(12f), d(12f)); background = DkaiTheme.round(c, DkaiTheme.CARD, 16f, DkaiTheme.BORDER) }
            val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.SERVER, DkaiKit.EMERALD)) }, LinearLayout.LayoutParams(d(20f), d(20f)))
            top.addView(DkaiTheme.text(c, "localhost:${r.port} • ${r.root.name}", 13.5f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD).apply { setPadding(d(8f), 0, 0, 0); maxLines = 1 },
                LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(DkaiTheme.pill(c, "Refresh", DkaiTheme.PILL, DkaiTheme.CYAN, 11.5f).apply { minHeight = d(34f); setOnClickListener { ping(r.port) } })
            top.addView(DkaiTheme.pill(c, "Stop", DkaiTheme.RED_BG, DkaiTheme.RED, 11.5f).apply { minHeight = d(34f); setOnClickListener { DkaiServer.stop(r.port) } },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(6f) })
            box.addView(top)
            val lat = pings[r.port]?.let { if (it < 0) "—" else "$it ms" } ?: "Refresh दबाएँ"
            box.addView(DkaiTheme.text(c, "uptime ${DkaiKit.uptime(System.currentTimeMillis() - r.started)} • latency $lat • requests ${r.hits}", 12f, DkaiTheme.GRAY, DkaiTheme.MONO)
                .apply { setPadding(0, d(6f), 0, 0) })
            box.addView(DkaiTheme.text(c, DkaiKit.shownPath(r.root), 11f, DkaiTheme.GRAY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START; setPadding(0, d(2f), 0, 0) })
            monitor.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(8f) })
        }
    }

    /** असली latency: localhost पर एक HEAD/GET */
    private fun ping(port: Int) {
        Thread {
            val t0 = System.nanoTime()
            val ms = try {
                http.newCall(Request.Builder().url(DkaiKit.localUrl(port)).build()).execute().use { (System.nanoTime() - t0) / 1_000_000 }
            } catch (_: Exception) { -1L }
            ticker.post { pings[port] = ms; paintMonitor() }
        }.start()
    }

    /** Dual Split: Android multi-window में खुलो (Preview एक तरफ़, दूसरा ऐप / DKAI दूसरी तरफ़) */
    private fun split() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24 && c.isInMultiWindowMode) { s.toast("पहले से split-screen में"); return }
            val i = Intent(c, c.javaClass).addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            c.startActivity(i)
            s.toast("Split-screen: दूसरी तरफ़ DKAI खुला - एक में Preview, दूसरे में Editor रखें")
        } catch (e: Exception) { s.toast("Split-screen नहीं खुला: ${e.message?.take(80)}") }
    }
}
