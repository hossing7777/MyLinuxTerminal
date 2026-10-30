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
        val wv = webView ?: return@withContext "Error: Browser not ready (open the Browser tab first)"
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
}
