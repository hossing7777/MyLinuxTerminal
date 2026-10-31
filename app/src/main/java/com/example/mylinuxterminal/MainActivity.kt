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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
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
    val logs: List<String>
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
    "nvidia/nemotron-3-ultra-550b-a55b:free" to "Nemotron 3 Ultra"
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
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Home, "Terminal", tint = Color.Cyan) }, label = { Text("Terminal", color = Color.Cyan) })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Settings, "Local AI", tint = Color.Cyan) }, label = { Text("Local AI", color = Color.Cyan) })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Search, "Browser", tint = Color.Cyan) }, label = { Text("Browser", color = Color.Cyan) })
                NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.List, "Files", tint = Color.Cyan) }, label = { Text("Files", color = Color.Cyan) })
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().zIndex(if (selectedTab == 0) 1f else 0f)) { TerminalScreen() }
                Box(modifier = Modifier.fillMaxSize().zIndex(if (selectedTab == 1) 1f else 0f)) { LocalAIScreen(localAiState, mainScope) }
                Box(modifier = Modifier.fillMaxSize().zIndex(if (selectedTab == 2) 1f else 0f)) { BrowserScreen() }
                Box(modifier = Modifier.fillMaxSize().zIndex(if (selectedTab == 3) 1f else 0f)) { FileViewerScreen() }
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
    val dbHelper = remember { MemoryDbHelper(context) }
    
    var isExtracted by remember { mutableStateOf(false) }
    var showPackageDialog by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }

    var inputText by remember { mutableStateOf("") }
    var fontSizeFloat by remember { mutableStateOf(14f) }
    val fontSize = fontSizeFloat.sp
    var textColor by remember { mutableStateOf(Color(0xFF00FF00)) }
    var bgColor by remember { mutableStateOf(Color(0xFF121212)) }
    var showSettings by remember { mutableStateOf(false) }
    
    var showAiDialog by remember { mutableStateOf(false) }
    var aiPrompt by remember { mutableStateOf("") }
    var isAiRunning by remember { mutableStateOf(false) }
    var agentJob by remember { mutableStateOf<Job?>(null) }
    var aiProgress by remember { mutableStateOf(0) }
    var agentViewMode by remember { mutableStateOf(0) }
    var currentPlan by remember { mutableStateOf("") }
    
    var currentChatId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val chatList = remember { mutableStateListOf<ChatSession>() }
    val chatsDir = remember { File("/sdcard/Download/Local_AI_Models/Agent_Chats").apply { mkdirs() } }
    val agentLogs = remember { mutableStateListOf<String>() }
    
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var selectedModelId by remember { mutableStateOf(prefs.getString("selected_model", "shell_agent") ?: "shell_agent") }
    var showModelDropdown by remember { mutableStateOf(false) }
    
    val ptyProcess = remember { PtyProcess() }
    val outputLines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val setupDoneFile = remember { File(context.filesDir, "alpine/.setup_done") }
    val availablePackages = listOf("python3", "git", "nano", "bash", "curl", "wget", "nodejs", "vim", "htop", "net-tools", "procps")
    val selectedPackages = remember { mutableStateListOf("python3", "git", "nano", "bash", "net-tools", "procps") }
    
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                if (content != null) aiPrompt += "\n\n[Attached File Content]:\n$content"
            } catch (e: Exception) {}
        }
    }
    
    val manageStorageLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) {}
    
    fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${context.packageName}")
            manageStorageLauncher.launch(intent)
        }
    }

    fun loadAllChats() {
        if (!chatsDir.exists()) chatsDir.mkdirs()
        chatList.clear()
        chatsDir.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val logFile = File(dir, "chat_history.txt")
            if (logFile.exists()) {
                try {
                    val logs = logFile.readLines()
                    val name = dir.name
                    val parts = name.split("_", limit = 3)
                    val title = if (parts.size == 3) parts[2].replace("_", " ") else name
                    chatList.add(ChatSession(id = dir.absolutePath, title = title, timestamp = dir.lastModified(), logs = logs))
                } catch (e: Exception) {}
            }
        }
        chatList.sortByDescending { it.timestamp }
    }

    fun saveCurrentChat() {
        if (agentLogs.isEmpty()) return
        try {
            val maxSize = 100 * 1024 * 1024
            if (agentLogs.sumOf { it.length } > maxSize) {
                while (agentLogs.sumOf { it.length } > maxSize && agentLogs.size > 100) { agentLogs.removeAt(0) }
            }
            val titleRaw = agentLogs.firstOrNull { it.contains("Request:") }?.replace("📝 Request: ", "")?.take(20) ?: "New_Chat"
            val safeTitle = titleRaw.replace(Regex("[^a-zA-Z0-9_\\-آ-ی]"), "_")
            
            var chatFolder: File
            if (currentChatId.startsWith("/")) { chatFolder = File(currentChatId) } 
            else {
                val now = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                chatFolder = File(chatsDir, "${now}_${safeTitle}")
                currentChatId = chatFolder.absolutePath
            }
            
            chatFolder.mkdirs()
            File(chatFolder, "chat_history.txt").writeText(agentLogs.joinToString("\n"))
            loadAllChats()
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun deleteChat(chatId: String) {
        try {
            val dir = File(chatId)
            if (dir.exists() && dir.isDirectory) dir.deleteRecursively()
            if (currentChatId == chatId) {
                agentLogs.clear()
                currentChatId = UUID.randomUUID().toString()
            }
            loadAllChats()
        } catch (e: Exception) {}
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
                val buf = ByteArray(65536)
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

                        withContext(Dispatchers.IO) {
                            try {
                                val logDir = File("/sdcard/Download/Local_AI_Models")
                                logDir.mkdirs()
                                File(logDir, "terminal_history.txt").appendText(cleanText)
                            } catch (e: Exception) {}
                        }

                        withContext(Dispatchers.Main) {
                            if (cleanText.contains("2J")) outputLines.clear()
                            
                            val rawLines = cleanText.split("\n")
                            for (rawLine in rawLines) {
                                if (rawLine.isBlank()) continue
                                val subLines = rawLine.split("\r")
                                for (subLine in subLines) {
                                    var trimmed = subLine.trim()
                                    if (trimmed.isBlank() || trimmed.contains("::CMD_DONE_")) continue
                                    if (trimmed.matches(Regex("^[0-9]$"))) continue
                                    if (trimmed.contains("█") || (trimmed.contains("%") && trimmed.length < 40)) {
                                        if (outputLines.isNotEmpty() && (outputLines.last().contains("█") || outputLines.last().contains("%"))) {
                                            outputLines[outputLines.lastIndex] = trimmed
                                        } else {
                                            outputLines.add(trimmed)
                                        }
                                    } else {
                                        outputLines.add(trimmed)
                                    }
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
        loadAllChats()
        extractLinux(context, mutableListOf<String>())
        isExtracted = true
        checkStoragePermission()
        if (setupDoneFile.exists()) startTerminal(emptyList()) else showPackageDialog = true
    }

    fun runShellAgent(userRequest: String) {
        isAiRunning = true
        aiProgress = 5
        agentLogs.clear()
        agentLogs.add("🚀 Autonomous Shell Agent Started...")
        agentLogs.add("📝 Request: $userRequest")
        
        val pastKnowledge = dbHelper.getMemoryContext()
        if (pastKnowledge.isNotBlank()) agentLogs.add("🧠 Loaded Long-Term Memory.")
        
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
                var currentPrompt = "PAST KNOWLEDGE:\n$pastKnowledge\n\nTASK: $userRequest.\n\nINSTRUCTIONS:\n1. Write a Python script to accomplish the task.\n2. Reply ONLY with the python code inside a markdown block."
                
                while (iteration < 7 && isAiRunning) {
                    iteration++
                    aiProgress = 10 + (iteration * 10)
                    agentLogs.add("🔄 Attempt $iteration/7...")
                    
                    agentLogs.add("⌨️ Sending prompt to AI...")
                    BrowserBridge.typeText(currentPrompt)
                    delay(1000)
                    BrowserBridge.clickSend()
                    
                    agentLogs.add("⏳ Waiting for response (15s)...")
                    delay(15000)
                    
                    agentLogs.add("📖 Extracting code...")
                    var rawCode = BrowserBridge.extractCode()
                    rawCode = rawCode.replace("\\u003C", "<").replace("\\u003E", ">").replace("&lt;", "<").replace("&gt;", ">").replace("\\u0026", "&")
                    
                    if (rawCode == "NO_CODE_FOUND" || rawCode.isBlank() || rawCode.contains("Error: Browser not ready")) {
                        agentLogs.add("⚠️ No valid code found. Retrying...")
                        currentPrompt = "Please provide a valid Python code block for: $userRequest"
                        continue
                    }
                    
                    agentLogs.add("📜 Code Extracted. Writing to disk...")
                    val scriptFile = File(context.filesDir, "ai_script.py")
                    scriptFile.writeText(rawCode)
                    
                    agentLogs.add("▶️ Executing Python script...")
                    val cmd = "if ! command -v python3 >/dev/null 2>&1; then apk add python3 >/dev/null 2>&1; fi; python3 /host_files/ai_script.py 2>&1"
                    
                    val output = executeCommandAndWait(cmd)
                    
                    val cleanOutputForAI = output.lines().filter { 
                        !it.contains("stty -echo") && !it.contains("::CMD_DONE") && 
                        !it.contains("base64 -d") && !it.contains("apk add python3") && it.isNotBlank() 
                    }.joinToString("\n")
                    
                    var verificationPassed = true
                    var verificationLog = ""
                    
                    val pathRegex = Regex("""(/sdcard/Download/[a-zA-Z0-9_./-]+)""")
                    val pathsToVerify = pathRegex.findAll(userRequest).map { it.value }.toList()
                    
                    if (pathsToVerify.isNotEmpty()) {
                        agentLogs.add("🔍 Verifying outputs at requested paths...")
                        for (path in pathsToVerify) {
                            val lsOutput = executeCommandAndWait("ls -la $path 2>&1")
                            if (lsOutput.contains("No such file or directory") || lsOutput.contains("not found") || lsOutput.isBlank()) {
                                verificationPassed = false
                                verificationLog += "❌ Path not found or empty: $path\n"
                            } else {
                                verificationLog += "✅ Path exists: $path\n$lsOutput\n"
                            }
                        }
                    }

                    val hasTerminalError = output.contains("Traceback", true) || output.contains("SyntaxError", true) || 
                                           output.contains("NameError", true) || output.contains("not found", true) || 
                                           output.contains("Error:", true) || output.contains("Exception", true) || 
                                           output.contains("Timed Out", true)

                    if (hasTerminalError || !verificationPassed) {
                        if (output.contains("Timed Out", true)) {
                            agentLogs.add("❌ Timeout detected! Asking AI to simplify...")
                            currentPrompt = "Your previous code TIMED OUT. Simplify it and use os.makedirs(directory, exist_ok=True) at the beginning. Reply ONLY with code."
                        } else if (!verificationPassed) {
                            agentLogs.add("❌ Verification Failed: Files were not created!")
                            currentPrompt = "I ran your code, but files were NOT created.\nOutput:\n$cleanOutputForAI\nVerification:\n$verificationLog\nUse os.makedirs and fix the code. Reply ONLY with code."
                        } else {
                            agentLogs.add("❌ Terminal Error detected! Asking AI to fix...")
                            currentPrompt = "The code failed with this error:\n$cleanOutputForAI\n\nFix the error and reply with the COMPLETE corrected Python code."
                        }
                    } else {
                        agentLogs.add("✅ Task completed successfully!")
                        dbHelper.addMemory("SUCCESS", "Task: $userRequest")
                        saveCurrentChat()
                        aiProgress = 100
                        isAiRunning = false
                        return@launch
                    }
                }
                
                if (isAiRunning) {
                    agentLogs.add("🛑 Max attempts reached. Stopping.")
                    dbHelper.addMemory("FAILED", "Task: $userRequest (Max attempts)")
                    saveCurrentChat()
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
        if (apiKey.isBlank()) {
            agentLogs.add("❌ API Key is missing. Please login from ⚙️ Settings.")
            showSettings = true
            return
        }
        isAiRunning = true
        agentLogs.add("🔐 Connecting to API...")
        agentJob = coroutineScope.launch {
            val authTest = AiAgent().testConnection(apiKey, "nvidia/nemotron-3-ultra-550b-a55b:free")
            if (!authTest.contains("✅")) {
                agentLogs.add("❌ Auth failed: $authTest")
                isAiRunning = false
                return@launch
            }
            agentLogs.add("✅ Connected.")
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
                Column {
                    TabRow(selectedTabIndex = agentViewMode, containerColor = Color(0xFF1E1E1E), contentColor = Color.Cyan) {
                        Tab(selected = agentViewMode == 0, onClick = { agentViewMode = 0 }, text = { Text("Chat") })
                        Tab(selected = agentViewMode == 1, onClick = { agentViewMode = 1 }, text = { Text("History") })
                        Tab(selected = agentViewMode == 2, onClick = { agentViewMode = 2 }, text = { Text("Plan") })
                        Tab(selected = agentViewMode == 3, onClick = { agentViewMode = 3 }, text = { Text("Memory") })
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    if (agentViewMode == 0) {
                        if (!isAiRunning) {
                            OutlinedTextField(
                                value = aiPrompt, onValueChange = { aiPrompt = it },
                                placeholder = { Text("درخواست خود را بنویسید...", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { filePickerLauncher.launch("*/*") }) { Icon(Icons.Default.Add, "Attach", tint = Color.Cyan) }
                                Button(onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("شروع", fontSize = 16.sp) }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Cyan, strokeWidth = 3.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("در حال اجرا... ($aiProgress%)", color = Color.Yellow)
                            }
                        }
                    } else if (agentViewMode == 1) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Chat History:", color = Color.Yellow, fontSize = 14.sp)
                            Button(onClick = { saveCurrentChat(); agentLogs.clear(); currentChatId = UUID.randomUUID().toString(); agentViewMode = 0 }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006600))) { Text("+ New Chat", fontSize = 12.sp) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                            items(chatList) { chat ->
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f).clickable {
                                            agentLogs.clear(); agentLogs.addAll(chat.logs)
                                            outputLines.clear(); outputLines.addAll(chat.logs)
                                            currentChatId = chat.id
                                            showAiDialog = false
                                            coroutineScope.launch { listState.animateScrollToItem(maxOf(0, outputLines.size - 1)) }
                                        }) {
                                            Text(chat.title, color = Color.Cyan, fontWeight = FontWeight.Bold, maxLines = 1)
                                            Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(chat.timestamp)), color = Color.Gray, fontSize = 10.sp)
                                        }
                                        IconButton(onClick = { deleteChat(chat.id) }) { Icon(Icons.Default.Delete, "Delete", tint = Color.Red) }
                                    }
                                }
                            }
                        }
                    } else if (agentViewMode == 2) {
                        Text("Current Plan:", color = Color.Yellow, fontSize = 12.sp)
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black).padding(8.dp).verticalScroll(rememberScrollState())) {
                            Text(if (currentPlan.isBlank()) "No active plan." else currentPlan, color = Color.Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else if (agentViewMode == 3) {
                        Text("Long-Term Memory:", color = Color.Yellow, fontSize = 12.sp)
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black).padding(8.dp).verticalScroll(rememberScrollState())) {
                            Text(dbHelper.getMemoryContext(), color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("گزارش زنده:", color = Color.LightGray, fontSize = 12.sp)
                        TextButton(onClick = { agentLogs.clear() }) { Text("پاک کردن", color = Color.Red, fontSize = 12.sp) }
                    }
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
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, placeholder = { Text("ng-...") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Active Model:", color = Color.Cyan)
                    Box {
                        OutlinedButton(onClick = { showModelDropdown = true }, modifier = Modifier.fillMaxWidth()) { Text(availableModels.find { it.first == selectedModelId }?.second ?: "Select") }
                        DropdownMenu(expanded = showModelDropdown, onDismissRequest = { showModelDropdown = false }) {
                            availableModels.forEach { (id, name) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { selectedModelId = id; prefs.edit().putString("selected_model", id).apply(); showModelDropdown = false })
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
                    IconButton(onClick = { 
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Terminal", outputLines.joinToString("\n"))
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.Share, "Copy All", tint = Color.White) }
                    IconButton(onClick = { outputLines.clear() }) { Icon(Icons.Default.Delete, "Clear", tint = Color.White) }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAiDialog = true }, containerColor = Color(0xFF6200EE), contentColor = Color.White, icon = { Icon(Icons.Default.Face, "AI") }, text = { Text("AI Agent") })
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(8.dp)) {
            LazyColumn(
                state = listState, 
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            fontSizeFloat = (fontSizeFloat * zoom).coerceIn(8f, 30f)
                        }
                    }
            ) {
                items(outputLines) { line -> 
                    SelectionContainer { 
                        Text(text = line, color = textColor, fontFamily = FontFamily.Monospace, fontSize = fontSize) 
                    } 
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "$ ", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontSize = fontSize, fontWeight = FontWeight.Bold)
                BasicTextField(
                    value = inputText, onValueChange = { inputText = it },
                    textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = fontSize),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester), singleLine = true, enabled = isExtracted,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { 
                        val cmd = inputText.trim()
                        if (cmd == "clear" || cmd == "cls") { outputLines.clear(); sendToPty("clear\n") } 
                        else { sendToPty(inputText + "\n") }
                        inputText = ""
                        focusRequester.requestFocus() 
                    })
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen() {
    var files by remember { mutableStateOf(listOf<File>()) }
    var selectedFileContent by remember { mutableStateOf<String?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var currentPath by remember { mutableStateOf("/sdcard/Download") }
    var previewAsHtml by remember { mutableStateOf(false) }

    LaunchedEffect(currentPath) {
        val dir = File(currentPath)
        if (dir.exists() && dir.isDirectory) files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        else files = File("/sdcard/Download").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            IconButton(onClick = { val parent = File(currentPath).parentFile; if (parent != null && parent.exists()) currentPath = parent.absolutePath }) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.Cyan) }
            Column {
                Text("File Viewer", color = Color.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Path: $currentPath", color = Color.Gray, fontSize = 12.sp)
            }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(files) { file ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                    if (file.isDirectory) currentPath = file.absolutePath
                    else { try { selectedFileContent = file.readText(); selectedFileName = file.name; previewAsHtml = false } catch (e: Exception) { selectedFileContent = "Error: ${e.message}"; selectedFileName = file.name } }
                }, colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (file.isDirectory) "📁 " else "📄 ", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column { Text(file.name, color = Color.White, fontWeight = FontWeight.Bold); Text("${file.length()} bytes", color = Color.Gray, fontSize = 10.sp) }
                    }
                }
            }
        }
        if (selectedFileContent != null) {
            AlertDialog(
                onDismissRequest = { selectedFileContent = null },
                title = { Text(selectedFileName, color = Color.Cyan) },
                text = {
                    if (previewAsHtml && selectedFileName.endsWith(".html")) {
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { ctx -> android.webkit.WebView(ctx).apply { settings.javaScriptEnabled = true; setBackgroundColor(android.graphics.Color.DKGRAY); loadDataWithBaseURL(null, selectedFileContent ?: "", "text/html", "UTF-8", null) } },
                            modifier = Modifier.fillMaxWidth().height(400.dp)
                        )
                    } else {
                        Box(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text(selectedFileContent ?: "", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                },
                confirmButton = { Row { if (selectedFileName.endsWith(".html")) TextButton(onClick = { previewAsHtml = !previewAsHtml }) { Text(if (previewAsHtml) "Source" else "Render", color = Color.Yellow) }; TextButton(onClick = { selectedFileContent = null }) { Text("Close", color = Color.Cyan) } } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LocalAIScreen(state: LocalAIState, scope: CoroutineScope) {
    var inputText by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Text("Local AI (Offline)", color = Color.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.chatHistory) { (role, msg) -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(text = "$role: $msg", color = Color.White, modifier = Modifier.padding(12.dp)) } }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message local AI...") })
            Button(onClick = { inputText = "" }, modifier = Modifier.padding(start = 8.dp)) { Text("Send") }
        }
    }
}

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
        try { alpineDir.mkdirs(); context.assets.open(assetName).use { input -> extractTarSmart(input, alpineDir) } } catch (e: Exception) { Log.e("Terminal", "Extract Error", e) }
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
            if (!destFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) { entry = tarIn.nextEntry; continue }
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

// Build Trigger: 57467c62

// Build Trigger: b9436513
