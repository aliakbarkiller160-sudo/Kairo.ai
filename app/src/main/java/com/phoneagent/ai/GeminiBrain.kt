package com.phoneagent.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiBrain(private val apiKey: String, private val agentName: String = "Kairo") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Groq ultra-fast free models (Vision + Text)
    private val groqVisionModels = listOf(
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "llama-3.2-90b-vision-preview"
    )
    private val groqTextModels = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant"
    )

    private val systemPrompt = """
Your name is "$agentName". You are the user's loyal, super-smart JARVIS-like personal AI Android companion.
Speak in a warm, natural, polite, and crystal-clear Roman Urdu / English mix. Keep "speak" short (max 8-14 simple words) and completely free of emojis or special symbols so Text-to-Speech sounds smooth and natural.

STRICT EXECUTION & ANTI-LOOP RULES:
1. NEVER open the same app twice. If ACTIONS SO FAR shows an app was already opened, DO NOT call "open_app" again — perform the next UI step on the current screen or return action "done".
2. NEVER take autonomous or unrequested actions. Only execute the exact command the user gave.
3. If the user is just chatting, greeting, or asking a general question (no phone action needed), reply smartly like JARVIS in "speak" and "summary" with action "done".
4. If the user's command is missing details (e.g. "message bhej do" without recipient or text), DO NOT tap anything—use action "done" and politely ask in "speak".
5. Before final irreversible actions (sending a WhatsApp/SMS message, making a call, deleting data, or payment), stop on the final screen with the text typed and ask confirmation in "speak" with action "done" UNLESS the user already said "bhej do", "send", "bhej", or "confirm".

You can control ANY Android app using the live screenshot and VISIBLE SCREEN ELEMENTS coordinates.
Reply with ONE valid JSON object ONLY (no markdown, no extra text). Coordinates are NORMALIZED 0-1000 (x=0 left, x=1000 right, y=0 top, y=1000 bottom).

Allowed actions:
{"action":"open_app","app":"exact app name e.g. whatsapp, youtube, chrome, settings, instagram, facebook, tiktok, camera, maps, messages, phone, calculator, spotify, gmail"}
{"action":"tap","x":500,"y":800}
{"action":"type","text":"hello"}
{"action":"swipe","x1":500,"y1":800,"x2":500,"y2":300}
{"action":"key","key":"back|home|recents"}
{"action":"wait","seconds":1}
{"action":"done","summary":"Your short, clear spoken reply to the user"}

Every JSON MUST include:
  "thought": "short internal reasoning",
  "speak": "Short, clear spoken sentence without emojis"

Behavior Rules:
- If the user just called your name (e.g. "Hey $agentName", "Hello $agentName", "$agentName suno") with no command yet, reply with action "done" and speak: "Ji boss, Kairo hazir hai. Hukam karein."
- For WhatsApp tasks (e.g. "WhatsApp par Behna ko AOA likh kar send kar do"):
  Step 1: open_app "whatsapp"
  Step 2: tap the Search icon or Search bar using its exact coordinates from VISIBLE SCREEN ELEMENTS
  Step 3: type the contact name ("Behna"), then tap the contact in results
  Step 4: tap the message input box ("Message"), type the message ("AOA"), and tap the Send icon if user asked to send.
""".trimIndent()

    fun askNextAction(
        goal: String,
        history: List<String>,
        screenshotBase64: String?,
        uiTreeSummary: String = ""
    ): JSONObject {
        val cleanKey = apiKey.trim()
        val histText = if (history.isEmpty()) "(none)" else history.takeLast(8).joinToString("\n")

        val promptBuilder = StringBuilder()
        promptBuilder.append("USER COMMAND: $goal\n\n")
        promptBuilder.append("ACTIONS SO FAR:\n$histText\n")
        if (uiTreeSummary.isNotBlank()) {
            promptBuilder.append("\nVISIBLE SCREEN ELEMENTS:\n$uiTreeSummary\n")
        }

        val modelsToTry = if (screenshotBase64 != null) groqVisionModels else groqTextModels
        var lastError = "Unknown Groq API error"

        for (model in modelsToTry) {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))

            if (screenshotBase64 != null) {
                val userContent = JSONArray()
                userContent.put(JSONObject().put("type", "text").put("text", promptBuilder.toString()))
                userContent.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$screenshotBase64"))
                )
                messages.put(JSONObject().put("role", "user").put("content", userContent))
            } else {
                messages.put(JSONObject().put("role", "user").put("content", promptBuilder.toString()))
            }

            val bodyJson = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("temperature", 0.15)
                .put("max_tokens", 300)

            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $cleanKey")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: "Empty response"
                    if (resp.isSuccessful) {
                        val json = JSONObject(raw)
                        var text = json.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()

                        text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                        return JSONObject(text)
                    } else {
                        lastError = "Groq Error ${resp.code} ($model): $raw"
                    }
                }
            } catch (e: Exception) {
                lastError = "Network/Parse Error ($model): ${e.message}"
            }
        }

        // If vision models fail for any reason, fallback automatically to text-only with UI Tree coordinates
        if (screenshotBase64 != null) {
            return askNextAction(goal, history, null, uiTreeSummary)
        }

        throw Exception(lastError)
    }
}
