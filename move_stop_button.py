import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

print("🔍 Moving Stop button next to Clear...")

# ۱. پیدا کردن و حذف دکمه Stop فعلی
old_row = '''Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("Start", fontSize = 14.sp) }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = { 
                                        isAiRunning = false
                                        agentLogs.add("🛑 Stopped by user.")
                                    }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Icon(Icons.Default.Clear, "Stop", tint = Color.White) }
                                }
'''

new_start_row = '''Button(onClick = { if (aiPrompt.isNotBlank()) runAgent(aiPrompt) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("Start", fontSize = 14.sp) }
                                Spacer(modifier = Modifier.width(8.dp))
'''

if old_row in s:
    s = s.replace(old_row, new_start_row)
    print("✅ Removed Stop button from bottom row.")

# ۲. پیدا کردن بخش "گزارش زنده" و "پاک کردن"
old_header = '''Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("گزارش زنده:", color = Color.LightGray, fontSize = 12.sp)
                        TextButton(onClick = { agentLogs.clear() }) { Text("پاک کردن", color = Color.Red, fontSize = 12.sp) }
                    }'''

new_header = '''Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("گزارش زنده:", color = Color.LightGray, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isAiRunning) {
                                Button(onClick = { 
                                    isAiRunning = false
                                    agentLogs.add("🛑 Stopped by user.")
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { 
                                    Icon(Icons.Default.Clear, "Stop", tint = Color.White, modifier = Modifier.size(16.dp)) 
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("STOP", color = Color.White, fontSize = 10.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            TextButton(onClick = { agentLogs.clear() }) { Text("پاک کردن", color = Color.Red, fontSize = 12.sp) }
                        }
                    }'''

if old_header in s:
    s = s.replace(old_header, new_header)
    p.write_text(s, encoding="utf-8")
    print("✅ Stop button moved next to Clear!")
else:
    print("⚠️ Could not find 'گزارش زنده' header.")
