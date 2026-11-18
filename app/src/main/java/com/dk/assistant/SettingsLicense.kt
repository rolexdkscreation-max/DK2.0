package com.dk.assistant

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.backend.BackendApi
import com.dk.assistant.backend.BackendConfig
import com.dk.assistant.backend.BackendSession
import com.dk.assistant.backend.BackendWire
import com.dk.assistant.connect.GoogleAuth

/**
 * 17 नवंबर 2026 (मालिक का "DK बैकएंड मास्टर प्रॉम्प्ट"): Settings → «लाइसेंस की».
 *  - की डालें + «चालू करें», अभी का स्तर, कब तक, आज कितना समय बचा
 *  - «लाइसेंस की पाएँ» = WhatsApp पर तय संदेश
 *  - Google से लॉगिन (मालिक) - मालिक हो तो «की बनाएँ / सूची / बंद करें»
 * यहाँ सिर्फ़ लाइसेंस की डलती है. सर्वर का पता, Cloudflare/Worker/D1/Webhook का कोई डिब्बा नहीं (मालिक का सख़्त नियम).
 */
internal const val LICENSE_CARD = "लाइसेंस की"
internal const val LICENSE_ADMIN_CARD = "लाइसेंस की बनाएँ (मालिक)"
internal const val LICENSE_LOGIN_CARD = "Google से लॉगिन"
internal const val RC_LICENSE_GOOGLE = 41
internal const val RC_LICENSE_PICK = 43
/** इस बार Google लॉगिन नहीं हो पाया = SHA-1 वाली मदद दिखाओ */
@Volatile internal var googleLoginFailed = false

private fun SettingsActivity.loginFail(msg: String) { toast(msg); if (!googleLoginFailed) { googleLoginFailed = true; recreate() } }

internal const val LOGIN_HINT = "टैप करने पर फ़ोन के सारे Google खाते दिखेंगे - जिससे चाहें लॉगिन करें। लॉगिन न करें तो भी ऐप चलता है।"
internal const val OWNER_LINE = "Welcome Boss • सब कुछ चालू, कोई सीमा नहीं"

internal fun SettingsActivity.secLicense() {
    if (!BackendConfig.ready()) {
        card(LICENSE_CARD, "DK का सर्वर इस ऐप में नहीं जुड़ा", startOpen = true) { box ->
            hint(box, "यह ऐप बिना सर्वर के बना है (GitHub Secrets में BACKEND_URL / Firebase नहीं थे)। " +
                "आपकी अपनी AI keys से सब पहले जैसा चलता है। सर्वर जुड़ा नया APK आने पर यहाँ लाइसेंस की डाल सकेंगे।")
            BackendConfig.whatsappLink()?.let { l -> row(box, button("लाइसेंस की पाएँ") { openWa(l) }) }
        }
        return
    }
    // 18 नवंबर 2026 (मालिक: "मालिक से लॉगिन किया, फिर भी 10 मिनट दिखा रहा है") - मालिक को सीमा वाली बात नहीं
    card(LICENSE_CARD, if (BackendSession.owner(this)) OWNER_LINE else "मुफ़्त: रोज़ 10 मिनट बातचीत • बेसिक: खोज और रिसर्च • प्रीमियम: सब कुछ", startOpen = true) { box -> licenseBody(box) }
    card(LICENSE_LOGIN_CARD, if (BackendSession.google(this)) "✓ " + BackendSession.email(this) else "चाहें तो • नाम अपने-आप • बिना लॉगिन भी सब शर्तों के साथ चलता है") { box -> loginBody(box) }
    if (BackendSession.owner(this)) card(LICENSE_ADMIN_CARD, "नई की • सूची • बंद करें") { box -> adminBody(box) }
}

private fun SettingsActivity.openWa(link: String) {
    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (e: Exception) { toast("WhatsApp नहीं खुला") }
}

/** पीछे से काम, फिर स्क्रीन पर (Activity बंद हो चुकी हो तो कुछ नहीं) */
private fun <T> SettingsActivity.bg(work: () -> T, done: (Result<T>) -> Unit) {
    Thread {
        val r = try { Result.success(work()) } catch (e: Exception) { Result.failure(e) }
        runOnUiThread { if (!isFinishing && !isDestroyed) done(r) }
    }.start()
}

