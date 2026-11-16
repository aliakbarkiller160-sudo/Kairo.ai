package com.phoneagent.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etApiKey: EditText
    private lateinit var etWakeName: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        etApiKey = findViewById(R.id.etApiKey)
        etWakeName = findViewById(R.id.etWakeName)

        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etWakeName.setText(prefs.getString("wake_name", "Kairo"))

        findViewById<Button>(R.id.btnAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStartListen).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val wakeName = etWakeName.text.toString().trim().ifEmpty { "Kairo" }

            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Pehle Gemini API Key daalein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("api_key", apiKey)
                .putString("wake_name", wakeName)
                .apply()

            if (!hasRequiredPermissions()) {
                requestRequiredPermissions()
                return@setOnClickListener
            }

            val serviceIntent = Intent(this, WakeWordVoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "$wakeName Listening Shuru!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStopListen).setOnClickListener {
            stopService(Intent(this, WakeWordVoiceService::class.java))
            appendLog("■ Kairo Listening band kar di gayi.")
        }

        WakeWordVoiceService.onLogUpdate = { msg ->
            appendLog(msg)
        }
    }

    override fun onResume() {
        super.onResume()
        val isAccEnabled = AgentAccessibilityService.instance != null
        if (isAccEnabled) {
            tvStatus.text = "Accessibility Service: ON ✅"
            tvStatus.setTextColor(0xFF22C55E.toInt())
        } else {
            tvStatus.text = "Accessibility Service: OFF ⚠️ (Upar button se ON karein)"
            tvStatus.setTextColor(0xFFF59E0B.toInt())
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return mic && notif
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    private fun appendLog(line: String) {
        val current = tvLog.text.toString()
        val updated = if (current.startsWith("API Key daalein")) line else "$current\n\n$line"
        tvLog.text = updated
    }
}
