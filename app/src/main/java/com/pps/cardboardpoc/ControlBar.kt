package com.pps.cardboardpoc

import android.view.Display
import kotlin.math.hypot

/**
 * Barra de controles flotante: una píldora centrada abajo, encima de la imagen capturada, con un botón
 * circular por cada entrada de [buttons]. Está oculta hasta que el cursor llega al borde de abajo, así
 * no tapa lo que la app tenga detrás. Mientras está a la vista, lo que cae sobre la píldora no llega a
 * la app capturada. Las medidas están en píxeles de la pantalla capturada, las mismas unidades que el
 * cursor.
 */
object ControlBar {

    /** Botones que puede llevar la barra. */
    enum class Button {
        /** Vuelve al panel de apps. */
        HOME,

        /** Pasa la app a vertical, o la devuelve a horizontal. */
        PORTRAIT
    }

    /**
     * Botones de la barra, de izquierda a derecha. Vertical solo aparece con la pantalla virtual, la
     * única que se puede poner en vertical, y cuando la orientación se puede elegir: no sobre el
     * panel, que siempre se ve en horizontal, ni sobre una app que solo funciona en una orientación.
     */
    val buttons: List<Button>
        get() = if (JoystickManager.targetDisplayId != Display.DEFAULT_DISPLAY &&
            !PanelActivity.isInFront && !ScreenCaptureService.frontAppOrientationLocked
        ) {
            WITH_PORTRAIT
        } else {
            HOME_ONLY
        }

    private val HOME_ONLY = listOf(Button.HOME)
    private val WITH_PORTRAIT = listOf(Button.HOME, Button.PORTRAIT)

    /** Diámetro de cada botón. */
    const val BUTTON_DIAMETER_PX = 112f

    /** Separación entre botones. */
    const val BUTTON_GAP_PX = 16f

    /** Distancia entre los botones y el borde de la píldora. */
    const val PADDING_PX = 12f

    /** Distancia entre la píldora y el borde de abajo de la pantalla. */
    const val BOTTOM_MARGIN_PX = 40f

    /** Alto de la zona pegada al borde de abajo en la que aparece la barra. */
    const val REVEAL_ZONE_PX = 48f

    /** Margen alrededor de la píldora dentro del cual la barra, ya visible, no se oculta. */
    const val KEEP_MARGIN_PX = 24f

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

    /** Píldora de la barra con [buttons], para una pantalla de [screenWidth] x [screenHeight]. */
    fun bounds(screenWidth: Int, screenHeight: Int, buttons: List<Button> = this.buttons): Box {
        val count = buttons.size
        val width = count * BUTTON_DIAMETER_PX + (count - 1) * BUTTON_GAP_PX + 2 * PADDING_PX
        val height = BUTTON_DIAMETER_PX + 2 * PADDING_PX
        val left = (screenWidth - width) / 2f
        val bottom = screenHeight - BOTTOM_MARGIN_PX
        return Box(left, bottom - height, left + width, bottom)
    }

    /** Centro horizontal del botón en la posición [index] de [buttons]. */
    fun buttonCenterX(
        index: Int,
        screenWidth: Int,
        screenHeight: Int,
        buttons: List<Button> = this.buttons
    ): Float =
        bounds(screenWidth, screenHeight, buttons).left + PADDING_PX + BUTTON_DIAMETER_PX / 2f +
            index * (BUTTON_DIAMETER_PX + BUTTON_GAP_PX)

    /** Si el punto cae sobre la píldora con [buttons]. */
    fun contains(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        buttons: List<Button> = this.buttons
    ): Boolean = bounds(screenWidth, screenHeight, buttons).contains(x, y)

    /** Botón de [buttons] bajo el punto, o null si el punto no cae sobre ningún círculo de botón. */
    fun buttonAt(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        buttons: List<Button> = this.buttons
    ): Button? {
        val centerY = bounds(screenWidth, screenHeight, buttons).centerY
        val radius = BUTTON_DIAMETER_PX / 2f
        return buttons.withIndex().firstOrNull { (index, _) ->
            hypot(x - buttonCenterX(index, screenWidth, screenHeight, buttons), y - centerY) <= radius
        }?.value
    }

    /**
     * Si la barra queda a la vista con el cursor en el punto, sabiendo si ya lo estaba. Aparece en la
     * zona de [REVEAL_ZONE_PX] junto al borde de abajo, y una vez visible se queda mientras el cursor
     * siga sobre la píldora o a menos de [KEEP_MARGIN_PX] de ella: así se puede subir hasta los botones
     * sin que se esconda. La píldora es la que corresponde a [buttons].
     */
    fun nextVisible(
        visible: Boolean,
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        buttons: List<Button> = this.buttons
    ): Boolean {
        if (y >= screenHeight - REVEAL_ZONE_PX) return true
        return visible && bounds(screenWidth, screenHeight, buttons).distanceTo(x, y) <= KEEP_MARGIN_PX
    }
}
