package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Hace avanzar la pantalla hacia un borde con swipes lentos encadenados, mientras esté activo. El avance
 * arranca despacio y acelera mientras dure. Ir hacia abajo muestra más contenido de abajo, así que el
 * dedo sube; lo mismo en los otros bordes. Cada swipe recorre la franja central de la pantalla, lejos de
 * los bordes donde el sistema tiene sus propios gestos (Atrás, Inicio, la cortina).
 */
class EdgeScroller(service: AccessibilityService) {

    private val finger = VirtualFinger(service, "avance")
    private val handler = Handler(Looper.getMainLooper())
    private val nextSwipe = Runnable { startSwipe() }

    private var active = false
    private var dirX = 0
    private var dirY = 0

    /** Momento (uptime en ms) en que arrancó el avance; de él depende la aceleración. */
    private var scrollStartMs = 0L

    private var fromX = 0f
    private var fromY = 0f
    private var toX = 0f
    private var toY = 0f

    /** Fracción recorrida del swipe en curso; queda fija al detener el avance. */
    private var progress = 0f
    private var lastProgressMs = 0L

    /** Arranca el avance hacia el borde indicado (-1, 0 o 1 por eje). */
    fun start(dirX: Int, dirY: Int) {
        this.dirX = dirX
        this.dirY = dirY
        active = true
        scrollStartMs = SystemClock.uptimeMillis()
        Log.d(TAG, "avance hacia ($dirX, $dirY)")
        // Si el dedo todavía se está levantando del swipe anterior, su onUp agenda el siguiente.
        if (!finger.isDown) startSwipe()
    }

    /** Detiene el avance: el swipe en curso se frena donde está y el dedo se levanta. */
    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(nextSwipe)
        Log.d(TAG, "avance detenido")
        finger.poke()
    }

    private fun startSwipe() {
        if (!active || finger.isDown) return
        val width = JoystickManager.screenWidth.toFloat()
        val height = JoystickManager.screenHeight.toFloat()
        if (width == 0f || height == 0f) return

        if (dirY != 0) {
            fromX = JoystickManager.cursorX.coerceIn(width * CROSS_MARGIN, width * (1 - CROSS_MARGIN))
            toX = fromX
            fromY = height * if (dirY > 0) BAND_END else BAND_START
            toY = height * if (dirY > 0) BAND_START else BAND_END
        } else {
            fromY = JoystickManager.cursorY.coerceIn(height * CROSS_MARGIN, height * (1 - CROSS_MARGIN))
            toY = fromY
            fromX = width * if (dirX > 0) BAND_END else BAND_START
            toX = width * if (dirX > 0) BAND_START else BAND_END
        }
        progress = 0f
        lastProgressMs = SystemClock.uptimeMillis()
        finger.down(
            fromX, fromY, SETTLE_MS,
            target = ::currentTarget,
            keepDown = { active && progress < 1f },
            // Volver a apoyar el dedo en el mismo instante en que el sistema da por levantado el anterior
            // hace que a veces cancele el gesto nuevo; una pausa corta lo evita, como con un dedo real.
            // Si igual se cancela, se reintenta mientras el avance siga activo.
            onUp = { if (active) handler.postDelayed(nextSwipe, REGRAB_DELAY_MS) }
        )
    }

    /**
     * Avanza el swipe en curso según el tiempo transcurrido, a la velocidad que corresponde a lo que
     * lleva el avance: arranca en [MIN_SCREENS_PER_SECOND] y llega a [MAX_SCREENS_PER_SECOND] tras
     * [ACCELERATION_MS].
     */
    private fun currentTarget(): PointF {
        if (active) {
            val now = SystemClock.uptimeMillis()
            val ramp = ((now - scrollStartMs) / ACCELERATION_MS.toFloat()).coerceAtMost(1f)
            val screensPerSecond =
                MIN_SCREENS_PER_SECOND + (MAX_SCREENS_PER_SECOND - MIN_SCREENS_PER_SECOND) * ramp
            val swipeFraction = BAND_END - BAND_START
            progress = (progress + screensPerSecond * (now - lastProgressMs) / 1000f / swipeFraction)
                .coerceAtMost(1f)
            lastProgressMs = now
        }
        return PointF(fromX + (toX - fromX) * progress, fromY + (toY - fromY) * progress)
    }

    private companion object {
        const val TAG = "DragDebug"

        /** Franja del eje de avance que recorre cada swipe, como fracción de la pantalla. */
        const val BAND_START = 0.25f
        const val BAND_END = 0.75f

        /** Distancia mínima del swipe a los bordes en el otro eje, como fracción de la pantalla. */
        const val CROSS_MARGIN = 0.1f

        /** Velocidad del avance, en pantallas por segundo, al arrancar y al terminar de acelerar. */
        const val MIN_SCREENS_PER_SECOND = 0.33f
        const val MAX_SCREENS_PER_SECOND = 1.5f

        /** Cuánto tarda el avance en pasar de la velocidad mínima a la máxima, en ms. */
        const val ACCELERATION_MS = 3000L

        /**
         * Tiempo quieto antes de levantar el dedo. La app calcula la velocidad del lanzamiento con los
         * últimos 100 ms de movimiento: tras una pausa más larga la ve en cero y no sigue desplazándose sola.
         */
        const val SETTLE_MS = 120L

        /** Pausa entre levantar el dedo y volver a apoyarlo para el swipe siguiente, en ms. */
        const val REGRAB_DELAY_MS = 40L
    }
}
