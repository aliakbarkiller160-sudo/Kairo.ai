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
import android.speech.tts.Voice
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
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeech: String? = null
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile private var isExecutingTask = false
    @Volatile private var cancelCurrentTask = false
    private var conversationActiveUntil = 0L // JARVIS 15-second continuous follow-up window

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
            val channel = NotificationChannel(channelId, "Kairo AI JARVIS Mode", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Kairo AI • JARVIS Mode Active 🛡️")
            .setContentText("'Kairo' bol kar baat karein ya hukam dein...")
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
        updateState("LISTENING (JARVIS)")
        log("🛡️ Kairo JARVIS Mode Active — 'Kairo' bol kar baat karein!")
        speakAndResume("Ji boss, Kairo online hai! Hukam karein.")
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
            val savedVoiceName = prefs.getString("kairo_locked_voice", null)
            val allVoices = try { tts?.voices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }

            // Permanent priority order for Kairo's single cute female signature voice
            val preferredVoiceOrder = listOf(
                "en-in-x-end-local",
                "en-in-x-ene-local",
                "en-us-x-sfg-local",
                "en-us-x-iol-local",
                "en-us-x-tpf-local",
                "en-gb-x-gba-local",
                "hi-in-x-hid-local"
            )

            var selectedVoice: Voice? = null

            // 1. Always load the exact previously locked signature voice first so voice NEVER changes
            if (!savedVoiceName.isNullOrBlank()) {
                selectedVoice = allVoices.firstOrNull { it.name == savedVoiceName }
            }

            // 2. Otherwise pick the #1 best available cute offline voice and lock it permanently
            if (selectedVoice == null) {
                for (targetName in preferredVoiceOrder) {
                    val match = allVoices.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                    if (match != null) {
                        selectedVoice = match
                        break
                    }
                }
            }

            // 3. Fallback to any offline female English voice
            if (selectedVoice == null) {
                selectedVoice = allVoices.firstOrNull { v ->
                    !v.isNetworkConnectionRequired &&
                    v.locale.language == "en" &&
                    (v.name.contains("female", true) || v.name.contains("sfg", true) || v.name.contains("end", true))
                }
            }

            if (selectedVoice != null) {
                tts?.language = selectedVoice.locale
                tts?.voice = selectedVoice
                prefs.edit().putString("kairo_locked_voice", selectedVoice.name).apply()
            } else {
                val res = tts?.setLanguage(Locale("en", "IN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
            }

            // Fixed cute, innocent & crystal-clear signature tone
            tts?.setPitch(1.18f)
            tts?.setSpeechRate(0.95f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    isExecutingTask = false
                    conversationActiveUntil = System.currentTimeMillis() + 15_000L
                    updateState("LISTENING (FOLLOW-UP)")
                    mainHandler.post { startListeningCycle() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    recoverFromTtsError(-1)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    recoverFromTtsError(errorCode)
                }
            })

            isTtsReady = true
            val lockedLabel = selectedVoice?.name ?: tts?.language?.toString() ?: "Default"
            log("🔊 Kairo Signature Voice Locked: $lockedLabel")

            val queued = pendingSpeech
            if (!queued.isNullOrBlank()) {
                pendingSpeech = null
                speakAndResume(queued)
            } else {
                startListeningCycle()
            }
        } else {
            log("❌ TTS Engine init failed (code=$status). Settings -> Text-to-Speech check karein.")
            startListeningCycle()
        }
    }

    private fun recoverFromTtsError(errorCode: Int) {
        log("⚠️ TTS Fallback (code=$errorCode) — Default US voice active.")
        try {
            getSharedPreferences("agent_prefs", MODE_PRIVATE).edit().remove("kairo_locked_voice").apply()
            tts?.setLanguage(Locale.US)
            tts?.setPitch(1.18f)
            tts?.setSpeechRate(0.95f)
        } catch (_: Exception) {}
        isExecutingTask = false
        updateState("LISTENING (JARVIS)")
        mainHandler.post { startListeningCycle() }
    }

    private fun ensureSpeakerAudible() {
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (curVol < (maxVol * 0.5).toInt()) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.85).toInt(), 0)
            }
        } catch (_: Exception) {}
    }

    private fun speakAndResume(text: String) {
        ensureSpeakerAudible()

        if (text.isBlank()) {
            isExecutingTask = false
            updateState("LISTENING (JARVIS)")
            startListeningCycle()
            return
        }

        // Queue speech until TTS engine finishes initializing so first greeting is never lost
        if (!isTtsReady) {
            pendingSpeech = text
            return
        }

        updateState("SPEAKING")
        log("🗣️ Kairo: $text")

        val cleanVoiceText = text
            .replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "")
            .replace("*", "")
            .trim()

        if (cleanVoiceText.isNotEmpty()) {
            // Re-lock signature pitch & rate before every reply so tone stays 100% consistent
            tts?.setPitch(1.18f)
            tts?.setSpeechRate(0.95f)
            // Standard null params prevents Infinix/XOS Bundle synthesis errors
            val res = tts?.speak(cleanVoiceText, TextToSpeech.QUEUE_FLUSH, null, "reply_id")
            if (res == TextToSpeech.ERROR) {
                log("❌ TTS speak() error — switching to US fallback.")
                recoverFromTtsError(-2)
            }
        } else {
            isExecutingTask = false
            updateState("LISTENING (JARVIS)")
            startListeningCycle()
        }
    }

    private fun startListeningCycle() {
        if (!isRunning || isExecutingTask) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("❌ Speech Recognition service missing! Google App enable karein.")
            return
        }
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ur-PK", "hi-IN", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
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
                    val delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900L else 450L
                    restartListenerDelayed(delayMs)
                }
            })
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            log("⚠️ Mic Error: ${e.message}")
            restartListenerDelayed(900)
        }
    }

    private fun restartListenerDelayed(ms: Long) {
        if (!isRunning || isExecutingTask) return
        mainHandler.postDelayed({ startListeningCycle() }, ms)
    }

    private fun isWakeWordDetected(spokenLower: String, wakeNameLower: String): Boolean {
        if (spokenLower.contains(wakeNameLower)) return true
        if (wakeNameLower == "kairo") {
            val variants = listOf(
                "kairo", "cairo", "kyro", "kero", "kiro", "qairo",
                "caro", "kairon", "kairow", "kairoh", "hero", "karo",
                "کائرو", "کیرو", "کairo"
            )
            return variants.any { spokenLower.contains(it) }
        }
        return false
    }

    private fun handleSpokenText(spoken: String) {
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        val wakeName = (prefs.getString("wake_name", "Kairo") ?: "Kairo").trim()
        val apiKey = (prefs.getString("api_key", "") ?: "").trim()
        val lower = spoken.lowercase()

        // Emergency Voice Stop
        if (lower == "stop" || lower == "ruk jao" || lower == "bas karo" || lower == "kairo stop") {
            cancelCurrentTask = true
            isExecutingTask = false
            conversationActiveUntil = 0L
            log("🛑 Task stopped by voice.")
            speakAndResume("Ji boss, ruk gaya hoon.")
            return
        }

        val calledByName = isWakeWordDetected(lower, wakeName.lowercase())
        val inFollowUpWindow = System.currentTimeMillis() < conversationActiveUntil

        if (!calledByName && !inFollowUpWindow) {
            log("👂 Mic sun raha hai (say '$wakeName'): $spoken")
            restartListenerDelayed(250)
            return
        }

        if (apiKey.isEmpty()) {
            log("⚠️ API Key missing! Dashboard mein key paste karein.")
            speakAndResume("Ji boss, pehle dashboard mein API key save kar dein.")
            return
        }

        val credits = prefs.getInt("credits_balance", 500)
        if (credits <= 0) {
            log("⚠️ Credits khatam! Top-up section se recharge karein.")
            speakAndResume("Boss, aap ke Kairo credits khatam ho gaye hain.")
            return
        }

        isExecutingTask = true
        cancelCurrentTask = false
        speechRecognizer?.destroy()
        log("👤 Tum: $spoken")

        var cleaned = spoken
        listOf(
            wakeName, "cairo", "kyro", "kero", "kiro", "qairo",
            "caro", "kairon", "kairow", "kairoh", "کائرو", "کیرو",
            "hey", "hi", "hello", "suno", "ji"
        ).forEach {
            cleaned = cleaned.replace(it, "", ignoreCase = true)
        }
        if (cleaned.trim().length <= 2) {
            speakAndResume("Ji boss! Kairo hazir hai, hukam karein.")
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
        cancelCurrentTask = false
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

    // Fast chat detection: skips heavy screenshot if user is just talking/asking a question
    private fun needsScreenControl(goal: String): Boolean {
        val g = goal.lowercase()
        val deviceKeywords = listOf(
            "open", "kholo", "khol", "chalao", "play", "search", "dhundo",
            "whatsapp", "youtube", "insta", "instagram", "facebook", "tiktok",
            "chrome", "settings", "camera", "maps", "message", "call", "phone",
            "scroll", "swipe", "tap", "click", "type", "likho", "send", "bhejo",
            "back", "home", "volume", "wifi", "bluetooth", "screen", "app"
        )
        return deviceKeywords.any { g.contains(it) }
    }

    private fun executeAgentCommand(apiKey: String, wakeName: String, goal: String) {
        updateState("THINKING...")
        val acc = AgentAccessibilityService.instance
        val useScreen = needsScreenControl(goal)

        scope.launch {
            val brain = GeminiBrain(apiKey, wakeName)
            val history = mutableListOf<String>()
            var finalSpoken = ""
            var lastOpenedApp = ""

            val maxSteps = if (useScreen) 8 else 1

            for (step in 1..maxSteps) {
                if (!isRunning || cancelCurrentTask) break

                val shot = if (useScreen) {
                    withContext(Dispatchers.IO) { acc?.captureScreenBase64() }
                } else {
                    null
                }

                val res = try {
                    withContext(Dispatchers.IO) { brain.askNextAction(goal, history, shot) }
                } catch (e: Exception) {
                    log("❌ API Error: ${e.message}")
                    finalSpoken = "Maaf kijiye boss, server abhi thoda busy hai."
                    break
                }

                deductCredit(1)
                val action = res.optString("action")
                val speakText = res.optString("speak")
                if (speakText.isNotEmpty()) finalSpoken = speakText

                if (action == "done") {
                    if (finalSpoken.isEmpty()) finalSpoken = res.optString("summary", "Ji boss, kaam ho gaya.")
                    break
                }

                if (acc == null) {
                    finalSpoken = "Boss, pehle Accessibility permission on kar dein."
                    break
                }

                updateState("EXECUTING: $action")
                when (action) {
                    "open_app" -> {
                        val app = res.optString("app").lowercase().trim()
                        // Anti-loop lock: never open the exact same app twice
                        if (app == lastOpenedApp) {
                            if (finalSpoken.isEmpty()) finalSpoken = "Ji boss, $app khol diya hai."
                            break
                        }
                        lastOpenedApp = app
                        log("📲 Opening: $app")
                        acc.openAppByName(app)
                        history.add("Opened app '$app' successfully. If the user only asked to open this app, return action 'done' immediately.")

                        val gLower = goal.lowercase()
                        val isSimpleOpen = !listOf("aur", "and", "par", "pe", "search", "play", "chalao", "likho", "send", "bhejo", "msg", "message").any { gLower.contains(it) }
                        if (isSimpleOpen) {
                            if (finalSpoken.isEmpty()) finalSpoken = "Ji boss, $app khol diya hai."
                            break
                        }
                    }
                    "tap" -> {
                        acc.performTap(res.optDouble("x").toFloat(), res.optDouble("y").toFloat())
                        history.add("Tapped at (${res.optInt("x")}, ${res.optInt("y")})")
                    }
                    "type" -> {
                        acc.performType(res.optString("text"))
                        history.add("Typed '${res.optString("text")}'")
                    }
                    "swipe" -> {
                        acc.performSwipe(
                            res.optDouble("x1").toFloat(), res.optDouble("y1").toFloat(),
                            res.optDouble("x2").toFloat(), res.optDouble("y2").toFloat()
                        )
                        history.add("Swiped screen")
                    }
                    "key" -> {
                        when (res.optString("key")) {
                            "home" -> acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                            "recents" -> acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                            else -> acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                        history.add("Pressed ${res.optString("key")}")
                    }
                    "wait" -> delay((res.optDouble("seconds", 1.0) * 1000).toLong())
                }
                delay(1100)
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
        speechRecognizer?.destroy()
        tts?.shutdown()
        scope.cancel()
        updateState("OFFLINE")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
