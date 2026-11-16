package com.phoneagent.ai

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Intent
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
        var onLogUpdate: ((String) -> Unit)? = null
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isExecutingTask = false
    private var awakeUntilMs = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        tts = TextToSpeech(this, this)
        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        val channelId = "kairo_voice_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Kairo AI Voice Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Kairo AI Sun Raha Hai 🎙️")
            .setContentText("'Kairo' bol kar apne agent ko bulayein...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1001, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("🎙️ Kairo ab background mein tumhari awaaz sun raha hai...")
        mainHandler.postDelayed({ startListeningCycle() }, 800)
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts.setLanguage(Locale("ur", "PK"))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale("hi", "IN")
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    awakeUntilMs = System.currentTimeMillis() + 15000L
                    isExecutingTask = false
                    mainHandler.post { startListeningCycle() }
                }
                override fun onError(utteranceId: String?) {
                    isExecutingTask = false
                    mainHandler.post { startListeningCycle() }
                }
            })
        }
    }

    private fun speakAndResume(text: String) {
        if (text.isBlank()) {
            isExecutingTask = false
            startListeningCycle()
            return
        }
        log("🗣️ Kairo: $text")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply_id")
    }

    private fun startListeningCycle() {
        if (!isRunning || isExecutingTask) return
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
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
                        restartListenerDelayed(300)
                    }
                }

                override fun onError(error: Int) {
                    restartListenerDelayed(500)
                }
            })
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            restartListenerDelayed(1000)
        }
    }

    private fun restartListenerDelayed(ms: Long) {
        if (!isRunning || isExecutingTask) return
        mainHandler.postDelayed({ startListeningCycle() }, ms)
    }

    private fun isWakeWordDetected(spokenLower: String, wakeNameLower: String): Boolean {
        if (spokenLower.contains(wakeNameLower)) return true
        if (wakeNameLower == "kairo") {
            val variants = listOf("kairo", "cairo", "kyro", "kero", "qairo", "کائرو", "کیرو")
            return variants.any { spokenLower.contains(it) }
        }
        return false
    }

    private fun handleSpokenText(spoken: String) {
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        val wakeName = (prefs.getString("wake_name", "Kairo") ?: "Kairo").trim()
        val apiKey = (prefs.getString("api_key", "") ?: "").trim()

        val now = System.currentTimeMillis()
        val inFollowUpWindow = now < awakeUntilMs
        val calledByName = isWakeWordDetected(spoken.lowercase(), wakeName.lowercase())

        if (!calledByName && !inFollowUpWindow) {
            restartListenerDelayed(200)
            return
        }

        if (apiKey.isEmpty()) {
            log("⚠️ API Key set nahi hai!")
            restartListenerDelayed(1000)
            return
        }

        isExecutingTask = true
        log("👤 Tum: $spoken")

        var cleaned = spoken
        listOf(wakeName, "cairo", "kyro", "kero", "qairo", "کائرو", "کیرو", "hey", "hi", "suno").forEach {
            cleaned = cleaned.replace(it, "", ignoreCase = true)
        }
        if (cleaned.trim().length <= 2) {
            speakAndResume("Ji boss, Kairo hazir hai! Hukam karein.")
            return
        }

        executeAgentCommand(apiKey, wakeName, spoken)
    }

    private fun executeAgentCommand(apiKey: String, wakeName: String, goal: String) {
        val acc = AgentAccessibilityService.instance
        scope.launch {
            val brain = GeminiBrain(apiKey, wakeName)
            val history = mutableListOf<String>()
            var finalSpoken = ""

            for (step in 1..12) {
                if (!isRunning) break
                val shot = withContext(Dispatchers.IO) { acc?.captureScreenBase64() }
                val res = try {
                    withContext(Dispatchers.IO) { brain.askNextAction(goal, history, shot) }
                } catch (e: Exception) {
                    log("❌ Error: ${e.message}")
                    finalSpoken = "Maaf kijiyega, network ya API mein masla aa gaya."
                    break
                }

                val action = res.optString("action")
                val speakText = res.optString("speak")
                if (speakText.isNotEmpty()) finalSpoken = speakText

                if (action == "done") {
                    if (finalSpoken.isEmpty()) finalSpoken = res.optString("summary", "Ho gaya.")
                    break
                }

                if (acc == null) {
                    finalSpoken = "Mobile control karne ke liye pehle Accessibility permission on karein."
                    break
                }

                when (action) {
                    "open_app" -> {
                        val app = res.optString("app")
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
                delay(1600)
            }
            speakAndResume(finalSpoken)
        }
    }

    private fun log(msg: String) {
        mainHandler.post { onLogUpdate?.invoke(msg) }
    }

    override fun onDestroy() {
        isRunning = false
        speechRecognizer?.destroy()
        tts.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
