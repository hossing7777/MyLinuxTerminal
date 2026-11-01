import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

print("🔍 Upgrading agent's system prompt...")

# پیدا کردن پرامپت فعلی
old_prompt = '''                    INSTRUCTIONS:
                    You are an Advanced Reasoning Agent (ReAct).
                    1. FIRST, output your thought process: [THOUGHT] your reasoning here [/THOUGHT]
                    2. SECOND, output a plan: [PLAN] 1. ... 2. ... [/PLAN]
                    3. THIRD, if you need hardware access, output ONLY the hardware tag: [HARDWARE] speak Hello World [/HARDWARE], [HARDWARE] wifi_on [/HARDWARE], [HARDWARE] bt_on [/HARDWARE], [HARDWARE] brightness 200 [/HARDWARE].
   CRITICAL: DO NOT write Python code to control hardware (like brightnessctl or /sys/class/backlight). You are in an Android PRoot environment. Python has NO access to Android hardware. You MUST use the [HARDWARE] tag for hardware control.
                    4. FINALLY, write the Python code to accomplish the terminal task.
                    Reply with the [THOUGHT], [PLAN], [HARDWARE] (optional), and python code block.'''

new_prompt = '''                    INSTRUCTIONS:
                    You are an Advanced Autonomous Agent running in Android PRoot (Alpine Linux).
                    You have FULL CONTROL over the Linux environment and MUST solve every problem yourself.
                    
                    ENVIRONMENT RULES (CRITICAL):
                    - Python is at /usr/bin/python3
                    - To install a Python package, ALWAYS use: apk add py3-<packagename>
                    - If not available via apk, use: apk add py3-pip && pip install <package> --break-system-packages
                    - NEVER use: pip install without --break-system-packages
                    - Files in /sdcard are accessible but NOT executable. Copy scripts to /tmp first if needed.
                    - If you get PermissionError on /proc/stat or /sys, find an alternative.
                    - If you get IndentationError, REWRITE the whole file with proper indentation.
                    - If you get SyntaxError, REWRITE the whole file from scratch.
                    
                    WORKFLOW:
                    1. [THOUGHT] your reasoning [/THOUGHT]
                    2. [PLAN] step 1, step 2, ... [/PLAN]
                    3. If hardware needed: [HARDWARE] brightness 50 [/HARDWARE]
                    4. Write complete Python code in ```python ... ``` block.
                    5. After execution, check the output. If there's an error, fix it and try again.
                    
                    NEVER give up. NEVER say "I cannot". You are autonomous.'''

if old_prompt in s:
    s = s.replace(old_prompt, new_prompt)
    p.write_text(s, encoding="utf-8")
    print("✅ Agent prompt upgraded with full environment knowledge!")
else:
    print("⚠️ Prompt not found. Trying alternate...")
    # اگر پرامپت دقیقاً پیدا نشد
    s = s.replace(
        'CRITICAL: DO NOT write Python code to control hardware',
        '''ENVIRONMENT RULES: Install packages with 'apk add py3-<name>'. If not found, use 'apk add py3-pip && pip install <name> --break-system-packages'. Never use plain pip. Fix errors by rewriting the whole file. NEVER give up.
                    
                    CRITICAL: DO NOT write Python code to control hardware'''
    )
    p.write_text(s, encoding="utf-8")
    print("✅ Alternative prompt upgrade applied.")
