import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
lines = p.read_text(encoding="utf-8").splitlines(keepends=True)

# پیدا کردن خط دکمه Start
start_line_idx = -1
for i, line in enumerate(lines):
    if 'runAgent(aiPrompt)' in line and 'Button(onClick' in line:
        start_line_idx = i
        break

if start_line_idx == -1:
    print("❌ Start button line not found!")
    exit()

print(f"✅ Found Start button at line {start_line_idx + 1}")

# ساخت خط جدید: Start + Stop کنار هم
new_line = '''                                Button(onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("Start", fontSize = 14.sp) }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(onClick = { 
                                    isAiRunning = false
                                    agentLogs.add("🛑 Stopped by user.")
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Icon(Icons.Default.Clear, "Stop", tint = Color.White) }
'''

# جایگزینی خط
lines[start_line_idx] = new_line
p.write_text("".join(lines), encoding="utf-8")
print("✅ Stop button added successfully!")
