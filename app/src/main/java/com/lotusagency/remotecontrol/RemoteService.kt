package com.lotusagency.remotecontrol

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class RemoteService : Service() {

    private var ws: WebSocket? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var screenW = 1080
    private var screenH = 2400
    private var density = 420
    private var isStreaming = false
    private var serverUrl = Config.FALLBACK_URL

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projResult = intent?.getIntExtra("projection_result", -1) ?: -1
        val projData   = intent?.getParcelableExtra<Intent>("projection_data")

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        density = metrics.densityDpi

        if (projData != null && projResult == Activity.RESULT_OK) {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(projResult, projData)
            setupImageReader()
        }

        scope.launch { connectWebSocket() }
        return START_STICKY
    }

    private fun setupImageReader() {
        val w = screenW / 2
        val h = screenH / 2
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "LotusRemote", w, h, density / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun captureScreen(): ByteArray? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return null
            val plane     = image.planes[0]
            val buffer    = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val w = image.width
            val h = image.height
            val padding = rowStride - pixStride * w
            val bmp = Bitmap.createBitmap(w + padding / pixStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
            bmp.recycle()
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 55, out)
            cropped.recycle()
            out.toByteArray()
        } catch (e: Exception) { null }
    }

    private suspend fun connectWebSocket() {
        // Fetch URL mới từ Gist mỗi lần kết nối
        updateNotification("Fetching server URL...")
        serverUrl = withContext(Dispatchers.IO) { Config.fetchServerUrl() }
        updateNotification("Connecting to server...")

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        while (scope.isActive) {
            // Refresh URL mỗi lần reconnect
            val currentUrl = withContext(Dispatchers.IO) { Config.fetchServerUrl() }
            if (currentUrl != serverUrl) {
                serverUrl = currentUrl
                updateNotification("Server URL updated!")
            }

            val request = Request.Builder().url(serverUrl).build()
            val latch = java.util.concurrent.CountDownLatch(1)

            try {
                ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        val msg = JSONObject().apply {
                            put("token",     Config.makeToken())
                            put("device_id", Config.DEVICE_ID)
                            put("role",      "android")
                            put("info", JSONObject().apply {
                                put("model",   Build.MODEL)
                                put("android", Build.VERSION.RELEASE)
                                put("width",   screenW)
                                put("height",  screenH)
                            })
                        }
                        ws.send(msg.toString())
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        try {
                            val msg = JSONObject(text)
                            when (msg.getString("type")) {
                                "auth_ok"    -> {
                                    updateNotification("Online: ${Config.DEVICE_ID}")
                                    isStreaming = true
                                    scope.launch { streamLoop(ws) }
                                }
                                "tap"        -> handleTap(msg)
                                "swipe"      -> handleSwipe(msg)
                                "key"        -> handleKey(msg)
                                "text"       -> handleText(msg)
                                "screenshot" -> scope.launch { sendFrame(ws) }
                                "unlock_pin" -> handleUnlockPin(msg)
                            }
                        } catch (_: Exception) {}
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        isStreaming = false
                        updateNotification("Reconnecting...")
                        latch.countDown()
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        isStreaming = false
                        latch.countDown()
                    }
                })
                latch.await()
            } catch (_: Exception) {}
            delay(4000)
        }
    }

    private suspend fun streamLoop(ws: WebSocket) {
        while (isStreaming && scope.isActive) {
            sendFrame(ws)
            delay(800)
        }
    }

    private suspend fun sendFrame(ws: WebSocket) {
        val data = withContext(Dispatchers.IO) { captureScreen() } ?: return
        try { ws.send(data.toByteString()) } catch (_: Exception) {}
    }

    private fun handleTap(msg: JSONObject) {
        execShell("input tap ${msg.getInt("x")} ${msg.getInt("y")}")
    }

    private fun handleSwipe(msg: JSONObject) {
        execShell("input swipe ${msg.getInt("x1")} ${msg.getInt("y1")} ${msg.getInt("x2")} ${msg.getInt("y2")} ${msg.optInt("dur",300)}")
    }

    private fun handleKey(msg: JSONObject) {
        execShell("input keyevent ${msg.getInt("code")}")
    }

    private fun handleText(msg: JSONObject) {
        var txt = msg.getString("text")
            .replace("\\","\\\\").replace("'","\\'")
            .replace("\"","\\\"").replace(" ","%s")
            .replace("&","\\&").replace(";","\\;")
        execShell("input text $txt")
    }

    private fun handleUnlockPin(msg: JSONObject) {
        val pin = msg.getString("pin")
        execShell("input keyevent 224")
        Thread.sleep(600)
        execShell("input swipe 540 1800 540 600 400")
        Thread.sleep(900)
        for (d in pin) {
            execShell("input keyevent ${7 + d.digitToInt()}")
            Thread.sleep(130)
        }
        execShell("input keyevent 66")
    }

    private fun execShell(cmd: String) {
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)) } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(Config.NOTIF_CH, "Lotus Remote", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Config.NOTIF_CH)
            .setContentTitle("Lotus Remote Control")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    override fun onDestroy() {
        isStreaming = false
        scope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        ws?.close(1000, "Stopped")
        super.onDestroy()
    }
}
