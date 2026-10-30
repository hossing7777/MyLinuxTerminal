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
import androidx.compose.ui.zIndex
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
import java.text.SimpleDateFormat
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

val availableModels = listOf(
    "auto" to "⚡ Auto (Orchestrator)",
    "parallel" to "🚀 Parallel (Race 3 Models)",
    "nvidia/nemotron-3-ultra-550b-a55b:free" to "Nemotron 3 Ultra (Reasoning)",
    "thinkingmachines/inkling:free" to "Inkling (Coding/Agent)",
    "cohere/north-mini-code:free" to "Cohere North (Light Code)",
    "nvidia/nemotron-3.5-lightning:free" to "Nemotron 3.5 (Fast Agent)",
    "dots-studio/dots-3-note-preview:free" to "Dots3 Note (Vision)"
)

val fallbackModels = listOf(
    "nvidia/nemotron-3-ultra-550b-a55b:free",
    "thinkingmachines/inkling:free",
    "cohere/north-mini-code:free",
    "nvidia/nemotron-3.5-lightning:free"
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)

class LocalAIState {
    var isModelReady by mutableStateOf(false)
    var isServerRunning by mutableStateOf(false)
    var isDownloading by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    val serverLog = mutableStateListOf<String>()
    val chatHistory = mutableStateListOf<Pair<String, String>>()
    var isGenerating by mutableStateOf(false)
}

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val localAiState = remember { LocalAIState() }
    val mainScope = rememberCoroutineScope()
    val context = LocalContext.current
    val dummyOutput = remember { mutableStateListOf<String>() }
    
    LaunchedEffect(Unit) {
        extractLinux(context, dummyOutput)
    }
    
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1E1E1E)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Terminal", tint = Color.Cyan) },
                    label = { Text("Terminal", color = Color.Cyan) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Local AI", tint = Color.Cyan) },
                    label = { Text("Local AI", color = Color.Cyan) }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().zIndex(if (selectedTab == 0) 1f else 0f)) {
                    TerminalScreen()
                }
                Box(modifier = Modifier.fillMaxSize().zIndex(if (selectedTab == 1) 1f else 0f)) {
                    LocalAIScreen(localAiState, mainScope)
                }
            }
        }
    }
}

