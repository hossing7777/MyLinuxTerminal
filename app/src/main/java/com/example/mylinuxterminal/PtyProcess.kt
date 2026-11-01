package com.example.mylinuxterminal

class PtyProcess {
    external fun createPty(scriptPath: String, prootPath: String, alpinePath: String, tmpDir: String, loaderPath: String, libDir: String, filesDir: String): Int
    external fun readData(buffer: ByteArray): Int
    external fun writeData(data: String)
    
    companion object {
        init { System.loadLibrary("pty") }
    }
}
