package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Arma cada frame con `takeScreenshotOfWindow` sobre las ventanas visibles, sin pasar por
 * MediaProjection: esas capturas toman cada ventana sola, así que los overlays de accesibilidad (la
 * ventana estéreo) no aparecen. El sistema rechaza más de una captura por ventana cada 333 ms, de ahí
 * el ritmo de [INTERVAL_MS]; los videos no se actualizan, porque la captura no incluye su superficie.
 *
 * Se puede arrancar y detener varias veces; [release] la deja inutilizable.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class WindowScreenshotSource(
    private val service: AccessibilityService,
    private val onFrame: (Bitmap) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * Recibe los resultados y compone el frame, fuera del main thread. Después de [release] descarta
     * los resultados que sigan llegando: rechazarlos tiraría la excepción en un hilo de binder.
     */
    private val executor = Executor { command ->
        try {
            worker.execute(command)
        } catch (_: RejectedExecutionException) {
        }
    }

    /**
     * Cambia en cada [start] y [stop]. Un pedido hecho en una generación anterior no publica su frame,
     * así una captura en vuelo no pisa los frames de la proyección cuando esta retoma.
     */
    @Volatile
    private var generation = 0

    /** Si la captura por ventanas está corriendo. */
    @Volatile
    var isRunning = false
        private set

    /** Ventanas ya registradas en el log, para describir cada una una sola vez. Main thread. */
    private val knownWindows = mutableSetOf<Int>()

    // Las escribe start() en el main thread y las lee compose() en el worker.
    @Volatile
    private var startedAtMs = 0L
    @Volatile
    private var firstFramePending = false
    @Volatile
    private var statsStartMs = 0L

    private var framesInWindow = 0
    private var errorsInWindow = 0
    private var latencySumMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            requestFrame()
            mainHandler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** Arranca la captura; el primer pedido sale en el acto. Main thread. */
    fun start() {
        if (isRunning) return
        generation++
        isRunning = true
        startedAtMs = SystemClock.uptimeMillis()
        statsStartMs = startedAtMs
        firstFramePending = true
        // Los contadores son del worker; si quedaran del arranque anterior, la primera medición de
        // fps saldría inflada.
        executor.execute {
            framesInWindow = 0
            errorsInWindow = 0
            latencySumMs = 0
        }
        mainHandler.post(tick)
    }

    /** Detiene la captura; los pedidos en vuelo se descartan. Main thread. */
    fun stop() {
        if (!isRunning) return
        generation++
        isRunning = false
        mainHandler.removeCallbacks(tick)
    }

    /** Detiene la captura y libera el hilo de composición. */
    fun release() {
        stop()
        worker.shutdown()
    }

    /** Pide una captura de cada ventana visible salvo los overlays de accesibilidad. Main thread. */
    private fun requestFrame() {
        val width = JoystickManager.screenWidth
        val height = JoystickManager.screenHeight
        if (width == 0 || height == 0) return

        // El servicio no se suscribe a ningún evento, así que la caché de ventanas nunca se invalida
        // sola y getWindows() seguiría devolviendo las ventanas del primer pedido.
        service.clearCache()
        // getWindows() viene ordenada de arriba hacia abajo; se dibuja al revés.
        val visible = service.windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .map { window -> Rect().also { window.getBoundsInScreen(it) } to window }
            .filter { (bounds, _) -> !bounds.isEmpty }
            .reversed()
        if (visible.isEmpty()) return
        visible.forEach { (bounds, window) ->
            if (knownWindows.add(window.id)) {
                Log.d(TAG, "ventanas: ${window.id} \"${window.title}\" tipo=${window.type} $bounds")
            }
        }
        val windows = visible.map { (bounds, window) -> bounds to window.id }

        val requestGeneration = generation
        val requestedAt = SystemClock.uptimeMillis()
        val shots = arrayOfNulls<Bitmap>(windows.size)
        var pending = windows.size

        windows.forEachIndexed { index, (_, windowId) ->
            service.takeScreenshotOfWindow(
                windowId,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        shots[index] = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        if (--pending == 0) {
                            compose(width, height, windows, shots, requestedAt, requestGeneration)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        errorsInWindow++
                        Log.d(TAG, "ventanas: la $windowId falló, código $errorCode")
                        if (--pending == 0) {
                            compose(width, height, windows, shots, requestedAt, requestGeneration)
                        }
                    }
                }
            )
        }
    }

    /** Dibuja cada captura en su lugar de la pantalla y publica el frame. Corre en [worker]. */
    private fun compose(
        width: Int,
        height: Int,
        windows: List<Pair<Rect, Int>>,
        shots: Array<Bitmap?>,
        requestedAt: Long,
        requestGeneration: Int
    ) {
        if (requestGeneration != generation) {
            shots.forEach { it?.recycle() }
            return
        }
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.BLACK)
        windows.forEachIndexed { index, (bounds, _) ->
            val shot = shots[index] ?: return@forEachIndexed
            canvas.drawBitmap(shot, null, bounds, null)
            shot.recycle()
        }
        frame.setHasAlpha(false)
        onFrame(frame)

        val now = SystemClock.uptimeMillis()
        if (firstFramePending) {
            firstFramePending = false
            Log.d(TAG, "ventanas: primer frame ${now - startedAtMs} ms después de arrancar")
        }
        framesInWindow++
        latencySumMs += now - requestedAt
        val elapsed = now - statsStartMs
        if (elapsed >= STATS_PERIOD_MS) {
            Log.d(
                TAG,
                "ventanas: %.1f fps, latencia media %d ms, ventanas=%d, errores=%d".format(
                    framesInWindow * 1000f / elapsed,
                    latencySumMs / framesInWindow,
                    windows.size,
                    errorsInWindow
                )
            )
            framesInWindow = 0
            errorsInWindow = 0
            latencySumMs = 0
            statsStartMs = now
        }
    }

    private companion object {
        const val TAG = "CardboardCapture"

        /** Apenas por encima del mínimo de 333 ms por ventana que impone el sistema. */
        const val INTERVAL_MS = 350L
        const val STATS_PERIOD_MS = 3000L
    }
}
