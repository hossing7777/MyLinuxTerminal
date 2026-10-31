package com.example.mylinuxterminal

import kotlinx.coroutines.*

object ShellAgent {
    fun start(
        userRequest: String,
        agentLogs: MutableList<String>,
        setAiRunning: (Boolean) -> Unit,
        setAiProgress: (Int) -> Unit,
        executeCommand: suspend (String) -> String,
        scope: CoroutineScope
    ) {
        setAiRunning(true)
        setAiProgress(5)
        agentLogs.add("🚀 Autonomous Agent Started (No API)...")

        scope.launch(Dispatchers.IO) {
            try {
                if (BrowserBridge.webView == null) {
                    withContext(Dispatchers.Main) { agentLogs.add("❌ Browser not ready. Open Browser tab first.") }
                    setAiRunning(false)
                    return@launch
                }

                var iteration = 0
                var currentPrompt = "$userRequest. Reply ONLY with the python code inside a markdown block."

                withContext(Dispatchers.Main) { agentLogs.add("🌐 Navigating to Duck.ai...") }
                BrowserBridge.loadUrl("https://duck.ai")
                delay(5000)

                while (iteration < 5) {
                    iteration++
                    setAiProgress(10 + (iteration * 15))
                    withContext(Dispatchers.Main) { agentLogs.add("🔄 Attempt $iteration/5...") }

                    withContext(Dispatchers.Main) {
                        agentLogs.add("⌨️ Sending prompt to AI...")
                        BrowserBridge.typeText(currentPrompt)
                        delay(1000)
                        BrowserBridge.clickSend()
                    }

                    withContext(Dispatchers.Main) { agentLogs.add("⏳ Waiting for AI response (15s)...") }
                    delay(15000)

                    withContext(Dispatchers.Main) { agentLogs.add("📖 Extracting code...") }
                    val rawCode = BrowserBridge.extractCode()

                    if (rawCode == "NO_CODE_FOUND" || rawCode.isBlank() || rawCode.contains("Error: Browser not ready")) {
                        withContext(Dispatchers.Main) { agentLogs.add("⚠️ No valid code found. Retrying...") }
                        currentPrompt = "Please provide a valid Python code block for: $userRequest"
                        continue
                    }

                    withContext(Dispatchers.Main) { agentLogs.add("📜 Code Extracted. Running...") }
                    val b64Code = android.util.Base64.encodeToString(rawCode.toByteArray(), android.util.Base64.NO_WRAP)
                    val cmd = "if ! command -v python3 >/dev/null 2>&1; then apk add python3 >/dev/null 2>&1; fi; " +
                              "echo '$b64Code' | base64 -d > /root/ai_script.py && python3 /root/ai_script.py 2>&1"

                    val output = executeCommand(cmd)
                    withContext(Dispatchers.Main) { agentLogs.add("📄 Output: ${output.take(200)}...") }

                    if (output.contains("Traceback") || output.contains("SyntaxError") || output.contains("NameError") || output.contains("Error:") || output.contains("not found")) {
                        withContext(Dispatchers.Main) { agentLogs.add("❌ Error detected! Asking AI to fix the code...") }
                        currentPrompt = "The code you provided failed with this error:\n$output\n\nPlease fix the code and provide the complete updated python code."
                    } else {
                        withContext(Dispatchers.Main) {
                            agentLogs.add("✅ Task completed successfully!")
                            setAiProgress(100)
                            setAiRunning(false)
                        }
                        return@launch
                    }
                }
                
                withContext(Dispatchers.Main) {
                    agentLogs.add("🛑 Reached max attempts (5). Stopping.")
                    setAiRunning(false)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    agentLogs.add("❌ Exception: ${e.message}")
                    setAiRunning(false)
                }
            }
        }
    }
}
