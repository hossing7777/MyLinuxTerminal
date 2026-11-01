import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

# پیدا کردن پرامپت فعلی
old_prompt = 'CRITICAL: The Linux environment has NO ACCESS to Bluetooth hardware.'

new_prompt = '''CRITICAL: You are an autonomous agent with full problem-solving capabilities.
If you need a library, you MUST install it yourself using pip or apk.
If a library is missing, try: apk add py3-<name>, then pip install <name> --break-system-packages
If a file is not readable (like /proc/stat), find an alternative method (e.g., use Python's os module, or read from Android-specific paths).
If you get a PermissionError, DO NOT give up. Try another approach.
If you get a SyntaxError in your own code, REWRITE the whole file from scratch, do not patch it.
NEVER say "I cannot do this" - you are an autonomous agent and you MUST solve every problem.
The Linux environment has NO ACCESS to Bluetooth hardware.'''

if old_prompt in s:
    s = s.replace(old_prompt, new_prompt)
    p.write_text(s, encoding="utf-8")
    print("✅ Prompt upgraded with self-resolving instructions!")
else:
    print("⚠️ Old prompt not found. Trying alternate...")
    # جایگزینی در پرامپت اصلی
    s = s.replace(
        'The Linux environment has NO ACCESS to Bluetooth hardware.',
        '''IMPORTANT: You are autonomous. If you need a library, install it yourself (apk add or pip). If a file is not readable, use another method. If you get a PermissionError, try another approach. NEVER give up.'''
    )
    p.write_text(s, encoding="utf-8")
    print("✅ Alternate prompt upgrade done.")
