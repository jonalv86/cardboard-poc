package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.KeyEvent
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de Accesibilidad responsable de interceptar eventos de hardware (Joystick Bluetooth)
 * y transformarlos en gestos táctiles simulados o acciones globales del sistema.
 * 
 * Requiere que el usuario lo active manualmente en Ajustes > Accesibilidad.
 */
class JoystickAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "JoystickAccService"
        private const val CAPTURE_TAG = "CardboardCapture"

        // En modo mouse el control aparece como un dispositivo aparte, "VR BOX Mouse".
        private const val VR_BOX_DEVICE_PREFIX = "VR BOX"
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
        if (ScreenCaptureService.isCapturing) {
            startService(ScreenCaptureService.stopIntent(this))
        }
    }

    /**
     * Punto de entrada crítico para la captura de eventos de botones físicos y joysticks.
     * Mapea códigos de tecla estándar de Android (KeyEvent) a las funciones lógicas del cursor.
     * En modo joystick el VR BOX manda el gatillo como BUTTON_L1 y el botón A como BUTTON_B.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isPress = event.action == KeyEvent.ACTION_DOWN
        val isFirstPress = isPress && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (event.repeatCount == 0) JoystickManager.setDpad(event.keyCode, isPress)

            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (isFirstPress) JoystickManager.performClick()

            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
                if (isFirstPress) JoystickManager.performBack()

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
     * Simula un toque físico ("tap") programáticamente en las coordenadas proporcionadas.
     * Utiliza la API de gestos de Accesibilidad para interactuar con otras aplicaciones en primer plano.
     * El toque atraviesa la ventana estéreo, que no recibe toques, y llega a la app capturada.
     */
    fun clickAt(x: Float, y: Float) {
        // Crea una trayectoria de un solo punto
        val path = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        
        // StrokeDescription define el inicio (0ms) y la duración (50ms) de la pulsación
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 50)
        gestureBuilder.addStroke(strokeDescription)
        
        // Despacha el gesto de forma asíncrona
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(CAPTURE_TAG, "click completado en ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(CAPTURE_TAG, "click cancelado en ($x, $y)")
            }
        }, null)
    }
    
    /**
     * Inyecta la acción global del sistema equivalente a presionar el botón de "Atrás".
     * Funciona independientemente de la jerarquía de vistas de la aplicación actual.
     */
    fun performBackAction() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
}
