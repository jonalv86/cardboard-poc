package com.pps.cardboardpoc

import kotlin.math.abs
import kotlin.math.roundToInt

/** Niveles de zoom que recorre el botón de la [ControlBar]. */
object ZoomLevels {

    /** Niveles en el orden en que se recorren; el primero es sin zoom. */
    val STEPS = listOf(1f, 1.5f, 2f)

    /** Nivel que sigue a [current]; después del último, o de uno mayor, se vuelve al primero. */
    fun next(current: Float): Float = STEPS.firstOrNull { it > current + 0.01f } ?: STEPS.first()

    /** Nivel siguiente más alto que [current]; desde el último, o uno mayor, se queda en el último. */
    fun higher(current: Float): Float = STEPS.firstOrNull { it > current + 0.01f } ?: STEPS.last()

    /** Nivel anterior más bajo que [current]; desde el primero se queda en el primero. */
    fun lower(current: Float): Float = STEPS.lastOrNull { it < current - 0.01f } ?: STEPS.first()

    /** Posición en [STEPS] del nivel más cercano a [level], para mostrarlo en el botón. */
    fun indexOf(level: Float): Int = STEPS.indices.minBy { abs(STEPS[it] - level) }
}

/**
 * Cómo entra la imagen capturada en cada ojo sin zoom, en medios tamaños de coordenadas del ojo (1 = el
 * ojo entero), y cuánto más alto puede ocupar con zoom.
 */
data class ZoomFit(
    /** Medio ancho de la imagen sin zoom. */
    val scaleX: Float,
    /** Medio alto de la imagen sin zoom. */
    val scaleY: Float,
    /**
     * Medio alto hasta el que puede crecer la imagen con zoom: más arriba o más abajo queda fuera de lo
     * que se ve a través de la lente. Nunca recorta la imagen sin zoom.
     */
    val maxHalfHeight: Float
) {
    companion object {
        /** Imagen que llena el ojo; vale hasta que el render informa cómo entra la real. */
        val FULL = ZoomFit(1f, 1f, 1f)
    }
}

/**
 * Parte de la pantalla capturada que se ve en el visor. Con zoom la imagen se agranda hasta llenar el
 * lugar que tiene en el ojo ([ZoomFit]), lo que no entra queda afuera, y la ventana recorre la pantalla
 * siguiendo al cursor ([follow]). Con zoom 1 es la pantalla entera.
 *
 * El cursor y los gestos siguen en píxeles de la pantalla: el zoom cambia solo qué se dibuja y dónde. La
 * UI del visor va en píxeles de la vista, que son los de la ventana agrandados [zoom] veces, con el
 * origen arriba a la izquierda de la imagen visible: así la UI no se agranda con la imagen. Sin zoom los
 * dos coinciden.
 */
