package com.phoneagent.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAgentState: TextView
    private lateinit var tvCreditsBalance: TextView
    private lateinit var tvCreditsUsed: TextView
    private lateinit var tvPlanBadge: TextView
    private lateinit var tvLog: TextView
    private lateinit var etApiKey: EditText
    private lateinit var etWakeName: EditText
    private lateinit var etDirectCommand: EditText
    private var isKeyVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applyExecutiveCardStyling()

        tvStatus = findViewById(R.id.tvStatus)
        tvAgentState = findViewById(R.id.tvAgentState)
        tvCreditsBalance = findViewById(R.id.tvCreditsBalance)
        tvCreditsUsed = findViewById(R.id.tvCreditsUsed)
        tvPlanBadge = findViewById(R.id.tvPlanBadge)
        tvLog = findViewById(R.id.tvLog)
        etApiKey = findViewById(R.id.etApiKey)
        etWakeName = findViewById(R.id.etWakeName)
        etDirectCommand = findViewById(R.id.etDirectCommand)

        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        if (!prefs.contains("credits_balance")) {
            prefs.edit().putInt("credits_balance", 500).putInt("credits_used", 0).apply()
        }

        etApiKey.setText(prefs.getString("api_key", ""))
        etWakeName.setText(prefs.getString("wake_name", "Kairo"))
        refreshCreditsUI()

        // Show / Hide API key
        val btnToggleKey = findViewById<Button>(R.id.btnToggleKey)
        btnToggleKey.setOnClickListener {
            isKeyVisible = !isKeyVisible
            if (isKeyVisible) {
                etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                btnToggleKey.text = "Hide"
            } else {
                etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                btnToggleKey.text = "Show"
            }
            etApiKey.setSelection(etApiKey.text.length)
        }

        // Open Accessibility Settings
        findViewById<Button>(R.id.btnAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Top-Up Buttons
        findViewById<Button>(R.id.btnTopup100).setOnClickListener { showTopUpDialog(100, "Starter Pack (+100 CR)") }
        findViewById<Button>(R.id.btnTopup500).setOnClickListener { showTopUpDialog(500, "Executive Pack (+500 CR)") }
        findViewById<Button>(R.id.btnTopup1000).setOnClickListener { showTopUpDialog(1000, "Pro Unlimited (+1000 CR)") }

        // Activate Kairo Voice Service
        findViewById<Button>(R.id.btnStartListen).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val wakeName = etWakeName.text.toString().trim().ifEmpty { "Kairo" }

            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Pehle Gemini API Key paste karein!", Toast.LENGTH_SHORT).show()
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

            startKairoService(null)
            Toast.makeText(this, "$wakeName Command-Only Mode Active!", Toast.LENGTH_SHORT).show()
        }

        // Stop Service
        findViewById<Button>(R.id.btnStopListen).setOnClickListener {
            stopService(Intent(this, WakeWordVoiceService::class.java))
            updateAgentBadge("OFFLINE")
            appendLog("■ Kairo service stopped.")
        }

        // Direct Text Command Execution
        findViewById<Button>(R.id.btnSendCommand).setOnClickListener {
            val cmd = etDirectCommand.text.toString().trim()
            val apiKey = etApiKey.text.toString().trim()
            val wakeName = etWakeName.text.toString().trim().ifEmpty { "Kairo" }

            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Pehle API Key save karein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (cmd.isEmpty()) {
                Toast.makeText(this, "Koi command likhein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putString("api_key", apiKey).putString("wake_name", wakeName).apply()
            etDirectCommand.setText("")

            if (WakeWordVoiceService.instance != null) {
                WakeWordVoiceService.instance?.triggerDirectCommand(cmd)
            } else {
                startKairoService(cmd)
            }
        }

        // Clear Logs
        findViewById<TextView>(R.id.btnClearLogs).setOnClickListener {
            tvLog.text = "Logs cleared."
        }

        // Service Callbacks
        WakeWordVoiceService.onLogUpdate = { msg -> appendLog(msg) }
        WakeWordVoiceService.onStateChange = { state -> updateAgentBadge(state) }
        WakeWordVoiceService.onCreditUsed = { _ -> refreshCreditsUI() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && hasRequiredPermissions()) {
            val wakeName = etWakeName.text.toString().trim().ifEmpty { "Kairo" }
            startKairoService(null)
            Toast.makeText(this, "$wakeName Command-Only Mode Active!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startKairoService(manualCommand: String?) {
        val serviceIntent = Intent(this, WakeWordVoiceService::class.java).apply {
            if (!manualCommand.isNullOrBlank()) {
                putExtra("manual_command", manualCommand)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun showTopUpDialog(creditsToAdd: Int, packName: String) {
        AlertDialog.Builder(this)
            .setTitle("Confirm Credit Top-Up")
            .setMessage("Package: $packName\n\nKya aap apne Kairo AI account mein $creditsToAdd credits add karna chahte hain?")
            .setPositiveButton("Confirm Top-Up") { _, _ ->
                val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
                val current = prefs.getInt("credits_balance", 500)
                val newBalance = current + creditsToAdd
                prefs.edit().putInt("credits_balance", newBalance).apply()
                refreshCreditsUI()
                appendLog("💳 Top-Up Successful: +$creditsToAdd CR added (New Balance: $newBalance CR)")
                Toast.makeText(this, "+$creditsToAdd Credits Added!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshCreditsUI() {
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        val balance = prefs.getInt("credits_balance", 500)
        val used = prefs.getInt("credits_used", 0)
        tvCreditsBalance.text = "$balance CR"
        tvCreditsUsed.text = "Used: $used actions"
        tvPlanBadge.text = if (balance >= 1000) "EXECUTIVE PRO" else "PRO TIER"
        tvCreditsBalance.setTextColor(if (balance > 50) 0xFF22C55E.toInt() else 0xFFF59E0B.toInt())
    }

    private fun updateAgentBadge(state: String) {
        tvAgentState.text = state
        val isOnline = state != "OFFLINE"
        tvAgentState.setTextColor(if (isOnline) 0xFF22C55E.toInt() else 0xFFF87171.toInt())
        val pill = GradientDrawable().apply {
            cornerRadius = 30f
            setColor(if (isOnline) 0x2222C55E else 0x22F87171)
            setStroke(2, if (isOnline) 0xFF22C55E.toInt() else 0xFFF87171.toInt())
        }
        tvAgentState.background = pill
    }

    private fun applyExecutiveCardStyling() {
        val cardIds = listOf(
            R.id.cardHeader,
            R.id.cardCredits,
            R.id.cardConfig,
            R.id.cardCommand,
            R.id.cardLogs
        )
        for (id in cardIds) {
            findViewById<LinearLayout>(id)?.background = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(0xFF131B2E.toInt())
                setStroke(2, 0xFF1E293B.toInt())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCreditsUI()
        updateAgentBadge(if (WakeWordVoiceService.isRunning) "LISTENING (SILENT)" else "OFFLINE")

        val isAccEnabled = AgentAccessibilityService.instance != null
        if (isAccEnabled) {
            tvStatus.text = "OS Control (Accessibility): ACTIVE ✅"
            tvStatus.setTextColor(0xFF22C55E.toInt())
        } else {
            tvStatus.text = "OS Control (Accessibility): OFF ⚠️"
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
        val updated = if (current.startsWith("System ready.") || current.startsWith("Logs cleared.")) {
            line
        } else {
            "$current\n\n$line"
        }
        tvLog.text = updated
    }
}
