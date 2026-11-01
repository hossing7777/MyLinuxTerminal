import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

old_logic = '''fun runAgent(userRequest: String) {
        if (selectedModelId == "shell_agent") {
            runShellAgent(userRequest)
            return
        }
    }'''

new_logic = '''fun runAgent(userRequest: String) {
        if (selectedModelId == "external_browser") {
            try {
                agentLogs.add("🌐 Opening External Browser...")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://duck.ai"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                agentLogs.add("✅ External browser opened successfully.")
            } catch (e: Exception) {
                agentLogs.add("❌ Error opening browser: ${e.message}")
            }
            return
        }
        if (selectedModelId == "shell_agent") {
            runShellAgent(userRequest)
            return
        }
    }'''

if old_logic in s:
    s = s.replace(old_logic, new_logic)
    p.write_text(s, encoding="utf-8")
    print("✅ runAgent logic updated for External Browser!")
else:
    print("⚠️ runAgent function not found exactly. Trying alternate match...")
    s = s.replace('''if (selectedModelId == "shell_agent") {
            runShellAgent(userRequest)
            return
        }''', '''if (selectedModelId == "external_browser") {
            try {
                agentLogs.add("🌐 Opening External Browser...")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://duck.ai"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) { agentLogs.add("❌ Error: ${e.message}") }
            return
        }
        if (selectedModelId == "shell_agent") {
            runShellAgent(userRequest)
            return
        }''')
    p.write_text(s, encoding="utf-8")
    print("✅ Attempted alternate replacement.")
