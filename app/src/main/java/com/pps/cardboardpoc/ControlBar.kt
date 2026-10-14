package com.pps.cardboardpoc

import kotlin.math.hypot

/**
 * Barra de controles flotante: una píldora centrada abajo, encima de la imagen capturada, con un botón
 * circular por cada entrada de [buttons]. Lo que cae sobre la píldora no llega a la app capturada. Las
 * medidas están en píxeles de la pantalla capturada, las mismas unidades que el cursor.
 */
object ControlBar {

    /** Botones que puede llevar la barra. */
    enum class Button { HOME }

    /** Botones de la barra, de izquierda a derecha. */
    val buttons: List<Button> = listOf(Button.HOME)

    /** Diámetro de cada botón. */
    const val BUTTON_DIAMETER_PX = 112f

    /** Separación entre botones. */
    const val BUTTON_GAP_PX = 16f

    /** Distancia entre los botones y el borde de la píldora. */
    const val PADDING_PX = 12f

    /** Distancia entre la píldora y el borde de abajo de la pantalla. */
    const val BOTTOM_MARGIN_PX = 40f

    /** Distancia al cursor hasta la que la barra se ve con opacidad completa. */
    const val NEAR_DISTANCE_PX = 80f

    /** Distancia al cursor desde la que la barra queda atenuada del todo. */
    const val FAR_DISTANCE_PX = 400f

    /** Rectángulo en píxeles de pantalla. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** Si el punto cae dentro del rectángulo, bordes incluidos. */
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

        /** Distancia del punto al rectángulo; 0 si cae adentro. */
        fun distanceTo(x: Float, y: Float): Float =
            hypot(maxOf(left - x, 0f, x - right), maxOf(top - y, 0f, y - bottom))
    }

    /** Píldora de la barra, para una pantalla de [screenWidth] x [screenHeight]. */
    fun bounds(screenWidth: Int, screenHeight: Int): Box {
        val count = buttons.size
        val width = count * BUTTON_DIAMETER_PX + (count - 1) * BUTTON_GAP_PX + 2 * PADDING_PX
        val height = BUTTON_DIAMETER_PX + 2 * PADDING_PX
        val left = (screenWidth - width) / 2f
        val bottom = screenHeight - BOTTOM_MARGIN_PX
        return Box(left, bottom - height, left + width, bottom)
    }

    /** Centro horizontal del botón en la posición [index] de [buttons]. */
    fun buttonCenterX(index: Int, screenWidth: Int, screenHeight: Int): Float =
        bounds(screenWidth, screenHeight).left + PADDING_PX + BUTTON_DIAMETER_PX / 2f +
            index * (BUTTON_DIAMETER_PX + BUTTON_GAP_PX)

    /** Si el punto cae sobre la píldora. */
    fun contains(x: Float, y: Float, screenWidth: Int, screenHeight: Int): Boolean =
        bounds(screenWidth, screenHeight).contains(x, y)

    /** Botón bajo el punto, o null si el punto no cae sobre ningún círculo de botón. */
    fun buttonAt(x: Float, y: Float, screenWidth: Int, screenHeight: Int): Button? {
        val centerY = bounds(screenWidth, screenHeight).centerY
        val radius = BUTTON_DIAMETER_PX / 2f
        return buttons.withIndex().firstOrNull { (index, _) ->
            hypot(x - buttonCenterX(index, screenWidth, screenHeight), y - centerY) <= radius
        }?.value
    }

    /**
     * Cercanía del punto a la píldora: 1 dentro de [NEAR_DISTANCE_PX], 0 más allá de
     * [FAR_DISTANCE_PX] y una rampa lineal entre las dos.
     */
    fun proximity(x: Float, y: Float, screenWidth: Int, screenHeight: Int): Float {
        val distance = bounds(screenWidth, screenHeight).distanceTo(x, y)
        return (1f - (distance - NEAR_DISTANCE_PX) / (FAR_DISTANCE_PX - NEAR_DISTANCE_PX))
            .coerceIn(0f, 1f)
    }
}
