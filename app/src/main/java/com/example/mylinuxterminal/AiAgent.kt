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

    suspend fun runAgentStep(apiKey: String, history: JSONArray): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Error: API Key is missing."

        // 👇 پرامپت سیستمی جدید (نصب خودکار و ادامه کار)
        val systemPrompt = """
            You are an autonomous AI Linux Agent inside an Android app running Alpine Linux.
            You will be given a task. You must execute it step by step.
            At each step, you MUST output ONLY the exact bash/linux command to execute.
            NO markdown, NO explanations, NO backticks.
            
            CRITICAL RULES:
            1. AUTO-INSTALL: If a command fails with "not found" (e.g., python3: not found), you MUST immediately install it using `apk add <package_name>` (e.g., `apk add python3`). NEVER search Android system directories like /system/bin.
            2. PYTHON PACKAGES: If a python module is missing (e.g., ModuleNotFoundError: No module named 'requests'), install it using `pip install --break-system-packages <module_name>`.
            3. CONTINUATION: If you receive a message saying "System Limit Reached", analyze your previous actions and output the NEXT command to continue the task. Do not start over.
            4. COMPLETION: When the entire task is successfully completed, reply ONLY with the exact text: TASK_COMPLETE
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            for (i in 0 until history.length()) {
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
                if (!response.isSuccessful) return@withContext "Error: ${response.code}"
                val responseData = response.body?.string() ?: return@withContext null
                val jsonObject = JSONObject(responseData)
                return@withContext jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
        } catch (e: IOException) {
            return@withContext "Network Error: ${e.message}"
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