// 👇 ================= تب ترمینال و ایجنت =================
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val terminalState = remember { TerminalState() }
    
    var isExtracted by remember { mutableStateOf(false) }
    var showPackageDialog by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }

    var inputText by remember { mutableStateOf("") }
    var fontSize by remember { mutableStateOf(14.sp) }
    var textColor by remember { mutableStateOf(Color(0xFF00FF00)) }
    var bgColor by remember { mutableStateOf(Color(0xFF121212)) }
    var showSettings by remember { mutableStateOf(false) }
    
    var showAiDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var aiPrompt by remember { mutableStateOf("") }
    var isAiRunning by remember { mutableStateOf(false) }
    var agentJob by remember { mutableStateOf<Job?>(null) }
    var aiProgress by remember { mutableStateOf(0) }
    var activeModelName by remember { mutableStateOf("") }
    
    var currentChatId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var currentHistory by remember { mutableStateOf(JSONArray()) }
    val chatList = remember { mutableStateListOf<ChatSession>() }
    val agentLogs = remember { mutableStateListOf<String>() }
    
    var longTermMemory by remember { mutableStateOf(prefs.getString("lt_memory", "No successful commands yet.") ?: "") }
    var globalLessons by remember { mutableStateOf("") }
    
    val aiAgent = remember { AiAgent() }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var selectedModelId by remember { mutableStateOf(prefs.getString("selected_model", "auto") ?: "auto") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    
    val ptyProcess = remember { PtyProcess() }
    val outputLines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val hardwarePermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BODY_SENSORS, Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    fun requestAllHardwarePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsToRequest = mutableListOf<String>()
            for (perm in hardwarePermissions) {
                if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(perm)
                }
            }
            if (permissionsToRequest.isNotEmpty()) {
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }
    }

    val availablePackages = listOf("python3", "git", "nano", "bash", "curl", "wget", "nodejs", "vim", "htop", "net-tools", "procps")
    val selectedPackages = remember { mutableStateListOf("python3", "git", "nano", "bash", "net-tools", "procps") }
    var isCtrlPressed by remember { mutableStateOf(false) }
    
    val setupDoneFile = remember { File(context.filesDir, "alpine/.setup_done") }
    val chatsDir = remember { File(context.filesDir, "chats").apply { mkdirs() } }
    val globalMemoryFile = remember { File(context.filesDir, "global_lessons.txt") }

    fun loadAllChats() {
        chatList.clear()
        chatsDir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                chatList.add(ChatSession(json.getString("id"), json.getString("title"), json.getLong("timestamp"), json.getJSONArray("history")))
            } catch (e: Exception) {}
        }
        chatList.sortByDescending { it.timestamp }
    }

    fun saveCurrentChat() {
        if (currentHistory.length() == 0) return
        try {
            val file = File(chatsDir, "$currentChatId.json")
            val title = if (currentHistory.length() > 0) currentHistory.getJSONObject(0).getString("content").take(30) + "..." else "New Chat"
            val json = JSONObject().apply {
                put("id", currentChatId); put("title", title); put("timestamp", System.currentTimeMillis()); put("history", currentHistory)
            }
            file.writeText(json.toString())
            loadAllChats()
        } catch (e: Exception) {}
    }

    fun loadGlobalMemory() {
        if (globalMemoryFile.exists()) globalLessons = globalMemoryFile.readText()
    }

    fun appendToGlobalMemory(errorMsg: String, solution: String) {
        try {
            globalMemoryFile.appendText("Error: $errorMsg\nSolution: $solution\n---\n")
            loadGlobalMemory()
        } catch (e: Exception) {}
    }

    fun updateMemory(cmd: String) {
        if (!longTermMemory.contains(cmd)) {
            longTermMemory += "\n- $cmd"
            prefs.edit().putString("lt_memory", longTermMemory).apply()
        }
    }

    LaunchedEffect(Unit) {
        loadAllChats()
        loadGlobalMemory()
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) {}

    fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${context.packageName}")
            manageStorageLauncher.launch(intent)
        }
    }

    fun copyAllText() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", outputLines.joinToString("\n")))
        Toast.makeText(context, "متن کپی شد", Toast.LENGTH_SHORT).show()
    }

    fun killProcesses() {
        if (terminalState.ptyFd > 0) {
            ptyProcess.writeData("killall -9 python3 node zip 2>/dev/null\n")
            outputLines.add("🛑 تمام پروسه‌های پس‌زمینه بسته شدند.")
        }
    }

    fun sendToPty(data: String) {
        if (terminalState.ptyFd > 0) ptyProcess.writeData(data)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    suspend fun executeCommandAndWait(command: String): String {
        return withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<String>()
            terminalState.commandDeferred = deferred
            terminalState.currentMarker = "::CMD_DONE_${System.currentTimeMillis()}::"
            terminalState.commandOutputBuffer.clear()
            terminalState.isWaitingForCommand = true
            
            ptyProcess.writeData("stty -echo; $command 2>&1; echo \"${terminalState.currentMarker}\"; stty echo\n")
            
            try {
                withTimeout(120000) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                terminalState.isWaitingForCommand = false
                ptyProcess.writeData("\u0003")
                "Command Timed Out after 2 minutes."
            }
        }
    }

    suspend fun executeWithAutoHeal(command: String): String {
        var output = executeCommandAndWait(command)
        
        if (output.contains("not found", ignoreCase = true) || output.contains("Permission denied", ignoreCase = true)) {
            val missingCmd = Regex("([^:\\s]+): not found").find(output)?.groupValues?.get(1)?.trim() ?: ""
            
            val pkgToInstall = when (missingCmd) {
                "bash" -> "bash"
                "python3", "python" -> "python3"
                "curl" -> "curl"
                "wget" -> "wget"
                "git" -> "git"
                "ifconfig" -> "net-tools"
                "free" -> "procps"
                "nano" -> "nano"
                "vim" -> "vim"
                "ssh" -> "openssh"
                "zip" -> "zip"
                "unzip" -> "unzip"
                "node" -> "nodejs"
                "sqlite3" -> "sqlite"
                "lspci" -> "pciutils"
                "lsusb" -> "usbutils"
                else -> missingCmd
            }

            if (pkgToInstall.isNotBlank() && pkgToInstall.length < 20 && !pkgToInstall.contains("/")) {
                coroutineScope.launch(Dispatchers.Main) {
                    agentLogs.add("⚙️ سیستم به طور خودکار پکیج '$pkgToInstall' را نصب می‌کند...")
                }
                
                executeCommandAndWait("mkdir -p /sdcard/Download/Local_AI_Models/Library_Cache/apk 2>/dev/null")
                val healScript = "CACHE=/sdcard/Download/Local_AI_Models/Library_Cache/apk ; if ls \$CACHE/$pkgToInstall-*.apk >/dev/null 2>&1; then echo '📦 [Local Cache] Installing $pkgToInstall from local storage...' ; apk add --cache-dir=\$CACHE --allow-untrusted $pkgToInstall 2>&1 ; else echo '🌐 [Internet] Downloading and installing $pkgToInstall...' ; apk add --cache-dir=\$CACHE $pkgToInstall 2>&1 ; fi"
                var installOutput = executeCommandAndWait(healScript)
                
                if (installOutput.contains("temporary error", ignoreCase = true) || installOutput.contains("error", ignoreCase = true)) {
                    coroutineScope.launch(Dispatchers.Main) {
                        agentLogs.add("🔄 خطا در نصب. تغییر Mirror به Aliyun و تلاش مجدد...")
                    }
                    installOutput = executeCommandAndWait("sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories && apk update && apk add --cache-dir=/sdcard/Download/Local_AI_Models/Library_Cache/apk $pkgToInstall 2>&1")
                }

                coroutineScope.launch(Dispatchers.Main) {
                    agentLogs.add("✅ نصب انجام شد. اجرای مجدد دستور اصلی...")
                }
                output = executeCommandAndWait(command)
            }
        }
        return output
    }

    fun startTerminal(packagesToInstall: List<String> = emptyList()) {
        if (terminalState.ptyFd > 0) return
        coroutineScope.launch(Dispatchers.IO) {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootPath = File(nativeLibDir, "libproot.so").absolutePath
            val loaderPath = File(nativeLibDir, "libproot_loader.so").absolutePath
            val alpinePath = File(context.filesDir, "alpine").absolutePath
            val tmpDir = File(context.cacheDir, "proot_tmp").absolutePath
            File(tmpDir).mkdirs()
            
            // ساخت یک فایل اسکریپت مجزا برای نصب پیش‌نیازها (بدون خطای سینتکس)
            val setupScriptFile = File(context.filesDir, "terminal_setup.sh")
            val setupPackages = if (!setupDoneFile.exists() && packagesToInstall.isNotEmpty()) {
                packagesToInstall.joinToString("\n") { "install_pkg $it" } + "\ntouch /.setup_done"
            } else {
                ""
            }
            
            val setupContent = """
                #!/bin/sh
                CACHE=/sdcard/Download/Local_AI_Models/Library_Cache/apk
                mkdir -p ${'$'}CACHE
                install_pkg() {
                    PKG=${'$'}1
                    if apk info -e ${'$'}PKG >/dev/null 2>&1; then return; fi
                    if ls ${'$'}CACHE/${'$'}PKG-*.apk >/dev/null 2>&1; then
                        echo "📦 [Local Cache] Installing ${'$'}PKG from local storage..."
                        apk add --cache-dir=${'$'}CACHE --allow-untrusted ${'$'}PKG
                    else
                        echo "🌐 [Internet] Downloading and installing ${'$'}PKG..."
                        apk add --cache-dir=${'$'}CACHE ${'$'}PKG
                    fi
                }
                install_pkg bash
                install_pkg net-tools
                install_pkg procps
                $setupPackages
                echo '::SETUP_DONE::'
            """.trimIndent()
            setupScriptFile.writeText(setupContent)
            setupScriptFile.setExecutable(true)

            // اجرای اسکریپت از طریق پوشه share شده در PRoot
            val scriptFile = File(context.filesDir, "start.sh")
            scriptFile.writeText("""
                $prootPath --kill-on-exit --link2symlink --sysvipc -0 -r $alpinePath -w / -b /dev -b /proc -b /sys -b /storage/emulated/0:/sdcard -b ${context.filesDir.absolutePath}:/host_files /bin/sh -c "sh /host_files/terminal_setup.sh; exec /bin/sh"
            """.trimIndent())
            scriptFile.setExecutable(true)

            withContext(Dispatchers.Main) { outputLines.clear() }

            terminalState.ptyFd = ptyProcess.createPty(
                scriptFile.absolutePath, prootPath, alpinePath, tmpDir, loaderPath, nativeLibDir, context.filesDir.absolutePath
            )
            
            if (terminalState.ptyFd > 0) {
                val buf = ByteArray(4096)
                while (true) {
                    val len = ptyProcess.readData(buf)
                    if (len > 0) {
                        val text = String(buf, 0, len)
                        val cleanText = cleanAnsi(text)

                        if (terminalState.isWaitingForCommand) {
                            terminalState.commandOutputBuffer.append(cleanText)
                            if (terminalState.commandOutputBuffer.contains(terminalState.currentMarker)) {
                                val finalOutput = terminalState.commandOutputBuffer.toString().substringBefore(terminalState.currentMarker).trim()
                                terminalState.commandDeferred?.complete(finalOutput)
                                terminalState.isWaitingForCommand = false
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (cleanText.contains("::SETUP_DONE::")) isInstalling = false
                            val lines = cleanText.split("\n")
                            for (line in lines) {
                                if (line.isNotBlank() && !line.contains("::CMD_DONE_") && !line.contains("::SETUP_DONE::")) {
                                    outputLines.add(line)
                                }
                            }
                            listState.animateScrollToItem(maxOf(0, outputLines.size - 1))
                        }
                    } else break
                }
            }
        }
    }

    LaunchedEffect(Unit) { 
        isExtracted = true
        if (setupDoneFile.exists() || prefs.getBoolean("skip_setup", false)) startTerminal(emptyList()) else showPackageDialog = true
        checkStoragePermission()
    }

    fun stopAgent() {
        agentJob?.cancel()
        sendToPty("\u0003")
        isAiRunning = false
        agentLogs.add("🛑 عملیات متوقف شد.")
    }

    fun startNewChat() {
        saveCurrentChat() 
        currentChatId = UUID.randomUUID().toString()
        currentHistory = JSONArray()
        agentLogs.clear()
        aiPrompt = ""
        showHistoryDialog = false
        showAiDialog = true
    }

    fun loadChat(session: ChatSession) {
        saveCurrentChat() 
        currentChatId = session.id
        currentHistory = session.history
        agentLogs.clear()
        agentLogs.add("📂 چت بارگذاری شد: ${session.title}")
        for (i in 0 until session.history.length()) {
            val msg = session.history.getJSONObject(i)
            val role = if (msg.getString("role") == "user") "👤 شما" else "🤖 AI"
            agentLogs.add("$role: ${msg.getString("content")}")
        }
        showHistoryDialog = false
        showAiDialog = true
    }

    fun runAgent(userRequest: String) {
        isAiRunning = true
        aiProgress = 0
        agentLogs.add("📝 درخواست جدید: $userRequest")
        
        currentHistory.put(JSONObject().put("role", "user").put("content", userRequest))
        saveCurrentChat()
        
        agentJob = coroutineScope.launch {
            var step = 0
            val maxStepsPerBatch = 30
            var fallbackIndex = 0
            var consecutiveErrors = 0 
            
            while (isAiRunning) {
                step++
                var currentModel = selectedModelId
                
                if (selectedModelId == "auto") {
                    if (step == 1) currentModel = "nvidia/nemotron-3-ultra-550b-a55b:free"
                    else currentModel = "thinkingmachines/inkling:free"
                } else if (selectedModelId == "parallel") {
                    currentModel = "parallel"
                }
                
                if (fallbackIndex > 0) currentModel = fallbackModels[fallbackIndex % fallbackModels.size]
                activeModelName = availableModels.find { it.first == currentModel }?.second ?: currentModel
                
                if (step > maxStepsPerBatch) {
                    agentLogs.add("🔄 سقف مراحل پر شد. ادامه می‌دهیم...")
                    currentHistory.put(JSONObject().put("role", "user").put("content", "Continue task from here."))
                    step = 1
                }

                agentLogs.add("🤖 در حال فکر کردن (مرحله $step - ${activeModelName.take(15)})...")
                
                var aiResponse: String? = null
                val memoryWithLessons = longTermMemory + "\nGLOBAL ERRORS:\n" + globalLessons
                
                if (currentModel == "parallel") {
                    aiResponse = aiAgent.runParallelAgentStep(apiKey, listOf("nvidia/nemotron-3-ultra-550b-a55b:free", "thinkingmachines/inkling:free", "cohere/north-mini-code:free"), currentHistory, memoryWithLessons)
                } else {
                    aiResponse = aiAgent.runAgentStep(apiKey, currentModel, currentHistory, memoryWithLessons)
                }
                
                if (aiResponse == "RETRY_ERROR" || aiResponse == null || aiResponse.startsWith("HTTP_ERROR")) {
                    fallbackIndex++
                    agentLogs.add("⚠️ خطا. تغییر به مدل: ${fallbackModels[fallbackIndex % fallbackModels.size]}")
                    if (fallbackIndex % fallbackModels.size == 0) delay(30000) else delay(5000)
                    step--
                    continue
                }
                
                fallbackIndex = 0
                
                if (aiResponse.contains("TASK_COMPLETE", ignoreCase = true)) {
                    aiProgress = 100
                    agentLogs.add("✅ عملیات با موفقیت به پایان رسید!")
                    currentHistory.put(JSONObject().put("role", "assistant").put("content", "TASK_COMPLETE"))
                    saveCurrentChat()
                    break
                }

                if (aiResponse.contains("I cannot help", ignoreCase = true) || aiResponse.contains("unethical", ignoreCase = true)) {
                    agentLogs.add("⚠️ هوش مصنوعی از انجام این درخواست خودداری کرد.")
                    break
                }

                var cleanCommand: String = aiResponse
                val progressRegex = Regex("""\[PROGRESS:\s*(\d+)%\]""")
                val match = progressRegex.find(cleanCommand)
                if (match != null) {
                    aiProgress = match.groupValues[1].toIntOrNull() ?: aiProgress
                    cleanCommand = cleanCommand.replace(progressRegex, "").trim()
                }

                if (cleanCommand.startsWith("{") && cleanCommand.contains("command")) {
                    try {
                        val jsonResponse = JSONObject(cleanCommand)
                        if (jsonResponse.has("command")) cleanCommand = jsonResponse.getString("command")
                    } catch (e: Exception) {}
                }

                if (cleanCommand.isEmpty()) continue

                agentLogs.add("💻 اجرای دستور: $cleanCommand")
                currentHistory.put(JSONObject().put("role", "assistant").put("content", cleanCommand))
                
                val output = executeWithAutoHeal(cleanCommand)
                
                if (output.contains("not found", ignoreCase = true) || output.contains("error", ignoreCase = true) || output.contains("Permission denied", ignoreCase = true)) {
                    consecutiveErrors++
                    if (selectedModelId == "auto") currentModel = "nvidia/nemotron-3-ultra-550b-a55b:free"
                    
                    agentLogs.add("⚠️ خطای پیچیده شناسایی شد ($consecutiveErrors/5). درخواست راه‌حل از AI...")
                    appendToGlobalMemory(cleanCommand, "Failed with error: ${output.take(50)}")
                    currentHistory.put(JSONObject().put("role", "user").put("content", "The command failed with error:\n$output\n\nPlease analyze the error and output a corrected command."))
                    
                    if (consecutiveErrors >= 5) {
                        agentLogs.add("❌ تعداد خطاهای متوالی به 5 رسید. برای جلوگیری از حلقه بی‌پایان متوقف شد.")
                        break
                    }
                } else {
                    consecutiveErrors = 0
                    if (!output.contains("Timed Out")) {
                        updateMemory(cleanCommand)
                    }
                }

                val displayOutput = if (output.isBlank()) "(بدون خروجی)" else output
                agentLogs.add("📄 خروجی:\n$displayOutput")
                
                currentHistory.put(JSONObject().put("role", "user").put("content", "Output: $output\nNext command?"))
                saveCurrentChat()
            }
            isAiRunning = false
        }
    }

    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("📁 Chat History", color = Color.Cyan) },
            text = {
                Column {
                    Button(
                        onClick = { startNewChat() },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006600))
                    ) { Text("➕ New Chat (چت جدید)") }
                    
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(chatList) { session ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { loadChat(session) },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(session.title, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.timestamp)),
                                        color = Color.Gray, fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistoryDialog = false }) { Text("Close") } }
        )
    }

    if (showAiDialog) {
        AlertDialog(
            onDismissRequest = { if (!isAiRunning) showAiDialog = false },
            containerColor = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(16.dp),
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Face, contentDescription = "AI", tint = Color.Cyan, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI Agent", color = Color.Cyan, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { loadAllChats(); showHistoryDialog = true }) {
                        Icon(Icons.Default.List, contentDescription = "History", tint = Color.White)
                    }
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (!isAiRunning) {
                        OutlinedTextField(
                            value = aiPrompt,
                            onValueChange = { aiPrompt = it },
                            placeholder = { Text("درخواست خود را بنویسید...", color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
                        ) { Text("شروع", fontSize = 16.sp) }
                    } else {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Cyan, strokeWidth = 3.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("در حال اجرا... ($aiProgress%)", color = Color.Yellow)
                                }
                                Button(onClick = { stopAgent() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("STOP") }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = aiProgress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                                color = Color.Cyan,
                                trackColor = Color.DarkGray
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("گزارش زنده:", color = Color.LightGray, fontSize = 12.sp)
                        TextButton(onClick = { agentLogs.clear() }) { Text("پاک کردن", color = Color.Red, fontSize = 12.sp) }
                    }
                    LazyColumn(modifier = Modifier.height(220.dp).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)) {
                        items(agentLogs) { log -> Text(log, color = Color(0xFF00FF00), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp)) }
                    }
                }
            },
            confirmButton = { if (!isAiRunning) TextButton(onClick = { saveCurrentChat(); showAiDialog = false }) { Text("بستن", color = Color.Cyan) } }
        )
    }

    if (showPackageDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Select Packages") },
            text = {
                LazyColumn {
                    items(availablePackages) { pkg ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                            Checkbox(checked = selectedPackages.contains(pkg), onCheckedChange = { if (it) selectedPackages.add(pkg) else selectedPackages.remove(pkg) })
                            Text(pkg)
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showPackageDialog = false; isInstalling = true; startTerminal(selectedPackages.toList()) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { setupDoneFile.parentFile?.mkdirs(); setupDoneFile.createNewFile(); prefs.edit().putBoolean("skip_setup", true).apply(); showPackageDialog = false; startTerminal(emptyList()) }) { Text("Skip") } }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings", color = Color.Cyan) },
            text = {
                Column {
                    Text("Hormuz API Key:", color = Color.Cyan)
                    OutlinedTextField(
                        value = apiKey, 
                        onValueChange = { apiKey = it }, 
                        placeholder = { Text("ng-...") }, 
                        modifier = Modifier.fillMaxWidth(), 
                        visualTransformation = PasswordVisualTransformation(), 
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text("Active AI Model:", color = Color.Cyan)
                    Box {
                        OutlinedButton(
                            onClick = { showModelDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text(availableModels.find { it.first == selectedModelId }?.second ?: "Select Model")
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = showModelDropdown,
                            onDismissRequest = { showModelDropdown = false },
                            modifier = Modifier.background(Color(0xFF2A2A2A)).heightIn(max = 300.dp)
                        ) {
                            availableModels.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name, color = Color.White) },
                                    onClick = {
                                        selectedModelId = id
                                        prefs.edit().putString("selected_model", id).apply()
                                        showModelDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(
                            onClick = { 
                                prefs.edit().putString("api_key", apiKey).apply()
                                Toast.makeText(context, "ذخیره شد", Toast.LENGTH_SHORT).show() 
                            }, 
                            modifier = Modifier.weight(1f)
                        ) { Text("ذخیره") }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Button(
                            onClick = { 
                                isTesting = true
                                coroutineScope.launch { 
                                    testResult = aiAgent.testConnection(apiKey, "nvidia/nemotron-3-ultra-550b-a55b:free")
                                    isTesting = false 
                                } 
                            }, 
                            modifier = Modifier.weight(1f)
                        ) { Text("تست") }
                    }
                    if (isTesting) Text("در حال تست...", color = Color.Yellow, modifier = Modifier.padding(top = 4.dp))
                    if (testResult.isNotEmpty()) Text(testResult, color = if (testResult.contains("✅")) Color.Green else Color.Red, modifier = Modifier.padding(top = 4.dp))

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { requestAllHardwarePermissions() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008080))
                    ) {
                        Text("Grant Hardware Permissions (دسترسی به سخت‌افزار)")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Close", color = Color.Cyan) } }
        )
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text("HguTerminal", color = Color.Cyan, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E)),
                actions = {
                    IconButton(onClick = { copyAllText() }) { Icon(Icons.Default.Share, contentDescription = "Copy", tint = Color.White) }
                    IconButton(onClick = { killProcesses() }) { Icon(Icons.Default.Close, contentDescription = "Kill", tint = Color(0xFFFF5252)) }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White) }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAiDialog = true },
                containerColor = Color(0xFF6200EE),
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Face, contentDescription = "AI") },
                text = { Text("AI Agent") }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(8.dp)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(outputLines) { line -> SelectionContainer { Text(text = line, color = textColor, fontFamily = FontFamily.Monospace, fontSize = fontSize) } }
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isCtrlPressed) {
                    listOf("C" to "\u0003", "D" to "\u0004").forEach { (l, c) -> Button(onClick = { sendToPty(c); isCtrlPressed = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.height(36.dp)) { Text(l, fontSize = 12.sp) } }
                } else {
                    Button(onClick = { isCtrlPressed = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)), modifier = Modifier.height(36.dp)) { Text("CTRL", fontSize = 12.sp) }
                    listOf("ESC" to "\u001b", "TAB" to "\t", "/" to "/", "-" to "-", "UP" to "\u001b[A", "DOWN" to "\u001b[B").forEach { (l, c) ->
                        Button(onClick = { sendToPty(c) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)), modifier = Modifier.height(36.dp)) { Text(l, fontSize = 12.sp) }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "$ ", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontSize = fontSize, fontWeight = FontWeight.Bold)
                BasicTextField(
                    value = inputText, onValueChange = { inputText = it },
                    textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = fontSize),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester), singleLine = true, enabled = isExtracted,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { sendToPty(inputText + "\n"); inputText = ""; focusRequester.requestFocus() })
                )
            }
        }
    }
}

