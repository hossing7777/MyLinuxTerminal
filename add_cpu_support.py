import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/HardwareBridge.kt")
s = p.read_text(encoding="utf-8")

# اضافه کردن تابع getCpuUsage
new_func = '''
    fun getCpuUsage(context: Context): String {
        return try {
            // روش اول: از ActivityManager
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            // خواندن CPU از /proc/stat (در اندروید ممکنه محدود باشه، پس از fallback استفاده می‌کنیم)
            val cpuUsage = try {
                val reader = java.io.BufferedReader(java.io.FileReader("/proc/stat"))
                val line = reader.readLine()
                reader.close()
                if (line != null && line.startsWith("cpu ")) {
                    val parts = line.split(Regex("\\\\s+"))
                    val user = parts[1].toLongOrNull() ?: 0
                    val nice = parts[2].toLongOrNull() ?: 0
                    val system = parts[3].toLongOrNull() ?: 0
                    val idle = parts[4].toLongOrNull() ?: 0
                    val total = user + nice + system + idle
                    if (total > 0) "${((user + nice + system) * 100 / total)}%" else "N/A"
                } else "N/A"
            } catch (e: Exception) {
                "N/A (Permission denied)"
            }
            
            "CPU: $cpuUsage, RAM: ${(memoryInfo.availMem * 100 / memoryInfo.totalMem)}% free"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
'''

# اضافه کردن قبل از تابع getSpeechIntent
if 'fun getCpuUsage' not in s:
    s = s.replace('    fun getSpeechIntent(): Intent {', new_func + '\n    fun getSpeechIntent(): Intent {')
    p.write_text(s, encoding="utf-8")
    print("✅ getCpuUsage added to HardwareBridge!")
else:
    print("ℹ️ getCpuUsage already exists.")

# اضافه کردن به getSystemInfo
old_sysinfo = '''    fun getSystemInfo(context: Context): String {
        return "${getBatteryLevel(context)} battery, ${getBatteryTemp(context)}, ${getRamInfo(context)}, ${getStorageInfo()}, ${getNetworkInfo(context)}"
    }'''

new_sysinfo = '''    fun getSystemInfo(context: Context): String {
        return "${getBatteryLevel(context)} battery, ${getBatteryTemp(context)}, ${getRamInfo(context)}, ${getStorageInfo()}, ${getNetworkInfo(context)}, ${getCpuUsage(context)}"
    }'''

if old_sysinfo in s:
    s = s.replace(old_sysinfo, new_sysinfo)
    p.write_text(s, encoding="utf-8")
    print("✅ getSystemInfo updated with CPU usage!")

