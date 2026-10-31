package com.example.mylinuxterminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiAgent {
    private val endpoint = "https://api.hormouz.net/v1/chat/completions"
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getSystemPrompt(memory: String): String {
        return """
            You are an autonomous AI Linux Agent inside an Android app running Alpine Linux.
            
            MEMORY OF PREVIOUS SUCCESSFUL COMMANDS:
            $memory
            
            CRITICAL RULES:
            1. RAW COMMANDS ONLY: You MUST output ONLY the exact raw bash/linux command. DO NOT output JSON. DO NOT output descriptions. DO NOT output markdown.
            2. PROGRESS TAG: Before EVERY command you output, prepend your estimated completion percentage like this: [PROGRESS: X%] (e.g., [PROGRESS: 25%] mkdir test).
            3. ALPINE PACKAGES (VERY IMPORTANT): If you need a Python library (like requests or pillow), ALWAYS try to install it using Alpine's package manager first: `apk add py3-requests` or `apk add py3-pillow`. Only use pip if the package is not available in apk.
            4. NETWORKING TOOLS: If you need `curl` or `wget`, you MUST install them first using `apk add curl` or `apk add wget`.
            5. NOEXEC MOUNT: The /sdcard directory is mounted as "noexec". You CANNOT execute .sh scripts directly using `./script.sh`. You MUST run them using `sh script.sh`.
            6. TIMEOUT: If you receive "Command Timed Out", it means the command took >2 mins. Try a faster approach.
            6. LOCAL PACKAGE CACHE (IMPORTANT): A persistent package cache exists at /sdcard/Download/Local_AI_Models/Library_Cache.
               To save bandwidth and time:
               - For APK packages use: `apk add --cache-dir=/sdcard/Download/Local_AI_Models/Library_Cache/apk <package>`
               - For Python packages use: `pip install --cache-dir=/sdcard/Download/Local_AI_Models/Library_Cache/pip --break-system-packages <package>`
               This way, the system checks the cache first and only downloads if missing.
            7. COMPLETION: When done, reply ONLY with: TASK_COMPLETE

            HARDWARE CONTROLS (CRITICAL RULE - NEVER BREAK THIS):
            The environment is a PRoot Alpine Linux WITHOUT Termux API. 
            DO NOT USE `termux-brightness`, `termux-vibrate`, `termux-volume`, or any termux-api commands. They will fail.
            If the user asks to change device settings (brightness, volume, vibrate, wifi, bluetooth, speak), 
            you MUST output ONLY the exact hardware tag. DO NOT output any Python code. DO NOT output bash. DO NOT explain.
            Format: [HARDWARE] action arg [/HARDWARE]
            Available actions:
            - brightness <1-255> (e.g. [HARDWARE] brightness 30 [/HARDWARE])
            - volume <0-100> (e.g. [HARDWARE] volume 50 [/HARDWARE])
            - vibrate <milliseconds> (e.g. [HARDWARE] vibrate 1000 [/HARDWARE])
            - wifi_on / wifi_off
            - bt_on / bt_off
            - speak <text> (e.g. [HARDWARE] speak Hello World [/HARDWARE])


            BROWSER AUTOMATION AGENT (SUPER IMPORTANT):        """.trimIndent()
    }

    suspend fun runAgentStep(apiKey: String, modelName: String, history: JSONArray, memory: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Error: API Key is missing."

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", getSystemPrompt(memory)))
            val startIndex = maxOf(0, history.length() - 15)
            for (i in startIndex until history.length()) {
                put(history.get(i))
            }
        }

        val jsonBody = JSONObject().apply {
            put("model", modelName)
            put("messages", messages)
            put("temperature", 0.1)
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("User-Agent", "Mozilla/5.0")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 429 || response.code >= 500) return@withContext "RETRY_ERROR"
                if (!response.isSuccessful) return@withContext "HTTP_ERROR_${response.code}"
                val responseData = response.body?.string() ?: return@withContext null
                val jsonObject = JSONObject(responseData)
                return@withContext jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
        } catch (e: IOException) {
            return@withContext "RETRY_ERROR"
        }
    }

    suspend fun runParallelAgentStep(apiKey: String, models: List<String>, history: JSONArray, memory: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Error: API Key is missing."

        coroutineScope {
            val deferredResponses = models.map { modelName ->
                async(Dispatchers.IO) {
                    runAgentStep(apiKey, modelName, history, memory)
                }
            }
            
            val results: List<String?> = deferredResponses.awaitAll()
            
            val validResponse = results.firstOrNull { response ->
                response != null && response != "RETRY_ERROR" && !response.startsWith("HTTP_ERROR") && response != "TASK_COMPLETE"
            }
            
            if (validResponse == null && results.any { it == "TASK_COMPLETE" }) {
                return@coroutineScope "TASK_COMPLETE"
            }
            
            return@coroutineScope validResponse ?: "RETRY_ERROR"
        }
    }

    suspend fun testConnection(apiKey: String, modelName: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "API Key is empty."
        val jsonBody = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply { put(JSONObject().put("role", "user").put("content", "Say OK")) })
            put("max_tokens", 10)
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint).addHeader("Authorization", "Bearer $apiKey").post(requestBody).build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) return@withContext "✅ Connection successful."
                else return@withContext "❌ Server error: ${response.code}"
            }
        } catch (e: IOException) {
            return@withContext "❌ Network error: ${e.message}"
        }
    }
}
