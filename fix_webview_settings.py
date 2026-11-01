import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/BrowserScreen.kt")
s = p.read_text(encoding="utf-8")

print("🔍 Searching for WebView settings...")

# پیدا کردن بخش تنظیمات WebView
old_settings = '''                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.setGeolocationEnabled(true)
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT'''

new_settings = '''                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.setGeolocationEnabled(true)
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkImage = false
                    settings.blockNetworkLoads = false
                    // اضافه کردن کوکی‌ها برای Cloudflare
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)'''

if old_settings in s:
    s = s.replace(old_settings, new_settings)
    p.write_text(s, encoding="utf-8")
    print("✅ WebView settings upgraded for better compatibility!")
else:
    print("❌ Settings block not found. Trying alternate...")
    # اگر ساختار کمی متفاوت بود
    s = s.replace('(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36', '(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36')
    p.write_text(s, encoding="utf-8")
    print("✅ User-Agent updated.")

