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
    }
    
    /**
     * Vincula el servicio activo al JoystickManager al momento de la conexión para permitir
     * el despacho de comandos de entrada.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Servicio de Accesibilidad Conectado")
        JoystickManager.accessibilityService = this
        
        val info = serviceInfo
        // FLAG_SEND_MOTION_EVENTS (API 31+) permite recibir eventos de movimiento en onMotionEvent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.flags = info.flags or 0x00004000 // 0x00004000 es FLAG_SEND_MOTION_EVENTS
            
            // Usamos reflexión para configurar motionEventSources (API 33+) y evitar errores de compilación por API levels
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val method = info.javaClass.getMethod("setMotionEventSources", Int::class.javaPrimitiveType)
                    method.invoke(info, InputDevice.SOURCE_JOYSTICK)
                } catch (e: Exception) {
                    Log.e(TAG, "Error configurando motionEventSources vía reflexión", e)
                }
            }
        }
        this.serviceInfo = info
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
            KeyEvent.KEYCODE_DPAD_UP -> if (isPress) JoystickManager.moveUp()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isPress) JoystickManager.moveDown()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isPress) JoystickManager.moveLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isPress) JoystickManager.moveRight()

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
     * Captura eventos de movimiento analógicos continuos de joysticks vinculados globales.
     * Disponible a partir de Android 12 (API 31+).
     */
    override fun onMotionEvent(event: MotionEvent) {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            val axisX = event.getAxisValue(MotionEvent.AXIS_X)
            val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
            JoystickManager.moveAnalog(axisX, axisY)
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
