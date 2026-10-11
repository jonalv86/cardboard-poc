package com.pps.cardboardpoc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Singleton encargado de gestionar el estado del cursor virtual compartido entre la captura de pantalla
 * y el servicio de accesibilidad. Controla las coordenadas cartesianas, los límites físicos de la pantalla
 * y delega la ejecución de gestos (toques) en el dispositivo.
 */
object JoystickManager {
    private const val TAG = "JoystickManager"
    
    /** Posición horizontal del cursor, en píxeles de pantalla. La lee el hilo de render en cada cuadro. */
    @Volatile
    var cursorX = 0f
        private set

    /** Posición vertical del cursor, en píxeles de pantalla. La lee el hilo de render en cada cuadro. */
    @Volatile
    var cursorY = 0f
        private set

    /** Ancho de la pantalla capturada en píxeles; 0 hasta que arranca la primera captura. */
    @Volatile
    var screenWidth = 0
        private set

    /** Alto de la pantalla capturada en píxeles; 0 hasta que arranca la primera captura. */
    @Volatile
    var screenHeight = 0
        private set

    // Umbral de zona muerta para contrarrestar el drift natural de los sticks analógicos
    private const val DEAD_ZONE = 0.15f

    // Velocidad del cursor en píxeles por segundo: arranca lenta para poder apuntar fino y acelera
    // mientras se mantiene la dirección.
    private const val MIN_SPEED = 400f
    private const val MAX_SPEED = 1600f
    private const val ACCELERATION_MS = 1000f
    private const val TICK_MS = 16L

    private val handler = Handler(Looper.getMainLooper())

    // El stick digital del VR BOX solo manda un evento cuando cambia de valor, y el D-pad uno al
    // apretar y otro al soltar: lo que se guarda es la dirección vigente, y el tick la aplica.
    private var stickX = 0f
    private var stickY = 0f
    private var dpadLeft = false
    private var dpadRight = false
    private var dpadUp = false
    private var dpadDown = false

    private var moveStartMs = 0L
    private var lastTickMs = 0L
    private var moving = false

    private val tick = object : Runnable {
        override fun run() {
            val dx = directionX()
            val dy = directionY()
            if (dx == 0f && dy == 0f) {
                moving = false
                Log.d(TAG, "Cursor detenido en ($cursorX, $cursorY) tras ${lastTickMs - moveStartMs} ms")
                return
            }
            val now = SystemClock.uptimeMillis()
            val elapsedSeconds = (now - lastTickMs) / 1000f
            lastTickMs = now
            val ramp = ((now - moveStartMs) / ACCELERATION_MS).coerceAtMost(1f)
            val speed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * ramp
            cursorX = (cursorX + dx * speed * elapsedSeconds).coerceIn(0f, screenWidth.toFloat())
            cursorY = (cursorY + dy * speed * elapsedSeconds).coerceIn(0f, screenHeight.toFloat())
            handler.postDelayed(this, TICK_MS)
        }
    }

    // Referencia débil/estática al servicio activo para despachar inyecciones de eventos globales
    var accessibilityService: JoystickAccessibilityService? = null
    
    /**
     * Ajusta los límites del cursor a la resolución de la captura. La primera vez lo centra; en los
     * cambios siguientes (una rotación, por ejemplo) conserva su posición relativa en la pantalla.
     */
    fun setScreenSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (screenWidth == 0 || screenHeight == 0) {
            cursorX = width / 2f
            cursorY = height / 2f
        } else {
            cursorX = cursorX * width / screenWidth
            cursorY = cursorY * height / screenHeight
        }
        screenWidth = width
        screenHeight = height
        Log.d(TAG, "Pantalla ${width}x${height}, cursor en ($cursorX, $cursorY)")
    }
    
    /**
     * Registra la inclinación actual del stick (-1..1 por eje). El cursor se sigue moviendo mientras
     * no vuelva al centro, con una velocidad proporcional a la inclinación.
     */
    fun setStick(axisX: Float, axisY: Float) {
        stickX = if (Math.abs(axisX) > DEAD_ZONE) axisX else 0f
        stickY = if (Math.abs(axisY) > DEAD_ZONE) axisY else 0f
        updateMovement()
    }

    /** Registra que una flecha del D-pad se apretó o se soltó. El cursor se mueve mientras siga apretada. */
    fun setDpad(keyCode: Int, pressed: Boolean) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dpadLeft = pressed
            KeyEvent.KEYCODE_DPAD_RIGHT -> dpadRight = pressed
            KeyEvent.KEYCODE_DPAD_UP -> dpadUp = pressed
            KeyEvent.KEYCODE_DPAD_DOWN -> dpadDown = pressed
        }
        updateMovement()
    }

    private fun directionX(): Float =
        (stickX + (if (dpadRight) 1f else 0f) - (if (dpadLeft) 1f else 0f)).coerceIn(-1f, 1f)

    private fun directionY(): Float =
        (stickY + (if (dpadDown) 1f else 0f) - (if (dpadUp) 1f else 0f)).coerceIn(-1f, 1f)

    /**
     * Arranca el tick cuando aparece una dirección. Un cambio de dirección con el cursor ya en
     * movimiento conserva la velocidad alcanzada; el tick se detiene solo cuando no queda ninguna.
     */
    private fun updateMovement() {
        if (moving || (directionX() == 0f && directionY() == 0f)) return
        moving = true
        moveStartMs = SystemClock.uptimeMillis()
        lastTickMs = moveStartMs
        handler.post(tick)
    }
    
    /**
     * Solicita al servicio de accesibilidad que inyecte un evento táctil síncrono (tap)
     * en la ubicación exacta donde se encuentra el cursor virtual en este momento.
     */
    fun performClick() {
        Log.d(TAG, "Simulando Click en: ($cursorX, $cursorY)")
        accessibilityService?.clickAt(cursorX, cursorY)
    }
    
    /**
     * Solicita la emulación de la pulsación física del botón de retroceso del sistema operativo.
     */
    fun performBack() {
        Log.d(TAG, "Simulando botón Atrás")
        accessibilityService?.performBackAction()
    }
}
