package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.PointF
import android.os.Build
import androidx.core.content.ContextCompat
import android.view.KeyEvent
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Servicio de Accesibilidad responsable de interceptar eventos de hardware (Joystick Bluetooth)
 * y transformarlos en gestos táctiles simulados o acciones globales del sistema.
 * 
 * Requiere que el usuario lo active manualmente en Ajustes > Accesibilidad.
 */
class JoystickAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "JoystickAccService"
        private const val DRAG_TAG = "DragDebug"

        // En modo mouse el control aparece como un dispositivo aparte, "VR BOX Mouse".
        private const val VR_BOX_DEVICE_PREFIX = "VR BOX"

        /**
         * Atajo de prueba por adb, solo en compilaciones depurables: arranca el avance hacia el borde
         * de [EXTRA_DEBUG_EDGE_X] y [EXTRA_DEBUG_EDGE_Y] (-1, 0 o 1), o lo detiene si los dos son 0.
         */
        const val ACTION_DEBUG_EDGE_SCROLL = "com.pps.cardboardpoc.debug.AVANCE"
        const val EXTRA_DEBUG_EDGE_X = "x"
        const val EXTRA_DEBUG_EDGE_Y = "y"

        /**
         * Atajo de prueba por adb, solo en compilaciones depurables: ajusta el zoom del visor. Cada extra
         * es opcional y es un float: [EXTRA_DEBUG_ZOOM_LEVEL] (1 a [JoystickManager.MAX_ZOOM]),
         * [EXTRA_DEBUG_ZOOM_DEAD_ZONE] (0 a 1), [EXTRA_DEBUG_ZOOM_MAX_HALF_HEIGHT] (0 vuelve al del
         * render), [EXTRA_DEBUG_HEAD_SMOOTHING_MS] y [EXTRA_DEBUG_HEAD_FULL_WIDTH_DEGREES].
         */
        const val ACTION_DEBUG_ZOOM = "com.pps.cardboardpoc.debug.ZOOM"
        const val EXTRA_DEBUG_ZOOM_LEVEL = "nivel"
        const val EXTRA_DEBUG_ZOOM_DEAD_ZONE = "zona"
        const val EXTRA_DEBUG_ZOOM_MAX_HALF_HEIGHT = "alto"
        const val EXTRA_DEBUG_HEAD_SMOOTHING_MS = "suavizado"
        const val EXTRA_DEBUG_HEAD_FULL_WIDTH_DEGREES = "grados"

        /**
         * Atajo de prueba por adb, solo en compilaciones depurables: lleva el cursor a ([EXTRA_DEBUG_CURSOR_X],
         * [EXTRA_DEBUG_CURSOR_Y]), floats en píxeles de pantalla, y con [EXTRA_DEBUG_CURSOR_CLICK] en
         * true toca donde quedó, como el gatillo apretado y soltado.
         */
        const val ACTION_DEBUG_CURSOR = "com.pps.cardboardpoc.debug.CURSOR"
        const val EXTRA_DEBUG_CURSOR_X = "x"
        const val EXTRA_DEBUG_CURSOR_Y = "y"
        const val EXTRA_DEBUG_CURSOR_CLICK = "tocar"
    }

    private val pressFinger = VirtualFinger(this, "gatillo")
    private val edgeScroller = EdgeScroller(this)

    /** Si el botón que apoya el dedo sigue apretado. */
    private var buttonHeld = false

    private val debugEdgeScrollReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val dirX = intent.getIntExtra(EXTRA_DEBUG_EDGE_X, 0)
            val dirY = intent.getIntExtra(EXTRA_DEBUG_EDGE_Y, 0)
            if (dirX == 0 && dirY == 0) stopEdgeScroll() else startEdgeScroll(dirX, dirY)
        }
    }

    private val debugZoomReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            fun Intent.floatExtra(name: String): Float? =
                if (hasExtra(name)) getFloatExtra(name, 0f) else null

            intent.floatExtra(EXTRA_DEBUG_HEAD_SMOOTHING_MS)?.let { HeadPointer.smoothingMs = it }
            intent.floatExtra(EXTRA_DEBUG_HEAD_FULL_WIDTH_DEGREES)?.let {
                HeadPointer.fullWidthDegrees = it
            }
            intent.floatExtra(EXTRA_DEBUG_ZOOM_DEAD_ZONE)?.let { JoystickManager.setZoomDeadZone(it) }
            intent.floatExtra(EXTRA_DEBUG_ZOOM_MAX_HALF_HEIGHT)?.let {
                JoystickManager.setZoomMaxHalfHeight(it)
            }
            intent.floatExtra(EXTRA_DEBUG_ZOOM_LEVEL)?.let { JoystickManager.setZoom(it) }
            Log.d(
                TAG,
                "Zoom por adb: cabeza suavizado=${HeadPointer.smoothingMs} ms " +
                    "grados=${HeadPointer.fullWidthDegrees}"
            )
        }
    }

    private val debugCursorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.hasExtra(EXTRA_DEBUG_CURSOR_X) || intent.hasExtra(EXTRA_DEBUG_CURSOR_Y)) {
                JoystickManager.moveCursorTo(
                    intent.getFloatExtra(EXTRA_DEBUG_CURSOR_X, JoystickManager.cursorX),
                    intent.getFloatExtra(EXTRA_DEBUG_CURSOR_Y, JoystickManager.cursorY)
                )
            }
            Log.d(
                TAG,
                "Cursor por adb: (${JoystickManager.cursorX}, ${JoystickManager.cursorY}) " +
                    "${JoystickManager.snapshot.window} barra=${JoystickManager.controlBarVisible} " +
                    "borde=${JoystickManager.edgeContact?.let { "(${it.dirX}, ${it.dirY})" }}"
            )
            if (intent.getBooleanExtra(EXTRA_DEBUG_CURSOR_CLICK, false)) JoystickManager.voiceClick()
        }
    }

    /**
     * Vincula el servicio activo al JoystickManager al momento de la conexión para permitir
     * el despacho de comandos de entrada.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Servicio de Accesibilidad Conectado")
        JoystickManager.accessibilityService = this

        setCaptureActive(ScreenCaptureService.isCapturing)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            ContextCompat.registerReceiver(
                this, debugEdgeScrollReceiver, IntentFilter(ACTION_DEBUG_EDGE_SCROLL),
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                this, debugZoomReceiver, IntentFilter(ACTION_DEBUG_ZOOM),
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                this, debugCursorReceiver, IntentFilter(ACTION_DEBUG_CURSOR),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    /**
     * Elige qué eventos de movimiento toma el servicio (onMotionEvent existe desde Android 14). El
     * stick se toma siempre. El mouse solo durante la captura, para detectar que el VR BOX quedó en
     * modo mouse: un servicio no puede observar el mouse sin consumirlo, así que fuera de la captura
     * se lo deja pasar para no inutilizar un mouse real.
     */
    fun setCaptureActive(active: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val info = serviceInfo ?: return
        info.motionEventSources = if (active) {
            InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_MOUSE
        } else {
            InputDevice.SOURCE_JOYSTICK
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No es necesario inspeccionar el contenido de las ventanas (UI Automator) para esta lógica de control remoto
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Servicio de Accesibilidad Interrumpido")
    }

    /**
     * Limpia la referencia al desactivarse el servicio y detiene la captura, cuya ventana estéreo
     * pertenece a este servicio y desaparece con él.
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Servicio de Accesibilidad Destruido")
        JoystickManager.accessibilityService = null
        edgeScroller.release()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            unregisterReceiver(debugEdgeScrollReceiver)
            unregisterReceiver(debugZoomReceiver)
            unregisterReceiver(debugCursorReceiver)
        }
        if (ScreenCaptureService.isCapturing) {
            startService(ScreenCaptureService.stopIntent(this))
        }
    }

    /**
     * Punto de entrada crítico para la captura de eventos de botones físicos y joysticks.
     * Mapea códigos de tecla estándar de Android (KeyEvent) a las funciones lógicas del cursor.
     * En modo joystick el VR BOX manda el gatillo como BUTTON_L1, el botón A como BUTTON_B y el botón B
     * como BUTTON_X. El gatillo funciona como un dedo: apretarlo apoya el dedo y soltarlo lo levanta.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.d(
            DRAG_TAG,
            "tecla ${KeyEvent.keyCodeToString(event.keyCode)} " +
                "${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} " +
                "repeat=${event.repeatCount} device=${event.device?.name} " +
                "source=0x${event.source.toString(16)}"
        )
        val isPress = event.action == KeyEvent.ACTION_DOWN
        val isFirstPress = isPress && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (event.repeatCount == 0) JoystickManager.setDpad(event.keyCode, isPress)

            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (isFirstPress) JoystickManager.pressDown()
                else if (!isPress) JoystickManager.releasePress()

            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
                if (isFirstPress) JoystickManager.performBack()

            KeyEvent.KEYCODE_BUTTON_X ->
                if (isFirstPress) JoystickManager.recenter()

            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R1 ->
                if (isFirstPress) Log.d(TAG, "${KeyEvent.keyCodeToString(event.keyCode)} sin asignar")

            else -> return super.onKeyEvent(event)
        }
        // Toda tecla del control se consume, ACTION_UP incluido: si le llegara cruda a la app de
        // abajo, esta la interpretaría sobre su vista con foco (BUTTON_B es Atrás para muchas apps).
        return true
    }

    /**
     * Captura los eventos del stick. Un stick digital como el del VR BOX solo manda un evento cuando
     * cambia de posición, así que se registra la inclinación y JoystickManager mueve el cursor
     * mientras dure.
     */
    override fun onMotionEvent(event: MotionEvent) {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            JoystickManager.setStick(
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y)
            )
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.device?.name?.startsWith(VR_BOX_DEVICE_PREFIX) == true
        ) {
            JoystickManager.reportMouseModeEvent()
        }
        super.onMotionEvent(event)
    }
    
    /**
     * Apoya un dedo simulado en las coordenadas dadas y lo deja apoyado hasta [releasePress]. Mientras
     * tanto el dedo sigue al cursor, así que una pulsación corta es un tap, una larga sin mover es un
     * long-press y una con el cursor en movimiento es un arrastre.
     */
    fun pressAt(x: Float, y: Float) {
        if (pressFinger.isDown) return
        buttonHeld = true
        pressFinger.down(
            x, y, settleMs = { 0L },
            target = { PointF(JoystickManager.cursorX, JoystickManager.cursorY) },
            keepDown = { buttonHeld },
            onUp = { cancelled -> if (cancelled && buttonHeld) JoystickManager.onPressCancelled() }
        )
    }

    /** Levanta el dedo apoyado con [pressAt], en la posición actual del cursor. */
    fun releasePress() {
        if (!buttonHeld) return
        buttonHeld = false
        pressFinger.poke()
    }

    /** Hace avanzar la pantalla hacia el borde indicado (-1, 0 o 1 por eje) hasta [stopEdgeScroll]. */
    fun startEdgeScroll(dirX: Int, dirY: Int) {
        edgeScroller.start(dirX, dirY)
    }

    /** Actualiza si se está empujando contra el borde para acelerar o desacelerar el avance. */
    fun setEdgeScrollPushing(pushing: Boolean) {
        edgeScroller.setPushing(pushing)
    }

    /** Detiene el avance arrancado con [startEdgeScroll]. */
    fun stopEdgeScroll() {
        edgeScroller.stop()
    }

    /**
     * Inyecta la acción global del sistema equivalente a presionar el botón de "Atrás".
     * Funciona independientemente de la jerarquía de vistas de la aplicación actual.
     */
    fun performBackAction() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Desplaza la vista activa al principio del contenido (tope). */
    fun scrollToTop() {
        val root = rootInActiveWindow ?: return
        val scrollableNode = findScrollableNode(root)
        if (scrollableNode != null) {
            var scrolled = true
            var count = 0
            while (scrolled && count < 25) {
                scrolled = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                count++
            }
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findScrollableNode(child)
            if (found != null) {
                return found
            }
        }
        return null
    }
}
