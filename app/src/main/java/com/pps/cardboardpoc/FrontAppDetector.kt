package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi

/**
 * Sigue qué app está al frente en la pantalla virtual del visor y cómo encaja su ventana en ella. Cada
 * revisión informa la app al frente con [onCheck].
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
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            onCheck(frontApp())
            if (running) handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun frontApp(): FrontApp {
        if (PanelActivity.isInFront) return NONE
        val window = frontAppWindow() ?: return NONE
        val packageName = window.root?.packageName?.toString() ?: return NONE

        window.getBoundsInScreen(bounds)
        val fit = WindowFit.of(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            JoystickManager.screenWidth, JoystickManager.screenHeight
        )
        return FrontApp(packageName, fit)
    }

    /** Ventana de la app de más arriba en la pantalla virtual, sin contar las de CeluVR. */
    private fun frontAppWindow(): AccessibilityWindowInfo? {
        // Sin suscripción a eventos, la lista de ventanas queda congelada si no se limpia la caché.
        service.clearCache()
        val windows = service.windowsOnAllDisplays[JoystickManager.targetDisplayId] ?: return null
        return windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                it.root?.packageName != service.packageName
        }
    }

    private companion object {
        val NONE = FrontApp(null, WindowFit.PARTIAL)

        /** Cada cuánto se mira la app al frente. */
        const val CHECK_INTERVAL_MS = 300L
    }
}