internal fun licenseError(e: Throwable): String = AiRouter.say(e) ?: when (e) {
    is java.net.UnknownHostException -> "इंटरनेट नहीं मिला"
    is java.io.InterruptedIOException -> "सर्वर ने समय पर जवाब नहीं दिया"
    else -> "सर्वर से बात नहीं हो पाई"
}

internal fun statusText(s: BackendWire.Status, now: Long): String = buildString {
    append("स्तर: ").append(BackendWire.levelHi(s.level))
    if (s.expiresAt > 0) {
        val d = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("hi", "IN")).format(java.util.Date(s.expiresAt))
        append("\nकब तक: ").append(d).append(" (").append(((s.expiresAt - now) / 86_400_000L).coerceAtLeast(0)).append(" दिन बाकी)")
    }
    s.quota?.let { q ->
        if (q.voiceLeft >= 0) append("\nआज बातचीत का समय बचा: ").append(q.voiceLeft / 60).append(" मिनट ").append(q.voiceLeft % 60).append(" सेकंड")
        if (q.chatLeft >= 0) append("\nआज के संदेश बचे: ").append(q.chatLeft)
    }
}

private fun SettingsActivity.licenseBody(box: LinearLayout) {
    val st = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (4 * dp).toInt()); text = "जाँच रहा हूँ…" }
    box.addView(st)
    fun refresh() = bg({ BackendApi.status(this) }) { r -> st.text = r.fold({ statusText(it, System.currentTimeMillis()) }, { "अभी पता नहीं चला: " + licenseError(it) }) }
    refresh()
    label(box, "लाइसेंस की (जैसे DK-ABCD-EFGH-JKLM)")
    val ed = EditText(this).apply {
        hint = "DK-XXXX-XXXX-XXXX"; isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setBackgroundResource(R.drawable.glass_input); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt()); textSize = 15f
        val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
    }
    box.addView(ed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    row(box, button("चालू करें") {
        val k = ed.text?.toString().orEmpty().trim().uppercase()
        if (!BackendWire.keyShape(k)) { toast("की ऐसी दिखती है: DK-XXXX-XXXX-XXXX"); return@button }
        st.text = "की जाँच रहा हूँ…"
        bg({ BackendApi.activate(this, k) }) { r ->
            r.onSuccess { s -> ed.setText(""); st.text = statusText(s, System.currentTimeMillis()); toast("✓ लाइसेंस चालू: " + BackendWire.levelHi(s.level)) }
                .onFailure { e -> st.text = licenseError(e); toast(licenseError(e)) }
        }
    }, button("ताज़ा करें") { refresh() })
    BackendConfig.whatsappLink()?.let { l -> row(box, button("लाइसेंस की पाएँ") { openWa(l) }) }
    if (!BackendSession.owner(this)) hint(box, "बिना लाइसेंस: अपनी Gemini key (AI दिमाग में) से लिखकर चैट जितनी चाहें; बोलकर बात रोज़ 10 मिनट " +
        "(रात 12 बजे भारत के समय से फिर, इंटरनेट ज़रूरी)। बेसिक: बिना सीमा बात + डीप रिसर्च। प्रीमियम: सब कुछ + अलग-अलग keys।")
}

private fun SettingsActivity.loginBody(box: LinearLayout) {
    val st = TextView(this).apply {
        textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (4 * dp).toInt())
        // 18 नवंबर 2026: लॉगिन वाले हिस्से में किसी खास पहचान / जाँच की बात नहीं - सिर्फ़ ईमेल
        text = if (BackendSession.google(this@loginBody)) "Google से जुड़े: ${BackendSession.email(this@loginBody)}"
            else "अभी बिना लॉगिन"
    }
    box.addView(st)
    row(box, button(if (BackendSession.google(this)) "दूसरा Google खाता" else "Google से लॉगिन") { googleLogin() }, button("लॉग आउट") {
        BackendSession.signOut(this); toast("लॉग आउट - अब मेहमान"); recreate()
    })
    hint(box, LOGIN_HINT)
    // 18 नवंबर 2026: लॉगिन में "8: Unknown error" = Cloud Console में यही package + SHA-1 वाला Android client चाहिए - यहीं दिखे
    // (सिर्फ़ लॉगिन न हो पाने पर - आम यूज़र को सेटअप/जाँच वाली बात नहीं)
    if (!googleLoginFailed) return
    val sha = GoogleAuth.signingSha1(this)
    hint(box, "लॉगिन न हो (code 8 / 10) तो Firebase वाले Google Cloud project में Android client बनाइए:\nPackage: $packageName\nSHA-1: ${sha ?: "नहीं मिला"}")
    row(box, button("SHA-1 कॉपी करें") { copyText("SHA-1", sha.orEmpty()) }, button("Package कॉपी करें") { copyText("package", packageName) })
}