// 👇 ================= تب هوش مصنوعی لوکال =================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LocalAIScreen(state: LocalAIState, scope: CoroutineScope) {
    val context = LocalContext.current
    val client = remember { OkHttpClient.Builder().connectTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build() }
    val modelsDirPath = "/sdcard/Download/Local_AI_Models"
    var inputText by remember { mutableStateOf("") }

    fun startLocalModelSetup() {
        state.isDownloading = true
        state.progress = 0f
        state.serverLog.clear()
        state.serverLog.add("Preparing environment...")
        
        scope.launch(Dispatchers.IO) {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootPath = File(nativeLibDir, "libproot.so").absolutePath
            val loaderPath = File(nativeLibDir, "libproot_loader.so").absolutePath
            val alpinePath = File(context.filesDir, "alpine").absolutePath
            
            val scriptFile = File(context.filesDir, "local_ai_start.sh")
                        scriptFile.writeText("""
                #!/bin/sh
                export PYTHONUNBUFFERED=1
                MODEL_DIR="$modelsDirPath"
                mkdir -p "${'$'}MODEL_DIR"
                MODEL_FILE=${'$'}(ls "${'$'}MODEL_DIR"/*.gguf 2>/dev/null | head -n 1)
                
                if [ -z "${'$'}MODEL_FILE" ]; then
                    echo "ERROR: No .gguf model found in ${'$'}MODEL_DIR"
                    echo "::ERROR_NO_MODEL::"
                    exit 1
                fi
                
                echo "Found model: ${'$'}MODEL_FILE"
                CACHE="/sdcard/Download/Local_AI_Models/Library_Cache"
                mkdir -p "${'$'}CACHE/apk"
                
                echo "::PROGRESS:10::"
                echo "Installing dependencies (APK)... This takes a few minutes."
                apk add --cache-dir="${'$'}CACHE/apk" python3 py3-pip python3-dev git cmake build-base libffi-dev linux-headers
                
                echo "::PROGRESS:40::"
                echo "Installing Python packages (llama-cpp)..."
                # استفاده از میرور علی‌بابا و افزایش تایم‌اوت/تعداد تلاش
                pip install llama-cpp-python[server] --break-system-packages \
                    -i https://mirrors.aliyun.com/pypi/simple/ \
                    --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu \
                    --timeout 120 \
                    --retries 10
                
                # بررسی موفقیت‌آمیز بودن نصب
                if [ $? -ne 0 ]; then
                    echo "❌ ERROR: Failed to install Python packages after several retries."
                    echo "::ERROR_NO_MODEL::"
                    exit 1
                fi
                
                echo "::PROGRESS:90::"
                echo "Loading model into RAM... Please wait (1-2 minutes)"
                python3 -u -m llama_cpp.server --model "${'$'}MODEL_FILE" --host 0.0.0.0 --port 8000 --n_ctx 2048
            """.trimIndent())
            scriptFile.setExecutable(true)
            
            val tmpDir = File(context.cacheDir, "proot_tmp").absolutePath
            File(tmpDir).mkdirs()
            
            val hostFilesDir = context.filesDir.absolutePath
            val pb = ProcessBuilder(
                prootPath, "--kill-on-exit", "--link2symlink", "--sysvipc", "-0",
                "-r", alpinePath, "-w", "/", "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "/storage/emulated/0:/sdcard",
                "-b", "$hostFilesDir:/host_files",
                "/bin/sh", "/host_files/local_ai_start.sh"
            )
            
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("PROOT_TMP_DIR", tmpDir)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_FORCE_FOREIGN_BINARY", "1")
                put("PROOT_LOADER", loaderPath)
                put("LD_LIBRARY_PATH", nativeLibDir)
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("HOME", "/root")
                put("PYTHONUNBUFFERED", "1")
            }
            
            val logFile = File("/sdcard/Download/Local_AI_Models/local_ai_server.log")
            try {
                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                logFile.appendText("\n\n========== 🚀 New Run Started at $dateStr ==========\n")
                
                val process = pb.start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    val currentLine = line
                    
                    // 👇 ذخیره لاگ در فایل
                    try {
                        logFile.appendText("$currentLine\n")
                    } catch (e: Exception) {
                        // نادیده گرفتن خطای احتمالی دسترسی به فایل
                    }
                    
                    withContext(Dispatchers.Main) {
                        state.serverLog.add(currentLine)
                        if (currentLine.contains("::PROGRESS:")) {
                            val prog = currentLine.substringAfter("::PROGRESS:").substringBefore("::").toFloatOrNull() ?: 0f
                            state.progress = prog / 100f
                            state.serverLog.add("📊 Progress: ${prog.toInt()}%")
                        }
                        if (currentLine.contains("Uvicorn running on http://0.0.0.0:8000")) {
                            state.isDownloading = false
                            state.isServerRunning = true
                            state.isModelReady = true
                            state.progress = 1f
                            state.serverLog.add("✅ Local Model is Ready! You can chat now.")
                        }
                        if (currentLine.contains("::ERROR_NO_MODEL::")) {
                            state.isDownloading = false
                            state.isModelReady = false
                            state.serverLog.add("❌ خطا: هیچ مدلی پیدا نشد. لطفاً یک فایل .gguf در پوشه دانلود قرار دهید.")
                        }
                    }
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state.serverLog.add("Error: ${e.message}")
                    state.isDownloading = false
                }
            }
        }
    }

    fun sendLocalChat(prompt: String) {
        if (prompt.isBlank()) return
        state.chatHistory.add("You" to prompt)
        state.isGenerating = true
        
        scope.launch(Dispatchers.IO) {
            try {
                val messagesArray = JSONArray()
                state.chatHistory.forEach { (role, content) ->
                    val apiRole = if (role == "You") "user" else "assistant"
                    messagesArray.put(JSONObject().put("role", apiRole).put("content", content))
                }
                
                val jsonBody = JSONObject().apply {
                    put("messages", messagesArray)
                    put("temperature", 0.1)       // دمای بسیار پایین برای کد
                    put("top_p", 0.9)
                    put("repeat_penalty", 1.15)   // جلوگیری از تکرار کلمات
                    put("max_tokens", 256)        // کاهش حجم پاسخ برای جلوگیری از تایم‌اوت
                    put("stop", JSONArray().put("<|im_end|>").put("<|endoftext|>"))
                }
                
                val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("http://127.0.0.1:8000/v1/completions").post(requestBody).build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseData = response.body?.string() ?: ""
                        val json = JSONObject(responseData)
                        val aiMsg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                        withContext(Dispatchers.Main) { state.chatHistory.add("AI" to aiMsg.trim()) }
                    } else {
                        withContext(Dispatchers.Main) { state.chatHistory.add("Error" to "Server error: ${response.code}") }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { state.chatHistory.add("Error" to "Connection failed. Is the server running?") }
            } finally {
                withContext(Dispatchers.Main) { state.isGenerating = false }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Text("Local AI (Offline)", color = Color.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        
        if (!state.isModelReady && !state.isServerRunning) {
            Text(
                "لطفاً یک مدل با پسوند .gguf (مثلاً Qwen2.5-0.5B) را در پوشه زیر در حافظه گوشی کپی کنید:\n$modelsDirPath",
                color = Color.Yellow, fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { startLocalModelSetup() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isDownloading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008080))
            ) {
                if (state.isDownloading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("در حال کار... (${(state.progress * 100).toInt()}%)")
                    }
                } else {
                    Text("شروع / بررسی مدل (Start Server)")
                }
            }
            
            if (state.isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = state.progress,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Color.Cyan,
                    trackColor = Color.DarkGray
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)) {
                items(state.serverLog) { log -> Text(log, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.chatHistory) { (role, msg) ->
                    val isUser = role == "You"
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isUser) Color(0xFF2A2A2A) else Color(0xFF006600))
                    ) {
                        Text(text = "$role: $msg", color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText, onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("به هوش مصنوعی لوکال پیام بدهید...") },
                    enabled = !state.isGenerating
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { sendLocalChat(inputText); inputText = "" },
                    enabled = !state.isGenerating,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
                ) {
                    if (state.isGenerating) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    else Text("ارسال")
                }
            }
        }
    }
}

