package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi

/**
 * Sigue qué app está al frente en la pantalla virtual del visor y reconoce las que solo funcionan en
 * vertical. En horizontal el sistema las encaja con bandas a los costados: su ventana ocupa todo el
 * alto pero es más alta que ancha. Las reconocidas se recuerdan para el resto de la sesión. Cada
 * revisión informa con [onCheck] si la app al frente es una de ellas.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class PortraitAppDetector(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val onCheck: (portraitOnly: Boolean) -> Unit
) {

    private val bounds = Rect()
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            onCheck(isFrontAppPortraitOnly())
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

    private fun isFrontAppPortraitOnly(): Boolean {
        if (PanelActivity.isInFront) return false
        val window = frontAppWindow() ?: return false
        val packageName = window.root?.packageName?.toString() ?: return false

        val width = JoystickManager.screenWidth
        val height = JoystickManager.screenHeight
        window.getBoundsInScreen(bounds)
        // Un diálogo también puede ser más alto que ancho; la ventana encajada ocupa todo el alto.
        val letterboxedPortrait = width > height &&
            bounds.height() >= height * FULL_HEIGHT_FRACTION && bounds.height() > bounds.width()
        if (letterboxedPortrait && !SessionApps.isPortraitOnly(packageName)) {
            Log.d(TAG, "$packageName solo funciona en vertical")
            SessionApps.markPortraitOnly(packageName)
        }
        return SessionApps.isPortraitOnly(packageName)
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
        const val TAG = "CardboardCapture"

        /** Cada cuánto se mira la app al frente. */
        const val CHECK_INTERVAL_MS = 300L

        /** Fracción del alto de la pantalla a partir de la cual una ventana cuenta como de alto completo. */
        const val FULL_HEIGHT_FRACTION = 0.95f
    }
}