class ZoomWindow private constructor(
    /** Ancho de la pantalla capturada, en píxeles. */
    val screenWidth: Int,
    /** Alto de la pantalla capturada, en píxeles. */
    val screenHeight: Int,
    /** Cómo entra la imagen en el ojo sin zoom. */
    val fit: ZoomFit,
    /** Cuántas veces se agranda la imagen respecto de como se ve sin zoom. */
    val zoom: Float,
    /** Medio ancho de la imagen visible en el ojo (1 = el ojo entero). */
    val quadHalfWidth: Float,
    /** Medio alto de la imagen visible en el ojo (1 = el ojo entero). */
    val quadHalfHeight: Float,
    /** Ancho de la ventana como fracción del ancho de la pantalla. */
    val fractionWidth: Float,
    /** Alto de la ventana como fracción del alto de la pantalla. */
    val fractionHeight: Float,
    /** Centro horizontal de la ventana como fracción del ancho de la pantalla. */
    private val centerFractionX: Float,
    /** Centro vertical de la ventana como fracción del alto de la pantalla. */
    private val centerFractionY: Float
) {
    /** Borde izquierdo de la ventana como fracción del ancho de la pantalla. */
    val fractionLeft: Float get() = centerFractionX - fractionWidth / 2f

    /** Borde de arriba de la ventana como fracción del alto de la pantalla. */
    val fractionTop: Float get() = centerFractionY - fractionHeight / 2f

    /** Ancho de la imagen visible, en píxeles de la vista. */
    val viewWidth: Float get() = fractionWidth * screenWidth * zoom

    /** Alto de la imagen visible, en píxeles de la vista. */
    val viewHeight: Float get() = fractionHeight * screenHeight * zoom

    /** [viewWidth] redondeado, para lo que se ubica en píxeles enteros, como la [ControlBar]. */
    val viewWidthPx: Int get() = viewWidth.roundToInt()

    /** [viewHeight] redondeado, para lo que se ubica en píxeles enteros, como la [ControlBar]. */
    val viewHeightPx: Int get() = viewHeight.roundToInt()

    /** Centro horizontal de la ventana, en píxeles de pantalla. */
    val centerX: Float get() = centerFractionX * screenWidth

    /** Pasa una posición horizontal de píxeles de pantalla a píxeles de la vista. */
    fun toViewX(x: Float): Float = (x - fractionLeft * screenWidth) * zoom

    /** Pasa una posición vertical de píxeles de pantalla a píxeles de la vista. */
    fun toViewY(y: Float): Float = (y - fractionTop * screenHeight) * zoom

    /**
     * Ventana que sigue al cursor en ([cursorX], [cursorY]), en píxeles de pantalla. El cursor se mueve
     * libre dentro de un recuadro central que ocupa la fracción [deadZone] de la ventana en cada eje; al
     * salir de él, la ventana se corre lo justo para alcanzarlo. Con 0 la ventana queda centrada en el
     * cursor, y con 1 se mueve solo cuando el cursor empuja su borde. Nunca se sale de la pantalla.
     */
    fun follow(cursorX: Float, cursorY: Float, deadZone: Float): ZoomWindow {
        if (screenWidth == 0 || screenHeight == 0) return this
        val x = followAxis(centerFractionX, cursorX / screenWidth, fractionWidth, deadZone)
        val y = followAxis(centerFractionY, cursorY / screenHeight, fractionHeight, deadZone)
        if (x == centerFractionX && y == centerFractionY) return this
        return of(screenWidth, screenHeight, fit, zoom, x, y)
    }

    /**
     * Ventana con otro tamaño de pantalla, encaje o zoom. Si cambia el zoom, el punto de la pantalla en
     * ([anchorX], [anchorY]) queda en el mismo lugar de la vista; si no, la ventana conserva su centro
     * relativo.
     */
    fun rebuilt(
        screenWidth: Int,
        screenHeight: Int,
        fit: ZoomFit,
        zoom: Float,
        anchorX: Float,
        anchorY: Float
    ): ZoomWindow {
        val kept = of(screenWidth, screenHeight, fit, zoom, centerFractionX, centerFractionY)
        if (kept.zoom == this.zoom || screenWidth == 0 || screenHeight == 0) return kept
        // La vista está centrada en el ojo: para que el punto no se mueva, su distancia al centro de la
        // ventana, multiplicada por el zoom, tiene que dar lo mismo antes y después.
        val ratio = this.zoom / kept.zoom
        val anchorFractionX = anchorX / screenWidth
        val anchorFractionY = anchorY / screenHeight
        return of(
            screenWidth, screenHeight, fit, zoom,
            anchorFractionX - (anchorFractionX - centerFractionX) * ratio,
            anchorFractionY - (anchorFractionY - centerFractionY) * ratio
        )
    }

    /** La misma ventana centrada en la pantalla. */
    fun centered(): ZoomWindow = of(screenWidth, screenHeight, fit, zoom)

    override fun toString(): String =
        "ZoomWindow(zoom=$zoom, ojo=${quadHalfWidth}x$quadHalfHeight, " +
            "ventana=[$fractionLeft, $fractionTop, ${fractionWidth}x$fractionHeight])"

    companion object {
        /**
         * Ventana de una pantalla de [screenWidth] x [screenHeight] con [zoom], centrada en la fracción
         * ([centerFractionX], [centerFractionY]) de la pantalla o lo más cerca que permitan sus bordes.
         */
        fun of(
            screenWidth: Int,
            screenHeight: Int,
            fit: ZoomFit,
            zoom: Float,
            centerFractionX: Float = 0.5f,
            centerFractionY: Float = 0.5f
        ): ZoomWindow {
            val effectiveZoom = zoom.coerceAtLeast(1f)
            val halfWidth = minOf(fit.scaleX * effectiveZoom, 1f)
            val halfHeight = minOf(fit.scaleY * effectiveZoom, maxOf(fit.maxHalfHeight, fit.scaleY))
            val width = (halfWidth / (fit.scaleX * effectiveZoom)).coerceAtMost(1f)
            val height = (halfHeight / (fit.scaleY * effectiveZoom)).coerceAtMost(1f)
            return ZoomWindow(
                screenWidth, screenHeight, fit, effectiveZoom, halfWidth, halfHeight, width, height,
                clampCenter(centerFractionX, width), clampCenter(centerFractionY, height)
            )
        }

        private fun followAxis(center: Float, cursor: Float, size: Float, deadZone: Float): Float {
            val reach = size / 2f * deadZone.coerceIn(0f, 1f)
            return when {
                cursor > center + reach -> cursor - reach
                cursor < center - reach -> cursor + reach
                else -> center
            }
        }

        private fun clampCenter(center: Float, size: Float): Float =
            if (size >= 1f) 0.5f else center.coerceIn(size / 2f, 1f - size / 2f)
    }
}
