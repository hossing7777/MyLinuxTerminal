package com.example.mylinuxterminal

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
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

    /** جایگزین initTts - یک بار در onCreate صدا زده می‌شود */
    fun init(context: Context) {
        appContext = context.applicationContext
        initTts(context)
    }

    fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    fun setActivity(activity: Activity?) {
        currentActivity = activity?.let { WeakReference(it) }
    }

    fun getContext(): Context? = appContext

    fun speak(text: String): String {
        return try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hw_tts")
            "Spoke: $text"
        } catch (e: Exception) {
            "Error speaking: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun toggleWifi(context: Context, enable: Boolean): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "WiFi settings opened (manual toggle needed on API 29+)"
            } else {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enable
                "WiFi turned ${if (enable) "ON" else "OFF"}"
            }
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun toggleBluetooth(enable: Boolean): String {
        return try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                ?: return "Bluetooth not supported"
            @Suppress("DEPRECATION")
            if (enable) bluetoothAdapter.enable() else bluetoothAdapter.disable()
            "Bluetooth ${if (enable) "ON" else "OFF"}"
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * روشنایی را هم در تنظیمات سیستم ذخیره می‌کند و هم فوراً روی پنجره‌ی فعال اعمال می‌کند.
     * level: 1..255
     */
    fun setBrightness(context: Context, level: Int): String {
        return try {
            val clamped = level.coerceIn(1, 255)

            // ---- ۱) ذخیره در تنظیمات سیستم (پایدار) ----
            var systemPart = "skipped(no WRITE_SETTINGS)"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
                try {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        clamped
                    )
                    systemPart = "system=$clamped"
                } catch (e: Exception) {
                    systemPart = "system_err=${e.message ?: e.javaClass.simpleName}"
                }
            }

            // ---- ۲) اعمال فوری روی Window (اثر آنی) ----
            var windowPart = "no_activity"
            val activity = currentActivity?.get()
            if (activity != null) {
                activity.runOnUiThread {
                    try {
                        val params = activity.window.attributes
                        params.screenBrightness = clamped / 255f
                        activity.window.attributes = params
                    } catch (e: Exception) {
                        Log.e(TAG, "window brightness failed", e)
                    }
                }
                windowPart = "window=${"%.2f".format(clamped / 255f)}"
            }

            "Brightness set to $clamped ($systemPart, $windowPart)"
        } catch (e: Exception) {
            "Error setting brightness: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun getSpeechIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
        return intent
    }
}
