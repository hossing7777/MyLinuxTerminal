import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
lines = p.read_text(encoding="utf-8").splitlines(keepends=True)

# پیدا کردن خطی که دکمه‌های خراب توش هستند
start_idx = -1
end_idx = -1

for i, line in enumerate(lines):
    if 'runAgent(aiPrompt)' in line and 'Button(onClick' in line:
        start_idx = i
        break

if start_idx == -1:
    print("❌ Start button not found!")
    exit()

# پیدا کردن انتهای دکمه‌های خراب (تا خطی که شامل '🛑 Stopped by user' هست)
for i in range(start_idx, len(lines)):
    if '🛑 Stopped by user' in lines[i]:
        # پیدا کردن خط بسته شدن دکمه
        for j in range(i, len(lines)):
            if '}' in lines[j] and 'Icon(Icons.Default.Clear' in lines[j]:
                end_idx = j
                break
        break

if end_idx == -1:
    print("❌ Could not find end of broken buttons!")
    exit()

print(f"✅ Found broken buttons from line {start_idx+1} to {end_idx+1}")

# ساخت ردیف تمیز و مرتب
new_row = '''                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("Start", fontSize = 14.sp) }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = { 
                                        isAiRunning = false
                                        agentLogs.add("🛑 Stopped by user.")
                                    }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Icon(Icons.Default.Clear, "Stop", tint = Color.White) }
                                }
'''

# جایگزینی خطوط خراب
new_lines = lines[:start_idx] + [new_row] + lines[end_idx+1:]
p.write_text("".join(new_lines), encoding="utf-8")
print("✅ Buttons fixed and reorganized!")
