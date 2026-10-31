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
    "shell_agent" to "🤖 Shell Agent (No API)",
    "full_auto" to "🔧 Full Auto (Recommended)",
    "auto" to "⚡ Auto (Orchestrator)",
    "parallel" to "🚀 Parallel (Race 3 Models)",
    "nvidia/nemotron-3-ultra-550b-a55b:free" to "Nemotron 3 Ultra",
    "thinkingmachines/inkling:free" to "Inkling",
    "cohere/north-mini-code:free" to "Cohere North"
)

val fallbackModels = listOf(
    "nvidia/nemotron-3-ultra-550b-a55b:free",
    "thinkingmachines/inkling:free",
    "cohere/north-mini-code:free"
)

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
    
    LaunchedEffect(Unit) {
        BrowserServer.start(mainScope)
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
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Browser", tint = Color.Cyan) },
                    label = { Text("Browser", color = Color.Cyan) }
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
                Box(modifier = Modifier.fillMaxSize().zIndex(if (selectedTab == 2) 1f else 0f)) {
                    BrowserScreen()
                }
            }
        }
    }
}

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
    var selectedModelId by remember { mutableStateOf(prefs.getString("selected_model", "shell_agent") ?: "shell_agent") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    
    val ptyProcess = remember { PtyProcess() }
    val outputLines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val availablePackages = listOf("python3", "git", "nano", "bash", "curl", "wget", "nodejs", "vim", "htop", "net-tools", "procps")
    val selectedPackages = remember { mutableStateListOf("python3", "git", "nano", "bash", "net-tools", "procps") }
    val manageStorageLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) {}
    
    fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${context.packageName}")
            manageStorageLauncher.launch(intent)
        }
    }
    
