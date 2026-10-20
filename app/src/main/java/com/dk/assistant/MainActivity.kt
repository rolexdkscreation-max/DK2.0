package com.dk.assistant

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/** Mukhya screen: beech me 3D orb, upar status, neeche baatcheet aur likhne ki bar. */
class MainActivity : AppCompatActivity() {

    private lateinit var txtHello: TextView
    private lateinit var txtName: TextView
    private lateinit var txtState: TextView
    private lateinit var txtYou: TextView
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
        txtYou = findViewById(R.id.txtYou)
        txtDk = findViewById(R.id.txtDk)
        chipAccess = findViewById(R.id.chipAccess)
        chipMic = findViewById(R.id.chipMic)
        chipAi = findViewById(R.id.chipAi)
        val edt = findViewById<EditText>(R.id.edtCmd)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { openSettings() }
        findViewById<OrbView>(R.id.orb).setOnClickListener { talk() }
        findViewById<ImageButton>(R.id.btnMic).setOnClickListener { talk() }

        val send = {
            val cmd = edt.text.toString().trim()
            if (cmd.isNotEmpty() && ready()) {
                DKAccessibilityService.instance?.runCommand(cmd)
                edt.setText("")
            }
        }
        findViewById<ImageButton>(R.id.btnSend).setOnClickListener { send() }
        edt.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }

        chipMic.setOnClickListener { askPerms() }
        chipAccess.setOnClickListener {
            if (!Setup.accessibilityOn()) {
                Toast.makeText(this, "Installed/Downloaded apps में ${Prefs.assistantName(this)} ON करें", Toast.LENGTH_LONG).show()
                Setup.openAccessibility(this)
            }
        }
        chipAi.setOnClickListener { openSettings() }

        if (!Setup.hasMic(this)) askPerms()
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun askPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Setup.startKeepAlive(this)
        render()
    }

    /** Sab taiyaar hai? Nahi to sahi jagah bhejo. */
    private fun ready(): Boolean {
        when {
            !Setup.hasMic(this) -> { askPerms(); return false }
            !Prefs.hasAnyKey(this) -> {
                Toast.makeText(this, "पहले सेटिंग्स में API key डालकर सेव करें", Toast.LENGTH_LONG).show()
                openSettings(); return false
            }
            !Setup.accessibilityOn() -> {
                Toast.makeText(this, "Accessibility में ${Prefs.assistantName(this)} ON करें", Toast.LENGTH_LONG).show()
                Setup.openAccessibility(this); return false
            }
        }
        return true
    }

    private fun talk() {
        if (DKState.live) DKAccessibilityService.instance?.stopLive()
        else if (ready()) DKAccessibilityService.instance?.startLive()
    }

    override fun onResume() {
        super.onResume()
        DKState.listen(stateListener)
        Setup.startKeepAlive(this)
        render()
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

        chipAccess.text = (if (Setup.accessibilityOn()) "✓ " else "✗ ") + "फ़ोन कंट्रोल"
        chipMic.text = (if (Setup.hasMic(this)) "✓ " else "✗ ") + "माइक"
        val p = Providers.byId(Prefs.activeProvider(this))
        chipAi.text = (if (Prefs.hasAnyKey(this)) "✓ " else "✗ ") + (p?.name ?: "AI")

        val live = DKState.live
        findViewById<ImageButton>(R.id.btnMic).setBackgroundResource(
            if (live) R.drawable.btn_live_circle else R.drawable.btn_accent_circle)
        txtState.text = when (DKState.mode) {
            Mode.IDLE -> if (live) "माइक चालू है • बंद करने के लिए टैप करें" else "एक बार टैप करें • लगातार सुनूँगा"
            Mode.LISTENING -> if (live) "सुन रहा हूँ… (बंद: टैप या \"माइक बंद\")" else "सुन रहा हूँ…"
            Mode.THINKING -> "सोच रहा हूँ…"
            Mode.WORKING -> "काम कर रहा हूँ…"
            Mode.SPEAKING -> "बोल रहा हूँ…"
        }
        txtYou.text = "आप: " + DKState.lastUser.ifBlank { "—" }
        txtDk.text = "$name: " + DKState.lastReply.ifBlank { "—" }
    }
}
