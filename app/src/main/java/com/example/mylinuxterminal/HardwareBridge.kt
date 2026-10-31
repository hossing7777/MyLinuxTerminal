package com.example.mylinuxterminal

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale

object HardwareBridge {
    private var tts: TextToSpeech? = null

    fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) { tts?.language = Locale.US }
        }
    }

    fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }

    fun toggleWifi(context: Context, enable: Boolean): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "WiFi settings opened"
            } else {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.isWifiEnabled = enable
                "WiFi turned ${if (enable) "ON" else "OFF"}"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun toggleBluetooth(enable: Boolean): String {
        return try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null) return "Bluetooth not supported"
            if (enable) { bluetoothAdapter.enable(); "Bluetooth ON" } else { bluetoothAdapter.disable(); "Bluetooth OFF" }
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}
