package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.roundToInt

/**
 * Dedo simulado que se apoya, sigue un objetivo mientras deba seguir apoyado y después se levanta. Se
 * inyecta como una cadena de tramos con willContinue: cada tramo se despacha recién en el callback del
 * anterior y arranca exactamente donde terminó ese. El toque atraviesa la ventana estéreo, que no
 * recibe toques, y llega a la app capturada.
 *
 * Despachar un gesto cancela el que esté en curso, así que dos dedos no pueden estar apoyados a la vez:
 * el que se apoya después cancela al otro.
 *
 * Los callbacks de los tramos llegan por [handler], y sus métodos se llaman desde ese mismo hilo. Si el
 * hilo está ocupado, el tramo siguiente sale tarde y la app ve el dedo detenido mientras tanto.
 */
class VirtualFinger(
    private val service: AccessibilityService,
    private val label: String,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    /** Último tramo despachado; null si el dedo no está apoyado. */
    private var stroke: StrokeDescription? = null

    /** Punto donde termina el último tramo despachado, que es donde tiene que arrancar el siguiente. */
    private var lastX = 0f
    private var lastY = 0f

    /** Si hay un tramo despachado cuyo callback todavía no llegó. */
    private var inFlight = false

    /** Desde cuándo (uptime en ms) se acumula el recorrido que va a cubrir el próximo tramo. */
    private var followSinceMs = 0L

    private var segmentCount = 0
    private var settleMs: () -> Long = { 0L }
    private var target: () -> PointF = { PointF(lastX, lastY) }
    private var keepDown: () -> Boolean = { false }
    private var onUp: (cancelled: Boolean) -> Unit = {}

    private val followTarget = Runnable { dispatchNext() }

    /** Si el dedo está apoyado, incluido el tramo final que lo levanta mientras no termine. */
    val isDown: Boolean
        get() = stroke != null

    /**
     * Apoya el dedo en (x, y). Mientras [keepDown] devuelva true el dedo sigue a [target]; cuando
     * devuelve false se levanta. Si al levantarse [settleMs] da más de 0, el dedo llega al objetivo y se
     * queda quieto ese tiempo antes de levantarse, para que la app no tome el final como un lanzamiento
     * (fling); con 0 se levanta en movimiento y la app sigue desplazándose sola. Con [firstTo] el dedo
     * se apoya ya moviéndose y llega ahí en [SEGMENT_MIN_MS]. [onUp] avisa cuando el dedo se levantó o
     * el sistema canceló el gesto. Devuelve false si ya estaba apoyado.
     */
    fun down(
        x: Float,
        y: Float,
        settleMs: () -> Long,
        target: () -> PointF,
        keepDown: () -> Boolean,
        onUp: (cancelled: Boolean) -> Unit,
        firstTo: PointF? = null
    ): Boolean {
        if (isDown) return false
        this.settleMs = settleMs
        this.target = target
        this.keepDown = keepDown
        this.onUp = onUp
        segmentCount = 0
        lastX = x
        lastY = y
        Log.d(TAG, "$label: dedo apoyado en ($x, $y)")
        val path = if (firstTo != null && changesPixel(firstTo)) lineTo(firstTo) else pointPath(x, y)
        dispatch(StrokeDescription(path, 0, SEGMENT_MIN_MS, true))
        return true
    }

    /**
     * Vuelve a mirar [keepDown] y el objetivo en el acto si el dedo está esperando quieto, en lugar de
     * esperar al próximo tick.
     */
    fun poke() {
        if (!isDown || inFlight) return
        handler.removeCallbacks(followTarget)
        dispatchNext()
    }

    /**
     * Encadena el tramo siguiente, desde donde terminó el anterior hasta el objetivo. Si el dedo tiene
     * que seguir apoyado y el objetivo no se movió, no despacha nada: el dedo queda apoyado y se vuelve
     * a mirar en el próximo tick.
     */
    private fun dispatchNext() {
        val previous = stroke ?: return
        // El objetivo puede ser el que decide que el recorrido terminó: si keepDown se mirara antes,
        // el tramo que llega al final no levantaría y el dedo se quedaría quieto un tramo entero.
        val point = target()
        val holding = keepDown()
        val moved = changesPixel(point)
        if (holding && !moved) {
            followSinceMs = SystemClock.uptimeMillis()
            handler.postDelayed(followTarget, FOLLOW_POLL_MS)
            return
        }

        val path = if (moved) lineTo(point) else pointPath(lastX, lastY)
        val settle = if (holding) 0L else settleMs()
        // Con settle, el tramo que todavía se mueve no levanta: lo hace el siguiente, ya quieto.
        val lifting = !holding && (settle == 0L || !moved)
        // El tramo dura lo que tardó el objetivo en recorrerlo, para que la app vea la misma velocidad.
        val duration = if (lifting && settle > 0L) {
            settle
        } else {
            (SystemClock.uptimeMillis() - followSinceMs).coerceIn(SEGMENT_MIN_MS, SEGMENT_MAX_MS)
        }
        dispatch(previous.continueStroke(path, 0, duration, !lifting))
    }

    private fun dispatch(next: StrokeDescription) {
        stroke = next
        inFlight = true
        followSinceMs = SystemClock.uptimeMillis()
        segmentCount++
        val builder = GestureDescription.Builder().addStroke(next)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setDisplayId(JoystickManager.targetDisplayId)
        }
        val gesture = builder.build()
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                inFlight = false
                if (next.willContinue()) {
                    dispatchNext()
                } else {
                    Log.d(TAG, "$label: dedo levantado en ($lastX, $lastY) tras $segmentCount tramos")
                    finish(cancelled = false)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "$label: tramo $segmentCount cancelado en ($lastX, $lastY)")
                finish(cancelled = true)
            }
        }, handler)
        if (!accepted) {
            Log.w(TAG, "$label: dispatchGesture rechazó el tramo $segmentCount")
            finish(cancelled = true)
        }
    }

    private fun finish(cancelled: Boolean) {
        handler.removeCallbacks(followTarget)
        stroke = null
        inFlight = false
        onUp(cancelled)
    }

    /**
     * Si llevar el dedo a [point] lo cambia de píxel. El sistema redondea cada punto del gesto a
     * píxeles enteros: un tramo que no cambia de píxel no genera ningún evento y lo informa como
     * cancelado. Pasa con el temblor de la cabeza, que mueve el cursor fracciones de píxel.
     */
    private fun changesPixel(point: PointF): Boolean =
        point.x.roundToInt() != lastX.roundToInt() || point.y.roundToInt() != lastY.roundToInt()

    /** Recorrido desde donde está el dedo hasta [point]; deja ahí el punto de partida del próximo tramo. */
    private fun lineTo(point: PointF): Path {
        val path = pointPath(lastX, lastY)
        path.lineTo(point.x, point.y)
        // El sistema exige que cada tramo arranque exactamente donde terminó el anterior, y ese final
        // lo calcula con PathMeasure: puede diferir del objetivo por redondeo.
        val end = FloatArray(2)
        val measure = PathMeasure(path, false)
        measure.getPosTan(measure.length, end, null)
        lastX = end[0]
        lastY = end[1]
        return path
    }

    private fun pointPath(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    companion object {
        private const val TAG = "DragDebug"

        /** Duración mínima de cada tramo, en ms; es lo que dura el que apoya el dedo. */
        const val SEGMENT_MIN_MS = 16L

        /** Duración máxima de cada tramo, en ms. */
        private const val SEGMENT_MAX_MS = 100L

        /** Cada cuánto se mira el objetivo mientras el dedo está apoyado y quieto, en ms. */
        private const val FOLLOW_POLL_MS = 16L
    }
}