private fun SettingsActivity.copyText(label: String, v: String) {
    val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, v)); toast("कॉपी हुआ: $label")
}

/** 18 नवंबर 2026: पहले फ़ोन के सारे Google खाते (popup), चुने खाते से [googleLoginAs] - [licenseGoogleResult] */
private fun SettingsActivity.googleLogin() {
    @Suppress("DEPRECATION")
    try { startActivityForResult(GoogleAuth.chooseAccountIntent(BackendSession.email(this)), RC_LICENSE_PICK) }
    catch (e: Exception) { googleLoginAs(null) }     // popup न खुले (बहुत पुराना फ़ोन) = पहले जैसा
}

/** Play services से Google (openid/email) - UI चाहिए तो Google की स्क्रीन, फिर [licenseGoogleResult] */
private fun SettingsActivity.googleLoginAs(account: String?) {
    bg({ GoogleAuth.tokenBlocking(this, BackendSession.GOOGLE_SCOPES, account) }) { r ->
        when (val res = r.getOrElse { e -> loginFail(GoogleAuth.explain(e)); return@bg }) {
            is GoogleAuth.Res.Token -> finishGoogle(res.value)
            is GoogleAuth.Res.NeedsUi -> {
                val pi = res.intent ?: run { toast("Google की स्क्रीन नहीं खुली"); return@bg }
                @Suppress("DEPRECATION")
                try { startIntentSenderForResult(pi.intentSender, RC_LICENSE_GOOGLE, null, 0, 0, 0) } catch (e: Exception) { loginFail(GoogleAuth.explain(e)) }
            }
            is GoogleAuth.Res.Fail -> loginFail(res.message)
        }
    }
}

private fun SettingsActivity.finishGoogle(accessToken: String) {
    toast("Google से जुड़ रहा हूँ…")
    bg({ BackendSession.signInGoogle(this, accessToken); BackendApi.status(this) }) { r ->
        r.onSuccess { s ->
            // 18 नवंबर 2026 (मालिक): Google का नाम अपने-आप (नाम पहले से हो तो वही; Settings → प्रोफ़ाइल में बदल सकते हैं)
            BackendSession.displayName(this).takeIf { it.isNotBlank() && Prefs.userName(this).isBlank() }?.let { n -> Prefs.edit(this) { putString("user_name", n) } }
            if (s.owner) com.dk.assistant.backend.WelcomeBoss.show(this) { recreate() } else { toast("✓ लॉगिन हुआ"); recreate() }
        }
            .onFailure { e -> toast(licenseError(e)) }
    }
}

/** [SettingsActivity.onActivityResult] से */
internal fun SettingsActivity.licenseGoogleResult(requestCode: Int, data: Intent?) {
    if (requestCode == RC_LICENSE_PICK) {
        GoogleAuth.pickedAccount(data)?.let { googleLoginAs(it) } ?: toast("कोई खाता नहीं चुना")
        return
    }
    if (requestCode != RC_LICENSE_GOOGLE) return
    try {
        val t = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data).accessToken
        if (!t.isNullOrBlank()) finishGoogle(t) else toast("Google ने इजाज़त नहीं दी")
    } catch (e: Exception) { loginFail(GoogleAuth.explain(e)) }
}

