package com.example.mylinuxterminal

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import java.lang.ref.WeakReference
import java.util.Locale

object HardwareBridge {
    private const val TAG = "HardwareBridge"
    private var tts: TextToSpeech? = null
    private var appContext: Context? = null
    private var currentActivity: WeakReference<Activity>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        initTts(context)
    }

    fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
    }

    fun setActivity(activity: Activity?) {
        currentActivity = activity?.let { WeakReference(it) }
    }

    fun getContext(): Context? = appContext

    fun speak(text: String): String {
        return try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hw_tts"); "Spoke: $text" } 
        catch (e: Exception) { "Error: ${e.message}" }
    }

    fun toggleWifi(context: Context, enable: Boolean): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "WiFi settings opened"
            } else {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enable
                "WiFi ${if (enable) "ON" else "OFF"}"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun toggleBluetooth(enable: Boolean): String {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "Bluetooth not supported"
            @Suppress("DEPRECATION")
            if (enable) adapter.enable() else adapter.disable()
            "Bluetooth ${if (enable) "ON" else "OFF"}"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun setBrightness(context: Context, level: Int): String {
        return try {
            val clamped = level.coerceIn(1, 255)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
            }
            currentActivity?.get()?.runOnUiThread {
                try {
                    val params = currentActivity?.get()?.window?.attributes
                    params?.screenBrightness = clamped / 255f
                    currentActivity?.get()?.window?.attributes = params
                } catch (e: Exception) { Log.e(TAG, "window brightness failed", e) }
            }
            "Brightness set to $clamped"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // 🆕 قابلیت ویبره
    fun vibrate(context: Context, ms: Long): String {
        return try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
            "Vibrated for ${ms}ms"
        } catch (e: Exception) { "Error vibrating: ${e.message}" }
    }

    // 🆕 قابلیت کنترل صدا (0 تا 100)
    fun setVolume(context: Context, level: Int): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = (level.coerceIn(0, 100) * maxVol / 100)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            "Volume set to $level%"
        } catch (e: Exception) { "Error setting volume: ${e.message}" }
    }

    fun getSpeechIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
        return intent
    }
}
