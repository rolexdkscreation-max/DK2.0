package com.dk.assistant.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dk.assistant.DKLog
import com.dk.assistant.Glass
import com.dk.assistant.OrbView
import com.dk.assistant.R
import com.dk.assistant.SettingsActivity
import com.dk.assistant.UiLang
import com.dk.assistant.ui.CollapsibleSection
import com.google.android.gms.auth.api.identity.Identity
import java.security.MessageDigest

/**
 * v3.72: सेटिंग्स → कनेक्टर. हर कनेक्टर एक कार्ड: हाल (✓ जुड़ा / ✗), जोड़ो / हटाओ, जाँचो.
 * Google = एक बार Cloud Console की तैयारी (package + SHA-1 यहीं दिखता है), फिर हर कनेक्टर पर "जोड़ो" = Google की Allow स्क्रीन.
 * Facebook Page / Instagram Business = Page token चिपकाओ. MCP = कोई भी server URL (+ token).
 */
class ConnectorsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }
    private var openId: String? = null
    private var pendingGoogle: ConnectorDef? = null

    private val googleUi = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val d = pendingGoogle ?: return@registerForActivityResult
        pendingGoogle = null
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            if (!r.accessToken.isNullOrBlank()) done(d) else toast("Google ने इजाज़त नहीं दी")
        } catch (e: Exception) {
            toast(GoogleAuth.explain(e))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Glass.install(this)
        container = findViewById(R.id.container)
        findViewById<OrbView>(R.id.miniOrb).mini = true
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.saveBar).visibility = View.GONE
        findViewById<TextView>(R.id.txtTitle).text = "कनेक्टर"
        build()
    }

    private fun build() {
        container.removeAllViews()
        note("अपने खाते (Gmail, Drive, Calendar, GitHub, Facebook Page, Instagram…) जोड़िए - फिर DK लाइव से बोलकर काम: \"मेरे नए मेल पढ़ो\", \"कल 4 बजे मीटिंग डालो\", \"Drive में बिल ढूँढो\".\n" +
            "भेजना, मिटाना, शेयर या पोस्ट करने से पहले DK हमेशा पूछेगा. पढ़ना/खोजना सीधे.\n" +
            "Token सिर्फ़ इसी फ़ोन में रहते हैं (Android Keystore से बंद) - किसी server पर नहीं जाते. सब मुफ़्त (निजी इस्तेमाल).")

        section("g_setup", "Google: एक बार की तैयारी", "Drive, Gmail, Calendar वग़ैरह के लिए - 10 मिनट, सिर्फ़ एक बार") { box -> googleSetup(box) }
        Catalog.GOOGLE.forEach { d -> googleCard(d) }
        githubCard()
        metaCard()
        agentCard()
        mcpCard()
        section("later", "जल्द आने वाले", null) { box ->
            text(box, "Notion, Telegram bot, Spotify, Microsoft Outlook / OneDrive, Dropbox - अगले version में.\n" +
                "तब तक: इनमें से किसी का MCP server हो तो नीचे \"MCP server\" में URL डालकर अभी जोड़ सकते हैं.")
        }
        space()
    }

    // ------------------------------------------------------------ Google
    private fun googleSetup(box: LinearLayout) {
        val sha = sha1()
        text(box, "1. console.cloud.google.com खोलें → नया Project बनाएँ (जैसे \"DK\").\n" +
            "2. APIs & Services → Library: Google Drive, Gmail, Google Calendar, People, Tasks, Google Sheets, Google Docs, YouTube Data API v3 - जो चाहिए उन्हें Enable करें.\n" +
            "3. OAuth consent screen (Google Auth Platform): User type External, ऐप का नाम DK, अपना ईमेल. Audience में Publish app (In production) करें - Testing में login 7 दिन में ख़त्म हो जाता है.\n" +
            "4. Clients → Create client → Android: package नीचे वाला, SHA-1 नीचे वाला.\n" +
            "5. यहाँ वापस आकर जिस कनेक्टर को चाहें उस पर \"खाता जोड़ो\" दबाएँ.\n" +
            "Google \"यह ऐप जाँचा नहीं गया\" दिखाए तो Advanced → Go to DK दबाएँ - यह आपका अपना ऐप है.")
        label(box, "Package"); value(box, packageName)
        label(box, "SHA-1 (इस APK की signing key)"); value(box, sha ?: "नहीं मिला")
        row(box, button("SHA-1 कॉपी करें") { copy("SHA-1", sha.orEmpty()) }, button("Package कॉपी करें") { copy("package", packageName) })
        hint(box, "हर नया APK एक ही key से बनता है, इसलिए SHA-1 वही रहता है. Debug APK की key अलग हो तो उसका SHA-1 भी अलग Android client में जोड़ें.")
    }

    private fun googleCard(d: ConnectorDef) {
        val on = ConnectorStore.isOn(this, d.id)
        section(d.id, d.title + "  •  " + status(on), d.sub) { box ->
            actions(box, d)
            row(box,
                button(if (on) "हटाओ" else "खाता जोड़ो") { if (on) { ConnectorStore.setOn(this, d.id, false); openId = d.id; build(); toast("हटा दिया - Google की इजाज़त पूरी तरह हटाने: myaccount.google.com/permissions") } else googleConnect(d) },
                button("जाँचो") { probe(d) })
        }
    }

    private fun googleConnect(d: ConnectorDef) {
        openId = d.id
        try {
            Identity.getAuthorizationClient(this).authorize(GoogleAuth.request(d.scopes))
                .addOnSuccessListener { r ->
                    val pi = r.pendingIntent
                    if (r.hasResolution() && pi != null) {
                        pendingGoogle = d
                        try { googleUi.launch(IntentSenderRequest.Builder(pi.intentSender).build()) } catch (e: Exception) { pendingGoogle = null; toast(GoogleAuth.explain(e)) }
                    } else if (!r.accessToken.isNullOrBlank()) done(d)
                    else toast("Google ने token नहीं दिया")
                }
                .addOnFailureListener { e -> DKLog.d("कनेक्टर Google: ${e.message?.take(120)}"); toast(GoogleAuth.explain(e)) }
        } catch (e: Exception) { toast(GoogleAuth.explain(e)) }
    }

    private fun done(d: ConnectorDef) {
        ConnectorStore.setOn(this, d.id, true); openId = d.id; build()
        toast(d.title + ": " + UiLang.s("जुड़ गया - अगली बार DK लाइव शुरू होने पर काम करेगा"))
    }

    // ------------------------------------------------------------ GitHub
    private fun githubCard() {
        val d = Catalog.GITHUB
        val has = com.dk.assistant.Prefs.ghToken(this).isNotBlank()
        val on = d in ConnectorStore.connected(this)
        section(d.id, d.title + "  •  " + status(on), d.sub) { box ->
            actions(box, d)
            if (!has) hint(box, "GitHub token अभी नहीं है - सेटिंग्स → कोडिंग एजेंट में डालें (वही token यहाँ काम आता है).")
            row(box,
                button("token सेटिंग") { startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "coder")) },
                button(if (on) "DK लाइव से हटाओ" else "DK लाइव में जोड़ो") {
                    getSharedPreferences("dk_connect", MODE_PRIVATE).edit().putBoolean("on_github", !on).apply(); openId = d.id; build()
                },
                button("जाँचो") { probe(d) })
        }
    }

    // ------------------------------------------------------------ Facebook Page + Instagram Business
    private fun metaCard() {
        val pages = ConnectorStore.metaPages(this)
        val on = ConnectorStore.isOn(this, "fb_page") && pages.isNotEmpty()
        val ig = pages.firstOrNull { it.igId.isNotBlank() }
        section("meta", "Facebook Page / Instagram Business  •  " + status(on), "अपने पेज और Business/Creator Instagram के काम (Graph API)") { box ->
            if (on) {
                text(box, UiLang.s("जुड़े पेज:") + " " + pages.joinToString(", ") { it.name } + "\n" +
                    "Instagram: " + (ig?.let { "@" + it.igUser } ?: UiLang.s("पेज से कोई Business/Creator Instagram जुड़ा नहीं")))
            }
            label(box, "Facebook Page के काम"); actions(box, Catalog.FB_PAGE, false)
            label(box, "Instagram के काम"); actions(box, Catalog.INSTAGRAM, false)
            CollapsibleSection.add(this, box, "Token कैसे मिलेगा (एक बार)", null) { g ->
                text(g, "1. developers.facebook.com → My Apps → Create app (Business). Facebook Login for Business / Instagram जोड़ें.\n" +
                    "2. Tools → Graph API Explorer: अपना ऐप चुनें, Get User Access Token - इजाज़तें: pages_show_list, pages_read_engagement, pages_manage_posts, pages_manage_engagement, instagram_basic, instagram_content_publish, instagram_manage_comments.\n" +
                    "3. Access Token Debugger में Extend Access Token दबाएँ (लंबा token, ~60 दिन).\n" +
                    "4. वही token नीचे चिपकाएँ - DK खुद आपके पेजों के Page token ले लेता है (जो ख़त्म नहीं होते).\n" +
                    "Instagram: अकाउंट Business या Creator हो और Facebook Page से जुड़ा हो (Instagram ऐप → Settings → Account type). निजी अकाउंट API से नहीं जुड़ता - उसके लिए फ़ोन-एजेंट.\n" +
                    "अपने पेज/अकाउंट के लिए Meta App Review की ज़रूरत नहीं.")
            }
            val tok = input(box, "Token (User या Page access token)", true)
            row(box,
                button(if (on) "token बदलो" else "पेज जोड़ो") { metaConnect(tok.text.toString().trim()) },
                button("हटाओ") {
                    ConnectorStore.setSecret(this, "meta_token", null); ConnectorStore.setMetaPages(this, emptyList())
                    ConnectorStore.setOn(this, "fb_page", false); ConnectorStore.setOn(this, "instagram", false); openId = "meta"; build()
                },
                button("जाँचो") { probe(Catalog.FB_PAGE) })
        }
    }

    private fun metaConnect(token: String) {
        if (token.length < 20) { toast("पहले token चिपकाएँ"); return }
        toast("जाँच रहा हूँ…")
        Thread {
            val res = try {
                val p = ConnectorRunner.loadPages(token)
                if (p.isEmpty()) "इस token से कोई Facebook Page नहीं मिला"
                else {
                    ConnectorStore.setSecret(this, "meta_token", token); ConnectorStore.setMetaPages(this, p)
                    ConnectorStore.setOn(this, "fb_page", true); ConnectorStore.setOn(this, "instagram", p.any { it.igId.isNotBlank() })
                    null
                }
            } catch (e: Exception) { e.message ?: "जुड़ नहीं पाया" }
            runOnUiThread { openId = "meta"; build(); toast(res ?: UiLang.s("Facebook Page जुड़ गया")) }
        }.start()
    }

    // ------------------------------------------------------------ निजी FB / IG (फ़ोन-एजेंट)
    private fun agentCard() {
        section("agent", "Facebook / Instagram निजी अकाउंट", "फ़ोन-एजेंट से - कोई token नहीं") { box ->
            text(box, "निजी Facebook प्रोफ़ाइल और निजी Instagram पर Meta किसी ऐप को API से पोस्ट नहीं करने देता.\n" +
                "इसलिए DK का पहले से चल रहा फ़ोन-एजेंट (Accessibility) यह काम Facebook / Instagram ऐप खोलकर करता है - पोस्ट, स्टोरी, मैसेज, कमेंट.\n" +
                "बोलिए: \"Instagram पर यह फ़ोटो पोस्ट करो\" या \"Facebook पर राहुल को मैसेज भेजो\". पोस्ट/भेजने से पहले DK पूछेगा.")
            hint(box, if (com.dk.assistant.Setup.accessibilityOn(this)) "Accessibility: ✓ चालू" else "Accessibility: ✗ बंद - सेटिंग्स → Accessibility में चालू करें")
        }
    }

    // ------------------------------------------------------------ MCP
    private fun mcpCard() {
        val list = ConnectorStore.mcpServers(this)
        section("mcp", "MCP server (कोई भी ऐप)  •  " + list.size, "Notion, Slack, Jira… किसी का भी MCP server URL डालें - उसके सारे tools DK लाइव में") { box ->
            list.forEach { s ->
                val risky = s.tools.count { it.risky }
                text(box, "${s.name}  (${s.id})\n${s.url}\n" + UiLang.s("tools:") + " ${s.tools.size} • " + UiLang.s("पूछकर:") + " $risky\n" +
                    s.tools.take(12).joinToString(", ") { it.name + if (it.risky) "*" else "" })
                row(box,
                    button("tools फिर से लो") { mcpAdd(s.id, s.name, s.url, null) },
                    button("हटाओ") { ConnectorStore.removeMcp(this, s.id); openId = "mcp"; build() })
            }
            label(box, "नया server जोड़ें")
            val name = input(box, "नाम (जैसे Notion)", false)
            val url = input(box, "URL (https://…/mcp)", false)
            val tok = input(box, "Token (ज़रूरत हो तो)", true)
            row(box, button("server जोड़ो") {
                val u = url.text.toString().trim()
                if (!u.startsWith("https://") && !u.startsWith("http://")) toast("सही URL डालें (https://…)")
                else mcpAdd(ConnectorStore.newMcpId(this), name.text.toString().trim(), u, tok.text.toString().trim())
            })
            hint(box, "सिर्फ़ Streamable HTTP वाले server (URL). * = भेजने/बदलने वाला tool, DK पहले पूछेगा.")
        }
    }

    private fun mcpAdd(id: String, name: String, url: String, token: String?) {
        toast("जोड़ रहा हूँ…")
        Thread {
            val res = try {
                val t = token ?: ConnectorStore.secret(this, "mcp_tok_$id")
                val (srv, tools) = McpClient.discover(url, t)
                if (tools.isEmpty()) "server से कोई tool नहीं मिला"
                else { ConnectorStore.saveMcp(this, ConnectorStore.McpServer(id, name.ifBlank { srv.ifBlank { id } }, url, tools), token); null }
            } catch (e: Exception) { UiLang.s("जुड़ नहीं पाया:") + " " + (e.message ?: e.javaClass.simpleName).take(160) }
            runOnUiThread { openId = "mcp"; build(); toast(res ?: UiLang.s("MCP server जुड़ गया")) }
        }.start()
    }

    // ------------------------------------------------------------ जाँच
    private fun probe(d: ConnectorDef) {
        toast("जाँच रहा हूँ…")
        Thread {
            val r = ConnectorRunner.probe(this, d)
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(d.title)
                    .setMessage(UiLang.t(r.take(1500))).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    // ------------------------------------------------------------ छोटे UI helpers (SettingsActivity जैसे)
    private fun status(on: Boolean) = UiLang.s(if (on) "✓ जुड़ा" else "✗ नहीं जुड़ा")

    private fun section(id: String, title: String, sub: String?, body: (LinearLayout) -> Unit) {
        CollapsibleSection.add(this, container, title, sub, startOpen = id == openId, body = body)
    }

    private fun actions(box: LinearLayout, d: ConnectorDef, showLabel: Boolean = true) {
        if (showLabel) label(box, "DK लाइव से क्या-क्या")
        text(box, d.tools.joinToString("\n") { "• " + it.name + (if (it.risky) "  " + UiLang.s("(पहले पूछेगा)") else "") + " - " + it.desc })
    }

    private fun note(t: String) {
        container.addView(TextView(this).apply {
            text = t; textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setBackgroundResource(R.drawable.glass_card)
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
    }

    private fun text(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(0xE6FFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0); setLineSpacing(0f, 1.15f)
    })

    private fun label(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
        setPadding((4 * dp).toInt(), (12 * dp).toInt(), 0, (2 * dp).toInt())
    })

    private fun value(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); setTextIsSelectable(true); typeface = Typeface.MONOSPACE
        setPadding((4 * dp).toInt(), 0, 0, 0)
    })

    private fun hint(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 12f; setTextColor(0x80FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0)
    })

    private fun input(box: LinearLayout, hintT: String, secret: Boolean): EditText {
        val e = EditText(this).apply {
            hint = hintT; setBackgroundResource(R.drawable.glass_input)
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt()); textSize = 14f; isSingleLine = true
            val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
        return e
    }

    private fun button(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundResource(R.drawable.glass_pill)
        minHeight = 0; minimumHeight = 0
        val ph = (10 * dp).toInt(); setPadding(ph, (8 * dp).toInt(), ph, (8 * dp).toInt())
        setOnClickListener(onClick)
    }

    private fun row(box: LinearLayout, vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (10 * dp).toInt(), 0, 0) }
        views.forEach { v -> r.addView(v, LinearLayout.LayoutParams(0, (42 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() }) }
        box.addView(r)
    }

    private fun space() = container.addView(View(this), LinearLayout.LayoutParams(1, (24 * dp).toInt()))

    private fun copy(label: String, v: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, v))
        toast("कॉपी हो गया")
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_LONG).show()

    /** इस APK की signing key का SHA-1 (Google Android OAuth client के लिए) */
    @Suppress("DEPRECATION")
    private fun sha1(): String? = try {
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            val i = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            i.signingInfo?.apkContentsSigners?.firstOrNull()
        } else packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        sig?.let { Hex.colon(MessageDigest.getInstance("SHA-1").digest(it.toByteArray())) }
    } catch (_: Exception) { null }
}

object Hex {
    fun colon(b: ByteArray): String = b.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
}
