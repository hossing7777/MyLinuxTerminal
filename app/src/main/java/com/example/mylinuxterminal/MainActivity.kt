package com.example.mylinuxterminal

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.*
import java.util.concurrent.TimeUnit

class TerminalState {
    var isWaitingForCommand = false
    var currentMarker = ""
    val commandOutputBuffer = StringBuilder()
    var commandDeferred: CompletableDeferred<String>? = null
    var ptyFd = -1
}

data class ChatSession(
    val id: String,
    val title: String,
    val timestamp: Long,
    val history: JSONArray
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen() }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1E1E1E)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Terminal", tint = Color.Cyan) },
                    label = { Text("Terminal", color = Color.Cyan) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Memory, contentDescription = "Local AI", tint = Color.Cyan) },
                    label = { Text("Local AI", color = Color.Cyan) }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (selectedTab == 0) {
                TerminalScreen()
            } else {
                LocalAIScreen()
            }
        }
    }
}

// 👇 ================= تب جدید هوش مصنوعی لوکال =================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LocalAIScreen() {
    val context = LocalContext.current
    val ptyProcess = remember { PtyProcess() }
    
    var isModelReady by remember { mutableStateOf(false) }
    var isServerRunning by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var serverLog by remember { mutableStateListOf<String>() }
    var chatHistory by remember { mutableStateListOf<Pair<String, String>>() }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    val localServerUrl = "http://127.0.0.1:8000/v1/chat/completions"
    val client = remember { OkHttpClient.Builder().connectTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build() }

    // مسیر پوشه مدل در حافظه گوشی
    val modelsDirPath = "/sdcard/Download/Local_AI_Models"

    fun startLocalModelSetup() {
        isDownloading = true
        serverLog.clear()
        serverLog.add("Preparing environment...")
        serverLog.add("Looking for models in $modelsDirPath")
        
        coroutineScope.launch(Dispatchers.IO) {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootPath = File(nativeLibDir, "libproot.so").absolutePath
            val alpinePath = File(context.filesDir, "alpine").absolutePath
            
            val scriptFile = File(context.filesDir, "local_ai_start.sh")
            // 👇 اسکریپت جدید که مدل را از پوشه دانلود گوشی می‌خواند
            scriptFile.writeText("""
                #!/bin/sh
                MODEL_DIR="$modelsDirPath"
                mkdir -p "$MODEL_DIR"
                
                # پیدا کردن اولین فایل gguf در پوشه
                MODEL_FILE=$(ls "$MODEL_DIR"/*.gguf 2>/dev/null | head -n 1)
                
                if [ -z "$MODEL_FILE" ]; then
                    echo "ERROR: No .gguf model found in $MODEL_DIR"
                    echo "::ERROR_NO_MODEL::"
                    exit 1
                fi
                
                echo "Found model: $MODEL_FILE"
                echo "Installing dependencies (Python, Llama.cpp)..."
                apk update && apk add python3 py3-pip git cmake build-base libffi-dev
                
                echo "Installing llama-cpp-python..."
                pip install llama-cpp-python[server] --break-system-packages --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu
                
                echo "Starting server..."
                python3 -m llama_cpp.server --model "$MODEL_FILE" --host 0.0.0.0 --port 8000 &
                echo "::SETUP_DONE::"
            """.trimIndent())
            scriptFile.setExecutable(true)
            
            val tmpDir = File(context.cacheDir, "proot_tmp").absolutePath
            File(tmpDir).mkdirs()
            
            val pb = ProcessBuilder(
                prootPath, "--kill-on-exit", "--link2symlink", "--sysvipc", "-0",
                "-r", alpinePath, "-w", "/", "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "/storage/emulated/0:/sdcard", // 👈 اتصال حافظه برای خواندن مدل
                "/bin/sh", scriptFile.absolutePath
            )
            pb.environment()["PROOT_TMP_DIR"] = tmpDir
            
            try {
                val process = pb.start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    val currentLine = line
                    withContext(Dispatchers.Main) {
                        serverLog.add(currentLine)
                        if (currentLine.contains("::SETUP_DONE::")) {
                            isDownloading = false
                            isServerRunning = true
                            isModelReady = true
                            serverLog.add("✅ Local Model is Ready! You can chat now.")
                        }
                        if (currentLine.contains("::ERROR_NO_MODEL::")) {
                            isDownloading = false
                            isModelReady = false
                            serverLog.add("❌ خطا: هیچ مدلی پیدا نشد. لطفاً یک فایل .gguf در پوشه دانلود قرار دهید.")
                        }
                    }
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    serverLog.add("Error: ${e.message}")
                    isDownloading = false
                }
            }
        }
    }

    fun sendLocalChat(prompt: String) {
        if (prompt.isBlank()) return
        chatHistory.add("You" to prompt)
        inputText = ""
        isGenerating = true
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply {
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "user").put("content", prompt))
                    })
                    put("temperature", 0.7)
                    put("max_tokens", 256)
                }
                
                val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(localServerUrl).post(requestBody).build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseData = response.body?.string() ?: ""
                        val json = JSONObject(responseData)
                        val aiMsg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                        withContext(Dispatchers.Main) {
                            chatHistory.add("AI" to aiMsg.trim())
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            chatHistory.add("Error" to "Server error: ${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    chatHistory.add("Error" to "Connection failed. Is the server running?")
                }
            } finally {
                withContext(Dispatchers.Main) { isGenerating = false }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Text("Local AI (Offline)", color = Color.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        
        if (!isModelReady && !isServerRunning) {
            Text(
                "لطفاً یک مدل با پسوند .gguf (مثلاً Qwen2.5-0.5B) را در پوشه زیر در حافظه گوشی کپی کنید:\n$modelsDirPath",
                color = Color.Yellow, fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { startLocalModelSetup() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDownloading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008080))
            ) {
                if (isDownloading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("در حال آماده‌سازی مدل...")
                    }
                } else {
                    Text("شروع / بررسی مدل (Start Server)")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)) {
                items(serverLog) { log ->
                    Text(log, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(chatHistory) { (role, msg) ->
                    val isUser = role == "You"
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isUser) Color(0xFF2A2A2A) else Color(0xFF006600))
                    ) {
                        Text(
                            text = "$role: $msg",
                            color = Color.White,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("به هوش مصنوعی لوکال پیام بدهید...") },
                    enabled = !isGenerating
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { sendLocalChat(inputText) },
                    enabled = !isGenerating,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
                ) {
                    if (isGenerating) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    else Text("ارسال")
                }
            }
        }
    }
}

// 👇 ================= کدهای ترمینال (بدون تغییرات) =================
// (برای جلوگیری از طولانی شدن، کدهای TerminalScreen و توابع کمکی در اینجا کپی می‌شوند)
// ... (کدهای TerminalScreen، AiAgent، extractLinux و ... دقیقاً مشابه نسخه قبل هستند)

