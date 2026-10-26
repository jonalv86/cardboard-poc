package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import kotlin.math.abs

/**
 * Sigue qué app está al frente en la pantalla virtual del visor y reconoce las que solo funcionan en
 * una orientación. En la contraria el sistema las encaja con bandas: una vertical, en horizontal,
 * ocupa todo el alto pero es más alta que ancha; una horizontal, en vertical, ocupa todo el ancho,
 * centrada, pero es más ancha que alta. Lo reconocido se recuerda para el resto de la sesión, y se
 * corrige cuando la misma app muestra una pantalla fija en la otra orientación. Cada revisión informa
 * la app al frente con [onCheck].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class FrontAppDetector(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val onCheck: (FrontApp) -> Unit
) {

    /**
     * App al frente de la pantalla virtual. [packageName] es null sobre el panel o sin ventana de app.
     * [lockedPortrait] es la orientación en la que tiene que verse: true vertical, false horizontal,
     * null cualquiera.
     */
    data class FrontApp(val packageName: String?, val lockedPortrait: Boolean?)

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
        val letterboxed = letterboxedPortrait(JoystickManager.screenWidth, JoystickManager.screenHeight)
        if (letterboxed != null && SessionApps.lockedPortrait(packageName) != letterboxed) {
            Log.d(TAG, "$packageName solo funciona en ${if (letterboxed) "vertical" else "horizontal"}")
            SessionApps.lockOrientation(packageName, letterboxed)
        }
        return FrontApp(packageName, SessionApps.lockedPortrait(packageName))
    }

    /**
     * Si la ventana en [bounds] está encajada con bandas en una pantalla de [width] x [height]: true si
     * es vertical en una pantalla horizontal, false si es horizontal en una vertical, null si no.
     */
    private fun letterboxedPortrait(width: Int, height: Int): Boolean? = when {
        // Un diálogo también puede ser más alto que ancho; la ventana encajada ocupa todo el alto.
        width > height && bounds.height() >= height * FULL_SIZE_FRACTION &&
            bounds.height() > bounds.width() -> true

        // Una hoja que sube desde abajo también ocupa todo el ancho; la ventana encajada queda centrada.
        height > width && bounds.width() >= width * FULL_SIZE_FRACTION &&
            bounds.width() > bounds.height() &&
            abs(bounds.top - (height - bounds.bottom)) <= height * CENTERED_TOLERANCE -> false

        else -> null
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

        val NONE = FrontApp(null, null)

        /** Cada cuánto se mira la app al frente. */
        const val CHECK_INTERVAL_MS = 300L

        /** Fracción del lado de la pantalla a partir de la cual una ventana lo ocupa entero. */
        const val FULL_SIZE_FRACTION = 0.95f

        /** Diferencia tolerada entre las bandas de arriba y de abajo, como fracción del alto. */
        const val CENTERED_TOLERANCE = 0.02f
    }
}
