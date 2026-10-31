package com.example.mylinuxterminal

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, "HguTerminal_Crash_Log.txt")
            if (logFile?.exists() == true) logFile?.delete()
            
            log("========================================")
            log("APP STARTED on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})")
            
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                log("🚨 FATAL CRASH on thread [${thread.name}]:\n$sw")
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun log(message: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            logFile?.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