// 👇 ================= توابع کمکی =================
fun cleanAnsi(text: String): String {
    var cleaned = text
    cleaned = cleaned.replace(Regex("""\u001B\[[0-9;?]*[a-zA-Z]"""), "")
    cleaned = cleaned.replace(Regex("""\u001B\([a-zA-Z0-9]"""), "")
    cleaned = cleaned.replace(Regex("""[\u000E\u000F]"""), "")
    cleaned = cleaned.replace(Regex("""\[\d+\s+\d+%"""), "")
    cleaned = cleaned.replace(Regex("""\[[78]"""), "")
    return cleaned
}

suspend fun extractLinux(context: Context, outputLines: MutableList<String>) = withContext(Dispatchers.IO) {
    val alpineDir = File(context.filesDir, "alpine")
    val assets = context.assets.list("") ?: emptyArray()
    val assetName = if (assets.contains("alpine.tar.gz")) "alpine.tar.gz" else "alpine.tar"
    if (!alpineDir.exists() || alpineDir.listFiles()?.isEmpty() == true) {
        try {
            alpineDir.mkdirs()
            context.assets.open(assetName).use { input -> extractTarSmart(input, alpineDir) }
        } catch (e: Exception) { Log.e("Terminal", "Extract Error", e) }
    }
    fixAlpineStructure(alpineDir)
}

