package com.example.mylinuxterminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

object BrowserServer {
    private const val PORT = 8081
    private var serverSocket: ServerSocket? = null

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(PORT)
                while (true) {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return

            // Parse: GET /browser?action=goto&arg=... HTTP/1.1
            if (requestLine.startsWith("GET /hardware")) {
                val queryString = requestLine.split(" ")[1].substringAfter("?")
                val params = parseQuery(queryString)
                val action = params["action"] ?: ""
                val arg = params["arg"] ?: ""
                val context = BrowserBridge.webView?.context

                val result = when (action) {
                    "speak" -> { HardwareBridge.speak(arg); "Spoke: $arg" }
                    "wifi_on" -> if (context != null) HardwareBridge.toggleWifi(context, true) else "Context null"
                    "wifi_off" -> if (context != null) HardwareBridge.toggleWifi(context, false) else "Context null"
                    "bt_on" -> HardwareBridge.toggleBluetooth(true)
                    "bt_off" -> HardwareBridge.toggleBluetooth(false)
                    "brightness" -> {
                        val level = arg.toIntOrNull() ?: 128
                        if (context != null) HardwareBridge.setBrightness(context, level) else "Context null"
                    }
                    else -> "Unknown hardware action"
                }

                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\n\r\n$result"
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.print(response)
                writer.flush()
                return
            } else if (requestLine.startsWith("GET /browser")) {
                val queryString = requestLine.split(" ")[1].substringAfter("?")
                val params = parseQuery(queryString)
                
                val action = params["action"] ?: ""
                val arg = params["arg"] ?: ""

                val result = when (action) {
                    "goto" -> BrowserBridge.loadUrl(arg)
                    "text" -> BrowserBridge.getPageText()
                    "source" -> BrowserBridge.getPageSource()
                    "click" -> BrowserBridge.clickElement(arg)
                    "eval" -> BrowserBridge.evaluateJs(arg)
                    "type" -> BrowserBridge.typeText(arg)
                    "send" -> BrowserBridge.clickSend()
                    "extract_code" -> BrowserBridge.extractCode()
                    "sleep" -> {
                        val ms = arg.toLongOrNull() ?: 3000L
                        kotlinx.coroutines.delay(ms)
                        "Slept for ${ms}ms"
                    }
                    else -> "Error: Unknown action '$action'"
                }

                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\n\r\n$result"
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.print(response)
                writer.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").associate { 
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            } else {
                URLDecoder.decode(parts[0], "UTF-8") to ""
            }
        }
    }
}
