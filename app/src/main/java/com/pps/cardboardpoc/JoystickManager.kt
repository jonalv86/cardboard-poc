package com.pps.cardboardpoc

import android.util.Log

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

    // Cantidad de píxeles que se desplaza el cursor por cada interrupción o evento del D-pad
    var moveStep = 30f
    
    // Sensibilidad máxima para el movimiento analógico continuado (píxeles por actualización)
    var analogSpeed = 35f
    
    // Umbral de zona muerta para contrarrestar el drift natural de los sticks analógicos
    var deadZone = 0.15f
    
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
    
    /** Reducen o aumentan los ejes acotando la posición entre 0 y el ancho/alto máximo de la pantalla */
    fun moveUp() {
        cursorY = (cursorY - moveStep).coerceIn(0f, screenHeight.toFloat())
        Log.d(TAG, "Mover Arriba: ($cursorX, $cursorY)")
    }
    
    fun moveDown() {
        cursorY = (cursorY + moveStep).coerceIn(0f, screenHeight.toFloat())
        Log.d(TAG, "Mover Abajo: ($cursorX, $cursorY)")
    }
    
    fun moveLeft() {
        cursorX = (cursorX - moveStep).coerceIn(0f, screenWidth.toFloat())
        Log.d(TAG, "Mover Izquierda: ($cursorX, $cursorY)")
    }
    
    fun moveRight() {
        cursorX = (cursorX + moveStep).coerceIn(0f, screenWidth.toFloat())
        Log.d(TAG, "Mover Derecha: ($cursorX, $cursorY)")
    }
    
    /**
     * Mueve el cursor de forma continua y proporcional utilizando los valores analógicos
     * recibidos del stick del joystick, aplicando una zona muerta para evitar drifts.
     */
    fun moveAnalog(axisX: Float, axisY: Float) {
        var updated = false
        if (Math.abs(axisX) > deadZone) {
            cursorX = (cursorX + (axisX * analogSpeed)).coerceIn(0f, screenWidth.toFloat())
            updated = true
        }
        if (Math.abs(axisY) > deadZone) {
            cursorY = (cursorY + (axisY * analogSpeed)).coerceIn(0f, screenHeight.toFloat())
            updated = true
        }
        if (updated) {
            Log.d(TAG, "Movimiento Analógico: Ejes($axisX, $axisY) -> Cursor($cursorX, $cursorY)")
        }
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