fun fixAlpineStructure(alpineDir: File) {
    try {
        val binDir = File(alpineDir, "bin")
        val usrBinDir = File(alpineDir, "usr/bin")
        val libDir = File(alpineDir, "lib")
        val etcDir = File(alpineDir, "etc")
        if (binDir.exists() && !binDir.isDirectory) binDir.delete()
        binDir.mkdirs()
        if (usrBinDir.exists() && !usrBinDir.isDirectory) usrBinDir.delete()
        usrBinDir.mkdirs()
        if (!etcDir.exists()) etcDir.mkdirs()
        val resolvConf = File(etcDir, "resolv.conf")
        if (!resolvConf.exists()) resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        var busybox = File(binDir, "busybox")
        if (!busybox.exists() || busybox.length() < 100000L) {
            val usrBusybox = File(usrBinDir, "busybox")
            if (usrBusybox.exists() && usrBusybox.length() > 100000L) busybox = usrBusybox
        }
        if (busybox.exists()) {
            val applets = listOf("sh", "ls", "cat", "echo", "pwd", "mkdir", "rm", "cp", "mv", "chmod", "grep", "sed", "awk", "find", "vi")
            for (applet in applets) {
                val appletBin = File(binDir, applet)
                try { appletBin.delete() } catch (e: Exception) {}
                busybox.copyTo(appletBin, overwrite = true)
                appletBin.setExecutable(true)
                val appletUsrBin = File(usrBinDir, applet)
                try { appletUsrBin.delete() } catch (e: Exception) {}
                busybox.copyTo(appletUsrBin, overwrite = true)
                appletUsrBin.setExecutable(true)
            }
        }
        if (libDir.exists() && !libDir.isDirectory) libDir.delete()
        libDir.mkdirs()
        val ldMusl = File(libDir, "ld-musl-aarch64.so.1")
        if (!ldMusl.exists()) {
            val libc = File(libDir, "libc.musl-aarch64.so.1")
            if (libc.exists()) { libc.copyTo(ldMusl, overwrite = true); ldMusl.setExecutable(true) }
        }
        File(alpineDir, "tmp").mkdirs()
        File(alpineDir, "root").mkdirs()
    } catch (e: Exception) { Log.e("Terminal", "Fix structure error", e) }
}

