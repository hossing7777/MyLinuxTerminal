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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalScreen() }
    }
}

@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val initialDir = remember { context.filesDir.absolutePath }
    
    var inputText by remember { mutableStateOf("") }
    var currentDir by remember { mutableStateOf(initialDir) }
    val outputLines = remember { mutableStateListOf("Welcome to MyLinux Terminal v1.0", "Type 'ls', 'pwd', 'cd ..', 'mkdir test', or 'clear'") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    fun getPrompt(): String {
        val displayPath = if (currentDir == initialDir) "~" else currentDir.replace(initialDir, "~")
        return "root@android:$displayPath# "
    }

    fun runCommand(cmd: String) {
        val cleanCmd = cmd.trim()
        if (cleanCmd.isEmpty()) return
        
        // ۱. مدیریت دستور clear
        if (cleanCmd == "clear") {
            outputLines.clear()
            return
        }
        
        outputLines.add(getPrompt() + cleanCmd)

        // ۲. مدیریت دستور cd
        if (cleanCmd.startsWith("cd ")) {
            val path = cleanCmd.substring(3).trim()
            val newDir = if (path.startsWith("/")) File(path) else File(currentDir, path)
            if (newDir.exists() && newDir.isDirectory) {
                currentDir = newDir.canonicalPath
            } else {
                outputLines.add("cd: no such file or directory: $path")
            }
            return
        }

        // ۳. اجرای سایر دستورات در پس‌زمینه
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val pb = ProcessBuilder("sh", "-c", cleanCmd)
                    pb.directory(File(currentDir)) // اجرای دستور در پوشه فعلی
                    pb.redirectErrorStream(true) // ادغام خروجی و خطاها
                    val process = pb.start()
                    
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = StringBuilder()
                    var line: String?
                    
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                    }
                    process.waitFor()
                    output.toString().trim()
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            
            if (result.isNotEmpty()) {
                outputLines.add(result)
            }
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f)
        ) {
            items(outputLines) { line ->
                Text(
                    text = line,
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = getPrompt(),
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it },
                textStyle = TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    runCommand(inputText)
                    inputText = ""
                })
            )
        }
    }
}
