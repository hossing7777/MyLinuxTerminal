package com.example.mylinuxterminal

import kotlinx.coroutines.Dispatchers
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

    suspend fun runAgentStep(apiKey: String, history: JSONArray, memory: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Error: API Key is missing."

        val systemPrompt = """
            You are an autonomous AI Linux Agent inside an Android app running Alpine Linux.
            
            MEMORY OF PREVIOUS SUCCESSFUL COMMANDS:
            $memory
            
            CRITICAL RULES:
            1. RAW COMMANDS ONLY: You MUST output ONLY the exact raw bash/linux command. DO NOT output JSON. DO NOT output descriptions. DO NOT output markdown.
            2. PROGRESS TAG: Before EVERY command you output, prepend your estimated completion percentage like this: [PROGRESS: X%] (e.g., [PROGRESS: 25%] mkdir test).
            3. AUTO-INSTALL: If a command fails with "not found", install it using `apk add <package_name>`. If slow, use a mirror: `sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories && apk update`.
            4. PYTHON PACKAGES: If missing, use `pip install --break-system-packages <module>` or use Tsinghua mirror.
            5. NOEXEC MOUNT (VERY IMPORTANT): The /sdcard directory is mounted as "noexec". You CANNOT execute .sh scripts or binaries directly from /sdcard using `./script.sh`. You MUST run them using `sh script.sh` or `bash script.sh`.
            6. TIMEOUT: If you receive "Command Timed Out", it means the command took >2 mins. Try a faster approach.
            7. COMPLETION: When done, reply ONLY with: TASK_COMPLETE
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            val startIndex = maxOf(0, history.length() - 15)
            for (i in startIndex until history.length()) {
                put(history.get(i))
            }
        }

        val jsonBody = JSONObject().apply {
            put("model", "nvidia/nemotron-3-ultra-550b-a55b:free")
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
                if (!response.isSuccessful) return@withContext "HTTP_ERROR_${response.code}"
                val responseData = response.body?.string() ?: return@withContext null
                val jsonObject = JSONObject(responseData)
                return@withContext jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
        } catch (e: IOException) {
            return@withContext "NETWORK_ERROR"
        }
    }

    suspend fun testConnection(apiKey: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "کلید خالی است."
        val jsonBody = JSONObject().apply {
            put("model", "nvidia/nemotron-3-ultra-550b-a55b:free")
            put("messages", JSONArray().apply { put(JSONObject().put("role", "user").put("content", "Say OK")) })
            put("max_tokens", 10)
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint).addHeader("Authorization", "Bearer $apiKey").post(requestBody).build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) return@withContext "✅ اتصال با موفقیت برقرار شد."
                else return@withContext "❌ خطای سرور: ${response.code}"
            }
        } catch (e: IOException) {
            return@withContext "❌ خطای شبکه: ${e.message}"
        }
    }
}
