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
    
    // 👇 افزایش زمان انتظار (Timeout) به ۶۰ ثانیه برای جلوگیری از خطای timeout
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(apiKey: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "کلید خالی است."

        val jsonBody = JSONObject().apply {
            put("model", "nvidia/nemotron-3-ultra-550b-a55b:free")
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", "Say OK"))
            })
            put("max_tokens", 10)
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
                if (response.isSuccessful) {
                    return@withContext "✅ اتصال با موفقیت برقرار شد."
                } else {
                    return@withContext "❌ خطای سرور: ${response.code} - ${response.message}"
                }
            }
        } catch (e: IOException) {
            return@withContext "❌ خطای شبکه: ${e.message}"
        }
    }

    suspend fun generateCommand(apiKey: String, userPrompt: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Error: API Key is missing."

        val systemPrompt = """
            You are an AI Linux Agent inside an Android app.
            The user gives you a request in natural language (Persian or English).
            You must output ONLY the bash/linux command to fulfill the request.
            Do NOT output any markdown, explanations, or backticks.
            If multiple commands are needed, chain them with &&.
            Example: "list files in download" -> ls /sdcard/Download
            Example: "create a folder named test and go inside it" -> mkdir -p test && cd test
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("model", "nvidia/nemotron-3-ultra-550b-a55b:free")
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
            put("temperature", 0.1)
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("User-Agent", "Mozilla/5.0") // 👈 اضافه کردن User-Agent برای جلوگیری از بلاک شدن
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Error: ${response.code} - ${response.message}"
                val responseData = response.body?.string() ?: return@withContext null
                val jsonObject = JSONObject(responseData)
                val content = jsonObject.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                return@withContext content.trim()
            }
        } catch (e: IOException) {
            return@withContext "Network Error: ${e.message}"
        }
    }
}
