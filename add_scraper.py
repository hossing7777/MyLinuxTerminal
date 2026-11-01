import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

# ۱. اضافه کردن تابع Web Scraper قبل از تابع runAgent
new_func = '''    fun scrapeResponseFromWebView(url: String, onResult: (String) -> Unit) {
        val wv = android.webkit.WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                // تزریق JS برای گرفتن آخرین پاسخ DeepSeek
                view?.evaluateJavascript("""
                    (function() {
                        let messages = document.querySelectorAll('.ds-markdown');
                        if (messages.length > 0) {
                            return messages[messages.length - 1].innerText;
                        }
                        return 'NO_MESSAGE_FOUND';
                    })()
                """) { result ->
                    val cleanResult = result?.removeSurrounding("\\"")?.replace("\\\\n", "\\n") ?: "ERROR"
                    onResult(cleanResult)
                    wv.destroy()
                }
            }
        }
        wv.loadUrl(url)
    }

    fun runAgent(userRequest: String) {'''

if 'fun scrapeResponseFromWebView' not in s:
    s = s.replace('    fun runAgent(userRequest: String) {', new_func)
    print("✅ Web Scraper function added.")
else:
    print("⚠️ Web Scraper already exists.")

p.write_text(s, encoding="utf-8")
