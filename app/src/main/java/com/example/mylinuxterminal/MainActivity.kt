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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

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
    var isExtracted by remember { mutableStateOf(false) }
    var showPackageDialog by remember { mutableStateOf(false) }
    
    var isInstalling by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf(0f) }
    var installStatus by remember { mutableStateOf("") }
    var isTerminalRunning by remember { mutableStateOf(false) }

    var inputText by remember { mutableStateOf("") }
    var fontSize by remember { mutableStateOf(14.sp) }
    var textColor by remember { mutableStateOf(Color.Green) }
    var bgColor by remember { mutableStateOf(Color.Black) }
    var showSettings by remember { mutableStateOf(false) }
    
    val ptyProcess = remember { PtyProcess() }
    var ptyFd by remember { mutableStateOf(-1) }
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
        outputLines.add("--- Copied ---")
    }

    fun resetApp() {
        coroutineScope.launch(Dispatchers.IO) {
            File(context.filesDir, "alpine").deleteRecursively()
            withContext(Dispatchers.Main) {
                outputLines.clear()
                outputLines.add("App data cleared. Restarting...")
                isExtracted = false
                isTerminalRunning = false
                showSettings = false
                coroutineScope.launch { extractLinux(context, outputLines) }
            }
        }
    }

    // تابع ارسال دستور به PTY و حفظ فوکوس
    fun sendToPty(data: String) {
        if (ptyFd > 0) {
            ptyProcess.writeData(data)
        }
        // اجباد به باز نگه داشتن کیبورد
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    fun startTerminal(packagesToInstall: List<String> = emptyList()) {
        if (isTerminalRunning) return
        isTerminalRunning = true

        coroutineScope.launch(Dispatchers.IO) {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootPath = File(nativeLibDir, "libproot.so").absolutePath
            val loaderPath = File(nativeLibDir, "libproot_loader.so").absolutePath
            val alpinePath = File(context.filesDir, "alpine").absolutePath
            val tmpDir = File(context.cacheDir, "proot_tmp").absolutePath
            File(tmpDir).mkdirs()
            
            val initialCmd = if (!setupDoneFile.exists() && packagesToInstall.isNotEmpty()) {
                "apk update && apk add ${packagesToInstall.joinToString(" ")} && touch /.setup_done && echo '::DONE::'"
            } else {
                "echo '::DONE::'"
            }

            val scriptFile = File(context.filesDir, "start.sh")
            scriptFile.writeText("""
                $prootPath --kill-on-exit --link2symlink --sysvipc -0 -r $alpinePath -w / -b /dev -b /proc -b /sys -b /storage/emulated/0:/sdcard /bin/sh -c "$initialCmd; exec /bin/sh"
            """.trimIndent())
            scriptFile.setExecutable(true)

            withContext(Dispatchers.Main) { 
                outputLines.clear()
                outputLines.add("Starting terminal engine...")
            }

            ptyFd = ptyProcess.createPty(
                scriptFile.absolutePath, prootPath, alpinePath, tmpDir, loaderPath, nativeLibDir, context.filesDir.absolutePath
            )
            
            if (ptyFd > 0) {
                val buf = ByteArray(4096)
                val regex = Regex("""\((\d+)/(\d+)\)""")
                val buffer = StringBuilder()

                while (true) {
                    val len = ptyProcess.readData(buf)
                    if (len > 0) {
                        val text = String(buf, 0, len)
                        val cleanText = cleanAnsi(text) // 👈 پاکسازی کدهای رنگی

                        buffer.append(cleanText)

                        while (buffer.contains("\n")) {
                            val idx = buffer.indexOf("\n")
                            val line = buffer.substring(0, idx)
                            buffer.delete(0, idx + 1)

                            withContext(Dispatchers.Main) {
                                if (line.contains("::DONE::")) {
                                    isInstalling = false
                                    installProgress = 1f
                                    installStatus = "Ready!"
                                } else {
                                    val match = regex.find(line)
                                    if (match != null && isInstalling) {
                                        val current = match.groupValues[1].toIntOrNull() ?: 0
                                        val total = match.groupValues[2].toIntOrNull() ?: 1
                                        if (total > 0) installProgress = current.toFloat() / total.toFloat()
                                        installStatus = "Installing ($current/$total)..."
                                    }
                                    if (line.isNotBlank()) {
                                        outputLines.add(line)
                                        listState.animateScrollToItem(maxOf(0, outputLines.size - 1))
                                    }
                                }
                            }
                        }
                        // اگر پرامپت بود، نمایش بده
                        if (buffer.contains("# ") || buffer.contains("$ ")) {
                            withContext(Dispatchers.Main) {
                                outputLines.add(buffer.toString())
                                buffer.clear()
                                listState.animateScrollToItem(maxOf(0, outputLines.size - 1))
                            }
                        }
                    } else break
                }
                withContext(Dispatchers.Main) { outputLines.add("Terminal process ended.") }
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

    if (showPackageDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Select Packages (First Run)") },
            text = {
                LazyColumn {
                    items(availablePackages) { pkg ->
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = selectedPackages.contains(pkg),
                                onCheckedChange = { isChecked ->
                                    if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
                                }
                            )
                            Text(pkg)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { 
                    showPackageDialog = false
                    isInstalling = true
                    startTerminal(selectedPackages.toList()) 
                }) { Text("Install & Start") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPackageDialog = false
                    startTerminal(emptyList())
                }) { Text("Skip") }
            }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column {
                    Text("Font Size: ${fontSize.value.toInt()}sp")
                    Slider(value = fontSize.value, onValueChange = { fontSize = it.sp }, valueRange = 8f..36f)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Text Color:")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { textColor = Color.Green }) { Text("Green") }
                        Button(onClick = { textColor = Color.White }) { Text("White") }
                        Button(onClick = { textColor = Color.Yellow }) { Text("Yellow") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Background:")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { bgColor = Color.Black }) { Text("Black") }
                        Button(onClick = { bgColor = Color(0xFF1A1A1A) }) { Text("Dark") }
                        Button(onClick = { bgColor = Color(0xFF001F3F) }) { Text("Navy") }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { resetApp() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) { Text("Reset App (Reinstall)") }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Close") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(bgColor).padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { copyAllText() }) { Text("Copy All", color = Color.Cyan, fontSize = 12.sp) }
            TextButton(onClick = { showSettings = true }) { Text("⚙️ Settings", color = Color.Cyan, fontSize = 12.sp) }
        }

        if (isInstalling) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(installStatus, color = Color.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = installProgress,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Green,
                    trackColor = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text("${(installProgress * 100).toInt()}%", color = Color.Green, fontSize = 10.sp)
            }
        }
        
        LazyColumn(
            state = listState, 
            modifier = Modifier.weight(1f).pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    fontSize = (fontSize.value * zoom).coerceIn(8f, 36f).sp
                }
            }
        ) {
            items(outputLines) { line -> 
                SelectionContainer { Text(text = line, color = textColor, fontFamily = FontFamily.Monospace, fontSize = fontSize) }
            }
        }
        
        // 👇 دکمه‌های کمکی با طراحی بهتر
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isCtrlPressed) {
                val ctrlKeys = listOf("C (Cancel)" to "\u0003", "D (Exit)" to "\u0004", "Z" to "\u001a")
                ctrlKeys.forEach { (label, code) ->
                    Button(
                        onClick = { sendToPty(code); isCtrlPressed = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFcc0000)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text(text = label, color = Color.White, fontSize = 10.sp) }
                }
                Button(
                    onClick = { isCtrlPressed = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("X", color = Color.White, fontSize = 10.sp) }
            } else {
                Button(
                    onClick = { isCtrlPressed = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("CTRL", color = Color.White, fontSize = 10.sp) }

                val keys = listOf(
                    "ESC" to "\u001b", "TAB" to "\t", "/" to "/", "-" to "-",
                    "UP" to "\u001b[A", "DOWN" to "\u001b[B", "LEFT" to "\u001b[D", "RIGHT" to "\u001b[C"
                )
                keys.forEach { (label, code) ->
                    Button(
                        onClick = { sendToPty(code) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text(text = label, color = Color.White, fontSize = 10.sp) }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "$ ", color = textColor, fontFamily = FontFamily.Monospace, fontSize = fontSize)
            BasicTextField(
                value = inputText, 
                onValueChange = { inputText = it },
                textStyle = TextStyle(color = textColor, fontFamily = FontFamily.Monospace, fontSize = fontSize),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true, 
                enabled = isExtracted,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { 
                    sendToPty(inputText + "\n")
                    inputText = ""
                    focusRequester.requestFocus()
                })
            )
        }
    }
}

// 👇 تابع پاکسازی کدهای ANSI
fun cleanAnsi(text: String): String {
    val ansiRegex = Regex("""\u001B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])""")
    return text.replace(ansiRegex, "")
}

suspend fun extractLinux(context: Context, outputLines: MutableList<String>) = withContext(Dispatchers.IO) {
    val alpineDir = File(context.filesDir, "alpine")
    val assets = context.assets.list("") ?: emptyArray()
    val assetName = if (assets.contains("alpine.tar.gz")) "alpine.tar.gz" else "alpine.tar"

    if (!alpineDir.exists() || alpineDir.listFiles()?.isEmpty() == true) {
        try {
            alpineDir.mkdirs()
            context.assets.open(assetName).use { input -> extractTarSmart(input, alpineDir) }
            withContext(Dispatchers.Main) { outputLines.add("Extraction successful!") }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { outputLines.add("Extract Error: ${e.toString()}") }
        }
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
        GzipCompressorInputStream(bufferedInputStream).use { gzipIn ->
            TarArchiveInputStream(gzipIn).use { tarIn -> extractEntries(tarIn, destDir) }
        }
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
                        val perms = buildString {
                            for (c in octal) {
                                val v = Character.digit(c, 8)
                                append(if (v and 4 != 0) "r" else "-")
                                append(if (v and 2 != 0) "w" else "-")
                                append(if (v and 1 != 0) "x" else "-")
                            }
                        }
                        Files.setPosixFilePermissions(Paths.get(destFile.absolutePath), PosixFilePermissions.fromString(perms))
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { Log.e("Terminal", "Error extracting", e) }
        entry = tarIn.nextEntry
    }
}
