package com.example.mylinuxterminal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MemoryDbHelper(context: Context) : SQLiteOpenHelper(context, "AgentMemory.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memory (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, content TEXT, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun addMemory(type: String, content: String) {
        try {
            val values = ContentValues().apply {
                put("type", type)
                put("content", content)
            }
            writableDatabase.insert("memory", null, values)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getMemoryContext(): String {
        val sb = StringBuilder()
        try {
            val cursor = readableDatabase.rawQuery("SELECT type, content FROM memory ORDER BY id DESC LIMIT 15", null)
            while (cursor.moveToNext()) {
                sb.append("- [${cursor.getString(0)}] ${cursor.getString(1)}\n")
            }
            cursor.close()
        } catch (e: Exception) { e.printStackTrace() }
        return sb.toString()
    }
}
