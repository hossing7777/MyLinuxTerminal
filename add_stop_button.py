import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

print("🔍 Searching for Start button...")

# پیدا کردن دکمه Start (فارسی یا انگلیسی)
start_button_fa = 'Button(onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("شروع", fontSize = 16.sp) }'
start_button_en = 'Button(onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("Start", fontSize = 16.sp) }'

target = None
if start_button_fa in s:
    target = start_button_fa
    print("✅ Found Persian Start button.")
elif start_button_en in s:
    target = start_button_en
    print("✅ Found English Start button.")
else:
    print("❌ Start button not found!")
    exit()

# ساخت بلاک جدید: Start + Stop در کنار هم
new_block = '''Button(onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("Start", fontSize = 14.sp) }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { 
                                    isAiRunning = false
                                    agentLogs.add("🛑 Stopped by user.")
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Icon(Icons.Default.Clear, "Stop", tint = Color.White) }'''

# جایگزینی
s = s.replace(target, new_block)
p.write_text(s, encoding="utf-8")
print("✅ Stop button added next to Start button!")
