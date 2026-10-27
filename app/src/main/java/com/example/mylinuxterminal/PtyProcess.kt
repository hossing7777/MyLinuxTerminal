package com.example.mylinuxterminal

class PtyProcess {
    external fun createPty(prootPath: String, alpinePath: String, tmpDir: String, loaderPath: String, nativeLibDir: String, installCmd: String): Int
    external fun readData(buffer: ByteArray): Int
    external fun writeData(data: String)
    
    companion object {
        init {
            System.loadLibrary("pty")
        }
    }
}
