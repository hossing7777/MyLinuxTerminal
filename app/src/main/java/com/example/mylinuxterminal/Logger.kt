package com.example.mylinuxterminal

import android.content.Context
import android.os.Build
import android.os.Environment
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
            // تلاش برای نوشتن در پوشه Download
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, "HguTerminal_Crash_Log.txt")
            file.appendText("") // تست دسترسی
            logFile = file
        } catch (e: Exception) {
            // اگر دسترسی نبود، در حافظه داخلی اپ بنویس
            try {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                logFile = File(dir, "HguTerminal_Crash_Log.txt")
            } catch (e2: Exception) {}
        }

        // پاک کردن لاگ‌های قبلی
        if (logFile?.exists() == true) logFile?.delete()

        log("========================================")
        log("APP STARTED on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})")
        
        // ثبت خودکار کرش‌ها
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            log("🚨 FATAL CRASH on thread [${thread.name}]:\n$sw")
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
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
