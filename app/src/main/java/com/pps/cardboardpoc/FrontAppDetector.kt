package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi

/**
 * Sigue qué app está al frente en la pantalla virtual del visor y cómo encaja su ventana en ella. Cada
 * revisión informa la app al frente con [onCheck], en [handler].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class FrontAppDetector(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val onCheck: (FrontApp) -> Unit
) {

    /**
     * App al frente de la pantalla virtual. [packageName] es null sobre el panel o sin ventana de app.
     * [fit] es cómo encaja su ventana en la pantalla.
     */
    data class FrontApp(val packageName: String?, val fit: WindowFit)

    private val bounds = Rect()

    @Volatile
    private var running = false
    private var worker: HandlerThread? = null

    // La lista de ventanas se pide en un hilo propio: el sistema demora la respuesta hasta que termina
    // la transición en curso, y esa transición espera a que las ventanas de CeluVR dibujen. Pedida
    // desde el hilo principal, cada uno espera al otro hasta el timeout de 5 s del sistema.
    private inner class Tick(private val workerHandler: Handler) : Runnable {
        override fun run() {
            val front = frontApp()
            handler.post { if (running) onCheck(front) }
            if (running) workerHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        val thread = HandlerThread("FrontAppDetector").apply { start() }
        worker = thread
        val workerHandler = Handler(thread.looper)
        workerHandler.post(Tick(workerHandler))
    }

    fun stop() {
        running = false
        worker?.quitSafely()
        worker = null
    }

    private fun frontApp(): FrontApp {
        val window = frontAppWindow() ?: return NONE
        val packageName = window.root?.packageName?.toString() ?: return NONE
        if (packageName == service.packageName) return NONE

        window.getBoundsInScreen(bounds)
        val fit = WindowFit.of(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            JoystickManager.screenWidth, JoystickManager.screenHeight
        )
        return FrontApp(packageName, fit)
    }

    /**
     * Ventana de app de más arriba en la pantalla virtual, que puede ser la del panel. Se decide por el
     * orden de las ventanas y no por si el panel está en primer plano: las apps se abren en
     * multiventana, y debajo de ellas el panel sigue visible y en primer plano aunque quede tapado.
     */
    private fun frontAppWindow(): AccessibilityWindowInfo? {
        // Sin suscripción a eventos, la lista de ventanas queda congelada si no se limpia la caché.
        service.clearCache()
        val windows = service.windowsOnAllDisplays[JoystickManager.targetDisplayId] ?: return null
        return windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    }

    private companion object {
        val NONE = FrontApp(null, WindowFit.PARTIAL)

        /** Cada cuánto se mira la app al frente. */
        const val CHECK_INTERVAL_MS = 300L
    }
}
