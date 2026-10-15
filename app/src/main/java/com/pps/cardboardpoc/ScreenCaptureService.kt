package com.pps.cardboardpoc

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service dueño del pipeline de captura: consume el token de MediaProjection, lee los
 * frames con un ImageReader y los publica en la ventana overlay.
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Serializa el uso del ImageReader entre CaptureThread y el main thread, que lo cierra. */
    private val captureLock = Any()

    @Volatile
    private var overlay: StereoOverlay? = null

    /** Captura por ventanas, para cuando la tarea capturada no está a la vista. Null antes de Android 14. */
    private var windowSource: WindowScreenshotSource? = null

    private val startWindowSource = Runnable {
        Log.d(TAG, "fuente: ventanas")
        windowSource?.start()
    }

    /**
     * Habilita las capturas del sistema sobre la ventana estéreo si vencido el plazo de
     * [checkFullScreenChoice] no se detectó la pantalla completa: la captura es de una sola app.
     */
    private val allowScreenCapture = Runnable {
        if (!fullScreenRejectedState.value) overlay?.allowScreenCapture()
    }
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** Si la sesión lee una pantalla virtual propia, vía Shizuku, en vez de la proyección. */
    private var virtualMode = false
    private var shizukuDisplay: ShizukuDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var framesSeen = 0

    /** Estado de [checkFullScreenChoice]; solo lo toca CaptureThread después del arranque. */
    private var fullScreenCheckDone = false
    private var blackStartupFrames = 0
    @Volatile
    private var captureStartMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    // El tipo mediaProjection solo se acepta una vez que el usuario aprobó la captura,
                    // y getMediaProjection exige que el service ya esté en foreground.
                    startForegroundAsProjection()
                    startCapture(resultCode, resultData)
                }
            }

            ACTION_START_VIRTUAL -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                startVirtual(intent.getStringExtra(EXTRA_OPEN_PACKAGE))
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

    private fun startForegroundAsProjection() {
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

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) return

        val host = JoystickManager.accessibilityService
        if (host == null) {
            Log.w(TAG, "servicio de accesibilidad inactivo: sin él no hay ventana estéreo")
            stopSelf()
            return
        }

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: return
        mediaProjection = projection

        // Obligatorio en Android 14+: registrar el callback ANTES de createVirtualDisplay,
        // si no, tira IllegalStateException y la app se cierra.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "el sistema detuvo la proyección")
                releaseCapture()
                stopSelf()
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                Log.d(TAG, "contenido capturado visible=$isVisible")
                onCapturedVisibilityChanged(isVisible)
            }
        }, mainHandler)

        overlay = StereoOverlay(host).apply { show() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            windowSource = WindowScreenshotSource(host) { frame -> overlay?.updateFrame(frame) }
        }
        capturedTaskVisible = true
        fullScreenCheckDone = false
        blackStartupFrames = 0
        captureStartMs = SystemClock.uptimeMillis()
        fullScreenRejectedState.value = false
        mainHandler.postDelayed(allowScreenCapture, FULL_SCREEN_CHECK_WINDOW_MS)
        isCapturing = true
        host.setCaptureActive(true)

        // La conversión Image -> Bitmap copia la pantalla completa por frame; en el main thread
        // trabaría el dibujado del propio overlay.
        captureThread = HandlerThread("CaptureThread").apply { start() }

        rebuildCapture()
        registerDisplayListener()

        mainHandler.post(decideStartScreen)
    }

    /**
     * Arranca el visor sobre una pantalla virtual propia: sin diálogo de captura ni selector, y toda
     * app corre ahí a fps completos. El panel se abre en esa pantalla y, con [openPackage], abre esa app.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startVirtual(openPackage: String?) {
        if (isCapturing) return
        val host = JoystickManager.accessibilityService
        if (host == null || !ShizukuDisplay.isAvailable()) {
            Log.w(TAG, "pantalla virtual: falta el servicio de accesibilidad o Shizuku con permiso")
            stopSelf()
            return
        }

        virtualMode = true
        // La ventana estéreo nunca entra en lo que se lee de la pantalla virtual.
        overlay = StereoOverlay(host).apply { show(); allowScreenCapture() }
        capturedTaskVisible = true
        fullScreenCheckDone = true
        captureStartMs = SystemClock.uptimeMillis()
        isCapturing = true
        host.setCaptureActive(true)
        captureThread = HandlerThread("CaptureThread").apply { start() }

        rebuildCapture()
        val display = shizukuDisplay
        if (display == null) {
            stopSelf()
            return
        }
        JoystickManager.targetDisplayId = display.displayId
        registerDisplayListener()
        PanelActivity.show(host, openPackage)
    }

    /**
     * Abre el panel solo si en el selector se eligió CeluVR. Con otra app elegida, el panel la
     * taparía y la dejaría fuera de la proyección. El selector corre dentro de nuestra tarea, así que
     * al volver de él la tarea sigue a la vista hasta que termina la transición hacia la app elegida:
     * solo cuenta que siga a la vista pasado [START_SCREEN_WAIT_MS].
     */
    private val decideStartScreen = object : Runnable {
        override fun run() {
            val waited = SystemClock.uptimeMillis() - captureStartMs
            if (!isOwnTaskVisible()) {
                Log.d(TAG, "se eligió otra app ($waited ms): se la ve directo, sin panel")
                return
            }
            if (waited < START_SCREEN_WAIT_MS) {
                mainHandler.postDelayed(this, START_SCREEN_POLL_MS)
                return
            }
            Log.d(TAG, "se eligió CeluVR: se abre el panel")
            JoystickManager.accessibilityService?.let { PanelActivity.show(it) }
        }
    }

    private fun isOwnTaskVisible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return getSystemService(ActivityManager::class.java).appTasks.any { it.taskInfo.isVisible }
    }

    /**
     * Elige de dónde salen los frames según si la tarea capturada está a la vista. Al ocultarse se
     * congela el último frame y, si sigue oculta pasado [SOURCE_SWITCH_DELAY_MS], arranca la captura
     * por ventanas; la espera descarta los avisos de ocultamiento que el sistema revierte en pocos
     * milisegundos (pasa al cerrar una actividad dentro de la tarea). Al volver a verse, la proyección
     * retoma en el acto.
     */
    private fun onCapturedVisibilityChanged(visible: Boolean) {
        mainHandler.removeCallbacks(startWindowSource)
        capturedTaskVisible = visible
        val source = windowSource ?: return
        if (visible) {
            if (source.isRunning) {
                source.stop()
                Log.d(TAG, "fuente: proyección")
            }
        } else {
            mainHandler.postDelayed(startWindowSource, SOURCE_SWITCH_DELAY_MS)
        }
    }

    /**
     * Crea el ImageReader y el VirtualDisplay con el tamaño actual de la pantalla, o los reajusta si
     * ese tamaño cambió. Corre siempre en el main thread.
     */
    private fun rebuildCapture() {
        val projection = mediaProjection
        if (projection == null && !virtualMode) return
        val thread = captureThread ?: return

        val metrics = currentDisplayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width == captureWidth && height == captureHeight) return

        captureWidth = width
        captureHeight = height
        JoystickManager.setScreenSize(width, height)

        // Soltar la superficie antes de cerrar el reader: si el display sigue escribiendo sobre un
        // reader cerrado, la captura se corta.
        virtualDisplay?.setSurface(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shizukuDisplay?.setSurface(null)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        synchronized(captureLock) {
            imageReader?.close()
            imageReader = reader
        }
        reader.setOnImageAvailableListener({ source ->
            // El main thread cierra el reader al rotar o al detener la captura. Con el lock tomado
            // el reader no se puede cerrar a mitad de la copia, y uno ya reemplazado se descarta.
            val bitmap = synchronized(captureLock) {
                if (source !== imageReader) return@setOnImageAvailableListener
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    imageToBitmap(image)
                } finally {
                    image.close()
                }
            }
            logFrameContent(bitmap)
            checkFullScreenChoice(bitmap)
            if (capturedTaskVisible) mainHandler.post { overlay?.updateFrame(bitmap) }
        }, Handler(thread.looper))

        val screen = currentDisplayMetrics()
        Log.d(
            TAG,
            "VirtualDisplay=${width}x${height} dpi=${metrics.densityDpi} | " +
                "pantalla real=${screen.widthPixels}x${screen.heightPixels} dpi=${screen.densityDpi}"
        )

        if (projection == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                updateShizukuDisplay(reader, width, height, metrics.densityDpi)
            }
            return
        }

        val display = virtualDisplay
        if (display == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "CardboardPocCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        } else {
            display.resize(width, height, metrics.densityDpi)
            display.setSurface(reader.surface)
        }
    }

    /** Crea la pantalla virtual sobre [reader], o la reajusta al tamaño nuevo. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun updateShizukuDisplay(reader: ImageReader, width: Int, height: Int, densityDpi: Int) {
        val display = shizukuDisplay
        if (display != null) {
            display.resize(width, height, densityDpi)
            display.setSurface(reader.surface)
            return
        }
        shizukuDisplay = try {
            ShizukuDisplay.create(reader.surface, width, height, densityDpi)
        } catch (e: Exception) {
            Log.w(TAG, "pantalla virtual: no se pudo crear", e)
            null
        }
    }

    /**
     * Sigue los cambios de la pantalla real. Sin esto el buffer queda con el tamaño que tenía al
     * arrancar la captura, y al rotar el frame llega encajado con bandas negras dentro del tamaño viejo.
     */
    private fun registerDisplayListener() {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) rebuildCapture()
            }
        }
        displayListener = listener
        getSystemService(DisplayManager::class.java).registerDisplayListener(listener, mainHandler)
    }

    /**
     * Muestrea una grilla del frame para distinguir una captura con contenido real de una que llega
     * en negro porque el compositor descartó lo que había en pantalla.
     */
    private fun logFrameContent(frame: Bitmap) {
        if (framesSeen++ % 60 != 0) return

        var conContenido = 0
        var lumaMax = 0
        forEachSample(frame) { luma ->
            if (luma > 8) conContenido++
            if (luma > lumaMax) lumaMax = luma
        }
        Log.d(TAG, "muestreo: $conContenido/100 pixeles con contenido, luma max=$lumaMax")
    }

    /**
     * Detecta que en el selector se eligió la pantalla completa en vez de "una sola app": así la
     * captura incluye la ventana estéreo, que sale en negro por FLAG_SECURE, y los frames llegan
     * enteros en negro sin parar. Los primeros pueden tener contenido, porque se capturan antes de
     * que la ventana estéreo se dibuje, así que lo que cuenta es una racha de frames negros. Solo se
     * mira al principio de la sesión: después, una app en negro (un video, por ejemplo) no tiene
     * que cortarla. Corre en CaptureThread.
     */
    private fun checkFullScreenChoice(frame: Bitmap) {
        if (fullScreenCheckDone) return
        if (SystemClock.uptimeMillis() - captureStartMs > FULL_SCREEN_CHECK_WINDOW_MS) {
            fullScreenCheckDone = true
            return
        }
        var hasContent = false
        forEachSample(frame) { luma -> if (luma > 8) hasContent = true }
        if (hasContent) {
            blackStartupFrames = 0
            return
        }
        if (++blackStartupFrames < FULL_SCREEN_BLACK_FRAMES) return

        fullScreenCheckDone = true
        Log.w(TAG, "se eligió la pantalla completa: dentro del visor sale en negro, se detiene")
        mainHandler.post {
            fullScreenRejectedState.value = true
            stopSelf()
        }
    }

    /** Recorre una grilla de 10 x 10 puntos del frame con el brillo de cada uno (0..255). */
    private inline fun forEachSample(frame: Bitmap, onSample: (luma: Int) -> Unit) {
        for (fila in 0 until 10) {
            for (columna in 0 until 10) {
                val pixel = frame.getPixel(
                    frame.width * columna / 10 + frame.width / 20,
                    frame.height * fila / 10 + frame.height / 20
                )
                onSample(maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)))
            }
        }
    }

    private fun currentDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    /**
     * Libera la captura y quita el overlay. Anula cada referencia antes de cerrar el recurso, para
     * que una segunda entrada (stop() dispara onStop(), que vuelve a llamar acá) no toque nada dos veces.
     */
    private fun releaseCapture() {
        // Primero el listener: si no, una rotación durante el teardown reconstruiría la captura.
        val listener = displayListener
        displayListener = null
        listener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }

        captureWidth = 0
        captureHeight = 0

        val display = virtualDisplay
        virtualDisplay = null
        display?.release()

        val ownDisplay = shizukuDisplay
        shizukuDisplay = null
        if (ownDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                ownDisplay.release()
            } catch (e: Exception) {
                Log.w(TAG, "pantalla virtual: no se pudo liberar", e)
            }
        }
        virtualMode = false
        JoystickManager.targetDisplayId = Display.DEFAULT_DISPLAY

        synchronized(captureLock) {
            val reader = imageReader
            imageReader = null
            reader?.close()
        }

        val projection = mediaProjection
        mediaProjection = null
        projection?.stop()

        val thread = captureThread
        captureThread = null
        thread?.quitSafely()

        mainHandler.removeCallbacks(startWindowSource)
        mainHandler.removeCallbacks(allowScreenCapture)
        mainHandler.removeCallbacks(decideStartScreen)
        val source = windowSource
        windowSource = null
        if (source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            source.release()
        }
        SessionApps.clear()

        val window = overlay
        overlay = null
        window?.hide()
        if (isCapturing) {
            isCapturing = false
            JoystickManager.accessibilityService?.setCaptureActive(false)
            JoystickManager.refreshEdgeContact()
        }
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

        // El buffer del display espejado puede traer alpha < 255; sin descartar el canal alpha el
        // frame se dibuja mezclado con lo que haya detrás.
        val frame = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            .apply { setHasAlpha(false) }
        Log.d(
            TAG,
            "bitmap=${frame.width}x${frame.height} | image=${image.width}x${image.height} " +
                "rowStride=$rowStride pixelStride=$pixelStride padding=$rowPadding"
        )
        return frame
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Capturando pantalla")
            .setSmallIcon(R.drawable.ic_stat_celuvr)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopAction)
            .build()
    }

    companion object {
        private const val TAG = "CardboardCapture"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val ACTION_START = "com.pps.cardboardpoc.action.START_CAPTURE"
        private const val ACTION_STOP = "com.pps.cardboardpoc.action.STOP_CAPTURE"
        private const val ACTION_START_VIRTUAL = "com.pps.cardboardpoc.action.START_VIRTUAL"
        private const val EXTRA_OPEN_PACKAGE = "open_package"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** Cuánto tiene que seguir oculta la tarea capturada para pasar a la captura por ventanas. */
        private const val SOURCE_SWITCH_DELAY_MS = 150L

        /** Cuánto tiene que seguir a la vista nuestra tarea al arrancar para abrir el panel. */
        private const val START_SCREEN_WAIT_MS = 1000L

        /** Cada cuánto se mira, durante esa espera, si nuestra tarea dejó de verse. */
        private const val START_SCREEN_POLL_MS = 50L

        /**
         * Frames enteramente negros seguidos, desde el arranque, que indican la pantalla completa. Con
         * pantalla completa la ventana estéreo se redibuja en cada cuadro, así que se juntan en ~1,5 s;
         * con una sola app los frames solo llegan cuando la pantalla cambia, y una transición en negro
         * no llega a tantos.
         */
        private const val FULL_SCREEN_BLACK_FRAMES = 90

        /** Hasta cuánto después del arranque se busca esa racha de frames negros. */
        private const val FULL_SCREEN_CHECK_WINDOW_MS = 5000L

        private val fullScreenRejectedState = MutableStateFlow(false)

        /** Si la última captura se detuvo porque en el selector se eligió la pantalla completa. */
        val fullScreenRejected: StateFlow<Boolean> = fullScreenRejectedState

        private val capturingState = MutableStateFlow(false)

        /** Si hay una captura en curso, con su ventana estéreo, como flujo observable. */
        val capturing: StateFlow<Boolean> = capturingState

        /**
         * Si la tarea capturada está a la vista. Mientras no lo está, los frames de la proyección no
         * llegan al visor: se queda con el último frame hasta que arranca la captura por ventanas.
         */
        @Volatile
        var capturedTaskVisible = true
            private set

        /** Si hay una captura en curso, con su ventana estéreo. Solo se escribe desde el main thread. */
        var isCapturing: Boolean
            get() = capturingState.value
            private set(value) {
                capturingState.value = value
            }

        /** Intent que arranca la captura con el token devuelto por el diálogo de MediaProjection. */
        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        /**
         * Intent que arranca el visor sobre una pantalla virtual propia, vía Shizuku. Con
         * [openPackage], el panel abre esa app apenas aparece.
         */
        fun startVirtualIntent(context: Context, openPackage: String?): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_VIRTUAL
                putExtra(EXTRA_OPEN_PACKAGE, openPackage)
            }

        /** Intent que detiene la captura y baja el service. */
        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }
}
