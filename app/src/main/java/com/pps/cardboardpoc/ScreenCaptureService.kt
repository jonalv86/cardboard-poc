package com.pps.cardboardpoc

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service dueño del pipeline de captura: consume el token de MediaProjection, lee los
 * frames con un ImageReader y los publica en la ventana overlay.
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlay: StereoOverlay? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Un arranque sin action solo pone el service en foreground: Android 14 lo exige activo
        // antes de que MainActivity pida el permiso de captura.
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startCapture(resultCode, resultData)
                }
            }

            ACTION_STOP -> stopSelf()
        }

        // El token de proyección no se puede reusar, así que un reinicio con intent nulo no sirve.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseCapture()
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) return

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: return
        mediaProjection = projection

        // Obligatorio en Android 14+: registrar el callback ANTES de createVirtualDisplay,
        // si no, tira IllegalStateException y la app se cierra.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCapture()
                stopSelf()
            }
        }, mainHandler)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        overlay = StereoOverlay(this).apply { show() }

        // La conversión Image -> Bitmap copia la pantalla completa por frame; en el main thread
        // trabaría el dibujado del propio overlay.
        val thread = HandlerThread("CaptureThread").apply { start() }
        captureThread = thread

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = imageToBitmap(image)
            image.close()
            mainHandler.post { overlay?.updateFrame(bitmap) }
        }, Handler(thread.looper))

        virtualDisplay = projection.createVirtualDisplay(
            "CardboardPocCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    /**
     * Libera la captura y quita el overlay. Anula cada referencia antes de cerrar el recurso, para
     * que una segunda entrada (stop() dispara onStop(), que vuelve a llamar acá) no toque nada dos veces.
     */
    private fun releaseCapture() {
        val display = virtualDisplay
        virtualDisplay = null
        display?.release()

        val reader = imageReader
        imageReader = null
        reader?.close()

        val projection = mediaProjection
        mediaProjection = null
        projection?.stop()

        val thread = captureThread
        captureThread = null
        thread?.quitSafely()

        val window = overlay
        overlay = null
        window?.hide()
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID, "Captura de pantalla", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopAction = PendingIntent.getService(
            this,
            0,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CardboardPOC")
            .setContentText("Capturando pantalla (prueba de concepto)")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopAction)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val ACTION_START = "com.pps.cardboardpoc.action.START_CAPTURE"
        private const val ACTION_STOP = "com.pps.cardboardpoc.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** Intent que arranca la captura con el token devuelto por el diálogo de MediaProjection. */
        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        /** Intent que detiene la captura y baja el service. */
        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }
}
