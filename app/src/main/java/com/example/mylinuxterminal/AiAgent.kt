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
            1. FIRST, if the user gives a complex task, output a PLAN of steps in a valid JSON format like this: {"plan": ["step 1", "step 2"]}. Then in the NEXT step, start executing.
            2. If a command fails with "not found", install it using `apk add <package_name>`. If it's slow, use a mirror: `sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories && apk update`.
            3. If a Python module is missing, use `pip install --break-system-packages <module>` or use Tsinghua mirror: `-i https://pypi.tuna.tsinghua.edu.cn/simple`.
            4. If you receive a message saying "Command Timed Out", it means the command was too slow. Try a different approach or use a faster mirror.
            5. When done, reply ONLY with: TASK_COMPLETE
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            // مدیریت زمینه (فقط ۱۵ پیام آخر را نگه می‌داریم)
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
