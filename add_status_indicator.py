import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

print("🔍 Searching for AI Agent title...")

# پیدا کردن متن "AI Agent" در هدر دیالوگ
old_title = 'Text("AI Agent", color = Color.Cyan, fontWeight = FontWeight.Bold)'

new_title = '''Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI Agent", color = Color.Cyan, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        // چراغ وضعیت اتصال
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = if (isDuckAiOnline) Color.Green else Color.Red,
                                    shape = RoundedCornerShape(50)
                                )
                        )
                    }'''

if old_title in s:
    s = s.replace(old_title, new_title)
    print("✅ Status indicator added to AI Agent title.")
else:
    print("❌ Could not find AI Agent title.")

# اضافه کردن متغیر وضعیت به TerminalScreen
old_state = 'var isAiRunning by remember { mutableStateOf(false) }'
new_state = '''var isAiRunning by remember { mutableStateOf(false) }
    var isDuckAiOnline by remember { mutableStateOf(false) }'''

if old_state in s:
    s = s.replace(old_state, new_state)
    print("✅ isDuckAiOnline state variable added.")

# اضافه کردن حلقه بررسی وضعیت در LaunchedEffect
old_launch = '''    LaunchedEffect(Unit) { 
        loadAllChats()
        extractLinux(context, mutableListOf<String>())
        isExtracted = true
        checkStoragePermission()'''

new_launch = '''    // حلقه بررسی وضعیت Duck.ai هر ۱۵ ثانیه
    LaunchedEffect(Unit) {
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    val connection = java.net.URL("https://duck.ai").openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.requestMethod = "HEAD"
                    val responseCode = connection.responseCode
                    isDuckAiOnline = responseCode in 200..399
                    connection.disconnect()
                }
            } catch (e: Exception) {
                isDuckAiOnline = false
            }
            delay(15000)
        }
    }

    LaunchedEffect(Unit) { 
        loadAllChats()
        extractLinux(context, mutableListOf<String>())
        isExtracted = true
        checkStoragePermission()'''

if old_launch in s:
    s = s.replace(old_launch, new_launch)
    print("✅ Status check loop added.")
else:
    print("❌ LaunchedEffect not found. Trying alternate...")
    s = s.replace(
        'LaunchedEffect(Unit) { \n        loadAllChats()',
        '''LaunchedEffect(Unit) {
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    val conn = java.net.URL("https://duck.ai").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.requestMethod = "HEAD"
                    isDuckAiOnline = conn.responseCode in 200..399
                    conn.disconnect()
                }
            } catch (e: Exception) { isDuckAiOnline = false }
            delay(15000)
        }
    }
    
    LaunchedEffect(Unit) { 
        loadAllChats()'''
    )
    print("✅ Alternate status check added.")

p.write_text(s, encoding="utf-8")
print("✅ Done!")