val setupDoneFile = remember { File(context.filesDir, "alpine/.setup_done") }
    val chatsDir = remember { File(context.filesDir, "chats").apply { mkdirs() } }
    val globalMemoryFile = remember { File(context.filesDir, "global_lessons.txt") }

    fun getSessionDir(chatId: String): File {
        return File(context.filesDir, "logs/$chatId").apply { mkdirs() }
    }

    fun appendAgentLog(line: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            File(getSessionDir(currentChatId), "agent.log").appendText("[$ts] $line\n")
        } catch (_: Exception) {}
    }

    fun appendCommandLog(cmd: String, status: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            File(getSessionDir(currentChatId), "commands.log").appendText("$ts | $status | $cmd\n")
        } catch (_: Exception) {}
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

    fun startTerminal(packagesToInstall: List<String> = emptyList()) {
        if (terminalState.ptyFd > 0) return
        coroutineScope.launch(Dispatchers.IO) {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootPath = File(nativeLibDir, "libproot.so").absolutePath
            val loaderPath = File(nativeLibDir, "libproot_loader.so").absolutePath
            val alpinePath = File(context.filesDir, "alpine").absolutePath
            val tmpDir = File(context.cacheDir, "proot_tmp").absolutePath
            File(tmpDir).mkdirs()
            
            // ساخت اسکریپت نصب پیش‌نیازها
            val setupScriptFile = File(context.filesDir, "terminal_setup.sh")
            val setupPackages = if (!setupDoneFile.exists() && packagesToInstall.isNotEmpty()) {
                packagesToInstall.joinToString("\n") { "install_pkg $it" } + "\ntouch /.setup_done"
            } else ""
            
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
                            val lines = cleanText.split("\n")
                            for (line in lines) {
                                if (line.isNotBlank() && !line.contains("::CMD_DONE_")) {
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
        extractLinux(context, mutableListOf<String>())
        isExtracted = true
        checkStoragePermission()
        if (setupDoneFile.exists()) {
            startTerminal(emptyList())
        } else {
            showPackageDialog = true
        }
    }

    // 👇 Shell Agent Logic (No API Required) 👇
    fun runShellAgent(userRequest: String) {
        isAiRunning = true
        aiProgress = 5
        agentLogs.clear()
        agentLogs.add("🚀 Autonomous Shell Agent Started...")
        agentLogs.add("📝 Request: $userRequest")
        
        coroutineScope.launch {
            try {
                if (terminalState.ptyFd <= 0) {
                    agentLogs.add("❌ Terminal is not ready.")
                    isAiRunning = false
                    return@launch
                }
                
                agentLogs.add("🌐 Navigating to Duck.ai...")
                BrowserBridge.loadUrl("https://duck.ai")
                delay(5000)
                
                var iteration = 0
                var currentPrompt = "$userRequest. Reply ONLY with the python code inside a markdown block."
                
                while (iteration < 5 && isAiRunning) {
                    iteration++
                    aiProgress = 10 + (iteration * 15)
                    agentLogs.add("🔄 Attempt $iteration/5...")
                    
                    agentLogs.add("⌨️ Sending prompt to AI...")
                    BrowserBridge.typeText(currentPrompt)
                    delay(1000)
                    BrowserBridge.clickSend()
                    
                    agentLogs.add("⏳ Waiting for response (15s)...")
                    delay(15000)
                    
                    agentLogs.add("📖 Extracting code...")
                    val rawCode = BrowserBridge.extractCode()
                    
                    if (rawCode == "NO_CODE_FOUND" || rawCode.isBlank() || rawCode.contains("Error: Browser not ready")) {
                        agentLogs.add("⚠️ No code found. Retrying...")
                        currentPrompt = "Please provide a valid Python code block for: $userRequest"
                        continue
                    }
                    
                    agentLogs.add("📜 Code Extracted. Running...")
                    val b64Code = android.util.Base64.encodeToString(rawCode.toByteArray(), android.util.Base64.NO_WRAP)
                    val cmd = "if ! command -v python3 >/dev/null 2>&1; then apk add python3 >/dev/null 2>&1; fi; " +
                              "echo '$b64Code' | base64 -d > /root/ai_script.py && python3 /root/ai_script.py 2>&1"
                    
                    val output = executeCommandAndWait(cmd)
                    val cleanOutput = output.lines().filter { 
                        !it.contains("stty -echo") && 
                        !it.contains("::CMD_DONE") && 
                        !it.contains("base64 -d") &&
                        it.isNotBlank() 
                    }.joinToString("\n")
                    agentLogs.add("📄 Output:\n$cleanOutput")
                    
                    if (output.contains("Traceback") || output.contains("SyntaxError") || output.contains("NameError") || output.contains("not found")) {
                        agentLogs.add("❌ Error detected! Asking AI to fix the code...")
                        currentPrompt = "The code failed with this error:\n$output\n\nPlease fix it and provide the complete python code."
                    } else {
                        agentLogs.add("✅ Task completed successfully!")
                        aiProgress = 100
                        isAiRunning = false
                        return@launch
                    }
                }
                if (isAiRunning) {
                    agentLogs.add("🛑 Max attempts reached. Stopping.")
                    isAiRunning = false
                }
            } catch (e: Exception) {
                agentLogs.add("❌ Exception: ${e.message}")
                isAiRunning = false
            }
        }
    }

    fun runAgent(userRequest: String) {
        if (selectedModelId == "shell_agent") {
            runShellAgent(userRequest)
            return
        }
        
        // API Agent Logic
        if (apiKey.isBlank()) {
            agentLogs.add("❌ API Key is missing. Please login from ⚙️ Settings.")
            showSettings = true
            return
        }
        isAiRunning = true
        aiProgress = 0
        agentLogs.add("🔐 Connecting to API...")
        
        agentJob = coroutineScope.launch {
            val authTest = aiAgent.testConnection(apiKey, "nvidia/nemotron-3-ultra-550b-a55b:free")
            if (!authTest.contains("✅")) {
                agentLogs.add("❌ Auth failed: $authTest")
                isAiRunning = false
                return@launch
            }
            agentLogs.add("✅ Connected. Starting task...")
            
            var step = 0
            var totalSteps = 0
            var fallbackIndex = 0
            
            while (isAiRunning) {
                step++
                totalSteps++
                if (totalSteps > 50) {
                    agentLogs.add("🛑 Max steps reached.")
                    break
                }
                
                var currentModel = selectedModelId
                if (selectedModelId == "full_auto" || selectedModelId == "auto") {
                    currentModel = if (step <= 2) "nvidia/nemotron-3-ultra-550b-a55b:free" else "thinkingmachines/inkling:free"
                }
                
                agentLogs.add("🤖 Thinking (Step $step)...")
                val memoryWithLessons = longTermMemory + "\nGLOBAL ERRORS:\n" + globalLessons
                
                val aiResponse = aiAgent.runAgentStep(apiKey, currentModel, currentHistory, memoryWithLessons)
                
                if (aiResponse == "RETRY_ERROR" || aiResponse == null) {
                    fallbackIndex++
                    delay(5000)
                    step--
                    continue
                }
                
                if (aiResponse.contains("TASK_COMPLETE", ignoreCase = true)) {
                    aiProgress = 100
                    agentLogs.add("✅ Task Complete!")
                    break
                }
                
                var cleanCommand = aiResponse
                agentLogs.add("💻 Executing: $cleanCommand")
                currentHistory.put(JSONObject().put("role", "assistant").put("content", cleanCommand))
                
                val output = executeCommandAndWait(cleanCommand)
                agentLogs.add("📄 Output:\n$output")
                currentHistory.put(JSONObject().put("role", "user").put("content", "Output: $output"))
            }
            isAiRunning = false
        }
    }

    
    if (showPackageDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Select Packages", color = Color.Cyan) },
            text = {
                LazyColumn {
                    items(availablePackages) { pkg ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                            Checkbox(
                                checked = selectedPackages.contains(pkg), 
                                onCheckedChange = { if (it) selectedPackages.add(pkg) else selectedPackages.remove(pkg) }
                            )
                            Text(pkg)
                        }
                    }
                }
            },
            confirmButton = { 
                Button(onClick = { 
                    showPackageDialog = false; 
                    isInstalling = true; 
                    startTerminal(selectedPackages.toList()) 
                }) { Text("Install") } 
            },
            dismissButton = { 
                TextButton(onClick = { 
                    setupDoneFile.parentFile?.mkdirs(); 
                    setupDoneFile.createNewFile(); 
                    prefs.edit().putBoolean("skip_setup", true).apply(); 
                    showPackageDialog = false; 
                    startTerminal(emptyList()) 
                }) { Text("Skip") } 
            }
        )
    }

    if (showAiDialog) {
        AlertDialog(
            onDismissRequest = { if (!isAiRunning) showAiDialog = false },
            containerColor = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(16.dp),
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AI Agent", color = Color.Cyan, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (!isAiRunning) {
                        OutlinedTextField(
                            value = aiPrompt,
                            onValueChange = { aiPrompt = it },
                            placeholder = { Text("درخواست خود را بنویسید...", color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
                        ) { Text("شروع", fontSize = 16.sp) }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Cyan, strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("در حال اجرا... ($aiProgress%)", color = Color.Yellow)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("گزارش زنده:", color = Color.LightGray, fontSize = 12.sp)
                    LazyColumn(modifier = Modifier.height(200.dp).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)) {
                        items(agentLogs) { log -> Text(log, color = Color(0xFF00FF00), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            },
            confirmButton = { if (!isAiRunning) TextButton(onClick = { showAiDialog = false }) { Text("بستن", color = Color.Cyan) } }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings", color = Color.Cyan) },
            text = {
                Column {
                    Text("API Key (Only for API models):", color = Color.Cyan)
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it }, 
                        placeholder = { Text("ng-...") }, modifier = Modifier.fillMaxWidth(), 
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Active Model:", color = Color.Cyan)
                    Box {
                        OutlinedButton(onClick = { showModelDropdown = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(availableModels.find { it.first == selectedModelId }?.second ?: "Select")
                        }
                        DropdownMenu(expanded = showModelDropdown, onDismissRequest = { showModelDropdown = false }) {
                            availableModels.forEach { (id, name) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    selectedModelId = id
                                    prefs.edit().putString("selected_model", id).apply()
                                    showModelDropdown = false
                                })
                            }
                        }
                    }
                    Button(onClick = { prefs.edit().putString("api_key", apiKey).apply(); Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() }, modifier = Modifier.padding(top = 12.dp)) { Text("ذخیره کلید") }
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
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAiDialog = true },
                containerColor = Color(0xFF6200EE),
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Face, "AI") },
                text = { Text("AI Agent") }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(8.dp)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(outputLines) { line -> SelectionContainer { Text(text = line, color = textColor, fontFamily = FontFamily.Monospace, fontSize = fontSize) } }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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

// 👇 Local AI 👇
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LocalAIScreen(state: LocalAIState, scope: CoroutineScope) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Text("Local AI (Offline)", color = Color.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Local AI needs a .gguf model in /sdcard/Download/Local_AI_Models", color = Color.Yellow, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.chatHistory) { (role, msg) ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(text = "$role: $msg", color = Color.White, modifier = Modifier.padding(12.dp))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message local AI...") })
            Button(onClick = { /* Local Chat Logic */ inputText = "" }, modifier = Modifier.padding(start = 8.dp)) { Text("Send") }
        }
    }
}

// 👇 Helpers 👇
fun cleanAnsi(text: String): String {
    var cleaned = text
    cleaned = cleaned.replace(Regex("""\u001B\[[0-9;?]*[a-zA-Z]"""), "")
    cleaned = cleaned.replace(Regex("""\u001B\([a-zA-Z0-9]"""), "")
    cleaned = cleaned.replace(Regex("""[\u000E\u000F]"""), "")
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
