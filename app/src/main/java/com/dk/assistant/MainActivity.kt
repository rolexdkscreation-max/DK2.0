package com.dk.assistant

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Mukhya screen: beech me 3D orb, upar status, neeche baatcheet aur likhne ki bar. */
class MainActivity : AppCompatActivity() {

    private lateinit var txtHello: TextView
    private lateinit var txtName: TextView
    private lateinit var txtState: TextView
    private lateinit var txtDk: TextView
    private lateinit var chipAccess: TextView
    private lateinit var chipMic: TextView
    private lateinit var chipAi: TextView

    private val stateListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Glass.install(this)
        if (savedInstanceState == null) DKSplash.show(this)     // ऐप खुलते ही DK logo (overlay, ~1.2s)

        txtHello = findViewById(R.id.txtHello)
        txtName = findViewById(R.id.txtName)
        txtState = findViewById(R.id.txtState)
        txtDk = findViewById(R.id.txtDk)
        UiLang.keep(txtDk)    // DK का जवाब (content) - UI भाषा से नहीं बदलता
        chipAccess = findViewById(R.id.chipAccess)
        chipMic = findViewById(R.id.chipMic)
        chipAi = findViewById(R.id.chipAi)
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { openSettings() }
        // orb par tap = chehra dikh raha ho to hatao, warna live awaaz screen
        findViewById<OrbView>(R.id.orb).setOnClickListener {
            if (System.currentTimeMillis() < DKState.faceUntil) DKState.hideFace() else openVoice()
        }
        findViewById<ImageButton>(R.id.btnOrbMin).setOnClickListener {
            Prefs.edit(this) { putBoolean("orb_min", !Prefs.orbMin(this@MainActivity)) }
            applyOrbMin()
        }
        applyOrbMin()
        findViewById<android.view.View>(R.id.btnChat).setOnClickListener { openChat() }
        findViewById<android.view.View>(R.id.btnVoice).setOnClickListener { openVoice() }
        findViewById<android.view.View>(R.id.btnMemory).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }
        Setup.selfEnableAccessibility(this)

        chipMic.setOnClickListener { askPerms() }
        chipAccess.setOnClickListener {
            if (!Setup.accessibilityOn(this)) {
                UiLang.toast(this, "Installed/Downloaded apps में ${Prefs.assistantName(this)} ON करें", Toast.LENGTH_LONG).show()
                Setup.openAccessibility(this)
            }
        }
        chipAi.setOnClickListener { openSettings() }
        // DK कंप्यूटर: कोई भी काम कदम-दर-कदम (खोज, फ़ाइलें, फ़ोन, कोडिंग…)
        findViewById<TextView>(R.id.chipComputer).setOnClickListener { startActivity(Intent(this, ComputerActivity::class.java)) }
        findViewById<TextView>(R.id.chipForge).setOnClickListener { startActivity(Intent(this, com.dk.assistant.forge.ForgeActivity::class.java)) }
        findViewById<TextView>(R.id.chipUpdate).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "update"))
        }

        val callPermsMissing = CallManager.PERMS.any {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val asked = getSharedPreferences("dk_prefs", MODE_PRIVATE).getBoolean("call_perm_asked", false)
        if (!Setup.hasMic(this) || (callPermsMissing && !asked)) {
            getSharedPreferences("dk_prefs", MODE_PRIVATE).edit().putBoolean("call_perm_asked", true).apply()
            askPerms()
        }
    }

    /** Orb chhota = upar chhoti jagah, DK ka aakhri jawab zyada dikhe. Bada = poori jagah. */
    private fun applyOrbMin() {
        val min = Prefs.orbMin(this)
        val dp = resources.displayMetrics.density
        val box = findViewById<android.view.View>(R.id.orbBox)
        box.layoutParams = (box.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            if (min) { height = (140 * dp).toInt(); weight = 0f } else { height = 0; weight = 1f }
        }
        txtDk.layoutParams = (txtDk.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            if (min) { height = 0; weight = 1f } else { height = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT; weight = 0f }
        }
        txtDk.maxLines = if (min) 200 else 2
        txtDk.ellipsize = if (min) null else android.text.TextUtils.TruncateAt.END
        txtDk.gravity = if (min) android.view.Gravity.TOP or android.view.Gravity.START else android.view.Gravity.CENTER
        txtDk.movementMethod = if (min) android.text.method.ScrollingMovementMethod.getInstance() else null
        findViewById<ImageButton>(R.id.btnOrbMin).apply {
            setImageResource(if (min) R.drawable.ic_maximize else R.drawable.ic_minimize)
            contentDescription = if (min) "ओर्ब बड़ा करें" else "ओर्ब छोटा करें"
        }
        box.requestLayout()
    }

    private fun openChat() = startActivity(Intent(this, ChatActivity::class.java))

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun askPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        perms.addAll(CallManager.PERMS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 10)
    }

    private var micAsked = false

    /** Har baar app khulne par mic permission jaancho - nahi hai to maango (mana kiya ho to App info kholo) */
    private fun checkMicPerm(force: Boolean = false) {
        if (Setup.hasMic(this)) { micAsked = false; return }
        if (micAsked && !force) return
        micAsked = true
        val canAsk = !Prefs.micDeniedOnce(this) || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (canAsk) askPerms()
        else {
            UiLang.toast(this, "माइक की परमिशन बंद है — App info → Permissions → Microphone → Allow करें", Toast.LENGTH_LONG).show()
            Setup.openAppInfo(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("ask_mic", false)) checkMicPerm(force = true)
    }

    /**
     * v3.45: सच्चा दोस्त - ऐप खुलते ही DK खुद बोले. पहले ताज़ा लोकेशन की इजाज़त (हर बार खुलने पर, न हो तो),
     * फिर DK (service) को बोलो कि बात शुरू करे. एक बार खुलने में एक ही बार पूछो (dialog बंद होने पर onResume दोबारा आता है).
     */
    private var friendAskedThisOpen = false
    private fun friendOnOpen() {
        if (!Prefs.friendMode(this) || !Prefs.friendOpener(this) || !Setup.hasMic(this)) return
        if (!LocalInfo.hasPermission(this) && !friendAskedThisOpen) {
            friendAskedThisOpen = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 11)
            return   // जवाब आने पर (onRequestPermissionsResult) बात शुरू
        }
        DKAccessibilityService.instance?.friendOpen()
    }

    override fun onStop() {
        super.onStop()
        friendAskedThisOpen = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 11) { DKAccessibilityService.instance?.friendOpen(); return }
        val i = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (i >= 0) {
            val ok = grantResults.getOrNull(i) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!ok) Prefs.edit(this) { putBoolean("mic_denied", true) }
            UiLang.toast(this, if (ok) "✓ माइक की परमिशन मिल गई — अब DK सुन सकता है" else "माइक की परमिशन नहीं मिली — इसके बिना DK सुन नहीं सकता", Toast.LENGTH_LONG).show()
        }
        Setup.startKeepAlive(this)
        DKAccessibilityService.instance?.calls?.start()
        render()
    }

    /** Sab taiyaar hai? Nahi to sahi jagah bhejo. */
    private fun ready(): Boolean {
        when {
            !Setup.hasMic(this) -> { askPerms(); return false }
            !Prefs.hasAnyKey(this) -> {
                UiLang.toast(this, "पहले कोई भी फ्री API key डालें — DK खुद सबसे तेज़ फ्री मॉडल चुन लेगा", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "brain")); return false
            }
            !Setup.accessibilityOn() && !Setup.selfEnableAccessibility(this) && !Setup.enabledInSettings(this) -> {
                UiLang.toast(this, "Accessibility में ${Prefs.assistantName(this)} ON करें", Toast.LENGTH_LONG).show()
                Setup.openAccessibility(this); return false
            }
        }
        return true
    }

    private fun openVoice() {
        if (ready()) startActivity(Intent(this, VoiceActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        DKState.listen(stateListener)
        Setup.startKeepAlive(this)
        checkMicPerm(force = intent?.getBooleanExtra("ask_mic", false) == true)
        intent?.removeExtra("ask_mic")
        friendOnOpen()
        render()
        checkUpdate()
    }

    /** App khulte hi (har 3 ghante me max ek baar) GitHub par naya version dekho; mila to background me download. */
    private fun checkUpdate() {
        showWhatsNew()
        if (!Prefs.autoUpdate(this)) return
        val chip = findViewById<TextView>(R.id.chipUpdate)
        val sp = getSharedPreferences("dk_prefs", MODE_PRIVATE)
        fun chipFor(rel: Updater.Release?) {
            if (rel == null) { chip.visibility = android.view.View.GONE; return }
            chip.text = when {
                Updater.downloaded(this, rel) != null -> "⬆ ${rel.typeLabel} तैयार — इंस्टॉल करें"
                Updater.isDownloading(rel) -> "⬆ ${rel.typeLabel} डाउनलोड हो रहा है"
                else -> "⬆ ${rel.typeLabel} • DK ${rel.version}"
            }
            chip.visibility = android.view.View.VISIBLE
        }
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(this) < 3 * 60 * 60 * 1000L) {
            // 3 ghante ke andar dobara GitHub nahi - yaad rakha hua update; adhoora download ho to aage badhao
            val rel = Updater.cached(this)
            if (rel != null && Updater.downloaded(this, rel) == null) Updater.startDownload(this, rel)
            chipFor(rel)
            return
        }
        lifecycleScope.launch {
            val rel = try { Updater.check(this@MainActivity) } catch (e: Exception) { null }
            if (rel != null) Updater.startDownload(this@MainActivity, rel)     // chupchap background me
            chipFor(rel)
            if (rel != null) UiLang.toast(this@MainActivity,
                "DK ${rel.version} का ${rel.typeLabel} आया है (${rel.sizeMb}) — बैकग्राउंड में डाउनलोड हो रहा है", Toast.LENGTH_LONG).show()
        }
    }

    /** Update hone ke baad pehli baar: kya-kya naya aaya */
    private fun showWhatsNew() {
        val sp = getSharedPreferences("dk_prefs", MODE_PRIVATE)
        val cur = Updater.currentCode(this)
        val seen = sp.getInt("upd_seen_code", 0)
        if (seen >= cur) return
        sp.edit().putInt("upd_seen_code", cur).apply()
        Updater.cleanup(this); Updater.clearNotification(this)
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (pi.firstInstallTime == pi.lastUpdateTime) return               // pehli baar install - update nahi
        lifecycleScope.launch {
            val rel = Updater.byCode(cur)
            val notes = rel?.notes?.takeIf { it.isNotBlank() } ?: sp.getString("upd_notes_$cur", "").orEmpty()
            val label = rel?.typeLabel ?: "अपडेट"
            if (isFinishing || isDestroyed) return@launch
            UiDialog(this@MainActivity, Glass.DIALOG)
                .setTitle("${Updater.displayVersion(this@MainActivity)} • $label इंस्टॉल हो गया")
                .setMessage("क्या नया है:\n\n" + notes.take(2500).ifBlank { "सुधार और नए फ़ीचर" })
                .setPositiveButton("ठीक है", null)
                .show()
        }
    }

    override fun onPause() {
        DKState.unlisten(stateListener)
        super.onPause()
    }

    private fun render() {
        val name = Prefs.assistantName(this)
        val user = Prefs.userName(this)
        UiLang.setText(txtName, name)
        UiLang.setText(txtHello, if (user.isBlank()) "नमस्ते" else "नमस्ते, $user")

        UiLang.setText(chipAccess, (if (Setup.accessibilityOn(this)) "✓ " else "✗ ") + "फ़ोन कंट्रोल")
        UiLang.setText(chipMic, (if (Setup.hasMic(this)) "✓ " else "✗ ") + "माइक")
        val p = Providers.byId(Prefs.activeProvider(this))
        UiLang.setText(chipAi, (if (Prefs.hasAnyKey(this)) "✓ " else "✗ ") + (p?.name ?: "AI"))

        val live = DKState.live
        UiLang.setText(txtState, when (DKState.mode) {
            Mode.IDLE -> if (live) "माइक चालू है • आवाज़ स्क्रीन खोलें" else "ओर्ब या \"आवाज़\" दबाकर बात करें"
            Mode.LISTENING -> if (live) "Listening • सुन रहा हूँ… (बंद: टैप या \"माइक बंद\")" else "Listening • सुन रहा हूँ…"
            Mode.THINKING -> "जवाब आ रहा है…"
            Mode.WORKING -> "काम कर रहा हूँ…"
            Mode.SPEAKING -> "Speaking • बोल रहा हूँ…"
        })
        UiLang.setText(txtDk, if (DKState.lastReply.isBlank()) "" else "$name: " + DKState.lastReply)
    }
}
