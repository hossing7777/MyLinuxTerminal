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
    var currentDir by remember { mutableStateOf("/root") }
    var fontSize by remember { mutableStateOf(14.sp) }
    
    val outputLines = remember { mutableStateListOf("Welcome to MyLinux Terminal v8.2", "Initializing...") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                outputLines.add("Storage permission granted! /sdcard is ready.")
            } else {
                outputLines.add("Storage permission denied. /sdcard may not work.")
            }
        }
    }

    fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                manageStorageLauncher.launch(intent)
            }
        }
    }

    fun copyAllText() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Terminal Output", outputLines.joinToString("\n"))
        clipboard.setPrimaryClip(clip)
        outputLines.add("--- All text copied ---")
    }

    suspend fun extractLinux() = withContext(Dispatchers.IO) {
        val alpineDir = File(context.filesDir, "alpine")
        
        val assets = context.assets.list("") ?: emptyArray()
        val assetName = when {
            assets.contains("alpine.tar.gz") -> "alpine.tar.gz"
            assets.contains("alpine.tar") -> "alpine.tar"
            else -> "alpine.tar.gz"
        }

        if (!alpineDir.exists() || alpineDir.listFiles()?.isEmpty() == true) {
            try {
                alpineDir.mkdirs()
                context.assets.open(assetName).use { input -> 
                    extractTarSmart(input, alpineDir) 
                }
                withContext(Dispatchers.Main) { outputLines.add("Extraction successful!") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { outputLines.add("Extract Error: ${e.toString()}") }
            }
        }

        fixAlpineStructure(alpineDir)

        withContext(Dispatchers.Main) {
            isExtracted = true
            listState.animateScrollToItem(outputLines.size - 1)
            focusRequester.requestFocus()
            keyboardController?.show()
            checkStoragePermission()
        }
    }

    LaunchedEffect(Unit) { extractLinux() }

    fun runCommand(cmd: String) {
        val cleanCmd = cmd.trim()
        if (cleanCmd.isEmpty() || !isExtracted) return
        if (cleanCmd == "clear") { outputLines.clear(); return }
        
        outputLines.add("root@alpine:$currentDir# $cleanCmd")

        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val prootPath = File(nativeLibDir, "libproot.so").absolutePath
                    val loaderPath = File(nativeLibDir, "libproot_loader.so").absolutePath
                    val alpinePath = File(context.filesDir, "alpine").absolutePath
                    val tmpDir = File(alpinePath, "tmp")

                    val fullCmd = "cd \"$currentDir\" 2>/dev/null; $cleanCmd; echo \"__PWD__\"; pwd"

                    val pb = ProcessBuilder(
                        prootPath,
                        "--kill-on-exit",
                        "--link2symlink",
                        "--sysvipc",
                        "-0",
                        "-r", alpinePath,
                        "-w", "/",
                        "-b", "/dev",
                        "-b", "/proc",
                        "-b", "/sys",
                        "-b", "/storage/emulated/0:/sdcard",
                        "/bin/sh", "-c", fullCmd
                    )
                    
                    val env = pb.environment()
                    env["PROOT_TMP_DIR"] = tmpDir.absolutePath
                    env["PROOT_NO_SECCOMP"] = "1"
                    env["PROOT_LOADER"] = loaderPath
                    env["LD_LIBRARY_PATH"] = nativeLibDir
                    env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                    env["HOME"] = "/root"
                    env["TERM"] = "xterm-256color"
                    env.remove("LD_PRELOAD")
                    
                    pb.redirectErrorStream(true)
                    val process = pb.start()
                    
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) { output.append(line).append("\n") }
                    process.waitFor()
                    
                    val rawOutput = output.toString().trim()
                    val lines = rawOutput.lines()
                    var newDir = currentDir
                    val displayOutput = mutableListOf<String>()

                    if (lines.size >= 2 && lines[lines.size - 2] == "__PWD__") {
                        newDir = lines.last() 
                        displayOutput.addAll(lines.dropLast(2)) 
                    } else {
                        displayOutput.addAll(lines)
                    }

                    Pair(newDir, displayOutput.joinToString("\n").trim())
                } catch (e: Exception) { 
                    Pair(currentDir, "Error: ${e.toString()}") 
                }
            }
            
            if (result.first != currentDir && result.first.isNotEmpty()) {
                currentDir = result.first
            }
            if (result.second.isNotEmpty()) { 
                outputLines.add(result.second) 
            }
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { copyAllText() }) {
                Text("Copy All", color = Color.Cyan, fontSize = 12.sp)
            }
        }
        
        LazyColumn(
            state = listState, 
            modifier = Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val newSize = fontSize.value * zoom
                        fontSize = newSize.coerceIn(8f, 36f).sp
                    }
                }
        ) {
            items(outputLines) { line -> 
                SelectionContainer {
                    Text(text = line, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = fontSize) 
                }
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "root@alpine:$currentDir# ", 
                color = Color.Green, 
                fontFamily = FontFamily.Monospace, 
                fontSize = fontSize 
            )
            BasicTextField(
                value = inputText, 
                onValueChange = { inputText = it },
                textStyle = TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = fontSize),
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
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 9.9.9.9\n")
        }

        var busybox = File(binDir, "busybox")
        if (!busybox.exists() || busybox.length() < 100000L) {
            val usrBusybox = File(usrBinDir, "busybox")
            if (usrBusybox.exists() && usrBusybox.length() > 100000L) {
                busybox = usrBusybox
            }
        }

        if (busybox.exists() && busybox.length() > 100000L) {
            val applets = listOf(
                "sh", "ls", "cat", "echo", "pwd", "mkdir", "rm", "cp", "mv", 
                "chmod", "chown", "sed", "grep", "awk", "env", "uname", 
                "sleep", "kill", "ps", "ln", "df", "du", "head", "tail", 
                "touch", "date", "clear", "mount", "id", "whoami", "find", "vi", "nano"
            )
            
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
        if (!ldMusl.exists() || ldMusl.length() < 1000L) {
            try { ldMusl.delete() } catch (e: Exception) {}
            val libc = File(libDir, "libc.musl-aarch64.so.1")
            if (libc.exists()) {
                libc.copyTo(ldMusl, overwrite = true)
                ldMusl.setExecutable(true)
            }
        }

        val tmpDir = File(alpineDir, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        
        val rootDir = File(alpineDir, "root")
        if (!rootDir.exists()) rootDir.mkdirs()

    } catch (e: Exception) {
        Log.e("Terminal", "Fix structure error", e)
    }
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
                entry = tarIn.nextEntry
                continue
            }
            if (entry.isDirectory) {
                destFile.mkdirs()
            } else if (entry.isSymbolicLink) {
                try {
                    Files.createSymbolicLink(Paths.get(destFile.absolutePath), Paths.get(entry.linkName))
                } catch (e: Exception) {
                }
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
        } catch (e: Exception) {
            Log.e("Terminal", "Error extracting ${entry.name}", e)
        }
        entry = tarIn.nextEntry
    }
}
