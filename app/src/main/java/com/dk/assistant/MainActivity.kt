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

        txtHello = findViewById(R.id.txtHello)
        txtName = findViewById(R.id.txtName)
        txtState = findViewById(R.id.txtState)
        txtDk = findViewById(R.id.txtDk)
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
                Toast.makeText(this, "Installed/Downloaded apps में ${Prefs.assistantName(this)} ON करें", Toast.LENGTH_LONG).show()
                Setup.openAccessibility(this)
            }
        }
        chipAi.setOnClickListener { openSettings() }
        findViewById<TextView>(R.id.chipUpdate).setOnClickListener { openSettings() }

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
            Toast.makeText(this, "माइक की परमिशन बंद है — App info → Permissions → Microphone → Allow करें", Toast.LENGTH_LONG).show()
            Setup.openAppInfo(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("ask_mic", false)) checkMicPerm(force = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val i = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (i >= 0) {
            val ok = grantResults.getOrNull(i) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!ok) Prefs.edit(this) { putBoolean("mic_denied", true) }
            Toast.makeText(this, if (ok) "✓ माइक की परमिशन मिल गई — अब DK सुन सकता है" else "माइक की परमिशन नहीं मिली — इसके बिना DK सुन नहीं सकता", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "पहले कोई भी फ्री API key डालें — DK खुद सबसे तेज़ फ्री मॉडल चुन लेगा", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "brain")); return false
            }
            !Setup.accessibilityOn() && !Setup.selfEnableAccessibility(this) && !Setup.enabledInSettings(this) -> {
                Toast.makeText(this, "Accessibility में ${Prefs.assistantName(this)} ON करें", Toast.LENGTH_LONG).show()
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
        render()
        checkUpdate()
    }

    /** App khulte hi (har 3 ghante me max ek baar) GitHub par naya version dekho. */
    private fun checkUpdate() {
        if (!Prefs.autoUpdate(this)) return
        val chip = findViewById<TextView>(R.id.chipUpdate)
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(this) < 3 * 60 * 60 * 1000L) {
            val latest = getSharedPreferences("dk_prefs", MODE_PRIVATE).getInt("upd_latest", 0)
            chip.visibility = if (latest > Updater.currentCode(this)) android.view.View.VISIBLE else android.view.View.GONE
            return
        }
        lifecycleScope.launch {
            val rel = try { Updater.check(this@MainActivity) } catch (e: Exception) { null }
            chip.visibility = if (rel != null) android.view.View.VISIBLE else android.view.View.GONE
            if (rel != null) Toast.makeText(this@MainActivity, "DK का नया अपडेट आया है — सेटिंग्स → अपडेट", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        DKState.unlisten(stateListener)
        super.onPause()
    }

    private fun render() {
        val name = Prefs.assistantName(this)
        val user = Prefs.userName(this)
        txtName.text = name
        txtHello.text = if (user.isBlank()) "नमस्ते" else "नमस्ते, $user"

        chipAccess.text = (if (Setup.accessibilityOn(this)) "✓ " else "✗ ") + "फ़ोन कंट्रोल"
        chipMic.text = (if (Setup.hasMic(this)) "✓ " else "✗ ") + "माइक"
        val p = Providers.byId(Prefs.activeProvider(this))
        chipAi.text = (if (Prefs.hasAnyKey(this)) "✓ " else "✗ ") + (p?.name ?: "AI")

        val live = DKState.live
        txtState.text = when (DKState.mode) {
            Mode.IDLE -> if (live) "माइक चालू है • आवाज़ स्क्रीन खोलें" else "ओर्ब या \"आवाज़\" दबाकर बात करें"
            Mode.LISTENING -> if (live) "Listening • सुन रहा हूँ… (बंद: टैप या \"माइक बंद\")" else "Listening • सुन रहा हूँ…"
            Mode.THINKING -> "जवाब आ रहा है…"
            Mode.WORKING -> "काम कर रहा हूँ…"
            Mode.SPEAKING -> "Speaking • बोल रहा हूँ…"
        }
        txtDk.text = if (DKState.lastReply.isBlank()) "" else "$name: " + DKState.lastReply
    }
}
