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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { TerminalScreen() } }
}

@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    var isExtracted by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val outputLines = remember { mutableStateListOf("Welcome to MyLinux Terminal v2.3", "Initializing...") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    suspend fun extractLinux() = withContext(Dispatchers.IO) {
        val alpineDir = File(context.filesDir, "alpine")
        val tarFile = File(context.filesDir, "alpine.tar.gz")
        if (!alpineDir.exists() || alpineDir.listFiles()?.isEmpty() == true) {
            try {
                context.assets.open("alpine.tar.gz").use { input -> tarFile.outputStream().use { output -> input.copyTo(output) } }
                if (!alpineDir.exists()) alpineDir.mkdirs()
                // استخراج فایل با استفاده از tar سیستم
                val process = Runtime.getRuntime().exec("tar -xzf ${tarFile.absolutePath} -C ${alpineDir.absolutePath}")
                if (process.waitFor() == 0) tarFile.delete() else withContext(Dispatchers.Main) { outputLines.add("Warning: tar exit code ${process.exitValue()}") }
            } catch (e: Exception) { withContext(Dispatchers.Main) { outputLines.add("Extraction Error: ${e.message}") } }
        }
        withContext(Dispatchers.Main) {
            isExtracted = true
            outputLines.add("Alpine Linux initialized!")
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
                    val loaderPath = File(nativeLibDir, "libproot_loader.so").absolutePath
                    // نام فایل در اندروید libtalloc.so است، اما آن را به libtalloc.so.2 در لینوکس متصل می‌کنیم
                    val tallocPath = File(nativeLibDir, "libtalloc.so").absolutePath

                    val pb = ProcessBuilder(
                        prootPath, "-r", alpinePath, "-0", "-w", "/",
                        "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/storage/emulated/0:/sdcard",
                        "-b", "$loaderPath:/lib/ld-musl-aarch64.so.1",
                        "-b", "$tallocPath:/lib/libtalloc.so.2", // اتصال به مسیر مورد نیاز PRoot
                        "--link2symlink",
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
