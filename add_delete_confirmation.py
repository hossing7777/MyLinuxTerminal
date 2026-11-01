import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/MainActivity.kt")
s = p.read_text(encoding="utf-8")

print("🔍 Searching for delete button code...")

# پیدا کردن دکمه Delete (که در مرحله قبل اضافه کردیم)
old_delete = '''IconButton(onClick = {
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
                        }'''

new_delete = '''IconButton(onClick = {
                            // ذخیره فایل برای حذف در دیالوگ
                            deleteTarget = file
                            showDeleteDialog = true
                        }) {
                            Icon(Icons.Default.Delete, "Delete", tint = Color.Red)
                        }'''

if old_delete in s:
    s = s.replace(old_delete, new_delete)
    print("✅ Delete button now triggers dialog instead of direct deletion.")

# اضافه کردن متغیرهای حالت به FileViewerScreen
old_state = '''fun FileViewerScreen() {
    var files by remember { mutableStateOf(listOf<File>()) }
    var selectedFileContent by remember { mutableStateOf<String?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var currentPath by remember { mutableStateOf("/sdcard/Download") }
    var previewAsHtml by remember { mutableStateOf(false) }'''

new_state = '''fun FileViewerScreen() {
    val context = LocalContext.current
    var files by remember { mutableStateOf(listOf<File>()) }
    var selectedFileContent by remember { mutableStateOf<String?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var currentPath by remember { mutableStateOf("/sdcard/Download") }
    var previewAsHtml by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }'''

if old_state in s:
    s = s.replace(old_state, new_state)
    print("✅ State variables added.")

# اضافه کردن دیالوگ تایید (قبل از بسته شدن FileViewerScreen)
old_end = '''        if (selectedFileContent != null) {
            AlertDialog(
                onDismissRequest = { selectedFileContent = null },
                title = { Text(selectedFileName, color = Color.Cyan) },'''

new_end = '''        if (showDeleteDialog && deleteTarget != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false; deleteTarget = null },
                title = { Text("Confirm Delete", color = Color.Red, fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to delete:\\n${deleteTarget?.name}?\\n\\nThis action cannot be undone.", color = Color.White) },
                confirmButton = {
                    Button(onClick = {
                        try {
                            if (deleteTarget?.deleteRecursively() == true) {
                                android.widget.Toast.makeText(context, "Deleted: ${deleteTarget?.name}", android.widget.Toast.LENGTH_SHORT).show()
                                val dir = File(currentPath)
                                files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
                            } else {
                                android.widget.Toast.makeText(context, "Failed to delete", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        showDeleteDialog = false
                        deleteTarget = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Delete", color = Color.White) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false; deleteTarget = null }) { Text("Cancel", color = Color.Cyan) }
                }
            )
        }
        
        if (selectedFileContent != null) {
            AlertDialog(
                onDismissRequest = { selectedFileContent = null },
                title = { Text(selectedFileName, color = Color.Cyan) },'''

if old_end in s:
    s = s.replace(old_end, new_end)
    print("✅ Confirmation dialog added!")

p.write_text(s, encoding="utf-8")
print("✅ Done!")
