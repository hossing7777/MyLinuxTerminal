import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

print("🔍 Searching for Start button...")

# پیدا کردن دکمه Start (با هر متن و استایلی)
import re

# الگو برای پیدا کردن خط دکمه Start
pattern = r'Button\(onClick = \{ if \(aiPrompt\.isNotBlank\(\)\) runAgent\(aiPrompt\) \}.*?\) \{ Text\("[^"]*", fontSize = 16\.sp\) \}'
match = re.search(pattern, s)

if not match:
    print("❌ Start button not found!")
    exit()

start_button = match.group(0)
print(f"✅ Found Start button: {start_button[:60]}...")

# ساخت بلاک جدید: Start + Stop
new_block = '''Button(onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("Start", fontSize = 14.sp) }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(onClick = { 
                                    isAiRunning = false
                                    agentLogs.add("🛑 Stopped by user.")
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Icon(Icons.Default.Clear, "Stop", tint = Color.White) }'''

# جایگزینی
s = s.replace(start_button, new_block)
p.write_text(s, encoding="utf-8")
print("✅ Stop button added successfully!")
