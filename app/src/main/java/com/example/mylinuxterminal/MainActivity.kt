package com.example.mylinuxterminal

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
import androidx.compose.material3.Text
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    var isExtracted by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val outputLines = remember { mutableStateListOf("Welcome to MyLinux Terminal v4.3", "Initializing...") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    suspend fun extractLinux() = withContext(Dispatchers.IO) {
        val binDir = File(context.filesDir, "bin")
        val alpineDir = File(context.filesDir, "alpine")
        
        // ۱. استخراج PRoot از assets
        val prootFile = File(binDir, "proot")
        if (!prootFile.exists()) {
            try {
                binDir.mkdirs()
                context.assets.open("proot").use { input ->
                    prootFile.outputStream().use { output -> input.copyTo(output) }
                }
                prootFile.setExecutable(true, false)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { outputLines.add("PRoot Extract Error: ${e.toString()}") }
            }
        }

        // ۲. استخراج Alpine Linux
        if (!alpineDir.exists() || alpineDir.listFiles()?.isEmpty() == true) {
            try {
                alpineDir.mkdirs()
                context.assets.open("alpine.tar.gz").use { input -> 
                    extractTarGz(input, alpineDir) 
                }
            } catch (e: Exception) {
                // چاپ خطای دقیق
                withContext(Dispatchers.Main) { outputLines.add("Alpine Extract Error: ${e.toString()}") }
            }
        }

        withContext(Dispatchers.Main) {
            isExtracted = true
            outputLines.add("Alpine Linux initialized successfully!")
            outputLines.add("Try: 'cat /etc/os-release' or 'apk add python3'")
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
                    val prootPath = File(context.filesDir, "bin/proot").absolutePath
                    val alpinePath = File(context.filesDir, "alpine").absolutePath

                    val pb = ProcessBuilder(
                        prootPath, "-r", alpinePath, "-0", "-w", "/",
                        "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/storage/emulated/0:/sdcard",
                        "/bin/sh", "-c", cleanCmd
                    )
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
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(outputLines) { line -> Text(text = line, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "root@alpine:~# ", color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            BasicTextField(
                value = inputText, 
                onValueChange = { inputText = it },
                textStyle = TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
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

fun extractTarGz(inputStream: InputStream, destDir: File) {
    GzipCompressorInputStream(inputStream).use { gzipIn ->
        TarArchiveInputStream(gzipIn).use { tarIn ->
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
                        if (!destFile.exists()) {
                            Files.createSymbolicLink(Paths.get(destFile.absolutePath), Paths.get(entry.linkName))
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
    }
}
