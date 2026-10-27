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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    var inputText by remember { mutableStateOf("") }
    var fontSize by remember { mutableStateOf(14.sp) }
    var textColor by remember { mutableStateOf(Color.Green) }
    var bgColor by remember { mutableStateOf(Color.Black) }
    var showSettings by remember { mutableStateOf(false) }
    
    val ptyProcess = remember { PtyProcess() }
    var ptyFd by remember { mutableStateOf(-1) }
    
    val outputLines = remember { mutableStateListOf("Welcome to MyLinux Terminal v9.0", "Initializing...") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            outputLines.add("Storage permission denied.")
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
        outputLines.add("--- Copied ---")
    }

    // شروع فرآیند PTY
    LaunchedEffect(isExtracted) {
        if (isExtracted && ptyFd <= 0) {
            withContext(Dispatchers.IO) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val prootPath = File(nativeLibDir, "libproot.so").absolutePath
                val loaderPath = File(nativeLibDir, "libproot_loader.so").absolutePath
                val alpinePath = File(context.filesDir, "alpine").absolutePath
                val tmpDir = File(alpinePath, "tmp")
                val scriptFile = File(context.filesDir, "start.sh")

                scriptFile.writeText("""
                    export PROOT_TMP_DIR=$tmpDir
                    export PROOT_NO_SECCOMP=1
                    export PROOT_LOADER=$loaderPath
                    export LD_LIBRARY_PATH=$nativeLibDir
                    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                    export HOME=/root
                    export TERM=xterm-256color
                    $prootPath --kill-on-exit --link2symlink --sysvipc -0 -r $alpinePath -w / -b /dev -b /proc -b /sys -b /storage/emulated/0:/sdcard /bin/sh
                """.trimIndent())
                scriptFile.setExecutable(true)

                ptyFd = ptyProcess.createPty()
                if (ptyFd > 0) {
                    ptyProcess.execCommand("sh ${scriptFile.absolutePath}")
                    val buf = ByteArray(4096)
                    while (true) {
                        val len = ptyProcess.readData(buf)
                        if (len > 0) {
                            val text = String(buf, 0, len)
                            withContext(Dispatchers.Main) { outputLines.add(text.trimEnd()) }
                        } else if (len < 0) break
                    }
                }
            }
        }
    }

    fun runCommand(cmd: String) {
        if (ptyFd <= 0) return
        ptyProcess.writeData(cmd + "\n")
    }

    suspend fun extractLinuxAndInstall() = withContext(Dispatchers.IO) {
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

        withContext(Dispatchers.Main) {
            outputLines.add("Installing essential packages (python3, git, nano)... This may take a minute.")
        }

        // نصب خودکار پکیج‌ها با ProcessBuilder (چون PTY هنوز آماده نیست)
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootPath = File(nativeLibDir, "libproot.so").absolutePath
            val loaderPath = File(nativeLibDir, "libproot_loader.so").absolutePath
            val tmpDir = File(alpineDir, "tmp")
            
            val pb = ProcessBuilder(
                prootPath, "--kill-on-exit", "--link2symlink", "--sysvipc", "-0",
                "-r", alpineDir.absolutePath, "-w", "/", "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "/storage/emulated/0:/sdcard", "/bin/sh", "-c", 
                "apk update && apk add python3 git nano bash curl wget"
            )
            val env = pb.environment()
            env["PROOT_TMP_DIR"] = tmpDir.absolutePath
            env["PROOT_NO_SECCOMP"] = "1"
            env["PROOT_LOADER"] = loaderPath
            env["LD_LIBRARY_PATH"] = nativeLibDir
            env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            env.remove("LD_PRELOAD")
            
            pb.redirectErrorStream(true)
            val process = pb.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                withContext(Dispatchers.Main) { outputLines.add(line) }
            }
            process.waitFor()
            withContext(Dispatchers.Main) { outputLines.add("Packages installed successfully!") }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { outputLines.add("Install Error: ${e.toString()}") }
        }

        withContext(Dispatchers.Main) {
            isExtracted = true
            focusRequester.requestFocus()
            keyboardController?.show()
            checkStoragePermission()
        }
    }

    LaunchedEffect(Unit) { extractLinuxAndInstall() }

    // تنظیمات UI
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
                    
                    Text("Background Color:")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { bgColor = Color.Black }) { Text("Black") }
                        Button(onClick = { bgColor = Color(0xFF1A1A1A) }) { Text("Dark") }
                        Button(onClick = { bgColor = Color(0xFF001F3F) }) { Text("Navy") }
                    }
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
        
        // کلیدهای کمکی
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("TAB" to "\t", "CTRL+C" to "\u0003").forEach { (label, char) ->
                TextButton(onClick = { ptyProcess.writeData(char); focusRequester.requestFocus() }, modifier = Modifier.weight(1f)) {
                    Text(text = label, color = Color.LightGray, fontSize = 12.sp)
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
                    runCommand(inputText)
                    inputText = ""
                    focusRequester.requestFocus()
                })
            )
        }
    }
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
            val applets = listOf("sh", "ls", "cat", "echo", "pwd", "mkdir", "rm", "cp", "mv", "chmod", "grep", "sed", "awk", "find", "vi", "nano")
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
