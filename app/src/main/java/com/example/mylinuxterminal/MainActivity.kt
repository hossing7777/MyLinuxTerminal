package com.example.mylinuxterminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
    val outputLines = remember { mutableStateListOf("Welcome to MyLinux Terminal v6.2", "Initializing...") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

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

        // عیب‌یابی: بررسی وجود فایل sh بعد از استخراج
        val shFile = File(alpineDir, "bin/sh")
        if (shFile.exists()) {
            withContext(Dispatchers.Main) { outputLines.add("Debug: /bin/sh exists.") }
        } else {
            withContext(Dispatchers.Main) { outputLines.add("Debug: /bin/sh NOT FOUND!") }
        }

        withContext(Dispatchers.Main) {
            isExtracted = true
            listState.animateScrollToItem(outputLines.size - 1)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(Unit) { extractLinux() }

    fun runCommand(cmd: String) {
        val cleanCmd = cmd.trim()
        if (cleanCmd.isEmpty() || !isExtracted) return
        if (cleanCmd == "clear") { outputLines.clear(); return }
        outputLines.add("root@alpine:~# $cleanCmd")

        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val prootPath = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
                    val alpinePath = File(context.filesDir, "alpine").absolutePath
                    
                    // استفاده از cacheDir برای پوشه موقت که در برخی دستگاه‌ها سازگارتر است
                    val tmpDir = File(context.cacheDir, "proot_tmp")
                    if (!tmpDir.exists()) tmpDir.mkdirs()

                    val pb = ProcessBuilder(
                        prootPath,
                        "--kill-on-exit",
                        "--link2symlink",
                        "--sysvipc", // پرچم جدید برای ارتباط بین پروسه‌ها
                        "-0",
                        "-r", alpinePath,
                        "-w", "/",
                        "-b", "/dev",
                        "-b", "/proc",
                        "-b", "/sys",
                        "-b", "/storage/emulated/0:/sdcard",
                        "/bin/sh", "-c", cleanCmd
                    )
                    
                    val env = pb.environment()
                    env["PROOT_TMP_DIR"] = tmpDir.absolutePath
                    env["PROOT_NO_SECCOMP"] = "1"
                    env["PROOT_FORCE_FOREIGN_BINARY"] = "1"
                    
                    pb.redirectErrorStream(true)
                    val process = pb.start()
                    
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) { output.append(line).append("\n") }
                    process.waitFor()
                    output.toString().trim()
                } catch (e: Exception) { "Error: ${e.toString()}" }
            }
            if (result.isNotEmpty()) { outputLines.add(result) }
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { copyAllText() }) {
                Text("Copy All", color = Color.Cyan, fontSize = 12.sp)
            }
        }
        
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(outputLines) { line -> 
                SelectionContainer {
                    Text(text = line, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp) 
                }
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "root@alpine:~# ", color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            BasicTextField(
                value = inputText, 
                onValueChange = { inputText = it },
                textStyle = TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
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
                // مدیریت بهتر لینک‌های نمادین
                if (!destFile.exists()) {
                    try {
                        Files.createSymbolicLink(Paths.get(destFile.absolutePath), Paths.get(entry.linkName))
                    } catch (e: Exception) {
                        Log.e("Terminal", "Failed to create symlink: ${entry.name} -> ${entry.linkName}", e)
                    }
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
