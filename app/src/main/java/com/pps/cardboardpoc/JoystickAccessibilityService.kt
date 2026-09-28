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
    
    /**
     * Limpia las referencias si el servicio es interrumpido por el sistema o desactivado por el usuario.
     */
    override fun onInterrupt() {
        Log.d(TAG, "Servicio de Accesibilidad Interrumpido")
        JoystickManager.accessibilityService = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Servicio de Accesibilidad Destruido")
        JoystickManager.accessibilityService = null
    }
    
    /**
     * Punto de entrada crítico para la captura de eventos de botones físicos y joysticks.
     * Mapea códigos de tecla estándar de Android (KeyEvent) a las funciones lógicas del cursor.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Procesamos solo la pulsación inicial para evitar repeticiones accidentales o rebotes
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Navegación direccional (D-Pad o Stick analógico configurado como digital)
                KeyEvent.KEYCODE_DPAD_UP -> {
                    JoystickManager.moveUp()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    JoystickManager.moveDown()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    JoystickManager.moveLeft()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    JoystickManager.moveRight()
                    return true
                }
                
                // Confirmación / Acción primaria (Botón A, gatillos o centro del D-Pad)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    JoystickManager.performClick()
                    return true
                }
                
                // Navegación hacia atrás (Botón B o botón físico Back)
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                    JoystickManager.performBack()
                    return true
                }
            }
        }
        // Devuelve false (o llama a super) para permitir que el sistema procese teclas no manejadas
        return super.onKeyEvent(event)
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
                Log.d(TAG, "Gesto de click completado exitosamente en ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "Gesto de click cancelado (posible conflicto con otro gesto o overlay)")
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
