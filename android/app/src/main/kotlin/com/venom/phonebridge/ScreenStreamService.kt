package com.venom.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.max

class ScreenStreamService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streaming = false

    private var screenWidth = 720
    private var screenHeight = 1560
    private var screenDensity = 320

    companion object {
        const val PORT = 8888
        const val NOTIF_CHANNEL = "phonebridge_channel"
        const val NOTIF_ID = 1

        @Volatile var isRunning = false
        @Volatile var isClientConnected = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        val metrics = DisplayMetrics()
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display.getRealMetrics(metrics)
        // Scale down for bandwidth/CPU — plenty sharp for control purposes.
        val scale = max(metrics.widthPixels, metrics.heightPixels).toFloat() / 900f
        screenWidth = (metrics.widthPixels / scale).toInt().coerceAtLeast(2) and 0xFFFFFE.toInt()
        screenHeight = (metrics.heightPixels / scale).toInt().coerceAtLeast(2)
        screenDensity = metrics.densityDpi

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        setupVirtualDisplay()
        startServer()

        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PhoneBridge",
            screenWidth, screenHeight, screenDensity,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                broadcastStatus(running = true, clientConnected = false)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    clientSocket = socket
                    broadcastStatus(running = true, clientConnected = true)
                    handleClient(socket)
                    broadcastStatus(running = true, clientConnected = false)
                }
            } catch (e: Exception) {
                broadcastStatus(running = false, clientConnected = false)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = coroutineScope {
        val outputJob = launch { streamFrames(socket) }
        val inputJob = launch { readCommands(socket) }
        outputJob.join()
        inputJob.cancel()
        socket.close()
    }

    private suspend fun streamFrames(socket: Socket) {
        val out = DataOutputStream(socket.getOutputStream())
        val reader = imageReader ?: return
        streaming = true
        try {
            while (streaming && socket.isConnected) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val jpeg = imageToJpeg(image)
                    image.close()
                    if (jpeg != null) {
                        out.writeInt(jpeg.size)
                        out.write(jpeg)
                        out.flush()
                    }
                }
                delay(66) // ~15fps target; tune based on measured hotspot throughput
            }
        } catch (e: Exception) {
            // client disconnected
        }
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            bitmap.recycle()
            cropped.recycle()
            stream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun readCommands(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                dispatchCommand(line)
            }
        } catch (e: Exception) {
            // ignore, client likely disconnected
        }
    }

    private fun dispatchCommand(line: String) {
        try {
            val json = JSONObject(line)
            val service = ControlAccessibilityService.instance ?: return
            // Coordinates arrive scaled to the phone's real screen; the PC
            // client is responsible for that scaling using the resolution
            // it was told about (extend the protocol to send it on connect).
            when (json.getString("type")) {
                "tap" -> service.tap(json.getInt("x"), json.getInt("y"))
                "swipe" -> service.swipe(
                    json.getInt("x1"), json.getInt("y1"),
                    json.getInt("x2"), json.getInt("y2"),
                    json.optInt("ms", 200)
                )
                "back" -> service.globalBack()
                "home" -> service.globalHome()
                "recents" -> service.globalRecents()
            }
        } catch (e: Exception) {
            // malformed command, ignore
        }
    }

    private fun broadcastStatus(running: Boolean, clientConnected: Boolean) {
        isRunning = running
        isClientConnected = clientConnected
        val intent = Intent("com.venom.phonebridge.STATUS").apply {
            setPackage(packageName)
            putExtra("running", running)
            putExtra("clientConnected", clientConnected)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL, "Phone Bridge", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Phone Bridge active")
            .setContentText("Streaming this screen to your PC")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        streaming = false
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        broadcastStatus(running = false, clientConnected = false)
        super.onDestroy()
    }
}
