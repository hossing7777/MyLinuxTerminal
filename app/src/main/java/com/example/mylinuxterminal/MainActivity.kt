package com.example.mylinuxterminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalScreen() }
    }
}

@Composable
fun TerminalScreen() {
    var inputText by remember { mutableStateOf("") }
    val outputLines = remember { mutableStateListOf("Welcome to MyLinux Terminal v1.0", "root@android:~# ") }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(outputLines) { line ->
                Text(text = line, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "$ ", color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            BasicTextField(
                value = inputText, onValueChange = { inputText = it },
                textStyle = TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.weight(1f), singleLine = true
            )
        }
    }
}
