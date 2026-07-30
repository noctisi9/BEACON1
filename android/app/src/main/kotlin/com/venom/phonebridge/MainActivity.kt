package com.venom.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : FlutterActivity() {
    private val CONTROL_CHANNEL = "phonebridge/control"
    private val STATUS_CHANNEL = "phonebridge/status"
    private val PROJECTION_REQUEST_CODE = 42

    private var eventSink: EventChannel.EventSink? = null
    private var statusReceiver: BroadcastReceiver? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTROL_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getLocalIp" -> result.success(getLocalIpAddress())
                    "isAccessibilityEnabled" -> result.success(isAccessibilityServiceEnabled())
                    "getStatus" -> result.success(
                        mapOf(
                            "running" to ScreenStreamService.isRunning,
                            "clientConnected" to ScreenStreamService.isClientConnected,
                            "accessibilityEnabled" to isAccessibilityServiceEnabled()
                        )
                    )
                    "startService" -> {
                        requestProjectionPermission()
                        result.success(null)
                    }
                    "stopService" -> {
                        stopService(Intent(this, ScreenStreamService::class.java))
                        result.success(null)
                    }
                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, STATUS_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(args: Any?, sink: EventChannel.EventSink?) {
                    eventSink = sink
                    registerStatusReceiver()
                }

                override fun onCancel(args: Any?) {
                    eventSink = null
                    statusReceiver?.let { unregisterReceiver(it) }
                    statusReceiver = null
                }
            })
    }

    private fun registerStatusReceiver() {
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val map = mapOf(
                    "running" to (intent?.getBooleanExtra("running", false) ?: false),
                    "clientConnected" to (intent?.getBooleanExtra("clientConnected", false) ?: false),
                    "accessibilityEnabled" to isAccessibilityServiceEnabled()
                )
                eventSink?.success(map)
            }
        }
        val filter = IntentFilter("com.venom.phonebridge.STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        // push an initial status immediately so the UI isn't blank
        eventSink?.success(
            mapOf(
                "running" to false,
                "clientConnected" to false,
                "accessibilityEnabled" to isAccessibilityServiceEnabled()
            )
        )
    }

    private fun requestProjectionPermission() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), PROJECTION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenStreamService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${ControlAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    /** Prefers the hotspot interface (usually starts with "ap" or "wlan") so the
     * IP shown is the one your PC should actually connect to. */
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidates = mutableListOf<Pair<String, String>>()
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        candidates.add(intf.name to addr.hostAddress!!)
                    }
                }
            }
            // hotspot interfaces are commonly named ap0, swlan0, wlan0 depending on OEM
            val hotspot = candidates.firstOrNull {
                it.first.startsWith("ap") || it.first.startsWith("swlan")
            }
            return hotspot?.second ?: candidates.firstOrNull()?.second ?: "unknown"
        } catch (e: Exception) {
            return "unknown"
        }
    }
}
