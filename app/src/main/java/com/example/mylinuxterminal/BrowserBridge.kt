package com.example.mylinuxterminal

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object BrowserBridge {
    var webView: WebView? = null
    var currentUrl: String = ""

    suspend fun loadUrl(url: String): String = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext "Error: Browser not ready"
        wv.loadUrl(url)
        delay(1500)
        currentUrl = url
        return@withContext "Success: Loaded $url"
    }

    suspend fun evaluateJs(js: String): String = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext "Error: Browser not ready"
        return@withContext suspendCancellableCoroutine { cont ->
            var resumed = false
            wv.evaluateJavascript(js) { result ->
                if (!resumed) {
                    resumed = true
                    val clean = result?.removeSurrounding("\"")
                        ?.replace("\\n", "\n")
                        ?.replace("\\\"", "\"")
                        ?: "null"
                    cont.resume(clean)
                }
            }
            cont.invokeOnCancellation { resumed = true }
        }
    }

    suspend fun getPageText(): String = evaluateJs("document.body.innerText")
    suspend fun getPageSource(): String = evaluateJs("document.documentElement.outerHTML")
    
    suspend fun clickElement(selector: String): String = evaluateJs(
        "var el=document.querySelector('$selector'); if(el){el.click();'Clicked: $selector'}else{'Error: Element not found: $selector'}"
    )

    suspend fun typeText(text: String): String {
        val base64Text = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
        val js = "(function(){" +
                 "let ta = document.querySelector('textarea');" +
                 "if(!ta) return 'No textarea found';" +
                 "ta.focus();" +
                 "let txt = atob('$base64Text');" +
                 "document.execCommand('insertText', false, txt);" +
                 "return 'Typed successfully';" +
                 "})()"
        return evaluateJs(js)
    }

    suspend fun clickSend(): String = evaluateJs(
        "(function(){" +
        "let btn = document.querySelector('button[type=\"submit\"]') || document.querySelector('button[aria-label=\"Send\"]') || document.querySelector('button[aria-label=\"send\"]');" +
        "if (btn) { btn.click(); return 'Clicked Send Button'; }" +
        "let ta = document.querySelector('textarea');" +
        "if(ta) { ta.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true})); return 'Pressed Enter'; }" +
        "return 'Error: No send button found';" +
        "})()"
    )

    suspend fun extractCode(): String = evaluateJs(
        "(function(){" +
        "let blocks = document.querySelectorAll('pre code');" +
        "if(blocks.length === 0) blocks = document.querySelectorAll('pre');" +
        "if(blocks.length === 0) return 'NO_CODE_FOUND';" +
        "let text = blocks[blocks.length - 1].innerText;" +
        "return text;" +
        "})()"
    )
}
