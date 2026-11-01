import pathlib

p = pathlib.Path("app/src/main/java/com/example/mylinuxterminal/RobotEyesService.kt")
s = p.read_text(encoding="utf-8")

# اضافه کردن توابع جدید به RobotEyesService
new_funcs = '''
    fun getCurrentUrl(): String {
        val root = rootInActiveWindow ?: return ""
        // پیدا کردن نوار آدرس (معمولاً از نوع EditText با متن URL)
        val editTexts = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
        if (editTexts.isNotEmpty()) {
            return editTexts[0].text?.toString() ?: ""
        }
        // تلاش دوم: هر EditText که شامل http باشه
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        root.findAccessibilityNodeInfosByText("http", allNodes)
        return allNodes.firstOrNull()?.text?.toString() ?: ""
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
'''

# جایگزینی تابع typeTextInFocused قدیمی با توابع جدید
old_func = '''    fun typeTextInFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }'''

if old_func in s:
    s = s.replace(old_func, new_funcs)
    p.write_text(s, encoding="utf-8")
    print("✅ RobotEyesService upgraded with getCurrentUrl()!")
else:
    print("⚠️ Function not found. Adding new functions anyway...")
    s = s.replace('fun clickByText(text: String): Boolean {', new_funcs + '\n    fun clickByText(text: String): Boolean {')
    p.write_text(s, encoding="utf-8")
    print("✅ Added new functions.")
