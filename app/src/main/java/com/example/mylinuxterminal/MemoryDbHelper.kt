package com.example.mylinuxterminal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MemoryDbHelper(context: Context) : SQLiteOpenHelper(context, "AgentMemory.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        // استفاده از FTS (Full Text Search) برای حافظه برداری و جستجوی دانش
        db.execSQL("CREATE VIRTUAL TABLE memory USING fts4(type, content, timestamp)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS memory")
        onCreate(db)
    }

    fun addMemory(type: String, content: String) {
        try {
            val values = ContentValues().apply { 
                put("type", type)
                put("content", content)
                put("timestamp", System.currentTimeMillis())
            }
            writableDatabase.insert("memory", null, values)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // جستجوی هوشمند در حافظه بر اساس کلمات کلیدی درخواست جدید
    fun getRelevantMemory(query: String): String {
        val sb = StringBuilder()
        try {
            val keywords = query.split(" ").filter { it.length > 3 }.take(3).joinToString(" OR ")
            val searchQuery = if (keywords.isBlank()) "*" else keywords
            val cursor = readableDatabase.rawQuery("SELECT type, content FROM memory WHERE memory MATCH ? ORDER BY timestamp DESC LIMIT 5", arrayOf(searchQuery))
            while (cursor.moveToNext()) { 
                sb.append("- [${cursor.getString(0)}] ${cursor.getString(1)}\n") 
            }
            cursor.close()
        } catch (e: Exception) { 
            // اگر جستجو خطا داد، آخرین ۵ مورد را برگردان
            try {
                val cursor = readableDatabase.rawQuery("SELECT type, content FROM memory ORDER BY timestamp DESC LIMIT 5", null)
                while (cursor.moveToNext()) { sb.append("- [${cursor.getString(0)}] ${cursor.getString(1)}\n") }
                cursor.close()
            } catch (e2: Exception) {}
        }
        return sb.toString()
    }
}
