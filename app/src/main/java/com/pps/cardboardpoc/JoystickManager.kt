package com.pps.cardboardpoc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /** Posición horizontal que aporta el joystick, en píxeles de pantalla. */
    private var baseX = 0f

    /** Posición vertical que aporta el joystick, en píxeles de pantalla. */
    private var baseY = 0f

    /** Desplazamiento horizontal que aporta la cabeza, en píxeles; 0 sin giroscopio. */
    private var headOffsetX = 0f

    /** Desplazamiento vertical que aporta la cabeza, en píxeles; 0 sin giroscopio. */
    private var headOffsetY = 0f

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
            moveBase(dx * speed * elapsedSeconds, dy * speed * elapsedSeconds)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val serviceConnectedState = MutableStateFlow(false)

    /** Si el servicio de accesibilidad está conectado. Sin él no hay ventana estéreo ni control. */
    val serviceConnected: StateFlow<Boolean> = serviceConnectedState

    // Referencia débil/estática al servicio activo para despachar inyecciones de eventos globales
    var accessibilityService: JoystickAccessibilityService? = null
        set(value) {
            field = value
            serviceConnectedState.value = value != null
        }

    /**
     * Momento (uptime en ms) del último evento de mouse que mandó el VR BOX, o 0 si nunca mandó. En
     * modo mouse el sistema dibuja su propio puntero, que no está en estéreo; el renderer lo usa para
     * avisar que hay que pasar el control a modo joystick.
     */
    @Volatile
    var lastMouseModeEventMs = 0L
        private set

    /** Registra que llegó un evento del control en modo mouse. */
    fun reportMouseModeEvent() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMouseModeEventMs > MOUSE_MODE_NOTICE_MS) {
            Log.w(TAG, "El VR BOX está en modo mouse: el puntero del sistema no se ve en estéreo")
        }
        lastMouseModeEventMs = now
    }

    /** Cuánto tiempo sigue visible el aviso de modo mouse después del último evento. */
    const val MOUSE_MODE_NOTICE_MS = 4000L

    /**
     * Ajusta los límites del cursor a la resolución de la captura. La primera vez lo centra; en los
     * cambios siguientes (una rotación, por ejemplo) conserva su posición relativa en la pantalla.
     */
    @Synchronized
    fun setScreenSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (screenWidth == 0 || screenHeight == 0) {
            baseX = width / 2f
            baseY = height / 2f
        } else {
            baseX = baseX * width / screenWidth
            baseY = baseY * height / screenHeight
        }
        screenWidth = width
        screenHeight = height
        updateCursor()
        Log.d(TAG, "Pantalla ${width}x${height}, cursor en ($cursorX, $cursorY)")
    }

    /**
     * Registra el desplazamiento que aporta la cabeza, en píxeles desde la posición del joystick. Se
     * llama desde el hilo de render, una vez por cuadro.
     */
    @Synchronized
    fun setHeadOffset(dx: Float, dy: Float) {
        headOffsetX = dx
        headOffsetY = dy
        updateCursor()
    }

    /**
     * Cuántas veces se pidió centrar el cursor. La fuente de la cabeza lo compara con el último valor
     * que atendió para tomar como frente la orientación de ese momento.
     */
    @Volatile
    var recenterCount = 0
        private set

    /** Lleva el cursor al centro de la pantalla y descarta el desplazamiento del joystick y de la cabeza. */
    @Synchronized
    fun recenter() {
        if (screenWidth == 0 || screenHeight == 0) return
        baseX = screenWidth / 2f
        baseY = screenHeight / 2f
        headOffsetX = 0f
        headOffsetY = 0f
        updateCursor()
        recenterCount++
        Log.d(TAG, "Cursor centrado en ($cursorX, $cursorY)")
    }

    /**
     * Mueve la posición del joystick. Contra un borde avanza solo hasta donde el cursor lo toca, así
     * que al volver no hay desplazamiento de más que deshacer.
     */
    @Synchronized
    private fun moveBase(stepX: Float, stepY: Float) {
        baseX = limitStep(baseX, stepX, headOffsetX, screenWidth)
        baseY = limitStep(baseY, stepY, headOffsetY, screenHeight)
        updateCursor()
    }

    /**
     * Aplica un paso del joystick sobre un eje. Si la cabeza ya lleva el cursor más allá del borde,
     * empujar hacia ese borde no mueve la base, pero tampoco la hace retroceder.
     */
    private fun limitStep(base: Float, step: Float, headOffset: Float, size: Int): Float {
        val target = base + step
        return if (step > 0f) {
            minOf(target, maxOf(base, size - headOffset))
        } else {
            maxOf(target, minOf(base, -headOffset))
        }
    }

    private fun updateCursor() {
        if (screenWidth == 0 || screenHeight == 0) return
        cursorX = (baseX + headOffsetX).coerceIn(0f, screenWidth.toFloat())
        cursorY = (baseY + headOffsetY).coerceIn(0f, screenHeight.toFloat())
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
