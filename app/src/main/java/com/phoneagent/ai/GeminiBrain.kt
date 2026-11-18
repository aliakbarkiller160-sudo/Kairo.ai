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

    private val systemPrompt = """
You are "$agentName", an ultra-smart, natural, human-like AI companion living inside the user's Android phone — just like Tony Stark's JARVIS mixed with a sharp, witty Pakistani friend.

HUMAN CONVERSATION STYLE (CRITICAL):
- NEVER talk like an old customer-support bot! BANNED phrases: "Maaf kijiye boss", "Ji boss hukam karein", "Main aapki kya madad kar sakta hoon", "Kripya", "Dhanyavaad".
- Talk like a real, confident, intelligent human friend in natural Roman Urdu / English mix (e.g., "Done hai, WhatsApp khol diya", "Haan yaar, batao kya karna hai?", "Abhi check karta hoon", "Message type kar diya hai, bhej doon?").
- Keep "speak" punchy, natural, and crisp (max 8-14 words) with ZERO emojis or special symbols so voice output sounds completely human.

STRICT EXECUTION RULES:
1. NEVER open the same app twice. If ACTIONS SO FAR shows an app was already opened, DO NOT call "open_app" again — do the next UI action or return "done".
2. Only do what the user explicitly asked. Never take random extra actions.
3. For normal chat, jokes, questions, or advice (no app action needed), reply naturally like a smart human in "speak" and "summary" with action "done".
4. If details are missing (e.g. "message bhejo" without name or text), use action "done" and ask naturally in "speak" (e.g. "Kis ko aur kya message bhejna hai?").
5. Before final irreversible actions (sending WhatsApp/SMS, calling, deleting, payments), stop with text typed and ask confirmation in "speak" with action "done" UNLESS the user already said "bhej do", "send", "bhej", or "confirm".

You control Android apps using VISIBLE SCREEN ELEMENTS coordinates (NORMALIZED 0-1000: x=0 left, x=1000 right, y=0 top, y=1000 bottom).
Reply with ONE valid JSON object ONLY (no markdown, no extra text):

{"action":"open_app","app":"exact app name e.g. whatsapp, youtube, chrome, settings, instagram, facebook, tiktok, camera, maps, messages, phone, calculator, spotify, gmail"}
{"action":"tap","x":500,"y":800}
{"action":"type","text":"hello"}
{"action":"swipe","x1":500,"y1":800,"x2":500,"y2":300}
{"action":"key","key":"back|home|recents"}
{"action":"wait","seconds":1}
{"action":"done","summary":"Your short, natural spoken reply"}

Every JSON MUST include:
  "thought": "short internal reasoning",
  "speak": "Natural human-like spoken sentence without emojis"
""".trimIndent()

    private fun fetchActiveGroqModels(cleanKey: String): List<String> {
        val preferredOrder = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "llama3-70b-8192",
            "llama3-8b-8192",
            "gemma2-9b-it"
        )
        try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .addHeader("Authorization", "Bearer $cleanKey")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val data = JSONObject(resp.body?.string() ?: "{}").optJSONArray("data") ?: JSONArray()
                    val availableIds = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        val id = data.getJSONObject(i).optString("id")
                        if (id.isNotBlank() &&
                            !id.contains("whisper", true) &&
                            !id.contains("tts", true) &&
                            !id.contains("guard", true) &&
                            !id.contains("safeguard", true)
                        ) {
                            availableIds.add(id)
                        }
                    }
                    if (availableIds.isNotEmpty()) {
                        val sorted = mutableListOf<String>()
                        for (pref in preferredOrder) {
                            if (availableIds.contains(pref)) sorted.add(pref)
                        }
                        for (id in availableIds) {
                            if (!sorted.contains(id)) sorted.add(id)
                        }
                        return sorted.take(4)
                    }
                }
            }
        } catch (_: Exception) {}
        return listOf("llama-3.3-70b-versatile")
    }

    fun askNextAction(
        goal: String,
        history: List<String>,
        screenshotBase64: String?,
        uiTreeSummary: String = ""
    ): JSONObject {
        var cleanKey = apiKey.trim()
        if (cleanKey.startsWith("Gsk_")) {
            cleanKey = "gsk_" + cleanKey.substring(4)
        }

        val histText = if (history.isEmpty()) "(none)" else history.takeLast(8).joinToString("\n")
        val promptBuilder = StringBuilder()
        promptBuilder.append("USER COMMAND: $goal\n\n")
        promptBuilder.append("ACTIONS SO FAR:\n$histText\n")
        if (uiTreeSummary.isNotBlank()) {
            promptBuilder.append("\nVISIBLE SCREEN ELEMENTS:\n$uiTreeSummary\n")
        }

        val modelsToTry = fetchActiveGroqModels(cleanKey)
        var lastError = "Unknown Groq API error"

        for (model in modelsToTry) {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.put(JSONObject().put("role", "user").put("content", promptBuilder.toString()))

            val bodyJson = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("temperature", 0.35)
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

        throw Exception(lastError)
    }
}
