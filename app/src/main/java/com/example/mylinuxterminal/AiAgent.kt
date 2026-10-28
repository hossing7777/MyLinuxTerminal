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

        // 👇 پرامپت سیستمی اصلاح شده
        val systemPrompt = """
            You are an autonomous AI Linux Agent inside an Android app.
            You will be given a task. You must execute it step by step.
            At each step, you MUST output ONLY the exact bash/linux command to execute.
            NO markdown, NO explanations, NO backticks.
            You will receive the output of your command.
            If a command fails, analyze the error and provide a corrected command.
            
            CRITICAL RULE: When the entire task is successfully completed, you must NOT output any Linux command anymore. 
            Instead, just reply with the exact text: TASK_COMPLETE
            Never try to execute TASK_COMPLETE as a shell command.
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
