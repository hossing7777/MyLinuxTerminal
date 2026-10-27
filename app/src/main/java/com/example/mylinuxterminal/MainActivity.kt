package com.example.mylinuxterminal

import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalScreen() }
    }
}

@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    var isExtracted by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val outputLines = remember { mutableStateListOf("Welcome to MyLinux Terminal v3.0", "Extracting Alpine Linux... Please wait.") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    suspend fun extractLinux() = withContext(Dispatchers.IO) {
        val alpineDir = File(context.filesDir, "alpine")
        if (!alpineDir.exists() || alpineDir.listFiles()?.isEmpty() == true) {
            try {
                alpineDir.mkdirs()
                // استفاده از کتابخانه برای استخراج به جای دستور tar سیستم
                context.assets.open("alpine.tar.gz").use { input ->
                    GzipCompressorInputStream(input).use { gzipIn ->
                        TarArchiveInputStream(gzipIn).use { tarIn ->
                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                val file = File(alpineDir, entry.name)
                                if (entry.isDirectory) {
                                    file.mkdirs()
                                } else {
                                    file.parentFile?.mkdirs()
                                    file.outputStream().use { output -> tarIn.copyTo(output) }
                                }
                                tarIn.closeEntry()
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { outputLines.add("Extraction Error: ${e.message}") }
            }
        }
        withContext(Dispatchers.Main) {
            isExtracted = true
            outputLines.add("Alpine Linux initialized!")
            outputLines.add("Try: 'cat /etc/os-release' or 'apk add python3'")
            listState.animateScrollToItem(outputLines.size - 1)
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
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val prootPath = File(nativeLibDir, "libproot.so").absolutePath
                    val alpinePath = File(context.filesDir, "alpine").absolutePath

                    // چون PRoot استاتیک است، نیازی به لودر و talloc ندارد
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
                } catch (e: Exception) { "Error: ${e.message}" }
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
                value = inputText, onValueChange = { inputText = it },
                textStyle = TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.weight(1f), singleLine = true, enabled = isExtracted,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { runCommand(inputText); inputText = "" })
            )
        }
    }
}
