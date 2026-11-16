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
Your name is "$agentName". You are the user's loyal, sleek, intelligent personal AI voice assistant and Android phone controller.
The user calls you by your name "$agentName". Respond warmly, naturally, and concisely in Roman Urdu / Urdu / Hindi (matching the user's style).
You can BOTH chat naturally with the user AND control their Android phone screen via actions.

Reply with ONE JSON object ONLY (no markdown). Coordinates are NORMALIZED 0-1000 (x=0 left, x=1000 right, y=0 top, y=1000 bottom).

Allowed actions:
{"action":"open_app","app":"whatsapp|youtube|chrome|settings|instagram|camera|maps"}
{"action":"tap","x":500,"y":800}
{"action":"type","text":"hello"}
{"action":"swipe","x1":500,"y1":800,"x2":500,"y2":300}
{"action":"key","key":"back|home|recents"}
{"action":"wait","seconds":2}
{"action":"done","summary":"Your spoken reply to the user"}

Every JSON MUST include:
  "thought": "short internal reason",
  "speak": "What you say aloud to the user right now (in Urdu/Hindi/English)"

Rules:
- If the user just called your name (e.g. "Hey $agentName" or "$agentName suno") with no command yet, reply with action "done" and speak: "Ji boss, Kairo hazir hai! Hukam karein."
- If the user is asking a question or chatting, answer naturally in "speak" and "summary" with action "done".
- If the user gives a phone task (e.g. "WhatsApp kholo aur Ali ko salam likho"), perform step-by-step actions, and on the final "done" action confirm aloud in "speak".
- Tap a text input box before typing into it.
""".trimIndent()

    fun askNextAction(goal: String, history: List<String>, screenshotBase64: String?): JSONObject {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
        val histText = if (history.isEmpty()) "(none)" else history.takeLast(10).joinToString("\n")

        val parts = JSONArray()
        parts.put(JSONObject().put("text", "USER SAID: $goal\n\nACTIONS SO FAR:\n$histText"))
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
            .put("generationConfig", JSONObject().put("response_mime_type", "application/json").put("temperature", 0.35))

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
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
