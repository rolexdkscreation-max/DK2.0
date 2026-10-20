package com.dk.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Setup screen: API key, permissions, accessibility ON, start. */
class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        val edtKey = findViewById<EditText>(R.id.edtApiKey)
        val edtModel = findViewById<EditText>(R.id.edtModel)
        val chkWake = findViewById<CheckBox>(R.id.chkWake)
        val chkShot = findViewById<CheckBox>(R.id.chkScreenshot)
        val edtTest = findViewById<EditText>(R.id.edtTest)

        edtKey.setText(Prefs.apiKey(this))
        edtModel.setText(Prefs.model(this))
        chkWake.isChecked = Prefs.wakeMode(this)
        chkShot.isChecked = Prefs.screenshot(this)

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.save(this, edtKey.text.toString(), edtModel.text.toString(), chkWake.isChecked, chkShot.isChecked)
            Toast.makeText(this, "Save ho gaya", Toast.LENGTH_SHORT).show()
            DKAccessibilityService.instance?.startWakeLoopIfEnabled()
            refresh()
        }

        findViewById<Button>(R.id.btnPerms).setOnClickListener {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 10)
        }

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            Toast.makeText(this, "Installed apps / Downloaded apps me 'DK' ON karo", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!hasMic()) {
                Toast.makeText(this, "Pehle mic permission do (button 2)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
            if (DKAccessibilityService.instance == null) {
                Toast.makeText(this, "Accessibility me DK ON karo (button 3)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "DK chalu hai. Bubble par tap karke bolo", Toast.LENGTH_LONG).show()
            }
            refresh()
        }

        findViewById<Button>(R.id.btnTts).setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                Toast.makeText(this, "TTS settings nahi khuli", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnRun).setOnClickListener {
            val cmd = edtTest.text.toString().trim()
            val svc = DKAccessibilityService.instance
            when {
                cmd.isEmpty() -> Toast.makeText(this, "Command likho", Toast.LENGTH_SHORT).show()
                svc == null -> Toast.makeText(this, "Pehle Accessibility me DK ON karo", Toast.LENGTH_LONG).show()
                else -> svc.runCommand(cmd)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DKLog.listener = { txtLog.text = it }
        txtLog.text = DKLog.all()
        refresh()
    }

    override fun onPause() {
        DKLog.listener = null
        super.onPause()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val ok = "✔"; val no = "✘"
        txtStatus.text = buildString {
            append(if (Prefs.apiKey(this@MainActivity).isNotBlank()) ok else no).append(" API key\n")
            append(if (hasMic()) ok else no).append(" Mic permission\n")
            append(if (DKAccessibilityService.instance != null) ok else no).append(" Accessibility service\n")
            append("Model: ").append(Prefs.model(this@MainActivity))
        }
    }
}
