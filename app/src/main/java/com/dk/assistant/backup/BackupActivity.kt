package com.dk.assistant.backup

import android.accounts.Account
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dk.assistant.DKLog
import com.dk.assistant.Glass
import com.dk.assistant.OrbView
import com.dk.assistant.R
import com.dk.assistant.UiLang
import com.dk.assistant.connect.GoogleAuth
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * नवंबर 2026 (मालिक): सेटिंग्स → बैकअप. WhatsApp जैसा: अपना ईमेल (Google खाता) डालो →
 * "बैकअप करो" = DK की याददाश्त (सिर्फ़ बातचीत / याद, और कुछ नहीं) उसी खाते के Google Drive में;
 * ऐप हटाकर दोबारा डाला → वही ईमेल → "रिस्टोर करो" = सारी बातें वापस.
 * बैकअप के समय "बैकअप हो रहा है…", रिस्टोर के समय "रिस्टोर हो रहा है…".
 */
class BackupActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var edEmail: EditText
    private lateinit var btnBackup: Button
    private lateinit var btnRestore: Button
    private lateinit var busyRow: LinearLayout
    private lateinit var busyText: TextView
    private lateinit var lastText: TextView
    private var pending: Job? = null
    @Volatile private var working = false

    private enum class Job { BACKUP, BACKUP_FORCE, RESTORE }

    private val googleUi = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val job = pending ?: return@registerForActivityResult
        pending = null
        try {
            val token = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data).accessToken
            if (!token.isNullOrBlank()) work(job, token) else idle("Google ने Drive की इजाज़त नहीं दी")
        } catch (e: Exception) { idle(GoogleAuth.explain(e)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        com.dk.assistant.PlainLook.install(this)   // 14 नवंबर 2026 (मालिक): होम (DKAI) जैसा सपाट रूप, काँच/ग्लासी नहीं
        Glass.install(this)
        container = findViewById(R.id.container)
        findViewById<OrbView>(R.id.miniOrb).mini = true
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtTitle).text = UiLang.s("बैकअप")
        build()
    }

    private fun build() {
        container.removeAllViews()
        card { box ->
            title(box, "DK की याददाश्त का बैकअप")
            text(box, "अपना Google ईमेल डालें - DK की सारी बातचीत और याद रखी बातें उसी खाते के Google Drive में सुरक्षित रहेंगी (WhatsApp जैसा).\n" +
                "ऐप हटाकर दोबारा डालें तो यही ईमेल डालकर \"रिस्टोर करो\" दबाएँ - सारी बातें वापस आ जाएँगी.")
            hint(box, "सिर्फ़ याददाश्त जाती है - API keys, सेटिंग, फ़ोटो या कुछ और नहीं. Drive में यह छिपे फ़ोल्डर में रहता है; DK आपकी दूसरी Drive फ़ाइलें नहीं देखता.")
            edEmail = input(box, "आपका Google ईमेल (जैसे name@gmail.com)")
            edEmail.setText(BackupPhone.email(this))
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (12 * dp).toInt(), 0, 0) }
            btnBackup = button("बैकअप करो") { start(Job.BACKUP) }
            btnRestore = button("रिस्टोर करो") { start(Job.RESTORE) }
            r.addView(btnBackup, LinearLayout.LayoutParams(0, (46 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() })
            r.addView(btnRestore, LinearLayout.LayoutParams(0, (46 * dp).toInt(), 1f))
            box.addView(r)
            busyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(0, (14 * dp).toInt(), 0, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            busyRow.addView(ProgressBar(this), LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()))
            busyText = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((10 * dp).toInt(), 0, 0, 0) }
            busyRow.addView(busyText)
            box.addView(busyRow)
            lastText = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (14 * dp).toInt(), 0, 0) }
            box.addView(lastText)
            showLast()
        }
        card { box ->
            title(box, "ध्यान दें")
            text(box, "• ईमेल वही हो जो इस फ़ोन में Google खाते के रूप में जुड़ा है (फ़ोन Settings → Accounts).\n" +
                "• पहली बार Google पूछेगा \"DK को Drive में अपना डेटा रखने दें?\" - Allow दबाएँ.\n" +
                "• रिस्टोर = जोड़ना: फ़ोन में जो बातें हैं वे नहीं मिटतीं, बैकअप की बाकी बातें जुड़ जाती हैं.\n" +
                "• बैकअप हर बार नई फ़ाइल से बदल जाता है - Drive में जगह नहीं भरती.")
        }
    }

    private fun showLast() {
        val at = BackupPhone.lastBackup(this)
        lastText.text = if (at <= 0) UiLang.s("अभी तक इस फ़ोन से कोई बैकअप नहीं हुआ")
        else UiLang.s("आख़िरी बैकअप:") + " " + SimpleDateFormat("d MMM yyyy, h:mm a", Locale("hi", "IN")).format(Date(at)) +
            BackupPhone.lastInfo(this).let { if (it.isBlank()) "" else "\n$it" }
    }

    // ------------------------------------------------------------------ Google इजाज़त (उसी ईमेल वाले खाते की)
    private fun start(job: Job) {
        if (working) return
        val email = MemoryBackup.cleanEmail(edEmail.text?.toString())
        if (!MemoryBackup.validEmail(email)) { toast("सही ईमेल डालें (जैसे name@gmail.com)"); edEmail.requestFocus(); return }
        BackupPhone.setEmail(this, email)
        busy(job)
        try {
            val req = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(MemoryBackup.SCOPE)))
                .setAccount(Account(email, "com.google"))
                .build()
            Identity.getAuthorizationClient(this).authorize(req)
                .addOnSuccessListener { r ->
                    val pi = r.pendingIntent
                    if (r.hasResolution() && pi != null) {
                        pending = job
                        try { googleUi.launch(IntentSenderRequest.Builder(pi.intentSender).build()) } catch (e: Exception) { pending = null; idle(GoogleAuth.explain(e)) }
                    } else if (!r.accessToken.isNullOrBlank()) work(job, r.accessToken!!)
                    else idle("Google ने token नहीं दिया")
                }
                .addOnFailureListener { e -> DKLog.d("बैकअप Google: ${e.message?.take(120)}"); idle(accountHint(e)) }
        } catch (e: Exception) { idle(accountHint(e)) }
    }

    private fun accountHint(e: Throwable): String {
        val m = GoogleAuth.explain(e)
        return if (m.startsWith("Google login नहीं हुआ")) "$m\n(क्या यह ईमेल इस फ़ोन में Google खाते के रूप में जुड़ा है? फ़ोन Settings → Accounts)" else m
    }

    // ------------------------------------------------------------------ असली काम (पीछे वाले thread पर)
    private fun work(job: Job, token: String) {
        busy(job)
        val app = applicationContext
        Thread {
            val store = DriveAppData(token)
            try {
                if (job == Job.RESTORE) {
                    val (snap, err) = MemoryBackup.fetch(store)
                    if (snap == null) { ui { idle(err) }; return@Thread }
                    ui { busyText.text = UiLang.s("रिस्टोर हो रहा है… बातें जोड़ी जा रही हैं") }
                    val (talks, kept) = BackupPhone.apply(app, snap)
                    val whenT = SimpleDateFormat("d MMM yyyy", Locale("hi", "IN")).format(Date(snap.at))
                    ui { idle("रिस्टोर हो गया ($whenT वाला बैकअप): $talks नई बातें जुड़ीं, कुल $kept याद रखी बातें. DK को सब याद है.") }
                } else {
                    val snap = BackupPhone.snapshot(app)
                    val out = MemoryBackup.backup(store, snap, force = job == Job.BACKUP_FORCE)
                    if (out.needConfirm) { ui { idle(""); confirmOverwrite(out.message) }; return@Thread }
                    BackupPhone.markBackup(app, System.currentTimeMillis(), out.message)
                    ui { idle(out.message) }
                }
            } catch (e: Exception) {
                DKLog.d("बैकअप/रिस्टोर: ${e.message?.take(160)}")
                ui { idle(DriveAppData.explain(e)) }
            }
        }.start()
    }

    private fun confirmOverwrite(msg: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(UiLang.s("पहले रिस्टोर करें?"))
            .setMessage(UiLang.s(msg))
            .setPositiveButton(UiLang.s("रिस्टोर करो")) { _, _ -> start(Job.RESTORE) }
            .setNegativeButton(UiLang.s("फिर भी बैकअप")) { _, _ -> start(Job.BACKUP_FORCE) }
            .setNeutralButton(UiLang.s("रहने दो"), null)
            .show()
    }

    private fun busy(job: Job) {
        working = true
        btnBackup.isEnabled = false; btnRestore.isEnabled = false
        btnBackup.alpha = 0.5f; btnRestore.alpha = 0.5f
        busyText.text = UiLang.s(if (job == Job.RESTORE) "रिस्टोर हो रहा है…" else "बैकअप हो रहा है…")
        busyRow.visibility = View.VISIBLE
    }

    private fun idle(msg: String) {
        working = false
        if (isFinishing || isDestroyed) return
        btnBackup.isEnabled = true; btnRestore.isEnabled = true
        btnBackup.alpha = 1f; btnRestore.alpha = 1f
        busyRow.visibility = View.GONE
        showLast()
        if (msg.isNotBlank()) {
            lastText.text = UiLang.s(msg) + "\n\n" + lastText.text
            toast(msg)
        }
    }

    private fun ui(f: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) f() }

    // ------------------------------------------------------------------ छोटे हिस्से (कनेक्टर स्क्रीन जैसे)
    private fun card(body: (LinearLayout) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }
        body(box)
        container.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
    }

    private fun title(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
        text = UiLang.s(t); textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
    })

    private fun text(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
        text = UiLang.s(t); textSize = 13f; setTextColor(0xE6FFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0); setLineSpacing(0f, 1.15f)
    })

    private fun hint(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
        text = UiLang.s(t); textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0)
    })

    private fun input(box: LinearLayout, hintT: String): EditText {
        val e = EditText(this).apply {
            hint = UiLang.s(hintT); setBackgroundResource(R.drawable.glass_input)
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt()); textSize = 14f; isSingleLine = true
            val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
        return e
    }

    private fun button(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = UiLang.s(t); isAllCaps = false; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundResource(R.drawable.glass_pill)
        minHeight = 0; minimumHeight = 0
        setOnClickListener(onClick)
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_LONG).show()
}
