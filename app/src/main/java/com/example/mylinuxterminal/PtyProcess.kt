package com.example.mylinuxterminal

class PtyProcess {
    external fun createPty(): Int
    external fun execCommand(cmd: String)
    external fun readData(buffer: ByteArray): Int
    external fun writeData(data: String)
    
    companion object {
        init {
            System.loadLibrary("pty")
        }
    }
}
