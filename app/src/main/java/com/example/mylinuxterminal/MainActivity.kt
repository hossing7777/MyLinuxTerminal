package com.example.mylinuxterminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face // آیکون استاندارد
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share // آیکون استاندارد
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

class TerminalState {
    var isWaitingForCommand = false
    var currentMarker = ""
    val commandOutputBuffer = StringBuilder()
    var commandDeferred: CompletableDeferred<String>? = null
    var ptyFd = -1
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalScreen() }
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
    var aiPrompt by remember { mutableStateOf("") }
    var isAiRunning by remember { mutableStateOf(false) }
    var agentJob by remember { mutableStateOf<Job?>(null) }
    
    // 👇 متغیر درصد پیشرفت
    var aiProgress by remember { mutableStateOf(0) }
    
    val agentLogs = remember { 
        val logs = mutableStateListOf<String>()
        val saved = prefs.getString("agent_logs", "[]")
        try {
            val arr = JSONArray(saved)
            for (i in 0 until arr.length()) logs.add(arr.getString(i))
        } catch (e: Exception) {}
        logs
    }
    
    var longTermMemory by remember { mutableStateOf(prefs.getString("lt_memory", "No successful commands yet.") ?: "") }
    
    val aiAgent = remember { AiAgent() }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    
    val ptyProcess = remember { PtyProcess() }
    val outputLines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val availablePackages = listOf("python3", "git", "nano", "bash", "curl", "wget", "nodejs", "vim", "htop")
    val selectedPackages = remember { mutableStateListOf("python3", "git", "nano") }
    var isCtrlPressed by remember { mutableStateOf(false) }
    
    val setupDoneFile = remember { File(context.filesDir, "alpine/.setup_done") }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {}

    fun saveHistory() {
        val arr = JSONArray()
        agentLogs.forEach { arr.put(it) }
        prefs.edit().putString("agent_logs", arr.toString()).apply()
    }

    fun updateMemory(cmd: String) {
        if (!longTermMemory.contains(cmd)) {
            longTermMemory += "\n- $cmd"
            prefs.edit().putString("lt_memory", longTermMemory).apply()
        }
    }

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

    // 👇 تایم‌اوت به ۲ دقیقه (۱۲۰,۰۰۰ میلی‌ثانیه) افزایش یافت
    suspend fun executeCommandAndWait(command: String): String {
        return withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<String>()
            terminalState.commandDeferred = deferred
            terminalState.currentMarker = "::CMD_DONE_${System.currentTimeMillis()}::"
            terminalState.commandOutputBuffer.clear()
            terminalState.isWaitingForCommand = true
            
            ptyProcess.writeData("stty -echo; $command 2>&1; echo \"${terminalState.currentMarker}\"; stty echo\n")
            
            try {
                withTimeout(120000) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                terminalState.isWaitingForCommand = false
                ptyProcess.writeData("\u0003")
                "Command Timed Out after 2 minutes. It was too slow. Please try a faster method or mirror."
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
            
            val initialCmd = if (!setupDoneFile.exists() && packagesToInstall.isNotEmpty()) {
                "apk update && apk add ${packagesToInstall.joinToString(" ")} && touch /.setup_done && echo '::SETUP_DONE::'"
            } else {
                "echo '::SETUP_DONE::'"
            }

            val scriptFile = File(context.filesDir, "start.sh")
            scriptFile.writeText("""
                $prootPath --kill-on-exit --link2symlink --sysvipc -0 -r $alpinePath -w / -b /dev -b /proc -b /sys -b /storage/emulated/0:/sdcard /bin/sh -c "$initialCmd; exec /bin/sh"
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
        extractLinux(context, outputLines)
        isExtracted = true
        if (setupDoneFile.exists()) {
            startTerminal(emptyList())
        } else {
            showPackageDialog = true
        }
        checkStoragePermission()
    }

    fun stopAgent() {
        agentJob?.cancel()
        sendToPty("\u0003")
        isAiRunning = false
        agentLogs.add("🛑 عملیات توسط کاربر متوقف شد.")
        saveHistory()
    }

    fun runAgent(userRequest: String) {
        isAiRunning = true
        aiProgress = 0
        agentLogs.add("📝 درخواست جدید: $userRequest")
        saveHistory()
        
        agentJob = coroutineScope.launch {
            val history = JSONArray()
            history.put(JSONObject().put("role", "user").put("content", userRequest))
            
            var step = 0
            val maxStepsPerBatch = 30
            
            while (isAiRunning) {
                step++
                if (step > maxStepsPerBatch) {
                    agentLogs.add("🔄 سقف مراحل پر شد. ارسال درخواست ادامه...")
                    saveHistory()
                    history.put(JSONObject().put("role", "user").put("content", "System Limit Reached. Please continue the task from where you left off. Output the next command."))
                    step = 1
                }

                agentLogs.add("🤖 در حال فکر کردن (مرحله $step)...")
                saveHistory()
                
                val aiResponse = aiAgent.runAgentStep(apiKey, history, longTermMemory)
                
                if (aiResponse == null || aiResponse.startsWith("Error")) {
                    agentLogs.add("❌ خطا در ارتباط: $aiResponse")
                    saveHistory()
                    break
                }
                
                if (aiResponse == "TASK_COMPLETE") {
                    aiProgress = 100
                    agentLogs.add("✅ عملیات با موفقیت به پایان رسید!")
                    saveHistory()
                    break
                }

                if (aiResponse.startsWith("{") && aiResponse.contains("plan")) {
                    agentLogs.add("🗺️ نقشه راه تولید شد:\n$aiResponse")
                    history.put(JSONObject().put("role", "assistant").put("content", aiResponse))
                    history.put(JSONObject().put("role", "user").put("content", "Plan received. Start executing step 1."))
                    saveHistory()
                    continue
                }

                // 👇 استخراج درصد پیشرفت از پاسخ هوش مصنوعی
                val progressRegex = Regex("""\[PROGRESS:\s*(\d+)%\]""")
                val match = progressRegex.find(aiResponse)
                if (match != null) {
                    aiProgress = match.groupValues[1].toIntOrNull() ?: aiProgress
                }
                
                // حذف تگ پیشرفت از دستور برای اجرا در ترمینال
                val cleanCommand = aiResponse.replace(progressRegex, "").trim()

                agentLogs.add("💻 اجرای دستور: $cleanCommand")
                saveHistory()
                
                val output = executeCommandAndWait(cleanCommand)
                
                if (!output.contains("Timed Out")) {
                    updateMemory(cleanCommand)
                }

                val displayOutput = if (output.isBlank()) "(بدون خروجی)" else output
                agentLogs.add("📄 خروجی ترمینال:\n$displayOutput")
                saveHistory()
                
                history.put(JSONObject().put("role", "assistant").put("content", aiResponse))
                history.put(JSONObject().put("role", "user").put("content", "The command output was:\n$output\n\nWhat is the next command? If the task is complete, output TASK_COMPLETE."))
            }
            isAiRunning = false
        }
    }

    if (showAiDialog) {
        AlertDialog(
            onDismissRequest = { if (!isAiRunning) showAiDialog = false },
            containerColor = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(16.dp),
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Face, contentDescription = "AI", tint = Color.Cyan, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI Agent", color = Color.Cyan, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    if (!isAiRunning) {
                        OutlinedTextField(
                            value = aiPrompt,
                            onValueChange = { aiPrompt = it },
                            placeholder = { Text("درخواست خود را بنویسید...", color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth(),
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
                            // 👇 نوار پیشرفت (Progress Bar)
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
                        TextButton(onClick = { agentLogs.clear(); saveHistory() }) { Text("پاک کردن", color = Color.Red, fontSize = 12.sp) }
                    }
                    LazyColumn(modifier = Modifier.height(220.dp).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)) {
                        items(agentLogs) { log -> Text(log, color = Color(0xFF00FF00), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp)) }
                    }
                }
            },
            confirmButton = { if (!isAiRunning) TextButton(onClick = { showAiDialog = false }) { Text("بستن", color = Color.Cyan) } }
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
            dismissButton = { TextButton(onClick = { showPackageDialog = false; startTerminal(emptyList()) }) { Text("Skip") } }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column {
                    Text("Hormuz API Key:", color = Color.Cyan)
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, placeholder = { Text("ng-...") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(onClick = { prefs.edit().putString("api_key", apiKey).apply(); Toast.makeText(context, "ذخیره شد", Toast.LENGTH_SHORT).show() }, modifier = Modifier.weight(1f)) { Text("ذخیره") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { isTesting = true; coroutineScope.launch { testResult = aiAgent.testConnection(apiKey); isTesting = false } }, modifier = Modifier.weight(1f)) { Text("تست") }
                    }
                    if (isTesting) Text("در حال تست...", color = Color.Yellow)
                    if (testResult.isNotEmpty()) Text(testResult, color = if (testResult.contains("✅")) Color.Green else Color.Red)
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Close") } }
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

fun cleanAnsi(text: String): String {
    var cleaned = text.replace(Regex("""\u001B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])"""), "")
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
