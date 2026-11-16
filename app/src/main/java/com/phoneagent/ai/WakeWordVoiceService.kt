package com.phoneagent.ai

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.Locale

class WakeWordVoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var isRunning = false
        var instance: WakeWordVoiceService? = null
        var onLogUpdate: ((String) -> Unit)? = null
        var onStateChange: ((String) -> Unit)? = null
        var onCreditUsed: ((Int) -> Unit)? = null
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isExecutingTask = false
    private var isBeepMuted = false
    private var savedNotifVolume = -1
    private var savedSystemVolume = -1

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        val channelId = "kairo_voice_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Kairo AI Executive Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Kairo AI • Command-Only Mode 🛡️")
            .setContentText("'Hey Kairo' bol kar hukam dein...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1001, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manualCmd = intent?.getStringExtra("manual_command")
        if (!manualCmd.isNullOrBlank()) {
            triggerDirectCommand(manualCmd)
            return START_STICKY
        }
        updateState("LISTENING (SILENT)")
        log("🛡️ Kairo Pro Active — Sirf tumhare hukam ka intezar hai.")
        mainHandler.postDelayed({ startListeningCycle() }, 600)
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts.setLanguage(Locale("ur", "PK"))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale.US
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    isExecutingTask = false
                    updateState("LISTENING (SILENT)")
                    mainHandler.post { startListeningCycle() }
                }
                override fun onError(utteranceId: String?) {
                    isExecutingTask = false
                    updateState("LISTENING (SILENT)")
                    mainHandler.post { startListeningCycle() }
                }
            })
        }
    }

    // Mute Android's repetitive "ton-ton" recognizer beep
    private fun muteRecognizerBeep(mute: Boolean) {
        try {
            if (mute && !isBeepMuted) {
                savedNotifVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                savedSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                isBeepMuted = true
            } else if (!mute && isBeepMuted) {
                if (savedNotifVolume >= 0) audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVolume, 0)
                if (savedSystemVolume >= 0) audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVolume, 0)
                isBeepMuted = false
            }
        } catch (_: Exception) {
            // Ignore on devices with Do Not Disturb restrictions
        }
    }

    private fun speakAndResume(text: String) {
        muteRecognizerBeep(false)
        if (text.isBlank()) {
            isExecutingTask = false
            updateState("LISTENING (SILENT)")
            startListeningCycle()
            return
        }
        updateState("SPEAKING")
        log("🗣️ Kairo: $text")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply_id")
    }

    private fun startListeningCycle() {
        if (!isRunning || isExecutingTask) return
        try {
            muteRecognizerBeep(true)
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_EXTRA_LANGUAGES, arrayListOf("ur-PK", "hi-IN", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.trim() ?: ""
                    if (spoken.isNotEmpty()) {
                        handleSpokenText(spoken)
                    } else {
                        restartListenerDelayed(400)
                    }
                }

                override fun onError(error: Int) {
                    val delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 600L
                    restartListenerDelayed(delayMs)
                }
            })
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            restartListenerDelayed(1200)
        }
    }

    private fun restartListenerDelayed(ms: Long) {
        if (!isRunning || isExecutingTask) return
        mainHandler.postDelayed({ startListeningCycle() }, ms)
    }

    private fun isWakeWordDetected(spokenLower: String, wakeNameLower: String): Boolean {
        if (spokenLower.contains(wakeNameLower)) return true
        if (wakeNameLower == "kairo") {
            val variants = listOf("kairo", "cairo", "kyro", "kero", "qairo", "kairon", "کائرو", "کیرو")
            return variants.any { spokenLower.contains(it) }
        }
        return false
    }

    private fun handleSpokenText(spoken: String) {
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        val wakeName = (prefs.getString("wake_name", "Kairo") ?: "Kairo").trim()
        val apiKey = (prefs.getString("api_key", "") ?: "").trim()

        // STRICT COMMAND-ONLY: Only activate when explicitly called by wake word
        val calledByName = isWakeWordDetected(spoken.lowercase(), wakeName.lowercase())
        if (!calledByName) {
            restartListenerDelayed(300)
            return
        }

        if (apiKey.isEmpty()) {
            log("⚠️ API Key missing! Dashboard mein key paste karein.")
            speakAndResume("Boss, pehle dashboard mein API key save karein.")
            return
        }

        val credits = prefs.getInt("credits_balance", 500)
        if (credits <= 0) {
            log("⚠️ Credits khatam! Top-up section se recharge karein.")
            speakAndResume("Boss, aap ke Kairo credits khatam ho gaye hain. Top up karein.")
            return
        }

        isExecutingTask = true
        speechRecognizer?.destroy()
        log("👤 Tum: $spoken")

        var cleaned = spoken
        listOf(wakeName, "cairo", "kyro", "kero", "qairo", "کائرو", "کیرو", "hey", "hi", "hello", "suno").forEach {
            cleaned = cleaned.replace(it, "", ignoreCase = true)
        }
        if (cleaned.trim().length <= 2) {
            speakAndResume("Ji boss, Kairo hazir hai! Hukam karein.")
            return
        }

        executeAgentCommand(apiKey, wakeName, spoken)
    }

    fun triggerDirectCommand(command: String) {
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        val wakeName = (prefs.getString("wake_name", "Kairo") ?: "Kairo").trim()
        val apiKey = (prefs.getString("api_key", "") ?: "").trim()
        if (apiKey.isEmpty()) {
            log("⚠️ API Key set nahi hai!")
            return
        }
        val credits = prefs.getInt("credits_balance", 500)
        if (credits <= 0) {
            log("⚠️ Credits 0 hain! Top-up karein.")
            return
        }
        isExecutingTask = true
        speechRecognizer?.destroy()
        log("⌨️ Command: $command")
        executeAgentCommand(apiKey, wakeName, command)
    }

    private fun deductCredit(amount: Int = 1) {
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        val current = prefs.getInt("credits_balance", 500)
        val updated = (current - amount).coerceAtLeast(0)
        val totalUsed = prefs.getInt("credits_used", 0) + amount
        prefs.edit().putInt("credits_balance", updated).putInt("credits_used", totalUsed).apply()
        mainHandler.post { onCreditUsed?.invoke(updated) }
    }

    private fun executeAgentCommand(apiKey: String, wakeName: String, goal: String) {
        updateState("PROCESSING")
        val acc = AgentAccessibilityService.instance
        scope.launch {
            val brain = GeminiBrain(apiKey, wakeName)
            val history = mutableListOf<String>()
            var finalSpoken = ""

            for (step in 1..15) {
                if (!isRunning) break
                val shot = withContext(Dispatchers.IO) { acc?.captureScreenBase64() }
                val res = try {
                    withContext(Dispatchers.IO) { brain.askNextAction(goal, history, shot) }
                } catch (e: Exception) {
                    log("❌ API Error: ${e.message}")
                    finalSpoken = "API connection mein error aa gaya. Logs check karein."
                    break
                }

                deductCredit(1)
                val action = res.optString("action")
                val speakText = res.optString("speak")
                if (speakText.isNotEmpty()) finalSpoken = speakText

                if (action == "done") {
                    if (finalSpoken.isEmpty()) finalSpoken = res.optString("summary", "Kaam mukammal ho gaya.")
                    break
                }

                if (acc == null) {
                    finalSpoken = "Poora mobile control karne ke liye pehle Accessibility permission ON karein."
                    break
                }

                updateState("EXECUTING: $action")
                when (action) {
                    "open_app" -> {
                        val app = res.optString("app")
                        log("📲 Opening: $app")
                        acc.openAppByName(app)
                        history.add("open_app $app")
                    }
                    "tap" -> {
                        acc.performTap(res.optDouble("x").toFloat(), res.optDouble("y").toFloat())
                        history.add("tap")
                    }
                    "type" -> {
                        acc.performType(res.optString("text"))
                        history.add("type")
                    }
                    "swipe" -> {
                        acc.performSwipe(
                            res.optDouble("x1").toFloat(), res.optDouble("y1").toFloat(),
                            res.optDouble("x2").toFloat(), res.optDouble("y2").toFloat()
                        )
                        history.add("swipe")
                    }
                    "key" -> {
                        when (res.optString("key")) {
                            "home" -> acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                            "recents" -> acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                            else -> acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                        history.add("key")
                    }
                    "wait" -> delay((res.optDouble("seconds", 2.0) * 1000).toLong())
                }
                delay(1500)
            }
            speakAndResume(finalSpoken)
        }
    }

    private fun log(msg: String) {
        mainHandler.post { onLogUpdate?.invoke(msg) }
    }

    private fun updateState(state: String) {
        mainHandler.post { onStateChange?.invoke(state) }
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        muteRecognizerBeep(false)
        speechRecognizer?.destroy()
        tts.shutdown()
        scope.cancel()
        updateState("OFFLINE")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