private fun SettingsActivity.adminBody(box: LinearLayout) {
    label(box, "स्तर")
    val levels = arrayOf("basic", "premium")
    var level = "premium"
    val lvBtn = button("स्तर: प्रीमियम") { }
    lvBtn.setOnClickListener {
        UiDialog(this, Glass.DIALOG).setTitle("स्तर चुनें").setItems(levels.map { BackendWire.levelHi(it) }.toTypedArray()) { _, i ->
            level = levels[i]; lvBtn.text = "स्तर: " + if (level == "basic") "बेसिक" else "प्रीमियम"
        }.show()
    }
    row(box, lvBtn)
    fun num(t: String, def: String) = EditText(this).apply {
        hint = t; setText(def); inputType = InputType.TYPE_CLASS_NUMBER; isSingleLine = true
        setBackgroundResource(R.drawable.glass_input); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt())
        val pd = (12 * dp).toInt(); setPadding(pd, pd, pd, pd)
    }
    label(box, "कितने दिन"); val days = num("30", "30"); box.addView(days)
    label(box, "कितने फ़ोन पर"); val devs = num("1", "1"); box.addView(devs)
    label(box, "नोट (किसके लिए)")
    val note = EditText(this).apply {
        hint = "जैसे: रमेश"; isSingleLine = true; setBackgroundResource(R.drawable.glass_input); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt())
        val pd = (12 * dp).toInt(); setPadding(pd, pd, pd, pd)
    }
    box.addView(note)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun load() {
        list.removeAllViews()
        bg({ BackendApi.adminList(this) }) { r ->
            val l = r.getOrElse { e -> hint(list, "सूची नहीं आई: " + licenseError(e)); return@bg }
            if (l.isEmpty()) hint(list, "अभी कोई की नहीं बनी")
            val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("hi", "IN"))
            l.forEach { k ->
                val tv = TextView(this).apply {
                    text = (if (k.active && k.expiresAt > System.currentTimeMillis()) "✓ " else "✗ ") +
                        "${if (k.level == "basic") "बेसिक" else "प्रीमियम"} • ${fmt.format(java.util.Date(k.expiresAt))} तक • ${k.devices}/${k.maxDevices} फ़ोन" +
                        (if (k.note.isNotBlank()) " • ${k.note}" else "") + (if (k.active) "\n(बंद करने के लिए दबाएँ)" else "")
                    textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
                    if (k.active) setOnClickListener {
                        UiDialog(this@adminBody, Glass.DIALOG).setTitle("यह की बंद करें?").setMessage("इस की वाले फ़ोन मुफ़्त स्तर पर आ जाएँगे।")
                            .setPositiveButton("बंद करो") { _, _ -> bg({ BackendApi.adminRevoke(this@adminBody, k.id) }) { rr ->
                                rr.onSuccess { toast("की बंद"); load() }.onFailure { e -> toast(licenseError(e)) } } }
                            .setNegativeButton("रुको", null).show()
                    }
                }
                list.addView(tv)
            }
        }
    }
    row(box, button("की बनाएँ") {
        val d = days.text.toString().toIntOrNull() ?: 0; val m = devs.text.toString().toIntOrNull() ?: 0
        if (d !in 1..3650 || m !in 1..100) { toast("दिन 1–3650 और फ़ोन 1–100"); return@button }
        bg({ BackendApi.adminCreate(this, level, d, m, note.text.toString().trim()) }) { r ->
            r.onSuccess { (key, _) -> showNewKey(key); load() }.onFailure { e -> toast(licenseError(e)) }
        }
    }, button("सूची ताज़ा करें") { load() })
    hint(box, "नई की सिर्फ़ एक बार दिखती है - उसी समय कॉपी करके भेजें। सर्वर में सिर्फ़ उसकी छाप (SHA-256) रहती है।")
    box.addView(list)
    load()
}

private fun SettingsActivity.showNewKey(key: String) {
    val tv = TextView(this).apply {
        text = key; textSize = 20f; setTextIsSelectable(true); setTextColor(0xFFFFFFFF.toInt())
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
    }
    UiDialog(this, Glass.DIALOG).setTitle("नई लाइसेंस की (सिर्फ़ अभी दिखेगी)").setView(tv)
        .setPositiveButton("कॉपी करें") { _, _ ->
            (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("DK लाइसेंस की", key))
            toast("कॉपी हो गई")
        }.setNegativeButton("बंद करें", null).show()
}
