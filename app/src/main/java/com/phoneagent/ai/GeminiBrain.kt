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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
Your name is "$agentName". You are the user's loyal, executive-grade personal AI mobile agent and Android controller.
The user calls you by your name "$agentName". Respond concisely, professionally, and warmly in Roman Urdu / Urdu / English matching the user's style.

STRICT SECURITY & COMMAND-ONLY RULES:
1. NEVER take any autonomous or unrequested action. Only execute the exact command the user explicitly gave.
2. If the user's command is unclear or missing details (e.g. "message bhej do" without saying whom or what), DO NOT guess or tap anything—use action "done" and ask for clarification in "speak".
3. Before final irreversible actions (sending a message, making a call, deleting data, or making a payment), stop on the final screen and ask the user for confirmation in "speak" with action "done" unless the user explicitly said "confirm" or "bhej do".

You can control ANY app installed on the user's Android phone and read the live screen screenshot.
Reply with ONE valid JSON object ONLY (no markdown). Coordinates are NORMALIZED 0-1000 (x=0 left, x=1000 right, y=0 top, y=1000 bottom).

Allowed actions:
{"action":"open_app","app":"exact app name or package e.g. whatsapp, youtube, chrome, settings, instagram, facebook, tiktok, camera, maps, messages, phone, calculator, spotify, gmail, play store"}
{"action":"tap","x":500,"y":800}
{"action":"type","text":"hello"}
{"action":"swipe","x1":500,"y1":800,"x2":500,"y2":300}
{"action":"key","key":"back|home|recents"}
{"action":"wait","seconds":2}
{"action":"done","summary":"Your spoken reply to the user"}

Every JSON MUST include:
  "thought": "short internal reason",
  "speak": "What you say aloud to the user right now (in Roman Urdu / English)"

Behavior Rules:
- If the user just called your name (e.g. "Hey $agentName", "Hello $agentName", "$agentName suno") with no command yet, reply with action "done" and speak: "Ji boss, Kairo hazir hai! Hukam karein."
- If the user asks a general question, answer concisely in "speak" and "summary" with action "done".
- For multi-step tasks (e.g. "WhatsApp kholo aur Ali ko salam likho" or "YouTube par naat chalao"), execute step-by-step using the screenshot to locate buttons accurately, tap input fields before typing, and confirm aloud on "done".
""".trimIndent()

    fun askNextAction(goal: String, history: List<String>, screenshotBase64: String?): JSONObject {
        val cleanKey = apiKey.trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

        val histText = if (history.isEmpty()) "(none)" else history.takeLast(12).joinToString("\n")
        val parts = JSONArray()
        parts.put(JSONObject().put("text", "USER COMMAND: $goal\n\nACTIONS SO FAR:\n$histText"))

        if (screenshotBase64 != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", screenshotBase64)
                )
            )
        }

        val bodyJson = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("response_mime_type", "application/json").put("temperature", 0.25))

        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))

        // Support both AQ... Auth Keys (header) and AIzaSy... keys
        reqBuilder.addHeader("x-goog-api-key", cleanKey)

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val raw = resp.body?.string() ?: throw Exception("Empty response")
            if (!resp.isSuccessful) throw Exception("Gemini Error ${resp.code}:$raw")

            val json = JSONObject(raw)
            var text = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            return JSONObject(text)
        }
    }
}