fun extractTarSmart(inputStream: InputStream, destDir: File) {
    val bufferedInputStream = BufferedInputStream(inputStream)
    bufferedInputStream.mark(2)
    val signature = ByteArray(2)
    bufferedInputStream.read(signature)
    bufferedInputStream.reset()
    val isGzip = signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()
    if (isGzip) {
        GzipCompressorInputStream(bufferedInputStream).use { gzipIn -> TarArchiveInputStream(gzipIn).use { tarIn -> extractEntries(tarIn, destDir) } }
    } else {
        TarArchiveInputStream(bufferedInputStream).use { tarIn -> extractEntries(tarIn, destDir) }
    }
}

fun extractEntries(tarIn: TarArchiveInputStream, destDir: File) {
    var entry: TarArchiveEntry? = tarIn.nextEntry
    while (entry != null) {
        try {
            val destFile = File(destDir, entry.name)
            if (!destFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                entry = tarIn.nextEntry; continue
            }
            if (entry.isDirectory) {
                destFile.mkdirs()
            } else if (entry.isSymbolicLink) {
                try { Files.createSymbolicLink(Paths.get(destFile.absolutePath), Paths.get(entry.linkName)) } catch (e: Exception) {}
            } else {
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { output -> tarIn.copyTo(output) }
                if (entry.mode != 0) {
                    try {
                        val octal = Integer.toOctalString(entry.mode).takeLast(3)
                        val perms = buildString { for (c in octal) { val v = Character.digit(c, 8); append(if (v and 4 != 0) "r" else "-"); append(if (v and 2 != 0) "w" else "-"); append(if (v and 1 != 0) "x" else "-") } }
                        Files.setPosixFilePermissions(Paths.get(destFile.absolutePath), PosixFilePermissions.fromString(perms))
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { Log.e("Terminal", "Error extracting", e) }
        entry = tarIn.nextEntry
    }
}
// Build Triggered: Fri Oct 30 01:25:27 +0330 2026
