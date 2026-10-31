package com.example.mylinuxterminal

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
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
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
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

    fun setBrightness(context: Context, level: Int): String {
        return try {
            // نیاز به دسترسی WRITE_SETTINGS دارد
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 255))
            "Brightness set to $level"
        } catch (e: Exception) { "Error setting brightness: ${e.message}" }
    }

    // دستور میکروفون (فقط تریگر را برمی‌گرداند، پیاده‌سازی UI برای شنیدن نیاز به Activity دارد)
    fun getSpeechIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR") // زبان فارسی
        return intent
    }
}
