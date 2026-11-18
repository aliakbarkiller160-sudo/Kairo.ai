package com.phoneagent.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAgentState: TextView
    private lateinit var tvLiveVoiceSpeech: TextView
    private lateinit var tvCreditsBalance: TextView
    private lateinit var tvCreditsUsed: TextView
    private lateinit var tvPlanBadge: TextView
    private lateinit var tvChatHistory: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollChat: ScrollView
    private lateinit var scrollErrors: ScrollView

    private lateinit var etApiKey: EditText
    private lateinit var etWakeName: EditText
    private lateinit var etDirectCommand: EditText

    private lateinit var sectionChat: LinearLayout
    private lateinit var sectionApi: ScrollView
    private lateinit var sectionErrors: LinearLayout

    private lateinit var tabChat: Button
    private lateinit var tabApi: Button
    private lateinit var tabErrors: Button

    private var isKeyVisible = false
    private var unreadErrorCount = 0
    private var activeTab = 0 // 0 = Chat, 1 = API & Setup, 2 = Errors

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeCardBg(fillColor: Int, strokeColor: Int, radiusDp: Int = 14): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(buildJarvisDashboardUI())

        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        if (!prefs.contains("credits_balance")) {
            prefs.edit().putInt("credits_balance", 500).putInt("credits_used", 0).apply()
        }

        etApiKey.setText(prefs.getString("api_key", ""))
        etWakeName.setText(prefs.getString("wake_name", "Kairo"))
        refreshCreditsUI()
        switchTab(0)

        WakeWordVoiceService.onLogUpdate = { msg -> routeServiceMessage(msg) }
        WakeWordVoiceService.onStateChange = { state -> updateAgentBadge(state) }
        WakeWordVoiceService.onCreditUsed = { _ -> refreshCreditsUI() }
    }

    private fun buildJarvisDashboardUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090D16.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // =====================================================================
        // 1. TOP CORE HUD & LIVE VOICE OUTPUT CARD
        // =====================================================================
        val cardHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(0xFF131B2E.toInt(), 0xFF1E293B.toInt(), 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = "KAIRO AI • JARVIS HUD"
            setTextColor(0xFF38BDF8.toInt())
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.06f
        }

        tvStatus = TextView(this).apply {
            text = "OS Control (Accessibility): CHECKING..."
            setTextColor(0xFFF59E0B.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }

        titleCol.addView(tvTitle)
        titleCol.addView(tvStatus)

        tvAgentState = TextView(this).apply {
            text = "OFFLINE"
            setTextColor(0xFFF87171.toInt())
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        topRow.addView(titleCol)
        topRow.addView(tvAgentState)
        cardHeader.addView(topRow)

        // Kairo Live Spoken Voice Box
        val cardVoiceLive = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(0xFF0B1324.toInt(), 0xFF0284C7.toInt(), 12)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val tvVoiceLabel = TextView(this).apply {
            text = "🔊 KAIRO LIVE VOICE OUTPUT"
            setTextColor(0xFF38BDF8.toInt())
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
        }

        tvLiveVoiceSpeech = TextView(this).apply {
            text = "Standby... Activate Kairo to start voice link."
            setTextColor(0xFFF8FAFC.toInt())
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        cardVoiceLive.addView(tvVoiceLabel)
        cardVoiceLive.addView(tvLiveVoiceSpeech)
        cardHeader.addView(cardVoiceLive)

        // Activate / Stop Buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val btnStartListen = Button(this).apply {
            text = "⚡ ACTIVATE KAIRO"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(0xFF0284C7.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1.4f).apply { marginEnd = dp(6) }
            setOnClickListener { handleActivateKairo() }
        }

        val btnStopListen = Button(this).apply {
            text = "■ STOP"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(0xFFDC2626.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 0.8f).apply { marginStart = dp(6) }
            setOnClickListener {
                stopService(Intent(this@MainActivity, WakeWordVoiceService::class.java))
                updateAgentBadge("OFFLINE")
                tvLiveVoiceSpeech.text = "Kairo offline."
                appendErrorLog("■ Kairo service stopped by user.")
            }
        }

        btnRow.addView(btnStartListen)
        btnRow.addView(btnStopListen)
        cardHeader.addView(btnRow)
        root.addView(cardHeader)

        // =====================================================================
        // 2. THREE SEPARATE SECTION TABS (CHAT | API & SETUP | ERRORS)
        // =====================================================================
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }

        tabChat = Button(this).apply {
            text = "💬 CHAT"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) }
            setOnClickListener { switchTab(0) }
        }

        tabApi = Button(this).apply {
            text = "🔑 API & SETUP"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnClickListener { switchTab(1) }
        }

        tabErrors = Button(this).apply {
            text = "⚠️ ERRORS"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) }
            setOnClickListener { switchTab(2) }
        }

        tabBar.addView(tabChat)
        tabBar.addView(tabApi)
        tabBar.addView(tabErrors)
        root.addView(tabBar)

        // =====================================================================
        // SECTION A: CHAT & COMMAND CONSOLE
        // =====================================================================
        sectionChat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(0xFF131B2E.toInt(), 0xFF1E293B.toInt(), 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val chatHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvChatTitle = TextView(this).apply {
            text = "💬 KAIRO CONVERSATION FEED"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClearChat = TextView(this).apply {
            text = "Clear Chat"
            setTextColor(0xFF38BDF8.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                tvChatHistory.text = "🤖 Kairo: Chat clear kar di hai. Jab zaroorat ho bas 'Kairo' bolna."
            }
        }

        chatHeaderRow.addView(tvChatTitle)
        chatHeaderRow.addView(btnClearChat)
        sectionChat.addView(chatHeaderRow)

        scrollChat = ScrollView(this).apply {
            setBackgroundColor(0xFF0B1120.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }

        tvChatHistory = TextView(this).apply {
            text = "🤖 Kairo: Assalam-o-Alaikum! Jab zaroorat ho bas 'Kairo' bolna, main hazir hoon."
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        scrollChat.addView(tvChatHistory)
        sectionChat.addView(scrollChat)

        val cmdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        etDirectCommand = EditText(this).apply {
            hint = "e.g. WhatsApp kholo aur Behna ko AOA bhejo..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF64748B.toInt())
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundColor(0xFF1E293B.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
        }

        val btnSendCommand = Button(this).apply {
            text = "SEND"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(0xFF16A34A.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(46))
            setOnClickListener { handleSendCommand() }
        }

        cmdRow.addView(etDirectCommand)
        cmdRow.addView(btnSendCommand)
        sectionChat.addView(cmdRow)
        root.addView(sectionChat)

        // =====================================================================
        // SECTION B: API CONNECT, CREDITS & SYSTEM PERMISSIONS
        // =====================================================================
        sectionApi = ScrollView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val apiContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val cardConfig = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(0xFF131B2E.toInt(), 0xFF1E293B.toInt(), 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        cardConfig.addView(TextView(this).apply {
            text = "🔑 API CONNECTION & WAKE WORD"
            setTextColor(0xFF38BDF8.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        })

        cardConfig.addView(TextView(this).apply {
            text = "Groq API Key (gsk_...)"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })

        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        etApiKey = EditText(this).apply {
            hint = "Paste gsk_... key here"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF64748B.toInt())
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundColor(0xFF1E293B.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
        }

        val btnToggleKey = Button(this).apply {
            text = "Show"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            isSingleLine = true
            setPadding(dp(4), 0, dp(4), 0)
            backgroundTintList = ColorStateList.valueOf(0xFF334155.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(46))
            setOnClickListener {
                isKeyVisible = !isKeyVisible
                if (isKeyVisible) {
                    etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    text = "Hide"
                } else {
                    etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    text = "Show"
                }
                etApiKey.setSelection(etApiKey.text.length)
            }
        }

        keyRow.addView(etApiKey)
        keyRow.addView(btnToggleKey)
        cardConfig.addView(keyRow)

        cardConfig.addView(TextView(this).apply {
            text = "AI Assistant Name (Wake Word)"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })

        etWakeName = EditText(this).apply {
            hint = "Kairo"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundColor(0xFF1E293B.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(4) }
        }
        cardConfig.addView(etWakeName)

        val apiActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val btnSaveApi = Button(this).apply {
            text = "💾 SAVE API"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(0xFF16A34A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
                var apiKey = etApiKey.text.toString().trim()
                if (apiKey.startsWith("Gsk_")) {
                    apiKey = "gsk_" + apiKey.substring(4)
                    etApiKey.setText(apiKey)
                }
                val wakeName = etWakeName.text.toString().trim().ifEmpty { "Kairo" }
                if (apiKey.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Pehle API Key paste karein!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("api_key", apiKey).putString("wake_name", wakeName).apply()
                Toast.makeText(this@MainActivity, "✅ API Key & Settings Saved!", Toast.LENGTH_SHORT).show()
                switchTab(0)
            }
        }

        val btnAcc = Button(this).apply {
            text = "⚙️ PERMISSIONS"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(0xFF334155.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        apiActionRow.addView(btnSaveApi)
        apiActionRow.addView(btnAcc)
        cardConfig.addView(apiActionRow)
        apiContainer.addView(cardConfig)

        // Credits Card
        val cardCredits = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg(0xFF131B2E.toInt(), 0xFF1E293B.toInt(), 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val creditTopRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        creditTopRow.addView(TextView(this).apply {
            text = "AVAILABLE CREDITS"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvPlanBadge = TextView(this).apply {
            text = "PRO TIER"
            setTextColor(0xFF38BDF8.toInt())
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
        }
        creditTopRow.addView(tvPlanBadge)
        cardCredits.addView(creditTopRow)

        val creditMidRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        tvCreditsBalance = TextView(this).apply {
            text = "500 CR"
            setTextColor(0xFF22C55E.toInt())
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvCreditsUsed = TextView(this).apply {
            text = "Used: 0 actions"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 12f
        }
        creditMidRow.addView(tvCreditsBalance)
        creditMidRow.addView(tvCreditsUsed)
        cardCredits.addView(creditMidRow)

        val topupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        listOf(
            Triple("+100 CR", 100, 0xFF1E293B.toInt()),
            Triple("+500 CR", 500, 0xFF1E293B.toInt()),
            Triple("+1000 CR", 1000, 0xFF16A34A.toInt())
        ).forEachIndexed { idx, (label, amount, tint) ->
            val btn = Button(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                isSingleLine = true
                setPadding(dp(2), 0, dp(2), 0)
                backgroundTintList = ColorStateList.valueOf(tint)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (idx > 0) marginStart = dp(4)
                    if (idx < 2) marginEnd = dp(4)
                }
                setOnClickListener { showTopUpDialog(amount, "Kairo Pack ($label)") }
            }
            topupRow.addView(btn)
        }

        cardCredits.addView(topupRow)
        apiContainer.addView(cardCredits)
        sectionApi.addView(apiContainer)
        root.addView(sectionApi)

        // =====================================================================
        // SECTION C: SYSTEM ERRORS & DIAGNOSTICS LOGS
        // =====================================================================
        sectionErrors = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = makeCardBg(0xFF131B2E.toInt(), 0xFF1E293B.toInt(), 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val errHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        errHeaderRow.addView(TextView(this).apply {
            text = "⚠️ SYSTEM ERRORS & DIAGNOSTICS"
            setTextColor(0xFFF87171.toInt())
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        errHeaderRow.addView(TextView(this).apply {
            text = "Clear Errors"
            setTextColor(0xFF38BDF8.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                tvLog.text = "✅ No system errors detected."
                unreadErrorCount = 0
                updateTabStyles()
            }
        })
        sectionErrors.addView(errHeaderRow)

        scrollErrors = ScrollView(this).apply {
            setBackgroundColor(0xFF0B1120.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
            }
        }

        tvLog = TextView(this).apply {
            text = "✅ No system errors detected."
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1f)
        }

        scrollErrors.addView(tvLog)
        sectionErrors.addView(scrollErrors)
        root.addView(sectionErrors)

        return root
    }

    private fun handleActivateKairo() {
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        var apiKey = etApiKey.text.toString().trim()
        if (apiKey.startsWith("Gsk_")) {
            apiKey = "gsk_" + apiKey.substring(4)
            etApiKey.setText(apiKey)
        }
        val wakeName = etWakeName.text.toString().trim().ifEmpty { "Kairo" }

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Pehle API & Setup tab mein Key paste karein!", Toast.LENGTH_SHORT).show()
            switchTab(1)
            return
        }

        prefs.edit().putString("api_key", apiKey).putString("wake_name", wakeName).apply()

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }

        startKairoService(null)
        Toast.makeText(this, "$wakeName Standby Mode Active!", Toast.LENGTH_SHORT).show()
    }

    private fun handleSendCommand() {
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        val cmd = etDirectCommand.text.toString().trim()
        var apiKey = etApiKey.text.toString().trim()
        if (apiKey.startsWith("Gsk_")) {
            apiKey = "gsk_" + apiKey.substring(4)
            etApiKey.setText(apiKey)
        }
        val wakeName = etWakeName.text.toString().trim().ifEmpty { "Kairo" }

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Pehle API & Setup tab mein API Key save karein!", Toast.LENGTH_SHORT).show()
            switchTab(1)
            return
        }
        if (cmd.isEmpty()) {
            Toast.makeText(this, "Koi command ya message likhein!", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().putString("api_key", apiKey).putString("wake_name", wakeName).apply()
        etDirectCommand.setText("")

        if (WakeWordVoiceService.instance != null) {
            WakeWordVoiceService.instance?.triggerDirectCommand(cmd)
        } else {
            startKairoService(cmd)
        }
    }

    private fun switchTab(index: Int) {
        activeTab = index
        sectionChat.visibility = if (index == 0) View.VISIBLE else View.GONE
        sectionApi.visibility = if (index == 1) View.VISIBLE else View.GONE
        sectionErrors.visibility = if (index == 2) View.VISIBLE else View.GONE

        if (index == 2) {
            unreadErrorCount = 0
        }
        updateTabStyles()
    }

    private fun updateTabStyles() {
        tabChat.backgroundTintList = ColorStateList.valueOf(
            if (activeTab == 0) 0xFF0284C7.toInt() else 0xFF1E293B.toInt()
        )
        tabChat.setTextColor(if (activeTab == 0) 0xFFFFFFFF.toInt() else 0xFFCBD5E1.toInt())

        tabApi.backgroundTintList = ColorStateList.valueOf(
            if (activeTab == 1) 0xFF0284C7.toInt() else 0xFF1E293B.toInt()
        )
        tabApi.setTextColor(if (activeTab == 1) 0xFFFFFFFF.toInt() else 0xFFCBD5E1.toInt())

        val errorBg = when {
            activeTab == 2 -> 0xFFDC2626.toInt()
            unreadErrorCount > 0 -> 0xFF7F1D1D.toInt()
            else -> 0xFF1E293B.toInt()
        }
        tabErrors.backgroundTintList = ColorStateList.valueOf(errorBg)
        tabErrors.text = if (unreadErrorCount > 0) "⚠️ ERRORS ($unreadErrorCount)" else "⚠️ ERRORS"
        tabErrors.setTextColor(if (activeTab == 2 || unreadErrorCount > 0) 0xFFFFFFFF.toInt() else 0xFFCBD5E1.toInt())
    }

    private fun routeServiceMessage(msg: String) {
        when {
            msg.startsWith("🗣️ Kairo:") -> {
                val spokenOnly = msg.removePrefix("🗣️ Kairo:").trim()
                tvLiveVoiceSpeech.text = "\"$spokenOnly\""
                appendChatLine("🤖 Kairo: $spokenOnly")
            }
            msg.startsWith("👤 Tum:") || msg.startsWith("⌨️ Command:") -> {
                val cleanUser = msg.replace("⌨️ Command:", "👤 Tum:")
                appendChatLine(cleanUser)
            }
            msg.startsWith("📲 Opening:") -> {
                appendChatLine(msg)
            }
            else -> {
                appendErrorLog(msg)
                if (msg.startsWith("❌") || msg.startsWith("⚠️")) {
                    if (activeTab != 2) {
                        unreadErrorCount++
                        updateTabStyles()
                    }
                }
            }
        }
    }

    private fun appendChatLine(line: String) {
        val current = tvChatHistory.text.toString()
        tvChatHistory.text = "$current\n\n$line"
        scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendErrorLog(line: String) {
        val current = tvLog.text.toString()
        tvLog.text = if (current.startsWith("✅ No system errors")) {
            line
        } else {
            "$current\n\n$line"
        }
        scrollErrors.post { scrollErrors.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && hasRequiredPermissions()) {
            val wakeName = etWakeName.text.toString().trim().ifEmpty { "Kairo" }
            startKairoService(null)
            Toast.makeText(this, "$wakeName Standby Mode Active!", Toast.LENGTH_SHORT).show()
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
                appendErrorLog("💳 Top-Up Successful: +$creditsToAdd CR added (New Balance: $newBalance CR)")
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
        tvAgentState.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (isOnline) 0x2222C55E else 0x22F87171)
            setStroke(dp(1), if (isOnline) 0xFF22C55E.toInt() else 0xFFF87171.toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCreditsUI()
        updateAgentBadge(if (WakeWordVoiceService.isRunning) "STANDBY (WAKE-WORD)" else "OFFLINE")

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
}
