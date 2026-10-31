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
    private val logFiles = mutableListOf<File>()

    fun init(context: Context) {
        // ۱. مسیر پوشه دانلود (اولویت اول)
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file1 = File(downloadDir, "HguTerminal_Log.txt")
            file1.appendText("") // تست نوشتن
            logFiles.add(file1)
        } catch (e: Exception) {}

        // ۲. مسیر اختصاصی برنامه در حافظه خارجی
        try {
            val dir2 = context.getExternalFilesDir(null)
            if (dir2 != null) logFiles.add(File(dir2, "HguTerminal_Log.txt"))
        } catch (e: Exception) {}

        // ۳. مسیر حافظه داخلی برنامه (پشتیبان)
        try {
            logFiles.add(File(context.filesDir, "HguTerminal_Log.txt"))
        } catch (e: Exception) {}

        // پاک کردن لاگ‌های قبلی
        logFiles.forEach { if (it.exists()) it.delete() }

        writeToAll("========================================")
        writeToAll("APP STARTED on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})")
        
        // ثبت خودکار کرش‌ها
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            writeToAll("🚨 FATAL CRASH on thread [${thread.name}]:\n$sw")
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }

    private fun writeToAll(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val formattedMessage = "[$timestamp] $message\n"
        logFiles.forEach { file ->
            try {
                file.appendText(formattedMessage)
            } catch (e: Exception) {}
        }
    }

    fun log(message: String) {
        writeToAll(message)
    }
}
