package com.example.mylinuxterminal

// ... (ایمپورت‌ها بدون تغییر)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    // ... (بخش‌های مربوط به state و clipboard بدون تغییر)

    suspend fun extractLinux() = withContext(Dispatchers.IO) {
        // ... (منطق استخراج فایل بدون تغییر)
    }

    LaunchedEffect(Unit) { extractLinux() }

    fun runCommand(cmd: String) {
        val cleanCmd = cmd.trim()
        if (cleanCmd.isEmpty() || !isExtracted) return
        if (cleanCmd == "clear") { outputLines.clear(); return }
        outputLines.add("root@alpine:~# $cleanCmd")

        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val prootPath = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
                    val alpinePath = File(context.filesDir, "alpine").absolutePath
                    val tmpDir = File(context.cacheDir, "proot_tmp")
                    if (!tmpDir.exists()) tmpDir.mkdirs()

                    // ⬇️⬇️⬇️ تغییرات اصلی اینجاست ⬇️⬇️⬇️
                    val pb = ProcessBuilder(
                        prootPath,
                        "--link2symlink", // بسیار مهم برای سازگاری با اندروید
                        "-0", // شبیه‌سازی کاربر روت
                        "-r", alpinePath,
                        "-w", "/",
                        "-b", "/dev",
                        "-b", "/proc",
                        "-b", "/sys",
                        "-b", "/system", // اتصال پوشه سیستم اندروید
                        "-b", "/storage/emulated/0:/sdcard",
                        "/bin/sh", "-c", cleanCmd
                    )
                    
                    // تنظیم متغیرهای محیطی برای رفع خطاهای Function not implemented
                    val env = pb.environment()
                    env["PROOT_TMP_DIR"] = tmpDir.absolutePath
                    env["PROOT_NO_SECCOMP"] = "1" // غیرفعال کردن seccomp برای جلوگیری از خطا
                    env["PROOT_FORCE_FOREIGN_BINARY"] = "1" // اجبار به اجرای باینری به عنوان برنامه خارجی
                    
                    pb.redirectErrorStream(true)
                    val process = pb.start()
                    
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) { output.append(line).append("\n") }
                    process.waitFor()
                    output.toString().trim()
                } catch (e: Exception) { "Error: ${e.toString()}" }
            }
            if (result.isNotEmpty()) { outputLines.add(result) }
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    // ... (بخش Column و TextField بدون تغییر)
}
// ... (توابع استخراج بدون تغییر)
