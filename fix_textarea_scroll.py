import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

print("🔍 Searching for the OutlinedTextField...")

# پیدا کردن کادر متنی فعلی
old_field = '''OutlinedTextField(
                                value = aiPrompt, onValueChange = { aiPrompt = it },
                                placeholder = { Text("درخواست خود را بنویسید...", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )'''

new_field = '''OutlinedTextField(
                                value = aiPrompt, onValueChange = { aiPrompt = it },
                                placeholder = { Text("Type your request here...", color = Color.Gray) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp, max = 200.dp) // حداکثر ارتفاع ۲۰۰
                                    .verticalScroll(rememberScrollState()), // اضافه کردن اسکرول
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )'''

if old_field in s:
    s = s.replace(old_field, new_field)
    p.write_text(s, encoding="utf-8")
    print("✅ Text field now has max height (200dp) and vertical scroll!")
else:
    print("⚠️ Exact text field not found. Trying alternate match...")
    # تلاش دوم با نسخه انگلیسی پلیس‌هولدر
    old_field_alt = '''OutlinedTextField(
                                value = aiPrompt, onValueChange = { aiPrompt = it },
                                placeholder = { Text("Type your request here...", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )'''

    if old_field_alt in s:
        s = s.replace(old_field_alt, new_field)
        p.write_text(s, encoding="utf-8")
        print("✅ Found and fixed (alternate match).")
    else:
        print("❌ Could not find text field. Please check manually.")
