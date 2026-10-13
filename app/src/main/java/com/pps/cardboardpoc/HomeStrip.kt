package com.pps.cardboardpoc

/**
 * Franja de la escena debajo de la imagen capturada, con el botón "Inicio" que vuelve al panel de apps.
 * No es parte de la pantalla del teléfono: el cursor puede bajar hasta ella, pero nada de lo que pasa
 * ahí llega a la app capturada. Las medidas están en píxeles de la pantalla capturada, las mismas
 * unidades que el cursor; la franja ocupa de `screenHeight` a `screenHeight + HEIGHT_PX`.
 */
object HomeStrip {

    /** Alto de la franja. */
    const val HEIGHT_PX = 180f

    /** Ancho del botón "Inicio", centrado en la franja. */
    const val BUTTON_WIDTH_PX = 360f

    /** Alto del botón "Inicio", centrado en la franja. */
    const val BUTTON_HEIGHT_PX = 120f

    /** Si la altura [y] cae en la franja, para una pantalla de [screenHeight]. */
    fun contains(y: Float, screenHeight: Int): Boolean = y > screenHeight

    /** Si el punto cae sobre el botón "Inicio", para una pantalla de [screenWidth] x [screenHeight]. */
    fun isOnButton(x: Float, y: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val left = (screenWidth - BUTTON_WIDTH_PX) / 2f
        val top = screenHeight + (HEIGHT_PX - BUTTON_HEIGHT_PX) / 2f
        return x >= left && x <= left + BUTTON_WIDTH_PX && y >= top && y <= top + BUTTON_HEIGHT_PX
    }
}
