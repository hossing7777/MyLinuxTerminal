package com.example.mylinuxterminal

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RobotEyesService : AccessibilityService() {
    companion object { var instance: RobotEyesService? = null }
    
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNotEmpty()) {
            var node: AccessibilityNodeInfo? = nodes[0]
            while (node != null) {
                if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                node = node.parent
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun getCurrentUrl(): String {
        val root = rootInActiveWindow ?: return ""
        // تلاش برای پیدا کردن نوار آدرس کروم
        val editTexts = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
        if (editTexts.isNotEmpty()) {
            return editTexts[0].text?.toString() ?: ""
        }
        // اگر پیدا نشد، نودهای حاوی http را جستجو کن
        try {
            val nodes = root.findAccessibilityNodeInfosByText("http")
            for (node in nodes) {
                val text = node.text?.toString() ?: ""
                if (text.startsWith("http") || text.contains("deepseek")) {
                    return text
                }
            }
        } catch (e: Exception) {
            return ""
        }
        return ""
    }
}
