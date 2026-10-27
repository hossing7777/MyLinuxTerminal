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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalScreen() }
    }
}

@Composable
fun TerminalScreen() {
    var inputText by remember { mutableStateOf("") }
    val outputLines = remember { mutableStateListOf("Welcome to MyLinux Terminal v1.0", "Type a command (e.g., 'pwd', 'ls', 'id') and press Enter.") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // تابع اجرای دستور در پس‌زمینه
    fun runCommand(cmd: String) {
        val cleanCmd = cmd.trim()
        if (cleanCmd.isEmpty()) return
        
        // اضافه کردن دستور کاربر به خروجی
        outputLines.add("root@android:~# $cleanCmd")
        
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // اجرای دستور در محیط شل اندروید
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cleanCmd))
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                    val output = StringBuilder()
                    var line: String?
                    
                    while (reader.readLine().also { line = it } != null) output.append(line).append("\n")
                    while (errorReader.readLine().also { line = it } != null) output.append(line).append("\n")
                    process.waitFor()
                    output.toString().trim()
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            
            if (result.isNotEmpty()) {
                outputLines.add(result)
            }
            // اسکرول خودکار به پایین
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
                text = "$ ",
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
