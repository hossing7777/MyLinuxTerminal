import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

old_models = '''val availableModels = listOf(
    "shell_agent" to "🤖 Shell Agent (No API)",
    "full_auto" to "🔧 Full Auto (API)",
    "nvidia/nemotron-3-ultra-550b-a55b:free" to "Nemotron 3 Ultra"
)'''

new_models = '''val availableModels = listOf(
    "shell_agent" to "🤖 Shell Agent (No API)",
    "external_browser" to "🌐 External Browser (Chrome)",
    "full_auto" to "🔧 Full Auto (API)",
    "nvidia/nemotron-3-ultra-550b-a55b:free" to "Nemotron 3 Ultra"
)'''

if old_models in s:
    s = s.replace(old_models, new_models)
    p.write_text(s, encoding="utf-8")
    print("✅ External Browser option added to models list!")
else:
    print("⚠️ Models list not found exactly. Trying alternate match...")
    # اگر فاصله‌ها متفاوت بود
    s = s.replace('"shell_agent" to "🤖 Shell Agent (No API)",', '"shell_agent" to "🤖 Shell Agent (No API)",\n    "external_browser" to "🌐 External Browser (Chrome)",')
    p.write_text(s, encoding="utf-8")
    print("✅ Attempted alternate replacement.")
