import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

print("🔍 Searching for FileViewerScreen item rendering...")

# پیدا کردن بخشی که فایل‌ها رو در FileViewerScreen رندر می‌کنه
old_item = '''items(files) { file ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                    if (file.isDirectory) currentPath = file.absolutePath
                    else { try { selectedFileContent = file.readText(); selectedFileName = file.name; previewAsHtml = false } catch (e: Exception) { selectedFileContent = "Error: ${e.message}"; selectedFileName = file.name } }
                }, colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (file.isDirectory) "📁 " else "📄 ", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column { Text(file.name, color = Color.White, fontWeight = FontWeight.Bold); Text("${file.length()} bytes", color = Color.Gray, fontSize = 10.sp) }
                    }
                }
            }'''

new_item = '''items(files) { file ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(modifier = Modifier.weight(1f).clickable {
                            if (file.isDirectory) currentPath = file.absolutePath
                            else { try { selectedFileContent = file.readText(); selectedFileName = file.name; previewAsHtml = false } catch (e: Exception) { selectedFileContent = "Error: ${e.message}"; selectedFileName = file.name } }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (file.isDirectory) "📁 " else "📄 ", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column { Text(file.name, color = Color.White, fontWeight = FontWeight.Bold); Text("${file.length()} bytes", color = Color.Gray, fontSize = 10.sp) }
                        }
                        IconButton(onClick = {
                            try {
                                if (file.deleteRecursively()) {
                                    android.widget.Toast.makeText(context, "Deleted: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                                    // رفرش لیست
                                    val dir = File(currentPath)
                                    files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
                                } else {
                                    android.widget.Toast.makeText(context, "Failed to delete", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Delete, "Delete", tint = Color.Red)
                        }
                    }
                }
            }'''

if old_item in s:
    s = s.replace(old_item, new_item)
    p.write_text(s, encoding="utf-8")
    print("✅ Delete button added to FileViewer!")
else:
    print("❌ FileViewer item block not found. Trying alternate...")
    # تلاش دوم: اگر ساختار کمی متفاوت بود
    if 'items(files) { file ->' in s and 'Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable' in s:
        print("⚠️ Found similar structure. Please check manually.")
    else:
        print("❌ Could not find FileViewer. Please check MainActivity.kt manually.")
